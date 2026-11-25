package dev.bustling;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Local collision-shape navigation. No real entities are created or ticked. */
public final class CrowdNavigation {
    private static final Map<String, Group> GROUPS = new HashMap<>();
    private static ClientLevel world;
    private static long budgetTick = Long.MIN_VALUE;
    private static int queryBudget, initBudget, queriesLeft, initLeft, passiveInitLeft, sharingGroups=1;
    private static Group geometryGroup;
    private static CrowdData.Emitter domain;
    private static final int MAX_QUERIES_PER_TICK=384;
    private static final double[] BYPASS=new double[3];
    private static final CrowdSpacing SPACING = new CrowdSpacing();
    private static final List<CrowdSpacing.Body> NEIGHBORS = new ArrayList<>();
    private static final double[] PRESSURE = new double[2];
    private static final double[] DIRECT_TURN = {0};
    private static final double[] AVOID_TURNS = {0, .45, -.45, .9, -.9, Math.PI / 2, -Math.PI / 2};
    private record TileKey(int x,int z,long y,long r,long h){}
    private static final Map<TileKey,Boolean> FLAT=new HashMap<>();
    private static final class Group {
        final Map<Integer, Agent> agents = new HashMap<>();
        final Map<Integer, Long> failedUntil = new HashMap<>();
        final List<CrowdSampler.Ghost> targets = new ArrayList<>();
        final List<CrowdSampler.Ghost> retiring = new ArrayList<>();
        long sampleTick=Long.MIN_VALUE; int sampledCount=-1;
        CrowdData.Emitter source; double renderTime;long shapeSignature=Long.MIN_VALUE;
        long seen, queryTick=Long.MIN_VALUE;int queryCount,initializations,activeCount;
        CrowdSampler.Ghost[] output=new CrowdSampler.Ghost[0];
    }
    private static final class Agent {
        double x,y,z,px,py,pz,fx,fy,fz,stamp,yaw;
        long tick=Long.MIN_VALUE, attemptTick=Long.MIN_VALUE, retry,seen;
        CrowdSpacing.Body body;
        boolean interacting;
        boolean finalSet, interpolate=true, finalInterpolate;
        long finalTick=Long.MIN_VALUE, nextLook,offsetTick=Long.MIN_VALUE;
        double fsx,fsy,fsz,alpha;
        final double[] output=new double[3];
        final double[] track=new double[192],scratch=new double[192],lengths=new double[64];
        int trackCount,scratchCount;
        double renderX,renderY,renderZ,renderYaw,gait,move,clock=Double.NaN,transitionStart,transitionDuration,fromYaw,toYaw;
        boolean displayed,stepMotion,active,standing,falling,hidden;
        boolean facingPlayer;
        double playerX, playerZ;
        double fallSpeed,lookFrom,lookTo,lookStart,lookDuration;CrowdSampler.Ghost frozen;
        CrowdFlow.State flow;double compressX,compressZ,compressClock=Double.NaN;int turnSide=1;long sideUntil;
        List<Node> route;
        int waypoint;
        double goalX,goalZ,originX,originZ;
    }
    private static final class Node {
        final int x,z; final double y,g,f; final Node parent;
        Node(int x,int z,double y,double g,double f,Node p){this.x=x;this.z=z;this.y=y;this.g=g;this.f=f;parent=p;}
    }
    private CrowdNavigation(){}
    /** Releases local simulation state when the world system is switched off. */
    public static void clear(){GROUPS.clear();SEPARATION.clear();FLAT.clear();SPACING.clear();NEIGHBORS.clear();CrowdFlow.clear();geometryGroup=null;domain=null;world=null;budgetTick=Long.MIN_VALUE;currentGroup="";}
    public static boolean hasActive(CrowdData.Emitter e){if(!WorldSystem.enabled)return false;Group g=GROUPS.get(e.dim+":"+e.id);return g!=null&&g.activeCount>0;}
    public static boolean isActive(CrowdData.Emitter e,int index){
        if(!WorldSystem.enabled)return false;Group g=GROUPS.get(e.dim+":"+e.id);Agent a=g==null?null:g.agents.get(index);
        return a!=null&&a.active&&WorldSystem.active(a.renderX,a.renderY,a.renderZ);
    }
    static boolean grounded(CrowdData.Emitter emitter, int index) {
        Group group = GROUPS.get(emitter.dim + ":" + emitter.id);
        Agent actor = group == null ? null : group.agents.get(index);
        return actor == null || !actor.falling;
    }

