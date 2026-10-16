package eu.metroworld.infrastructure.world;
import java.util.*;
/** Seeded hard-core station placement. Spatial buckets are lookup bounds, not station rows. */
public final class StationGraph {
 public static final int REGION=4096,MIN_DISTANCE=600,MIN_Y=-176,MAX_Y=112;
 public record Id(int regionX,int regionZ,int slot){}
 public enum Traffic { PASSENGER, FREIGHT, MIXED }
 public record Vertex(Id id,NetworkPlan.Node node,boolean portal,long salt){public Traffic traffic(){return id.slot()==0||portal||Math.floorMod(salt,6)==0?Traffic.MIXED:Math.floorMod(salt,4)==0?Traffic.FREIGHT:Traffic.PASSENGER;}}
 public record Edge(Vertex a,Vertex b,boolean northSouth,long salt){public boolean freight(){return a.traffic()==Traffic.FREIGHT||b.traffic()==Traffic.FREIGHT||a.traffic()==Traffic.MIXED&&b.traffic()==Traffic.MIXED&&northSouth;}}
 private static boolean compatible(Vertex a,Vertex b){
  if(a.traffic()!=Traffic.MIXED&&b.traffic()!=Traffic.MIXED)return a.traffic()==b.traffic();
  boolean ns=Math.abs(a.node.z()-b.node.z())>Math.abs(a.node.x()-b.node.x());
  // Mixed terminals separate public EW tracks from the lower NS cargo hall.
  if(a.traffic()==Traffic.PASSENGER||b.traffic()==Traffic.PASSENGER)return !ns;
  if(a.traffic()==Traffic.FREIGHT||b.traffic()==Traffic.FREIGHT)return ns;
  return true;
 }

