import java.util.*;
import eu.metroworld.infrastructure.train.*;
import eu.metroworld.infrastructure.world.TransitGeometry;
public final class TrainChecks {
 static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
 public static void main(String[] args){
  var c=new TrainPhysics();c.position=30;c.power=true;c.compressor=true;c.brake=0;c.traction=5;c.leftDoors=true;for(int i=0;i<400;i++)c.tick(.05,20,1000,0);check(c.speed==0,"doors interlock");check(c.pressure==8,"compressor pressure");
  c.leftDoors=false;for(int i=0;i<200;i++)c.tick(.05,20,1000,.125);check(c.speed>0,"full traction climbs 1:8");check(!c.reverse(),"reverse while moving");check(!c.doors(true),"doors while moving");
  double moving=c.position;c.emergency=true;for(int i=0;i<200;i++)c.tick(.05,20,1000,0);check(c.speed==0,"emergency stop");check(c.position>moving,"braking distance");c.brake=7;check(c.resetEmergency(),"reset while stopped");check(c.reverse()&&c.direction==0,"neutral");check(c.reverse()&&c.direction==-1,"reverse");c.brake=0;for(int i=0;i<400;i++)c.tick(.05,20,1000,0);check(c.position==20&&c.speed==0,"endpoint clamp");
  var p=new TrainPath(List.of(new TransitGeometry.Point(0,0,0),new TransitGeometry.Point(100,10,100)));var pose=p.at(p.length/2);check(Math.abs(pose.x()-50)<1e-9&&Math.abs(pose.z()-50)<1e-9,"true diagonal");check(Math.abs(pose.y()-5)<1e-9,"graded route");var world=pose.world(1,2,3);var local=pose.local(world[0],world[1],world[2]);for(int i=0;i<3;i++)check(Math.abs(local[i]-(i+1))<1e-9,"relative walking frame");
  var data=new TrainData();data.cars.addAll(List.of(TrainCar.LOCOMOTIVE,TrainCar.CONTAINER,TrainCar.PLATFORM));check(data.length()==30&&data.carOffset(2)==25,"consist spacing");data.lengths.addAll(List.of(8.0,12.0,6.0));check(data.length()==28&&data.carOffset(2)==25,"imported lengths");
  var a=new TrainPath.Pose(0,0,0,Math.sqrt(.5),Math.sqrt(.5),0);var separated=a.world(6,0,0);var b=new TrainPath.Pose(separated[0],0,separated[2],a.hx(),a.hz(),0);check(!TrainEnvelope.overlaps(a,10,2.8,b,10,2.8),"parallel diagonal trains do not collide");check(TrainEnvelope.overlaps(a,10,2.8,a,10,2.8),"same track overlap");check(!TrainEnvelope.overlaps(a,10,2.8,new TrainPath.Pose(0,6,0,1,0,0),10,2.8),"different levels");
  System.out.println("TRAIN_CORE_OK: doors, compressor, uphill traction, emergency, reversing, bounds, continuous diagonal/grade, local walking frame and carriage spacing");
 }
}
