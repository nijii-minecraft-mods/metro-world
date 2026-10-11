package eu.metroworld.infrastructure;
import com.google.gson.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import eu.pb4.polymer.core.api.item.PolymerBlockItem;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import org.joml.Vector3f;
import org.joml.Quaternionf;
import net.minecraft.block.*;
import net.minecraft.block.enums.SlabType;
import net.minecraft.item.*;
import net.minecraft.registry.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.*;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.*;
import net.minecraft.world.World;
import java.io.*;
import java.nio.file.*;
import java.util.*;
/** Persistent stationary escalators. Transport is conservative server teleport movement, not a client animation. */
public final class Escalators {
 public record Placement(String dimension,EscalatorGeometry.Plan plan){}
 private static final List<Placement> PLACEMENTS=new ArrayList<>();
 private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
 private static final int MAX_PLACEMENTS=4096;
 private static boolean loadFailed;
 private static final String VISUAL_TAG="metro-world:escalator-tread";
 private static final Map<Placement,List<DisplayEntity.BlockDisplayEntity>> VISUALS=new LinkedHashMap<>();
 private static final Set<UUID> LIVE_VISUALS=new HashSet<>();
 public static Block MARKER;
 private Escalators(){}
 public static void initialize(){
  if(MARKER!=null)return;Identifier id=Identifier.of("metro-world","escalator_landing");
  MARKER=Registry.register(Registries.BLOCK,id,new EscalatorBlock());
  Registry.register(Registries.ITEM,id,new PolymerBlockItem(MARKER,new Item.Settings(),Items.YELLOW_CONCRETE));
  ServerEntityEvents.ENTITY_LOAD.register((entity,world)->{if(entity.getCommandTags().contains(VISUAL_TAG)&&!LIVE_VISUALS.contains(entity.getUuid()))entity.discard();});
  ServerEntityEvents.ENTITY_UNLOAD.register((entity,world)->{if(entity.getCommandTags().contains(VISUAL_TAG))LIVE_VISUALS.remove(entity.getUuid());});
 }
 public static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher){
  var build=CommandManager.literal("build").then(CommandManager.argument("rise",IntegerArgumentType.integer(1,32))
    .then(directionCommands("up",false)).then(directionCommands("down",true)));
  dispatcher.register(CommandManager.literal("metro-escalator").requires(s->s.hasPermissionLevel(2)).then(build)
   .then(CommandManager.literal("list").executes(c->{c.getSource().sendFeedback(()->Text.literal("Эскалаторов: "+PLACEMENTS.size()),false);return PLACEMENTS.size();}))
   .then(CommandManager.literal("disable-nearest").executes(c->disable(c.getSource()))));
 }
 private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> directionCommands(String name,boolean down){
  var branch=CommandManager.literal(name);
  for(Direction d:new Direction[]{Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST})branch.then(CommandManager.literal(d.asString()).executes(c->{
   var player=c.getSource().getPlayerOrThrow();var start=player.getBlockPos().offset(d,3);
   var plan=new EscalatorGeometry.Plan(start.getX(),start.getY(),start.getZ(),d.getOffsetX(),d.getOffsetZ(),IntegerArgumentType.getInteger(c,"rise"),down);
   if(!build(player.getServerWorld(),plan)){c.getSource().sendError(Text.literal("Место занято, вне границы мира или лимит достигнут. Нужен свободный проход шириной 5 блоков."));return 0;}
   save(player.getServer());c.getSource().sendFeedback(()->Text.literal("Эскалатор построен. Для выхода шагните в сторону; приседание останавливает перенос."),false);return 1;
  }));return branch;
 }
 /** Build from bottom landing, axis points uphill. Refuses occupied headroom; returns false without changing blocks. */
 public static boolean build(ServerWorld world,EscalatorGeometry.Plan plan){
  if(loadFailed||MARKER==null||PLACEMENTS.size()>=MAX_PLACEMENTS)return false;
  Map<BlockPos,BlockState> blocks=new LinkedHashMap<>();
  for(int step=-2;step<=plan.length()+1;step++){
   double top=plan.floorHeight(step);int floor=(int)Math.ceil(top)-1;
   for(int side=-2;side<=2;side++){
    BlockPos at=position(plan,step,side,floor);
    BlockState state=step>=0&&step<plan.length()&&step%2==0?Blocks.POLISHED_BLACKSTONE_SLAB.getDefaultState().with(SlabBlock.TYPE,SlabType.BOTTOM):Blocks.POLISHED_BLACKSTONE.getDefaultState();
    if(step<0||step>=plan.length())state=MARKER.getDefaultState();
    blocks.put(at,state);
    for(int h=1;h<=3;h++)blocks.put(at.up(h),Math.abs(side)==2&&h<=2?Blocks.GLASS.getDefaultState():Blocks.AIR.getDefaultState());
   }
  }
  for(var entry:blocks.entrySet()){
   var at=entry.getKey();if(at.getY()<world.getBottomY()||at.getY()>=world.getTopY()||!world.getWorldBorder().contains(at))return false;
   var existing=world.getBlockState(at);
   if(!existing.isReplaceable()&&!existing.isOf(MARKER)){
    // The bottom landing may use an existing ground block. Never excavate the route.
    if(at.getY()==plan.y()-1&&!entry.getValue().isAir()&&existing.isSideSolidFullSquare(world,at,Direction.UP))continue;
    return false;
   }
   if(!world.getFluidState(at).isEmpty())return false;
  }
  for(var entry:blocks.entrySet()){
   var at=entry.getKey();if(at.getY()==plan.y()-1&&!world.getBlockState(at).isReplaceable())continue;
   world.setBlockState(at,entry.getValue(),3);
  }
  PLACEMENTS.add(new Placement(world.getRegistryKey().getValue().toString(),plan));return true;
 }
 private static BlockPos position(EscalatorGeometry.Plan p,int along,int side,int y){return new BlockPos(p.x()+p.dx()*along-p.dz()*side,y,p.z()+p.dz()*along+p.dx()*side);}
 public static void tick(MinecraftServer server){
  animate(server);
  for(ServerPlayerEntity player:server.getPlayerManager().getPlayerList()){
   if(player.isSpectator()||player.isSneaking()||player.hasVehicle()||!player.isOnGround()||player.getAbilities().flying)continue;
   String dimension=player.getWorld().getRegistryKey().getValue().toString();
   for(var placement:PLACEMENTS){
    var p=placement.plan();if(!placement.dimension().equals(dimension)||!p.riding(player.getX(),player.getY(),player.getZ()))continue;
    double next=p.advance(p.along(player.getX(),player.getZ()));double x=p.x()+.5+p.dx()*(next-.5),z=p.z()+.5+p.dz()*(next-.5);
    // Keep a player's lateral position; walking away immediately leaves the activation strip.
    double across=p.across(player.getX(),player.getZ());x-=p.dz()*across;z+=p.dx()*across;double y=p.height(next);
    var world=player.getServerWorld();var feet=BlockPos.ofFloored(x,y+.001,z);if(!world.isChunkLoaded(feet))break;
    var support=BlockPos.ofFloored(x,y-.01,z);var floor=world.getBlockState(support);
    if(!(floor.isOf(MARKER)||floor.isOf(Blocks.POLISHED_BLACKSTONE)||floor.isOf(Blocks.POLISHED_BLACKSTONE_SLAB)))break;
    var box=player.getBoundingBox().offset(x-player.getX(),y-player.getY(),z-player.getZ());
    if(!world.isSpaceEmpty(player,box))break;
    player.networkHandler.requestTeleport(x,y,z,player.getYaw(),player.getPitch());player.fallDistance=0;break;
   }
  }
 }
 private static int disable(ServerCommandSource source)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
  var player=source.getPlayerOrThrow();String dimension=player.getWorld().getRegistryKey().getValue().toString();
  Placement nearest=null;double distance=64*64;
  for(var p:PLACEMENTS)if(p.dimension().equals(dimension)){double d=player.squaredDistanceTo(p.plan().x()+.5,p.plan().y(),p.plan().z()+.5);if(d<distance){nearest=p;distance=d;}}
  if(nearest==null){source.sendError(Text.literal("Рядом нет зарегистрированного эскалатора."));return 0;}
  PLACEMENTS.remove(nearest);save(player.getServer());source.sendFeedback(()->Text.literal("Перенос отключён. Ступени сохранены как обычная лестница."),false);return 1;
 }
 private static Path file(MinecraftServer server){return server.getSavePath(WorldSavePath.ROOT).resolve("metro-world-escalators.json");}
 public static void load(MinecraftServer server){
  reset();Path path=file(server);if(!Files.exists(path))return;
  try(var in=Files.newBufferedReader(path)){
   JsonArray entries=JsonParser.parseReader(in).getAsJsonArray();if(entries.size()>MAX_PLACEMENTS)throw new IOException("Too many placements");
   List<Placement> loaded=new ArrayList<>();for(var element:entries){var o=element.getAsJsonObject();String dimension=o.get("dimension").getAsString();Identifier.of(dimension);
    var p=new EscalatorGeometry.Plan(o.get("x").getAsInt(),o.get("y").getAsInt(),o.get("z").getAsInt(),o.get("dx").getAsInt(),o.get("dz").getAsInt(),o.get("rise").getAsInt(),o.get("descending").getAsBoolean());loaded.add(new Placement(dimension,p));}
   PLACEMENTS.addAll(loaded);
  }catch(IOException|RuntimeException e){loadFailed=true;org.slf4j.LoggerFactory.getLogger("metro-world").error("Escalators not loaded; original JSON retained",e);}
 }
 public static void save(MinecraftServer server){
  clearVisuals();
  if(loadFailed)return; // Do not overwrite a malformed persistence file with an empty placement list.
  JsonArray entries=new JsonArray();for(var placement:PLACEMENTS){var p=placement.plan();var o=new JsonObject();o.addProperty("dimension",placement.dimension());o.addProperty("x",p.x());o.addProperty("y",p.y());o.addProperty("z",p.z());o.addProperty("dx",p.dx());o.addProperty("dz",p.dz());o.addProperty("rise",p.rise());o.addProperty("descending",p.descending());entries.add(o);}
  Path path=file(server),tmp=path.resolveSibling(path.getFileName()+".tmp");try{
   Files.writeString(tmp,JSON.toJson(entries));try{Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING);}
  }catch(IOException e){org.slf4j.LoggerFactory.getLogger("metro-world").error("Cannot save escalator placements",e);}
 }
 public static void reset(){clearVisuals();PLACEMENTS.clear();loadFailed=false;}
 private static void clearVisuals(){for(var parts:VISUALS.values())for(var entity:parts)if(entity!=null){entity.discard();LIVE_VISUALS.remove(entity.getUuid());}VISUALS.clear();}
 private static void animate(MinecraftServer server){
  var nearby=new ArrayList<Placement>();var distances=new HashMap<Placement,Double>();
  for(var placement:PLACEMENTS){var p=placement.plan();double best=Double.POSITIVE_INFINITY;
   for(var player:server.getPlayerManager().getPlayerList())if(!player.isSpectator()&&player.getWorld().getRegistryKey().getValue().toString().equals(placement.dimension())){
    double t=Math.max(0,Math.min(p.length(),p.along(player.getX(),player.getZ())));
    double x=p.x()+.5+p.dx()*(t-.5),z=p.z()+.5+p.dz()*(t-.5);
    best=Math.min(best,player.squaredDistanceTo(x,p.floorHeight(t),z));
   }
   if(best<48*48){nearby.add(placement);distances.put(placement,best);}
  }
  nearby.sort(Comparator.comparingDouble(distances::get));if(nearby.size()>4)nearby.subList(4,nearby.size()).clear();
  for(var iterator=VISUALS.entrySet().iterator();iterator.hasNext();){var entry=iterator.next();if(!nearby.contains(entry.getKey())){
   for(var entity:entry.getValue())if(entity!=null){entity.discard();LIVE_VISUALS.remove(entity.getUuid());}iterator.remove();
  }}
  for(var placement:nearby){
   var world=server.getWorld(RegistryKey.of(RegistryKeys.WORLD,Identifier.of(placement.dimension())));if(world==null)continue;
   var p=placement.plan();int count=Math.min(32,p.length());var parts=VISUALS.computeIfAbsent(placement,k->new ArrayList<>());
   double sx=p.dx()==0?2.5:.1,sz=p.dx()==0?.1:2.5;
   for(int i=0;i<count;i++){
    double t=(i*(double)p.length()/count+(p.descending()?-1:1)*server.getTicks()*.08)%p.length();if(t<0)t+=p.length();
    double x=p.x()+.5+p.dx()*(t-.5)-sx/2,z=p.z()+.5+p.dz()*(t-.5)-sz/2,y=p.floorHeight(t)+.012;
    if(!world.isChunkLoaded(BlockPos.ofFloored(x,y,z)))continue;
    while(parts.size()<=i)parts.add(null);
    var entity=parts.get(i);
    if(entity==null||entity.isRemoved()){
     if(entity!=null)LIVE_VISUALS.remove(entity.getUuid());
     entity=new DisplayEntity.BlockDisplayEntity(EntityType.BLOCK_DISPLAY,world);
     entity.setBlockState(Blocks.LIGHT_GRAY_CONCRETE.getDefaultState());
     entity.setTransformation(new AffineTransformation(new Vector3f(),new Quaternionf(),new Vector3f((float)sx,.018f,(float)sz),new Quaternionf()));
     entity.setTeleportDuration(1);entity.setViewRange(.75f);entity.addCommandTag(VISUAL_TAG);entity.setNoGravity(true);entity.setInvulnerable(true);
     entity.refreshPositionAndAngles(x,y,z,0,0);LIVE_VISUALS.add(entity.getUuid());
     if(world.spawnEntity(entity))parts.set(i,entity);else{LIVE_VISUALS.remove(entity.getUuid());parts.set(i,null);}
    }else entity.refreshPositionAndAngles(x,y,z,0,0);
   }
  }
 }

}
