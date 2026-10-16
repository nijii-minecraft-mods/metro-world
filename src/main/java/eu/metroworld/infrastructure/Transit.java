package eu.metroworld.infrastructure;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.registry.*;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.Heightmap;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.Direction;
public final class Transit {
 public static final RegistryKey<World> UNDERGROUND=RegistryKey.of(RegistryKeys.WORLD,Identifier.of("metro-world","metro-world"));
 private static final String LEGACY_NAMESPACE="btr_infrastructure";
 public static boolean isNetworkWorld(net.minecraft.world.World w){var id=w.getRegistryKey().getValue();return id.equals(UNDERGROUND.getValue())||(id.getNamespace().equals(LEGACY_NAMESPACE)&&(id.getPath().equals("metro")||id.getPath().equals("underground")));}
 private static final Properties RETURN_POSITIONS=new Properties();
 private static boolean dirty=false,loaded=false;
 private static Path file(net.minecraft.server.MinecraftServer server){return server.getSavePath(WorldSavePath.ROOT).resolve("metro-world-return.properties");}
 public static synchronized void load(net.minecraft.server.MinecraftServer server){
  RETURN_POSITIONS.clear();dirty=false;loaded=true;
  if(server==null)return;
  for(String legacyName:new String[]{"btr-return.properties","kessoku-return.properties"}){
   Path legacy=file(server).resolveSibling(legacyName);
   if(Files.exists(legacy))try(var in=Files.newInputStream(legacy)){RETURN_POSITIONS.load(in);}catch(IOException ignored){}
  }
  Path path=file(server);if(Files.exists(path))try(var in=Files.newInputStream(path)){RETURN_POSITIONS.load(in);}catch(IOException ignored){}
 }
 public static synchronized void save(net.minecraft.server.MinecraftServer server){
  if(!dirty||server==null)return;
  Path path=file(server),tmp=path.resolveSibling("metro-world-return.properties.tmp");
  try{
   try(var out=Files.newOutputStream(tmp)){RETURN_POSITIONS.store(out,"Metro World return positions");}
   try{Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
   catch(AtomicMoveNotSupportedException e){Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING);}
   dirty=false;
  }catch(IOException e){org.slf4j.LoggerFactory.getLogger("metro-world").error("Failed to save metro-world-return.properties",e);}
 }
 public static synchronized void reset(){RETURN_POSITIONS.clear();dirty=false;loaded=false;}
 private static synchronized Properties getPositions(ServerPlayerEntity p){
  if(!loaded&&p.getServer()!=null)load(p.getServer());
  return RETURN_POSITIONS;
 }
 public static int enter(ServerPlayerEntity p){return enter(p,null);}
 public static int enter(ServerPlayerEntity p,BlockPos returnPos){
  if(p.getWorld().getRegistryKey().equals(UNDERGROUND))return 1;
  if(isNetworkWorld(p.getWorld())&&leave(p)==0)return 0;
  ServerWorld target=p.getServer().getWorld(UNDERGROUND);
  if(target==null){p.sendMessage(Text.literal("Измерение метро не загружено: требуется перезапуск сервера."));return 0;}
  double sourceX=returnPos==null?p.getX():returnPos.getX()+0.5;
  double sourceZ=returnPos==null?p.getZ():returnPos.getZ()+0.5;
  var v=eu.metroworld.infrastructure.world.StationGraph.nearestPortalVertex(target.getSeed(),TransitScale.toMetro(sourceX),TransitScale.toMetro(sourceZ));
  if(v==null){p.sendMessage(Text.literal("Рядом не найдена доступная входная станция. Попробуй другой вход."));return 0;}
  var flags=eu.metroworld.infrastructure.world.NetworkPlan.halls(target.getSeed(),v);
  boolean ns=!flags[0]&&flags[1];
  var station=eu.metroworld.infrastructure.world.NetworkPlan.station(target.getSeed(),v,ns);
  int arrivalX=station.node().x()+(ns?-8:2);
  int arrivalZ=station.node().z()+(ns?2:8);
  int arrivalY=station.node().y()+3;
  target.getChunk(Math.floorDiv(arrivalX,16),Math.floorDiv(arrivalZ,16));
  BlockPos arrival=new BlockPos(arrivalX,arrivalY,arrivalZ);
  if(!safeFeet(target,arrival)){p.sendMessage(Text.literal("Площадка станции недоступна. Переход отменён; обновите старое измерение метро."));return 0;}
  synchronized(RETURN_POSITIONS){
   getPositions(p).setProperty(p.getUuidAsString(),p.getWorld().getRegistryKey().getValue()+","+(returnPos==null?p.getX():returnPos.getX()+0.5)+","+(returnPos==null?p.getY():returnPos.getY())+","+(returnPos==null?p.getZ():returnPos.getZ()+0.5)+","+p.getYaw()+","+p.getPitch()+","+station.node().x()+","+station.node().z()+","+station.node().y());
   dirty=true;
  }
  p.teleport(target,arrivalX+0.5,arrivalY,arrivalZ+0.5,ns?0:90,0);
  p.sendMessage(Text.literal("Метро: станция. Лифт возвращения — слева, чёрно-жёлтый блок. Высоты станций и тоннелей меняются по пути."));
  return 1;
 }
 public static int leave(ServerPlayerEntity p){
  if(!isNetworkWorld(p.getWorld()))return 1;
  try{
   String saved;synchronized(RETURN_POSITIONS){saved=getPositions(p).getProperty(p.getUuidAsString());}
   if(saved!=null){String[] s=saved.split(",");if(s.length<7)throw new IllegalArgumentException("Invalid return position");ServerWorld w=p.getServer().getWorld(RegistryKey.of(RegistryKeys.WORLD,Identifier.of(s[0])));
    // Legacy saves have no anchor and keep their exact original return behaviour.
    // s[6]=station.x, s[7]=station.z, s[8]=station.y
    boolean atEntry=s.length<8||(Math.hypot(p.getX()-Double.parseDouble(s[6]),p.getZ()-Double.parseDouble(s[7]))<110&&(s.length<9||Math.abs(p.getY()-Double.parseDouble(s[8]))<100));
    if(w!=null&&atEntry){
     BlockPos original=BlockPos.ofFloored(Double.parseDouble(s[1]),Double.parseDouble(s[2]),Double.parseDouble(s[3]));
     BlockPos safe=findNearby(w,original);
     if(safe==null)safe=findSurface(w,original.getX(),original.getZ());
     if(safe!=null){p.teleport(w,safe.getX()+0.5,safe.getY(),safe.getZ()+0.5,Float.parseFloat(s[4]),Float.parseFloat(s[5]));return 1;}
    }
   }
  }catch(IllegalArgumentException e){p.sendMessage(Text.literal("Точка возвращения недоступна; ищем выход по масштабу 8:1."));}
  ServerWorld w=p.getServer().getOverworld();
  int x=(int)Math.floor(TransitScale.toOverworld(p.getX())),z=(int)Math.floor(TransitScale.toOverworld(p.getZ()));
  BlockPos surface=findSurface(w,x,z);
  if(surface==null){p.sendMessage(Text.literal("На поверхности над этой точкой нет безопасного выхода. Попробуй другую станцию."));return 0;}
  p.teleport(w,surface.getX()+0.5,surface.getY(),surface.getZ()+0.5,p.getYaw(),0);return 1;
 }
 private static BlockPos findSurface(ServerWorld world,int x,int z){
  x=(int)Math.min(Math.max((long)x,(long)Math.ceil(world.getWorldBorder().getBoundWest())+2),(long)Math.floor(world.getWorldBorder().getBoundEast())-2);
  z=(int)Math.min(Math.max((long)z,(long)Math.ceil(world.getWorldBorder().getBoundNorth())+2),(long)Math.floor(world.getWorldBorder().getBoundSouth())-2);
  world.getChunk(Math.floorDiv(x,16),Math.floorDiv(z,16));
  for(int radius=0;radius<=48;radius++)for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++){
   if(Math.max(Math.abs(dx),Math.abs(dz))!=radius)continue;
   int cx=Math.floorDiv(x+dx,16),cz=Math.floorDiv(z+dz,16);
   if(radius>16&&!world.getChunkManager().isChunkLoaded(cx,cz))continue;
   BlockPos pos=world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,new BlockPos(x+dx,0,z+dz));
   if(safeFeet(world,pos))return pos;
  }
  return null;
 }
 static boolean safeFeet(ServerWorld world,BlockPos pos){
  if(pos.getY()<=world.getBottomY()||pos.getY()+1>=world.getTopY()||!world.getWorldBorder().contains(pos))return false;
  BlockPos floor=pos.down();var state=world.getBlockState(floor);
  if(!Block.sideCoversSmallSquare(world,floor,Direction.UP)||!world.getFluidState(floor).isEmpty()
    ||state.isOf(Blocks.MAGMA_BLOCK)||state.isOf(Blocks.CACTUS)||state.isOf(Blocks.CAMPFIRE)||state.isOf(Blocks.SOUL_CAMPFIRE)||state.isOf(Blocks.POWDER_SNOW))return false;
  for(int h=0;h<2;h++){BlockPos at=pos.up(h);var atState=world.getBlockState(at);if(!world.getFluidState(at).isEmpty()||!atState.getCollisionShape(world,at).isEmpty()||atState.isOf(Blocks.FIRE)||atState.isOf(Blocks.SOUL_FIRE)||atState.isOf(Blocks.SWEET_BERRY_BUSH)||atState.isOf(Blocks.POWDER_SNOW))return false;}
  return true;
 }
 private static BlockPos findNearby(ServerWorld world,BlockPos original){
  world.getChunk(Math.floorDiv(original.getX(),16),Math.floorDiv(original.getZ(),16));
  for(int radius=0;radius<=4;radius++)for(int dy:new int[]{0,1,-1,2,-2})for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++){
   if(Math.max(Math.abs(dx),Math.abs(dz))!=radius)continue;
   BlockPos at=original.add(dx,dy,dz);if(safeFeet(world,at))return at;
  }
  return null;
 }
 public static void toggle(ServerPlayerEntity p){if(isNetworkWorld(p.getWorld()))leave(p);else enter(p);}
}
