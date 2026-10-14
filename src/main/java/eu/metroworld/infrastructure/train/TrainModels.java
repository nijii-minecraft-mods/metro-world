package eu.metroworld.infrastructure.train;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import eu.pb4.polymer.resourcepack.api.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.*;
import net.minecraft.item.*;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Quaternionf;
/** Reads the documented TrainCarts normal form, preserving parent matrix composition. */
public final class TrainModels {
 private TrainModels(){}
 private static final Map<String,PolymerModelData> ICONS=new HashMap<>();
 private static final Map<String,JsonObject> MODELS=new LinkedHashMap<>();
 private static final Map<String,PolymerModelData> ITEMS=new HashMap<>();
 public static void initialize(){
  PolymerResourcePackUtils.addModAssets("metro-world");PolymerResourcePackUtils.markAsRequired();
  for(String icon:new String[]{"power","compressor","reverser","emergency","traction","brake","doors","lights","speed","pressure"})ICONS.put(icon,PolymerResourcePackUtils.requestModel(Items.PAPER,Identifier.of("btr_infrastructure","item/train_"+icon)));
  for(String type:new String[]{"speed","pressure","traction","brake"}){int max=switch(type){case "speed"->44;case "pressure"->8;case "traction"->5;default->7;};for(int value=0;value<=max;value++){String key=type+"_"+value;ICONS.put(key,PolymerResourcePackUtils.requestModel(Items.PAPER,Identifier.of("btr_infrastructure","item/train_"+key)));}}
  Path root=FabricLoader.getInstance().getConfigDir().resolve("metro-world/train-models");
  if(!Files.isDirectory(root))return;
  try(var files=Files.list(root)){
   for(Path file:files.filter(p->p.getFileName().toString().endsWith(".json")).sorted().limit(32).toList()){
    if(Files.size(file)>4_000_000)throw new IllegalArgumentException("oversize train manifest "+file);
    var model=JsonParser.parseString(Files.readString(file)).getAsJsonObject();if(!model.get("schema").getAsString().equals("metro-world.train-model.v1"))throw new IllegalArgumentException("unknown train schema");
    String id=model.get("id").getAsString();if(MODELS.putIfAbsent(id,model)!=null)throw new IllegalArgumentException("duplicate model "+id);
    Path pack=root.resolve(model.getAsJsonObject("resourcePack").get("assetsPath").getAsString()).normalize();if(!pack.startsWith(root)||!Files.isDirectory(pack))throw new IllegalArgumentException("missing/unsafe model resource pack");
    PolymerResourcePackUtils.RESOURCE_PACK_CREATION_EVENT.register(builder->builder.copyResourcePackFromPath(pack));
    for(var cart:model.getAsJsonArray("carts"))for(var entry:cart.getAsJsonObject().getAsJsonArray("attachments")){
     var a=entry.getAsJsonObject();if(!a.get("type").getAsString().equals("item"))continue;var item=a.getAsJsonObject("item");if(!item.has("model")||item.get("model").isJsonNull())continue;
     String path=item.get("model").getAsString();ITEMS.computeIfAbsent(path,key->PolymerResourcePackUtils.requestModel(Items.PAPER,Identifier.of(path)));
    }
   }
  }catch(Exception e){throw new IllegalStateException("Cannot load imported train models",e);}
 }
 public static void buildPack(){if(!PolymerResourcePackUtils.buildMain())throw new IllegalStateException("Train resource pack generation failed");}
 public static boolean hasPack(ServerPlayerEntity p){return p!=null&&PolymerResourcePackUtils.hasMainPack(p);}
 public static ItemStack panelItem(ItemStack fallback,String name,boolean packed){var data=ICONS.get(name);if(!packed||data==null)return fallback;var stack=data.asStack();stack.set(DataComponentTypes.CUSTOM_NAME,fallback.get(DataComponentTypes.CUSTOM_NAME));return stack;}
 public static int list(ServerCommandSource source){for(var e:MODELS.entrySet())source.sendFeedback(()->Text.literal(e.getKey()+": "+e.getValue().getAsJsonArray("carts").size()+" вагонов; полный импорт="+e.getValue().get("complete")),false);if(MODELS.isEmpty())source.sendFeedback(()->Text.literal("Импортируйте YAML+ZIP в config/metro-world/train-models, затем перезапустите сервер. TRAIN-MODELS.md"),false);return MODELS.size();}
 static JsonObject cart(TrainData data,int index){return cart(data.modelAt(index),data.modelIndex(index));}
 static JsonObject cart(String model,int index){var m=MODELS.get(model);return m==null||index>=m.getAsJsonArray("carts").size()?null:m.getAsJsonArray("carts").get(index).getAsJsonObject();}
 public static int apply(ServerPlayerEntity p,String id,double scale){var t=Trains.nearest(p);var model=MODELS.get(id);if(t==null||model==null||!t.data.control.stopped()||scale<.25||scale>1)return 0;var carts=model.getAsJsonArray("carts");if(carts.size()<1||carts.size()>6){p.sendMessage(Text.literal("Импортированный состав должен иметь 1–6 вагонов."));return 0;}
  if(t.cargo.stream().anyMatch(inv->!inv.isEmpty())){p.sendMessage(Text.literal("Сначала выгрузите контейнеры: замена модели меняет состав."));return 0;}
  var lengths=new ArrayList<Double>();for(var cart:carts){double length=cart.getAsJsonObject().get("length").getAsDouble()*scale;if(!Double.isFinite(length)||length<1||length>16)return 0;lengths.add(length);}
  double total=lengths.stream().mapToDouble(v->v+1).sum()-1;if(total+4>=t.path.length)return 0;
  t.hide();t.data.cars.clear();t.data.lengths.clear();t.data.inventories.clear();t.cargo.clear();t.data.model=id;t.data.modelScale=scale;t.data.modelIds.clear();t.data.modelIndices.clear();t.data.modelScales.clear();
  for(var cart:carts){double length=cart.getAsJsonObject().get("length").getAsDouble()*scale;t.data.modelIndices.add(t.data.cars.size());t.data.modelIds.add(id);t.data.modelScales.add(scale);t.data.cars.add(TrainCar.METRO_BLUE);t.data.lengths.add(length);t.cargo.add(new net.minecraft.inventory.SimpleInventory(54));}
  Trains.clampBoarding(t.data.id,carts.size());
  t.data.control.position=Math.max(t.data.length()+2,t.data.control.position);Trains.save(p.getServer());p.sendMessage(Text.literal("Модель "+id+" применена. Неподдержанные вложения смотрите в diagnostics импортёра."));return 1;
 }
 static Vector3f vector(JsonObject obj,String key,Vector3f fallback){if(!obj.has(key))return new Vector3f(fallback);var a=obj.getAsJsonArray(key);return new Vector3f(a.get(0).getAsFloat(),a.get(1).getAsFloat(),a.get(2).getAsFloat());}
 static Matrix4f local(JsonObject a){var r=vector(a,"rotation",new Vector3f()).mul((float)Math.PI/180);return new Matrix4f().translation(vector(a,"translation",new Vector3f())).rotateY(r.y).rotateX(r.x).rotateZ(r.z).scale(vector(a,"scale",new Vector3f(1)));}
 static final class Attachment {
  final JsonObject spec;final DisplayEntity.ItemDisplayEntity display;final ArmorStandEntity seat;final InteractionEntity hit;final String parent;Matrix4f matrix=new Matrix4f();
  boolean initialized=false,lastVisible=false;final Matrix4f lastRotationScale=new Matrix4f();
  Attachment(JsonObject spec,DisplayEntity.ItemDisplayEntity display,ArmorStandEntity seat,InteractionEntity hit){this.spec=spec;this.display=display;this.seat=seat;this.hit=hit;parent=spec.has("parent")&&!spec.get("parent").isJsonNull()?spec.get("parent").getAsString():"";}
 }
 static List<Attachment> spawn(TrainRender render){var cart=cart(render.train.data,render.index);if(cart==null)return List.of();var result=new ArrayList<Attachment>();
  for(var entry:cart.getAsJsonArray("attachments")){var a=entry.getAsJsonObject();String type=a.get("type").getAsString();DisplayEntity.ItemDisplayEntity display=null;ArmorStandEntity seat=null;InteractionEntity hit=null;
   if(type.equals("item")){var item=a.getAsJsonObject("item");if(!item.has("model")||item.get("model").isJsonNull())continue;var data=ITEMS.get(item.get("model").getAsString());if(data==null)continue;display=new DisplayEntity.ItemDisplayEntity(EntityType.ITEM_DISPLAY,render.world);display.setItemStack(data.asStack());display.setTransformationMode(ModelTransformationMode.valueOf(item.get("display").getAsString().toUpperCase(Locale.ROOT)));display.setTeleportDuration(1);render.add(display,0,0,0,false,false);
   }else if(type.equals("seat")){seat=new ArmorStandEntity(EntityType.ARMOR_STAND,render.world);var nbt=new net.minecraft.nbt.NbtCompound();nbt.putBoolean("Marker",true);nbt.putBoolean("Invisible",true);nbt.putBoolean("NoGravity",true);seat.readNbt(nbt);seat.setInvulnerable(true);int index=render.seats.size();render.seats.add(seat);render.add(seat,0,0,0,false,false);hit=new InteractionEntity(EntityType.INTERACTION,render.world);hit.setInteractionWidth(.8f);hit.setInteractionHeight(.9f);Trains.HITS.put(hit.getUuid(),new Trains.Hit(render.train.data.id,render.index,"seat",index));render.add(hit,0,0,0,false,false);}
   result.add(new Attachment(a,display,seat,hit));
  }return result;
 }
 static void update(TrainRender render,List<Attachment> attachments,TrainPath.Pose pose){var matrices=new HashMap<String,Matrix4f>();var cart=cart(render.train.data,render.index);boolean flipped=cart.has("flipped")&&cart.get("flipped").getAsBoolean();var root=new Matrix4f().scale((float)render.train.data.scaleAt(render.index));if(flipped)root.rotateY((float)Math.PI);
  for(var a:attachments){var parent=matrices.getOrDefault(a.parent,root);a.matrix=new Matrix4f(parent).mul(local(a.spec));boolean visible=true;
   if(a.spec.has("animations"))for(var entry:a.spec.getAsJsonObject("animations").entrySet()){
    String name=entry.getKey().toLowerCase(Locale.ROOT);var animation=entry.getValue().getAsJsonObject();TrainAnimation.Frame frame=null;
    boolean movement=(animation.has("movementControlled")&&animation.get("movementControlled").getAsBoolean())||name.contains("wheel");
    if(name.contains("doors_l")||name.contains("door_left"))frame=TrainAnimation.door(animation,render.leftAmount);
    else if(name.contains("doors_r")||name.contains("door_right"))frame=TrainAnimation.door(animation,render.rightAmount);
    else if(movement||(animation.has("autoplay")&&animation.get("autoplay").getAsBoolean()))frame=TrainAnimation.sample(animation,movement?render.wheelDistance/2.5:render.animationTicks/20.0);
    if(frame!=null){a.matrix.mul(TrainAnimation.matrix(frame));visible&=frame.active();}
   }matrices.put(a.spec.get("id").getAsString(),a.matrix);
   if(a.display!=null){
    var item=a.spec.getAsJsonObject("item");
    if(!a.initialized||visible!=a.lastVisible){a.display.setItemStack(visible?ITEMS.get(item.get("model").getAsString()).asStack():ItemStack.EMPTY);a.lastVisible=visible;}
    var image=new Matrix4f(a.matrix).translate(vector(item,"displayTranslation",new Vector3f())).scale(vector(item,"displayScale",new Vector3f(1)));
    var position=image.getTranslation(new Vector3f());var at=pose.world(position.x,position.y,position.z);
    a.display.refreshPositionAndAngles(at[0],at[1],at[2],pose.yaw(),pose.pitch());
    var rotScale=new Matrix4f(image).setTranslation(0,0,0);
    if(!a.initialized||!rotScale.equals(a.lastRotationScale,1e-4f)){a.display.setTransformation(new AffineTransformation(rotScale));a.lastRotationScale.set(rotScale);}
    a.initialized=true;
   }
   if(a.seat!=null){var pos=a.matrix.getTranslation(new Vector3f());var at=pose.world(pos.x,pos.y,pos.z);a.seat.refreshPositionAndAngles(at[0],at[1]-.15,at[2],pose.yaw(),pose.pitch());if(a.hit!=null)a.hit.refreshPositionAndAngles(at[0],at[1],at[2],pose.yaw(),pose.pitch());}
  }
 }
}