 private record Key(long seed,int x,int z){}
 private static final Map<Key,List<Vertex>> VERTICES=new LinkedHashMap<>(96,.75f,true){protected boolean removeEldestEntry(Map.Entry<Key,List<Vertex>> e){return size()>96;}};
 private static final Map<Key,List<Edge>> EDGES=new LinkedHashMap<>(96,.75f,true){protected boolean removeEldestEntry(Map.Entry<Key,List<Edge>> e){return size()>96;}};
 private static long random(long seed,int x,int z,int slot){return NetworkPlan.hash(seed,x,z,slot);}
 private static int height(long seed,int x,int z){double phase=Math.floorMod(seed,10000)/1000.0;return (int)Math.round(-40+52*Math.sin(x/1300.0+phase)+60*Math.cos(z/1100.0-phase));}
 private record Candidate(int x,int y,int z,long priority){}
 private static final Map<Key,List<Candidate>> RAW=new LinkedHashMap<>(128,.75f,true){protected boolean removeEldestEntry(Map.Entry<Key,List<Candidate>> e){return size()>128;}};
 private static List<Candidate> raw(long seed,int rx,int rz){synchronized(RAW){return RAW.computeIfAbsent(new Key(seed,rx,rz),k->{
  var out=new ArrayList<Candidate>();for(int attempt=0;attempt<64;attempt++){
   long h=random(seed,rx,rz,500+attempt);int x=rx*REGION+(int)Math.floorMod(h,REGION),z=rz*REGION+(int)Math.floorMod(h>>>24,REGION);
   int base=height(seed,x,z);
   for(int delta:new int[]{0,32,-32,64,-64,96,-96,128,-128}){int y=base+delta;if(y<MIN_Y||y>MAX_Y)continue;if(ExclusionNoise.stationClear(seed,x,y,z)){out.add(new Candidate(x,y,z,h));break;}}
  }return List.copyOf(out);
 });}}
 public static List<Vertex> vertices(long seed,int rx,int rz){synchronized(VERTICES){return VERTICES.computeIfAbsent(new Key(seed,rx,rz),key->{
  var out=new ArrayList<Vertex>();for(var c:raw(seed,rx,rz)){
   boolean clear=true;
   // Global priority thinning includes neighbouring buckets; there are no empty grid-border strips.
   for(int dx=-1;dx<=1&&clear;dx++)for(int dz=-1;dz<=1&&clear;dz++)for(var rival:raw(seed,rx+dx,rz+dz)){
    if(Long.compareUnsigned(rival.priority,c.priority)<0&&Math.hypot(rival.x-c.x,rival.z-c.z)<MIN_DISTANCE){clear=false;break;}
   }
   if(clear){int slot=out.size();long salt=random(seed,rx,rz,1000+slot);out.add(new Vertex(new Id(rx,rz,slot),new NetworkPlan.Node(c.x,c.z,c.y),slot==0||Math.floorMod(salt,5)==0,salt));}
  }return List.copyOf(out);
 });}}
 private static double distance(Vertex a,Vertex b){return Math.hypot(a.node.x()-b.node.x(),a.node.z()-b.node.z());}
 private static Edge edge(Vertex a,Vertex b,long seed){
  boolean ns=Math.abs(a.node.z()-b.node.z())>Math.abs(a.node.x()-b.node.x());
  if(ns?a.node.z()>b.node.z():a.node.x()>b.node.x()){var swap=a;a=b;b=swap;}
  long salt=random(seed,a.id.regionX,a.id.regionZ,1700+a.id.slot)^random(seed,b.id.regionX,b.id.regionZ,1800+b.id.slot);
  return new Edge(a,b,ns,salt);
 }
 private static boolean same(Edge e,Vertex a,Vertex b){return e.a.id.equals(a.id)&&e.b.id.equals(b.id)||e.a.id.equals(b.id)&&e.b.id.equals(a.id);}
 private record Local(List<Vertex> active,List<Edge> edges){}
 private static final Map<Key,Local> LOCAL=new LinkedHashMap<>(128,.75f,true){protected boolean removeEldestEntry(Map.Entry<Key,Local> e){return size()>128;}};
 private static Local local(long seed,int rx,int rz){synchronized(LOCAL){return LOCAL.computeIfAbsent(new Key(seed,rx,rz),key->{
  var nodes=vertices(seed,rx,rz);var out=new ArrayList<Edge>();var reached=new HashSet<Id>();if(nodes.isEmpty())return new Local(List.of(),List.of());reached.add(nodes.get(0).id);
  // Build one connected regional backbone; unrouteable candidates are pruned.
  while(reached.size()<nodes.size()){
   Vertex a=null,b=null;double best=Double.POSITIVE_INFINITY;
   for(var first:nodes)if(reached.contains(first.id))for(var second:nodes)if(!reached.contains(second.id)){double d=distance(first,second);if(d<best&&compatible(first,second)&&NoiseRouter.connect(seed,first,second)!=null){best=d;a=first;b=second;}}
   if(b==null)break;out.add(edge(a,b,seed));reached.add(b.id);
  }
  // A few nearest-neighbour links create regional loops without a regular four-way lattice.
  for(var a:nodes)if(reached.contains(a.id)&&Math.floorMod(a.salt,4)==0){
   Vertex b=null;double best=Double.POSITIVE_INFINITY;for(var v:nodes)if(reached.contains(v.id)&&!v.id.equals(a.id)&&out.stream().noneMatch(e->same(e,a,v))){double d=distance(a,v);if(d<best&&compatible(a,v)&&NoiseRouter.connect(seed,a,v)!=null){best=d;b=v;}}
   if(b!=null&&best<2200)out.add(edge(a,b,seed));
  }
  return new Local(nodes.stream().filter(v->reached.contains(v.id)).toList(),List.copyOf(out));
 });}}
 public static List<Edge> edges(long seed,int rx,int rz){synchronized(EDGES){return EDGES.computeIfAbsent(new Key(seed,rx,rz),key->{
  var own=local(seed,rx,rz);var nodes=own.active;var out=new ArrayList<Edge>(own.edges);
  // Connect adjacent spatial buckets by their closest station pair, independent of placement.
  for(int[] step:new int[][]{{1,0},{0,1}}){
   var next=local(seed,rx+step[0],rz+step[1]).active;Vertex a=null,b=null;double best=Double.POSITIVE_INFINITY;
   for(var first:nodes)for(var second:next){double d=distance(first,second);if(d<best&&compatible(first,second)&&NoiseRouter.connect(seed,first,second)!=null){best=d;a=first;b=second;}}
   if(b!=null)out.add(edge(a,b,seed));
  }
  return List.copyOf(out);
 });}}
 private static boolean within(double x,double z,double minX,double maxX,double minZ,double maxZ){return x+15>=minX&&x<=maxX&&z+15>=minZ&&z<=maxZ;}
 public static List<Edge> nearbyEdges(long seed,double x,double z){
  int rx=(int)Math.floor(x/REGION),rz=(int)Math.floor(z/REGION);var out=new ArrayList<Edge>();
  for(int dx=-2;dx<=1;dx++)for(int dz=-2;dz<=1;dz++){
   int bx=rx+dx,bz=rz+dz;double minX=bx*(double)REGION-1100,maxX=(bx+1.0)*REGION+1100,minZ=bz*(double)REGION-1100,maxZ=(bz+1.0)*REGION+1100;
   // Only the local tree or its east/north bridges can own a route through this chunk.
   if(within(x,z,minX,maxX+REGION,minZ,maxZ)||within(x,z,minX,maxX,minZ,maxZ+REGION))out.addAll(edges(seed,bx,bz));
  }return out;
 }
 public static List<Edge> incident(long seed,Vertex vertex){var out=new ArrayList<Edge>();int rx=vertex.id.regionX,rz=vertex.id.regionZ;for(int[] d:new int[][]{{0,0},{-1,0},{0,-1}})for(var e:edges(seed,rx+d[0],rz+d[1]))if(e.a.id.equals(vertex.id)||e.b.id.equals(vertex.id))out.add(e);return out;}
 public static NetworkPlan.Node railNode(Vertex v,boolean ns){var n=v.node;return ns?new NetworkPlan.Node(n.x(),n.z(),n.y()-24):n;}
 public static Vertex nearestPortalVertex(long seed,double x,double z){
  int rx=(int)Math.floor(x/REGION),rz=(int)Math.floor(z/REGION);Vertex best=null;double distance=Double.POSITIVE_INFINITY;
  for(int radius=0;radius<=8;radius++){
   for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++)if(radius==0||Math.abs(dx)==radius||Math.abs(dz)==radius)for(var v:vertices(seed,rx+dx,rz+dz))if(v.portal&&!incident(seed,v).isEmpty()){double d=Math.hypot(v.node.x()-x,v.node.z()-z);if(d<distance){distance=d;best=v;}}
   if(best!=null&&radius*REGION>distance)return best;
  }
  return best;
 }
 public static NetworkPlan.Node nearestPortal(long seed,double x,double z){var v=nearestPortalVertex(seed,x,z);if(v==null)throw new IllegalStateException("No accessible portal near requested position");return v.node;}
}
