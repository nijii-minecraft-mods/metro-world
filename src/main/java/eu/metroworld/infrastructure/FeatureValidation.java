package eu.metroworld.infrastructure;

import eu.metroworld.infrastructure.world.DomeDesign;
import eu.metroworld.infrastructure.world.BotanicalAnnex;
import eu.metroworld.infrastructure.world.NetworkPlan;
import eu.metroworld.infrastructure.world.StationGraph;
import eu.metroworld.infrastructure.world.SideBranchDesign;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/** Checks generated blocks and resolves an actual loot table; does not impersonate a player. */
public final class FeatureValidation {
 private FeatureValidation() {}
 private static final class Checks {
  int count,failures;String first="";
  void require(boolean valid,String description){count++;if(!valid){failures++;if(first.isEmpty())first=description;}}
 }
 private static void load(ServerWorld world,BlockPos pos){world.getChunk(Math.floorDiv(pos.getX(),16),Math.floorDiv(pos.getZ(),16));}
 public static int run(ServerCommandSource source){
  ServerWorld world=source.getServer().getWorld(Transit.UNDERGROUND);Checks checks=new Checks();
  if(world==null){source.sendError(Text.literal("FEATURES_FAILED: metro dimension unavailable"));return 0;}
  try{
   var v=StationGraph.nearestPortalVertex(world.getSeed(),0,0);
   var flags=NetworkPlan.halls(world.getSeed(),v);
   boolean ns=!flags[0]&&flags[1];
   var hall=NetworkPlan.forChunk(world.getSeed(),Math.floorDiv(v.node().x(),16)*16,Math.floorDiv(v.node().z(),16)*16).stations().stream()
     .filter(s->s.node().equals(StationGraph.railNode(v,ns))&&s.northSouth()==ns).findFirst().orElseThrow(()->new IllegalStateException("portal has no hall"));
   var pnode=hall.node();
   BlockPos arrival=new BlockPos(pnode.x()+(ns?-8:2),pnode.y()+3,pnode.z()+(ns?2:8));load(world,arrival);
   checks.require(Transit.safeFeet(world,arrival),"unsafe portal arrival "+arrival.toShortString());
   int width=NetworkPlan.halfWidth(hall.kind());
   BlockPos terminal=ns?new BlockPos(pnode.x()-(width+8),pnode.y()+3,pnode.z()-10):new BlockPos(pnode.x()-10,pnode.y()+3,pnode.z()+width+8);load(world,terminal);
   checks.require(world.getBlockState(terminal).isOf(InfrastructureBlocks.LIFT),"missing portal return terminal "+terminal.toShortString());
   // Walkway leading up to the physical return terminal, not through the terminal block itself.
   for(int z=width-3;z<width+8;z++){
    BlockPos feet=ns?new BlockPos(pnode.x()-z,pnode.y()+3,pnode.z()-10):new BlockPos(pnode.x()-10,pnode.y()+3,pnode.z()+z);load(world,feet);
    checks.require(Transit.safeFeet(world,feet),"blocked return concourse "+feet.toShortString());
   }
   var aquarium=NetworkPlan.nearestStation(world.getSeed(),0,0,"aquarium");
   if(aquarium==null)throw new IllegalStateException("no accessible aquarium station near validation origin");
   var node=aquarium.node();
   var station=NetworkPlan.forChunk(world.getSeed(),Math.floorDiv(node.x(),16)*16,Math.floorDiv(node.z(),16)*16).stations().stream()
     .filter(s->s.node().equals(node)&&s.northSouth()==aquarium.northSouth()).findFirst().orElseThrow();
   var spec=DomeDesign.spec(station.salt());
   checks.require(station.kind()==NetworkPlan.StationKind.BIOCENTER&&spec.kind()==DomeDesign.Kind.AQUA&&!station.northSouth(),"aquarium locator does not identify an aqua dome");
   int centerX=node.x()+spec.offsetX(),centerZ=node.z()+spec.offsetZ();
   for(int z=centerZ;z<=node.z()-14;z+=4){
    BlockPos feet=new BlockPos(centerX,node.y()+3,z);load(world,feet);
    checks.require(Transit.safeFeet(world,feet),"blocked or flooded aquarium access "+feet.toShortString());
   }
   BlockPos water=new BlockPos(centerX+4,node.y()+4,centerZ+4);load(world,water);
   checks.require(world.getFluidState(water).isIn(FluidTags.WATER),"aquarium tank has no water "+water.toShortString());
   BlockPos chest=new BlockPos(centerX+spec.innerRadius()-5,node.y()+3,centerZ);load(world,chest);
   checks.require(world.getBlockState(chest).isOf(Blocks.CHEST),"missing aquarium reward chest "+chest.toShortString());
   checks.require(world.getBlockState(chest.up()).getCollisionShape(world,chest.up()).isEmpty(),"aquarium chest lid blocked");
   BlockPos approach=chest.add(0,0,1);load(world,approach);
   checks.require(Transit.safeFeet(world,approach),"aquarium reward chest approach flooded or obstructed");
   if(!(world.getBlockEntity(chest) instanceof LootableContainerBlockEntity container))throw new IllegalStateException("aquarium loot container block entity missing");
   var expected=RegistryKey.of(RegistryKeys.LOOT_TABLE,Identifier.of("btr_infrastructure","chests/aquarium"));
   // A second run can legitimately encounter the inventory already generated by this check.
   if(container.getLootTable()!=null){
    checks.require(container.getLootTable().equals(expected),"wrong aquarium loot table "+container.getLootTable());
    container.generateLoot(null);
   }
   int occupied=0;for(int slot=0;slot<container.size();slot++)if(!container.getStack(slot).isEmpty())occupied++;
   checks.require(occupied>0,"aquarium loot table resolved to an empty inventory");
   var annex=BotanicalAnnex.spec(station.salt());
   for(int z=-7;z<=annex.halfLength();z++){
    BlockPos feet=new BlockPos(node.x()+annex.offsetX(),node.y()+3,node.z()+annex.offsetZ()+z);load(world,feet);
    checks.require(Transit.safeFeet(world,feet),"botanical annex aisle blocked "+feet.toShortString());
   }
   BlockPos annexChest=new BlockPos(node.x()+annex.offsetX()+6,node.y()+3,node.z()+annex.offsetZ()-7);load(world,annexChest);
   checks.require(world.getBlockState(annexChest).isOf(Blocks.CHEST),"botanical annex reward chest missing");
   checks.require(world.getBlockState(annexChest.up()).getCollisionShape(world,annexChest.up()).isEmpty(),"botanical annex chest lid blocked");
   quarantine(world,checks);
  }catch(RuntimeException error){checks.require(false,error.getClass().getSimpleName()+": "+error.getMessage());}
  if(checks.failures>0){source.sendError(Text.literal("FEATURES_FAILED: "+checks.failures+" / "+checks.count+"; "+checks.first));return 0;}
  int count=checks.count;source.sendFeedback(()->Text.literal("FEATURES_OK: "+count+" generated-block checks; portal arrival, exit walkway, dry aquarium access, tank water, quarantine spawner block entities and resolved chest loot verified"),false);return count;
 }
 private static BlockPos local(NetworkPlan.Station station,int x,int y,int z){
  var n=station.node();return new BlockPos(n.x()+(station.northSouth()?-z:x),n.y()+y,n.z()+(station.northSouth()?x:z));
 }
 private static void quarantine(ServerWorld world,Checks checks){
  NetworkPlan.Station found=null;
  search: for(int radius=0;radius<=3;radius++)for(int rx=-radius;rx<=radius;rx++)for(int rz=-radius;rz<=radius;rz++){
   if(Math.max(Math.abs(rx),Math.abs(rz))!=radius)continue;
   for(var vertex:StationGraph.vertices(world.getSeed(),rx,rz)){
    if(!SideBranchDesign.present(vertex.salt()))continue;
    var edges=StationGraph.incident(world.getSeed(),vertex);if(edges.isEmpty())continue;
    boolean ns=!vertex.portal()&&edges.stream().noneMatch(e->!e.northSouth());
    var hall=NetworkPlan.station(world.getSeed(),vertex,ns);
    if(SideBranchDesign.select(hall.salt(),NetworkPlan.halfLength(hall.kind())).kind()!=SideBranchDesign.Kind.QUARANTINE)continue;
    var n=hall.node();found=NetworkPlan.forChunk(world.getSeed(),Math.floorDiv(n.x(),16)*16,Math.floorDiv(n.z(),16)*16).stations().stream()
      .filter(s->s.node().equals(n)&&s.northSouth()==ns).findFirst().orElseThrow(()->new IllegalStateException("quarantine hall absent from chunk plan"));
    break search;
   }
  }
  if(found==null)throw new IllegalStateException("no connected quarantine side branch near validation origin");
  for(int x:new int[]{-111,-100,-89}){
   BlockPos at=local(found,x,3,78);load(world,at);
   checks.require(world.getBlockState(at).isOf(Blocks.SPAWNER),"missing quarantine spawner "+at.toShortString());
   checks.require(world.getBlockEntity(at) instanceof MobSpawnerBlockEntity,"quarantine spawner block entity missing "+at.toShortString());
  }
  BlockPos floor=local(found,-100,3,70);load(world,floor);
  checks.require(Transit.safeFeet(world,floor),"quarantine room approach blocked or flooded "+floor.toShortString());
  BlockPos chest=local(found,-114,3,80);load(world,chest);
  checks.require(world.getBlockState(chest).isOf(Blocks.CHEST),"missing quarantine relic chest "+chest.toShortString());
  checks.require(world.getBlockState(chest.up()).getCollisionShape(world,chest.up()).isEmpty(),"quarantine relic chest lid blocked");
  if(!(world.getBlockEntity(chest) instanceof LootableContainerBlockEntity container))throw new IllegalStateException("quarantine relic loot block entity missing");
  var expected=RegistryKey.of(RegistryKeys.LOOT_TABLE,Identifier.of("btr_infrastructure","chests/relic_vault"));
  if(container.getLootTable()!=null){checks.require(container.getLootTable().equals(expected),"wrong quarantine relic loot table "+container.getLootTable());container.generateLoot(null);}
  int occupied=0;for(int slot=0;slot<container.size();slot++)if(!container.getStack(slot).isEmpty())occupied++;
  checks.require(occupied>0,"quarantine relic vault resolved to an empty inventory");
 }

}
