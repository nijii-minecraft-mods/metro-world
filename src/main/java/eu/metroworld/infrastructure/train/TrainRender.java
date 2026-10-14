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
 record Part(Entity entity,double x,double y,double z,boolean door,boolean left){}
 final List<Part> parts=new ArrayList<>();final List<ArmorStandEntity> seats=new ArrayList<>();
 final ServerWorld world;final Trains.Runtime train;final int index;final TrainCar car;
 int animationTicks;float leftAmount,rightAmount;
 TrainPath.Pose pose;List<TrainModels.Attachment> imported=List.of();
 TrainRender(ServerWorld world,Trains.Runtime train,int index){this.world=world;this.train=train;this.index=index;car=train.data.cars.get(index);}
 void spawn(){
  double half=train.data.carLength(index)/2;BlockState color=(car==TrainCar.METRO_RED?Blocks.RED_CONCRETE:car==TrainCar.LOCOMOTIVE?Blocks.ORANGE_CONCRETE:Blocks.LIGHT_BLUE_CONCRETE).getDefaultState();
  if(TrainModels.cart(train.data,index)==null){
  box(Blocks.POLISHED_DEEPSLATE.getDefaultState(),-1.4,-.22,-half,2.8,.22,train.data.carLength(index));
  if(car!=TrainCar.PLATFORM){
   if(car==TrainCar.CONTAINER){box(Blocks.CYAN_TERRACOTTA.getDefaultState(),-1.28,0,-half+1,2.56,2.4,train.data.carLength(index)-2);box(Blocks.IRON_BLOCK.getDefaultState(),-1.29,.1,half-1.05,2.58,2.2,.1);}
   else{
    box(color,-1.4,2.5,-half,2.8,.2,train.data.carLength(index));
    for(boolean left:new boolean[]{true,false}){
     double x=left?-1.4:1.3;
     for(int z=-(int)half;z<(int)half;z++){
      boolean door=z==-1||z==0;
      box(color,x,0,z,.1,.6,1);
      if(!door){box(Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState(),x,.6,z,.1,1.25,1);box(color,x,1.85,z,.1,.65,1);}
      else boxDoor(Blocks.LIGHT_GRAY_CONCRETE.getDefaultState(),x,.6,z,.1,1.9,1,left);
     }
    }
    // Ends leave a gangway except for the outside cab faces.
    if(index==0)box(Blocks.GRAY_CONCRETE.getDefaultState(),-1.35,0,half-.12,2.7,1,.12);
    if(index==train.data.cars.size()-1)box(color,-1.35,0,-half,2.7,1,.12);
    for(int i=0;i<car.seats;i++){
     double x=car==TrainCar.LOCOMOTIVE?0:i%2==0?-.95:.95,z=car==TrainCar.LOCOMOTIVE?2:(i/2-1.5)*1.7;
     box(Blocks.BLUE_CONCRETE.getDefaultState(),x-.32,.32,z-.35,.64,.25,.7);
     var seat=new ArmorStandEntity(EntityType.ARMOR_STAND,world);var nbt=new NbtCompound();nbt.putBoolean("Invisible",true);nbt.putBoolean("Marker",true);nbt.putBoolean("NoGravity",true);seat.readNbt(nbt);seat.setInvulnerable(true);seats.add(seat);add(seat,x,-.15,z,false,false);
     hit(x,.4,z,.9f,.9f,"seat",i);
    }
    hit(0,.5,half-1.1,1.1f,1.2f,"panel",0);
   }
  }
  }else imported=TrainModels.spawn(this);
  box(Blocks.BLACK_CONCRETE.getDefaultState(),-1,-.7,-half*.6-.7,2,.35,1.4);
  box(Blocks.BLACK_CONCRETE.getDefaultState(),-1,-.7,half*.6-.7,2,.35,1.4);
  if(car.cargo())hit(0,.3,0,2.7f,2.5f,"cargo",0);
  hit(0,.3,half+.25,1.2f,.8f,"couple",0);
  // Collision floor is vanilla-compatible; seating is independent of floor transport.
  for(int z=-(int)half;z<half+1;z++)for(int x=-1;x<=1;x++){
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
 void move(TrainPath.Pose target){animationTicks++;leftAmount+=(train.data.control.leftDoors?1-leftAmount:-leftAmount)*.15f;rightAmount+=(train.data.control.rightDoors?1-rightAmount:-rightAmount)*.15f;pose=target;for(var p:parts){double offset=p.door&& (p.left?train.data.control.leftDoors:train.data.control.rightDoors)?(p.z<0?-1.25:1.25):0;var at=target.world(p.x,p.y,p.z+offset);p.entity.refreshPositionAndAngles(at[0],at[1],at[2],target.yaw(),target.pitch());if(p.entity instanceof DisplayEntity d){d.setYaw(target.yaw());d.setPitch(target.pitch());d.setBrightness(new Brightness(train.data.control.lights?15:4,0));}if(!p.entity.isRemoved()&&p.entity.getWorld().getEntityById(p.entity.getId())==null)world.spawnEntity(p.entity);}
  if(!imported.isEmpty())TrainModels.update(this,imported,target);
 }
 void dispose(){for(var p:parts){p.entity.discard();Trains.HITS.remove(p.entity.getUuid());Trains.OWNED.remove(p.entity.getUuid());}parts.clear();seats.clear();}
}
