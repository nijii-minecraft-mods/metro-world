import eu.metroworld.infrastructure.world.StationVariant;
/** Dependency-free smoke test for circulation selection and protected vehicle clearance. */
public final class StationVariantChecks {
 private static void require(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  for(int i=0;i<256;i++){
   require(StationVariant.select((long)i<<20,28,12,10,0)==StationVariant.COMPACT_SIDE);
   require(StationVariant.select((long)i<<20,40,17,14,4)==StationVariant.ISLAND_BRIDGE);
  }
  require(StationVariant.select(0,40,17,14,0)==StationVariant.VAULTED_SIDE);
  require(StationVariant.select(1L<<20,40,17,14,0)==StationVariant.GALLERY);
  require(StationVariant.select(2L<<20,40,17,14,0)==StationVariant.DISTRIBUTION_HALL);
  for(int expansion:new int[]{0,4})for(int side:new int[]{-1,1})for(int y=1;y<=3;y++)for(int dz:new int[]{0})
   require(StationVariant.vehicleClearance(y,side*(3+expansion)+dz,expansion));
  require(!StationVariant.vehicleClearance(4,3,0));
  for(var variant:StationVariant.values())for(int z=-17;z<=17;z++){
   require(variant.roof(z,17,14)>=12);
   require(variant.roof(z,17,14)==variant.roof(-z,17,14));
  }
  System.out.println("STATION_VARIANTS_OK: compact/island selection, 3 circulation plans, 12 vehicle cells, 210 symmetric roof samples");
 }
}
