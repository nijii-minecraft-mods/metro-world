package eu.metroworld.infrastructure.world;

import eu.metroworld.infrastructure.InfrastructureBlocks;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RailBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.RailShape;
import net.minecraft.block.enums.SlabType;

/**
 * Modern metro architecture for diagonal stations.
 * Platforms and tracks run at a 45-degree angle (DIAGONAL_NE or DIAGONAL_NW),
 * taking full advantage of the mod's continuous 3D kinematics and rotated vehicle envelope.
 * Walking platforms are at Y=3, tracks at Y=1. Null means outside this hall.
 */
public final class DiagonalStationDesign {
    private DiagonalStationDesign() {}

    public static double edge(int dx, int y, int dz, int length, int width, int height, NetworkPlan.Orientation orientation) {
        boolean nw = orientation == NetworkPlan.Orientation.DIAGONAL_NW;
        int tx = dx, tz = nw ? -dz : dz;
        double u = (tx + tz) * 0.7071067811865475;
        double v = (tz - tx) * 0.7071067811865475;
        return Math.min(Math.min(length - Math.abs(u), width - Math.abs(v)), Math.min(y, height - y));
    }

    public static boolean isRail(int dx, int dz, NetworkPlan.Orientation orientation) {
        boolean nw = orientation == NetworkPlan.Orientation.DIAGONAL_NW;
        int tx = dx, tz = nw ? -dz : dz;
        int diff = tz - tx;
        return diff == 5 || diff == 6 || diff == -6 || diff == -7;
    }

    public static RailShape railShape(int dx, int dz, NetworkPlan.Orientation orientation) {
        boolean nw = orientation == NetworkPlan.Orientation.DIAGONAL_NW;
        if (!nw) {
            return ((dx + dz) & 1) == 0 ? RailShape.NORTH_EAST : RailShape.SOUTH_WEST;
        } else {
            return ((dx - dz) & 1) == 0 ? RailShape.NORTH_WEST : RailShape.SOUTH_EAST;
        }
    }

