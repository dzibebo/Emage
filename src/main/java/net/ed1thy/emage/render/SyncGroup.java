package net.ed1thy.emage.render;

import com.github.benmanes.caffeine.cache.Cache;
import net.ed1thy.emage.model.DeltaFrame;
import net.ed1thy.emage.model.FrameNode;
import net.ed1thy.emage.model.MapFrameUpdate;
import net.ed1thy.emage.model.MapMetadata;
import net.ed1thy.emage.storage.FlatFileStorage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;

public class SyncGroup {

    private static final int PREFETCH_FRAMES = 2;

    private static final double ANIMATION_START_DRAIN_SECONDS = 1.0;
    private static final double ANIMATION_MAX_DRAIN_SECONDS = 1.5;

    private static final long LOAD_RETRY_NANOS = 250_000_000L;

    private final MapMetadata metadata;
    private final List<FrameNode> nodes;
    private final FlatFileStorage flatFileStorage;
    private final Cache<Long, MapFrameUpdate> frameCache;
    private final ExecutorService vtExecutor;

    private int currentFrameIndex = 0;
    private long lastTickTime = 0;

    private final Map<Integer, MapFrameUpdate> baseFrames = new ConcurrentHashMap<>();
    private final Set<Integer> loadingFrames = ConcurrentHashMap.newKeySet();
    private final Map<Integer, Long> lastLoadAttempt = new ConcurrentHashMap<>();

    private final Map<UUID, Set<Integer>> initializedMapsPerUser = new ConcurrentHashMap<>();
    private final Set<UUID> inRangePlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> visiblePlayers = ConcurrentHashMap.newKeySet();
    private final Set<Integer> ownedMapIds = ConcurrentHashMap.newKeySet();
    private final Map<UUID, double[]> playerViews = new ConcurrentHashMap<>();

    private final Set<UUID> animSynced = ConcurrentHashMap.newKeySet();

    private final List<Location> centerLocs = new CopyOnWriteArrayList<>();
    private volatile World world;
    private volatile RenderManager renderManager;

    private volatile int[] frameDelays;
    private volatile boolean animated;

    private volatile double minX, minY, minZ, maxX, maxY, maxZ;
    private final double baseViewDistance;
    private volatile double viewDistance;
    private volatile double viewDistanceSquared;

    public SyncGroup(@NotNull MapMetadata metadata, @NotNull List<FrameNode> nodes,
                     @NotNull FlatFileStorage flatFileStorage, @NotNull Cache<Long, MapFrameUpdate> frameCache,
                     @NotNull ExecutorService vtExecutor, @Nullable Map<Integer, MapFrameUpdate> preloadedBaseFrames) {
        this(metadata, nodes, flatFileStorage, frameCache, vtExecutor, preloadedBaseFrames, 96.0);
    }

    public SyncGroup(@NotNull MapMetadata metadata, @NotNull List<FrameNode> nodes,
                     @NotNull FlatFileStorage flatFileStorage, @NotNull Cache<Long, MapFrameUpdate> frameCache,
                     @NotNull ExecutorService vtExecutor, @Nullable Map<Integer, MapFrameUpdate> preloadedBaseFrames,
                     double viewDistance) {
        this.metadata = metadata;
        this.nodes = nodes;
        this.flatFileStorage = flatFileStorage;
        this.frameCache = frameCache;
        this.vtExecutor = vtExecutor;
        this.baseViewDistance = viewDistance > 0 ? viewDistance : 96.0;
        this.animated = metadata.isAnimated();
        setViewDistance(this.baseViewDistance);

        for (FrameNode n : nodes) ownedMapIds.add(n.getMapID());

        if (preloadedBaseFrames != null) {
            for (Map.Entry<Integer, MapFrameUpdate> entry : preloadedBaseFrames.entrySet()) {
                installFrame(0, entry.getKey(), entry.getValue());
            }
        } else {
            loadFrameAsync(0);
        }

        this.frameDelays = new int[] { metadata.delayMs() };
        CompletableFuture.runAsync(this::loadAnimationInfo, vtExecutor);

        if (!nodes.isEmpty()) {
            this.world = Bukkit.getWorld(nodes.get(0).getWorldUUID());
            addNewWallBounds(nodes);
        }
    }

