package eu.metroworld.infrastructure.train;
import java.util.*;
import eu.metroworld.infrastructure.world.TransitGeometry;
/** Continuous arc-length motion. A diagonal never follows the voxel rail's stair-step. */
public final class TrainPath {
 public record Pose(double x,double y,double z,double hx,double hz,double grade){
  public double[] world(double lateral,double height,double longitudinal){return new double[]{x+hz*lateral+hx*longitudinal,y+height+grade*longitudinal,z-hx*lateral+hz*longitudinal};}
  public double[] local(double px,double py,double pz){double dx=px-x,dz=pz-z;double longitudinal=hx*dx+hz*dz;return new double[]{hz*dx-hx*dz,py-y-grade*longitudinal,longitudinal};}
  public float pitch(){return -(float)Math.toDegrees(Math.atan(grade));}
  public float yaw(){return (float)Math.toDegrees(Math.atan2(-hx,hz));}
 }
 public final List<TransitGeometry.Point> points;
 private final double[] distances;
 public final double length;
 public TrainPath(List<TransitGeometry.Point> input){
  if(input.size()<2||input.size()>20000)throw new IllegalArgumentException("route point count");
  var clean=new ArrayList<TransitGeometry.Point>();
  for(var p:input){if(!Double.isFinite(p.x())||!Double.isFinite(p.y())||!Double.isFinite(p.z()))throw new IllegalArgumentException("nonfinite route");if(clean.isEmpty()||Math.hypot(p.x()-clean.get(clean.size()-1).x(),p.z()-clean.get(clean.size()-1).z())>1e-6)clean.add(p);}
  if(clean.size()<2)throw new IllegalArgumentException("empty route");points=List.copyOf(clean);distances=new double[points.size()];
  for(int i=1;i<points.size();i++)distances[i]=distances[i-1]+Math.hypot(points.get(i).x()-points.get(i-1).x(),points.get(i).z()-points.get(i-1).z());length=distances[distances.length-1];
 }
 public Pose at(double distance){
  double d=Math.max(0,Math.min(length,distance));int k=Arrays.binarySearch(distances,d);if(k<0)k=-k-2;k=Math.max(0,Math.min(points.size()-2,k));
  var a=points.get(k);var b=points.get(k+1);double len=distances[k+1]-distances[k],t=(d-distances[k])/len;
  return new Pose(a.x()+(b.x()-a.x())*t,a.y()+(b.y()-a.y())*t,a.z()+(b.z()-a.z())*t,(b.x()-a.x())/len,(b.z()-a.z())/len,(b.y()-a.y())/len);
 }
 public boolean same(TrainPath other){return points.equals(other.points);}
 public static TrainPath lane(TransitGeometry.Route route,boolean single){
  var points=new ArrayList<TransitGeometry.Point>();double along=0;var raw=route.points();
  for(int i=0;i<raw.size();i++){var p=raw.get(i);if(i>0)along+=Math.hypot(p.x()-raw.get(i-1).x(),p.z()-raw.get(i-1).z());var h=route.headingAt(along);double offset=single?0:-3;points.add(new TransitGeometry.Point(p.x()-h.z()*offset+0.5,p.y()+1.2,p.z()+h.x()*offset+0.5));}
  return new TrainPath(points);
 }
}
