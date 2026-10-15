package eu.metroworld.infrastructure.world;
import eu.metroworld.infrastructure.InfrastructureBlocks;
import net.minecraft.block.*;
import net.minecraft.block.entity.*;
import net.minecraft.block.enums.*;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.*;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.*;
import net.minecraft.world.chunk.Chunk;
/** Rasterizes architecture and curved tubes before touching the chunk: explicit merge priorities. */
public final class NetworkBuilder {
 private static final BlockState AIR=Blocks.AIR.getDefaultState();
 public enum SignKind { STATION, SECRET, BOTANICAL, QUARANTINE }
 public record SignSpec(String title, SignKind kind) {}
 private static final class Canvas {
  final Chunk chunk;final int sx,sz,bottom,height;final BlockState[] states;final byte[] priorities;int minY=Integer.MAX_VALUE,maxY=Integer.MIN_VALUE;final java.util.Map<BlockPos,SignSpec> signs=new java.util.HashMap<>();final java.util.Map<BlockPos,String> loot=new java.util.HashMap<>();
  Canvas(Chunk chunk){this.chunk=chunk;sx=chunk.getPos().getStartX();sz=chunk.getPos().getStartZ();bottom=chunk.getBottomY();height=chunk.getHeight();states=new BlockState[height*256];priorities=new byte[states.length];}
  void sign(BlockPos pos,String title,SignKind kind){signs.put(pos,new SignSpec(title,kind));}
  void put(int x,int y,int z,BlockState state,int priority){if(x<sx||x>sx+15||z<sz||z>sz+15||y<bottom||y>=bottom+height)return;int i=(y-bottom)*256+(z-sz)*16+x-sx;if(priority>=priorities[i]){states[i]=state;priorities[i]=(byte)priority;if(y<minY)minY=y;if(y>maxY)maxY=y;}}
  BlockState peek(int x,int y,int z){if(x<sx||x>sx+15||z<sz||z>sz+15||y<bottom||y>=bottom+height)return null;return states[(y-bottom)*256+(z-sz)*16+x-sx];}
  void box(int x1,int x2,int y1,int y2,int z1,int z2,BlockState state,int priority){for(int x=Math.max(sx,x1);x<=Math.min(sx+15,x2);x++)for(int z=Math.max(sz,z1);z<=Math.min(sz+15,z2);z++)for(int y=y1;y<=y2;y++)put(x,y,z,state,priority);}
  void flush(long seed){if(minY>maxY)return;int startI=(minY-bottom)*256,endI=Math.min(states.length,(maxY-bottom+1)*256);BlockPos.Mutable pos=new BlockPos.Mutable();for(int i=startI;i<endI;i++){BlockState state=states[i];if(state==null)continue;int x=sx+(i&15),z=sz+((i>>>4)&15),y=bottom+(i>>>8);chunk.setBlockState(pos.set(x,y,z),state,false);
   if(state.isOf(Blocks.CHEST)||state.isOf(Blocks.BARREL)){
    LootableContainerBlockEntity container=state.isOf(Blocks.CHEST)?new ChestBlockEntity(pos.toImmutable(),state):new BarrelBlockEntity(pos.toImmutable(),state);
    String table=loot.getOrDefault(pos.toImmutable(),"maintenance");
    container.setLootTable(RegistryKey.of(RegistryKeys.LOOT_TABLE,Identifier.of("btr_infrastructure","chests/"+table)));
    container.setLootTableSeed(NetworkPlan.hash(seed,x,z,y));chunk.setBlockEntity(container);
   }
   if(state.isOf(Blocks.OAK_WALL_SIGN)){
    SignSpec spec=signs.getOrDefault(pos.toImmutable(),new SignSpec("Метро",SignKind.STATION));
    String name=spec.title();
    boolean secret=spec.kind()==SignKind.SECRET;
    boolean botanical=spec.kind()==SignKind.BOTANICAL;
    boolean quarantine=spec.kind()==SignKind.QUARANTINE;
    SignText text=new SignText().withMessage(0,net.minecraft.text.Text.literal(name))
     .withMessage(1,net.minecraft.text.Text.literal(quarantine?"Опасно: заражённые":secret?"Ветка закрыта":botanical?"Исследовательский сектор":"← Лифт / выход"))
     .withMessage(2,net.minecraft.text.Text.literal(quarantine||secret?"Осмотрите хранилище":botanical?"Назад → станция":"Переходы →")).withGlowing(true);
    net.minecraft.nbt.NbtCompound data=new net.minecraft.nbt.NbtCompound();
    data.putString("id","minecraft:sign");data.putInt("x",x);data.putInt("y",y);data.putInt("z",z);
    data.put("front_text",SignText.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE,text).getOrThrow());
    chunk.addPendingBlockEntityNbt(data);
   }
   if(state.isOf(Blocks.SPAWNER)){MobSpawnerBlockEntity spawner=new MobSpawnerBlockEntity(pos.toImmutable(),state);spawner.setEntityType(EntityType.ZOMBIE,net.minecraft.util.math.random.Random.create(NetworkPlan.hash(seed,x,z,y)));chunk.setBlockEntity(spawner);}
  }}
 }
 private static void shell(Canvas c,int x,int y,int z,double edge){if(edge>=-6&&edge<0)c.put(x,y,z,(edge>=-3?Blocks.STONE:edge>=-5?Blocks.OBSIDIAN:Blocks.BEDROCK).getDefaultState(),(int)Math.ceil(edge)+7);}
 public static void build(Chunk chunk,long seed){
  Canvas c=new Canvas(chunk);var plan=NetworkPlan.forChunk(seed,c.sx,c.sz);
  for(var room:plan.rooms())room(c,room);
  for(var path:plan.paths())path(c,path);
  for(var tower:plan.stairs())stairs(c,tower);
  for(var station:plan.stations())station(c,station,seed);
  // Rail corridors own their supports and headroom, including every station layout.
  for(var s:plan.stations())for(int x=-NetworkPlan.halfLength(s.kind());x<=NetworkPlan.halfLength(s.kind());x++)for(int z:new int[]{-(3+s.trackOffset()),3+s.trackOffset()}){
   localPut(c,s,x,0,z,Blocks.POLISHED_DEEPSLATE.getDefaultState(),60);
   for(int h=1;h<=3;h++)localPut(c,s,x,h,z,AIR,61);
   localPut(c,s,x,1,z,Blocks.RAIL.getDefaultState().with(RailBlock.SHAPE,RailShape.EAST_WEST),62);
  }
  rails(c,plan.paths());
  c.flush(seed);
 }
 private static BlockPos local(NetworkPlan.Station s,int x,int h,int z){var n=s.node();return s.northSouth()?new BlockPos(n.x()-z,n.y()+h,n.z()+x):new BlockPos(n.x()+x,n.y()+h,n.z()+z);}
 private static void localPut(Canvas c,NetworkPlan.Station s,int x,int h,int z,BlockState state,int priority){BlockPos p=local(s,x,h,z);c.put(p.getX(),p.getY(),p.getZ(),s.northSouth()?state.rotate(net.minecraft.util.BlockRotation.CLOCKWISE_90):state,priority);}
 private static void station(Canvas c,NetworkPlan.Station s,long seed){var n=s.node();var kind=s.kind();int length=NetworkPlan.halfLength(kind),width=NetworkPlan.halfWidth(kind),height=NetworkPlan.stationHeight(kind);
  for(int x=c.sx;x<c.sx+16;x++)for(int z=c.sz;z<c.sz+16;z++)for(int h=-6;h<=height+6;h++){
   int dx=s.northSouth()?z-n.z():x-n.x(),dz=s.northSouth()?n.x()-x:z-n.z();double edge=Math.min(Math.min(length-Math.abs(dx),width-Math.abs(dz)),Math.min(h,height-h));
   if(edge<0){shell(c,x,n.y()+h,z,edge);continue;}
   BlockState state=StationDesign.sample(dx,h,dz,length,width,height,s.salt(),s.trackOffset(),kind==NetworkPlan.StationKind.FREIGHT||kind==NetworkPlan.StationKind.MIXED?StationVariant.LOGISTICS:StationVariant.select(s.salt(),length,width,height,s.trackOffset()));
   if(kind==NetworkPlan.StationKind.FREIGHT||kind==NetworkPlan.StationKind.MIXED&&(s.northSouth()||dz<0))state=SpecialStations.freight(dx,h,dz,state);
   state=StationDecay.decorate(dx,h,dz,length,width,height,s.salt(),s.condition(),s.trackOffset(),state);
   if(kind==NetworkPlan.StationKind.BIOCENTER&&Math.abs(dx)<=3&&dz<=-14&&h>=3&&h<=7)state=AIR;
   if(dx>=-12&&dx<=-6&&dz>=width-3&&h>=3&&h<=6)state=AIR;
   int serviceZ=kind==NetworkPlan.StationKind.MINI?8:12;
   if(!s.northSouth()&&dx>=length-6&&Math.abs(dz-serviceZ)<=2&&h>=3&&h<=6)state=AIR;
   // The lower transverse hall opens eastward to the shared public staircase.
   if(s.interchange()&&s.northSouth()&&Math.abs(dx-12)<=2&&dz<=-width+4&&h>=3&&h<=6)state=AIR;
   if(state!=null&&(state.isOf(Blocks.CHEST)||state.isOf(Blocks.BARREL)))c.loot.put(new BlockPos(x,n.y()+h,z),stationLoot(kind));
   if(state!=null)c.put(x,n.y()+h,z,s.northSouth()?state.rotate(net.minecraft.util.BlockRotation.CLOCKWISE_90):state,state.isAir()?34:35);
  }
  // Every hall has a visible exit concourse, including intermediate stops.
  for(int x=c.sx;x<c.sx+16;x++)for(int z=c.sz;z<c.sz+16;z++)for(int h=-4;h<=14;h++){
   int dx=s.northSouth()?z-n.z():x-n.x(),dz=s.northSouth()?n.x()-x:z-n.z();
   double edge=Math.min(Math.min(dx+18,-4-dx),Math.min(Math.min(dz-width,width+16-dz),Math.min(h-2,8-h)));
   if(edge<0){shell(c,x,n.y()+h,z,edge);continue;}
   boolean wall=dx==-18||dx==-4||dz==width+16;
   BlockState state=h==2?Blocks.SMOOTH_QUARTZ.getDefaultState():h==8?Blocks.SEA_LANTERN.getDefaultState():wall?Blocks.WHITE_CONCRETE.getDefaultState():AIR;
   c.put(x,n.y()+h,z,state,state.isAir()?43:42);
  }
  String name=kind==NetworkPlan.StationKind.MIXED?"Пассажирско-грузовая":s.interchange()?kind==NetworkPlan.StationKind.TERMINAL?"Вокзал / пересадка":"Пересадочная":switch(kind){case MINI->"Малая станция";case TERMINAL->"Вокзал";case FREIGHT->"Грузовой терминал";case BIOCENTER->"Биоцентр / купол";default->"Пассажирская";};
  BlockPos signPos=local(s,0,6,width-1);c.sign(signPos,name,SignKind.STATION);
  localPut(c,s,0,6,width,Blocks.SMOOTH_QUARTZ.getDefaultState(),50);
  localPut(c,s,0,6,width-1,Blocks.OAK_WALL_SIGN.getDefaultState().with(WallSignBlock.FACING,Direction.NORTH),50);
  localPut(c,s,2,2,8,Blocks.SMOOTH_QUARTZ.getDefaultState(),50);
  localPut(c,s,2,3,8,AIR,50);localPut(c,s,2,4,8,AIR,50);
  localPut(c,s,-10,3,width+8,InfrastructureBlocks.LIFT.getDefaultState(),50);
  localPut(c,s,-10,5,width+15,InfrastructureBlocks.WAYFINDING_SIGN.getDefaultState(),50);
  stationChest(c,s,length-16,width-3,stationLoot(kind));
  // An occasional station locker rewards exploration without putting rare loot in every crate.
  stationChest(c,s,-length+16,-width+3,Math.floorMod(s.salt(),5)==0?"secure_locker":stationLoot(kind));
  if(kind==NetworkPlan.StationKind.BIOCENTER){biocenter(c,s);botanical(c,s);}
  if(SideBranchDesign.present(s.salt()))sideBranch(c,s);
 }
 private static String stationLoot(NetworkPlan.StationKind kind){
  return switch(kind){case FREIGHT,MIXED->"freight";case BIOCENTER->"biocenter";default->"passenger";};
 }
 private static void stationChest(Canvas c,NetworkPlan.Station s,int x,int z,String table){
  localPut(c,s,x,2,z,Blocks.SMOOTH_QUARTZ.getDefaultState(),40);
  localPut(c,s,x,3,z,Blocks.CHEST.getDefaultState(),40);
  localPut(c,s,x,4,z,AIR,40);
  c.loot.put(local(s,x,3,z),table);
 }
 private static void biocenter(Canvas c,NetworkPlan.Station s){
  var n=s.node();var spec=DomeDesign.spec(s.salt());int radius=spec.outerRadius();
  // A separate garden dome south of the station's maintenance wing.
  for(int x=c.sx;x<c.sx+16;x++)for(int z=c.sz;z<c.sz+16;z++){
   int dx=x-n.x(),dz=z-(n.z()-62);
   if(Math.abs(dx)<=radius&&Math.abs(dz)<=radius)for(int h=-6;h<=radius;h++){
    BlockState state=SpecialStations.garden(dx,h,dz,spec);if(state!=null)c.put(x,n.y()+2+h,z,state,35);
   }
   // Level pedestrian corridor meets the station platform and dome walkway.
   if(Math.abs(dx)<=9&&z>=n.z()+spec.offsetZ()+spec.innerRadius()-5&&z<=n.z()-14)for(int h=-6;h<=14;h++){
    double edge=Math.min(3-Math.abs(dx),Math.min(h-2,8-h));
    if(edge<0){shell(c,x,n.y()+h,z,edge);continue;}
    BlockState state=h==2?Blocks.SMOOTH_QUARTZ.getDefaultState():h==8?Blocks.SEA_LANTERN.getDefaultState():Math.abs(dx)==3?Blocks.WHITE_CONCRETE.getDefaultState():AIR;
    c.put(x,n.y()+h,z,state,42);
   }
  }
  int chestX=spec.innerRadius()-5;BlockPos chest=new BlockPos(n.x()+chestX,n.y()+3,n.z()-62);
  c.put(chest.getX(),chest.getY()-1,chest.getZ(),Blocks.SMOOTH_QUARTZ.getDefaultState(),40);
  c.put(chest.getX(),chest.getY(),chest.getZ(),Blocks.CHEST.getDefaultState(),40);
  c.put(chest.getX(),chest.getY()+1,chest.getZ(),AIR,40);c.loot.put(chest,spec.kind()==DomeDesign.Kind.AQUA?"aquarium":"biocenter");
 }
 private static void botanical(Canvas c,NetworkPlan.Station s){
  var n=s.node();var spec=BotanicalAnnex.spec(s.salt());String table=switch(spec.kind()){case SEED_BANK->"seed_bank";case LABORATORY->"botanical_lab";default->"biocenter";};
  for(int x=c.sx;x<c.sx+16;x++)for(int z=c.sz;z<c.sz+16;z++)for(int y=-3;y<=spec.height();y++){
   int dx=x-n.x()-spec.offsetX(),dz=z-n.z()-spec.offsetZ();var state=BotanicalStructures.sample(dx,y,dz,spec);if(state==null)continue;
   int worldY=n.y()+2+y;c.put(x,worldY,z,state,38);
   if(state.isOf(Blocks.CHEST)||state.isOf(Blocks.BARREL))c.loot.put(new BlockPos(x,worldY,z),table);
  }
  String name=switch(spec.kind()){case GREENHOUSE->"Теплица";case SEED_BANK->"Банк семян";case LABORATORY->"Биолаборатория";case OVERGROWN_NURSERY->"Заросший питомник";};
  BlockPos sign=new BlockPos(n.x()+spec.offsetX()+4,n.y()+7,n.z()+spec.offsetZ()+spec.halfLength());c.sign(sign,name,SignKind.BOTANICAL);
  c.put(sign.getX(),sign.getY(),sign.getZ(),Blocks.OAK_WALL_SIGN.getDefaultState().with(WallSignBlock.FACING,Direction.SOUTH),50);
  var dome=DomeDesign.spec(s.salt());var route=TransitGeometry.rounded(java.util.List.of(
    new TransitGeometry.Point(n.x()+dome.innerRadius()-5,n.y()+2,n.z()-62),
    new TransitGeometry.Point(n.x()+50,n.y()+2,n.z()-62),
    new TransitGeometry.Point(n.x()+50,n.y()+2,n.z()-36),
    new TransitGeometry.Point(n.x()+spec.offsetX(),n.y()+2,n.z()-36),
    new TransitGeometry.Point(n.x()+spec.offsetX(),n.y()+2,n.z()+spec.offsetZ()+spec.halfLength())),6);
  path(c,new NetworkPlan.Path(route,NetworkPlan.Condition.INTACT,s.salt()^4291,true));
  // This connector must pierce the finished dome/annex shells, not stop at their glass or bedrock.
  // Reward chests have higher priority and stay intact.
  for(int x=c.sx;x<c.sx+16;x++)for(int z=c.sz;z<c.sz+16;z++)for(var sample:route.samplesNear(x,z,3)){
   if(sample.distance()>2)continue;
   c.put(x,n.y()+2,z,Blocks.SMOOTH_QUARTZ.getDefaultState(),39);
   for(int h=3;h<=6;h++)c.put(x,n.y()+h,z,AIR,39);
  }
 }
 private static void sideBranch(Canvas c,NetworkPlan.Station s){
  var n=s.node();var spec=SideBranchDesign.select(s.salt(),NetworkPlan.halfLength(s.kind()));String table=switch(spec.kind()){case SECRET_STOP,QUARANTINE->"relic_vault";case ABANDONED_DEPOT->"freight";case UTILITY->"maintenance";};
  for(int x=c.sx;x<c.sx+16;x++)for(int z=c.sz;z<c.sz+16;z++){
   int dx=s.northSouth()?z-n.z():x-n.x(),dz=s.northSouth()?n.x()-x:z-n.z();
   if(dx<SideBranchDesign.minX(spec)||dx>SideBranchDesign.maxX(spec)||dz<SideBranchDesign.minZ(spec)||dz>SideBranchDesign.maxZ(spec))continue;
   for(int y=-3;y<=17;y++){
    var state=SideBranchBuilder.sample(dx,y,dz,spec);if(state==null)continue;if(SideBranchDesign.entrance(dx,y,dz,spec))state=AIR;
    c.put(x,n.y()+y,z,s.northSouth()?state.rotate(net.minecraft.util.BlockRotation.CLOCKWISE_90):state,38);
    if(state.isOf(Blocks.CHEST)||state.isOf(Blocks.BARREL))c.loot.put(new BlockPos(x,n.y()+y,z),table);
   }
  }
  String name=switch(spec.kind()){case SECRET_STOP->"Тайная остановка";case QUARANTINE->"Карантинный блок";case ABANDONED_DEPOT->"Заброшенное депо";case UTILITY->"Техническая галерея";};
  BlockPos sign=local(s,-105,7,83);c.sign(sign,name,spec.kind()==SideBranchDesign.Kind.QUARANTINE?SignKind.QUARANTINE:SignKind.SECRET);
  localPut(c,s,-105,7,83,Blocks.OAK_WALL_SIGN.getDefaultState().with(WallSignBlock.FACING,Direction.NORTH),50);
 }
 private static final BlockState[] ACCENTS={Blocks.CYAN_CONCRETE.getDefaultState(),Blocks.LIME_CONCRETE.getDefaultState(),Blocks.ORANGE_CONCRETE.getDefaultState(),Blocks.LIGHT_BLUE_CONCRETE.getDefaultState(),Blocks.PURPLE_CONCRETE.getDefaultState()};
 private static final BlockState[] GLASSES={Blocks.CYAN_STAINED_GLASS.getDefaultState(),Blocks.LIME_STAINED_GLASS.getDefaultState(),Blocks.ORANGE_STAINED_GLASS.getDefaultState(),Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState(),Blocks.PURPLE_STAINED_GLASS.getDefaultState()};
 private static void path(Canvas c,NetworkPlan.Path p){double length=p.route().length();RailPlan railPlan=p.service()?null:(p.singleTrack()?RailPlan.ofSingle(p.route()):RailPlan.of(p.route()));
  int line=Math.floorMod((int)(p.salt()>>>5),ACCENTS.length);BlockState accent=p.freight()?Blocks.YELLOW_CONCRETE.getDefaultState():ACCENTS[line],glass=GLASSES[line];
  for(int x=c.sx;x<c.sx+16;x++)for(int z=c.sz;z<c.sz+16;z++)for(var sample:p.route().samplesNear(x,z,12)){
   var profile=p.freight()?new TunnelProfile.Section(TunnelProfile.Style.INDUSTRIAL,6,9,false):TunnelProfile.section(p.salt(),p.service(),p.transfer(),sample.along(),length);
   double width=p.singleTrack()?3:profile.halfWidth();int fullHeight=profile.height();
   int base=railPlan==null?(int)Math.floor(sample.floorY()):railPlan.floorAt(x,z,sample);double d=profile.distance(sample);int roof=p.singleTrack()?Math.max(5,profile.roof(d)):profile.roof(d);
   boolean damaged=p.condition()!=NetworkPlan.Condition.INTACT;
   int bay=Math.floorMod((int)sample.along(),12);
   BlockState wall=switch(profile.style()){
    case BRICK->(damaged&&bay==0?Blocks.CRACKED_STONE_BRICKS:Blocks.BRICKS).getDefaultState();
    case INDUSTRIAL->(bay<=1?Blocks.POLISHED_DEEPSLATE:Blocks.GRAY_CONCRETE).getDefaultState();
    case ROCK->(Math.floorMod(x*13+z*7+base,5)==0?Blocks.ANDESITE:Blocks.STONE).getDefaultState();
    case CLEAN->Blocks.SMOOTH_QUARTZ.getDefaultState();
    case SERVICE,COMPACT->(bay<=1?Blocks.POLISHED_ANDESITE:Blocks.STONE_BRICKS).getDefaultState();
    default->Blocks.WHITE_CONCRETE.getDefaultState();
   };
   for(int h=-6;h<=fullHeight+6;h++){
    double edge=Math.min(width-d,Math.min(h,roof-h));int y=base+h;
    if(edge<0){shell(c,x,y,z,edge);continue;}
    BlockState state=AIR;int priority=20;
    if(h==0){state=(d<1.3&&!p.service()?Blocks.POLISHED_DEEPSLATE:Blocks.SMOOTH_STONE).getDefaultState();priority=30;}
    else if(d>width-0.85||h==roof){
     state=wall;priority=12;
     if(h==3&&h<roof&&d>width-0.85&&profile.style()!=TunnelProfile.Style.BRICK&&profile.style()!=TunnelProfile.Style.ROCK)state=accent;
     if(h==roof&&d<(profile.compact()?0.8:1.6)&&profile.lamp(sample.along(),damaged))
      state=(profile.style()==TunnelProfile.Style.SERVICE||profile.style()==TunnelProfile.Style.COMPACT?Blocks.MAGMA_BLOCK:profile.style()==TunnelProfile.Style.BRICK?Blocks.SHROOMLIGHT:Blocks.SEA_LANTERN).getDefaultState();
     if(!profile.compact()&&d>width-0.85&&h==2&&profile.style()==TunnelProfile.Style.MODERN)state=Blocks.SMOOTH_QUARTZ_STAIRS.getDefaultState().with(StairsBlock.FACING,Math.abs(sample.headingX())>Math.abs(sample.headingZ())?(sample.headingX()>0?Direction.EAST:Direction.WEST):(sample.headingZ()>0?Direction.SOUTH:Direction.NORTH));
     if(!profile.compact()&&d>width-0.85&&h==4&&Math.floorMod((int)sample.along(),16)<=1)state=Blocks.WAXED_OXIDIZED_CUT_COPPER.getDefaultState();
     if(profile.style()==TunnelProfile.Style.MODERN&&d>width-0.85&&h>=4&&h<roof&&Math.floorMod((int)sample.along(),16)>2)state=glass;
    }
    c.put(x,y,z,state,priority);
   }
   // Only pedestrian corridors use slab ramps; track beds stay full solid blocks.
   double fraction=sample.floorY()-base;
   if(p.service()&&fraction>0.5&&d<width-1.2)c.put(x,base+1,z,Blocks.SMOOTH_STONE_SLAB.getDefaultState(),31);
   int loc=Math.floorMod((int)sample.along()+(int)(p.salt()&63),137);
   if(p.condition()==NetworkPlan.Condition.COLLAPSED&&loc>=64&&loc<=67&&d>2.8&&d<width-1)c.put(x,base+1,z,Blocks.COBBLESTONE.getDefaultState(),33);
   if(p.condition()==NetworkPlan.Condition.CHEMICAL&&loc>=71&&loc<=73&&d>width-2&&d<width-1)c.put(x,base,z,Blocks.MAGMA_BLOCK.getDefaultState(),33);
  }
 }
 private static void rails(Canvas c,java.util.List<NetworkPlan.Path> paths){
  var cells=new java.util.LinkedHashMap<BlockPos,RailPlan.Cell>();var power=new java.util.HashSet<BlockPos>();
  for(var p:paths)if(!p.service())for(var lane:(p.singleTrack()?RailPlan.ofSingle(p.route()):RailPlan.of(p.route())).lanes())for(var cell:lane){
   if(cell.x()<c.sx-4||cell.x()>c.sx+19||cell.z()<c.sz-4||cell.z()>c.sz+19)continue;
   BlockPos at=new BlockPos(cell.x(),cell.floor()+1,cell.z());cells.putIfAbsent(at,cell);
   boolean curve=!cell.shape().startsWith("ascending_")&&!cell.shape().equals("east_west")&&!cell.shape().equals("north_south");
   if(!curve&&cell.along()>72&&cell.along()<p.route().length()-48&&Math.floorMod((int)cell.along(),32)<2)power.add(at);
  }
  var junctions=new java.util.ArrayList<BlockPos>();
  for(var entry:cells.entrySet()){
   var cell=entry.getValue();var at=entry.getKey();int count=0;
   for(var d:new Direction[]{Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST})if(cells.containsKey(at.offset(d)))count++;
   if(count>=3)junctions.add(at);
   RailShape shape=RailShape.valueOf(cell.shape().toUpperCase(java.util.Locale.ROOT));
   boolean powered=power.contains(at)&&count<3&&(cell.shape().startsWith("ascending_")||cell.shape().equals("east_west")||cell.shape().equals("north_south"));
   c.put(cell.x(),cell.floor(),cell.z(),(powered?Blocks.REDSTONE_BLOCK:Blocks.POLISHED_DEEPSLATE).getDefaultState(),60);
   c.box(cell.x(),cell.x(),cell.floor()+1,cell.floor()+3,cell.z(),cell.z(),AIR,61);
   c.put(cell.x(),cell.floor()+1,cell.z(),powered?Blocks.POWERED_RAIL.getDefaultState().with(PoweredRailBlock.SHAPE,shape).with(PoweredRailBlock.POWERED,true):Blocks.RAIL.getDefaultState().with(RailBlock.SHAPE,shape),62);
  }
  // Controls stand on the aisle, never on the track or in the minecart headroom.
  for(var at:junctions){
   if(at.getX()<c.sx||at.getX()>c.sx+15||at.getZ()<c.sz||at.getZ()>c.sz+15)continue;
   boolean placed=false;
   for(int radius=2;radius<=3&&!placed;radius++)for(int dx=-radius;dx<=radius&&!placed;dx++)for(int dz=-radius;dz<=radius&&!placed;dz++){
    if(Math.max(Math.abs(dx),Math.abs(dz))!=radius)continue;
    BlockPos control=at.add(dx,0,dz);BlockState state=c.peek(control.getX(),control.getY(),control.getZ());BlockState floor=c.peek(control.getX(),control.getY()-1,control.getZ());
    if(state==null||!state.isAir()||floor==null||floor.isAir())continue;
    if(cells.containsKey(control)||cells.containsKey(control.north())||cells.containsKey(control.south())||cells.containsKey(control.east())||cells.containsKey(control.west()))continue;
    c.put(control.getX(),control.getY(),control.getZ(),InfrastructureBlocks.TRACK_SWITCH.getDefaultState(),65);placed=true;
   }
  }
 }
 private static void room(Canvas c,NetworkPlan.Room r){
  for(int x=c.sx;x<c.sx+16;x++)for(int z=c.sz;z<c.sz+16;z++)for(int h=-6;h<=r.height()+6;h++){
   int edge=Math.min(Math.min(Math.min(x-r.x1(),r.x2()-x),Math.min(z-r.z1(),r.z2()-z)),Math.min(h,r.height()-h));
   if(edge<0){shell(c,x,r.base()+h,z,edge);continue;}
   boolean side=x==r.x1()||x==r.x2()||z==r.z1()||z==r.z2();
   BlockState state=(h==0?Blocks.SMOOTH_STONE:h==r.height()?Blocks.WHITE_CONCRETE:side?Blocks.SMOOTH_QUARTZ:Blocks.AIR).getDefaultState();
   if(h==r.height()&&Math.floorMod(x+z,7)==0)state=Blocks.SEA_LANTERN.getDefaultState();
   if(side&&h==3)state=(r.condition()==NetworkPlan.Condition.CHEMICAL?Blocks.LIME_CONCRETE:r.condition()==NetworkPlan.Condition.QUARANTINE?Blocks.ORANGE_CONCRETE:Blocks.CYAN_CONCRETE).getDefaultState();
   if(side&&h>=4&&h<=6&&Math.floorMod(x+z,6)!=0)state=Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState();
   // Both doors meet designated station/service passages, leaving intact wall bays.
   if(h>=1&&h<=4&&((x>=r.x1()+10&&x<=r.x1()+18&&(z==r.z1()||z==r.z2()))||x==r.x2()&&z>=r.z1()+5&&z<=r.z1()+9))state=AIR;
   c.put(x,r.base()+h,z,state,state.isAir()?22:side?13:30);
  }
  int x=r.x1()+4,z=r.z2()-4,y=r.base()+1;
  c.put(x,y,z,Blocks.CHEST.getDefaultState(),40);
  c.put(x,y+1,z,AIR,40);
  boolean relic=Math.floorMod(r.salt(),12)==0;
  c.loot.put(new BlockPos(x,y,z),relic?"relic_vault":"maintenance");
  if(relic){c.put(x,y+2,z,Blocks.CHISELED_DEEPSLATE.getDefaultState(),40);c.put(x,y+3,z,Blocks.SOUL_LANTERN.getDefaultState(),40);}
  for(int i=0;i<3;i++){
   c.put(r.x2()-4,r.base()+1,r.z2()-5-i*4,Blocks.BARREL.getDefaultState(),34);
   c.put(r.x2()-4,r.base()+3,r.z2()-5-i*4,Blocks.SMOOTH_STONE_SLAB.getDefaultState(),34);
  }
  if(r.condition()==NetworkPlan.Condition.QUARANTINE){c.put(r.x2()-5,y,r.z2()-4,Blocks.SPAWNER.getDefaultState(),40);c.put(r.x2()-3,y+2,r.z2()-3,Blocks.COBWEB.getDefaultState(),34);}
  if(r.condition()==NetworkPlan.Condition.CHEMICAL){c.box(r.x2()-9,r.x2()-7,r.base(),r.base(),r.z2()-9,r.z2()-7,Blocks.MAGMA_BLOCK.getDefaultState(),40);c.put(r.x2()-10,y,r.z2()-8,InfrastructureBlocks.CONTAINER.getDefaultState(),40);}
 }
 private static void stairs(Canvas c,NetworkPlan.StairTower t){
  var room=new NetworkPlan.Room(t.x()-3,t.x()+11,t.z()-1,t.z()+10,t.bottom(),t.top()-t.bottom()+7,NetworkPlan.Condition.INTACT,t.salt());room(c,room);
  // Clear storage placements belonging to the generic room; tower has its own landings.
  c.box(t.x()-2,t.x()+10,t.bottom()+1,t.top()+6,t.z(),t.z()+9,AIR,41);
  c.box(t.x()-3,t.x()-1,t.bottom(),t.bottom(),t.z(),t.z()+9,Blocks.SMOOTH_QUARTZ.getDefaultState(),44);
  for(int level=t.bottom()+4;level<t.top()+6;level+=8)c.put(t.x()+11,level,t.z()+5,Blocks.SEA_LANTERN.getDefaultState(),44);
  int flights=(t.top()-t.bottom())/8;
  for(int f=0;f<flights;f++){
   int start=t.bottom()+f*8,lane=t.z()+(f%2==0?0:6);boolean east=f%2==0;
   for(int step=0;step<8;step++)for(int w=0;w<3;w++){
    int x=t.x()+(east?step:7-step),y=start+step+1,z=lane+w;
    c.put(x,y,z,Blocks.SMOOTH_QUARTZ_STAIRS.getDefaultState().with(StairsBlock.FACING,east?Direction.EAST:Direction.WEST),45);
    c.put(x,y-1,z,Blocks.SMOOTH_QUARTZ.getDefaultState(),44);
    if(w==0)c.put(x,y+1,z-1,Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState(),45);
   }
   int x1=east?t.x()+8:t.x()-3,x2=east?t.x()+10:t.x()-1;
   c.box(x1,x2,start+8,start+8,t.z(),t.z()+8,Blocks.SMOOTH_QUARTZ.getDefaultState(),44);
  }
  // The upper landing crosses between the two flights without covering stair headroom.
  c.box(t.x()-3,t.x()-1,t.top(),t.top(),t.z(),t.z()+8,Blocks.SMOOTH_QUARTZ.getDefaultState(),44);
  c.box(t.x(),t.x()+10,t.top(),t.top(),t.z()+3,t.z()+5,Blocks.SMOOTH_QUARTZ.getDefaultState(),44);
  // Public openings at both levels; service galleries connect on the left side.
  for(int floor:new int[]{t.bottom(),t.top()})c.box(t.x()-3,t.x()-1,floor+1,floor+4,t.z(),t.z()+2,AIR,46);
 }
}