    public static BlockState sample(int dx, int y, int dz, int length, int width, int height, long salt, NetworkPlan.Orientation orientation) {
        boolean nw = orientation == NetworkPlan.Orientation.DIAGONAL_NW;
        int tx = dx, tz = nw ? -dz : dz;
        double u = (tx + tz) * 0.7071067811865475;
        double v = (tz - tx) * 0.7071067811865475;
        double au = Math.abs(u), av = Math.abs(v);
        if (au > length || av > width || y < 0 || y > height) return null;

        BlockState accent = ((salt & 1) == 0 ? Blocks.CYAN_CONCRETE : (salt & 4) == 0 ? Blocks.BLUE_CONCRETE : Blocks.LIME_CONCRETE).getDefaultState();
        BlockState glass = ((salt & 1) == 0 ? Blocks.CYAN_STAINED_GLASS : (salt & 4) == 0 ? Blocks.LIGHT_BLUE_STAINED_GLASS : Blocks.LIME_STAINED_GLASS).getDefaultState();

        int diff = tz - tx;
        boolean track1 = (diff == 5 || diff == 6);
        boolean track2 = (diff == -6 || diff == -7);
        boolean rail = track1 || track2;
        boolean nearTrack = (diff >= 4 && diff <= 7) || (diff >= -8 && diff <= -5);

        // Vehicle clearance reserve (tracks at Y=1, headroom Y=2..6)
        if (rail && y >= 1 && y <= 6) {
            if (y == 1) return Blocks.RAIL.getDefaultState().with(RailBlock.SHAPE, railShape(dx, dz, orientation));
            return Blocks.AIR.getDefaultState();
        }

        int roof = height - (av > width - 4 ? 2 : av > width - 7 ? 1 : 0);
        if (y >= roof) {
            if (y == roof && (Math.abs(av - 7) < 1.3 || av > width - 3) && Math.floorMod((int)Math.round(u), 4) != 0)
                return Blocks.SEA_LANTERN.getDefaultState();
            return (y == roof && Math.floorMod((int)Math.round(u), 12) == 0 ? Blocks.SMOOTH_QUARTZ : Blocks.WHITE_CONCRETE).getDefaultState();
        }

        // Sub-track foundation at Y=0
        if (y == 0) {
            return (nearTrack ? (Math.floorMod((int)Math.round(u), 3) == 0 ? Blocks.POLISHED_DEEPSLATE : Blocks.GRAVEL) : Blocks.SMOOTH_STONE).getDefaultState();
        }

        int bay = Math.floorMod((int)Math.round(u) + 6, 12);

        // Platform surfaces and tactile paving at Y=2, platform walking at Y=3
        // Central island platform between tracks (diff between -5 and 4)
        boolean island = diff >= -5 && diff <= 4;
        // Side platforms outside tracks (diff <= -8 or diff >= 7)
        boolean sidePlatform = diff <= -8 || diff >= 7;

        if (island && y <= 2) {
            if (y == 2 && (diff == 4 || diff == -5)) return InfrastructureBlocks.TACTILE_TILE.getDefaultState();
            if (y == 2 && (Math.floorMod((int)Math.round(u), 6) == 0 || Math.abs(diff) <= 1)) return Blocks.POLISHED_ANDESITE.getDefaultState();
            return Blocks.SMOOTH_QUARTZ.getDefaultState();
        }

        if (sidePlatform && av < width && y <= 2) {
            if (y == 2 && (diff == 8 || diff == -9)) return InfrastructureBlocks.TACTILE_TILE.getDefaultState();
            if (y == 2 && (Math.floorMod((int)Math.round(u), 6) == 0 || av >= width - 2)) return Blocks.POLISHED_ANDESITE.getDefaultState();
            return Blocks.SMOOTH_QUARTZ.getDefaultState();
        }

        // Wooden benches on platform aisles: lower slab seats with trapdoor backs
        if (au < length - 10 && bay >= 3 && bay <= 6) {
            if (y == 2 && av >= width - 4 && av < width - 2)
                return Blocks.SMOOTH_STONE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.BOTTOM);
            if (y == 2 && av >= width - 2)
                return Blocks.DARK_OAK_TRAPDOOR.getDefaultState().with(TrapdoorBlock.OPEN, true).with(TrapdoorBlock.HALF, BlockHalf.BOTTOM);
        }

        // Planters with flowers bounded by curb
        if (au < length - 12 && av >= width - 4 && av <= width - 2 && bay >= 8 && bay <= 10) {
            if (y == 3) {
                if (bay == 9) return Blocks.GRASS_BLOCK.getDefaultState();
                return Blocks.SMOOTH_STONE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.BOTTOM);
            }
            if (y == 4 && bay == 9) {
                return ((salt & 2) == 0 ? Blocks.PINK_TULIP : Blocks.OXEYE_DAISY).getDefaultState();
            }
        }

        // Structural ribs with ribbed panels and lanterns
        boolean rib = Math.floorMod((int)Math.round(u), 12) == 0 && au < length - 4;
        if (rib && av >= width - 4 && av < width - 1 && y >= 3 && y < roof) {
            if (y == roof - 1) return Blocks.SEA_LANTERN.getDefaultState();
            return InfrastructureBlocks.RIBBED_PANEL.getDefaultState();
        }
        if (rib && y == roof - 1 && av >= 4) return Blocks.SMOOTH_QUARTZ.getDefaultState();

        // Outer service walls
        if (av >= width - 1) {
            if (y == 4 || y == 10) return accent;
            if (y >= 5 && y <= 8 && au < length - 5 && Math.floorMod((int)Math.round(u), 8) != 0) return glass;
            return Blocks.SMOOTH_QUARTZ.getDefaultState();
        }

        // End portals remain open for track corridors
        if (au >= length - 2) {
            if (nearTrack && y <= 6) return Blocks.AIR.getDefaultState();
            if (y == 8) return accent;
            return Blocks.SMOOTH_QUARTZ.getDefaultState();
        }

        // Overhead wayfinding signs
        if (Math.floorMod((int)Math.round(u), 24) == 6 && av >= 6 && av <= 9 && y == 8) return accent;

        return Blocks.AIR.getDefaultState();
    }
}
