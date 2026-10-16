package eu.metroworld.infrastructure;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.block.Blocks;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
public final class InfrastructureMod implements ModInitializer {
 public void onInitialize(){
  InfrastructureBlocks.initialize();
  eu.metroworld.infrastructure.train.Trains.initialize();
  Escalators.initialize();
  net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server->{eu.metroworld.infrastructure.train.Trains.load(server);Escalators.load(server);Transit.load(server);eu.metroworld.infrastructure.train.TrainModels.buildPack();});
  net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server->{eu.metroworld.infrastructure.train.Trains.save(server);Escalators.save(server);Transit.save(server);});
  net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server->{
   eu.metroworld.infrastructure.train.Trains.tick(server);
   Escalators.tick(server);
   if(server.getTicks()%200==0)Transit.save(server);
   if(server.getTicks()%20!=0)return;
   MetroMusic.tick(server);
   for(var player:server.getPlayerManager().getPlayerList()){
    if(!Transit.isNetworkWorld(player.getWorld()))continue;
    if(player.getWorld().getBlockState(player.getBlockPos().down()).isOf(Blocks.MAGMA_BLOCK)&&!player.isSpectator()&&!player.isCreative()){
     player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.POISON,60,0));
     player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.NAUSEA,160,0));
    }
    if(server.getTicks()%1200==0)player.playSoundToPlayer(net.minecraft.sound.SoundEvents.AMBIENT_CAVE.value(),net.minecraft.sound.SoundCategory.AMBIENT,0.35f,0.85f);
   }
  });
  net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server->{MetroMusic.reset();eu.metroworld.infrastructure.train.Trains.reset();Escalators.reset();Transit.reset();});
  CommandRegistrationCallback.EVENT.register((dispatcher,registries,environment)->{
   eu.metroworld.infrastructure.train.TrainCommands.register(dispatcher);
   Escalators.registerCommands(dispatcher);
   dispatcher.register(CommandManager.literal("metro-world-music")
    .then(CommandManager.literal("on").executes(c->MetroMusic.enabled(c.getSource().getPlayerOrThrow(),true)))
    .then(CommandManager.literal("off").executes(c->MetroMusic.enabled(c.getSource().getPlayerOrThrow(),false))));
   dispatcher.register(CommandManager.literal("metro-world").requires(s->s.hasPermissionLevel(2))
    .executes(c->{c.getSource().sendFeedback(()->Text.literal("Metro World: /metro-world enter, /metro-world exit, /metro-world entrance, /metro-world locate station|freight|mixed|biocenter|spiral. Измерение: metro-world:metro-world; диапазон высот −256…255."),false);return 1;})
    .then(locateCommands())
    .then(CommandManager.literal("validate-features").executes(c->FeatureValidation.run(c.getSource())))
    .then(CommandManager.literal("validate-tracks").executes(c->TrackValidation.run(c.getSource())))
    .then(CommandManager.literal("enter").executes(c->Transit.enter(c.getSource().getPlayerOrThrow())))
    .then(CommandManager.literal("exit").executes(c->Transit.leave(c.getSource().getPlayerOrThrow())))
    .then(CommandManager.literal("entrance").executes(c->{
     ServerPlayerEntity player=c.getSource().getPlayerOrThrow();
     if(!player.getWorld().getRegistryKey().equals(net.minecraft.world.World.OVERWORLD)){c.getSource().sendError(Text.literal("Создайте вход в обычном мире."));return 0;}
     BlockPos at=player.getBlockPos().add(0,0,3);
     // Small entrance terminal; explicitly placed by admin, no automatic surface edits.
     for(int x=-2;x<=2;x++)for(int z=-1;z<=1;z++)player.getServerWorld().setBlockState(at.add(x,-1,z),Blocks.SMOOTH_STONE.getDefaultState());
     player.getServerWorld().setBlockState(at,InfrastructureBlocks.LIFT.getDefaultState());
     player.getServerWorld().setBlockState(at.up(),Blocks.YELLOW_CONCRETE.getDefaultState());
     player.getServerWorld().setBlockState(at.up(2),Blocks.SEA_LANTERN.getDefaultState());
     c.getSource().sendFeedback(()->Text.literal("Вход установлен. ПКМ по чёрному блоку лифта переносит в метро; обратный лифт сохраняет точку входа."),false);return 1;
    })));
  });
 }
 private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.server.command.ServerCommandSource> locateCommands(){
  var command=CommandManager.literal("locate");
  for(String type:new String[]{"station","passenger","mini","interchange","terminal","freight","mixed","biocenter","aquarium","spiral","entrance"})command.then(CommandManager.literal(type).executes(c->locate(c.getSource(),type)));
  return command;
 }
 private static int locate(net.minecraft.server.command.ServerCommandSource source,String type){
  var world=source.getServer().getWorld(Transit.UNDERGROUND);if(world==null){source.sendError(Text.literal("Измерение метро недоступно."));return 0;}
  boolean metro=Transit.isNetworkWorld(source.getWorld());if(!metro&&!source.getWorld().getRegistryKey().equals(net.minecraft.world.World.OVERWORLD)){source.sendError(Text.literal("Ищите станции из обычного мира или метро."));return 0;}
  var pos=source.getPosition();double x=metro?pos.x:TransitScale.toMetro(pos.x),z=metro?pos.z:TransitScale.toMetro(pos.z);
  if(type.equals("entrance")){
   var v=eu.metroworld.infrastructure.world.StationGraph.nearestPortalVertex(world.getSeed(),x,z);
   var n=v.node();
   var flags=eu.metroworld.infrastructure.world.NetworkPlan.halls(world.getSeed(),v);
   boolean ns=!flags[0]&&flags[1];
   var s=eu.metroworld.infrastructure.world.NetworkPlan.station(world.getSeed(),v,ns);
   int ox=Math.floorDiv(Math.floorDiv(n.x(),8),16)*16+8,oz=Math.floorDiv(Math.floorDiv(n.z(),8),16)*16+8;
   source.sendFeedback(()->Text.literal("Узел с выходом: метро ["+s.node().x()+", "+(s.node().y()+3)+", "+s.node().z()+"]. План павильона Overworld: ["+ox+", ~, "+oz+"]. Павильон появляется только на подходящей местности. /execute in minecraft:overworld run tp @s "+ox+" ~ "+oz),false);return 1;
  }
  if(type.equals("spiral")){
   var at=eu.metroworld.infrastructure.world.NetworkPlan.nearestSpiral(world.getSeed(),x,z);
   if(at==null){source.sendError(Text.literal("Спираль не найдена в радиусе поиска."));return 0;}
   int tx=(int)Math.round(at.x()),ty=(int)Math.round(at.y())+1,tz=(int)Math.round(at.z());
   source.sendFeedback(()->Text.literal("Спираль: ["+tx+", "+ty+", "+tz+"]. /execute in metro-world:metro-world run tp @s "+tx+" "+ty+" "+tz),false);return 1;
  }
  var found=eu.metroworld.infrastructure.world.NetworkPlan.nearestStation(world.getSeed(),x,z,type);if(found==null){source.sendError(Text.literal("Станция не найдена в радиусе поиска."));return 0;}
  var n=found.node();String label=switch(type){case "freight"->"Грузовая станция";case "mixed"->"Пассажирско-грузовая станция";case "terminal"->"Вокзал";case "interchange"->"Пересадка";case "biocenter"->"Биоцентр";case "aquarium"->"Аквакупол";case "mini"->"Малая станция";default->"Станция";};
  int tx=n.x()+(found.northSouth()?-8:2),tz=n.z()+(found.northSouth()?2:8);
  source.sendFeedback(()->Text.literal(label+": ["+n.x()+", "+(n.y()+3)+", "+n.z()+"]"+((type.equals("biocenter")||type.equals("aquarium"))?"; купол ["+n.x()+", "+(n.y()+5)+", "+(n.z()-62)+"]":"")+". /execute in metro-world:metro-world run tp @s "+tx+" "+(n.y()+3)+" "+tz),false);return 1;
 }

}
