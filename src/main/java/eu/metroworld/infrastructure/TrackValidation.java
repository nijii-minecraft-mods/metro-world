package eu.metroworld.infrastructure;
import eu.metroworld.infrastructure.world.*;
import net.minecraft.block.*;
import net.minecraft.block.enums.RailShape;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.*;
import java.util.*;
/** Validate actual generated blocks on the central east/west and transverse connections, not just its plan. */
public final class TrackValidation {
 /** Rail placement depends on the support face, not the redstone-conduction predicate. */
 private static boolean supported(net.minecraft.world.WorldView world,BlockPos at,BlockState state){
  if(!(state.getBlock() instanceof AbstractRailBlock)||!state.canPlaceAt(world,at))return false;
  RailShape shape=state.isOf(Blocks.POWERED_RAIL)?state.get(PoweredRailBlock.SHAPE):state.get(RailBlock.SHAPE);
  Direction uphill=switch(shape){case ASCENDING_EAST->Direction.EAST;case ASCENDING_WEST->Direction.WEST;case ASCENDING_NORTH->Direction.NORTH;case ASCENDING_SOUTH->Direction.SOUTH;default->null;};
  return uphill==null||Block.sideCoversSmallSquare(world,at.offset(uphill),Direction.UP);
 }
 public static int run(ServerCommandSource source){
  var world=source.getServer().getWorld(Transit.UNDERGROUND);
  if(world==null){source.sendError(Text.literal("TRACKS_FAILED: metro dimension unavailable"));return 0;}
  int checked=0,errors=0,powered=0;String first="";var visited=new HashSet<BlockPos>();
  var candidates=StationGraph.nearbyEdges(world.getSeed(),source.getPosition().x,source.getPosition().z);
  candidates.sort(Comparator.comparingDouble(e->Math.hypot((e.a().node().x()+e.b().node().x())*.5-source.getPosition().x,(e.a().node().z()+e.b().node().z())*.5-source.getPosition().z)));
  var edges=new ArrayList<StationGraph.Edge>();boolean foundSpiral=false,foundSplit=false,foundIsland=false;
  for(var edge:candidates){
   var a=StationGraph.railNode(edge.a(),edge.northSouth());var b=StationGraph.railNode(edge.b(),edge.northSouth());
   boolean spiral=NetworkPlan.spiralEntry(world.getSeed(),edge)!=null,split=Math.abs(a.y()-b.y())<32&&Math.floorMod(edge.salt(),5)==0;
   boolean island=NetworkPlan.station(world.getSeed(),edge.a(),edge.northSouth()).trackOffset()>0||NetworkPlan.station(world.getSeed(),edge.b(),edge.northSouth()).trackOffset()>0;
   if(edges.size()<2||spiral&&!foundSpiral||split&&!foundSplit||island&&!foundIsland){edges.add(edge);foundSpiral|=spiral;foundSplit|=split;foundIsland|=island;}
   if(edges.size()>=5)break;
  }
  if(edges.isEmpty()){source.sendError(Text.literal("TRACKS_FAILED: no accessible edges near validation origin"));return 0;}
  var routes=new ArrayList<NetworkPlan.TrackRoute>();for(var edge:edges)routes.addAll(NetworkPlan.trackRoutes(world.getSeed(),edge));
  for(var route:routes)for(var lane:(route.singleTrack()?RailPlan.ofSingle(route.route()):RailPlan.of(route.route())).lanes())for(var cell:lane){
   var at=new BlockPos(cell.x(),cell.floor()+1,cell.z());if(!visited.add(at))continue;
   world.getChunk(Math.floorDiv(at.getX(),16),Math.floorDiv(at.getZ(),16));
   var state=world.getBlockState(at);String problem=null;
   if(!(state.getBlock() instanceof AbstractRailBlock))problem="missing rail";
   else if(!supported(world,at,state))problem="unsupported rail; below="+net.minecraft.registry.Registries.BLOCK.getId(world.getBlockState(at.down()).getBlock());
   else if(!world.getBlockState(at.up()).isAir())problem="blocked headroom";
   else {
    int neighbours=0;for(var d:new Direction[]{Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST}){
     var next=at.offset(d);if(world.getBlockState(next).getBlock() instanceof AbstractRailBlock||world.getBlockState(next.up()).getBlock() instanceof AbstractRailBlock||world.getBlockState(next.down()).getBlock() instanceof AbstractRailBlock)neighbours++;
    }
    // Junctions have selectable active legs; ordinary track cells must match their chain.
    if(neighbours<=2){
     RailShape shape=state.isOf(Blocks.POWERED_RAIL)?state.get(PoweredRailBlock.SHAPE):state.get(RailBlock.SHAPE);
     if(!shape.asString().equals(cell.shape()))problem="wrong rail shape "+shape.asString()+" expected "+cell.shape();
    }
   }
   checked++;if(state.isOf(Blocks.POWERED_RAIL))powered++;if(problem!=null){errors++;if(first.isEmpty())first=at.toShortString()+": "+problem;}
  }
  // Check the two halls at each end, their rail throats and physical exit terminals.
  for(var edge:edges)for(int end=0;end<=1;end++){
   boolean ns=edge.northSouth();var node=StationGraph.railNode(end==0?edge.a():edge.b(),ns);
   var plan=NetworkPlan.forChunk(world.getSeed(),Math.floorDiv(node.x(),16)*16,Math.floorDiv(node.z(),16)*16);
   var station=plan.stations().stream().filter(s->s.node().equals(node)&&s.northSouth()==ns).findFirst().orElseThrow();
   int length=NetworkPlan.halfLength(station.kind()),width=NetworkPlan.halfWidth(station.kind());
   for(int offset=-length;offset<=length;offset++)for(int lane:new int[]{-(3+station.trackOffset()),3+station.trackOffset()}){
    var at=new BlockPos(node.x()+(ns?-lane:offset),node.y()+1,node.z()+(ns?offset:lane));
    world.getChunk(Math.floorDiv(at.getX(),16),Math.floorDiv(at.getZ(),16));
    if(!(world.getBlockState(at).getBlock() instanceof AbstractRailBlock)||!world.getBlockState(at.up()).isAir()||!supported(world,at,world.getBlockState(at))){
     errors++;if(first.isEmpty())first=at.toShortString()+": station throat blocked";
    }
    checked++;
   }
   var terminal=new BlockPos(node.x()+(ns?-(width+8):-10),node.y()+3,node.z()+(ns?-10:width+8));
   world.getChunk(Math.floorDiv(terminal.getX(),16),Math.floorDiv(terminal.getZ(),16));
   if(!world.getBlockState(terminal).isOf(InfrastructureBlocks.LIFT)){errors++;if(first.isEmpty())first=terminal.toShortString()+": missing exit";}
   for(int side=width-3;side<width+8;side++){
    var feet=new BlockPos(node.x()+(ns?-side:-10),node.y()+3,node.z()+(ns?-10:side));
    world.getChunk(Math.floorDiv(feet.getX(),16),Math.floorDiv(feet.getZ(),16));
    if(!world.getBlockState(feet).isAir()||!world.getBlockState(feet.up()).isAir()||!world.getBlockState(feet.down()).isSideSolidFullSquare(world,feet.down(),Direction.UP)){
     errors++;if(first.isEmpty())first=feet.toShortString()+": blocked exit concourse";
    }
   }
  }
  if(errors>0){source.sendError(Text.literal("TRACKS_FAILED: "+errors+" / "+checked+"; "+first));return 0;}
  int count=checked,powerCount=powered;source.sendFeedback(()->Text.literal("TRACKS_OK: "+count+" generated rails ("+powerCount+" powered); placement support, headroom and shapes verified"),false);return count;
 }
}
