package eu.metroworld.infrastructure;
import eu.pb4.polymer.core.api.block.SimplePolymerBlock;
import eu.pb4.polymer.core.api.item.PolymerBlockItem;
import eu.pb4.polymer.core.api.item.PolymerItemGroupUtils;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
public final class InfrastructureBlocks {
 public static final Block PANEL=register("concrete_panel",Blocks.LIGHT_GRAY_CONCRETE,Items.LIGHT_GRAY_CONCRETE);
 public static final Block WARNING=register("hazard_marker",Blocks.YELLOW_CONCRETE,Items.YELLOW_CONCRETE);
 public static final Block CONTAINER=register("chemical_container",Blocks.YELLOW_TERRACOTTA,Items.YELLOW_TERRACOTTA);
 public static final Block RIBBED_PANEL=register("ribbed_panel",Blocks.QUARTZ_PILLAR,Items.QUARTZ_PILLAR);
 public static final Block TACTILE_TILE=register("tactile_tile",Blocks.YELLOW_CONCRETE,Items.YELLOW_CONCRETE);
 public static final Block WAYFINDING_SIGN=register("wayfinding_sign",Blocks.BLUE_CONCRETE,Items.BLUE_CONCRETE);
 public static final Block TRACK_SWITCH=registerSwitch();
 public static final Block LIFT=registerLift();
 public static final Block SERVICE_LIFT=registerServiceLift();
 private static Block registerSwitch(){
  Identifier id=Identifier.of("metro-world","track_switch");
  Block block=Registry.register(Registries.BLOCK,id,new TrackSwitchBlock());
  Registry.register(Registries.ITEM,id,new PolymerBlockItem(block,new Item.Settings(),Items.YELLOW_GLAZED_TERRACOTTA));return block;
 }
 private static Block registerServiceLift(){
  Identifier id=Identifier.of("metro-world","service_terminal");
  Block block=Registry.register(Registries.BLOCK,id,new ServiceLiftBlock());
  Registry.register(Registries.ITEM,id,new PolymerBlockItem(block,new Item.Settings(),Items.BLACK_GLAZED_TERRACOTTA));return block;
 }
 private static Block registerLift(){
  Identifier id=Identifier.of("btr_infrastructure","lift_terminal");
  Block block=Registry.register(Registries.BLOCK,id,new LiftBlock());
  Registry.register(Registries.ITEM,id,new PolymerBlockItem(block,new Item.Settings(),Items.BLACK_GLAZED_TERRACOTTA));return block;
 }
 private static Block register(String name,Block visual,Item itemVisual){
  Identifier id=Identifier.of("btr_infrastructure",name);
  Block block=Registry.register(Registries.BLOCK,id,new SimplePolymerBlock(AbstractBlock.Settings.copy(visual),visual));
  Registry.register(Registries.ITEM,id,new PolymerBlockItem(block,new Item.Settings(),itemVisual));
  return block;
 }
 public static void initialize(){
  PolymerItemGroupUtils.registerPolymerItemGroup(Identifier.of("btr_infrastructure","infrastructure"),
   PolymerItemGroupUtils.builder().displayName(Text.literal("Metro World — Инфраструктура"))
    .icon(()->new ItemStack(PANEL)).entries((context,entries)->{entries.add(PANEL);entries.add(WARNING);entries.add(CONTAINER);entries.add(LIFT);entries.add(RIBBED_PANEL);entries.add(TACTILE_TILE);entries.add(WAYFINDING_SIGN);entries.add(TRACK_SWITCH);entries.add(SERVICE_LIFT);}).build());
 }
}
