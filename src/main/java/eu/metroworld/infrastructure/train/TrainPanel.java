package eu.metroworld.infrastructure.train;
import java.util.*;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.*;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.*;
import net.minecraft.screen.*;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.*;
import net.minecraft.util.Identifier;
/** Vanilla container protocol, Polymer resource-pack skin; no client screen class required. */
public final class TrainPanel extends GenericContainerScreenHandler {
 private final Trains.Runtime train;private final UUID operator;private final SimpleInventory panel;private final ServerPlayerEntity viewer;
 private static final Map<String,UUID> OPERATORS=new HashMap<>();
 private TrainPanel(int sync,PlayerInventory inventory,Trains.Runtime train,SimpleInventory panel){super(ScreenHandlerType.GENERIC_9X6,sync,inventory,panel,6);this.train=train;this.panel=panel;operator=inventory.player.getUuid();viewer=(ServerPlayerEntity)inventory.player;refresh();}
 public static int open(ServerPlayerEntity player,Trains.Runtime train){
  if(!train.nearby(player))return 0;UUID owner=OPERATORS.get(train.data.id);if(owner!=null&&!owner.equals(player.getUuid())){player.sendMessage(Text.literal("Пульт сейчас занят другим машинистом."));return 0;}
  OPERATORS.put(train.data.id,player.getUuid());Text title=TrainModels.hasPack(player)?Text.literal("\uE001\uE000").setStyle(Style.EMPTY.withFont(Identifier.of("btr_infrastructure","train_panel")).withColor(0xffffff)):Text.literal("Кабина — "+train.data.name);
  player.openHandledScreen(new SimpleNamedScreenHandlerFactory((sync,inv,p)->new TrainPanel(sync,inv,train,new SimpleInventory(54)),title));return 1;
 }
 public static int nearest(ServerPlayerEntity p){var train=Trains.nearest(p);return train==null?0:open(p,train);}
 private void button(int slot,Item item,String name,String model){var stack=new ItemStack(item);stack.set(DataComponentTypes.CUSTOM_NAME,Text.literal(name));stack=TrainModels.panelItem(stack,model,TrainModels.hasPack(viewer));panel.setStack(slot,stack);}
 private void refresh(){var c=train.data.control;for(int i=0;i<54;i++)panel.setStack(i,ItemStack.EMPTY);
  button(10,c.power?Items.LIME_DYE:Items.GRAY_DYE,"Питание: "+(c.power?"ВКЛ":"ВЫКЛ"),"power");
  button(12,c.compressor?Items.LIME_DYE:Items.GRAY_DYE,"Компрессор: "+(c.compressor?"ВКЛ":"ВЫКЛ"),"compressor");
  button(14,Items.COMPASS,"Реверсор: "+(c.direction==1?"ВПЕРЁД":c.direction==0?"НЕЙТРАЛЬ":"НАЗАД"),"reverser");
  button(16,Items.REDSTONE_BLOCK,c.emergency?"Экстренный: СБРОС (только стоя с тормозом)":"Экстренный тормоз","emergency");
  button(20,Items.CLOCK,String.format(Locale.ROOT,"Скорость: %.1f км/ч",Math.abs(c.speed)*3.6),"speed_"+Math.min(44,(int)Math.round(Math.abs(c.speed)*3.6)));
  button(24,Items.GLASS_BOTTLE,String.format(Locale.ROOT,"Давление: %.1f бар (тяга от 4)",c.pressure),"pressure_"+(int)Math.round(c.pressure));
  button(28,Items.RED_DYE,"Тяга − ("+c.traction+" / 5)","traction_"+c.traction);button(29,Items.LIME_DYE,"Тяга + ("+c.traction+" / 5)","traction_"+c.traction);
  button(32,Items.LIME_DYE,"Тормоз − ("+c.brake+" / 7)","brake_"+c.brake);button(33,Items.RED_DYE,"Тормоз + ("+c.brake+" / 7)","brake_"+c.brake);
  button(37,Items.IRON_DOOR,"Левые двери: "+(c.leftDoors?"ОТКРЫТЫ":"ЗАКРЫТЫ"),"doors");button(40,Items.IRON_DOOR,"Правые двери: "+(c.rightDoors?"ОТКРЫТЫ":"ЗАКРЫТЫ"),"doors");
  button(43,Items.SEA_LANTERN,"Свет: "+(c.lights?"ВКЛ":"ВЫКЛ"),"lights");
 }
 @Override public boolean canUse(PlayerEntity player){return player.getUuid().equals(operator)&&player instanceof ServerPlayerEntity p&&train.nearby(p)&&Trains.TRAINS.get(train.data.id)==train;}
 @Override public ItemStack quickMove(PlayerEntity player,int slot){return ItemStack.EMPTY;}
 @Override public void onSlotClick(int slot,int button,SlotActionType action,PlayerEntity player){
  // Never put control icons on the cursor: shift-click, number keys, drag and throw are denied.
  if(action!=SlotActionType.PICKUP||!canUse(player))return;var c=train.data.control;
  switch(slot){case 10->{if(train.data.hasTraction())c.power=!c.power;}case 12->c.compressor=!c.compressor;case 14->c.reverse();case 16->{if(c.emergency)c.resetEmergency();else{c.emergency=true;c.traction=0;}}case 28->c.traction=Math.max(0,c.traction-1);case 29->c.traction=Math.min(5,c.traction+1);case 32->c.brake=Math.max(0,c.brake-1);case 33->c.brake=Math.min(7,c.brake+1);case 37->c.doors(true);case 40->c.doors(false);case 43->c.lights=!c.lights;default->{}}
  setCursorStack(ItemStack.EMPTY);
  if(player instanceof ServerPlayerEntity sp)sp.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket(net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket.UPDATE_CURSOR_SYNC_ID,nextRevision(),-1,ItemStack.EMPTY));
  sendContentUpdates();
 }
 @Override public void sendContentUpdates(){if(panel!=null)refresh();super.sendContentUpdates();}
 @Override public void onClosed(PlayerEntity player){super.onClosed(player);OPERATORS.remove(train.data.id,operator);/* Closing does not touch traction/brakes. */}
 static void operatorRemoved(String trainId){OPERATORS.remove(trainId);}
 static void reset(){OPERATORS.clear();}
}
