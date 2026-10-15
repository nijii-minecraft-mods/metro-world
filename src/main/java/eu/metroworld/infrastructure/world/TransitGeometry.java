package eu.metroworld.infrastructure.world;

import java.util.ArrayList;
import java.util.List;

/** Seed-independent geometry primitives. Coordinates are sampled before chunk clipping. */
public final class TransitGeometry {
    private TransitGeometry() {}
    public record Point(double x, double y, double z) {}
    public record Sample(double distance, double floorY, double headingX, double headingZ, double along, double signedOffset) {}
    public static final class Route {
        private final List<Point> points;
        private final double minX,maxX,minZ,maxZ;
        private final int segCount;
        private final double[] segDx, segDz, segDy, segLen, segLenSq, segTravel;
        private final double[] segMinX, segMaxX, segMinZ, segMaxZ;
        private final double totalLength;

        public Route(List<Point> input) {
            if(input.size()<2) throw new IllegalArgumentException("A route needs two points");
            points=List.copyOf(input);
            minX=points.stream().mapToDouble(Point::x).min().orElseThrow();
            maxX=points.stream().mapToDouble(Point::x).max().orElseThrow();
            minZ=points.stream().mapToDouble(Point::z).min().orElseThrow();
            maxZ=points.stream().mapToDouble(Point::z).max().orElseThrow();
            segCount = points.size() - 1;
            segDx = new double[segCount];
            segDz = new double[segCount];
            segDy = new double[segCount];
            segLen = new double[segCount];
            segLenSq = new double[segCount];
            segTravel = new double[segCount];
            segMinX = new double[segCount];
            segMaxX = new double[segCount];
            segMinZ = new double[segCount];
            segMaxZ = new double[segCount];
            double travel = 0;
            for(int i = 0; i < segCount; i++){
                Point a = points.get(i), b = points.get(i + 1);
                double dx = b.x - a.x, dz = b.z - a.z, dy = b.y - a.y;
                double len = Math.hypot(dx, dz);
                segDx[i] = dx;
                segDz[i] = dz;
                segDy[i] = dy;
                segLen[i] = len;
                segLenSq[i] = len * len;
                segTravel[i] = travel;
                travel += len;
                segMinX[i] = Math.min(a.x, b.x);
                segMaxX[i] = Math.max(a.x, b.x);
                segMinZ[i] = Math.min(a.z, b.z);
                segMaxZ[i] = Math.max(a.z, b.z);
            }
            totalLength = travel;
        }
        public List<Point> points(){return points;}
        public double length(){return totalLength;}
        /** Unit X/Z tangent at a distance measured along the route. */
        public Point headingAt(double distance){
            for(int i = 0; i < segCount; i++){
                double len = segLen[i];
                if(len < 1e-8) continue;
                if(segTravel[i] + len >= distance) return new Point(segDx[i] / len, 0, segDz[i] / len);
            }
            int last = segCount - 1;
            double len = segLen[last];
            return len >= 1e-8 ? new Point(segDx[last] / len, 0, segDz[last] / len) : new Point(0, 0, 0);
        }
        public boolean intersects(int chunkX,int chunkZ,double padding){
            return maxX+padding>=chunkX&&minX-padding<=chunkX+15&&maxZ+padding>=chunkZ&&minZ-padding<=chunkZ+15;
        }
        /** Column query, preserving separate levels of a helix; run once per x/z column. */
        public List<Sample> samplesNear(double x,double z,double halfWidth){
            List<Sample> found = null;
            for(int i = 0; i < segCount; i++){
                double len = segLen[i];
                if(len < 1e-8) continue;
                if(x < segMinX[i] - halfWidth || x > segMaxX[i] + halfWidth || z < segMinZ[i] - halfWidth || z > segMaxZ[i] + halfWidth) continue;
                double dx = segDx[i], dz = segDz[i];
                Point a = points.get(i);
                double t = Math.max(0, Math.min(1, ((x - a.x) * dx + (z - a.z) * dz) / segLenSq[i]));
                double px = x - a.x - t * dx, pz = z - a.z - t * dz;
                double dist = Math.hypot(px, pz);
                if(dist <= halfWidth){
                    double floor = a.y + t * segDy[i];
                    Sample sample = new Sample(dist, floor, dx / len, dz / len, segTravel[i] + t * len, (-px * dz + pz * dx) / len);
                    if(found == null) found = new ArrayList<>();
                    int match = -1;
                    for(int k = 0; k < found.size(); k++){
                        if(Math.abs(found.get(k).floorY - floor) < 8){ match = k; break; }
                    }
                    if(match < 0) found.add(sample);
                    else if(dist < found.get(match).distance) found.set(match, sample);
                }
            }
            return found == null ? List.of() : List.copyOf(found);
        }
        /** Use this for tubes without vertical loops. For helices use nearest(x,y,z). */
        public Sample nearest(double x,double z){return nearest(x,Double.NaN,z);}
        public Sample nearest(double x,double y,double z){
            double best = Double.POSITIVE_INFINITY; Sample result = null;
            boolean hasY = !Double.isNaN(y);
            for(int i = 0; i < segCount; i++){
                double len = segLen[i];
                if(len < 1e-8) continue;
                double dx = segDx[i], dz = segDz[i], dy = segDy[i];
                Point a = points.get(i);
                double t = Math.max(0, Math.min(1, ((x - a.x) * dx + (z - a.z) * dz) / segLenSq[i]));
                double floor = a.y + t * dy;
                double px = x - a.x - t * dx, pz = z - a.z - t * dz;
                double dist = Math.hypot(px, pz);
                double yDiff = hasY ? y - floor : 0;
                double score = dist * dist + (hasY ? yDiff * yDiff : 0);
                if(score < best){
                    best = score;
                    result = new Sample(dist, floor, dx / len, dz / len, segTravel[i] + t * len, (-px * dz + pz * dx) / len);
                }
            }
            return result;
        }
    }
    /** Broad horizontal quarter arcs replace instantaneous right-angle corners. */
    public static Route rounded(List<Point> controls,double radius){
        List<Point> out=new ArrayList<>();out.add(controls.get(0));
        for(int i=1;i<controls.size()-1;i++){
            Point a=controls.get(i-1),b=controls.get(i),c=controls.get(i+1);
            double l1=Math.hypot(b.x-a.x,b.z-a.z),l2=Math.hypot(c.x-b.x,c.z-b.z);
            if(l1<1e-6||l2<1e-6)continue;
            double r=Math.min(radius,Math.min(l1,l2)*0.42);
            Point entry=mix(b,a,r/l1),exit=mix(b,c,r/l2);
            appendLine(out,entry);
            // Quadratic fillet: tangents match both straight approach segments.
            int steps=Math.max(8,(int)Math.ceil(r*3));
            for(int k=1;k<=steps;k++){
                double t=(double)k/steps,u=1-t;
                out.add(new Point(u*u*entry.x+2*u*t*b.x+t*t*exit.x,u*u*entry.y+2*u*t*b.y+t*t*exit.y,u*u*entry.z+2*u*t*b.z+t*t*exit.z));
            }
        }
        appendLine(out,controls.get(controls.size()-1));return new Route(out);
    }
    private static Point mix(Point a,Point b,double t){return new Point(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t,a.z+(b.z-a.z)*t);}
    private static void appendLine(List<Point> out,Point end){
        Point start=out.get(out.size()-1);int n=Math.max(1,(int)Math.ceil(Math.hypot(end.x-start.x,end.z-start.z)/1.5));
        for(int k=1;k<=n;k++)out.add(mix(start,end,(double)k/n));
    }
    /** Forward-only connection between matching station ports. No coils or return legs. */
    public static Route connection(Point a,Point b,boolean northSouth,int aLength,int bLength){
        double u0=northSouth?a.z:a.x,u1=northSouth?b.z:b.x,v0=northSouth?a.x:a.z,v1=northSouth?b.x:b.z;
        double start=u0+aLength,end=u1-bLength;
        if(end-start<128)throw new IllegalArgumentException("Station ports too close");
        // Flat straight throats and a broad S-bend: no perpendicular leg or abrupt corner.
        var horizontalPoints=new ArrayList<Point>();horizontalPoints.add(new Point(start,0,v0));
        appendLine(horizontalPoints,new Point(start+24,0,v0));
        int steps=(int)Math.ceil((end-start-48)/1.5);
        for(int i=1;i<=steps;i++){
            double t=(double)i/steps,smooth=t*t*(3-2*t);
            horizontalPoints.add(new Point(start+24+(end-start-48)*t,0,v0+(v1-v0)*smooth));
        }
        appendLine(horizontalPoints,new Point(end,0,v1));
        Route horizontal=new Route(horizontalPoints);double length=horizontal.length(),travel=0;var out=new ArrayList<Point>();
        if(Math.abs(b.y-a.y)/(length-32)>.26)throw new IllegalArgumentException("Station grade too steep");
        for(int i=0;i<horizontal.points.size();i++){
            Point p=horizontal.points.get(i);if(i>0){Point q=horizontal.points.get(i-1);travel+=Math.hypot(p.x-q.x,p.z-q.z);}
            double t=Math.max(0,Math.min(1,(travel-16)/(length-32))),y=a.y+(b.y-a.y)*t;
            out.add(northSouth?new Point(p.z,y,p.x):new Point(p.x,y,p.z));
        }
        return new Route(out);
    }
    /** Dedicated side bay for large rises; both ends remain part of a station-to-station edge. */
    public static Route spiralConnection(Point a,Point b,boolean northSouth,int aLength,int bLength){
        double start=(northSouth?a.z:a.x)+aLength,end=(northSouth?b.z:b.x)-bLength;
        double v0=northSouth?a.x:a.z,v1=northSouth?b.x:b.z;
        if(Math.abs(b.y-a.y)<32||end-start<320)return connection(a,b,northSouth,aLength,bLength);
        double bay=v0+(northSouth?-156:96),u=start+192;
        var approach=rounded(List.of(new Point(start,a.y,v0),new Point(start+80,a.y,v0),new Point(start+80,a.y,bay),new Point(u,a.y,bay)),16);
        var points=new ArrayList<Point>(approach.points());
        var coil=helix(new Point(u,a.y,bay),b.y,30).points();points.addAll(coil.subList(1,coil.size()));
        var tail=connection(new Point(u,b.y,bay),new Point(end,b.y,v1),false,0,0).points();points.addAll(tail.subList(1,tail.size()));
        if(northSouth){var rotated=new ArrayList<Point>();for(var p:points)rotated.add(new Point(p.z,p.y,p.x));return new Route(rotated);}
        return new Route(points);
    }
    /** Two independent one-rail tubes diverge from and reunite with the matching mainline lanes. */
    public static List<Route> splitMerge(Route mainline,int startGap,int endGap,boolean separate){
        double length=mainline.length();var lanes=new ArrayList<List<Point>>();lanes.add(new ArrayList<>());lanes.add(new ArrayList<>());
        double along=0;
        for(int i=0;i<mainline.points.size();i++){
            Point p=mainline.points.get(i);if(i>0){Point q=mainline.points.get(i-1);along+=Math.hypot(p.x-q.x,p.z-q.z);}
            double t=length==0?0:along/length,spread=startGap+(endGap-startGap)*(t*t*(3-2*t))+(separate?9*Math.pow(Math.sin(Math.PI*t),2):0),rise=separate?5*Math.pow(Math.sin(Math.PI*t),2):0;
            Point heading=mainline.headingAt(along);double nx=-heading.z,nz=heading.x;
            lanes.get(0).add(new Point(p.x+nx*spread,p.y+rise,p.z+nz*spread));
            lanes.get(1).add(new Point(p.x-nx*spread,p.y-rise,p.z-nz*spread));
        }
        return List.of(new Route(lanes.get(0)),new Route(lanes.get(1)));
    }
    /** Full circular ramp, entering and leaving eastbound. Turns separated by >=24 blocks. */
    public static Route helix(Point entry,double targetY,double radius){
        double rise=targetY-entry.y;
        if(Math.abs(rise)<20)throw new IllegalArgumentException("Helix requires at least 20 blocks rise");
        int turns=Math.max(1,(int)Math.floor(Math.abs(rise)/24));
        int n=(int)Math.ceil(turns*Math.PI*2*radius/1.25);
        List<Point> out=new ArrayList<>();
        for(int i=0;i<=n;i++){
            double t=(double)i/n,angle=-Math.PI/2+t*turns*Math.PI*2;
            out.add(new Point(entry.x+radius*Math.cos(angle),entry.y+rise*t,entry.z+radius+radius*Math.sin(angle)));
        }
        return new Route(out);
    }
    /** East/west station throats always start flat before the route bends outside the building. */
    public static Route between(Point a,Point b,int direction,double stationHalfLength,long salt){
        double lead=stationHalfLength+24,r=24;
        Point throat=new Point(a.x+stationHalfLength,a.y,a.z);
        Point transition=new Point(a.x+lead+40,a.y,a.z);
        List<Point> out=new ArrayList<>();out.add(throat);
        if(direction!=0){
            // Branch before the first coil and bypass it on the north side.
            Point fork=new Point(a.x+64,a.y,a.z);
            transition=new Point(a.x+232,a.y,a.z-112);
            var approach=rounded(List.of(throat,fork,new Point(fork.x,a.y,a.z-112),transition),16).points();
            out.addAll(approach.subList(1,approach.size()));
        }else appendLine(out,transition);
        double rise=b.y-a.y;
        if(Math.abs(rise)>=20){
            Point coilEntry=transition;
            List<Point> coil=helix(coilEntry,b.y,30).points();
            out.addAll(coil.subList(1,coil.size()));
        }else if(Math.abs(rise)>0){
            // Small changes use a long straight walking ramp, never a vertical stair shaft.
            transition=new Point(transition.x+Math.abs(rise)*5,b.y,transition.z);
            appendLine(out,transition);
        }
        Point elevated=out.get(out.size()-1);
        List<Point> p=new ArrayList<>();p.add(elevated);
        if(direction==0){
            double middle=Math.max(elevated.x+48,(a.x+b.x)/2);
            p.add(new Point(middle,b.y,a.z));p.add(new Point(middle,b.y,b.z));
            p.add(new Point(b.x-lead,b.y,b.z));
        }else{
            double outside=Math.max(elevated.x,b.x+lead)+64+Math.floorMod(salt,24);
            double middle=(a.z+b.z)/2;
            p.add(new Point(outside,b.y,elevated.z));p.add(new Point(outside,b.y,middle));
            p.add(new Point(b.x-lead,b.y,middle));p.add(new Point(b.x-lead,b.y,b.z));
        }
        p.add(new Point(b.x-stationHalfLength,b.y,b.z));
        List<Point> tail=rounded(p,r).points();out.addAll(tail.subList(1,tail.size()));
        return new Route(out);
    }
}
