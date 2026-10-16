package eu.metroworld.infrastructure.world;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RailBlock;
import net.minecraft.block.enums.RailShape;

/** Pure block sampler; caller merges it last, and assigns loot/spawner block entities. */
public final class SideBranchBuilder {
    private SideBranchBuilder() {}
    public static BlockState sample(int x,int y,int z,SideBranchDesign.Spec s){
        if(!SideBranchDesign.inside(x,y,z,s))return null;
        boolean foot=SideBranchDesign.footprint(x,z,s);
        if(!foot){
            int depth=3;
            search:for(int r=1;r<=3;r++)for(int dx=-r;dx<=r;dx++)for(int dz=-r;dz<=r;dz++)if(SideBranchDesign.footprint(x+dx,z+dz,s)){depth=r;break search;}
            return (depth==1?Blocks.STONE:depth==2?Blocks.OBSIDIAN:Blocks.BEDROCK).getDefaultState();
        }
        if(y<0||y>14)return (y< -1||y>15?Blocks.BEDROCK:Blocks.OBSIDIAN).getDefaultState();
        if(y<2)return Blocks.STONE.getDefaultState();
        if(y==2)return (s.kind()==SideBranchDesign.Kind.QUARANTINE?Blocks.POLISHED_DIORITE:Blocks.POLISHED_DEEPSLATE).getDefaultState();
        if(!SideBranchDesign.interior(x,y,z,s)){
            if(y==7&&SideBranchDesign.corridor(x,z,s,2)&&Math.floorMod(x+z,19)==0)return Blocks.REDSTONE_LAMP.getDefaultState();
            return (s.kind()==SideBranchDesign.Kind.QUARANTINE?Blocks.WHITE_CONCRETE:
                Math.floorMod(x*31+z*17+y+(int)s.salt(),7)==0?Blocks.CRACKED_STONE_BRICKS:Blocks.STONE_BRICKS).getDefaultState();
        }
        if(SideBranchDesign.container(x,y,z,s))return Blocks.CHEST.getDefaultState();
        if(SideBranchDesign.spawner(x,y,z,s))return Blocks.SPAWNER.getDefaultState();
        if(SideBranchDesign.brokenRail(x,y,z,s))return Blocks.RAIL.getDefaultState().with(RailBlock.SHAPE,RailShape.EAST_WEST);
        // The central five-block approach is always clear. Furniture lives against the far walls.
        if(SideBranchDesign.room(x,z)){
            if(y==10&&z==70&&(x==-110||x==-90))return Blocks.SEA_LANTERN.getDefaultState();
            if(s.kind()==SideBranchDesign.Kind.QUARANTINE){
                // Containment screens flank an open central doorway; infected cells are reachable.
                if(z==75&&Math.abs(x+100)>2&&Math.abs(x+100)<16&&y<=6)
                    return Blocks.LIME_STAINED_GLASS.getDefaultState();
                if(y==3&&z==81&&(x==-108||x==-92))return Blocks.CAULDRON.getDefaultState();
                if(y==3&&z==58&&Math.floorMod(x,5)==0)return Blocks.IRON_BLOCK.getDefaultState();
            }else if(s.kind()==SideBranchDesign.Kind.SECRET_STOP){
                if(y==3&&z==78&&x>=-108&&x<=-103)return Blocks.SPRUCE_SLAB.getDefaultState();
                if(y==4&&z==79&&x>=-108&&x<=-103)return Blocks.SPRUCE_FENCE.getDefaultState();
                if(y==3&&z==64&&x>=-110&&x<=-87)return Blocks.YELLOW_CONCRETE.getDefaultState();
            }else if(s.kind()==SideBranchDesign.Kind.ABANDONED_DEPOT){
                if(z==80&&y<=4&&(x==-108||x==-104||x==-92))return Blocks.BARREL.getDefaultState();
                if(y==3&&z==60&&Math.floorMod(x,7)==0)return Blocks.COBWEB.getDefaultState();
                if(z==70){
                    if(x==-115&&y==3)return Blocks.POLISHED_BLACKSTONE.getDefaultState();
                    if(x==-115&&y==4)return Blocks.IRON_BLOCK.getDefaultState();
                    if(x==-115&&y==5)return Blocks.REDSTONE_TORCH.getDefaultState();
                    if(x==-116&&y>=3&&y<=5)return Blocks.POLISHED_BLACKSTONE.getDefaultState();
                }
            }else{
                if(z==81&&y<=5&&x>=-106&&x<=-94)return Blocks.COPPER_BLOCK.getDefaultState();
                if(z==59&&y==3&&(x==-110||x==-90))return Blocks.CRAFTING_TABLE.getDefaultState();
            }
        }
        return Blocks.AIR.getDefaultState();
    }
}