    private void loadAnimationInfo() {
        try {
            int[] delays = flatFileStorage.loadFrameDelays(metadata.syncGroupID());
            if (delays != null && delays.length > 0) {
                this.frameDelays = delays;
            }

            if (metadata.isAnimated()) {
                boolean anyMotion = false;
                for (int frame = 1; frame < metadata.totalFrames(); frame++) {
                    if (flatFileStorage.hasFrameData(metadata.syncGroupID(), frame)) {
                        anyMotion = true;
                        break;
                    }
                }
                if (!anyMotion) {
                    this.animated = false;
                    animSynced.clear();
                    refreshViewDistance();
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void setViewDistance(double distance) {
        this.viewDistance = distance;
        this.viewDistanceSquared = distance * distance;
    }

    private void refreshViewDistance() {
        double distance = baseViewDistance;
        RenderManager manager = this.renderManager;
        if (animated && manager != null) {
            double animatedDistance = manager.getAnimatedViewDistance();
            if (animatedDistance > 0 && animatedDistance < distance) {
                distance = animatedDistance;
            }
        }
        setViewDistance(distance);
    }

    public void attach(@NotNull RenderManager renderManager) {
        this.renderManager = renderManager;
        refreshViewDistance();
    }

    public void addNewWall(List<FrameNode> newNodes) {
        this.nodes.addAll(newNodes);
        for (FrameNode n : newNodes) ownedMapIds.add(n.getMapID());
        if (this.world == null && !newNodes.isEmpty()) {
            this.world = Bukkit.getWorld(newNodes.get(0).getWorldUUID());
        }
        addNewWallBounds(newNodes);
    }

    private void addNewWallBounds(List<FrameNode> wallNodes) {
        if (wallNodes.isEmpty() || this.world == null) return;

        double cMinX = Double.MAX_VALUE, cMinY = Double.MAX_VALUE, cMinZ = Double.MAX_VALUE;
        double cMaxX = -Double.MAX_VALUE, cMaxY = -Double.MAX_VALUE, cMaxZ = -Double.MAX_VALUE;

        for (FrameNode n : wallNodes) {
            cMinX = Math.min(cMinX, n.getBlockX()); cMinY = Math.min(cMinY, n.getBlockY()); cMinZ = Math.min(cMinZ, n.getBlockZ());
            cMaxX = Math.max(cMaxX, n.getBlockX() + 1.0); cMaxY = Math.max(cMaxY, n.getBlockY() + 1.0); cMaxZ = Math.max(cMaxZ, n.getBlockZ() + 1.0);
        }

        if (centerLocs.isEmpty()) {
            this.minX = cMinX; this.minY = cMinY; this.minZ = cMinZ;
            this.maxX = cMaxX; this.maxY = cMaxY; this.maxZ = cMaxZ;
        } else {
            this.minX = Math.min(this.minX, cMinX); this.minY = Math.min(this.minY, cMinY); this.minZ = Math.min(this.minZ, cMinZ);
            this.maxX = Math.max(this.maxX, cMaxX); this.maxY = Math.max(this.maxY, cMaxY); this.maxZ = Math.max(this.maxZ, cMaxZ);
        }

        Location center = new Location(world, (cMinX + cMaxX) / 2.0, (cMinY + cMaxY) / 2.0, (cMinZ + cMaxZ) / 2.0);
        this.centerLocs.add(center);
    }

    public void updateVisibility() {
        if (world == null || centerLocs.isEmpty()) return;

        for (Player player : world.getPlayers()) {
            checkPlayerVisibility(player);
        }

        inRangePlayers.removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
        visiblePlayers.removeIf(uuid -> !inRangePlayers.contains(uuid));
        animSynced.removeIf(uuid -> !visiblePlayers.contains(uuid));
        playerViews.keySet().removeIf(uuid -> !inRangePlayers.contains(uuid));
        initializedMapsPerUser.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
    }

    private void markNotVisible(@NotNull UUID uuid) {
        visiblePlayers.remove(uuid);
        animSynced.remove(uuid);
    }

    private void onLeftRange(@NotNull UUID uuid) {
        boolean wasInRange = inRangePlayers.remove(uuid);
        markNotVisible(uuid);
        playerViews.remove(uuid);
        RenderManager manager = this.renderManager;
        if (wasInRange && manager != null && manager.hasPendingPackets(uuid)) {
            manager.requestPurge(uuid, ownedMapIds, this);
        }
    }

    public void checkPlayerVisibility(@NotNull Player player) {
        UUID uuid = player.getUniqueId();
        if (world == null || centerLocs.isEmpty() || !player.getWorld().equals(world)) {
            onLeftRange(uuid);
            return;
        }

        Location pLoc = player.getLocation();
        double viewDistance = this.viewDistance;
        if (pLoc.getX() < minX - viewDistance || pLoc.getX() > maxX + viewDistance ||
                pLoc.getY() < minY - viewDistance || pLoc.getY() > maxY + viewDistance ||
                pLoc.getZ() < minZ - viewDistance || pLoc.getZ() > maxZ + viewDistance) {
            onLeftRange(uuid);
            return;
        }

        boolean inRange = false;
        double rangeSquared = this.viewDistanceSquared;
        for (Location center : centerLocs) {
            if (center.distanceSquared(pLoc) <= rangeSquared) {
                inRange = true;
                break;
            }
        }
        if (!inRange) {
            onLeftRange(uuid);
            return;
        }

        Location eyeLoc = player.getEyeLocation();
        Vector lookDir = eyeLoc.getDirection();

        double[] view = playerViews.computeIfAbsent(uuid, k -> new double[6]);
        view[0] = eyeLoc.getX();
        view[1] = eyeLoc.getY();
        view[2] = eyeLoc.getZ();
        view[3] = lookDir.getX();
        view[4] = lookDir.getY();
        view[5] = lookDir.getZ();
        inRangePlayers.add(uuid);

        if (calculateVisibility(eyeLoc, lookDir)) {
            visiblePlayers.add(uuid);
        } else {
            markNotVisible(uuid);
        }
    }

    public void onPlayerQuit(@NotNull UUID uuid) {
        inRangePlayers.remove(uuid);
        visiblePlayers.remove(uuid);
        animSynced.remove(uuid);
        playerViews.remove(uuid);
        initializedMapsPerUser.remove(uuid);
    }

    @Nullable
    public MapFrameUpdate getBaseFrame(int mapId) {
        return baseFrames.get(mapId);
    }

    public boolean ownsMap(int mapId) {
        return ownedMapIds.contains(mapId);
    }

    public boolean isNodeInitialized(@NotNull UUID uuid, int mapId) {
        Set<Integer> sent = initializedMapsPerUser.get(uuid);
        return sent != null && sent.contains(mapId);
    }

    public void clearUserState(@NotNull UUID uuid) {
        animSynced.remove(uuid);
        initializedMapsPerUser.remove(uuid);
    }

    public void forgetMaps(@NotNull UUID uuid, @NotNull Set<Integer> mapIds) {
        animSynced.remove(uuid);
        Set<Integer> sent = initializedMapsPerUser.get(uuid);
        if (sent != null) {
            sent.removeAll(mapIds);
        }
    }

    public void markNodeInitialized(@NotNull UUID uuid, int mapId) {
        initializedMapsPerUser.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet()).add(mapId);
    }

    public void invalidateNodes(@NotNull UUID uuid, @NotNull List<FrameNode> unrenderedNodes) {
        Set<Integer> sent = initializedMapsPerUser.get(uuid);
        if (sent != null) {
            boolean removedAny = false;
            for (FrameNode node : unrenderedNodes) {
                removedAny |= sent.remove(node.getMapID());
            }
            if (removedAny) animSynced.remove(uuid);
        }
    }

    private boolean calculateVisibility(Location eyeLoc, Vector lookDir) {
        double ex = eyeLoc.getX(), ey = eyeLoc.getY(), ez = eyeLoc.getZ();
        double dx = lookDir.getX(), dy = lookDir.getY(), dz = lookDir.getZ();
        double x0 = minX, y0 = minY, z0 = minZ, x1 = maxX, y1 = maxY, z1 = maxZ;

        if (pointVisible(x0, y0, z0, ex, ey, ez, dx, dy, dz)
                || pointVisible(x1, y0, z0, ex, ey, ez, dx, dy, dz)
                || pointVisible(x0, y1, z0, ex, ey, ez, dx, dy, dz)
                || pointVisible(x1, y1, z0, ex, ey, ez, dx, dy, dz)
                || pointVisible(x0, y0, z1, ex, ey, ez, dx, dy, dz)
                || pointVisible(x1, y0, z1, ex, ey, ez, dx, dy, dz)
                || pointVisible(x0, y1, z1, ex, ey, ez, dx, dy, dz)
                || pointVisible(x1, y1, z1, ex, ey, ez, dx, dy, dz)
                || pointVisible((x0 + x1) / 2.0, (y0 + y1) / 2.0, (z0 + z1) / 2.0, ex, ey, ez, dx, dy, dz)) {
            return true;
        }
        for (Location loc : centerLocs) {
            if (pointVisible(loc.getX(), loc.getY(), loc.getZ(), ex, ey, ez, dx, dy, dz)) return true;
        }
        return false;
    }

    private boolean pointVisible(double px, double py, double pz, double ex, double ey, double ez,
                                 double dx, double dy, double dz) {
        double tx = px - ex, ty = py - ey, tz = pz - ez;
        double distSq = tx * tx + ty * ty + tz * tz;
        if (distSq < 25.0) return true;
        if (distSq > viewDistanceSquared) return false;
        return (tx * dx + ty * dy + tz * dz) > 0.0;
    }

    private static long cacheKey(int frame, int mapId) {
        return ((long) frame << 32) | (mapId & 0xFFFFFFFFL);
    }

    private void installFrame(int frame, int mapId, @NotNull MapFrameUpdate update) {
        if (frame == 0) {
            update.retain();
            MapFrameUpdate old = baseFrames.put(mapId, update);
            if (old != null && old != update) {
                old.freeMemory();
            }
        }
        frameCache.put(cacheKey(frame, mapId), update);
    }

    @Nullable
    private MapFrameUpdate frameFor(int frame, int mapId) {
        MapFrameUpdate update = frameCache.getIfPresent(cacheKey(frame, mapId));
        if (update != null && !update.isFreed()) return update;
        if (frame == 0) {
            MapFrameUpdate base = baseFrames.get(mapId);
            if (base != null && !base.isFreed()) {
                try {
                    base.retain();
                } catch (RuntimeException e) {
                    return null;
                }
                frameCache.put(cacheKey(0, mapId), base);
                return base;
            }
        }
        return null;
    }

    private boolean isFrameReady(int frame) {
        for (FrameNode node : nodes) {
            if (frameFor(frame, node.getMapID()) == null) return false;
        }
        return true;
    }

    private void loadFrameAsync(int frame) {
        long now = System.nanoTime();
        Long last = lastLoadAttempt.get(frame);
        if (last != null && now - last < LOAD_RETRY_NANOS) return;
        if (!loadingFrames.add(frame)) return;
        lastLoadAttempt.put(frame, now);
        CompletableFuture.runAsync(() -> {
            try {
                Map<Integer, MapFrameUpdate> bundled = flatFileStorage.loadBundledFrame(metadata.syncGroupID(), frame);
                for (FrameNode node : nodes) {
                    int mapId = node.getMapID();
                    MapFrameUpdate update = bundled.remove(mapId);
                    if (update == null) {
                        if (frame == 0) continue;
                        update = new MapFrameUpdate(new DeltaFrame[0]);
                    }
                    installFrame(frame, mapId, update);
                }
                for (Map.Entry<Integer, MapFrameUpdate> rest : bundled.entrySet()) {
                    if (frame == 0) {
                        installFrame(0, rest.getKey(), rest.getValue());
                    } else {
                        rest.getValue().freeMemory();
                    }
                }
            } catch (Exception ignored) {
            } finally {
                loadingFrames.remove(frame);
            }
        }, vtExecutor);
    }

    private int frameCount() {
        return Math.max(1, metadata.totalFrames());
    }

    public boolean shouldTick(long currentTimeMillis) {
        if (!animated) {
            for (UUID uuid : inRangePlayers) {
                Set<Integer> sent = initializedMapsPerUser.get(uuid);
                if (sent == null || sent.size() < nodes.size()) {
                    return true;
                }
            }
            return (currentTimeMillis - lastTickTime) >= 1000;
        }
        int[] delays = frameDelays;
        int delay = (delays != null && currentFrameIndex < delays.length) ? delays[currentFrameIndex] : metadata.delayMs();
        if (delay <= 0) delay = metadata.delayMs();
        return (currentTimeMillis - lastTickTime) >= delay;
    }

    public void tick(long currentTimeMillis, @NotNull RenderManager renderManager) {
        if (inRangePlayers.isEmpty()) return;

        if (animated) {
            tickAnimated(renderManager);
        } else {
            currentFrameIndex = 0;
            for (UUID uuid : inRangePlayers) {
                sendStills(uuid, sentMapsOf(uuid), renderManager);
            }
        }
        lastTickTime = currentTimeMillis;
    }

    private void tickAnimated(RenderManager renderManager) {
        if (!isFrameReady(currentFrameIndex)) {
            loadFrameAsync(currentFrameIndex);
            return;
        }

        long burstBytes = 0;
        if (currentFrameIndex == 0) {
            for (FrameNode node : nodes) {
                MapFrameUpdate frame = frameFor(0, node.getMapID());
                if (frame != null) burstBytes += frame.totalBytes();
            }
        }

        for (UUID uuid : inRangePlayers) {
            Set<Integer> sentMaps = sentMapsOf(uuid);
            if (!visiblePlayers.contains(uuid)) {
                animSynced.remove(uuid);
                sendAnimationStills(uuid, sentMaps, renderManager);
                continue;
            }
            tickAnimatedViewer(uuid, sentMaps, renderManager, burstBytes);
        }

        currentFrameIndex++;
        if (currentFrameIndex >= frameCount()) {
            currentFrameIndex = 0;
        }
        for (int i = 0; i <= PREFETCH_FRAMES; i++) {
            int ahead = (currentFrameIndex + i) % frameCount();
            if (!isFrameReady(ahead)) {
                loadFrameAsync(ahead);
            }
        }
    }

    private void tickAnimatedViewer(UUID uuid, Set<Integer> sentMaps, RenderManager renderManager, long burstBytes) {
        boolean synced = animSynced.contains(uuid);

        if (synced && renderManager.animationDrainSeconds(uuid, 0) > ANIMATION_MAX_DRAIN_SECONDS) {
            animSynced.remove(uuid);
            synced = false;
        }

        if (!synced) {
            if (currentFrameIndex == 0 && renderManager.animationDrainSeconds(uuid, burstBytes) <= ANIMATION_START_DRAIN_SECONDS) {
                boolean all = true;
                for (FrameNode node : nodes) {
                    int mapId = node.getMapID();
                    MapFrameUpdate frame = frameFor(0, mapId);
                    if (frame == null || !renderManager.enqueueUpdate(uuid, frame, PlayerSendQueue.TIER_ANIMATION)) {
                        all = false;
                        continue;
                    }
                    sentMaps.add(mapId);
                }
                if (all) animSynced.add(uuid);
            } else {
                sendAnimationStills(uuid, sentMaps, renderManager);
            }
            return;
        }

        for (FrameNode node : nodes) {
            int mapId = node.getMapID();
            MapFrameUpdate frame = frameFor(currentFrameIndex, mapId);
            if (frame == null) {
                animSynced.remove(uuid);
                return;
            }
            if (frame.parts().length == 0) continue;
            if (!renderManager.enqueueUpdate(uuid, frame, PlayerSendQueue.TIER_ANIMATION)) {
                animSynced.remove(uuid);
                return;
            }
            sentMaps.add(mapId);
        }
    }

    private Set<Integer> sentMapsOf(UUID uuid) {
        return initializedMapsPerUser.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());
    }

    private void sendAnimationStills(UUID uuid, Set<Integer> sentMaps, RenderManager renderManager) {
        List<FrameNode> missing = null;
        for (FrameNode node : nodes) {
            if (!sentMaps.contains(node.getMapID())) {
                if (missing == null) missing = new ArrayList<>();
                missing.add(node);
            }
        }
        if (missing == null) return;

        double[] view = playerViews.get(uuid);
        if (view != null && missing.size() > 1) {
            missing.sort(Comparator.comparingDouble(n -> score(view, n)));
        }

        boolean notLoaded = false;
        for (FrameNode node : missing) {
            int mapId = node.getMapID();
            MapFrameUpdate frame = frameFor(0, mapId);
            if (frame == null) {
                notLoaded = true;
                continue;
            }
            if (renderManager.enqueueUpdate(uuid, frame, PlayerSendQueue.TIER_ANIMATION)) {
                sentMaps.add(mapId);
            }
        }
        if (notLoaded) {
            loadFrameAsync(0);
        }
    }

    private record Candidate(double score, FrameNode node) {}

    private void sendStills(UUID uuid, Set<Integer> sentMaps, RenderManager renderManager) {
        long budget = renderManager.stillBudget(uuid, 1);
        if (budget <= 0) return;

        int limit = (int) Math.max(1, Math.min(512, budget / PlayerSendQueue.MAP_PACKET_BYTES + 1));
        double[] view = playerViews.get(uuid);
        PriorityQueue<Candidate> best = new PriorityQueue<>(limit + 1, (x, y) -> Double.compare(y.score(), x.score()));
        for (FrameNode node : nodes) {
            if (sentMaps.contains(node.getMapID())) continue;
            double score = score(view, node);
            if (best.size() < limit) {
                best.add(new Candidate(score, node));
            } else if (score < best.peek().score()) {
                best.poll();
                best.add(new Candidate(score, node));
            }
        }
        if (best.isEmpty()) return;

        List<Candidate> ordered = new ArrayList<>(best);
        ordered.sort(Comparator.comparingDouble(Candidate::score));

        boolean notLoaded = false;
        for (Candidate candidate : ordered) {
            int tier = tierFor(candidate.score());
            if (renderManager.stillBudget(uuid, tier) <= 0) break;
            int mapId = candidate.node().getMapID();
            MapFrameUpdate frame = frameFor(0, mapId);
            if (frame == null) {
                notLoaded = true;
                continue;
            }
            if (renderManager.enqueueUpdate(uuid, frame, tier)) {
                sentMaps.add(mapId);
            }
        }
        if (notLoaded) {
            loadFrameAsync(0);
        }
    }

    private static double score(@Nullable double[] view, FrameNode node) {
        if (view == null) return 40.0;
        double tx = node.getBlockX() + 0.5 - view[0];
        double ty = node.getBlockY() + 0.5 - view[1];
        double tz = node.getBlockZ() + 0.5 - view[2];
        double distance = Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (distance < 1.0e-6) return 0.0;
        double facing = (tx * view[3] + ty * view[4] + tz * view[5]) / distance;
        double factor = facing > 0.35 ? 1.0 : (facing > -0.1 ? 1.8 : 3.5);
        return distance * factor;
    }

    private static int tierFor(double score) {
        if (score < 24.0) return 1;
        if (score < 56.0) return 2;
        return 3;
    }

    public List<FrameNode> getNodes() {
        return nodes;
    }

    public void cleanup() {
        for (MapFrameUpdate update : baseFrames.values()) {
            update.freeMemory();
        }
        baseFrames.clear();

        for (FrameNode node : nodes) {
            for (int i = 0; i < metadata.totalFrames(); i++) {
                frameCache.invalidate(cacheKey(i, node.getMapID()));
            }
        }
    }
}