    private static void context(ClientLevel level) {
        if(world!=level){GROUPS.clear();SEPARATION.clear();FLAT.clear();SPACING.clear();NEIGHBORS.clear();geometryGroup=null;world=level;budgetTick=Long.MIN_VALUE;}
        long tick=level.getGameTime();
        if(tick!=budgetTick){
            budgetTick=tick;
            queryBudget=MAX_QUERIES_PER_TICK * WorldSystem.navigationBudget;
            initBudget=32 * WorldSystem.navigationBudget;
            queriesLeft=queryBudget;initLeft=initBudget;passiveInitLeft=2;FLAT.clear();
            SPACING.clear();
            NEIGHBORS.clear();
            int influence = WorldSystem.playerInfluenceRadius();
            if (influence > 0) {
                for (Group group : GROUPS.values()) {
                    if (tick - group.seen > 2) continue;
                    for (Agent actor : group.agents.values()) {
                        if (actor.active && !actor.hidden && tick - actor.seen <= 2
                                && interactionArea(actor, influence + 5)) registerBody(actor);
                    }
                }
            }
            sharingGroups=0;for(Group g:GROUPS.values())if(tick-g.seen<=4)sharingGroups++;sharingGroups=Math.max(1,sharingGroups);
            if(tick%100==0){GROUPS.values().removeIf(g->tick-g.seen>200);SEPARATION.keySet().retainAll(GROUPS.keySet());for(Group g:GROUPS.values())g.agents.values().removeIf(a->tick-a.seen>200);}
        }
    }
    private static Group group(CrowdData.Emitter e){
        Group g=GROUPS.computeIfAbsent(e.dim+":"+e.id,k->new Group());g.seen=world.getGameTime();geometryGroup=g;domain=e;return g;
    }
    private static boolean loaded(double x,double z,double r,double y){
        return world.hasChunkAt(BlockPos.containing(x-r,y,z-r)) && world.hasChunkAt(BlockPos.containing(x+r,y,z+r))
            && world.hasChunkAt(BlockPos.containing(x-r,y,z+r)) && world.hasChunkAt(BlockPos.containing(x+r,y,z-r));
    }
    private static int remainingQueries(){
        if(geometryGroup==null)return queriesLeft;
        if(geometryGroup.queryTick!=budgetTick){geometryGroup.queryTick=budgetTick;geometryGroup.queryCount=0;geometryGroup.initializations=0;}
        return Math.min(queriesLeft,Math.max(0,Math.max(8,queryBudget/sharingGroups)-geometryGroup.queryCount));
    }
    private static Iterable<VoxelShape> query(AABB box){
        if(remainingQueries()<=0)return null;
        queriesLeft--;if(geometryGroup!=null)geometryGroup.queryCount++;return world.getBlockCollisions(null,box);
    }
    private static boolean covered(List<AABB> surfaces,double loX,double loZ,double hiX,double hiZ){
        List<Double> xs=new ArrayList<>();xs.add(loX);xs.add(hiX);
        for(AABB b:surfaces){if(b.minX>loX&&b.minX<hiX)xs.add(b.minX);if(b.maxX>loX&&b.maxX<hiX)xs.add(b.maxX);}
        Collections.sort(xs);
        for(int i=1;i<xs.size();i++){
            double left=xs.get(i-1),right=xs.get(i);if(right-left<1e-9)continue;double x=(left+right)*.5;
            List<AABB> bands=new ArrayList<>();for(AABB b:surfaces)if(b.minX<=x&&b.maxX>=x)bands.add(b);
            bands.sort(Comparator.comparingDouble(b->b.minZ));double end=loZ;
            for(AABB b:bands){if(b.maxZ<=end)continue;if(b.minZ>end+1e-8)break;end=Math.max(end,b.maxZ);}
            if(end<hiZ-1e-8)return false;
        }return true;
    }
    private static boolean tile(int x,int z,double y,double r,double h,boolean create){
        TileKey key=new TileKey(x,z,Double.doubleToLongBits(y),Double.doubleToLongBits(r),Double.doubleToLongBits(h));
        Boolean known=FLAT.get(key);if(known!=null)return known;
        if(!create||remainingQueries()<2||!loaded(x+.5,z+.5,r+.5,y))return false;
        double lx=x-r,lz=z-r,hx=x+1+r,hz=z+1+r;
        Iterable<VoxelShape> body=query(new AABB(lx,y+.025,lz,hx,y+h,hz));if(body==null)return false;
        if(body.iterator().hasNext()){FLAT.put(key,false);return false;}
        Iterable<VoxelShape> support=query(new AABB(lx,y-.03,lz,hx,y+.001,hz));if(support==null)return false;
        List<AABB> faces=new ArrayList<>();for(VoxelShape shape:support)for(AABB b:shape.toAabbs())if(Math.abs(b.maxY-y)<1e-7)faces.add(b);
        boolean safe=covered(faces,lx,lz,hx,hz);FLAT.put(key,safe);return safe;
    }
    private static boolean flat(double x,double y,double z,double tx,double tz,double r,double h,boolean create){
        int lx=(int)Math.floor(Math.min(x,tx)),hx=(int)Math.floor(Math.max(x,tx)),lz=(int)Math.floor(Math.min(z,tz)),hz=(int)Math.floor(Math.max(z,tz));
        if(hx-lx>3||hz-lz>3)return false;
        for(int ix=lx;ix<=hx;ix++)for(int iz=lz;iz<=hz;iz++)if(!tile(ix,iz,y,r,h,create))return false;
        return true;
    }
    private static boolean corridor(double x,double y,double z,double tx,double tz,double r,double h){
        if(flat(x,y,z,tx,tz,r,h,false))return true;
        if(!loaded(x,z,r,y)||!loaded(tx,tz,r,y))return false;
        Iterable<VoxelShape> shapes=query(new AABB(Math.min(x,tx)-r,y+.025,Math.min(z,tz)-r,Math.max(x,tx)+r,y+h,Math.max(z,tz)+r));
        return shapes!=null&&!shapes.iterator().hasNext();
    }
    private static boolean clear(double x,double y,double z,double r,double h){
        return corridor(x,y,z,x,z,r,h);
    }
    private static double floor(double x,double y,double z,double r,double h,boolean ground){return floor(x,y,z,r,h,ground,DiffuserData.climb(domain));}
    private static double floor(double x,double y,double z,double r,double h,boolean ground,double climb){
        if(domain!=null&&!CrowdBounds.inside(domain,x,z,r))return Double.NaN;
        if(!ground)return clear(x,y,z,r,h)?y:Double.NaN;
        if(!loaded(x,z,r,y))return Double.NaN;
        if(flat(x,y,z,x,z,r,h,false))return y;
        double best=Double.NEGATIVE_INFINITY;
        double drop=Math.max(1,climb);
        AABB probe=new AABB(x-r,y-drop-.05,z-r,x+r,y+climb+.01,z+r);
        Iterable<VoxelShape> shapes=query(probe);if(shapes==null)return Double.NaN;
        for(VoxelShape shape:shapes)for(AABB b:shape.toAabbs()){
            if(b.maxY<=y+climb+.001 && b.maxY>=y-drop-.001 && b.maxX>x-r && b.minX<x+r && b.maxZ>z-r && b.minZ<z+r)
                best=Math.max(best,b.maxY);
        }
        return Double.isFinite(best)&&clear(x,best,z,r,h)?best:Double.NaN;
    }
    /** Sample the entire swept body, including diagonal corners; missing chunks are blocked. */
    private static double sweep(double x,double y,double z,double tx,double tz,double r,double h,boolean ground){return trace(x,y,z,tx,tz,r,h,ground,null);}
    private static void append(Agent a,double x,double y,double z){if(a==null)return;int i=a.scratchCount++*3;a.scratch[i]=x;a.scratch[i+1]=y;a.scratch[i+2]=z;}
    private static boolean vertical(double x,double y,double z,double ny,double r,double h){
        Iterable<VoxelShape> shapes=query(new AABB(x-r,Math.min(y,ny)+.025,z-r,x+r,Math.max(y,ny)+h,z+r));
        return shapes!=null&&!shapes.iterator().hasNext();
    }
    /** Produces a collision-checked continuous trajectory, including vertical step phases. */
    private static double trace(double x,double y,double z,double tx,double tz,double r,double h,boolean ground,Agent capture){
        if(capture!=null)capture.scratchCount=0;
        int steps=Math.max(1,(int)Math.ceil(Math.hypot(tx-x,tz-z)/.10));if(steps>24)return Double.NaN;
        double ox=x,oz=z;append(capture,x,y,z);
        if(ground&&(domain==null||CrowdBounds.segment(domain,x,z,tx,tz,r))&&flat(x,y,z,tx,tz,r,h,true)){
            append(capture,tx,y,tz);return y;
        }
        for(int i=1;i<=steps;i++){
            double nx=ox+(tx-ox)*i/steps,nz=oz+(tz-oz)*i/steps;
            if(domain!=null&&!CrowdBounds.segment(domain,x,z,nx,nz,r))return Double.NaN;
            double ny=floor(nx,y,nz,r,h,ground);if(!Double.isFinite(ny))return Double.NaN;
            if(Math.abs(ny-y)>1e-6){
                if(!vertical(ny>y?x:nx,y,ny>y?z:nz,ny,r,h)||!corridor(x,Math.max(y,ny),z,nx,nz,r,h))return Double.NaN;
                if(ny>y)append(capture,x,ny,z);else append(capture,nx,y,nz);
            }
            append(capture,nx,ny,nz);x=nx;y=ny;z=nz;
        }return y;
    }
    private static double radius(CrowdSampler.Ghost g){return Math.max(.18,Math.min(1.2,.30*g.size()/1.875));}
    private static double height(CrowdSampler.Ghost g){return Math.max(.4,Math.min(6,g.size()));}
    private static Agent agent(Group group,CrowdSampler.Ghost ghost,boolean ground,boolean active){
        Agent a=group.agents.get(ghost.idx());
        if(a!=null)return a;
        long now=world.getGameTime();int remaining=remainingQueries();
        if(group.failedUntil.getOrDefault(ghost.idx(),Long.MIN_VALUE)>now || initLeft<=0 || (!active&&passiveInitLeft<=0) || group.initializations>=Math.max(1,initBudget/sharingGroups) || remaining<4)return null;
        if (!CrowdPresence.outsideSpawnView(ghost.x(), ghost.y(), ghost.z(), ghost.size())) return null;
        initLeft--;if(!active)passiveInitLeft--;group.initializations++;
        double r=radius(ghost),h=height(ghost);
        double[] projected=CrowdBounds.project(domain,ghost.x(),ghost.z(),r);
        if(projected==null){group.failedUntil.put(ghost.idx(),now+40);return null;}
        double x=projected[0],z=projected[1],y=floor(x,ghost.y(),z,r,h,ground,1);
        var body = new CrowdSpacing.Body(((long)domain.id.hashCode() << 32) ^ ghost.idx());
        body.radius = r;
        body.height = h;
        int influence = WorldSystem.playerInfluenceRadius();
        boolean placeWithSpace = active && influence > 0 && interactionArea(x, z, influence + 3);
        if (placeWithSpace && Double.isFinite(y) && !SPACING.canPlace(body, x, y, z, NEIGHBORS)) y = Double.NaN;
        if (!Double.isFinite(y)) {
            // Give new actors a free slot without relaxing their area's swept body checks.
            int rings = placeWithSpace ? 5 : 2;
            double increment = placeWithSpace ? Math.max(.35, r) : .25;
            double phase = placeWithSpace ? ghost.idx() * 2.3999632297 : 0;
            boolean viewLimited = false;
            outer: for (int ring = 1; ring <= rings; ring++) {
                for (int j = 0; j < 8; j++) {
                    double angle = phase + j * Math.PI / 4;
                    double xx = x + ring * increment * Math.cos(angle);
                    double zz = z + ring * increment * Math.sin(angle);
                    if (placeWithSpace && !SPACING.canPlace(body, xx, ghost.y(), zz, NEIGHBORS)) continue;
                    double yy = floor(xx, ghost.y(), zz, r, h, ground, 1);
                    if (Double.isFinite(yy) && (!placeWithSpace || SPACING.canPlace(body, xx, yy, zz, NEIGHBORS))) {
                        if (!CrowdPresence.outsideSpawnView(xx, yy, zz, ghost.size())) {
                            viewLimited = true;
                            if (remainingQueries() == 0) break outer;
                            continue;
                        }
                        x=xx;z=zz;y=yy;
                        break outer;
                    }
                    if (remainingQueries() == 0) break outer;
                }
            }
            if (!Double.isFinite(y) && viewLimited) return null;
        }
        if(!Double.isFinite(y)){
            group.failedUntil.put(ghost.idx(),now+(remainingQueries()==0?2:placeWithSpace?8:40)+(ghost.idx()&7));
            return null;
        }
        if (!CrowdPresence.outsideSpawnView(x, y, z, ghost.size())) return null;
        group.failedUntil.remove(ghost.idx());
        a=new Agent();
        a.body = body;
        a.x=a.px=x;a.y=a.py=y;a.z=a.pz=z;a.yaw=ghost.yaw();a.flow=CrowdFlow.create(domain,ghost.idx());CrowdFlow.attach(domain,a.flow,x,z);a.yaw=a.flow.heading;a.turnSide=(ghost.idx()&1)==0?1:-1;group.agents.put(ghost.idx(),a);return a;
    }
    /** Coarse rejection happens before the original trigonometry/path sampler. */
    public static void sample(CrowdData.Emitter e,double time,List<CrowdSampler.Ghost> out){
        if(!WorldSystem.enabled||!e.enabled||e.count<=0||(e.isLine()&&(e.path==null||e.path.size()<2)))return;
        int count=WorldSystem.count(e);
        if (count == 0 && !CrowdPresence.hasTrackedActors(e)) return;
        Minecraft mc=Minecraft.getInstance();if(mc.level==null)return;
        context(mc.level);
        net.minecraft.world.phys.Vec3 camera=mc.gameRenderer.getMainCamera().getPosition();
        double minX=Double.POSITIVE_INFINITY,minZ=minX,maxX=Double.NEGATIVE_INFINITY,maxZ=maxX;
        if(DiffuserData.isDiffuser(e)){minX=e.a[0]-e.diffuserRadius;maxX=e.a[0]+e.diffuserRadius;minZ=e.a[2]-e.diffuserRadius;maxZ=e.a[2]+e.diffuserRadius;}
        else if(e.isLine()&&e.path!=null){for(double[] p:e.path)if(p!=null&&p.length>=3){minX=Math.min(minX,p[0]);maxX=Math.max(maxX,p[0]);minZ=Math.min(minZ,p[2]);maxZ=Math.max(maxZ,p[2]);}}
        else if(e.a!=null&&e.b!=null&&e.a.length>=3&&e.b.length>=3){minX=Math.min(e.a[0],e.b[0]);maxX=Math.max(e.a[0],e.b[0]);minZ=Math.min(e.a[2],e.b[2]);maxZ=Math.max(e.a[2],e.b[2]);}
        double distance=e.maxDist>0?Math.min(160,e.maxDist):64;
        double pad=Math.max(2,Math.max(e.width,e.spread))+WorldSystem.playerInfluenceRadius()+2;
        double dx=Math.max(0,Math.max(minX-camera.x,camera.x-maxX)-pad),dz=Math.max(0,Math.max(minZ-camera.z,camera.z-maxZ)-pad);
        if(dx*dx+dz*dz>distance*distance)return;
        Group group=group(e);group.renderTime=time;long tick=world.getGameTime();
        long signature=CrowdFlow.signature(e);CrowdFlow.prepare(e);
        if(group.shapeSignature!=signature){group.targets.clear();group.retiring.clear();group.agents.clear();group.failedUntil.clear();group.sampledCount=-1;group.shapeSignature=signature;}
        group.source=e;
        if (!group.targets.isEmpty()) {
            CrowdSampler.Ghost first = group.targets.getFirst();
            if (first.alpha() != (float)e.alpha || first.color() != e.color) {
                group.targets.clear();
                group.retiring.clear();
                group.sampledCount = -1;
            }
        }
        if (count < group.sampledCount) {
            int end = group.targets.size();
            while (end > 0 && group.targets.get(end - 1).idx() >= count) end--;
            for (int i = end; i < group.targets.size(); i++) {
                CrowdSampler.Ghost candidate = group.targets.get(i);
                if (CrowdPresence.keep(e, candidate.idx())) group.retiring.add(candidate);
            }
            group.targets.subList(end, group.targets.size()).clear();
        } else if (count > group.sampledCount) {
            group.retiring.removeIf(target -> target.idx() < count);
            double radius = Math.max(.18, Math.min(1.2, .30 * e.size / 1.875));
            for (int i = Math.max(0, group.sampledCount); i < count; i++) {
                double[] seed = CrowdFlow.spawn(e, i, radius);
                if (seed == null) continue;
                group.targets.add(new CrowdSampler.Ghost(seed[0], seed[1], seed[2], 0, (float)e.alpha,
                        (float)e.size, e.color, i, 0, 0));
            }
        }
        group.sampledCount = count;
        group.retiring.removeIf(target -> !CrowdPresence.keep(e, target.idx()));
        for(CrowdSampler.Ghost target:group.targets){Agent existing=group.agents.get(target.idx());double x=existing==null?target.x():existing.renderX,z=existing==null?target.z():existing.renderZ;double view=distance+8;if((x-camera.x)*(x-camera.x)+(z-camera.z)*(z-camera.z)<=view*view)out.add(target);}
        out.addAll(group.retiring);
    }
    /** Revalidates support independently of horizontal walking; no stale height survives block removal. */
    private static boolean settle(Agent a,double radius,double height,boolean ground,double time,double dt){
        if(!ground){a.hidden=false;a.falling=false;a.fallSpeed=0;return clear(a.x,a.y,a.z,radius,height);}
        if(flat(a.x,a.y,a.z,a.x,a.z,radius,height,true)){a.hidden=false;a.falling=false;a.fallSpeed=0;return true;}
        if(!loaded(a.x,a.z,radius,a.y)||remainingQueries()<2)return false;
        double velocity=Math.min(12,a.fallSpeed+9.8*dt),drop=Math.min(1.8,Math.max(.04,velocity*dt));
        Iterable<VoxelShape> shapes=query(new AABB(a.x-radius,a.y-drop-.03,a.z-radius,a.x+radius,a.y+height,a.z+radius));
        if(shapes==null)return false;double support=Double.NEGATIVE_INFINITY,escape=a.y;boolean blocked=false;
        for(VoxelShape shape:shapes)for(AABB box:shape.toAabbs()){
            if(box.maxX<=a.x-radius||box.minX>=a.x+radius||box.maxZ<=a.z-radius||box.minZ>=a.z+radius)continue;
            if(box.maxY>a.y+.025&&box.minY<a.y+height-.001){blocked=true;escape=Math.max(escape,box.maxY);}
            if(box.maxY<=a.y+.025&&box.maxY>=a.y-drop-.001)support=Math.max(support,box.maxY);
        }
        if(blocked){
            // A newly placed block may engulf an actor. Only accept a clear nearby top; hide sealed bodies.
            if(escape>a.y&&escape<=a.y+DiffuserData.climb(domain)+.001&&clear(a.x,escape,a.z,radius,height)){
                a.y=a.fy=a.renderY=escape;a.x=a.fx=a.renderX;a.z=a.fz=a.renderZ;a.trackCount=0;a.stepMotion=false;
                a.hidden=false;a.falling=false;a.fallSpeed=0;return true;
            }
            a.hidden=true;a.falling=false;a.fallSpeed=0;return false;
        }
        a.hidden=false;
        if(Double.isFinite(support)&&Math.abs(support-a.y)<.026){a.falling=false;a.fallSpeed=0;return true;}
        double destination=Double.isFinite(support)?support:a.y-drop;
        if(!Double.isFinite(destination)||destination>=a.y-.001)return false;
        // The swept query already found the highest top below the feet and all body overlaps.
        a.scratchCount=0;append(a,a.x,a.y,a.z);append(a,a.x,destination,a.z);
        a.fx=a.x;a.fy=destination;a.fz=a.z;transition(a,time,Math.max(.1,velocity));
        a.transitionDuration=Math.max(.05,Math.min(.15,dt));a.fallSpeed=Double.isFinite(support)?0:velocity;
        a.falling=!Double.isFinite(support);return false;
    }
    private static void freeze(Agent a,double time){
        a.fx=a.x=a.renderX;a.fy=a.y=a.renderY;a.fz=a.z=a.renderZ;a.trackCount=0;a.stepMotion=false;a.lookDuration=0;
        a.facingPlayer=false;a.interacting=false;a.move=0;a.compressX=a.compressZ=0;a.clock=time;a.compressClock=time;a.fallSpeed=0;
    }
    private static CrowdSampler.Ghost pose(Agent a,CrowdSampler.Ghost source){
        return new CrowdSampler.Ghost(a.renderX,a.renderY,a.renderZ,(float)a.renderYaw,source.alpha(),source.size(),source.color(),source.idx(),(float)a.gait,(float)a.move);
    }
    private static void idleLook(Agent a,int index,long tick,double time){
        if(a.nextLook==0)a.nextLook=tick+80+Math.floorMod(index*73,141);
        if(tick<a.nextLook)return;
        long phase=Math.floorDiv(tick,100)+index*31L;double turn=Math.sin(phase*2.3999632297)*.9;
        a.lookFrom=a.renderYaw;a.lookTo=a.renderYaw+turn;a.lookStart=time;a.lookDuration=.75;
        a.nextLook=tick+100+Math.floorMod(index*47+(int)phase,181);
    }
    private static boolean interactionArea(Agent actor, int reach) {
        return interactionArea(actor.renderX, actor.renderZ, reach);
    }

