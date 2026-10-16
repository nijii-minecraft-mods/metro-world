package eu.metroworld.infrastructure.world;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NetworkPlanTest {
 @Test void placementIsRepeatableAndRespectsNoise(){var vs=StationGraph.vertices(7,0,0);assertEquals(vs,StationGraph.vertices(7,0,0));for(var v:vs){assertTrue(ExclusionNoise.stationClear(7,v.node().x(),v.node().y(),v.node().z()));for(var w:vs)if(!v.equals(w))assertTrue(Math.hypot(v.node().x()-w.node().x(),v.node().z()-w.node().z())>=600);}}
 @Test void changingSeedChangesLayout(){assertNotEquals(StationGraph.vertices(7,0,0),StationGraph.vertices(8,0,0));}
 @Test void noiseIsContinuousAcrossNegativeCoordinates(){assertEquals(ExclusionNoise.sample(7,-.001,0,0),ExclusionNoise.sample(7,.001,0,0),.001);}
 @Test void routesAndTrafficRespectReservations(){for(var e:StationGraph.edges(7,0,0)){assertNotNull(NoiseRouter.connect(7,e.a(),e.b()));assertTrue(e.a().traffic()==StationGraph.Traffic.MIXED||e.b().traffic()==StationGraph.Traffic.MIXED||e.a().traffic()==e.b().traffic());}}
 @Test void locatorFindsGeneratedStation(){var s=NetworkPlan.nearestStation(7,0,0,"station");assertNotNull(s);var n=s.node();assertTrue(NetworkPlan.forChunk(7,Math.floorDiv(n.x(),16)*16,Math.floorDiv(n.z(),16)*16).stations().stream().anyMatch(h->h.node().equals(n)&&h.northSouth()==s.northSouth()));}
 @Test void interchangeStationsHaveConnectingTracksCCB(){
  int found=0;
  for(int rx=-1;rx<=1;rx++)for(int rz=-1;rz<=1;rz++)for(var v:StationGraph.vertices(7,rx,rz)){
   var inc=StationGraph.incident(7,v);boolean ew=v.portal(),ns=false;
   for(var e:inc){if(e.northSouth())ns=true;else ew=true;}
   if(ew&&ns){
    found++;
    var ccb=NetworkPlan.ccbRoute(7,v);
    assertNotNull(ccb);
    assertTrue(ExclusionNoise.routeClear(7,ccb,12),"CCB route must clear exclusion noise");
    var plan=RailPlan.ofSingle(ccb);
    var lane=plan.lanes().get(0);
    assertEquals(v.node().y(),lane.get(0).floor(),"CCB must start at upper line elevation");
    assertEquals(v.node().y()-24,lane.get(lane.size()-1).floor(),"CCB must end at lower line elevation (24 blocks drop)");
    for(int i=0;i<lane.size();i++){
     var c=lane.get(i);
     if(i>0){
      var prev=lane.get(i-1);
      assertEquals(1,Math.abs(c.x()-prev.x())+Math.abs(c.z()-prev.z()),"Rail continuity gap");
      assertTrue(Math.abs(c.floor()-prev.floor())<=1,"Vertical step <= 1");
      if(c.floor()!=prev.floor()){
       var lower=c.floor()<prev.floor()?c:prev;
       assertTrue(lower.shape().startsWith("ascending_"),"Slope must only be on straight ascending rails");
      }
     }
    }
    int cx=Math.floorDiv(v.node().x(),16)*16,cz=Math.floorDiv(v.node().z(),16)*16;
    var chunkPlan=NetworkPlan.forChunk(7,cx,cz);
    assertTrue(chunkPlan.paths().stream().anyMatch(p->p.singleTrack()&&p.transfer()&&!p.service()),"Chunk plan must contain single-track CCB rail path");
   }
  }
  assertTrue(found>=5,"Must find interchange stations with CCB");
 }
 @Test void terminalStationsHaveReversingSidings(){
  int found=0;
  for(int rx=-2;rx<=2;rx++)for(int rz=-2;rz<=2;rz++)for(var v:StationGraph.vertices(7,rx,rz)){
   for(boolean ns:new boolean[]{false,true}){
    var s=NetworkPlan.station(7,v,ns);
    if(s.kind()==NetworkPlan.StationKind.TERMINAL){
     found++;
     assertTrue(s.intersects(s.node().x()+(ns?0:75),s.node().z()+(ns?75:0),NetworkPlan.SHELL)||s.intersects(s.node().x()-(ns?0:75),s.node().z()-(ns?75:0),NetworkPlan.SHELL),"Terminal station bounds must include reversing siding");
    }
   }
  }
  assertTrue(found>=10,"Must find terminal stations with sidings");
 }
}
