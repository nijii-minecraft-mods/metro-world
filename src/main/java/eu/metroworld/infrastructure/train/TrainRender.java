package eu.metroworld.infrastructure.train;
import java.util.*;
import net.minecraft.block.*;
import net.minecraft.entity.*;
import net.minecraft.entity.decoration.*;
import net.minecraft.entity.mob.ShulkerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.AffineTransformation;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Quaternionf;
/** Compact original bodies; imported attachments use the same carriage coordinate frame. */
final class TrainRender {
 static final class Part {
  final Entity entity;final double x,y,z;final boolean door,left;boolean spawned;
  Part(Entity entity,double x,double y,double z,boolean door,boolean left){this.entity=entity;this.x=x;this.y=y;this.z=z;this.door=door;this.left=left;}
  public Entity entity(){return entity;}
 }
 final List<Part> parts=new ArrayList<>();final List<ArmorStandEntity> seats=new ArrayList<>();
 final ServerWorld world;final Trains.Runtime train;final int index;final TrainCar car;
 int animationTicks;float leftAmount,rightAmount;double wheelDistance;
 boolean lastLights;
 TrainPath.Pose pose;List<TrainModels.Attachment> imported=List.of();
 TrainRender(ServerWorld world,Trains.Runtime train,int index){this.world=world;this.train=train;this.index=index;car=train.data.cars.get(index);this.lastLights=!train.data.control.lights;}
 void spawn(){
  double half=train.data.carLength(index)/2;BlockState color=(car==TrainCar.METRO_RED?Blocks.RED_CONCRETE:car==TrainCar.LOCOMOTIVE?Blocks.ORANGE_CONCRETE:Blocks.LIGHT_BLUE_CONCRETE).getDefaultState();
  if(TrainModels.cart(train.data,index)==null){
   // Underfloor steel chassis and interior floor
   box(Blocks.GRAY_CONCRETE.getDefaultState(),-1.35,-.4,-half,2.7,.2,train.data.carLength(index));
   box(Blocks.POLISHED_DEEPSLATE.getDefaultState(),-1.4,-.2,-half,2.8,.2,train.data.carLength(index));
   // Realistic bogies (wheelsets, axles, side frames, bolster)
   double bogieDist=half*.65;
   for(double bz:new double[]{-bogieDist,bogieDist}){
    for(double az:new double[]{bz-.75,bz+.75}){
     box(Blocks.IRON_BLOCK.getDefaultState(),-.74,-1.82,az-.36,.12,.72,.72);
     box(Blocks.IRON_BLOCK.getDefaultState(),.62,-1.82,az-.36,.12,.72,.72);
     box(Blocks.GRAY_CONCRETE.getDefaultState(),-.62,-1.54,az-.08,1.24,.16,.16);
    }
    box(Blocks.POLISHED_BLACKSTONE.getDefaultState(),-.88,-1.6,bz-1.2,.16,.36,2.4);
    box(Blocks.POLISHED_BLACKSTONE.getDefaultState(),.72,-1.6,bz-1.2,.16,.36,2.4);
    box(Blocks.DEEPSLATE_TILES.getDefaultState(),-.85,-1.35,bz-.35,1.7,.25,.7);
   }
   // Underbody machinery between bogies
   box(Blocks.BLACK_CONCRETE.getDefaultState(),-.9,-1.3,-1.4,1.8,.55,1.2);
   box(Blocks.POLISHED_ANDESITE.getDefaultState(),-.55,-1.25,.2,1.1,.45,1.5);
   box(Blocks.IRON_BLOCK.getDefaultState(),-.8,-1.3,2.0,1.6,.55,1.0);

   if(car!=TrainCar.PLATFORM){
    if(car==TrainCar.CONTAINER){
     box(Blocks.CYAN_TERRACOTTA.getDefaultState(),-1.28,0,-half+1,2.56,2.4,train.data.carLength(index)-2);
     box(Blocks.IRON_BLOCK.getDefaultState(),-1.29,.1,half-1.05,2.58,2.2,.1);
    }else{
     box(Blocks.WHITE_CONCRETE.getDefaultState(),-1.4,2.5,-half,2.8,.2,train.data.carLength(index));
     for(boolean left:new boolean[]{true,false}){
      double x=left?-1.4:1.3;
      // Front and rear window sections
      box(color,x,0,-half,.1,.6,half-2);
      box(Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState(),x,.6,-half,.1,1.25,half-2);
      box(color,x,1.85,-half,.1,.65,half-2);

      box(color,x,0,2,.1,.6,half-2);
      box(Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState(),x,.6,2,.1,1.25,half-2);
      box(color,x,1.85,2,.1,.65,half-2);

      // Center pillar between the double doors
      box(color,x,0,0,.1,2.5,1);

      // Two pairs of wide sliding doors
      boxDoor(Blocks.LIGHT_GRAY_CONCRETE.getDefaultState(),x,.6,-2,.1,1.9,1,left);
      boxDoor(Blocks.LIGHT_GRAY_CONCRETE.getDefaultState(),x,.6,-1,.1,1.9,1,left);
      boxDoor(Blocks.LIGHT_GRAY_CONCRETE.getDefaultState(),x,.6,1,.1,1.9,1,left);
      boxDoor(Blocks.LIGHT_GRAY_CONCRETE.getDefaultState(),x,.6,2,.1,1.9,1,left);
     }
     // Front end: cab windshield and headlights
     if(index==0){
      box(color,-1.35,0,half-.12,2.7,.6,.12);
      box(Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState(),-1.35,.6,half-.12,2.7,1.25,.12);
      box(color,-1.35,1.85,half-.12,2.7,.65,.12);
      box(Blocks.SEA_LANTERN.getDefaultState(),-1.1,.15,half-.1,.45,.35,.1);
      box(Blocks.SEA_LANTERN.getDefaultState(),.65,.15,half-.1,.45,.35,.1);
      hit(0,.5,half-1.1,1.1f,1.2f,"panel",0);
     }
     // Rear end: tail marker lights
     if(index==train.data.cars.size()-1){
      box(color,-1.35,0,-half,2.7,2.5,.12);
      box(Blocks.REDSTONE_BLOCK.getDefaultState(),-1.1,.15,-half-.02,.45,.35,.1);
      box(Blocks.REDSTONE_BLOCK.getDefaultState(),.65,.15,-half-.02,.45,.35,.1);
     }
     // Passenger seating
     for(int i=0;i<car.seats;i++){
      double x=car==TrainCar.LOCOMOTIVE?0:i%2==0?-.95:.95,z=car==TrainCar.LOCOMOTIVE?2:(i/2-1.5)*1.7;
      box(Blocks.BLUE_CONCRETE.getDefaultState(),x-.32,.32,z-.35,.64,.25,.7);
      var seat=new ArmorStandEntity(EntityType.ARMOR_STAND,world);var nbt=new NbtCompound();nbt.putBoolean("Invisible",true);nbt.putBoolean("Marker",true);nbt.putBoolean("NoGravity",true);seat.readNbt(nbt);seat.setInvulnerable(true);seats.add(seat);add(seat,x,-.15,z,false,false);
      hit(x,.4,z,.9f,.9f,"seat",i);
     }
    }
   }
  }else imported=TrainModels.spawn(this);
  if(car.cargo())hit(0,.3,0,2.7f,2.5f,"cargo",0);
  if(index==0)hit(0,.3,half+.25,1.2f,.8f,"couple",0);
  // Collision floor is vanilla-compatible; seating is independent of floor transport.
  for(int z=-(int)half;z<(int)half;z++)for(double x:new double[]{-.6,.6}){
   var anchor=new DisplayEntity.BlockDisplayEntity(EntityType.BLOCK_DISPLAY,world);anchor.setBlockState(Blocks.AIR.getDefaultState());anchor.setTeleportDuration(1);add(anchor,x,-1,z+.5,false,false);
   var floor=new ShulkerEntity(EntityType.SHULKER,world);floor.addCommandTag("metro-world:train-floor");floor.setAiDisabled(true);floor.setInvisible(true);floor.setSilent(true);floor.setInvulnerable(true);floor.setNoGravity(true);add(floor,x,-1,z+.5,false,false);floor.startRiding(anchor,true);
  }
 }
 void box(BlockState state,double x,double y,double z,double sx,double sy,double sz){boxPart(state,x,y,z,sx,sy,sz,false,false);}
 void boxDoor(BlockState state,double x,double y,double z,double sx,double sy,double sz,boolean left){boxPart(state,x,y,z,sx,sy,sz,true,left);}
 void boxPart(BlockState state,double x,double y,double z,double sx,double sy,double sz,boolean door,boolean left){
  var e=new DisplayEntity.BlockDisplayEntity(EntityType.BLOCK_DISPLAY,world);e.setBlockState(state);e.setTransformation(new AffineTransformation(new Vector3f(),new Quaternionf(),new Vector3f((float)sx,(float)sy,(float)sz),new Quaternionf()));e.setTeleportDuration(1);e.setViewRange(1);add(e,x,y,z,door,left);
 }
 void hit(double x,double y,double z,float width,float height,String action,int seat){var e=new InteractionEntity(EntityType.INTERACTION,world);e.setInteractionWidth(width);e.setInteractionHeight(height);Trains.HITS.put(e.getUuid(),new Trains.Hit(train.data.id,index,action,seat));add(e,x,y,z,false,false);}
 void add(Entity e,double x,double y,double z,boolean door,boolean left){e.addCommandTag(Trains.RENDER_TAG);e.setNoGravity(true);Trains.OWNED.add(e.getUuid());parts.add(new Part(e,x,y,z,door,left));}
 void move(TrainPath.Pose target){
  animationTicks++;wheelDistance+=Math.abs(train.data.control.speed)/20.0;
  leftAmount+=(train.data.control.leftDoors?1-leftAmount:-leftAmount)*.15f;
  rightAmount+=(train.data.control.rightDoors?1-rightAmount:-rightAmount)*.15f;
  pose=target;
  boolean lightsChanged=train.data.control.lights!=lastLights;
  lastLights=train.data.control.lights;
  Brightness brightness=lightsChanged?new Brightness(train.data.control.lights?15:4,0):null;
  for(var p:parts){
   double offset=p.door&&(p.left?train.data.control.leftDoors:train.data.control.rightDoors)?(p.z<0?-1.25:1.25):0;
   double effZ=p.z+offset;
   double wx=target.x()+target.hz()*p.x+target.hx()*effZ;
   double wy=target.y()+p.y+target.grade()*effZ;
   double wz=target.z()-target.hx()*p.x+target.hz()*effZ;
   p.entity.refreshPositionAndAngles(wx,wy,wz,target.yaw(),target.pitch());
   if(p.entity instanceof DisplayEntity d){
    d.setYaw(target.yaw());d.setPitch(target.pitch());
    if(lightsChanged)d.setBrightness(brightness);
   }
   if(!p.spawned&&!p.entity.isRemoved()){
    if(world.getEntity(p.entity.getUuid())==null)world.spawnEntity(p.entity);
    p.spawned=true;
   }
  }
  if(!imported.isEmpty())TrainModels.update(this,imported,target);
 }
 void dispose(){for(var p:parts){p.entity.discard();Trains.HITS.remove(p.entity.getUuid());Trains.OWNED.remove(p.entity.getUuid());}parts.clear();seats.clear();}
}