    private static boolean interactionArea(double x, double z, int reach) {
        double rangeSquared = (double)reach * reach;
        for (Player player : world.players()) {
            double dx = x - player.getX(), dz = z - player.getZ();
            if (dx * dx + dz * dz <= rangeSquared) return true;
        }
        return false;
    }

    private static void registerBody(Agent actor) {
        var body = actor.body;
        SPACING.add(body, actor.renderX, actor.renderY, actor.renderZ, body.radius, body.height, actor.fx, actor.fz);
    }

    public static void adjust(CrowdData.Emitter e,double time,List<CrowdSampler.Ghost> ghosts){
        if(!WorldSystem.enabled){ghosts.clear();return;}
        Minecraft mc=Minecraft.getInstance();if(mc.level==null||ghosts.isEmpty())return;
        context(mc.level);currentGroup=e.dim+":"+e.id;
        Group group=group(e);group.renderTime=time;group.activeCount=0;long tick=world.getGameTime();int n=ghosts.size();
        int influence = WorldSystem.playerInfluenceRadius();
        if(group.output.length<n)group.output=new CrowdSampler.Ghost[n];int start=(int)Math.floorMod(tick*73,n);
        // Active actors get initialization/query budget first; passive placement is capped at two per tick.
        for(int pass=0;pass<2;pass++)for(int ordinal=0;ordinal<n;ordinal++){
            int read=(start+ordinal)%n;CrowdSampler.Ghost g=ghosts.get(read);Agent known=group.agents.get(g.idx());
            boolean active=WorldSystem.active(known==null?g.x():known.renderX,known==null?g.y():known.renderY,known==null?g.z():known.renderZ);
            if(active!=(pass==0))continue;
            group.output[read]=null;Agent a=agent(group,g,e.ground,active);if(a==null)continue;a.seen=tick;
            if(!a.finalSet){a.fx=a.x;a.fy=a.y;a.fz=a.z;a.finalSet=true;display(a,time);}
            a.standing=WorldSystem.standing(e,g.idx());
            if(!active){
                if(a.active||a.frozen==null){display(a,time);freeze(a,time);a.frozen=pose(a,g);}a.active=false;a.tick=tick;
                if(!a.hidden)group.output[read]=a.frozen;continue;
            }
            if(!a.active){
                freeze(a,time);a.tick=Long.MIN_VALUE;a.attemptTick=Long.MIN_VALUE;a.frozen=null;
                if (influence > 0) registerBody(a);
            }
            a.active=true;
            display(a,time);boolean finishing=a.stepMotion&&time<a.transitionStart+a.transitionDuration;
            double r=radius(g),h=height(g);int period=a.standing&&!a.falling?10:2;
            if(a.standing&&influence>0&&mc.player!=null&&Math.abs(a.renderY-mc.player.getY())<3&&Math.hypot(a.renderX-mc.player.getX(),a.renderZ-mc.player.getZ())<influence)period=2;
            long age = a.tick == Long.MIN_VALUE ? period + 1L : tick - a.tick;
            // A slow frame can skip the actor's preferred tick. Let overdue work use any tick.
            boolean ready = age > period || age == period && Math.floorMod(tick + g.idx(), period) == 0;
            if (!finishing && a.attemptTick != tick && ready) {
                a.attemptTick=tick;
                if (remainingQueries() > 0 || flat(a.renderX,a.renderY,a.renderZ,a.renderX,a.renderZ,r,h,false)) {
                    double elapsed = a.tick == Long.MIN_VALUE ? period / 20.0 : (tick - a.tick) / 20.0;
                    double dt = Math.clamp(elapsed, .05, .15);
                    a.x=a.renderX;a.y=a.renderY;a.z=a.renderZ;
                    if(settle(a,r,h,e.ground,time,dt)){
                        Player nearest = nearestPlayer(mc, a, influence);
                        a.interacting = nearest != null || influence > 0 && interactionArea(a, influence + 2);
                        double overlap = 0;
                        if (a.interacting) {
                            a.compressX = 0;
                            a.compressZ = 0;
                            SPACING.nearby(a.body, a.x, a.y, a.z, NEIGHBORS);
                            overlap = CrowdSpacing.pressure(a.body, NEIGHBORS, a.x, a.z, PRESSURE);
                        }
                        a.facingPlayer = nearest != null && WorldSystem.playerRadius < 0;
                        if (a.facingPlayer) {
                            a.playerX = nearest.getX();
                            a.playerZ = nearest.getZ();
                            a.lookDuration = 0;
                        }
                        if (overlap < .003 || !recoverSpacing(e, a, r, h, time, dt, overlap)) {
                            if (a.facingPlayer) {
                                approachPlayer(e, a, g.idx(), r, h, time, dt);
                            } else {
                                moveWithFlow(e, a, g.idx(), nearest, influence, r, h, tick, time, dt);
                            }
                        }
                        if (a.transitionStart == time && !a.stepMotion) {
                            a.transitionDuration = Math.max(a.transitionDuration, Math.min(.25, elapsed));
                        }
                        SPACING.reserve(a.body, a.fx, a.fz);
                        a.x=a.fx;a.y=a.fy;a.z=a.fz;
                    }
                    if (remainingQueries() > 0 || a.transitionStart == time) a.tick=tick;
                }
            }
            if(!a.hidden){group.output[read]=pose(a,g);group.activeCount++;}
        }
        int write=0;for(int i=0;i<n;i++){if(group.output[i]!=null)ghosts.set(write++,group.output[i]);group.output[i]=null;}ghosts.subList(write,n).clear();
        if(tick%100==0){int count=WorldSystem.count(e);group.agents.keySet().removeIf(i->i>=count&&!CrowdPresence.keep(e,i));}
    }
    private static Player nearestPlayer(Minecraft mc, Agent actor, int range) {
        if (range == 0) return null;
        Player nearest = null;
        double nearestSquared = (double) range * range;
        for (Player player : mc.level.players()) {
            if (Math.abs(actor.y - player.getY()) >= 3) continue;
            double dx = actor.x - player.getX(), dz = actor.z - player.getZ();
            double squared = dx * dx + dz * dz;
            if (squared < nearestSquared || nearest == null && squared <= nearestSquared + 1e-8) {
                nearest = player;
                nearestSquared = squared;
            }
        }
        return nearest;
    }

