package eu.metroworld.infrastructure.train;
import java.util.*;
import eu.metroworld.infrastructure.world.TransitGeometry;
/** Save format is deliberately independent of ephemeral render entities. */
public final class TrainData {
 public String id=UUID.randomUUID().toString(),name="Метро",anchor="",model="";
 public eu.metroworld.infrastructure.world.StationGraph.Id origin,destination;
 public boolean northSouth,freight;
 public List<String> routeAnchors=new ArrayList<>();
 public TrainPhysics control=new TrainPhysics();
 public List<TransitGeometry.Point> points=new ArrayList<>();
 public double modelScale=.75;
 public List<String> modelIds=new ArrayList<>();
 public List<Integer> modelIndices=new ArrayList<>();
 public List<Double> modelScales=new ArrayList<>();
 public String modelAt(int i){return i<modelIds.size()?modelIds.get(i):model;}
 public int modelIndex(int i){return i<modelIndices.size()?modelIndices.get(i):i;}
 public double scaleAt(int i){return i<modelScales.size()?modelScales.get(i):modelScale;}
 public List<Double> lengths=new ArrayList<>();
 public double carLength(int index){return index<lengths.size()?lengths.get(index):cars.get(index).length;}
 public List<TrainCar> cars=new ArrayList<>();
 public List<String> inventories=new ArrayList<>();
 public boolean hasTraction(){return cars.stream().anyMatch(c->!c.cargo());}
 public double length(){double value=-1;for(int i=0;i<cars.size();i++)value+=carLength(i)+1;return value;}
 public double carOffset(int index){double distance=0;for(int i=0;i<index;i++)distance+=carLength(i)+1;return distance+carLength(index)/2;}
}
