package eu.metroworld.infrastructure.train;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.nio.file.*;
import java.util.*;
import eu.metroworld.infrastructure.Transit;
import eu.metroworld.infrastructure.world.*;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.entity.*;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.*;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.*;
/** Server-owned consists. Render proxies are disposable; the saved consist is authoritative. */
public final class Trains {
 private Trains(){}
 public static final String RENDER_TAG="metro-world:train-render";
 static final Set<UUID> OWNED=new HashSet<>();
 record Hit(String train,int car,String action,int seat){}
 static final Map<UUID,Hit> HITS=new HashMap<>();
 static final Map<String,Runtime> TRAINS=new LinkedHashMap<>();
 private static final Map<UUID,Boarding> BOARDING=new HashMap<>();
 private record Boarding(String train,int car){}
 private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
 private static boolean saveAllowed=true;
 private static final ChunkTicketType<String> TICKET=ChunkTicketType.create("metro-world-train",Comparator.<String>naturalOrder(),40);
 public static final class Runtime {
  public final TrainData data;public TrainPath path;final List<TrainRender> render=new ArrayList<>();final List<SimpleInventory> cargo=new ArrayList<>();
  Runtime(TrainData data,ServerWorld world){this.data=data;path=new TrainPath(data.points);data.control.sanitize();
   for(int i=0;i<data.cars.size();i++){var inv=new SimpleInventory(54);if(i<data.inventories.size()&&!data.inventories.get(i).isEmpty())try{var n=StringNbtReader.parse(data.inventories.get(i));var items=n.getList("Items",10);for(var entry:items){var stack=(NbtCompound)entry;int slot=stack.getInt("Slot");if(slot>=0&&slot<54)inv.setStack(slot,ItemStack.fromNbtOrEmpty(world.getRegistryManager(),stack.getCompound("Stack")));}}catch(Exception e){throw new IllegalArgumentException("invalid saved cargo for "+data.id,e);}cargo.add(inv);}
  }
  TrainPath.Pose pose(int car){return path.at(data.control.position-data.carOffset(car));}
  public boolean nearby(ServerPlayerEntity player){if(!Transit.isNetworkWorld(player.getWorld()))return false;for(int i=0;i<data.cars.size();i++){var p=pose(i);if(player.squaredDistanceTo(p.x(),p.y(),p.z())<196)return true;}return false;}
  void show(ServerWorld world){if(!render.isEmpty())return;for(int i=0;i<data.cars.size();i++){var car=new TrainRender(world,this,i);car.spawn();render.add(car);car.move(pose(i));}}
  void hide(){
   for(int i=0;i<render.size();i++){
    var r=render.get(i);
    for(var seat:r.seats)for(var passenger:new ArrayList<>(seat.getPassengerList())){
     if(passenger instanceof ServerPlayerEntity player){
      player.stopRiding();
      var at=pose(i).world(0,.05,0);
      player.requestTeleport(at[0],at[1],at[2]);
      BOARDING.put(player.getUuid(),new Boarding(data.id,i));
     }
    }
   }
   for(var car:render)car.dispose();render.clear();
  }
  void encodeCargo(ServerWorld world){data.inventories.clear();for(var inv:cargo){var root=new NbtCompound();var list=new NbtList();for(int slot=0;slot<54;slot++)if(!inv.getStack(slot).isEmpty()){var entry=new NbtCompound();entry.putInt("Slot",slot);entry.put("Stack",inv.getStack(slot).encode(world.getRegistryManager()));list.add(entry);}root.put("Items",list);data.inventories.add(root.toString());}}
 }
 public static void initialize(){
  TrainModels.initialize();
  UseEntityCallback.EVENT.register((player,world,hand,entity,hit)->{var control=HITS.get(entity.getUuid());if(control==null||!(player instanceof ServerPlayerEntity p))return ActionResult.PASS;if(hand!=net.minecraft.util.Hand.MAIN_HAND)return ActionResult.SUCCESS;var train=TRAINS.get(control.train);if(train==null||!train.nearby(p))return ActionResult.FAIL;switch(control.action){case "panel"->TrainPanel.open(p,train);case "cargo"->cargo(p,train,control.car);case "seat"->seat(p,train,control.car,control.seat);case "couple"->couple(p,train);}return ActionResult.SUCCESS;});
  ServerEntityEvents.ENTITY_LOAD.register((entity,world)->{if(entity.getCommandTags().contains(RENDER_TAG)&&!OWNED.contains(entity.getUuid()))entity.discard();});
 }
 public static Runtime nearest(ServerPlayerEntity p){Runtime best=null;double distance=196;for(var t:TRAINS.values())if(t.nearby(p))for(int i=0;i<t.data.cars.size();i++){var pose=t.pose(i);double d=p.squaredDistanceTo(pose.x(),pose.y(),pose.z());if(d<distance){distance=d;best=t;}}return best;}
 public static void load(MinecraftServer server){reset();var world=server.getWorld(Transit.UNDERGROUND);if(world==null)return;Path file=savePath(server);if(!Files.exists(file))return;
  try{List<TrainData> data=JSON.fromJson(Files.readString(file),new TypeToken<List<TrainData>>(){}.getType());if(data==null||data.size()>48)throw new IllegalArgumentException("train count");for(var d:data){if(d.cars==null||d.cars.isEmpty()||d.cars.size()>6||d.control==null)throw new IllegalArgumentException("invalid consist");if(TRAINS.containsKey(d.id))throw new IllegalArgumentException("duplicate train id");if(!d.points.isEmpty()&&Math.abs(d.points.get(0).x()-(int)Math.floor(d.points.get(0).x()))<1e-4){var shifted=new ArrayList<TransitGeometry.Point>();for(var pt:d.points)shifted.add(new TransitGeometry.Point(pt.x()+0.5,pt.y(),pt.z()+0.5));d.points=shifted;}var t=new Runtime(d,world);d.control.position=Math.max(d.length(),Math.min(t.path.length,d.control.position));d.control.speed=0;d.control.traction=0;d.control.brake=7;TRAINS.put(d.id,t);}}
  catch(Exception e){saveAllowed=false;throw new IllegalStateException("Metro World refuses to overwrite corrupt train save "+file,e);}
 }
 private static Path savePath(MinecraftServer s){return s.getSavePath(WorldSavePath.ROOT).resolve("metro-world-trains.json");}
 public static void save(MinecraftServer server){if(!saveAllowed)return;var world=server.getWorld(Transit.UNDERGROUND);if(world==null)return;try{for(var t:TRAINS.values())t.encodeCargo(world);Path file=savePath(server),tmp=file.resolveSibling(file.getFileName()+".tmp");Files.writeString(tmp,JSON.toJson(TRAINS.values().stream().map(t->t.data).toList()));try{Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);}}catch(Exception e){throw new IllegalStateException("Cannot save metro trains",e);}}
 public static void clampBoarding(String trainId,int maxCars){for(var e:new ArrayList<>(BOARDING.entrySet()))if(e.getValue().train.equals(trainId)&&e.getValue().car>=maxCars)BOARDING.put(e.getKey(),new Boarding(trainId,Math.max(0,maxCars-1)));}
 public static void reset(){for(var t:TRAINS.values())t.hide();TRAINS.clear();BOARDING.clear();OWNED.clear();HITS.clear();saveAllowed=true;TrainPanel.reset();}
 public static void tick(MinecraftServer server){var world=server.getWorld(Transit.UNDERGROUND);if(world==null)return;
  BOARDING.entrySet().removeIf(e->{var p=server.getPlayerManager().getPlayer(e.getKey());return p==null||!Transit.isNetworkWorld(p.getWorld())||!TRAINS.containsKey(e.getValue().train);});
  int active=0;
  for(var t:TRAINS.values()){
   boolean rider=t.render.stream().anyMatch(r->r.seats.stream().anyMatch(net.minecraft.entity.Entity::hasPassengers))||BOARDING.values().stream().anyMatch(b->b.train.equals(t.data.id));
   boolean near=server.getPlayerManager().getPlayerList().stream().anyMatch(p->{if(!Transit.isNetworkWorld(p.getWorld()))return false;for(int i=0;i<t.data.cars.size();i++){var at=t.pose(i);if(p.squaredDistanceTo(at.x(),at.y(),at.z())<256*256)return true;}return false;});
   if(!near||active++>=4&&!rider){if(!t.render.isEmpty()){t.data.control.speed=0;t.data.control.traction=0;t.data.control.brake=7;t.hide();}continue;}
   t.show(world);int carCount=t.data.cars.size();var old=new ArrayList<TrainPath.Pose>(carCount);for(int i=0;i<carCount;i++)old.add(t.pose(i));
   if(!t.data.hasTraction()){t.data.control.power=false;t.data.control.compressor=false;t.data.control.traction=0;}
   extend(world,t);
   int lastTicketCx=Integer.MIN_VALUE,lastTicketCz=Integer.MIN_VALUE;
   for(int i=0;i<carCount;i++){
    var pose=old.get(i);int cx=(int)Math.floor(pose.x())>>4,cz=(int)Math.floor(pose.z())>>4;
    if(cx!=lastTicketCx||cz!=lastTicketCz){lastTicketCx=cx;lastTicketCz=cz;ChunkPos chunk=new ChunkPos(cx,cz);world.getChunkManager().addTicket(TICKET,chunk,2,t.data.id);world.getChunk(chunk.x,chunk.z);}
   }
   double before=t.data.control.position;t.data.control.tick(.05,t.data.length()+2,t.path.length-2,t.path.at(before).grade());
   // Compare occupied intervals, so a split consist can move apart but cannot overlap.
   for(var other:TRAINS.values())if(other!=t&&t.path.same(other.path)){
    double oldGap=gap(before,t.data.length(),other.data.control.position,other.data.length());
    double newGap=gap(t.data.control.position,t.data.length(),other.data.control.position,other.data.length());
    if(newGap<Math.min(.8,oldGap)-.0001){t.data.control.position=before;t.data.control.speed=0;t.data.control.brake=7;break;}
   }
   var current=new ArrayList<TrainPath.Pose>(carCount);for(int i=0;i<carCount;i++)current.add(t.pose(i));
   boolean occupied=false;
   for(var other:TRAINS.values())if(other!=t)for(int i=0;i<carCount;i++)for(int j=0;j<other.data.cars.size();j++){
    var a=current.get(i);var b=other.pose(j);if(!TrainEnvelope.overlaps(a,t.data.carLength(i),2.8,b,other.data.carLength(j),2.8))continue;
    double oldDistance=Math.hypot(old.get(i).x()-b.x(),old.get(i).z()-b.z()),newDistance=Math.hypot(a.x()-b.x(),a.z()-b.z());if(newDistance<oldDistance-.0001)occupied=true;
   }
   if(occupied||!clear(world,t,current)){t.data.control.position=before;t.data.control.speed=0;t.data.control.traction=0;t.data.control.brake=7;current=old;}
   for(int i=0;i<t.render.size();i++){
    carry(server,t,i,old.get(i),current.get(i));t.render.get(i).move(current.get(i));
   }
  }
  if(server.getTicks()%100==0){for(var p:server.getPlayerManager().getPlayerList())if(Transit.isNetworkWorld(p.getWorld()))autoSpawn(p);}
  if(server.getTicks()%200==0)save(server);
 }
 static double gap(double a,double al,double b,double bl){return Math.max(a-al-b,b-bl-a);}
 private static boolean clear(ServerWorld world,Runtime train,List<TrainPath.Pose> poses){
  int lastCx=Integer.MIN_VALUE,lastCz=Integer.MIN_VALUE;
  int lastBx=Integer.MIN_VALUE,lastBy=Integer.MIN_VALUE,lastBz=Integer.MIN_VALUE;
  net.minecraft.world.chunk.WorldChunk chunk=null;
  BlockPos.Mutable pos=new BlockPos.Mutable();
  for(int i=0;i<train.data.cars.size();i++){
   var pose=poses.get(i);double half=train.data.carLength(i)/2;
   for(double z=-half+.3;z<=half-.2;z+=1)for(double x:new double[]{-1.15,0,1.15})for(double y:new double[]{.4,1.3,2.4}){
    double wx=pose.x()+pose.hz()*x+pose.hx()*z;
    double wy=pose.y()+y+pose.grade()*z;
    double wz=pose.z()-pose.hx()*x+pose.hz()*z;
    int bx=(int)Math.floor(wx),by=(int)Math.floor(wy),bz=(int)Math.floor(wz);
    if(bx==lastBx&&by==lastBy&&bz==lastBz)continue;
    lastBx=bx;lastBy=by;lastBz=bz;
    int cx=Math.floorDiv(bx,16),cz=Math.floorDiv(bz,16);
    if(cx!=lastCx||cz!=lastCz){lastCx=cx;lastCz=cz;chunk=world.getChunkManager().getWorldChunk(cx,cz);}
    if(chunk==null)return false;
    var state=chunk.getBlockState(pos.set(bx,by,bz));
    if(!state.isAir()&&!state.getCollisionShape(world,pos).isEmpty())return false;
   }
  }return true;
 }
 private static void carry(MinecraftServer server,Runtime train,int index,TrainPath.Pose old,TrainPath.Pose next){
  double carHalf=train.data.carLength(index)/2;
  for(var p:server.getPlayerManager().getPlayerList()){
   if(!Transit.isNetworkWorld(p.getWorld())||p.isSpectator()||p.hasVehicle())continue;
   if(Math.abs(p.getX()-old.x())>20||Math.abs(p.getZ()-old.z())>20)continue;
   var local=old.local(p.getX(),p.getY(),p.getZ());
   var entry=BOARDING.get(p.getUuid());boolean ours=entry!=null&&entry.train.equals(train.data.id)&&entry.car==index;
   if(!ours){if((entry!=null&&TRAINS.containsKey(entry.train))||Math.abs(local[0])>1.15||Math.abs(local[2])>carHalf||local[1]<-.15||local[1]>.35)continue;
    boolean floor=worldFloor(p);if(!floor)continue;BOARDING.put(p.getUuid(),new Boarding(train.data.id,index));}
   if(Math.abs(local[0])>1.65||Math.abs(local[2])>carHalf+1.2||local[1]<-1||local[1]>3.5){BOARDING.remove(p.getUuid());continue;}
   if(train.data.control.stopped())continue;
   double lateral=local[0];if(!train.data.control.leftDoors&&!train.data.control.rightDoors)lateral=Math.max(-1.05,Math.min(1.05,lateral));
   double atX=next.x()+next.hz()*lateral+next.hx()*local[2];
   double atY=next.y()+local[1]+next.grade()*local[2];
   double atZ=next.z()-next.hx()*lateral+next.hz()*local[2];
   if(Math.abs(atX-p.getX())+Math.abs(atY-p.getY())+Math.abs(atZ-p.getZ())>.001)p.networkHandler.requestTeleport(atX,atY,atZ,p.getYaw(),p.getPitch());
   p.fallDistance=0;
  }
 }
 private static boolean worldFloor(ServerPlayerEntity p){return p.getWorld().getOtherEntities(p,p.getBoundingBox().offset(0,-.3,0),e->e.getCommandTags().contains("metro-world:train-floor")).size()>0;}
 public static int board(ServerPlayerEntity p){var t=nearest(p);if(t==null){p.sendMessage(Text.literal("Рядом нет поезда."));return 0;}if(!t.data.control.stopped()||!t.data.control.leftDoors&&!t.data.control.rightDoors){p.sendMessage(Text.literal("Посадка: остановите поезд и откройте двери через пульт."));return 0;}int car=0;double best=Double.MAX_VALUE;for(int i=0;i<t.data.cars.size();i++){var atCar=t.pose(i);double d=p.squaredDistanceTo(atCar.x(),atCar.y(),atCar.z());if(d<best){best=d;car=i;}}var at=t.pose(car).world(0,.05,0);p.requestTeleport(at[0],at[1],at[2]);BOARDING.put(p.getUuid(),new Boarding(t.data.id,car));p.sendMessage(Text.literal("Вы вошли в вагон №"+(car+1)+"."));return 1;}
 static int seat(ServerPlayerEntity p,Runtime t,int car,int seat){if(t.render.isEmpty()||seat<0||seat>=t.render.get(car).seats.size())return 0;var entity=t.render.get(car).seats.get(seat);if(entity.hasPassengers())return 0;if(p.squaredDistanceTo(entity)>9)return 0;BOARDING.remove(p.getUuid());return p.startRiding(entity,true)?1:0;}
 public static int seatNearest(ServerPlayerEntity p,int seat){var t=nearest(p);if(t==null){p.sendMessage(Text.literal("Рядом нет поезда."));return 0;}int car=0;double best=Double.MAX_VALUE;for(int i=0;i<t.data.cars.size();i++){var at=t.pose(i);double d=p.squaredDistanceTo(at.x(),at.y(),at.z());if(d<best){best=d;car=i;}}int res=seat(p,t,car,seat);if(res==0)p.sendMessage(Text.literal("Не удалось сесть: подойдите ближе к сиденью или оно занято."));return res;}
 public static int stand(ServerPlayerEntity p){var vehicle=p.getVehicle();if(vehicle==null){p.sendMessage(Text.literal("Вы не сидите на месте в поезде."));return 0;}for(var t:TRAINS.values())for(int i=0;i<t.render.size();i++)if(t.render.get(i).seats.contains(vehicle)){p.stopRiding();var at=t.pose(i).world(0,.05,0);p.requestTeleport(at[0],at[1],at[2]);BOARDING.put(p.getUuid(),new Boarding(t.data.id,i));return 1;}return 0;}
 static int cargo(ServerPlayerEntity p,Runtime t,int index){var at=t.pose(index);if(p.squaredDistanceTo(at.x(),at.y(),at.z())>64||!t.data.control.stopped()||!t.data.cars.get(index).cargo()||!t.nearby(p))return 0;p.openHandledScreen(new SimpleNamedScreenHandlerFactory((sync,inv,player)->new GenericContainerScreenHandler(net.minecraft.screen.ScreenHandlerType.GENERIC_9X6,sync,inv,t.cargo.get(index),6){@Override public boolean canUse(net.minecraft.entity.player.PlayerEntity player){var at=t.pose(index);return TRAINS.get(t.data.id)==t&&t.data.control.stopped()&&player instanceof ServerPlayerEntity sp&&t.nearby(sp)&&sp.squaredDistanceTo(at.x(),at.y(),at.z())<64;}@Override public void onClosed(net.minecraft.entity.player.PlayerEntity player){super.onClosed(player);if(player instanceof ServerPlayerEntity sp)save(sp.getServer());}},Text.literal("Контейнер — "+t.data.name)));return 1;}
 public static int cargoNearest(ServerPlayerEntity p){var t=nearest(p);if(t==null){p.sendMessage(Text.literal("Рядом нет поезда."));return 0;}for(int i=0;i<t.data.cars.size();i++)if(t.data.cars.get(i).cargo())return cargo(p,t,i);p.sendMessage(Text.literal("В составе нет грузовых вагонов."));return 0;}
 private static String anchor(StationGraph.Edge edge){return edge.a().id()+"→"+edge.b().id();}
 public static Runtime create(ServerWorld world,StationGraph.Edge edge,String type){return create(world,edge,type,false);}
 public static Runtime create(ServerWorld world,StationGraph.Edge edge,String type,boolean atEnd){
  if(TRAINS.size()>=48)return null;var routes=NetworkPlan.trackRoutes(world.getSeed(),edge);if(routes.isEmpty())return null;String anchor=anchor(edge);if(TRAINS.values().stream().anyMatch(t->(t.data.anchor.equals(anchor)||t.data.routeAnchors.contains(anchor))))return null;
  var data=new TrainData();data.anchor=anchor;data.routeAnchors.add(anchor);data.origin=edge.a().id();data.destination=edge.b().id();data.northSouth=edge.northSouth();data.freight=edge.freight();data.points=new ArrayList<>(pathForEdge(world.getSeed(),edge).points);
  if(type.equals("freight")){data.name="Грузовой состав";data.cars.addAll(List.of(TrainCar.LOCOMOTIVE,TrainCar.CONTAINER,TrainCar.PLATFORM));}else{boolean red=type.equals("red");data.name=red?"Метро — красный":"Метро — голубой";data.cars.addAll(List.of(red?TrainCar.METRO_RED:TrainCar.METRO_BLUE,red?TrainCar.METRO_RED:TrainCar.METRO_BLUE));}
  data.control.position=atEnd?new TrainPath(data.points).length-5:data.length()+5;data.control.pressure=5;data.control.leftDoors=true;var runtime=new Runtime(data,world);TRAINS.put(data.id,runtime);return runtime;
 }
 private static TrainPath pathForEdge(long seed,StationGraph.Edge edge){
  var track=NetworkPlan.trackRoutes(seed,edge).get(0);var controls=new ArrayList<TransitGeometry.Point>(track.route().points());var a=StationGraph.railNode(edge.a(),edge.northSouth());var b=StationGraph.railNode(edge.b(),edge.northSouth());var first=controls.get(0);var last=controls.get(controls.size()-1);
  var sa=NetworkPlan.station(seed,edge.a(),edge.northSouth());
  var sb=NetworkPlan.station(seed,edge.b(),edge.northSouth());
  if(sa.isDiagonal()){
   boolean nw=sa.orientation()==NetworkPlan.Orientation.DIAGONAL_NW;
   int dx=28,dz=nw?-28:28;
   controls.add(0,new TransitGeometry.Point(a.x(),a.y(),a.z()));
   controls.add(0,new TransitGeometry.Point(a.x()-dx,a.y(),a.z()-dz));
  }else{
   controls.add(0,new TransitGeometry.Point(edge.northSouth()&&track.singleTrack()?first.x():a.x(),a.y(),!edge.northSouth()&&track.singleTrack()?first.z():a.z()));
  }
  if(sb.isDiagonal()){
   boolean nw=sb.orientation()==NetworkPlan.Orientation.DIAGONAL_NW;
   int dx=28,dz=nw?-28:28;
   controls.add(new TransitGeometry.Point(b.x(),b.y(),b.z()));
   controls.add(new TransitGeometry.Point(b.x()+dx,b.y(),b.z()+dz));
  }else{
   controls.add(new TransitGeometry.Point(edge.northSouth()&&track.singleTrack()?last.x():b.x(),b.y(),!edge.northSouth()&&track.singleTrack()?last.z():b.z()));
  }
  return TrainPath.lane(new TransitGeometry.Route(controls),track.singleTrack());
 }
 private static void extend(ServerWorld world,Runtime train){
  while(train.data.routeAnchors.size()>4)train.data.routeAnchors.remove(0);
  if(train.data.points.size()>4000&&train.data.control.position-train.data.length()>300){
   double cutTarget=Math.min(200,train.data.control.position-train.data.length()-100);
   double cut=0;int trim=0;
   for(int i=1;i<train.data.points.size()&&cut<cutTarget;i++){
    var p1=train.data.points.get(i-1);var p2=train.data.points.get(i);
    cut+=Math.hypot(p2.x()-p1.x(),p2.z()-p1.z());trim=i;
   }
   if(trim>0&&cut>0){
    train.data.points=new ArrayList<>(train.data.points.subList(trim,train.data.points.size()));
    train.data.control.position-=cut;
    train.path=new TrainPath(train.data.points);
   }
  }
  if(train.data.points.size()>16000)return;
  if(train.data.control.direction>=0){
   if(train.data.destination==null||train.path.length-train.data.control.position>100)return;
   var id=train.data.destination;var vertex=StationGraph.vertices(world.getSeed(),id.regionX(),id.regionZ()).stream().filter(v->v.id().equals(id)).findFirst().orElse(null);if(vertex==null)return;var tail=train.path.at(train.path.length);
   for(var edge:StationGraph.incident(world.getSeed(),vertex)){
    if(!edge.a().id().equals(id)||edge.northSouth()!=train.data.northSouth||edge.freight()!=train.data.freight||train.data.routeAnchors.contains(anchor(edge)))continue;
    var next=pathForEdge(world.getSeed(),edge);var start=next.at(0);if(Math.hypot(start.x()-tail.x(),start.z()-tail.z())>.8||Math.abs(start.y()-tail.y())>.2||start.hx()*tail.hx()+start.hz()*tail.hz()<.9)continue;
    train.data.points.addAll(next.points.subList(1,next.points.size()));train.path=new TrainPath(train.data.points);train.data.destination=edge.b().id();train.data.routeAnchors.add(anchor(edge));break;
   }
  }else{
   if(train.data.control.position>100)return;
   var id=train.data.origin;
   if(id==null){
    String[] parts=train.data.anchor.split("→");
    if(parts.length==2){for(int rx=-1;rx<=1;rx++)for(int rz=-1;rz<=1;rz++)for(var v:StationGraph.vertices(world.getSeed(),rx,rz))if(v.id().toString().equals(parts[0]))train.data.origin=v.id();}
    id=train.data.origin;
   }
   if(id==null)return;
   final var targetId=id;
   var vertex=StationGraph.vertices(world.getSeed(),id.regionX(),id.regionZ()).stream().filter(v->v.id().equals(targetId)).findFirst().orElse(null);if(vertex==null)return;
   var head=train.path.at(0);
   for(var edge:StationGraph.incident(world.getSeed(),vertex)){
    if(!edge.b().id().equals(id)||edge.northSouth()!=train.data.northSouth||edge.freight()!=train.data.freight||train.data.routeAnchors.contains(anchor(edge)))continue;
    var prev=pathForEdge(world.getSeed(),edge);var prevTail=prev.at(prev.length);
    if(Math.hypot(prevTail.x()-head.x(),prevTail.z()-head.z())>.8||Math.abs(prevTail.y()-head.y())>.2||prevTail.hx()*head.hx()+prevTail.hz()*head.hz()<.9)continue;
    var merged=new ArrayList<TransitGeometry.Point>(prev.points.subList(0,prev.points.size()-1));
    merged.addAll(train.data.points);train.data.points=merged;
    train.data.control.position+=prev.length;
    train.path=new TrainPath(train.data.points);
    train.data.origin=edge.a().id();train.data.routeAnchors.add(anchor(edge));break;
   }
  }
 }
 public static int spawn(ServerPlayerEntity p,String type){if(!Transit.isNetworkWorld(p.getWorld()))return 0;var edges=StationGraph.nearbyEdges(p.getServerWorld().getSeed(),p.getX(),p.getZ());var edge=edges.stream().filter(e->type.equals("freight")==e.freight()).min(Comparator.comparingDouble(e->Math.min(Math.hypot(e.a().node().x()-p.getX(),e.a().node().z()-p.getZ()),Math.hypot(e.b().node().x()-p.getX(),e.b().node().z()-p.getZ())))).orElse(null);if(edge==null)return 0;boolean atEnd=Math.hypot(edge.b().node().x()-p.getX(),edge.b().node().z()-p.getZ())<Math.hypot(edge.a().node().x()-p.getX(),edge.a().node().z()-p.getZ());var t=create(p.getServerWorld(),edge,type,atEnd);if(t==null){p.sendMessage(Text.literal("На этой ветке уже есть состав либо путь недоступен."));return 0;}t.show(p.getServerWorld());save(p.getServer());p.sendMessage(Text.literal("Состав создан у станции: "+t.data.id+"; /metro-train board, /metro-train panel"));return 1;}
 private static void autoSpawn(ServerPlayerEntity p){if(TRAINS.size()>=12)return;var plan=NetworkPlan.forChunk(p.getServerWorld().getSeed(),p.getChunkPos().getStartX(),p.getChunkPos().getStartZ());for(var hall:plan.stations()){var n=hall.node();if(Math.hypot(n.x()-p.getX(),n.z()-p.getZ())>80||Math.abs(n.y()-p.getY())>20)continue;var edges=StationGraph.nearbyEdges(p.getServerWorld().getSeed(),n.x(),n.z());for(var edge:edges){var a=StationGraph.railNode(edge.a(),edge.northSouth());var b=StationGraph.railNode(edge.b(),edge.northSouth());if(a.equals(n)||b.equals(n)){create(p.getServerWorld(),edge,edge.freight()?"freight":Math.floorMod(hall.salt(),2)==0?"blue":"red",b.equals(n));break;}}}}
 static int couple(ServerPlayerEntity p,Runtime a){if(!a.data.control.stopped()){p.sendMessage(Text.literal("Сцепка возможна только при полной остановке обоих составов."));return 0;}for(var b:new ArrayList<>(TRAINS.values())){if(a==b||!b.data.control.stopped()||!a.path.same(b.path)||a.data.cars.size()+b.data.cars.size()>6)continue;Runtime front=a.data.control.position>b.data.control.position?a:b,back=front==a?b:a;double gap=front.data.control.position-front.data.length()-back.data.control.position;if(gap<-.2||gap>2)continue;front.encodeCargo(p.getServerWorld());back.encodeCargo(p.getServerWorld());front.hide();back.hide();int frontCarsCount=front.data.cars.size();for(int i=front.data.lengths.size();i<front.data.cars.size();i++)front.data.lengths.add(front.data.cars.get(i).length);for(int i=0;i<back.data.cars.size();i++)front.data.lengths.add(back.data.carLength(i));for(int i=front.data.modelIds.size();i<front.data.cars.size();i++){front.data.modelIds.add(front.data.modelAt(i));front.data.modelIndices.add(front.data.modelIndex(i));front.data.modelScales.add(front.data.scaleAt(i));}for(int i=0;i<back.data.cars.size();i++){front.data.modelIds.add(back.data.modelAt(i));front.data.modelIndices.add(back.data.modelIndex(i));front.data.modelScales.add(back.data.scaleAt(i));}front.data.cars.addAll(back.data.cars);if(front.data.origin==null)front.data.origin=back.data.origin;front.data.inventories.addAll(back.data.inventories);TRAINS.remove(back.data.id);TrainPanel.operatorRemoved(back.data.id);for(var e:new ArrayList<>(BOARDING.entrySet())){if(e.getValue().train.equals(back.data.id)){BOARDING.put(e.getKey(),new Boarding(front.data.id,frontCarsCount+e.getValue().car));}}var combined=new Runtime(front.data,p.getServerWorld());TRAINS.put(front.data.id,combined);save(p.getServer());p.sendMessage(Text.literal("Вагоны сцеплены."));return 1;}p.sendMessage(Text.literal("Подведите сцепки одного пути на расстояние до двух блоков; оба состава должны стоять."));return 0;}
 public static int coupleNearest(ServerPlayerEntity p){var t=nearest(p);if(t==null){p.sendMessage(Text.literal("Рядом нет поезда."));return 0;}return couple(p,t);}
 public static int uncouple(ServerPlayerEntity p){var t=nearest(p);if(t==null){p.sendMessage(Text.literal("Рядом нет поезда."));return 0;}if(!t.data.control.stopped()){p.sendMessage(Text.literal("Расцепка возможна только при полной остановке."));return 0;}if(t.data.cars.size()<2){p.sendMessage(Text.literal("В составе должен быть как минимум 2 вагона для расцепки."));return 0;}t.encodeCargo(p.getServerWorld());int index=t.data.cars.size()-1;double position=t.data.control.position-t.data.carOffset(index)+t.data.carLength(index)/2;t.hide();var tail=new TrainData();tail.points=new ArrayList<>(t.data.points);tail.name="Отцепленный вагон";tail.anchor=t.data.anchor;tail.origin=t.data.origin;tail.destination=t.data.destination;tail.northSouth=t.data.northSouth;tail.freight=t.data.freight;tail.routeAnchors=new ArrayList<>(t.data.routeAnchors);tail.lengths.add(t.data.carLength(index));if(index<t.data.lengths.size()){t.data.lengths.remove(index);}tail.model=t.data.modelAt(index);tail.modelScale=t.data.scaleAt(index);tail.modelIds.add(t.data.modelAt(index));tail.modelIndices.add(0);tail.modelScales.add(t.data.scaleAt(index));if(index<t.data.modelIds.size()){t.data.modelIds.remove(index);}if(index<t.data.modelIndices.size()){t.data.modelIndices.remove(index);}if(index<t.data.modelScales.size()){t.data.modelScales.remove(index);}tail.cars.add(t.data.cars.remove(index));tail.inventories.add(t.data.inventories.remove(index));tail.control.position=position;tail.control.pressure=5;for(var e:new ArrayList<>(BOARDING.entrySet())){if(e.getValue().train.equals(t.data.id)&&e.getValue().car==index){BOARDING.put(e.getKey(),new Boarding(tail.id,0));}}TRAINS.put(t.data.id,new Runtime(t.data,p.getServerWorld()));TRAINS.put(tail.id,new Runtime(tail,p.getServerWorld()));save(p.getServer());p.sendMessage(Text.literal("Вагон отцеплен."));return 1;}
 public static int add(ServerPlayerEntity p,String type){var t=nearest(p);if(t==null){p.sendMessage(Text.literal("Рядом нет поезда."));return 0;}if(!t.data.control.stopped()){p.sendMessage(Text.literal("Добавление вагона возможно только при полной остановке."));return 0;}if(t.data.cars.size()>=6){p.sendMessage(Text.literal("Максимальная длина состава — 6 вагонов."));return 0;}TrainCar car;try{car=TrainCar.valueOf(type.toUpperCase(java.util.Locale.ROOT));}catch(IllegalArgumentException e){p.sendMessage(Text.literal("Неизвестный тип вагона: "+type+". Доступны: container, platform, locomotive, metro_blue, metro_red."));return 0;}if(t.data.control.position<t.data.length()+car.length+3){p.sendMessage(Text.literal("Продвиньте состав вперёд: позади не хватает места."));return 0;}t.encodeCargo(p.getServerWorld());t.hide();for(int i=t.data.lengths.size();i<t.data.cars.size();i++)t.data.lengths.add(t.data.cars.get(i).length);for(int i=t.data.modelIds.size();i<t.data.cars.size();i++){t.data.modelIds.add(t.data.modelAt(i));t.data.modelIndices.add(t.data.modelIndex(i));t.data.modelScales.add(t.data.scaleAt(i));}t.data.cars.add(car);t.data.lengths.add(car.length);t.data.modelIds.add("");t.data.modelIndices.add(0);t.data.modelScales.add(1.0);t.data.inventories.add("");TRAINS.put(t.data.id,new Runtime(t.data,p.getServerWorld()));save(p.getServer());p.sendMessage(Text.literal("Вагон "+type+" добавлен к составу."));return 1;}
 public static int removeNearest(ServerPlayerEntity p){var t=nearest(p);if(t==null){p.sendMessage(Text.literal("Рядом нет поезда."));return 0;}return remove(p.getCommandSource(),t.data.id);}
 public static int remove(net.minecraft.server.command.ServerCommandSource source,String id){var t=TRAINS.remove(id);if(t==null){source.sendError(Text.literal("Поезд с ID "+id+" не найден."));return 0;}t.hide();BOARDING.entrySet().removeIf(e->e.getValue().train.equals(id));TrainPanel.operatorRemoved(id);save(source.getServer());source.sendFeedback(()->Text.literal("Поезд "+id+" удалён."),false);return 1;}
 public static int clearAll(net.minecraft.server.command.ServerCommandSource source){int count=TRAINS.size();for(var t:TRAINS.values())t.hide();TRAINS.clear();BOARDING.clear();TrainPanel.reset();save(source.getServer());source.sendFeedback(()->Text.literal("Все поезда удалены ("+count+" шт.)."),false);return count;}
 public static int list(net.minecraft.server.command.ServerCommandSource source){for(var t:TRAINS.values())source.sendFeedback(()->Text.literal(t.data.id+" "+t.data.name+"; вагоны="+t.data.cars.size()+"; скорость="+Math.round(Math.abs(t.data.control.speed)*3.6)+" км/ч"),false);return TRAINS.size();}
}