    private static boolean recoverSpacing(CrowdData.Emitter emitter, Agent actor,
                                          double radius, double height, double time, double dt, double overlap) {
        double heading = Math.atan2(PRESSURE[0], PRESSURE[1]);
        double step = Math.min(.20, 1.4 * dt);
        for (int pass = 0; pass < 2; pass++, step *= .45) {
            for (int direction = 0; direction < 8; direction++) {
                double angle = heading + direction * Math.PI / 4 + pass * Math.PI / 8;
                double dx = Math.sin(angle) * step, dz = Math.cos(angle) * step;
                if (actor.facingPlayer && !PlayerApproach.allowsStep(actor.x - actor.playerX, actor.z - actor.playerZ, dx, dz)) continue;
                if (!CrowdSpacing.permits(actor.body, NEIGHBORS, actor.x, actor.z, dx, dz, overlap)) continue;
                double x = actor.x + dx, z = actor.z + dz;
                double y = trace(actor.x, actor.y, actor.z, x, z, radius, height, emitter.ground, actor);
                if (Double.isFinite(y)) {
                    actor.fx = x;
                    actor.fy = y;
                    actor.fz = z;
                    transition(actor, time, 1.4);
                    actor.toYaw = actor.fromYaw;
                    return true;
                }
                if (remainingQueries() == 0) return false;
            }
        }
        return false;
    }

