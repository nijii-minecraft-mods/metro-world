package eu.metroworld.infrastructure.world;

/** Pure station architecture rules; route topology and exits are owned by NetworkPlan. */
public enum StationVariant {
    COMPACT_SIDE, VAULTED_SIDE, ISLAND_BRIDGE, GALLERY, DISTRIBUTION_HALL, LOGISTICS;

    public static StationVariant select(long salt,int length,int width,int height,int expansion) {
        if (length < 34 || width < 15 || height < 13) return COMPACT_SIDE;
        if (expansion > 0) return ISLAND_BRIDGE;
        return switch (Math.floorMod((int)(salt >>> 20),3)) {
            case 0 -> VAULTED_SIDE; case 1 -> GALLERY; default -> DISTRIBUTION_HALL;
        };
    }
    public boolean mezzanine(){return this==GALLERY||this==DISTRIBUTION_HALL||this==LOGISTICS;}
    public boolean overheadBridge(){return this==DISTRIBUTION_HALL;}
    public int roof(int z,int width,int height){
        int az=Math.abs(z);
        if(this==COMPACT_SIDE)return height;
        if(this==DISTRIBUTION_HALL||this==LOGISTICS)return height-(az>width-3?1:0);
        return height-(az>width-4?2:az>width-7?1:0);
    }
    /** Keep the entire vehicle clearance unobstructed, including slopes at each portal. */
    public static boolean vehicleClearance(int y,int z,int expansion){
        return y>=1&&y<=3&&Math.abs(z)==3+expansion;
    }
}
