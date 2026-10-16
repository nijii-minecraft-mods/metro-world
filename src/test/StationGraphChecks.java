import eu.metroworld.infrastructure.world.*;
import java.util.*;
public class StationGraphChecks {
 static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 public static void main(String[] args){
  int blocked=0,free=0,stations=0,edges=0,rails=0,spirals=0,diagonal=0;
  for(long seed:new long[]{7,619015}){
   for(int x=-4096;x<4096;x+=64)for(int z=-4096;z<4096;z+=64){if(ExclusionNoise.blocked(seed,x,-40,z))blocked++;else free++;}
   for(int rx=-1;rx<=0;rx++)for(int rz=-1;rz<=0;rz++){
    var vs=StationGraph.vertices(seed,rx,rz);check(vs.equals(StationGraph.vertices(seed,rx,rz)),"non-deterministic placement");
    for(var v:vs){stations++;check(ExclusionNoise.stationClear(seed,v.node().x(),v.node().y(),v.node().z()),"station in forbidden region");for(var w:vs)if(!v.equals(w))check(Math.hypot(v.node().x()-w.node().x(),v.node().z()-w.node().z())>=600,"stations too close");}
   }
   for(int erx=-1;erx<=1;erx++)for(int erz=-1;erz<=1;erz++)for(var e:StationGraph.edges(seed,erx,erz)){
    edges++;boolean ns=e.northSouth();var a=StationGraph.railNode(e.a(),ns);var b=StationGraph.railNode(e.b(),ns);
    check(e.a().traffic()==StationGraph.Traffic.MIXED||e.b().traffic()==StationGraph.Traffic.MIXED||e.a().traffic()==e.b().traffic(),"cargo/passenger interchange without mixed station");
    var main=NoiseRouter.connect(seed,e.a(),e.b());check(main!=null&&ExclusionNoise.routeClear(seed,main,26),"route cuts forbidden noise");
    boolean reverses=false;for(int i=1;i<main.points().size();i++){var p=main.points().get(i-1);var q=main.points().get(i);if(Math.abs(q.x()-p.x())>.01&&Math.abs(q.z()-p.z())>.01)diagonal++;if((ns?q.z()-p.z():q.x()-p.x())<-.01)reverses=true;}
    if(reverses&&Math.abs(a.y()-b.y())>=32)spirals++;
    var laneOccupancy=new HashSet<String>();
    for(var track:NetworkPlan.trackRoutes(seed,e)){
     check(ExclusionNoise.routeClear(seed,track.route(),12),"split branch enters noise");
     for(var lane:(track.singleTrack()?RailPlan.ofSingle(track.route()):RailPlan.of(track.route())).lanes()){
      check(lane.get(0).floor()==a.y()&&lane.get(lane.size()-1).floor()==b.y(),"wrong throat elevation");
      var occupied=new HashSet<String>();
      for(int i=0;i<lane.size();i++){var c=lane.get(i);rails++;check(occupied.add(c.x()+","+c.floor()+","+c.z()),"rail self-overlap seed="+seed+" edge="+e+" cell="+c);check(laneOccupancy.add(c.x()+","+c.floor()+","+c.z()),"opposite lanes overlap "+e);if(i%256==0){int cx=Math.floorDiv(c.x(),16)*16,cz=Math.floorDiv(c.z(),16)*16;check(NetworkPlan.forChunk(seed,cx,cz).paths().stream().anyMatch(p->p.route().points().equals(track.route().points())),"chunk route ownership missing");}if(i>0){var prev=lane.get(i-1);check(Math.abs(c.x()-prev.x())+Math.abs(c.z()-prev.z())==1,"rail gap");check(Math.abs(c.floor()-prev.floor())<=1,"rail height jump");}}
      for(int end:new int[]{0,1}){var v=end==0?e.a():e.b();var s=NetworkPlan.station(seed,v,ns);var cell=lane.get(end==0?0:lane.size()-1);check((ns?Math.abs(cell.x()-s.node().x()):Math.abs(cell.z()-s.node().z()))==3+s.trackOffset(),"wrong platform rail position");}
     }
    }
   }
   check(NetworkPlan.nearestSpiral(seed,0,0)!=null,"spiral locator finds no real coil");
   int ccbs=0;
   for(int rx=-1;rx<=0;rx++)for(int rz=-1;rz<=0;rz++){
    for(var v:StationGraph.vertices(seed,rx,rz)){
     var inc=StationGraph.incident(seed,v);boolean ew=v.portal(),ns=false;
     for(var e:inc){if(e.northSouth())ns=true;else ew=true;}
     if(ew&&ns){
      ccbs++;
      var ccb=NetworkPlan.ccbRoute(seed,v);
      check(ccb!=null&&ExclusionNoise.routeClear(seed,ccb,12),"CCB route enters noise");
      var plan=RailPlan.ofSingle(ccb);
      var lane=plan.lanes().get(0);
      check(lane.get(0).floor()==v.node().y(),"wrong CCB throat elevation");
      check(lane.get(lane.size()-1).floor()==v.node().y()-24,"wrong CCB arrival elevation");
      for(int i=1;i<lane.size();i++){
       var c=lane.get(i);var prev=lane.get(i-1);
       check(Math.abs(c.x()-prev.x())+Math.abs(c.z()-prev.z())==1,"CCB rail gap");
       check(Math.abs(c.floor()-prev.floor())<=1,"CCB vertical jump");
       if(c.floor()!=prev.floor()){
        var lower=c.floor()<prev.floor()?c:prev;
        check(lower.shape().startsWith("ascending_"),"CCB slope on curve "+lower);
       }
      }
     }
    }
   }
   check(ccbs>0,"interchange CCB branches absent");
   for(String type:new String[]{"station","passenger","mini","interchange","terminal","freight","mixed","biocenter","aquarium"}){
    var match=NetworkPlan.nearestStation(seed,0,0,type);check(match!=null,"no station type "+type);var n=match.node();var plan=NetworkPlan.forChunk(seed,Math.floorDiv(n.x(),16)*16,Math.floorDiv(n.z(),16)*16);check(plan.stations().stream().anyMatch(s->s.node().equals(n)&&s.kind()==match.kind()&&s.northSouth()==match.northSouth()),"locator disagrees with rendering");
   }
  }
  check(blocked>1000&&free>1000,"noise has no occupied/free areas");check(stations>20&&edges>0&&diagonal>100,"random topology absent");
  System.out.println("PASS: noise "+blocked+" forbidden / "+free+" free samples; "+stations+" random candidates; "+edges+" routed edges, "+rails+" rails, "+spirals+" large-rise routes; themed branch compatibility and locators");
 }
}