    private static void approachPlayer(CrowdData.Emitter emitter, Agent actor, int index,
                                       double radius, double height, double time, double dt) {
        actor.trackCount = 0;
        actor.stepMotion = false;
        actor.fx = actor.x;
        actor.fy = actor.y;
        actor.fz = actor.z;
        double offsetX = actor.x - actor.playerX, offsetZ = actor.z - actor.playerZ;
        double distance = Math.hypot(offsetX, offsetZ);
        double speed = Math.clamp(emitter.speed, .8, 5);
        double step = PlayerApproach.displacement(distance, speed * dt);
        double away = distance < .001 ? index * 2.3999632297 : Math.atan2(offsetX, offsetZ);
        double dxWanted = Math.sin(away) * step + PRESSURE[0];
        double dzWanted = Math.cos(away) * step + PRESSURE[1];
        double length = Math.min(speed * dt, Math.hypot(dxWanted, dzWanted));
        if (length < .001) return;
        double heading = Math.atan2(dxWanted, dzWanted);
        // Crowd compression may move tangentially or outward even when the radial target is reached.
        for (double turn : AVOID_TURNS) {
            double dx = Math.sin(heading + turn) * length, dz = Math.cos(heading + turn) * length;
            if (!PlayerApproach.allowsStep(offsetX, offsetZ, dx, dz)
                    || !CrowdSpacing.permits(actor.body, NEIGHBORS, actor.x, actor.z, dx, dz)) continue;
            double x = actor.x + dx, z = actor.z + dz;
            double y = trace(actor.x, actor.y, actor.z, x, z, radius, height, emitter.ground, actor);
            if (Double.isFinite(y)) {
                actor.fx = x;
                actor.fy = y;
                actor.fz = z;
                transition(actor, time, speed);
                actor.finalTick = actor.tick;
                return;
            }
            if (!WorldSystem.avoidBlocks || remainingQueries() == 0) break;
        }
    }

