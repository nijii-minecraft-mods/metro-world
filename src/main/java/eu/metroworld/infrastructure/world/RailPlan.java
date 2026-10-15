package eu.metroworld.infrastructure.world;

import java.util.*;
/** Single-block, face-connected rail paths. Corners are flat; slopes use straight cells only. */
public final class RailPlan {
 public record Cell(int x,int floor,int z,double along,String shape){}
 private record Raw(int x,int z,double y,double along){}
 private record Key(List<TransitGeometry.Point> points,boolean single){}
 private static final Map<Key,RailPlan> CACHE=new LinkedHashMap<>(64,.75f,true){
  @Override protected boolean removeEldestEntry(Map.Entry<Key,RailPlan> e){return size()>96;}
 };
 private final List<List<Cell>> lanes;
 private RailPlan(TransitGeometry.Route route,boolean single){lanes=single?List.of(lane(route,0)):List.of(lane(route,-3),lane(route,3));}
 public static RailPlan of(TransitGeometry.Route route){synchronized(CACHE){return CACHE.computeIfAbsent(new Key(route.points(),false),k->new RailPlan(route,false));}}
 public static RailPlan ofSingle(TransitGeometry.Route route){synchronized(CACHE){return CACHE.computeIfAbsent(new Key(route.points(),true),k->new RailPlan(route,true));}}
 public List<List<Cell>> lanes(){return lanes;}
 public int floorAt(double x,double z,TransitGeometry.Sample sample){
  double best=Double.POSITIVE_INFINITY;int floor=(int)Math.floor(sample.floorY());
  for(var lane:lanes){
   int low=0,high=lane.size();while(low<high){int mid=(low+high)>>>1;if(lane.get(mid).along()<sample.along())low=mid+1;else high=mid;}
   for(int i=Math.max(0,low-20);i<Math.min(lane.size(),low+21);i++){
    Cell c=lane.get(i);
    double dx=x-c.x(),dz=z-c.z(),da=(sample.along()-c.along())*.2;
    double score=dx*dx+dz*dz+da*da;
    if(score<best){best=score;floor=c.floor();}
   }
  }
  return floor;
 }
 private static List<Cell> lane(TransitGeometry.Route route,int offset){
  var points=route.points();var raw=new ArrayList<Raw>();double along=0;
  for(int i=0;i<points.size();i++){
   var p=points.get(i);var a=points.get(Math.max(0,i-1));var b=points.get(Math.min(points.size()-1,i+1));
   double hx=b.x()-a.x(),hz=b.z()-a.z(),len=Math.hypot(hx,hz);if(len<1e-8)continue;
   if(i>0)along+=Math.hypot(p.x()-points.get(i-1).x(),p.z()-points.get(i-1).z());
   Raw target=new Raw((int)Math.round(p.x()-hz/len*offset),(int)Math.round(p.z()+hx/len*offset),p.y(),along);
   if(raw.isEmpty()){raw.add(target);continue;}
   Raw last=raw.get(raw.size()-1);int steps=Math.abs(target.x()-last.x())+Math.abs(target.z()-last.z());
   int startX=last.x(),startZ=last.z();double startY=last.y(),startAlong=last.along();
   for(int step=1;step<=steps;step++){
    Raw prev=raw.get(raw.size()-1);int dx=Integer.compare(target.x(),prev.x()),dz=Integer.compare(target.z(),prev.z());
    // Choose the next face-adjacent block closest to the continuous segment.
    double sx=target.x()-startX,sz=target.z()-startZ;
    double errX=dx==0?Double.POSITIVE_INFINITY:Math.abs((prev.x()+dx-startX)*sz-(prev.z()-startZ)*sx);
    double errZ=dz==0?Double.POSITIVE_INFINITY:Math.abs((prev.x()-startX)*sz-(prev.z()+dz-startZ)*sx);
    int x=prev.x()+(errX<=errZ?dx:0),z=prev.z()+(errX<=errZ?0:dz);
    double t=(double)step/steps;Raw next=new Raw(x,z,startY+(target.y()-startY)*t,startAlong+(target.along()-startAlong)*t);
    // Rounded sampling can briefly step back across a voxel boundary. Remove that spur.
    if(raw.size()>1&&raw.get(raw.size()-2).x()==x&&raw.get(raw.size()-2).z()==z&&Math.abs(raw.get(raw.size()-2).y()-next.y())<2)raw.remove(raw.size()-1);
    else raw.add(next);
   }
  }
  int[] heights=new int[raw.size()];heights[0]=(int)Math.floor(raw.get(0).y());
  for(int i=1;i<raw.size();i++){
   int desired=(int)Math.floor(raw.get(i).y()+1e-7);
   heights[i]=heights[i-1]+(straight(raw,i-1)&&straight(raw,i)?Integer.compare(desired,heights[i-1]):0);
  }
  var result=new ArrayList<Cell>();
  for(int i=0;i<raw.size();i++){
   Raw c=raw.get(i),a=raw.get(i==0?Math.min(1,raw.size()-1):i-1),b=raw.get(i==raw.size()-1?Math.max(0,i-1):i+1);
   int ax=a.x()-c.x(),az=a.z()-c.z(),bx=b.x()-c.x(),bz=b.z()-c.z();
   if(i==0){ax=-bx;az=-bz;}if(i==raw.size()-1){bx=-ax;bz=-az;}
   String shape;
   if(i>0&&heights[i-1]>heights[i])shape=ascending(ax,az);
   else if(i+1<raw.size()&&heights[i+1]>heights[i])shape=ascending(bx,bz);
   else if(ax!=0&&bx!=0)shape="east_west";
   else if(az!=0&&bz!=0)shape="north_south";
   else {int dx=ax!=0?ax:bx,dz=az!=0?az:bz;shape=(dz>0?"south":"north")+"_"+(dx>0?"east":"west");}
   result.add(new Cell(c.x(),heights[i],c.z(),c.along(),shape));
  }
  return List.copyOf(result);
 }
 private static boolean straight(List<Raw> raw,int i){
  if(i==0||i==raw.size()-1)return true;
  Raw a=raw.get(i-1),b=raw.get(i),c=raw.get(i+1);
  return b.x()-a.x()==c.x()-b.x()&&b.z()-a.z()==c.z()-b.z();
 }
 private static String ascending(int dx,int dz){return "ascending_"+(dx>0?"east":dx<0?"west":dz>0?"south":"north");}
}
