package eu.metroworld.infrastructure.train;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import net.minecraft.server.command.*;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
public final class TrainCommands {
 private TrainCommands(){}
 public static void register(CommandDispatcher<ServerCommandSource> dispatcher){
  var root=CommandManager.literal("metro-train").executes(c->{c.getSource().sendFeedback(()->Text.literal("/metro-train panel | board | seat <0..7> | stand | cargo | couple | uncouple | list | remove [id] | clear"),false);return 1;});
  root.then(CommandManager.literal("panel").executes(c->TrainPanel.nearest(c.getSource().getPlayerOrThrow())));
  root.then(CommandManager.literal("board").executes(c->Trains.board(c.getSource().getPlayerOrThrow())));
  root.then(CommandManager.literal("seat").then(CommandManager.argument("index",IntegerArgumentType.integer(0,7)).executes(c->Trains.seatNearest(c.getSource().getPlayerOrThrow(),IntegerArgumentType.getInteger(c,"index")))));
  root.then(CommandManager.literal("stand").executes(c->Trains.stand(c.getSource().getPlayerOrThrow())));
  root.then(CommandManager.literal("cargo").executes(c->Trains.cargoNearest(c.getSource().getPlayerOrThrow())));
  root.then(CommandManager.literal("couple").executes(c->Trains.coupleNearest(c.getSource().getPlayerOrThrow())));
  root.then(CommandManager.literal("uncouple").executes(c->Trains.uncouple(c.getSource().getPlayerOrThrow())));
  root.then(CommandManager.literal("list").executes(c->Trains.list(c.getSource())));
  var remove=CommandManager.literal("remove").requires(s->s.hasPermissionLevel(2))
    .executes(c->Trains.removeNearest(c.getSource().getPlayerOrThrow()))
    .then(CommandManager.argument("id",StringArgumentType.string()).executes(c->Trains.remove(c.getSource(),StringArgumentType.getString(c,"id"))));
  root.then(remove);
  root.then(CommandManager.literal("clear").requires(s->s.hasPermissionLevel(2)).executes(c->Trains.clearAll(c.getSource())));
  var spawn=CommandManager.literal("spawn").requires(s->s.hasPermissionLevel(2));for(String type:new String[]{"blue","red","freight"})spawn.then(CommandManager.literal(type).executes(c->Trains.spawn(c.getSource().getPlayerOrThrow(),type)));root.then(spawn);
  root.then(CommandManager.literal("models").requires(s->s.hasPermissionLevel(2)).executes(c->TrainModels.list(c.getSource())));
  root.then(CommandManager.literal("validate").requires(s->s.hasPermissionLevel(2)).executes(c->TrainValidation.run(c.getSource())));
  root.then(CommandManager.literal("model").requires(s->s.hasPermissionLevel(2)).then(CommandManager.argument("id",StringArgumentType.string()).then(CommandManager.argument("scale",DoubleArgumentType.doubleArg(.25,1)).executes(c->TrainModels.apply(c.getSource().getPlayerOrThrow(),StringArgumentType.getString(c,"id"),DoubleArgumentType.getDouble(c,"scale"))))));
  var add=CommandManager.literal("add").requires(s->s.hasPermissionLevel(2));for(String type:new String[]{"locomotive","platform","container","metro_blue","metro_red"})add.then(CommandManager.literal(type).executes(c->Trains.add(c.getSource().getPlayerOrThrow(),type)));root.then(add);
  dispatcher.register(root);
 }
}