    private static void moveWithFlow(CrowdData.Emitter e, Agent a, int index, Player nearest, int influence,
                                     double r, double h, long tick, double time, double dt) {
        double y = a.y;
        double[] velocity = a.standing ? new double[]{0, 0} : CrowdFlow.velocity(e, a.flow, a.x, a.z, r);
        if (nearest != null) {
            double dx = a.x - nearest.getX(), dz = a.z - nearest.getZ();
            double distance = Math.hypot(dx, dz), proximity = distance;
            if (distance < .001) {
                double angle = index * 2.3999632297;
                dx = Math.sin(angle);
                dz = Math.cos(angle);
                distance = 1;
            }
            double strength = Math.max(.2, e.speed) * 2.5 * Math.pow(Math.max(0, 1 - proximity / influence), 1.2);
            velocity[0] += dx / distance * strength;
            velocity[1] += dz / distance * strength;
        }
        if (a.interacting) {
            velocity[0] += PRESSURE[0] / dt;
            velocity[1] += PRESSURE[1] / dt;
        }
        double speed = Math.min(5, Math.hypot(velocity[0], velocity[1]));
        if (speed <= .01) {
            if (a.standing) {
                a.trackCount = 0;
                a.stepMotion = false;
                idleLook(a, index, tick, time);
            }
            return;
        }
        a.lookDuration = 0;
        double desired = Math.atan2(velocity[0], velocity[1]), maxTurn = 3.5 * dt;
        double previous = Double.NaN;
        boolean blockedByCrowd = false;
        for (double turn : WorldSystem.avoidBlocks ? AVOID_TURNS : DIRECT_TURN) {
            double heading = CrowdFlow.rotate(a.flow.heading, desired + turn, maxTurn);
            if (Double.isFinite(previous) && Math.abs(heading - previous) < .01) continue;
            previous = heading;
            double dx = Math.sin(heading) * speed * dt, dz = Math.cos(heading) * speed * dt;
            if (a.interacting && !CrowdSpacing.permits(a.body, NEIGHBORS, a.x, a.z, dx, dz)) {
                blockedByCrowd = true;
                continue;
            }
            double nx = a.x + dx, nz = a.z + dz;
            double ny = trace(a.x, y, a.z, nx, nz, r, h, e.ground, a);
            if (Double.isFinite(ny)) {
                double distance = Math.hypot(nx - a.x, nz - a.z);
                a.fx = nx;
                a.fy = ny;
                a.fz = nz;
                a.flow.heading = heading;
                transition(a, time, speed);
                a.finalTick = a.tick;
                CrowdFlow.moved(e, a.flow, nx, nz, distance);
                return;
            }
            if (remainingQueries() == 0 && !flat(a.x, y, a.z, nx, nz, r, h, false)) break;
        }
        if (blockedByCrowd) return;
        if (tick >= a.sideUntil) {
            a.turnSide = (index & 1) == 0 ? 1 : -1;
            a.sideUntil = tick + 20;
        }
        double forward = CrowdFlow.rotate(a.flow.heading, desired, maxTurn);
        double fx = a.x + Math.sin(forward) * speed * dt, fz = a.z + Math.cos(forward) * speed * dt;
        boolean boundary = !CrowdBounds.segment(e, a.x, a.z, fx, fz, r);
        if (boundary || remainingQueries() > 0) {
            a.flow.heading = CrowdFlow.rotate(a.flow.heading, boundary ? desired : desired + a.turnSide * Math.PI / 2, maxTurn);
            if (!a.standing) CrowdFlow.moved(e, a.flow, a.x, a.z, 0);
        }
    }

