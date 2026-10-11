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
  void hide(){for(var car:render)car.dispose();render.clear();}
  void encodeCargo(ServerWorld world){data.inventories.clear();for(var inv:cargo){var root=new NbtCompound();var list=new NbtList();for(int slot=0;slot<54;slot++)if(!inv.getStack(slot).isEmpty()){var entry=new NbtCompound();entry.putInt("Slot",slot);entry.put("Stack",inv.getStack(slot).encode(world.getRegistryManager()));list.add(entry);}root.put("Items",list);data.inventories.add(root.toString());}}
 }
 public static void initialize(){
  TrainModels.initialize();
  UseEntityCallback.EVENT.register((player,world,hand,entity,hit)->{var control=HITS.get(entity.getUuid());if(control==null||!(player instanceof ServerPlayerEntity p))return ActionResult.PASS;if(hand!=net.minecraft.util.Hand.MAIN_HAND)return ActionResult.SUCCESS;var train=TRAINS.get(control.train);if(train==null||!train.nearby(p))return ActionResult.FAIL;switch(control.action){case "panel"->TrainPanel.open(p,train);case "cargo"->cargo(p,train,control.car);case "seat"->seat(p,train,control.car,control.seat);case "couple"->couple(p,train);}return ActionResult.SUCCESS;});
  ServerEntityEvents.ENTITY_LOAD.register((entity,world)->{if(entity.getCommandTags().contains(RENDER_TAG)&&!OWNED.contains(entity.getUuid()))entity.discard();});
 }
 public static Runtime nearest(ServerPlayerEntity p){Runtime best=null;double distance=196;for(var t:TRAINS.values())if(t.nearby(p))for(int i=0;i<t.data.cars.size();i++){var pose=t.pose(i);double d=p.squaredDistanceTo(pose.x(),pose.y(),pose.z());if(d<distance){distance=d;best=t;}}return best;}
 public static void load(MinecraftServer server){reset();var world=server.getWorld(Transit.UNDERGROUND);if(world==null)return;Path file=savePath(server);if(!Files.exists(file))return;
  try{List<TrainData> data=JSON.fromJson(Files.readString(file),new TypeToken<List<TrainData>>(){}.getType());if(data==null||data.size()>48)throw new IllegalArgumentException("train count");for(var d:data){if(d.cars==null||d.cars.isEmpty()||d.cars.size()>6||d.control==null)throw new IllegalArgumentException("invalid consist");if(TRAINS.containsKey(d.id))throw new IllegalArgumentException("duplicate train id");var t=new Runtime(d,world);d.control.position=Math.max(d.length(),Math.min(t.path.length,d.control.position));d.control.speed=0;d.control.traction=0;d.control.brake=7;TRAINS.put(d.id,t);}}
  catch(Exception e){saveAllowed=false;throw new IllegalStateException("Metro World refuses to overwrite corrupt train save "+file,e);}
 }
 private static Path savePath(MinecraftServer s){return s.getSavePath(WorldSavePath.ROOT).resolve("metro-world-trains.json");}
 public static void save(MinecraftServer server){if(!saveAllowed)return;var world=server.getWorld(Transit.UNDERGROUND);if(world==null)return;try{for(var t:TRAINS.values())t.encodeCargo(world);Path file=savePath(server),tmp=file.resolveSibling(file.getFileName()+".tmp");Files.writeString(tmp,JSON.toJson(TRAINS.values().stream().map(t->t.data).toList()));try{Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);}}catch(Exception e){throw new IllegalStateException("Cannot save metro trains",e);}}
 public static void reset(){for(var t:TRAINS.values())t.hide();TRAINS.clear();BOARDING.clear();OWNED.clear();HITS.clear();saveAllowed=true;TrainPanel.reset();}
 public static void tick(MinecraftServer server){var world=server.getWorld(Transit.UNDERGROUND);if(world==null)return;
  BOARDING.entrySet().removeIf(e->{var p=server.getPlayerManager().getPlayer(e.getKey());return p==null||!Transit.isNetworkWorld(p.getWorld())||!TRAINS.containsKey(e.getValue().train);});
  int active=0;
  for(var t:TRAINS.values()){
   boolean rider=t.render.stream().anyMatch(r->r.seats.stream().anyMatch(net.minecraft.entity.Entity::hasPassengers))||BOARDING.values().stream().anyMatch(b->b.train.equals(t.data.id));
   boolean near=server.getPlayerManager().getPlayerList().stream().anyMatch(p->{if(!Transit.isNetworkWorld(p.getWorld()))return false;var at=t.pose(0);return p.squaredDistanceTo(at.x(),at.y(),at.z())<256*256;});
   if(!near||active++>=4&&!rider){if(!t.render.isEmpty()){t.data.control.speed=0;t.data.control.traction=0;t.data.control.brake=7;t.hide();}continue;}
   t.show(world);var old=new ArrayList<TrainPath.Pose>();for(int i=0;i<t.data.cars.size();i++)old.add(t.pose(i));
   if(!t.data.hasTraction()){t.data.control.power=false;t.data.control.compressor=false;t.data.control.traction=0;}
   extend(world,t);
   double before=t.data.control.position;t.data.control.tick(.05,t.data.length()+2,t.path.length-2,t.path.at(before).grade());
   // Compare occupied intervals, so a split consist can move apart but cannot overlap.
   for(var other:TRAINS.values())if(other!=t&&t.path.same(other.path)){
    double oldGap=gap(before,t.data.length(),other.data.control.position,other.data.length());
    double newGap=gap(t.data.control.position,t.data.length(),other.data.control.position,other.data.length());
    if(newGap<Math.min(.8,oldGap)-.0001){t.data.control.position=before;t.data.control.speed=0;t.data.control.brake=7;break;}
   }
   boolean occupied=false;
   for(var other:TRAINS.values())if(other!=t)for(int i=0;i<t.data.cars.size();i++)for(int j=0;j<other.data.cars.size();j++){
    var a=t.pose(i);var b=other.pose(j);if(!TrainEnvelope.overlaps(a,t.data.carLength(i),2.8,b,other.data.carLength(j),2.8))continue;
    double oldDistance=Math.hypot(old.get(i).x()-b.x(),old.get(i).z()-b.z()),newDistance=Math.hypot(a.x()-b.x(),a.z()-b.z());if(newDistance<oldDistance-.0001)occupied=true;
   }
   if(occupied||!clear(world,t)){t.data.control.position=before;t.data.control.speed=0;t.data.control.traction=0;t.data.control.brake=7;}
   for(int i=0;i<t.render.size();i++){
    var pose=t.pose(i);ChunkPos chunk=new ChunkPos(BlockPos.ofFloored(pose.x(),pose.y(),pose.z()));world.getChunkManager().addTicket(TICKET,chunk,2,t.data.id);world.getChunk(chunk.x,chunk.z);
    carry(server,t,i,old.get(i),pose);t.render.get(i).move(pose);
   }
  }
  if(server.getTicks()%100==0){for(var p:server.getPlayerManager().getPlayerList())if(Transit.isNetworkWorld(p.getWorld()))autoSpawn(p);}
  if(server.getTicks()%200==0)save(server);
 }
 static double gap(double a,double al,double b,double bl){return Math.max(a-al-b,b-bl-a);}
 private static boolean clear(ServerWorld world,Runtime train){
  for(int i=0;i<train.data.cars.size();i++){
   var pose=train.pose(i);double half=train.data.carLength(i)/2;
   for(double z=-half+.3;z<=half-.2;z+=1)for(double x:new double[]{-1.2,0,1.2})for(double y:new double[]{.35,1.3,2.5}){
    var at=pose.world(x,y,z);var pos=BlockPos.ofFloored(at[0],at[1],at[2]);world.getChunk(Math.floorDiv(pos.getX(),16),Math.floorDiv(pos.getZ(),16));
    if(!world.getBlockState(pos).getCollisionShape(world,pos).isEmpty())return false;
   }
  }return true;
 }
 private static void carry(MinecraftServer server,Runtime train,int index,TrainPath.Pose old,TrainPath.Pose next){
  for(var p:server.getPlayerManager().getPlayerList()){
   if(!Transit.isNetworkWorld(p.getWorld())||p.isSpectator()||p.hasVehicle())continue;
   var local=old.local(p.getX(),p.getY(),p.getZ());var car=train.data.cars.get(index);
   var entry=BOARDING.get(p.getUuid());boolean ours=entry!=null&&entry.train.equals(train.data.id)&&entry.car==index;
   if(!ours){if((entry!=null&&TRAINS.containsKey(entry.train))||Math.abs(local[0])>1.15||Math.abs(local[2])>train.data.carLength(index)/2||local[1]<-.15||local[1]>.35)continue;
    boolean floor=worldFloor(p);if(!floor)continue;BOARDING.put(p.getUuid(),new Boarding(train.data.id,index));}
   if(Math.abs(local[0])>1.65||Math.abs(local[2])>train.data.carLength(index)/2+1.2||local[1]<-1||local[1]>3.5){BOARDING.remove(p.getUuid());continue;}
   double lateral=local[0];if(!train.data.control.leftDoors&&!train.data.control.rightDoors)lateral=Math.max(-1.05,Math.min(1.05,lateral));
   var at=next.world(lateral,local[1],local[2]);
   if(Math.abs(at[0]-p.getX())+Math.abs(at[1]-p.getY())+Math.abs(at[2]-p.getZ())>.001)p.networkHandler.requestTeleport(at[0],at[1],at[2],p.getYaw(),p.getPitch());
   p.fallDistance=0;
  }
 }
 private static boolean worldFloor(ServerPlayerEntity p){return p.getWorld().getOtherEntities(p,p.getBoundingBox().offset(0,-.3,0),e->e.getCommandTags().contains("metro-world:train-floor")).size()>0;}
 public static int board(ServerPlayerEntity p){var t=nearest(p);if(t==null){p.sendMessage(Text.literal("Рядом нет поезда."));return 0;}if(!t.data.control.stopped()||!t.data.control.leftDoors&&!t.data.control.rightDoors){p.sendMessage(Text.literal("Посадка: остановите поезд и откройте двери через пульт."));return 0;}var at=t.pose(0).world(0,.05,0);p.requestTeleport(at[0],at[1],at[2]);BOARDING.put(p.getUuid(),new Boarding(t.data.id,0));return 1;}
 static int seat(ServerPlayerEntity p,Runtime t,int car,int seat){if(t.render.isEmpty()||seat<0||seat>=t.render.get(car).seats.size())return 0;var entity=t.render.get(car).seats.get(seat);if(entity.hasPassengers())return 0;if(p.squaredDistanceTo(entity)>9)return 0;BOARDING.remove(p.getUuid());return p.startRiding(entity,true)?1:0;}
 public static int seatNearest(ServerPlayerEntity p,int seat){var t=nearest(p);if(t==null)return 0;int car=0;double best=Double.MAX_VALUE;for(int i=0;i<t.data.cars.size();i++){var at=t.pose(i);double d=p.squaredDistanceTo(at.x(),at.y(),at.z());if(d<best){best=d;car=i;}}return seat(p,t,car,seat);}
 public static int stand(ServerPlayerEntity p){var vehicle=p.getVehicle();if(vehicle==null)return 0;for(var t:TRAINS.values())for(int i=0;i<t.render.size();i++)if(t.render.get(i).seats.contains(vehicle)){p.stopRiding();var at=t.pose(i).world(0,.05,0);p.requestTeleport(at[0],at[1],at[2]);BOARDING.put(p.getUuid(),new Boarding(t.data.id,i));return 1;}return 0;}
 static int cargo(ServerPlayerEntity p,Runtime t,int index){var at=t.pose(index);if(p.squaredDistanceTo(at.x(),at.y(),at.z())>64||!t.data.control.stopped()||!t.data.cars.get(index).cargo()||!t.nearby(p))return 0;p.openHandledScreen(new SimpleNamedScreenHandlerFactory((sync,inv,player)->new GenericContainerScreenHandler(net.minecraft.screen.ScreenHandlerType.GENERIC_9X6,sync,inv,t.cargo.get(index),6){@Override public boolean canUse(net.minecraft.entity.player.PlayerEntity player){var at=t.pose(index);return TRAINS.get(t.data.id)==t&&t.data.control.stopped()&&player instanceof ServerPlayerEntity sp&&t.nearby(sp)&&sp.squaredDistanceTo(at.x(),at.y(),at.z())<64;}@Override public void onClosed(net.minecraft.entity.player.PlayerEntity player){super.onClosed(player);if(player instanceof ServerPlayerEntity sp)save(sp.getServer());}},Text.literal("Контейнер — "+t.data.name)));return 1;}
 public static int cargoNearest(ServerPlayerEntity p){var t=nearest(p);if(t==null)return 0;for(int i=0;i<t.data.cars.size();i++)if(t.data.cars.get(i).cargo())return cargo(p,t,i);return 0;}
 private static String anchor(StationGraph.Edge edge){return edge.a().id()+"→"+edge.b().id();}
 public static Runtime create(ServerWorld world,StationGraph.Edge edge,String type){return create(world,edge,type,false);}
 public static Runtime create(ServerWorld world,StationGraph.Edge edge,String type,boolean atEnd){
  if(TRAINS.size()>=48)return null;var routes=NetworkPlan.trackRoutes(world.getSeed(),edge);if(routes.isEmpty())return null;String anchor=anchor(edge);if(TRAINS.values().stream().anyMatch(t->(t.data.anchor.equals(anchor)||t.data.routeAnchors.contains(anchor))))return null;
  var data=new TrainData();data.anchor=anchor;data.routeAnchors.add(anchor);data.destination=edge.b().id();data.northSouth=edge.northSouth();data.freight=edge.freight();data.points=new ArrayList<>(pathForEdge(world.getSeed(),edge).points);
  if(type.equals("freight")){data.name="Грузовой состав";data.cars.addAll(List.of(TrainCar.LOCOMOTIVE,TrainCar.CONTAINER,TrainCar.PLATFORM));}else{boolean red=type.equals("red");data.name=red?"Метро — красный":"Метро — голубой";data.cars.addAll(List.of(red?TrainCar.METRO_RED:TrainCar.METRO_BLUE,red?TrainCar.METRO_RED:TrainCar.METRO_BLUE));}
  data.control.position=atEnd?new TrainPath(data.points).length-5:data.length()+5;data.control.pressure=5;data.control.leftDoors=true;var runtime=new Runtime(data,world);TRAINS.put(data.id,runtime);return runtime;
 }
 private static TrainPath pathForEdge(long seed,StationGraph.Edge edge){
  var track=NetworkPlan.trackRoutes(seed,edge).get(0);var controls=new ArrayList<TransitGeometry.Point>(track.route().points());var a=StationGraph.railNode(edge.a(),edge.northSouth());var b=StationGraph.railNode(edge.b(),edge.northSouth());var first=controls.get(0);var last=controls.get(controls.size()-1);
  controls.add(0,new TransitGeometry.Point(edge.northSouth()&&track.singleTrack()?first.x():a.x(),a.y(),!edge.northSouth()&&track.singleTrack()?first.z():a.z()));
  controls.add(new TransitGeometry.Point(edge.northSouth()&&track.singleTrack()?last.x():b.x(),b.y(),!edge.northSouth()&&track.singleTrack()?last.z():b.z()));return TrainPath.lane(new TransitGeometry.Route(controls),track.singleTrack());
 }
 private static void extend(ServerWorld world,Runtime train){
  if(train.data.destination==null||train.data.control.direction<0||train.path.length-train.data.control.position>100||train.data.points.size()>16000)return;
  var id=train.data.destination;var vertex=StationGraph.vertices(world.getSeed(),id.regionX(),id.regionZ()).stream().filter(v->v.id().equals(id)).findFirst().orElse(null);if(vertex==null)return;var tail=train.path.at(train.path.length);
  for(var edge:StationGraph.incident(world.getSeed(),vertex)){
   if(!edge.a().id().equals(id)||edge.northSouth()!=train.data.northSouth||edge.freight()!=train.data.freight||train.data.routeAnchors.contains(anchor(edge)))continue;
   var next=pathForEdge(world.getSeed(),edge);var start=next.at(0);if(Math.hypot(start.x()-tail.x(),start.z()-tail.z())>.8||Math.abs(start.y()-tail.y())>.2||start.hx()*tail.hx()+start.hz()*tail.hz()<.9)continue;
   train.data.points.addAll(next.points.subList(1,next.points.size()));train.path=new TrainPath(train.data.points);train.data.destination=edge.b().id();train.data.routeAnchors.add(anchor(edge));break;
  }
 }
 public static int spawn(ServerPlayerEntity p,String type){if(!Transit.isNetworkWorld(p.getWorld()))return 0;var edges=StationGraph.nearbyEdges(p.getServerWorld().getSeed(),p.getX(),p.getZ());var edge=edges.stream().filter(e->type.equals("freight")==e.freight()).min(Comparator.comparingDouble(e->Math.min(Math.hypot(e.a().node().x()-p.getX(),e.a().node().z()-p.getZ()),Math.hypot(e.b().node().x()-p.getX(),e.b().node().z()-p.getZ())))).orElse(null);if(edge==null)return 0;boolean atEnd=Math.hypot(edge.b().node().x()-p.getX(),edge.b().node().z()-p.getZ())<Math.hypot(edge.a().node().x()-p.getX(),edge.a().node().z()-p.getZ());var t=create(p.getServerWorld(),edge,type,atEnd);if(t==null){p.sendMessage(Text.literal("На этой ветке уже есть состав либо путь недоступен."));return 0;}t.show(p.getServerWorld());save(p.getServer());p.sendMessage(Text.literal("Состав создан у станции: "+t.data.id+"; /metro-train board, /metro-train panel"));return 1;}
 private static void autoSpawn(ServerPlayerEntity p){if(TRAINS.size()>=12)return;var plan=NetworkPlan.forChunk(p.getServerWorld().getSeed(),p.getChunkPos().getStartX(),p.getChunkPos().getStartZ());for(var hall:plan.stations()){var n=hall.node();if(Math.hypot(n.x()-p.getX(),n.z()-p.getZ())>80||Math.abs(n.y()-p.getY())>20)continue;var edges=StationGraph.nearbyEdges(p.getServerWorld().getSeed(),n.x(),n.z());for(var edge:edges){var a=StationGraph.railNode(edge.a(),edge.northSouth());var b=StationGraph.railNode(edge.b(),edge.northSouth());if(a.equals(n)||b.equals(n)){create(p.getServerWorld(),edge,edge.freight()?"freight":Math.floorMod(hall.salt(),2)==0?"blue":"red",b.equals(n));break;}}}}
 static int couple(ServerPlayerEntity p,Runtime a){if(!a.data.control.stopped())return 0;for(var b:new ArrayList<>(TRAINS.values())){if(a==b||!b.data.control.stopped()||!a.path.same(b.path)||a.data.cars.size()+b.data.cars.size()>6)continue;Runtime front=a.data.control.position>b.data.control.position?a:b,back=front==a?b:a;double gap=front.data.control.position-front.data.length()-back.data.control.position;if(gap<-.2||gap>2)continue;front.encodeCargo(p.getServerWorld());back.encodeCargo(p.getServerWorld());front.hide();back.hide();int frontCarsCount=front.data.cars.size();for(int i=front.data.lengths.size();i<front.data.cars.size();i++)front.data.lengths.add(front.data.cars.get(i).length);for(int i=0;i<back.data.cars.size();i++)front.data.lengths.add(back.data.carLength(i));for(int i=front.data.modelIds.size();i<front.data.cars.size();i++){front.data.modelIds.add(front.data.modelAt(i));front.data.modelIndices.add(front.data.modelIndex(i));front.data.modelScales.add(front.data.scaleAt(i));}for(int i=0;i<back.data.cars.size();i++){front.data.modelIds.add(back.data.modelAt(i));front.data.modelIndices.add(back.data.modelIndex(i));front.data.modelScales.add(back.data.scaleAt(i));}front.data.cars.addAll(back.data.cars);front.data.inventories.addAll(back.data.inventories);TRAINS.remove(back.data.id);TrainPanel.operatorRemoved(back.data.id);for(var e:new ArrayList<>(BOARDING.entrySet())){if(e.getValue().train.equals(back.data.id)){BOARDING.put(e.getKey(),new Boarding(front.data.id,frontCarsCount+e.getValue().car));}}var combined=new Runtime(front.data,p.getServerWorld());TRAINS.put(front.data.id,combined);save(p.getServer());p.sendMessage(Text.literal("Вагоны сцеплены."));return 1;}p.sendMessage(Text.literal("Подведите сцепки одного пути на расстояние до двух блоков; оба состава должны стоять."));return 0;}
 public static int coupleNearest(ServerPlayerEntity p){var t=nearest(p);return t==null?0:couple(p,t);}
 public static int uncouple(ServerPlayerEntity p){var t=nearest(p);if(t==null||!t.data.control.stopped()||t.data.cars.size()<2)return 0;t.encodeCargo(p.getServerWorld());int index=t.data.cars.size()-1;double position=t.data.control.position-t.data.carOffset(index)+t.data.carLength(index)/2;t.hide();var tail=new TrainData();tail.points=new ArrayList<>(t.data.points);tail.name="Отцепленный вагон";tail.lengths.add(t.data.carLength(index));if(index<t.data.lengths.size()){t.data.lengths.remove(index);}tail.model=t.data.modelAt(index);tail.modelScale=t.data.scaleAt(index);tail.modelIds.add(t.data.modelAt(index));tail.modelIndices.add(0);tail.modelScales.add(t.data.scaleAt(index));if(index<t.data.modelIds.size()){t.data.modelIds.remove(index);}if(index<t.data.modelIndices.size()){t.data.modelIndices.remove(index);}if(index<t.data.modelScales.size()){t.data.modelScales.remove(index);}tail.cars.add(t.data.cars.remove(index));tail.inventories.add(t.data.inventories.remove(index));tail.control.position=position;tail.control.pressure=5;for(var e:new ArrayList<>(BOARDING.entrySet())){if(e.getValue().train.equals(t.data.id)&&e.getValue().car==index){BOARDING.put(e.getKey(),new Boarding(tail.id,0));}}TRAINS.put(t.data.id,new Runtime(t.data,p.getServerWorld()));TRAINS.put(tail.id,new Runtime(tail,p.getServerWorld()));save(p.getServer());return 1;}
 public static int add(ServerPlayerEntity p,String type){var t=nearest(p);if(t==null||!t.data.control.stopped()||t.data.cars.size()>=6)return 0;TrainCar car=TrainCar.valueOf(type.toUpperCase(java.util.Locale.ROOT));if(t.data.control.position<t.data.length()+car.length+3){p.sendMessage(Text.literal("Продвиньте состав вперёд: позади не хватает места."));return 0;}t.encodeCargo(p.getServerWorld());t.hide();for(int i=t.data.lengths.size();i<t.data.cars.size();i++)t.data.lengths.add(t.data.cars.get(i).length);for(int i=t.data.modelIds.size();i<t.data.cars.size();i++){t.data.modelIds.add(t.data.modelAt(i));t.data.modelIndices.add(t.data.modelIndex(i));t.data.modelScales.add(t.data.scaleAt(i));}t.data.cars.add(car);t.data.lengths.add(car.length);t.data.modelIds.add("");t.data.modelIndices.add(0);t.data.modelScales.add(1.0);t.data.inventories.add("");TRAINS.put(t.data.id,new Runtime(t.data,p.getServerWorld()));save(p.getServer());return 1;}
 public static int removeNearest(ServerPlayerEntity p){var t=nearest(p);if(t==null){p.sendMessage(Text.literal("Рядом нет поезда."));return 0;}return remove(p.getCommandSource(),t.data.id);}
 public static int remove(net.minecraft.server.command.ServerCommandSource source,String id){var t=TRAINS.remove(id);if(t==null){source.sendError(Text.literal("Поезд с ID "+id+" не найден."));return 0;}t.hide();BOARDING.entrySet().removeIf(e->e.getValue().train.equals(id));TrainPanel.operatorRemoved(id);save(source.getServer());source.sendFeedback(()->Text.literal("Поезд "+id+" удалён."),false);return 1;}
 public static int clearAll(net.minecraft.server.command.ServerCommandSource source){int count=TRAINS.size();for(var t:TRAINS.values())t.hide();TRAINS.clear();BOARDING.clear();TrainPanel.reset();save(source.getServer());source.sendFeedback(()->Text.literal("Все поезда удалены ("+count+" шт.)."),false);return count;}
 public static int list(net.minecraft.server.command.ServerCommandSource source){for(var t:TRAINS.values())source.sendFeedback(()->Text.literal(t.data.id+" "+t.data.name+"; вагоны="+t.data.cars.size()+"; скорость="+Math.round(Math.abs(t.data.control.speed)*3.6)+" км/ч"),false);return TRAINS.size();}
}
