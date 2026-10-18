package eu.metroworld.infrastructure.world;
import java.util.*;
/** Immutable spatial plan. Stations own explicit ports; routes never start inside platforms. */
public final class NetworkPlan {
 public enum StationKind { PASSENGER, MINI, TERMINAL, INTERCHANGE, MIXED, FREIGHT, BIOCENTER }
 public static StationKind stationKind(Node n,long salt){
  
  int roll=Math.floorMod((int)(salt>>>12),10);return roll<2?StationKind.MINI:roll==2?StationKind.TERMINAL:roll<5?StationKind.FREIGHT:roll<7?StationKind.BIOCENTER:StationKind.PASSENGER;
 }
 public enum TrackLayout { CENTRAL, ISLAND }
 public enum Condition { INTACT, COLLAPSED, CHEMICAL, QUARANTINE }
 public static final int SHELL=6;
 public record Node(int x,int z,int y){}
 public static int halfLength(StationKind kind){return kind==StationKind.MINI?28:kind==StationKind.TERMINAL?54:40;}
 public static int halfWidth(StationKind kind){return kind==StationKind.MINI?12:kind==StationKind.TERMINAL?22:17;}
 public static int stationHeight(StationKind kind){return kind==StationKind.MINI?10:kind==StationKind.TERMINAL?18:14;}
 public record Station(Node node,long salt,Condition condition,boolean interchange,boolean northSouth,boolean portal,StationKind kind,TrackLayout trackLayout,int trackOffset,boolean deadEndMinus,boolean deadEndPlus){
  public Station(Node node,long salt,Condition condition){this(node,salt,condition,false,false,false,stationKind(node,salt),TrackLayout.CENTRAL,0,false,false);}
  public Station(Node node,long salt,Condition condition,boolean interchange){this(node,salt,condition,interchange,false,false,stationKind(node,salt),TrackLayout.CENTRAL,0,false,false);}
  public Station(Node node,long salt,Condition condition,boolean interchange,boolean northSouth,boolean portal,StationKind kind,TrackLayout trackLayout,int trackOffset){this(node,salt,condition,interchange,northSouth,portal,kind,trackLayout,trackOffset,false,false);}
  public boolean intersects(int x,int z,int margin){
   int length=halfLength(kind),minX=-length,maxX=length,minZ=-halfWidth(kind),maxZ=halfWidth(kind)+16;
   if(kind==StationKind.BIOCENTER){minZ=-62-DomeDesign.spec(salt).outerRadius();maxX=Math.max(maxX,82);}
   if(SideBranchDesign.present(salt)){var spec=SideBranchDesign.select(salt,length);minX=Math.min(minX,SideBranchDesign.minX(spec));maxZ=Math.max(maxZ,SideBranchDesign.maxZ(spec));}
   if(deadEndMinus)minX=Math.min(minX,-length-36);if(deadEndPlus)maxX=Math.max(maxX,length+36);
   return northSouth?node.x-minZ+margin>=x&&node.x-maxZ-margin<=x+15&&node.z+maxX+margin>=z&&node.z+minX-margin<=z+15:
    node.x+maxX+margin>=x&&node.x+minX-margin<=x+15&&node.z+maxZ+margin>=z&&node.z+minZ-margin<=z+15;
  }
 }
 public record Path(TransitGeometry.Route route,Condition condition,long salt,boolean service,boolean transfer,boolean singleTrack,boolean freight){
  public Path(TransitGeometry.Route route,Condition condition,long salt,boolean service,boolean transfer,boolean singleTrack){this(route,condition,salt,service,transfer,singleTrack,false);}
  public Path(TransitGeometry.Route route,Condition condition,long salt,boolean service){this(route,condition,salt,service,false,false);}
  public Path(TransitGeometry.Route route,Condition condition,long salt,boolean service,boolean transfer){this(route,condition,salt,service,transfer,false);}
 }
 public record TrackRoute(TransitGeometry.Route route,boolean singleTrack,long salt){}
 public record Room(int x1,int x2,int z1,int z2,int base,int height,Condition condition,long salt){
  public boolean intersects(int x,int z,int margin){return x2+margin>=x&&x1-margin<=x+15&&z2+margin>=z&&z1-margin<=z+15;}
 }
 public record StairTower(int x,int z,int bottom,int top,long salt){
  public boolean intersects(int sx,int sz,int margin){return x+11+margin>=sx&&x-3-margin<=sx+15&&z+10+margin>=sz&&z-1-margin<=sz+15;}
 }
 public record ChunkPlan(List<Station> stations,List<Path> paths,List<Room> rooms,List<StairTower> stairs){}
 public static long hash(long seed,int a,int b,int level){long n=seed^(a*341873128712L)^(b*132897987541L)^(level*42317861L);n=(n^(n>>>30))*0xbf58476d1ce4e5b9L;n=(n^(n>>>27))*0x94d049bb133111ebL;return n^(n>>>31);}
 public static Condition condition(long seed,int gx,int gz,int salt){
  int v=(int)Math.floorMod(hash(seed,gx,gz,salt),100);return v<70?Condition.INTACT:v<84?Condition.COLLAPSED:v<92?Condition.CHEMICAL:Condition.QUARANTINE;
 }
 private static TransitGeometry.Point point(Node n){return new TransitGeometry.Point(n.x,n.y,n.z);}
 private static void addPath(List<Path> out,TransitGeometry.Route route,Condition c,long h,boolean service,int sx,int sz){if(route.intersects(sx,sz,SHELL+(service?3:6)))out.add(new Path(route,c,h,service));}
 public static boolean[] halls(long seed,StationGraph.Vertex v){
  boolean ew=false,ns=false;
  for(var e:StationGraph.incident(seed,v)){
   if(e.northSouth())ns=true;
   else ew=true;
  }
  if(!ew&&!ns)ew=true;
  return new boolean[]{ew,ns};
 }
 public static Station station(long seed,StationGraph.Vertex v,boolean ns){
  var flags=halls(seed,v);boolean transfer=flags[0]&&flags[1];var n=StationGraph.railNode(v,ns);long h=v.salt();
  StationKind k=v.traffic()==StationGraph.Traffic.FREIGHT?StationKind.FREIGHT:v.traffic()==StationGraph.Traffic.MIXED?StationKind.MIXED:stationKind(n,h);
  if(k==StationKind.FREIGHT)k=StationKind.PASSENGER; // Pure passenger candidates cannot become cargo stops by decoration roll.
  if(v.traffic()==StationGraph.Traffic.FREIGHT)k=StationKind.FREIGHT;
  if(ns&&k==StationKind.BIOCENTER)k=StationKind.PASSENGER;
  int offset=transfer?0:Math.floorMod(h,3)==0?4:0;
  int plusEdges=0,minusEdges=0;
  for(var e:StationGraph.incident(seed,v)){
   if(e.northSouth()==ns){
    if(e.a().id().equals(v.id()))plusEdges++;
    else minusEdges++;
   }
  }
  return new Station(n,h,condition(seed,n.x,n.z,41),transfer,ns,v.portal()&&(!flags[0]||!ns),k,offset==0?TrackLayout.CENTRAL:TrackLayout.ISLAND,offset,minusEdges==0,plusEdges==0);
 }
 private record RouteKey(long seed,StationGraph.Id a,StationGraph.Id b){}
 private static final Map<RouteKey,List<TrackRoute>> TRACK_ROUTES=new LinkedHashMap<>(128,.75f,true){protected boolean removeEldestEntry(Map.Entry<RouteKey,List<TrackRoute>> e){return size()>128;}};
 public static List<TrackRoute> trackRoutes(long seed,StationGraph.Edge edge){
  synchronized(TRACK_ROUTES){return TRACK_ROUTES.computeIfAbsent(new RouteKey(seed,edge.a().id(),edge.b().id()),key->{
   var main=NoiseRouter.connect(seed,edge.a(),edge.b());if(main==null)return List.of();
   boolean ns=edge.northSouth();var sa=station(seed,edge.a(),ns);var sb=station(seed,edge.b(),ns);var a=sa.node();var b=sb.node();
   // Fill from the actual station port to the conservative routing reservation (54 blocks).
   var points=new ArrayList<TransitGeometry.Point>();points.add(new TransitGeometry.Point(a.x+(ns?0:halfLength(sa.kind())),a.y,a.z+(ns?halfLength(sa.kind()):0)));
   points.addAll(main.points());points.add(new TransitGeometry.Point(b.x-(ns?0:halfLength(sb.kind())),b.y,b.z-(ns?halfLength(sb.kind()):0)));main=new TransitGeometry.Route(points);
   boolean split=Math.abs(a.y-b.y)<48&&Math.floorMod(edge.salt(),5)==0;int startGap=3+sa.trackOffset(),endGap=3+sb.trackOffset();
   if(split||startGap!=3||endGap!=3){var branches=TransitGeometry.splitMerge(main,startGap,endGap,split);return List.of(new TrackRoute(branches.get(0),true,edge.salt()),new TrackRoute(branches.get(1),true,edge.salt()^71));}
   return List.of(new TrackRoute(main,false,edge.salt()));
  });}
 }
 private record CCBKey(long seed,StationGraph.Id id){}
 private static final Map<CCBKey,TransitGeometry.Route> CCB_ROUTES=new LinkedHashMap<>(128,.75f,true){protected boolean removeEldestEntry(Map.Entry<CCBKey,TransitGeometry.Route> e){return size()>128;}};
 public static TransitGeometry.Route buildCCBRoute(int ax,int ay,int az,int upperL,int lowerL,boolean signZ,boolean signX){
  int sz=signZ?1:-1,sx=signX?1:-1;
  int xSwitchUpper=ax+sx*(upperL+6),zUpper=az+sz*3,yUpper=ay;
  int zSwitchLower=az+sz*(lowerL+6),xLower=ax+sx*3,yLower=ay-24;
  int r=16;int xRamp=xSwitchUpper+sx*r,zRampStart=zUpper+sz*r,rampLen=196;
  int zRampEnd=zRampStart+sz*rampLen,xReturn=xLower+sx*r;
  var p=new ArrayList<TransitGeometry.Point>();
  p.add(new TransitGeometry.Point(ax+sx*(upperL-2),yUpper,zUpper));
  p.add(new TransitGeometry.Point(xSwitchUpper,yUpper,zUpper));
  p.add(new TransitGeometry.Point(xRamp,yUpper,zUpper));
  p.add(new TransitGeometry.Point(xRamp,yUpper,zRampStart));
  p.add(new TransitGeometry.Point(xRamp,yLower,zRampEnd));
  p.add(new TransitGeometry.Point(xRamp,yLower,zRampEnd+sz*r));
  p.add(new TransitGeometry.Point(xReturn,yLower,zRampEnd+sz*r));
  p.add(new TransitGeometry.Point(xReturn,yLower,zSwitchLower+sz*r));
  p.add(new TransitGeometry.Point(xLower,yLower,zSwitchLower+sz*r));
  p.add(new TransitGeometry.Point(xLower,yLower,zSwitchLower));
  p.add(new TransitGeometry.Point(xLower,yLower,az+sz*(lowerL-2)));
  return TransitGeometry.rounded(p,r);
 }
 public static TransitGeometry.Route ccbRoute(long seed,StationGraph.Vertex v){
  synchronized(CCB_ROUTES){return CCB_ROUTES.computeIfAbsent(new CCBKey(seed,v.id()),k->{
   int upperL=halfLength(station(seed,v,false).kind()),lowerL=halfLength(station(seed,v,true).kind());
   var node=v.node();
   boolean[][] quads={{false,false},{false,true},{true,false},{true,true}};
   for(var q:quads){
    var r=buildCCBRoute(node.x(),node.y(),node.z(),upperL,lowerL,q[0],q[1]);
    if(ExclusionNoise.routeClear(seed,r,12))return r;
   }
   return buildCCBRoute(node.x(),node.y(),node.z(),upperL,lowerL,false,false);
  });}
 }
 public record StationMatch(Node node,StationKind kind,boolean northSouth,boolean interchange){}
 public static StationMatch nearestStation(long seed,double x,double z,String requested){
  int rx=(int)Math.floor(x/StationGraph.REGION),rz=(int)Math.floor(z/StationGraph.REGION);StationMatch best=null;double distance=Double.POSITIVE_INFINITY;
  for(int radius=0;radius<=8;radius++){
   for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++){
    if(radius>0&&Math.abs(dx)!=radius&&Math.abs(dz)!=radius)continue;
    for(var v:StationGraph.vertices(seed,rx+dx,rz+dz)){if(StationGraph.incident(seed,v).isEmpty())continue;var flags=halls(seed,v);
     for(boolean ns:new boolean[]{false,true}){if(!flags[ns?1:0])continue;var s=station(seed,v,ns);var k=s.kind();
      boolean matches=requested.equals("station")||requested.equals("interchange")&&(s.interchange()||(k==StationKind.INTERCHANGE||k==StationKind.MIXED))||requested.equals(k.name().toLowerCase(Locale.ROOT))||requested.equals("aquarium")&&k==StationKind.BIOCENTER&&DomeDesign.spec(s.salt()).kind()==DomeDesign.Kind.AQUA;
      if(!matches)continue;double d=Math.hypot(s.node().x-x,s.node().z-z);if(d<distance){distance=d;best=new StationMatch(s.node(),k,ns,s.interchange());}
     }
    }
   }
   if(best!=null&&radius*StationGraph.REGION>distance)return best;
  }return best;
 }
 public static Node nearestBiocenter(long seed,double x,double z){var s=nearestStation(seed,x,z,"biocenter");return s==null?null:s.node();}
 public static TransitGeometry.Point spiralEntry(long seed,StationGraph.Edge edge){
  var a=StationGraph.railNode(edge.a(),edge.northSouth());var b=StationGraph.railNode(edge.b(),edge.northSouth());
  double span=edge.northSouth()?b.z-a.z:b.x-a.x;if(Math.abs(a.y-b.y)<32||span-108<320)return null;
  TransitGeometry.Route direct;try{direct=TransitGeometry.spiralConnection(point(a),point(b),edge.northSouth(),54,54);}catch(IllegalArgumentException e){return null;}
  var route=NoiseRouter.connect(seed,edge.a(),edge.b());if(route==null||!route.points().equals(direct.points()))return null;
  return edge.northSouth()?new TransitGeometry.Point(a.x-156,a.y,a.z+246):new TransitGeometry.Point(a.x+246,a.y,a.z+96);
 }
 public static TransitGeometry.Point nearestSpiral(long seed,double x,double z){
  int rx=(int)Math.floor(x/StationGraph.REGION),rz=(int)Math.floor(z/StationGraph.REGION);TransitGeometry.Point best=null;double distance=Double.POSITIVE_INFINITY;
  for(int radius=0;radius<=5;radius++){
   for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++)if(radius==0||Math.abs(dx)==radius||Math.abs(dz)==radius)for(var edge:StationGraph.edges(seed,rx+dx,rz+dz)){
    var entrance=spiralEntry(seed,edge);if(entrance==null)continue;
    double d=Math.hypot(entrance.x()-x,entrance.z()-z);if(d<distance){distance=d;best=entrance;}
   }
   // Coil entry may be 292 blocks away from its containing region.
   if(best!=null&&(radius-1)*StationGraph.REGION-320>distance)return best;
  }return best;
 }
 private record ChunkKey(long seed,int sx,int sz){}
 private static final Map<ChunkKey,ChunkPlan> CHUNK_CACHE=new LinkedHashMap<>(512,.75f,true){@Override protected boolean removeEldestEntry(Map.Entry<ChunkKey,ChunkPlan> e){return size()>512;}};
 public static ChunkPlan forChunk(long seed,int sx,int sz){
  synchronized(CHUNK_CACHE){return CHUNK_CACHE.computeIfAbsent(new ChunkKey(seed,sx,sz),k->buildChunkPlan(seed,sx,sz));}
 }
 private static ChunkPlan buildChunkPlan(long seed,int sx,int sz){
  var stations=new ArrayList<Station>();var paths=new ArrayList<Path>();var rooms=new ArrayList<Room>();var stairs=new ArrayList<StairTower>();var candidates=new LinkedHashMap<StationGraph.Id,StationGraph.Vertex>();
  for(var edge:StationGraph.nearbyEdges(seed,sx,sz)){
   candidates.put(edge.a().id(),edge.a());candidates.put(edge.b().id(),edge.b());
   // Cheap endpoint envelope avoids building rail lanes for distant edges.
   var a=edge.a().node();var b=edge.b().node();if(sx<Math.min(a.x,b.x)-1100||sx>Math.max(a.x,b.x)+1100||sz<Math.min(a.z,b.z)-1100||sz>Math.max(a.z,b.z)+1100)continue;
   for(var route:trackRoutes(seed,edge))if(route.route().intersects(sx,sz,SHELL+6))paths.add(new Path(route.route(),condition(seed,a.x,a.z,41),route.salt(),false,false,route.singleTrack(),edge.freight()));
  }
  for(var v:candidates.values()){
   Node a=v.node();if(Math.abs(a.x-sx)>320||Math.abs(a.z-sz)>320)continue;
   var flags=halls(seed,v);long h=v.salt();Condition damage=condition(seed,a.x,a.z,41);
   for(boolean ns:new boolean[]{false,true})if(flags[ns?1:0]){var s=station(seed,v,ns);if(s.intersects(sx,sz,SHELL))stations.add(s);}
   if(flags[0]&&flags[1]){
    int bottom=a.y-24+2,top=a.y+2;StairTower tower=new StairTower(a.x+72,a.z+11,bottom,top,h);if(tower.intersects(sx,sz,SHELL))stairs.add(tower);
    int upperStart=halfLength(station(seed,v,false).kind())-6;
    addPath(paths,new TransitGeometry.Route(List.of(new TransitGeometry.Point(a.x+upperStart,top,a.z+12),new TransitGeometry.Point(a.x+70,top,a.z+12))),Condition.INTACT,h,true,sx,sz);
    addPath(paths,new TransitGeometry.Route(List.of(new TransitGeometry.Point(a.x+14,bottom,a.z+12),new TransitGeometry.Point(a.x+70,bottom,a.z+12))),Condition.INTACT,h,true,sx,sz);
    var ccb=ccbRoute(seed,v);if(ccb.intersects(sx,sz,SHELL+6))paths.add(new Path(ccb,damage,h,false,true,true,false));
   }else if(flags[0]){
    int serviceFloor=a.y+34,serviceZ=station(seed,v,false).kind()==StationKind.MINI?8:12;StairTower tower=new StairTower(a.x+66,a.z+serviceZ-1,a.y+2,serviceFloor,h);if(tower.intersects(sx,sz,SHELL))stairs.add(tower);
    int start=halfLength(station(seed,v,false).kind())-6;
    addPath(paths,new TransitGeometry.Route(List.of(new TransitGeometry.Point(a.x+start,a.y+2,a.z+serviceZ),new TransitGeometry.Point(a.x+64,a.y+2,a.z+serviceZ))),Condition.INTACT,h,true,sx,sz);
    addPath(paths,new TransitGeometry.Route(List.of(new TransitGeometry.Point(a.x+64,serviceFloor,a.z+serviceZ),new TransitGeometry.Point(a.x+64,serviceFloor,a.z+66))),damage,h,true,sx,sz);
    Room cache=new Room(a.x+54,a.x+78,a.z+56,a.z+81,serviceFloor,8,damage,h);if(cache.intersects(sx,sz,SHELL))rooms.add(cache);
   }
  }
  return new ChunkPlan(List.copyOf(stations),List.copyOf(paths),List.copyOf(rooms),List.copyOf(stairs));
 }
}