    private static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
    /** Last-stage sweep prevents player/separation offsets from pushing a ghost through a wall. */
    private static void display(Agent a,double time){
        if(!a.displayed){a.renderX=a.fx;a.renderY=a.fy;a.renderZ=a.fz;a.renderYaw=a.yaw;a.displayed=true;}
        double previousX=a.renderX,previousZ=a.renderZ;
        if(a.trackCount>0){
            double t=Math.max(0,Math.min(1,(time-a.transitionStart)/a.transitionDuration));
            double distance=a.lengths[a.trackCount-1]*t;int segment=1;
            while(segment<a.trackCount-1&&a.lengths[segment]<distance)segment++;
            int from=(segment-1)*3,to=segment*3;double span=a.lengths[segment]-a.lengths[segment-1];
            double u=span<1e-9?1:(distance-a.lengths[segment-1])/span;u=Math.max(0,Math.min(1,u));
            a.renderX=a.track[from]+(a.track[to]-a.track[from])*u;
            a.renderY=a.track[from+1]+(a.track[to+1]-a.track[from+1])*u;
            a.renderZ=a.track[from+2]+(a.track[to+2]-a.track[from+2])*u;
            if(!a.facingPlayer)a.renderYaw=a.fromYaw+(a.toYaw-a.fromYaw)*t;
        }
        if(!a.facingPlayer&&a.lookDuration>0){double t=Math.max(0,Math.min(1,(time-a.lookStart)/a.lookDuration));double eased=t*t*(3-2*t);a.renderYaw=a.lookFrom+(a.lookTo-a.lookFrom)*eased;}
        double moved=Math.hypot(a.renderX-previousX,a.renderZ-previousZ);
        double dt=Double.isNaN(a.clock)?0:Math.max(0,Math.min(.1,time-a.clock));
        if (a.facingPlayer && dt > 0) {
            double dx = a.playerX - a.renderX, dz = a.playerZ - a.renderZ;
            if (dx * dx + dz * dz > 1e-6) a.renderYaw = CrowdFlow.rotate(a.renderYaw, Math.atan2(dx, dz), 10 * dt);
        }
        double lastGait = a.gait;
        a.gait+=moved*Math.PI*2/1.25;
        if (WorldSystem.footsteps && a.active && !a.hidden && !a.falling
                && (long)(a.gait / Math.PI) > (long)(lastGait / Math.PI)) {
            CrowdFootsteps.play(world, a.renderX, a.renderY, a.renderZ);
        }
        if(dt>1e-6){double target=Math.min(1,moved/dt);a.move+=(target-a.move)*(1-Math.exp(-dt*15));}
        a.clock=time;
    }
    private static void transition(Agent a,double time,double speed){
        a.trackCount=a.scratchCount;System.arraycopy(a.scratch,0,a.track,0,a.trackCount*3);
        a.lengths[0]=0;a.stepMotion=false;double horizontal=0;
        for(int i=1;i<a.trackCount;i++){
            int k=i*3;double dx=a.track[k]-a.track[k-3],dy=a.track[k+1]-a.track[k-2],dz=a.track[k+2]-a.track[k-1];
            horizontal+=Math.hypot(dx,dz);a.lengths[i]=a.lengths[i-1]+Math.sqrt(dx*dx+dy*dy+dz*dz);a.stepMotion|=Math.abs(dy)>.001;
        }
        a.transitionStart=time;a.transitionDuration=a.stepMotion?.25:Math.max(.06,Math.min(.35,horizontal/Math.max(.1,speed)));
        a.fromYaw=a.renderYaw;double yaw=horizontal>.005?Math.atan2(a.track[(a.trackCount-1)*3]-a.track[0],a.track[(a.trackCount-1)*3+2]-a.track[2]):a.renderYaw;
        a.toYaw=CrowdFlow.rotate(a.fromYaw,yaw,3.5*a.transitionDuration);
    }
    public static double[] resolve(Minecraft mc,CrowdData.Emitter e,CrowdSampler.Ghost ghost,double x,double y,double z){
        if(!WorldSystem.enabled||mc.level==null)return null;
        context(mc.level);Group group=group(e);Agent a=group.agents.get(ghost.idx());if(a==null||a.hidden)return null;
        if(!a.active||a.standing||a.interacting){a.output[0]=a.renderX;a.output[1]=a.renderY;a.output[2]=a.renderZ;return a.output;}
        double time=group.renderTime,r=radius(ghost),h=height(ghost);
        if(!a.finalSet){a.fx=a.x;a.fy=a.y;a.fz=a.z;a.finalSet=true;}
        display(a,time);
        // Compression affects only a small display offset; it never changes the flow target or speed.
        double dt=Double.isNaN(a.compressClock)?0:Math.max(0,Math.min(.1,time-a.compressClock));a.compressClock=time;
        double dx=x-ghost.x(),dz=z-ghost.z(),length=Math.hypot(dx,dz);if(length>.06){dx*=.06/length;dz*=.06/length;}
        double blend=1-Math.exp(-dt*12);a.compressX+=(dx-a.compressX)*blend;a.compressZ+=(dz-a.compressZ)*blend;
        double px=a.renderX+a.compressX,pz=a.renderZ+a.compressZ;
        boolean safe=CrowdBounds.segment(e,a.renderX,a.renderZ,px,pz,r)&&(e.ground?flat(a.renderX,a.renderY,a.renderZ,px,pz,r,h,false):false);
        if(!safe&&Math.hypot(a.compressX,a.compressZ)>.002&&remainingQueries()>=3&&CrowdBounds.segment(e,a.renderX,a.renderZ,px,pz,r)){
            double floor=floor(px,a.renderY,pz,r,h,e.ground);safe=Double.isFinite(floor)&&Math.abs(floor-a.renderY)<1e-6&&corridor(a.renderX,a.renderY,a.renderZ,px,pz,r,h);
        }
        if(!safe){px=a.renderX;pz=a.renderZ;}
        if(!CrowdBounds.inside(e,px,pz,r))return null;
        a.output[0]=px;a.output[1]=a.renderY;a.output[2]=pz;return a.output;
    }
    private static long tickOfWorld(){return world.getGameTime();}
    public static CrowdSampler.Ghost animated(CrowdData.Emitter e,CrowdSampler.Ghost original){
        Agent a=group(e).agents.get(original.idx());if(a==null)return original;
        if(!a.active&&a.frozen!=null)return a.frozen;
        return new CrowdSampler.Ghost(a.output[0],a.output[1],a.output[2],(float)a.renderYaw,original.alpha(),original.size(),original.color(),original.idx(),(float)a.gait,(float)a.move);
    }
    private static final Map<String, Separation> SEPARATION=new HashMap<>();
    private static String currentGroup="";
    private static final class Separation{long tick=Long.MIN_VALUE;double spacing;double[] x,z;}
    /** Spatial buckets replace the original quadratic all-pairs separation. */
    public static void separate(List<CrowdSampler.Ghost> list,double spacing,double[] outX,double[] outZ){
        Arrays.fill(outX,0);Arrays.fill(outZ,0);if(!WorldSystem.enabled)return;
        boolean any=false;for(CrowdSampler.Ghost g:list)if(WorldSystem.active(g.x(),g.y(),g.z())&&(domain==null||!WorldSystem.standing(domain,g.idx()))){any=true;break;}if(!any)return;
        Separation cached=SEPARATION.computeIfAbsent(currentGroup,k->new Separation());
        if(cached.x!=null&&cached.x.length==list.size()&&cached.tick==budgetTick&&cached.spacing==spacing){System.arraycopy(cached.x,0,outX,0,list.size());System.arraycopy(cached.z,0,outZ,0,list.size());return;}
        double cell=Math.max(.25,Math.min(.65,spacing*.6)),range=cell*cell;
        Map<Long,List<Integer>> grid=new HashMap<>();
        for(int i=0;i<list.size();i++){CrowdSampler.Ghost g=list.get(i);if(!WorldSystem.active(g.x(),g.y(),g.z())||(domain!=null&&WorldSystem.standing(domain,g.idx())))continue;grid.computeIfAbsent(key((int)Math.floor(g.x()/cell),(int)Math.floor(g.z()/cell)),k->new ArrayList<>()).add(i);}
        for(int i=0;i<list.size();i++){
            CrowdSampler.Ghost g=list.get(i);if(!WorldSystem.active(g.x(),g.y(),g.z())||(domain!=null&&WorldSystem.standing(domain,g.idx())))continue;int gx=(int)Math.floor(g.x()/cell),gz=(int)Math.floor(g.z()/cell),checked=0;double x=0,z=0;
            outer:for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
                List<Integer> bucket=grid.get(key(gx+dx,gz+dz));if(bucket==null)continue;
                for(int j:bucket){if(j==i)continue;if(++checked>32)break outer;
                    CrowdSampler.Ghost b=list.get(j);double vx=g.x()-b.x(),vz=g.z()-b.z(),sq=vx*vx+vz*vz;
                    if(sq>=range)continue;if(sq<1e-8){double angle=((long)Math.min(i,j)*73856093L+(long)Math.max(i,j)*19349663L)%6283/1000.0;vx=Math.cos(angle)*(i<j?.001:-.001);vz=Math.sin(angle)*(i<j?.001:-.001);sq=vx*vx+vz*vz;}double d=Math.sqrt(sq),weight=(cell-d)/cell;x+=vx/d*weight;z+=vz/d*weight;
                }
            }
            double length=Math.hypot(x,z),scale=length>1e-8?Math.min(.05,length*.12)/length:0;
            outX[i]=x*scale;outZ[i]=z*scale;
        }
        if(cached.x==null||cached.x.length!=list.size()){cached.x=new double[list.size()];cached.z=new double[list.size()];}
        System.arraycopy(outX,0,cached.x,0,list.size());System.arraycopy(outZ,0,cached.z,0,list.size());cached.tick=budgetTick;cached.spacing=spacing;
    }
}
