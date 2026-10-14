package eu.metroworld.infrastructure.mixin;
import eu.metroworld.infrastructure.InfrastructureBlocks;
import eu.metroworld.infrastructure.world.NetworkPlan;
import net.minecraft.block.*;
import net.minecraft.block.enums.SlabType;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.math.*;
import net.minecraft.world.*;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ChunkGenerator.class)
public abstract class EntranceGeneratorMixin {
 @Inject(method="generateFeatures",at=@At("TAIL"))
 private void metroWorld$entrance(StructureWorldAccess world,Chunk chunk,StructureAccessor structures,CallbackInfo ci){
  if(!((Object)this instanceof NoiseChunkGenerator)||!world.toServerWorld().getRegistryKey().equals(World.OVERWORLD))return;
  metroWorld$plannedEntrance(world,chunk);
  metroWorld$treeHatch(world,chunk);
  var registry=world.getRegistryManager().get(RegistryKeys.STRUCTURE);
  java.util.Set<net.minecraft.structure.StructureStart> starts=new java.util.HashSet<>();
  for(int ox=-1;ox<=1;ox++)for(int oz=-1;oz<=1;oz++)starts.addAll(structures.getStructureStarts(new ChunkPos(chunk.getPos().x+ox,chunk.getPos().z+oz),s->{var id=registry.getId(s);if(id==null)return false;String name=id.getPath();return name.startsWith("village_")||name.equals("pillager_outpost")||name.equals("desert_pyramid")||name.equals("jungle_pyramid")||name.equals("igloo")||name.equals("mansion");}));
  for(var start:starts){
   var box=start.getBoundingBox();long h=NetworkPlan.hash(world.getSeed(),start.getPos().x,start.getPos().z,102);int side=(int)(h&3);
   int x=side==0?box.getMaxX()+14:side==1?box.getMinX()-14:(box.getMinX()+box.getMaxX())/2;
   int z=side==2?box.getMaxZ()+14:side==3?box.getMinZ()-14:(box.getMinZ()+box.getMaxZ())/2;
   x=Math.floorDiv(x,16)*16+8;z=Math.floorDiv(z,16)*16+8;
   // Each structure chooses one entrance; only its owning generation chunk writes it.
   if(Math.floorDiv(x,16)!=chunk.getPos().x||Math.floorDiv(z,16)!=chunk.getPos().z)continue;
   int y=world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,x,z);
   if(y<world.getSeaLevel()||y>world.getTopY()-8)continue;
   BlockPos.Mutable pos=new BlockPos.Mutable();boolean clear=true;
   for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++){
    int top=world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,x+dx,z+dz);
    if(Math.abs(top-y)>1||!world.getFluidState(pos.set(x+dx,top-1,z+dz)).isEmpty())clear=false;
    for(int dy=0;dy<=4;dy++)if(!world.getBlockState(pos.set(x+dx,y+dy,z+dz)).isReplaceable())clear=false;
   }
   if(!clear)continue;
   for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++){
    world.setBlockState(pos.set(x+dx,y-1,z+dz),Blocks.SMOOTH_QUARTZ.getDefaultState(),2);
    world.setBlockState(pos.set(x+dx,y+4,z+dz),Blocks.SMOOTH_QUARTZ_SLAB.getDefaultState().with(SlabBlock.TYPE,SlabType.BOTTOM),2);
    if(Math.abs(dx)==3&&Math.abs(dz)==3)for(int dy=0;dy<4;dy++)world.setBlockState(pos.set(x+dx,y+dy,z+dz),InfrastructureBlocks.RIBBED_PANEL.getDefaultState(),2);
    else if((Math.abs(dx)==3||dz==3))for(int dy=1;dy<4;dy++)world.setBlockState(pos.set(x+dx,y+dy,z+dz),Blocks.CYAN_STAINED_GLASS.getDefaultState(),2);
   }
   world.setBlockState(pos.set(x,y,z+1),InfrastructureBlocks.LIFT.getDefaultState(),2);
   world.setBlockState(pos.set(x,y+3,z),Blocks.SEA_LANTERN.getDefaultState(),2);
   world.setBlockState(pos.set(x,y+3,z-3),InfrastructureBlocks.WAYFINDING_SIGN.getDefaultState(),2);
   world.setBlockState(pos.set(x-2,y,z+2),Blocks.GRASS_BLOCK.getDefaultState(),2);
   world.setBlockState(pos.set(x-2,y+1,z+2),Blocks.PINK_TULIP.getDefaultState(),2);
   org.slf4j.LoggerFactory.getLogger("metro-world").debug("Вход метро рядом с {}: {}, {}, {}",registry.getId(start.getStructure()),x,y,z);
  }
 }
 private void metroWorld$plannedEntrance(StructureWorldAccess world,Chunk chunk){
  int rx=Math.floorDiv(chunk.getPos().x,32),rz=Math.floorDiv(chunk.getPos().z,32);
  eu.metroworld.infrastructure.world.StationGraph.Vertex candidate=null;
  for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
   for(var v:eu.metroworld.infrastructure.world.StationGraph.vertices(world.getSeed(),rx+dx,rz+dz)){
    if(v.portal()&&Math.floorDiv(Math.floorDiv(v.node().x(),8),16)==chunk.getPos().x&&Math.floorDiv(Math.floorDiv(v.node().z(),8),16)==chunk.getPos().z){
     candidate=v;break;
    }
   }
   if(candidate!=null)break;
  }
  if(candidate==null||eu.metroworld.infrastructure.world.StationGraph.incident(world.getSeed(),candidate).isEmpty())return;
  NetworkPlan.Node anchor=candidate.node();
  int x=Math.floorDiv(Math.floorDiv(anchor.x(),8),16)*16+8,z=Math.floorDiv(Math.floorDiv(anchor.z(),8),16)*16+8;
  int y=Integer.MIN_VALUE;
  // Try several clear sites inside the owning chunk instead of losing an entrance to one uneven column.
  search: for(int[] offset:new int[][]{{0,0},{-4,0},{4,0},{0,-4},{0,4},{-4,-4},{4,4},{-4,4},{4,-4}}){
   int tx=x+offset[0],tz=z+offset[1],ty=world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,tx,tz);
   if(ty<=world.getBottomY()+4||ty>world.getTopY()-7)continue;
   for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++){
    int top=world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,tx+dx,tz+dz);
    BlockPos floor=new BlockPos(tx+dx,top-1,tz+dz);
    if(Math.abs(top-ty)>1||!world.getFluidState(floor).isEmpty()||!world.getBlockState(floor).isSolidBlock(world,floor))continue search;
    for(int h=0;h<4;h++)if(!world.getBlockState(new BlockPos(tx+dx,ty+h,tz+dz)).isReplaceable())continue search;
   }
   x=tx;z=tz;y=ty;break;
  }
  if(y==Integer.MIN_VALUE)return;
  for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++){
   world.setBlockState(new BlockPos(x+dx,y-1,z+dz),Blocks.SMOOTH_QUARTZ.getDefaultState(),2);
   world.setBlockState(new BlockPos(x+dx,y+3,z+dz),Blocks.WHITE_CONCRETE.getDefaultState(),2);
   if(Math.abs(dx)==2||dz==2)for(int h=0;h<3;h++)world.setBlockState(new BlockPos(x+dx,y+h,z+dz),Blocks.CYAN_STAINED_GLASS.getDefaultState(),2);
  }
  world.setBlockState(new BlockPos(x,y,z+1),InfrastructureBlocks.LIFT.getDefaultState(),2);
  world.setBlockState(new BlockPos(x,y+2,z),Blocks.SEA_LANTERN.getDefaultState(),2);
  world.setBlockState(new BlockPos(x,y+2,z-2),InfrastructureBlocks.WAYFINDING_SIGN.getDefaultState(),2);
 }
 private void metroWorld$treeHatch(StructureWorldAccess world,Chunk chunk){
  // Rare service entrances, contained entirely in the owning chunk.
  if(Math.floorMod(NetworkPlan.hash(world.getSeed(),chunk.getPos().x,chunk.getPos().z,731),96)!=0)return;
  int sx=chunk.getPos().getStartX(),sz=chunk.getPos().getStartZ();
  for(int tx=sx+3;tx<=sx+10;tx++)for(int tz=sz+3;tz<=sz+12;tz++){
   int treeY=world.getTopY(Heightmap.Type.WORLD_SURFACE,tx,tz)-1;
   if(!world.getBlockState(new BlockPos(tx,treeY,tz)).isIn(BlockTags.LEAVES))continue;
   int ground=treeY;
   while(ground>world.getSeaLevel()&&world.getBlockState(new BlockPos(tx,ground,tz)).isIn(BlockTags.LEAVES))ground--;
   if(!world.getBlockState(new BlockPos(tx,ground,tz)).isIn(BlockTags.LOGS))continue;
   while(ground>world.getSeaLevel()&&world.getBlockState(new BlockPos(tx,ground,tz)).isIn(BlockTags.LOGS))ground--;
   int x=tx+2,z=tz,y=ground+1;
   boolean clear=true;
   for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
    for(int dy=-3;dy<=-1;dy++){
     BlockPos p=new BlockPos(x+dx,y+dy,z+dz);BlockState state=world.getBlockState(p);
     if(!state.isSolidBlock(world,p)||!world.getFluidState(p).isEmpty()||state.isIn(BlockTags.LOGS))clear=false;
    }
    for(int dy=0;dy<=2;dy++)if(!world.getBlockState(new BlockPos(x+dx,y+dy,z+dz)).isReplaceable())clear=false;
   }
   if(!clear)continue;
   for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)for(int dy=-3;dy<=-1;dy++)
    world.setBlockState(new BlockPos(x+dx,y+dy,z+dz),Blocks.STONE_BRICKS.getDefaultState(),2);
   world.setBlockState(new BlockPos(x,y-2,z),InfrastructureBlocks.SERVICE_LIFT.getDefaultState(),2);
   world.setBlockState(new BlockPos(x,y-1,z),Blocks.LADDER.getDefaultState().with(LadderBlock.FACING,Direction.NORTH),2);
   world.setBlockState(new BlockPos(x,y,z),Blocks.OAK_TRAPDOOR.getDefaultState().with(TrapdoorBlock.HALF,BlockHalf.TOP),2);
   world.setBlockState(new BlockPos(x+1,y-1,z),Blocks.SEA_LANTERN.getDefaultState(),2);
   org.slf4j.LoggerFactory.getLogger("metro-world").debug("Технический люк под деревом: {}, {}, {}",x,y,z);
   return;
  }
 }

}
