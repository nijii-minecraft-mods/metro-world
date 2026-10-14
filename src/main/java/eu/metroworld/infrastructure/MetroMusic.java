package eu.metroworld.infrastructure;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.*;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.util.Identifier;
import eu.metroworld.infrastructure.world.*;
/** Quiet, individual vanilla soundtrack. No client mod or custom audio download required. */
public final class MetroMusic {
 private static final String OFF_TAG="metro-world:music-off";
 private record Session(MusicSchedule.State schedule,Identifier sound){}
 private static final Map<UUID,Session> SESSIONS=new HashMap<>();
 private static void stop(ServerPlayerEntity p,Identifier sound){if(sound!=null)p.networkHandler.sendPacket(new StopSoundS2CPacket(sound,SoundCategory.MUSIC));}
 public static int enabled(ServerPlayerEntity p,boolean enabled){
  if(enabled)p.removeCommandTag(OFF_TAG);else p.addCommandTag(OFF_TAG);
  Session previous=SESSIONS.remove(p.getUuid());if(previous!=null)stop(p,previous.sound());
  p.sendMessage(net.minecraft.text.Text.literal(enabled?"Фоновая музыка метро включена.":"Фоновая музыка метро выключена."));return 1;
 }
 public static void tick(MinecraftServer server){
  long now=server.getTicks();var connected=new HashSet<UUID>();
  for(var p:server.getPlayerManager().getPlayerList()){
   UUID id=p.getUuid();connected.add(id);Session s=SESSIONS.get(id);
   if(!Transit.isNetworkWorld(p.getWorld())||p.getCommandTags().contains(OFF_TAG)||!p.isAlive()){
    if(s!=null){stop(p,s.sound());SESSIONS.remove(id);}continue;
   }
   if(s==null){SESSIONS.put(id,new Session(MusicSchedule.entered(now),null));continue;}
   if(now%600==0)for(String nativeTrack:new String[]{"music.nether.nether_wastes","music.nether.soul_sand_valley","music.nether.basalt_deltas","music.nether.crimson_forest","music.nether.warped_forest","music.game","music.creative"})stop(p,Identifier.of("minecraft",nativeTrack));
   var decision=MusicSchedule.advance(s.schedule(),now,p.getUuid().getLeastSignificantBits());Identifier sound=s.sound();
   if(decision.stop()){stop(p,sound);sound=null;}
   if(decision.start()){
    // Stop client-selected music before starting our one private track.
    p.networkHandler.sendPacket(new StopSoundS2CPacket(null,SoundCategory.MUSIC));
    RegistryEntry<SoundEvent> event=SoundEvents.MUSIC_OVERWORLD_DEEP_DARK;
    long seed=p.getServerWorld().getSeed();
    int rx=Math.floorDiv((int)Math.floor(p.getX()),StationGraph.REGION),rz=Math.floorDiv((int)Math.floor(p.getZ()),StationGraph.REGION);
    boolean biocenter=false;
    for(int dx=-1;dx<=1&&!biocenter;dx++)for(int dz=-1;dz<=1&&!biocenter;dz++){
     for(var v:StationGraph.vertices(seed,rx+dx,rz+dz)){
      if(NetworkPlan.station(seed,v,false).kind()==NetworkPlan.StationKind.BIOCENTER&&Math.hypot(p.getX()-v.node().x(),p.getZ()-v.node().z())<140){
       biocenter=true;break;
      }
     }
    }
    if(biocenter)event=SoundEvents.MUSIC_OVERWORLD_LUSH_CAVES;
    else{
     for(var e:StationGraph.nearbyEdges(seed,p.getX(),p.getZ())){
      if(e.freight()&&distanceToEdge(p.getX(),p.getZ(),e.a().node(),e.b().node())<64){
       event=SoundEvents.MUSIC_OVERWORLD_DRIPSTONE_CAVES;break;
      }
     }
    }
    sound=event.value().getId();
    // Attach to the listener entity so walking or riding cannot leave the soundtrack behind.
    p.networkHandler.sendPacket(new PlaySoundFromEntityS2CPacket(event,SoundCategory.MUSIC,p,.22f,1.0f,now^p.getUuid().getLeastSignificantBits()));
   }
   SESSIONS.put(id,new Session(decision.state(),sound));
  }
  SESSIONS.keySet().retainAll(connected);
 }
 private static double distanceToEdge(double px,double pz,NetworkPlan.Node a,NetworkPlan.Node b){
  double vx=b.x()-a.x(),vz=b.z()-a.z(),lenSq=vx*vx+vz*vz;
  if(lenSq<1e-6)return Math.hypot(px-a.x(),pz-a.z());
  double t=Math.max(0,Math.min(1,((px-a.x())*vx+(pz-a.z())*vz)/lenSq));
  return Math.hypot(px-(a.x()+t*vx),pz-(a.z()+t*vz));
 }
 public static void reset(){SESSIONS.clear();}
 private MetroMusic(){}
}
