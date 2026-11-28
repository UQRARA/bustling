package dev.bustling;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
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
    private static final int TARGETS_PER_TICK = 2048;
    private static int targetsLeft;
    private static final double[] SWEEP = new double[192];
    private static final Map<Long, String> GROUND_IDS = new HashMap<>();
    private static final List<CrowdSampler.Ghost> TICK_TARGETS = new ArrayList<>();
    private static double frameTime;
    private static final CrowdSpacing SPACING = new CrowdSpacing();
    private static final List<CrowdSpacing.Body> NEIGHBORS = new ArrayList<>();
    private static final double[] PRESSURE = new double[2];
    private static final double[] YIELD = new double[2];
    private static final double[] DIRECT_TURN = {0};
    private static final double[] AVOID_TURNS = {0, .45, -.45, .9, -.9, Math.PI / 2, -Math.PI / 2};
    private static final double[] BOUNDARY_TURNS = {0, .38, .9, Math.PI / 2};
    private record TileKey(int x,int z,long y,long r,long h){}
    private static final Map<TileKey,Boolean> FLAT=new HashMap<>();
    private record ColumnKey(int x, int z, int feet, int top) {}
    private static final Map<ColumnKey, Boolean> OPEN_COLUMNS = new HashMap<>();
    private static final class Group {
        final Map<Integer, Agent> agents = new HashMap<>();
        final Map<Integer, Long> failedUntil = new HashMap<>();
        final List<CrowdSampler.Ghost> targets = new ArrayList<>();
        final List<CrowdSampler.Ghost> retiring = new ArrayList<>();
        final BitSet delayedArrivals = new BitSet();
        long allocationRevision = Long.MIN_VALUE;
        int sampledCount = -1, seededCount;
        long plannedTick = Long.MIN_VALUE, geometryRevision = Long.MIN_VALUE;
        CrowdData.Emitter geometrySource;
        List<String> groundFilter;
        int groundMode;
        final List<CrowdSampler.Ghost> planned = new ArrayList<>();
        double minX, minZ, maxX, maxZ;
        CrowdData.Emitter source; long shapeSignature=Long.MIN_VALUE;
        long seen, queryTick=Long.MIN_VALUE;int queryCount,initializations,activeCount;
        CrowdSampler.Ghost[] output=new CrowdSampler.Ghost[0];
        byte[] priority = new byte[0];
    }
    private static final class Agent {
        double x,y,z,fx,fy,fz,yaw;
        long tick=Long.MIN_VALUE, seen, indexedTick = Long.MIN_VALUE;
        CrowdSpacing.Body body;
        boolean finalSet;
        long nextLook;
        final double[] output=new double[3];
        double[] track, lengths;
        int trackCount,scratchCount;
        double renderX,renderY,renderZ,renderYaw,gait,move,clock=Double.NaN,transitionStart,transitionDuration,fromYaw,toYaw;
        boolean displayed,stepMotion,active,standing,falling,hidden,floating;
        boolean facingPlayer, escapeRunning;
        int retreatPlayer = -1;
        CrowdPace.Style interactionStyle;
        double runEffort, runBlend, footstepPhase;
        double playerX, playerZ, pursuit, escapeReaction, escapeEffort;
        double fallSpeed,lookFrom,lookTo,lookStart,lookDuration;CrowdSampler.Ghost frozen;
        CrowdFlow.State flow;int turnSide=1;long sideUntil;
        int crowdWait;

    }
    private CrowdNavigation(){}
    /** Releases local simulation state when the world system is switched off. */
    public static void clear(){GROUPS.clear();FLAT.clear();OPEN_COLUMNS.clear();GROUND_IDS.clear();SPACING.clear();NEIGHBORS.clear();CrowdFlow.clear();CrowdWater.clear();geometryGroup=null;domain=null;world=null;budgetTick=Long.MIN_VALUE;TICK_TARGETS.clear();frameTime=0;}
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
        if(world!=level){GROUPS.clear();FLAT.clear();OPEN_COLUMNS.clear();GROUND_IDS.clear();SPACING.clear();NEIGHBORS.clear();geometryGroup=null;world=level;budgetTick=Long.MIN_VALUE;}
        long tick=level.getGameTime();
        if(tick!=budgetTick){
            budgetTick=tick;
            queryBudget=MAX_QUERIES_PER_TICK * WorldSystem.navigationBudget;
            initBudget=32 * WorldSystem.navigationBudget;
            queriesLeft=queryBudget;initLeft=initBudget;passiveInitLeft=2;targetsLeft=TARGETS_PER_TICK;FLAT.clear();OPEN_COLUMNS.clear();GROUND_IDS.clear();
            SPACING.clear();
            NEIGHBORS.clear();
            for (Group group : GROUPS.values()) {
                if (tick - group.seen > 2 || group.source == null || !group.source.enabled) continue;
                for (Agent actor : group.agents.values()) {
                    if (actor.hidden || tick - actor.seen > 2) continue;
                    if (actor.active) display(actor, tick / 20.0);
                    registerBody(actor);
                }
            }
            sharingGroups=0;for(Group g:GROUPS.values())if(tick-g.seen<=4)sharingGroups++;sharingGroups=Math.max(1,sharingGroups);
            if(tick%100==0){GROUPS.values().removeIf(g->tick-g.seen>200);for(Group g:GROUPS.values())g.agents.values().removeIf(a->tick-a.seen>200);}
        }
    }
    private static String groupKey(CrowdData.Emitter emitter) {
        return emitter.dim + ":" + emitter.id;
    }

    private static Group group(CrowdData.Emitter emitter) {
        return GROUPS.computeIfAbsent(groupKey(emitter), ignored -> new Group());
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
    private static boolean openColumn(int x, int z, int feet, int top) {
        ColumnKey key = new ColumnKey(x, z, feet, top);
        Boolean cached = OPEN_COLUMNS.get(key);
        if (cached != null) return cached;
        var pos = new BlockPos.MutableBlockPos(x, feet - 1, z);
        if (!world.hasChunkAt(pos)) {
            OPEN_COLUMNS.put(key, false);
            return false;
        }
        var support = world.getBlockState(pos);
        boolean safe = !support.getBlock().hasDynamicShape() && support.isCollisionShapeFullBlock(world, pos);
        for (int y = feet; safe && y <= top; y++) {
            var state = world.getBlockState(pos.setY(y));
            safe = state.is(Blocks.AIR) || state.is(Blocks.CAVE_AIR) || state.is(Blocks.VOID_AIR);
        }
        OPEN_COLUMNS.put(key, safe);
        return safe;
    }

    private static boolean openTile(int x, int z, double y, double radius, double height) {
        int feet = (int)Math.floor(y);
        if (y != feet) return false;
        // Include the same adjacent cells a vanilla collision query can consult for oversized shapes.
        int margin = (int)Math.ceil(radius) + 1;
        int top = (int)Math.floor(y + height) + 1;
        for (int ix = x - margin; ix <= x + margin; ix++) {
            for (int iz = z - margin; iz <= z + margin; iz++) {
                if (!openColumn(ix, iz, feet, top)) return false;
            }
        }
        return true;
    }

    private static boolean tile(int x,int z,double y,double r,double h,boolean create){
        TileKey key=new TileKey(x,z,Double.doubleToLongBits(y),Double.doubleToLongBits(r),Double.doubleToLongBits(h));
        Boolean known=FLAT.get(key);if(known!=null)return known;
        if (!create) return false;
        if (openTile(x, z, y, r, h)) {
            FLAT.put(key, true);
            return true;
        }
        if(remainingQueries()<2||!loaded(x+.5,z+.5,r+.5,y))return false;
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
    private static double landFloor(double x,double y,double z,double r,double h,boolean ground,double climb){
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
    private static boolean hasGroundFilter(CrowdData.Emitter emitter) {
        return emitter != null && emitter.groundBlocks != null && !emitter.groundBlocks.isEmpty();
    }

    private static boolean groundAllowed(CrowdData.Emitter emitter, double x, double y, double z) {
        if (!hasGroundFilter(emitter)) return true;
        BlockPos pos = BlockPos.containing(x, y - .2, z);
        if (!world.hasChunkAt(pos)) return false;
        String block = GROUND_IDS.computeIfAbsent(pos.asLong(), key ->
                BuiltInRegistries.BLOCK.getKey(world.getBlockState(pos).getBlock()).toString());
        return emitter.groundBlocks.contains(block) != (emitter.groundMode == 1);
    }

    private static double spawnHeight(double x, double y, double z, double radius, double height,
                                      boolean ground, boolean active) {
        double surface = CrowdWater.surface(world, x, y, z);
        if (!Double.isFinite(surface)) {
            if (!CrowdBounds.inside(domain, x, z, radius)) return Double.NaN;
            double floor = ground && flat(x, y, z, x, z, radius, height, true)
                    ? y : landFloor(x, y, z, radius, height, ground, 1);
            return Double.isFinite(floor) && groundAllowed(domain, x, floor, z) ? floor : Double.NaN;
        }
        if (!CrowdBounds.inside(domain, x, z, radius)) return Double.NaN;
        double feet = CrowdWater.floatingFeet(world, x, z, surface, height);
        if (!groundAllowed(domain, x, feet, z)) return Double.NaN;
        double startingY = active && !hasGroundFilter(domain) ? Math.min(y, feet) : feet;
        if (clear(x, startingY, z, radius, height)) return startingY;
        return clear(x, feet, z, radius, height) ? feet : Double.NaN;
    }
    private static void append(Agent a,double x,double y,double z){if(a==null)return;int i=a.scratchCount++*3;SWEEP[i]=x;SWEEP[i+1]=y;SWEEP[i+2]=z;}
    private static boolean vertical(double x,double y,double z,double ny,double r,double h){
        Iterable<VoxelShape> shapes=query(new AABB(x-r,Math.min(y,ny)+.025,z-r,x+r,Math.max(y,ny)+h,z+r));
        return shapes!=null&&!shapes.iterator().hasNext();
    }
    /** Produces a collision-checked continuous trajectory, including vertical step phases. */
    private static double trace(double x,double y,double z,double tx,double tz,double r,double h,boolean ground,Agent capture){
        if(capture!=null)capture.scratchCount=0;
        int steps=Math.max(1,(int)Math.ceil(Math.hypot(tx-x,tz-z)/.10));if(steps>24)return Double.NaN;
        double ox=x,oz=z;append(capture,x,y,z);
        double water = CrowdWater.surface(world, x, y, z);
        if(ground && !hasGroundFilter(domain) && !Double.isFinite(water) && !Double.isFinite(CrowdWater.surface(world,tx,y,tz))
                && (domain==null||CrowdBounds.segment(domain,x,z,tx,tz,r)) && flat(x,y,z,tx,tz,r,h,true)){
            append(capture,tx,y,tz);return y;
        }
        for(int i=1;i<=steps;i++){
            double nx=ox+(tx-ox)*i/steps,nz=oz+(tz-oz)*i/steps;
            if(domain!=null&&!CrowdBounds.segment(domain,x,z,nx,nz,r))return Double.NaN;
            double nextWater = CrowdWater.surface(world, nx, y, nz);
            double ny;
            if (Double.isFinite(nextWater)) {
                ny = CrowdWater.floatingFeet(world, nx, nz, nextWater, h);
                if (!clear(nx, ny, nz, r, h)) return Double.NaN;
            } else {
                double reference = Double.isFinite(water) ? Math.max(y, water) : y;
                ny = landFloor(nx, reference, nz, r, h, ground, DiffuserData.climb(domain));
                if (!Double.isFinite(ny)) return Double.NaN;
            }
            if (!groundAllowed(domain, nx, ny, nz)) return Double.NaN;
            if(Math.abs(ny-y)>1e-6){
                if(!vertical(ny>y?x:nx,y,ny>y?z:nz,ny,r,h)||!corridor(x,Math.max(y,ny),z,nx,nz,r,h))return Double.NaN;
                if(ny>y)append(capture,x,ny,z);else append(capture,nx,y,nz);
            }
            append(capture,nx,ny,nz);x=nx;y=ny;z=nz;water=nextWater;
        }return y;
    }
    private static double radius(CrowdSampler.Ghost g){return Math.max(.18,Math.min(1.2,.30*g.size()/1.875));}
    private static double height(CrowdSampler.Ghost g){return Math.max(.4,Math.min(6,g.size()));}
    private static Agent agent(Group group,CrowdSampler.Ghost ghost,boolean ground,boolean active){
        Agent a=group.agents.get(ghost.idx());
        if(a!=null)return a;
        boolean delayed = group.delayedArrivals.get(ghost.idx());
        long now=world.getGameTime();
        remainingQueries(); // Initialize the group's per-tick counters before checking the spawn quota.
        if(group.failedUntil.getOrDefault(ghost.idx(),Long.MIN_VALUE)>now || initLeft<=0 || (!active&&passiveInitLeft<=0) || group.initializations>=Math.max(1,initBudget/sharingGroups))return null;
        if (delayed && !CrowdPresence.outsideSpawnView(ghost.x(), ghost.y(), ghost.z(), ghost.size())) return null;
        initLeft--;if(!active)passiveInitLeft--;group.initializations++;
        double r=radius(ghost),h=height(ghost);
        double[] projected=CrowdBounds.project(domain,ghost.x(),ghost.z(),r);
        if(projected==null){group.failedUntil.put(ghost.idx(),now+40);return null;}
        double x=projected[0],z=projected[1],y=spawnHeight(x,ghost.y(),z,r,h,ground,active);
        var body = new CrowdSpacing.Body(((long)domain.id.hashCode() << 32) ^ ghost.idx());
        body.radius = r;
        body.height = h;
        if (Double.isFinite(y) && (!outsideAttractionRing(x, y, z) || !SPACING.canPlace(body, x, y, z, NEIGHBORS))) y = Double.NaN;
        if (!Double.isFinite(y)) {
            // Give new actors a free slot without relaxing their area's swept body checks.
            int rings = active ? 5 : 2;
            double increment = Math.max(.35, r);
            double phase = ghost.idx() * 2.3999632297;
            boolean viewLimited = false;
            outer: for (int ring = 1; ring <= rings; ring++) {
                for (int j = 0; j < 8; j++) {
                    double angle = phase + j * Math.PI / 4;
                    double xx = x + ring * increment * Math.cos(angle);
                    double zz = z + ring * increment * Math.sin(angle);
                    if (!outsideAttractionRing(xx, ghost.y(), zz) || !SPACING.canPlace(body, xx, ghost.y(), zz, NEIGHBORS)) continue;
                    double yy = spawnHeight(xx, ghost.y(), zz, r, h, ground, active);
                    if (Double.isFinite(yy) && outsideAttractionRing(xx, yy, zz) && SPACING.canPlace(body, xx, yy, zz, NEIGHBORS)) {
                        if (delayed && !CrowdPresence.outsideSpawnView(xx, yy, zz, ghost.size())) {
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
            group.failedUntil.put(ghost.idx(),now+(remainingQueries()==0?2:active?8:40)+(ghost.idx()&7));
            return null;
        }
        if (delayed && !CrowdPresence.outsideSpawnView(x, y, z, ghost.size())) return null;
        group.delayedArrivals.clear(ghost.idx());
        group.failedUntil.remove(ghost.idx());
        a=new Agent();
        a.body = body;
        a.interactionStyle = CrowdPace.style(ghost.idx());
        a.floating = Double.isFinite(CrowdWater.surface(world, x, y, z));
        a.x=x;a.y=y;a.z=z;a.yaw=ghost.yaw();a.flow=CrowdFlow.create(domain,ghost.idx());CrowdFlow.attach(domain,a.flow,x,z);a.yaw=a.flow.heading;a.turnSide=(ghost.idx()&1)==0?1:-1;group.agents.put(ghost.idx(),a);return a;
    }
    /** Plans movement once per client game tick. The renderer only interpolates these trajectories. */
    static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!WorldSystem.enabled || mc.level == null || mc.player == null) return;
        context(mc.level);
        long tick = mc.level.getGameTime();
        double time = tick / 20.0;
        Vec3 camera = mc.gameRenderer.getMainCamera().isInitialized()
                ? mc.gameRenderer.getMainCamera().getPosition() : mc.player.getEyePosition();
        String dimension = mc.level.dimension().location().toString();
        int groups = CrowdData.EMITTERS.size();
        int first = groups == 0 ? 0 : Math.floorMod(tick, groups);
        for (int i = 0; i < groups; i++) {
            CrowdData.Emitter emitter = CrowdData.EMITTERS.get((first + i) % groups);
            if (emitter == null || !emitter.enabled || emitter.count <= 0
                    || (emitter.isLine() && (emitter.path == null || emitter.path.size() < 2))
                    || emitter.dim != null && !emitter.dim.isBlank() && !dimension.equals(emitter.dim)) continue;
            Group group = group(emitter);
            if (group.plannedTick == tick) continue;
            group.plannedTick = tick;
            group.planned.clear();
            prepareGeometry(group, emitter);
            if (!inRange(group, emitter, camera)) continue;
            group.seen = tick;
            TICK_TARGETS.clear();
            collect(emitter, group, camera, TICK_TARGETS);
            if (!TICK_TARGETS.isEmpty()) plan(emitter, group, time, TICK_TARGETS);
            group.planned.addAll(TICK_TARGETS);
        }
        TICK_TARGETS.clear();
    }

    static void beginFrame(long tick, float partial) {
        frameTime = (tick + Math.clamp((double)partial, 0, 1)) / 20.0;
    }

    public static void sample(CrowdData.Emitter emitter, double rendererTime, List<CrowdSampler.Ghost> out) {
        if (!WorldSystem.enabled || world != Minecraft.getInstance().level || world == null) return;
        Group group = GROUPS.get(groupKey(emitter));
        if (group != null && group.source == emitter) {
            out.addAll(group.planned);
        }
    }

    private static void prepareGeometry(Group group, CrowdData.Emitter emitter) {
        long revision = WorldSystem.allocationRevision();
        if (group.geometryRevision == revision && group.geometrySource == emitter) return;
        group.geometryRevision = revision;
        group.geometrySource = emitter;
        long signature = CrowdFlow.signature(emitter);
        if (group.shapeSignature != signature || !Objects.equals(group.groundFilter, emitter.groundBlocks)
                || group.groundMode != emitter.groundMode) {
            group.targets.clear();
            group.retiring.clear();
            group.delayedArrivals.clear();
            group.agents.clear();
            group.failedUntil.clear();
            group.sampledCount = -1;
            group.seededCount = 0;
            group.shapeSignature = signature;
            group.groundFilter = emitter.groundBlocks == null ? null : new ArrayList<>(emitter.groundBlocks);
            group.groundMode = emitter.groundMode;
        }
        CrowdFlow.prepare(emitter);
        if (DiffuserData.isDiffuser(emitter)) {
            group.minX = emitter.a[0] - emitter.diffuserRadius;
            group.maxX = emitter.a[0] + emitter.diffuserRadius;
            group.minZ = emitter.a[2] - emitter.diffuserRadius;
            group.maxZ = emitter.a[2] + emitter.diffuserRadius;
        } else if (emitter.isLine()) {
            group.minX = group.minZ = Double.POSITIVE_INFINITY;
            group.maxX = group.maxZ = Double.NEGATIVE_INFINITY;
            for (double[] point : emitter.path) {
                group.minX = Math.min(group.minX, point[0]);
                group.maxX = Math.max(group.maxX, point[0]);
                group.minZ = Math.min(group.minZ, point[2]);
                group.maxZ = Math.max(group.maxZ, point[2]);
            }
        } else {
            group.minX = Math.min(emitter.a[0], emitter.b[0]);
            group.maxX = Math.max(emitter.a[0], emitter.b[0]);
            group.minZ = Math.min(emitter.a[2], emitter.b[2]);
            group.maxZ = Math.max(emitter.a[2], emitter.b[2]);
        }
    }

    private static double drawDistance(CrowdData.Emitter emitter) {
        return emitter.maxDist > 0 ? Math.min(160, emitter.maxDist) : 64;
    }

    private static boolean inRange(Group group, CrowdData.Emitter emitter, Vec3 camera) {
        double pad = Math.max(2, Math.max(emitter.width, emitter.spread)) + WorldSystem.playerInfluenceRadius() + 2;
        double x = Math.max(0, Math.max(group.minX - camera.x, camera.x - group.maxX) - pad);
        double z = Math.max(0, Math.max(group.minZ - camera.z, camera.z - group.maxZ) - pad);
        double draw = drawDistance(emitter);
        return x * x + z * z <= draw * draw;
    }

    private static void collect(CrowdData.Emitter e, Group group, Vec3 camera, List<CrowdSampler.Ghost> out) {
        int count = WorldSystem.count(e);
        double distance = drawDistance(e);
        long revision = WorldSystem.allocationRevision();
        boolean environmental = WorldSystem.environmentEnabled && group.allocationRevision == revision && group.source == e;
        if (!environmental) {
            group.delayedArrivals.clear();
            for (CrowdSampler.Ghost target : group.retiring) {
                if (target.idx() >= count) removeActor(group, target.idx());
            }
            group.retiring.clear();
        }
        group.allocationRevision = revision;
        group.source=e;
        if (!group.targets.isEmpty()) {
            CrowdSampler.Ghost first = group.targets.getFirst();
            if (first.alpha() != (float)e.alpha || first.color() != e.color) {
                group.targets.clear();
                group.retiring.clear();
                group.delayedArrivals.clear();
                group.sampledCount = -1;
                group.seededCount = 0;
            }
        }
        if (count < group.sampledCount) {
            group.delayedArrivals.clear(count, group.sampledCount);
            int end = group.targets.size();
            while (end > 0 && group.targets.get(end - 1).idx() >= count) end--;
            for (int i = end; i < group.targets.size(); i++) {
                CrowdSampler.Ghost candidate = group.targets.get(i);
                if (environmental && CrowdPresence.keep(e, candidate.idx())) group.retiring.add(candidate);
                else removeActor(group, candidate.idx());
            }
            group.targets.subList(end, group.targets.size()).clear();
            group.seededCount = Math.min(group.seededCount, count);
        } else if (count > group.sampledCount) {
            if (environmental && group.sampledCount >= 0) {
                group.delayedArrivals.set(group.sampledCount, count);
            }
            for (CrowdSampler.Ghost target : group.retiring) {
                if (target.idx() < count) group.delayedArrivals.clear(target.idx());
            }
            group.retiring.removeIf(target -> target.idx() < count);
        }
        double radius = Math.max(.18, Math.min(1.2, .30 * e.size / 1.875));
        int end = Math.min(count, group.seededCount + Math.min(targetsLeft, 512));
        while (group.seededCount < end) {
            int index = group.seededCount++;
            targetsLeft--;
            double[] seed = CrowdFlow.spawn(e, index, radius);
            if (seed != null) group.targets.add(new CrowdSampler.Ghost(seed[0], seed[1], seed[2], 0, (float)e.alpha,
                    (float)e.size, e.color, index, 0, 0));
        }
        group.sampledCount = count;
        group.retiring.removeIf(target -> {
            if (CrowdPresence.keep(e, target.idx())) return false;
            removeActor(group, target.idx());
            return true;
        });
        for(CrowdSampler.Ghost target:group.targets){Agent existing=group.agents.get(target.idx());double x=existing==null?target.x():existing.renderX,z=existing==null?target.z():existing.renderZ;double view=distance+8;if((x-camera.x)*(x-camera.x)+(z-camera.z)*(z-camera.z)<=view*view)out.add(target);}
        out.addAll(group.retiring);
    }
    private static void removeActor(Group group, int index) {
        group.agents.remove(index);
        group.failedUntil.remove(index);
    }
    /** Revalidates support independently of horizontal walking; no stale height survives block removal. */
    private static boolean settle(Agent a,double radius,double height,boolean ground,double time,double dt){
        double water = CrowdWater.surface(world, a.x, a.y, a.z);
        a.floating = Double.isFinite(water);
        if (a.floating) {
            a.falling = false;
            a.fallSpeed = 0;
            double feet = CrowdWater.floatingFeet(world, a.x, a.z, water, height);
            if (!clear(a.x, a.y, a.z, radius, height)) {
                if (remainingQueries() > 0) a.hidden = true;
                return false;
            }
            a.hidden = false;
            if (Math.abs(feet - a.y) < .001) return true;
            double nextY = a.y + Math.clamp(feet - a.y, -CrowdWater.RISE_SPEED * dt, CrowdWater.RISE_SPEED * dt);
            if (!vertical(a.x, a.y, a.z, nextY, radius, height)) return false;
            a.scratchCount = 0;
            append(a, a.x, a.y, a.z);
            append(a, a.x, nextY, a.z);
            a.fx = a.x;
            a.fy = nextY;
            a.fz = a.z;
            transition(a, time, CrowdWater.RISE_SPEED);
            a.transitionDuration = dt;
            return false;
        }
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
        a.facingPlayer=false;a.move=0;a.clock=time;a.fallSpeed=0;
        a.runEffort = 0;
        a.runBlend = 0;
        a.retreatPlayer = -1;
        a.pursuit = 0;
        a.escapeRunning = false;
        a.crowdWait = 0;
    }
    private static CrowdSampler.Ghost pose(Agent a,CrowdSampler.Ghost source){
        return new CrowdSampler.Ghost(a.renderX,a.renderY,a.renderZ,(float)a.renderYaw,source.alpha(),source.size(),source.color(),source.idx(),(float)a.gait,locomotion(a));
    }
    private static float locomotion(Agent actor) {
        return actor.floating ? CrowdWater.movement(actor.move) : CrowdPace.movement(actor.move, actor.runBlend);
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
        if (actor.indexedTick == budgetTick) return;
        actor.indexedTick = budgetTick;
        var body = actor.body;
        SPACING.add(body, actor.renderX, actor.renderY, actor.renderZ, body.radius, body.height, actor.fx, actor.fz);
    }

    private static boolean outsideAttractionRing(double x, double y, double z) {
        if (!WorldSystem.avoidPlayers || WorldSystem.playerRadius >= 0) return true;
        for (Player player : world.players()) {
            if (Math.abs(y - player.getY()) >= 3) continue;
            double dx = x - player.getX(), dz = z - player.getZ();
            if (dx * dx + dz * dz < PlayerApproach.HOLD_DISTANCE * PlayerApproach.HOLD_DISTANCE - 1e-8) return false;
        }
        return true;
    }

    public static void adjust(CrowdData.Emitter e, double rendererTime, List<CrowdSampler.Ghost> ghosts) {
        if (ghosts.isEmpty()) return;
        Group group = group(e);
        double time = Math.max(world.getGameTime() / 20.0, frameTime);
        for (int i = 0; i < ghosts.size(); i++) {
            CrowdSampler.Ghost seed = ghosts.get(i);
            Agent actor = group.agents.get(seed.idx());
            if (actor.active) display(actor, time);
            ghosts.set(i, actor.active ? pose(actor, seed) : actor.frozen);
        }
    }

    private static void plan(CrowdData.Emitter e, Group group, double time, List<CrowdSampler.Ghost> ghosts) {
        Minecraft mc = Minecraft.getInstance();
        domain = e;
        geometryGroup = group;
        group.activeCount = 0;
        long tick = world.getGameTime();
        int n = ghosts.size();
        int influence = WorldSystem.playerInfluenceRadius();
        if (group.output.length < n) {
            group.output = new CrowdSampler.Ghost[n];
            group.priority = new byte[n];
        }
        var viewer = mc.player;
        for (int i = 0; i < n; i++) {
            var target = ghosts.get(i);
            Agent existing = group.agents.get(target.idx());
            double x = existing == null ? target.x() : existing.renderX;
            double y = existing == null ? target.y() : existing.renderY;
            double z = existing == null ? target.z() : existing.renderZ;
            double dx = x - viewer.getX(), dy = y - viewer.getY(), dz = z - viewer.getZ();
            if (!WorldSystem.active(x, y, z)) group.priority[i] = 3;
            else if (existing != null && !outsideAttractionRing(x, y, z)) group.priority[i] = 0;
            else group.priority[i] = (byte)(dx * dx + dy * dy + dz * dz <= 144 ? 1 : 2);
        }
        int start = Math.floorMod(tick * 73, n);
        // Reserve the first opportunities for retreating and nearby actors, then serve the outer range and passive spawns.
        for(int pass=0;pass<4;pass++)for(int ordinal=0;ordinal<n;ordinal++){
            int read=(start+ordinal)%n;CrowdSampler.Ghost g=ghosts.get(read);
            if(group.priority[read]!=pass)continue;
            boolean active = pass != 3;
            group.output[read]=null;Agent a=agent(group,g,e.ground,active);if(a==null)continue;a.seen=tick;
            if(!a.finalSet){a.fx=a.x;a.fy=a.y;a.fz=a.z;a.finalSet=true;display(a,time);}
            a.standing=WorldSystem.standing(e,g.idx());
            if(!active){
                if(a.active||a.frozen==null){display(a,time);freeze(a,time);a.frozen=pose(a,g);}a.active=false;a.tick=tick;
                if (!a.hidden) { registerBody(a); SPACING.reserve(a.body, a.fx, a.fz); }
                if(!a.hidden)group.output[read]=g;continue;
            }
            if(!a.active){
                freeze(a,time);a.tick=Long.MIN_VALUE;a.frozen=null;
            }
            if (!a.hidden) registerBody(a);
            a.active=true;
            display(a,time);boolean finishing=a.stepMotion&&time<a.transitionStart+a.transitionDuration;
            double r=radius(g),h=height(g);
            int period = a.standing && !a.falling && !(a.floating && a.stepMotion) ? 10 : 2;
            if (a.standing && (pass == 0 || influence > 0 && interactionArea(a, influence))) period = 2;
            long age = a.tick == Long.MIN_VALUE ? period + 1L : tick - a.tick;
            boolean ready = age > period || age == period && Math.floorMod(tick + g.idx(), period) == 0;
            if (!finishing && ready) {
                if (remainingQueries() > 0 || flat(a.renderX,a.renderY,a.renderZ,a.renderX,a.renderZ,r,h,true)) {
                    double elapsed = a.tick == Long.MIN_VALUE ? period / 20.0 : (tick - a.tick) / 20.0;
                    double dt = Math.clamp(elapsed, .05, .15);
                    a.x=a.renderX;a.y=a.renderY;a.z=a.renderZ;
                    a.runEffort = 0;
                    if(settle(a,r,h,e.ground,time,dt)){
                        if (!groundAllowed(e, a.x, a.y, a.z)) {
                            removeActor(group, g.idx());
                            continue;
                        }
                        registerBody(a);
                        int previousTarget = a.retreatPlayer;
                        Player nearest = interactionPlayer(mc, a, influence);
                        SPACING.nearby(a.body, a.x, a.y, a.z, NEIGHBORS);
                        double overlap = CrowdSpacing.pressure(a.body, NEIGHBORS, a.x, a.z, PRESSURE);
                        boolean approaching = nearest != null && WorldSystem.playerRadius < 0;
                        boolean retreating = nearest != null && !approaching && a.interactionStyle != CrowdPace.Style.WALK;
                        updateEscapePace(a, nearest, previousTarget, influence, elapsed);
                        a.facingPlayer = !a.floating && (approaching || (retreating
                                && a.interactionStyle == CrowdPace.Style.BACKPEDAL && !a.escapeRunning));
                        if (nearest != null) {
                            a.playerX = nearest.getX();
                            a.playerZ = nearest.getZ();
                        }
                        if (approaching || retreating) {
                            a.lookDuration = 0;
                        }
                        boolean madeRoom = approaching && makeRoomForPlayer(e, a, g.idx(), r, h, time, dt);
                        if (!madeRoom && (overlap < .003 || !recoverSpacing(e, a, r, h, time, dt, overlap, approaching, retreating))) {
                            if (approaching || retreating) {
                                moveRelativeToPlayer(e, a, g.idx(), r, h, time, dt, influence, approaching);
                            } else {
                                moveWithFlow(e, a, g.idx(), nearest, influence, r, h, tick, time, dt);
                            }
                        }
                        if (a.transitionStart == time) {
                            if (!a.stepMotion) a.transitionDuration = Math.max(a.transitionDuration, Math.min(.25, elapsed));
                            if (e.isLine()) CrowdFlow.moved(e, a.flow, a.fx, a.fz, Math.hypot(a.fx - a.x, a.fz - a.z));
                        }
                        SPACING.reserve(a.body, a.fx, a.fz);
                        a.x=a.fx;a.y=a.fy;a.z=a.fz;
                    }
                    if (remainingQueries() > 0 || a.transitionStart == time) a.tick=tick;
                }
            }
            if(!a.hidden){group.output[read]=g;group.activeCount++;}
        }
        int write=0;for(int i=0;i<n;i++){if(group.output[i]!=null)ghosts.set(write++,group.output[i]);group.output[i]=null;}ghosts.subList(write,n).clear();
        if(tick%100==0){int count=WorldSystem.count(e);group.agents.keySet().removeIf(i->i>=count&&!CrowdPresence.keep(e,i));}
    }
    private static Player interactionPlayer(Minecraft mc, Agent actor, int range) {
        if (range > 0 && WorldSystem.playerRadius > 0 && actor.interactionStyle == CrowdPace.Style.BACKPEDAL) {
            if (mc.level.getEntity(actor.retreatPlayer) instanceof Player tracked && tracked.isAlive()
                    && Math.abs(actor.y - tracked.getY()) < 3
                    && CrowdPace.holdsRetreat(Math.hypot(actor.x - tracked.getX(), actor.z - tracked.getZ()), range)) {
                return tracked;
            }
            Player nearest = nearestPlayer(mc, actor, range);
            if (nearest != null && nearest.getId() != actor.retreatPlayer) {
                var random = ThreadLocalRandom.current();
                actor.escapeReaction = random.nextDouble(.28, .60);
                actor.escapeEffort = random.nextDouble(.7, 1);
            }
            actor.retreatPlayer = nearest == null ? -1 : nearest.getId();
            return nearest;
        }
        actor.retreatPlayer = -1;
        return nearestPlayer(mc, actor, range);
    }

    private static void updateEscapePace(Agent actor, Player target, int previousTarget, int range, double elapsed) {
        if (actor.floating || target == null || actor.retreatPlayer == -1 || actor.retreatPlayer != previousTarget) {
            actor.pursuit = 0;
            actor.escapeRunning = false;
            return;
        }
        // Measure the player's approach against the same actor position for both samples.
        double oldDistance = Math.hypot(actor.x - actor.playerX, actor.z - actor.playerZ);
        double distance = Math.hypot(actor.x - target.getX(), actor.z - target.getZ());
        actor.pursuit = CrowdPace.pursuit(actor.pursuit, oldDistance - distance, elapsed);
        actor.escapeRunning = CrowdPace.escapeRunning(actor.escapeRunning, actor.pursuit, distance, range, actor.escapeReaction);
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

    private static void holdPosition(Agent actor) {
        actor.trackCount = 0;
        actor.stepMotion = false;
        actor.fx = actor.x;
        actor.fy = actor.y;
        actor.fz = actor.z;
    }

    private static boolean recoverSpacing(CrowdData.Emitter emitter, Agent actor,
                                          double radius, double height, double time, double dt, double overlap,
                                          boolean approaching, boolean retreating) {
        double heading = Math.atan2(PRESSURE[0], PRESSURE[1]);
        double step = Math.min(.20, 1.4 * dt);
        for (int pass = 0; pass < 2; pass++, step *= .45) {
            for (int direction = 0; direction < 8; direction++) {
                double angle = heading + direction * Math.PI / 4 + pass * Math.PI / 8;
                double dx = Math.sin(angle) * step, dz = Math.cos(angle) * step;
                if ((approaching || retreating) && !allowsInteractionStep(actor.x - actor.playerX,
                        actor.z - actor.playerZ, dx, dz, approaching)) continue;
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

    private static boolean allowsInteractionStep(double x, double z, double dx, double dz, boolean approaching) {
        return approaching ? PlayerApproach.allowsStep(x, z, dx, dz) : CrowdPace.movesOutward(x, z, dx, dz);
    }

    private static boolean makeRoomForPlayer(CrowdData.Emitter emitter, Agent actor, int index, double radius,
                                              double height, double time, double dt) {
        double x = actor.x - actor.playerX, z = actor.z - actor.playerZ;
        double distance = Math.hypot(x, z);
        boolean retreat = distance < PlayerApproach.HOLD_DISTANCE - .002;
        if (!retreat && !CrowdSpacing.yieldForRetreat(actor.body, NEIGHBORS, actor.x, actor.z, actor.playerX, actor.playerZ, YIELD)) {
            return false;
        }
        double heading = retreat ? (distance < .001 ? index * 2.3999632297 : Math.atan2(x, z)) : Math.atan2(YIELD[0], YIELD[1]);
        double speed = Math.clamp(emitter.speed, 1.8, 2.6);
        double length = retreat ? Math.min(speed * dt, PlayerApproach.RETREAT_DISTANCE - distance) : speed * dt;
        for (double turn : AVOID_TURNS) {
            double dx = Math.sin(heading + turn) * length, dz = Math.cos(heading + turn) * length;
            if (retreat && Math.hypot(x + dx, z + dz) < distance + .002) continue;
            if (!PlayerApproach.allowsStep(x, z, dx, dz)
                    || !CrowdSpacing.permits(actor.body, NEIGHBORS, actor.x, actor.z, dx, dz)) continue;
            double targetX = actor.x + dx, targetZ = actor.z + dz;
            double floor = trace(actor.x, actor.y, actor.z, targetX, targetZ, radius, height, emitter.ground, actor);
            if (!Double.isFinite(floor)) continue;
            actor.fx = targetX;
            actor.fy = floor;
            actor.fz = targetZ;
            transition(actor, time, speed);
            return true;
        }
        // Keep the outgoing lane free while the blocked actor retries on the following tick.
        if (!retreat) holdPosition(actor);
        return !retreat;
    }

    private static void moveRelativeToPlayer(CrowdData.Emitter emitter, Agent actor, int index,
                                             double radius, double height, double time, double dt,
                                             int range, boolean approaching) {
        holdPosition(actor);
        double offsetX = actor.x - actor.playerX, offsetZ = actor.z - actor.playerZ;
        double distance = Math.hypot(offsetX, offsetZ);
        double baseSpeed = Math.clamp(emitter.speed, .8, 5);
        double walkSpeed = approaching ? baseSpeed : Math.min(3, CrowdPace.repulsionSpeed(emitter.speed, distance, range));
        double effort = actor.interactionStyle == CrowdPace.Style.RUN
                ? CrowdPace.runningEffort(approaching, distance, range) : 0;
        if (actor.escapeRunning) effort = actor.escapeEffort;
        double speed = CrowdPace.speed(walkSpeed, baseSpeed, effort);
        if (actor.floating) speed = Math.min(speed, CrowdWater.SWIM_SPEED);
        double limit = approaching ? speed * dt : Math.min(speed * dt, Math.max(0, range - distance));
        double step = approaching ? PlayerApproach.displacement(distance, limit) : limit;
        double away = distance < .001 ? index * 2.3999632297 : Math.atan2(offsetX, offsetZ);
        double dxWanted = Math.sin(away) * step + PRESSURE[0];
        double dzWanted = Math.cos(away) * step + PRESSURE[1];
        double wanted = Math.hypot(dxWanted, dzWanted);
        double length = Math.min(limit, wanted);
        if (length < .001) return;
        if (!CrowdBounds.segment(emitter, actor.x, actor.z, actor.x + dxWanted * length / wanted,
                actor.z + dzWanted * length / wanted, radius)
                && followBoundary(emitter, actor, radius, height, time, dt, length, true, !approaching, effort)) return;
        double heading = Math.atan2(dxWanted, dzWanted);
        // Allow tangential escape from compression without crossing the player-side limit.
        for (double turn : AVOID_TURNS) {
            double direction = heading + turn;
            double dx = Math.sin(direction) * length, dz = Math.cos(direction) * length;
            if (!allowsInteractionStep(offsetX, offsetZ, dx, dz, approaching)
                    || !CrowdSpacing.permits(actor.body, NEIGHBORS, actor.x, actor.z, dx, dz)) continue;
            double x = actor.x + dx, z = actor.z + dz;
            double y = trace(actor.x, actor.y, actor.z, x, z, radius, height, emitter.ground, actor);
            if (Double.isFinite(y)) {
                if (turnForEscape(actor, direction, time, dt)) return;
                actor.fx = x;
                actor.fy = y;
                actor.fz = z;
                transition(actor, time, speed);
                double newDistance = Math.hypot(offsetX + dx, offsetZ + dz);
                if (approaching ? newDistance < distance - .001 : newDistance > distance + .001) {
                    actor.runEffort = effort;
                }
                if (!approaching) {
                    actor.flow.heading = Math.atan2(dx, dz);
                    if (!actor.facingPlayer) {
                        actor.toYaw = CrowdFlow.rotate(actor.fromYaw, actor.flow.heading, 8 * actor.transitionDuration);
                    }
                }
                return;
            }
            if (!WorldSystem.avoidBlocks || remainingQueries() == 0) break;
        }
        if (emitter.isLine()) followBoundary(emitter, actor, radius, height, time, dt, length, true, !approaching, effort);
    }

    private static boolean turnForEscape(Agent actor, double direction, double time, double dt) {
        if (!actor.escapeRunning || Math.cos(direction - actor.renderYaw) >= .7) return false;
        actor.lookFrom = actor.renderYaw;
        actor.lookTo = CrowdFlow.rotate(actor.renderYaw, direction, 11 * dt);
        actor.lookStart = time;
        actor.lookDuration = dt;
        return true;
    }

    private record BoundaryNormal(double x, double z, boolean onCenterline) {}

    private static BoundaryNormal boundaryNormal(CrowdData.Emitter emitter, double x, double z) {
        if (DiffuserData.isDiffuser(emitter)) {
            double dx = x - emitter.a[0], dz = z - emitter.a[2];
            double length = Math.hypot(dx, dz);
            return length > .001 ? new BoundaryNormal(dx / length, dz / length, false) : null;
        }
        boolean strip = emitter.isLine();
        boolean polygon = !strip && CrowdBounds.polygon(emitter);
        if (!strip && !polygon) {
            double left = x - Math.min(emitter.a[0], emitter.b[0]);
            double right = Math.max(emitter.a[0], emitter.b[0]) - x;
            double near = z - Math.min(emitter.a[2], emitter.b[2]);
            double far = Math.max(emitter.a[2], emitter.b[2]) - z;
            if (Math.min(left, right) <= Math.min(near, far)) return new BoundaryNormal(left < right ? -1 : 1, 0, false);
            return new BoundaryNormal(0, near < far ? -1 : 1, false);
        }

        List<double[]> points = strip ? emitter.path : emitter.polygon;
        double closest = Double.POSITIVE_INFINITY;
        double outwardX = 0, outwardZ = 0;
        int segments = strip ? points.size() - 1 : points.size();
        for (int i = 0; i < segments; i++) {
            double[] start = points.get(i), end = points.get((i + 1) % points.size());
            double dx = end[0] - start[0], dz = end[2] - start[2];
            double squared = dx * dx + dz * dz;
            if (squared < 1e-8) continue;
            double offsetX = x - start[0], offsetZ = z - start[2];
            double fraction = Math.clamp((offsetX * dx + offsetZ * dz) / squared, 0, 1);
            offsetX -= dx * fraction;
            offsetZ -= dz * fraction;
            double distance = Math.hypot(offsetX, offsetZ);
            if (distance >= closest) continue;
            closest = distance;
            double sign = strip ? 1 : -1;
            double length = Math.sqrt(squared);
            outwardX = distance < .001 ? dz / length : offsetX / distance * sign;
            outwardZ = distance < .001 ? -dx / length : offsetZ / distance * sign;
        }
        return Double.isFinite(closest) ? new BoundaryNormal(outwardX, outwardZ, closest < .001) : null;
    }

    private static boolean followBoundary(CrowdData.Emitter emitter, Agent actor, double radius, double height,
                                          double time, double dt, double length, boolean keepPlayerDistance,
                                          boolean repelled, double effort) {
        if (remainingQueries() == 0 && !flat(actor.x, actor.y, actor.z, actor.x, actor.z, radius, height, false)) return false;
        BoundaryNormal normal = boundaryNormal(emitter, actor.x, actor.z);
        if (normal == null) return false;
        double normalX = normal.x(), normalZ = normal.z();
        // Start tangent to the strip, then bend toward its interior if a wall blocks that direction.
        double tangentX = -normalZ, tangentZ = normalX;
        double headingDot = tangentX * Math.sin(actor.flow.heading) + tangentZ * Math.cos(actor.flow.heading);
        if (emitter.isLine() && Math.abs(headingDot) < .25) {
            double[] flow = CrowdFlow.velocity(emitter, actor.flow, actor.x, actor.z, radius);
            headingDot = tangentX * flow[0] + tangentZ * flow[1];
        }
        int preferred = headingDot >= 0 ? 1 : -1;
        if (repelled && !actor.floating) effort = Math.max(effort, .7);
        double speed = repelled && !actor.floating ? CrowdPace.speed(length / dt, emitter.speed, effort) : length / dt;
        double step = Math.min(.45, speed * dt);
        for (double turn : BOUNDARY_TURNS) {
            if (turn > 0 && normal.onCenterline()) break;
            int sides = turn == Math.PI / 2 ? 1 : 2;
            for (int side = 0; side < sides; side++) {
                int direction = side == 0 ? preferred : -preferred;
                double dx = step * (direction * tangentX * Math.cos(turn) - normalX * Math.sin(turn));
                double dz = step * (direction * tangentZ * Math.cos(turn) - normalZ * Math.sin(turn));
                double x = actor.x + dx, z = actor.z + dz;
                if (!CrowdBounds.segment(emitter, actor.x, actor.z, x, z, radius)
                        || keepPlayerDistance && !PlayerApproach.allowsStep(actor.x - actor.playerX, actor.z - actor.playerZ, dx, dz)
                        || !CrowdSpacing.permits(actor.body, NEIGHBORS, actor.x, actor.z, dx, dz)) continue;
                if (remainingQueries() == 0 && !flat(actor.x, actor.y, actor.z, x, z, radius, height, false)) continue;
                double y = trace(actor.x, actor.y, actor.z, x, z, radius, height, emitter.ground, actor);
                if (!Double.isFinite(y)) continue;
                double heading = Math.atan2(dx, dz);
                if (turnForEscape(actor, heading, time, dt)) return true;
                actor.fx = x;
                actor.fy = y;
                actor.fz = z;
                actor.flow.heading = heading;
                actor.runEffort = effort;
                if (repelled) actor.facingPlayer = false;
                transition(actor, time, step / dt);
                if (repelled && !actor.floating) actor.toYaw = CrowdFlow.rotate(actor.fromYaw, heading, 8 * actor.transitionDuration);
                return true;
            }
        }
        return false;
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
            double strength = CrowdPace.repulsionSpeed(e.speed, proximity, influence);
            velocity[0] += dx / distance * strength;
            velocity[1] += dz / distance * strength;
        }
        velocity[0] += PRESSURE[0] / dt;
        velocity[1] += PRESSURE[1] / dt;
        double speed = Math.min(a.floating ? CrowdWater.SWIM_SPEED : 5, Math.hypot(velocity[0], velocity[1]));
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
        double step = speed * dt;
        if (!CrowdBounds.segment(e, a.x, a.z, a.x + Math.sin(desired) * step, a.z + Math.cos(desired) * step, r)
                && followBoundary(e, a, r, h, time, dt, step, nearest != null, nearest != null, 0)) {
            a.crowdWait = 0;
            return;
        }
        double previous = Double.NaN;
        boolean blockedByCrowd = false;
        for (double turn : WorldSystem.avoidBlocks ? AVOID_TURNS : DIRECT_TURN) {
            double heading = CrowdFlow.rotate(a.flow.heading, desired + turn, maxTurn);
            if (Double.isFinite(previous) && Math.abs(heading - previous) < .01) continue;
            previous = heading;
            double dx = Math.sin(heading) * speed * dt, dz = Math.cos(heading) * speed * dt;
            if (!CrowdSpacing.permits(a.body, NEIGHBORS, a.x, a.z, dx, dz)) {
                blockedByCrowd = true;
                dx *= .40;
                dz *= .40;
                if (!CrowdSpacing.permits(a.body, NEIGHBORS, a.x, a.z, dx, dz)) continue;
            }
            double nx = a.x + dx, nz = a.z + dz;
            double ny = trace(a.x, y, a.z, nx, nz, r, h, e.ground, a);
            if (Double.isFinite(ny)) {
                double distance = Math.hypot(nx - a.x, nz - a.z);
                a.fx = nx;
                a.fy = ny;
                a.fz = nz;
                a.flow.heading = heading;
                a.crowdWait = 0;
                transition(a, time, distance / dt);
                if (!e.isLine()) CrowdFlow.moved(e, a.flow, nx, nz, distance);
                return;
            }
            if (remainingQueries() == 0 && !flat(a.x, y, a.z, nx, nz, r, h, false)) break;
        }
        if (e.isLine() && followBoundary(e, a, r, h, time, dt, step, nearest != null, nearest != null, 0)) return;
        if (blockedByCrowd) {
            if (!a.standing) {
                a.flow.heading = CrowdFlow.rotate(a.flow.heading, desired, maxTurn);
                if (!e.isLine()) CrowdFlow.moved(e, a.flow, a.x, a.z, 0);
                if (++a.crowdWait >= 2 && nearest == null) sidestepNeighbor(e, a, r, h, time, dt, step);
            }
            return;
        }
        a.crowdWait = 0;
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

    private static void sidestepNeighbor(CrowdData.Emitter emitter, Agent actor, double radius,
                                         double height, double time, double dt, double step) {
        double length = Math.min(step, .16);
        double heading = actor.flow.heading;
        // A short move to either side can release opposing walkers while preserving the route.
        for (int side = 1; side >= -1; side -= 2) {
            double dx = Math.cos(heading) * length * side;
            double dz = -Math.sin(heading) * length * side;
            if (!CrowdSpacing.permits(actor.body, NEIGHBORS, actor.x, actor.z, dx, dz)) continue;
            double x = actor.x + dx, z = actor.z + dz;
            double floor = trace(actor.x, actor.y, actor.z, x, z, radius, height, emitter.ground, actor);
            if (!Double.isFinite(floor)) continue;
            actor.fx = x;
            actor.fy = floor;
            actor.fz = z;
            transition(actor, time, length / dt);
            actor.toYaw = actor.fromYaw;
            actor.crowdWait = 0;
            return;
        }
    }

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
        if (dt > 1e-6) {
            double speed = moved / dt;
            double target = Math.min(1, speed);
            double running = a.floating || a.falling || a.stepMotion ? 0 : a.runEffort * Math.min(1, speed / 2);
            double blend = 1 - Math.exp(-dt * 15);
            a.move += (target - a.move) * blend;
            a.runBlend += (running - a.runBlend) * blend;
        }
        double stride = moved * Math.PI * 2 / (1.25 + .5 * a.runBlend);
        boolean steppingBackward = a.facingPlayer && (a.playerX - previousX) * (a.renderX - previousX)
                + (a.playerZ - previousZ) * (a.renderZ - previousZ) < -1e-7;
        a.gait += steppingBackward ? -stride : stride;
        a.footstepPhase += stride;
        if (WorldSystem.footsteps && a.active && !a.hidden && !a.falling && !a.floating
                && a.footstepPhase >= Math.PI) {
            CrowdFootsteps.play(world, a.renderX, a.renderY, a.renderZ);
        }
        if (a.footstepPhase >= Math.PI) a.footstepPhase %= Math.PI;
        a.clock=time;
    }
    private static void transition(Agent a,double time,double speed){
        a.trackCount = a.scratchCount;
        if (a.track == null || a.track.length < a.trackCount * 3) {
            int capacity = Math.max(4, Integer.highestOneBit(a.trackCount - 1) << 1);
            a.track = new double[capacity * 3];
            a.lengths = new double[capacity];
        }
        System.arraycopy(SWEEP, 0, a.track, 0, a.trackCount * 3);
        a.lengths[0]=0;a.stepMotion=false;double horizontal=0;
        for(int i=1;i<a.trackCount;i++){
            int k=i*3;double dx=a.track[k]-a.track[k-3],dy=a.track[k+1]-a.track[k-2],dz=a.track[k+2]-a.track[k-1];
            horizontal+=Math.hypot(dx,dz);a.lengths[i]=a.lengths[i-1]+Math.sqrt(dx*dx+dy*dy+dz*dz);a.stepMotion|=Math.abs(dy)>.001;
        }
        a.transitionStart=time;a.transitionDuration=a.stepMotion?.25:Math.max(.06,Math.min(.35,horizontal/Math.max(.1,speed)));
        a.fromYaw=a.renderYaw;double yaw=horizontal>.005?Math.atan2(a.track[(a.trackCount-1)*3]-a.track[0],a.track[(a.trackCount-1)*3+2]-a.track[2]):a.renderYaw;
        a.toYaw=CrowdFlow.rotate(a.fromYaw,yaw,3.5*a.transitionDuration);
    }
    public static double[] resolve(Minecraft mc, CrowdData.Emitter e, CrowdSampler.Ghost ghost, double x, double y, double z) {
        if (!WorldSystem.enabled || mc.level == null) return null;
        Agent actor = group(e).agents.get(ghost.idx());
        if (actor == null || actor.hidden || !CrowdBounds.inside(e, actor.renderX, actor.renderZ, radius(ghost))) return null;
        actor.output[0] = actor.renderX;
        actor.output[1] = actor.renderY;
        actor.output[2] = actor.renderZ;
        return actor.output;
    }

    public static CrowdSampler.Ghost animated(CrowdData.Emitter emitter, CrowdSampler.Ghost original) {
        return original;
    }

    /** The legacy renderer links this method, but movement now resolves real body positions. */
    public static void separate(List<CrowdSampler.Ghost> list, double spacing, double[] outX, double[] outZ) {
        Arrays.fill(outX, 0);
        Arrays.fill(outZ, 0);
    }
}
