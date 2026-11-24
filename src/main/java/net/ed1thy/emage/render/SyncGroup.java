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

    private static final int PREFETCH_FRAMES = 12;
    private static final int MAX_CLOCK_STEPS = 4;
    private static final long PRELOAD_LIMIT_BYTES = 64L * 1024 * 1024;
    private static final double ANIMATION_RANGE_HYSTERESIS = 8.0;

    private static final long LOAD_RETRY_NANOS = 250_000_000L;

    private final MapMetadata metadata;
    private final List<FrameNode> nodes;
    private final FlatFileStorage flatFileStorage;
    private final Cache<Long, MapFrameUpdate> frameCache;
    private final ExecutorService vtExecutor;

    private static final double SERVED_HALF_LIFE_MILLIS = 1000.0;

    private long lastTickTime = 0;
    private double servedBytes;
    private volatile double focusWeight = 1.0;
    private final Set<UUID> frozenViewers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, double[]> throttles = new ConcurrentHashMap<>();
    static volatile boolean scatterOrder = true;
    private long servedStamp;
    private long tickNowMillis;

    private final Map<Integer, MapFrameUpdate> baseFrames = new ConcurrentHashMap<>();
    private final Map<Integer, Map<Integer, MapFrameUpdate>> window = new ConcurrentHashMap<>();
    private final Set<Integer> loadingFrames = ConcurrentHashMap.newKeySet();
    private final Map<Integer, Long> lastLoadAttempt = new ConcurrentHashMap<>();

    private final Map<UUID, Set<Integer>> initializedMapsPerUser = new ConcurrentHashMap<>();
    private final Set<UUID> inRangePlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> visiblePlayers = ConcurrentHashMap.newKeySet();
    private final Set<Integer> ownedMapIds = ConcurrentHashMap.newKeySet();
    private final Map<UUID, double[]> playerViews = new ConcurrentHashMap<>();

    private volatile AnimationCanvas canvas;
    private volatile Map<Integer, Integer> canvasIndex = Map.of();
    private volatile int[] canvasMapIds = new int[0];
    private volatile List<FrameNode> canvasNodes = List.of();
    private volatile boolean animationInfoLoaded;
    private long canvasSignature;
    private int canvasFrame;
    private long lastClockMillis;
    private boolean clockFrozen;
    private volatile boolean preloadAll;
    private volatile boolean preloadStarted;
    private final Map<UUID, int[]> viewerSeq = new ConcurrentHashMap<>();
    private final Set<UUID> animRangePlayers = ConcurrentHashMap.newKeySet();
    private volatile double animatedDistance;

    private final List<Location> centerLocs = new CopyOnWriteArrayList<>();
    private volatile World world;
    private volatile RenderManager renderManager;

    private volatile int[] frameDelays;
    private volatile long lastActiveNanos = System.nanoTime();
    private volatile boolean animated;
    private volatile boolean closed;
    private final Map<UUID, ViewerStats> viewerStats = new ConcurrentHashMap<>();
    private final Map<UUID, java.util.concurrent.ConcurrentLinkedDeque<BatchItem>> batches = new ConcurrentHashMap<>();

    private record BatchItem(int index, int mapId, int prevSeq, io.netty.buffer.ByteBuf buf, int bytes) {}
    private volatile double frameBytesEwma;
    private volatile long clockStalls;
    private volatile int clockWaitingFrame = -1;
    private volatile String staticReason = "";

    private static final class ViewerStats {
        long bytes;
        long packets;
        long lastMillis;
        double bytesPerSecond;
        double packetsPerSecond;
    }

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
                installBase(entry.getKey(), entry.getValue());
            }
        } else {
            loadFrameAsync(0);
        }

        this.frameDelays = new int[] { metadata.delayMs() };
        CompletableFuture.runAsync(this::loadAnimationInfo, vtExecutor);

        if (!nodes.isEmpty()) {
            this.world = resolveWorld(nodes.get(0).getWorldUUID());
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
                long deltaBytes = 0;
                for (int frame = 1; frame < metadata.totalFrames(); frame++) {
                    if (flatFileStorage.hasFrameData(metadata.syncGroupID(), frame)) {
                        anyMotion = true;
                        deltaBytes += flatFileStorage.frameDataLength(metadata.syncGroupID(), frame);
                    }
                }
                long firstFrameBytes = flatFileStorage.frameDataLength(metadata.syncGroupID(), 0);
                boolean smallDeltas = anyMotion && firstFrameBytes > 0 && deltaBytes * 25 <= firstFrameBytes;
                boolean nearlyStill = smallDeltas && changedAreaWithinLimit();
                if (!anyMotion || nearlyStill) {
                    this.animated = false;
                    this.staticReason = !anyMotion ? "no frame differs" : "almost no pixels change";
                } else {
                    this.preloadAll = deltaBytes * 4 <= PRELOAD_LIMIT_BYTES;
                }
            }
        } catch (Exception ignored) {
        } finally {
            animationInfoLoaded = true;
        }
    }

    private boolean changedAreaWithinLimit() throws java.io.IOException {
        long maps = Math.max(1, (long) metadata.columns() * metadata.rows());
        long limit = Math.max(16L, maps * AnimationCanvas.MAP_BYTES / 1000);
        long area = 0;
        for (int frame = 1; frame < metadata.totalFrames(); frame++) {
            if (!flatFileStorage.hasFrameData(metadata.syncGroupID(), frame)) continue;
            Map<Integer, MapFrameUpdate> bundled = flatFileStorage.loadBundledFrame(metadata.syncGroupID(), frame);
            for (MapFrameUpdate update : bundled.values()) {
                for (DeltaFrame part : update.parts()) {
                    area += AnimationCanvas.packetArea(part.packetBuf());
                }
            }
            freeAll(bundled);
            if (area > limit) return false;
        }
        return true;
    }

    private void setViewDistance(double distance) {
        this.viewDistance = distance;
        this.viewDistanceSquared = distance * distance;
    }

    private static World resolveWorld(UUID id) {
        try {
            return Bukkit.getWorld(id);
        } catch (Throwable t) {
            return null;
        }
    }

    public void attach(@NotNull RenderManager renderManager) {
        this.renderManager = renderManager;
        this.animatedDistance = renderManager.getAnimatedViewDistance();
    }

    private double distanceToBoundsSquared(double x, double y, double z) {
        double dx = Math.max(Math.max(minX - x, 0.0), x - maxX);
        double dy = Math.max(Math.max(minY - y, 0.0), y - maxY);
        double dz = Math.max(Math.max(minZ - z, 0.0), z - maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    private boolean isAnimating(UUID uuid) {
        return animRangePlayers.contains(uuid) && visiblePlayers.contains(uuid);
    }

    private boolean anyAnimationViewer() {
        for (UUID uuid : visiblePlayers) {
            if (animRangePlayers.contains(uuid) && !frozenViewers.contains(uuid)) return true;
        }
        return false;
    }

    public void addNewWall(List<FrameNode> newNodes) {
        this.nodes.addAll(newNodes);
        for (Integer frame : window.keySet()) {
            freeAll(window.remove(frame));
        }
        for (FrameNode n : newNodes) ownedMapIds.add(n.getMapID());
        if (this.world == null && !newNodes.isEmpty()) {
            this.world = resolveWorld(newNodes.get(0).getWorldUUID());
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
        updateFocus();
        visiblePlayers.removeIf(uuid -> !inRangePlayers.contains(uuid));
        animRangePlayers.removeIf(uuid -> !inRangePlayers.contains(uuid));
        playerViews.keySet().removeIf(uuid -> !inRangePlayers.contains(uuid));
        initializedMapsPerUser.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
    }

    private void updateFocus() {
        double best = 0.1;
        for (UUID uuid : inRangePlayers) {
            best = Math.max(best, priorityFor(uuid));
        }
        focusWeight = best;
    }

    public double priorityFor(@NotNull UUID uuid) {
        double[] view = playerViews.get(uuid);
        if (view == null) return 0.1;
        if (distanceToBoundsSquared(view[0], view[1], view[2]) < 16.0) return 1.0;
        double cx = (minX + maxX) / 2.0;
        double cy = (minY + maxY) / 2.0;
        double cz = (minZ + maxZ) / 2.0;
        double tx = cx - view[0];
        double ty = cy - view[1];
        double tz = cz - view[2];
        double distance = Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (distance < 1.0e-6) return 1.0;
        double facing = (tx * view[3] + ty * view[4] + tz * view[5]) / distance;
        double weight = facing >= 0.9 ? 1.0 : (facing >= 0.5 ? 0.4 + 0.6 * (facing - 0.5) / 0.4 : 0.25);
        double nearest = Math.sqrt(distanceToBoundsSquared(view[0], view[1], view[2]));
        double distanceFactor = nearest <= 16.0 ? 1.0 : Math.max(0.35, 16.0 / nearest);
        return Math.max(0.1, weight * distanceFactor);
    }

    public double demandBytesPerSecond() {
        double perFrame = frameBytesEwma;
        if (perFrame <= 0) perFrame = nodes.size() * (double) AnimationCanvas.MAP_BYTES * 0.5;
        return perFrame * 1000.0 / Math.max(20, metadata.delayMs());
    }

    public int groupId() {
        return metadata.syncGroupID();
    }

    public boolean isPlayingFor(@NotNull UUID uuid) {
        return isAnimating(uuid);
    }

    public void setAllowanceFor(@NotNull UUID uuid, double bytesPerSecond) {
        if (bytesPerSecond <= 0) {
            throttles.remove(uuid);
            return;
        }
        double[] throttle = throttles.computeIfAbsent(uuid, k -> new double[]{bytesPerSecond, bytesPerSecond * 0.25, 0});
        throttle[0] = bytesPerSecond;
    }

    private long throttleAllowed(UUID uuid) {
        double[] throttle = throttles.get(uuid);
        if (throttle == null) return Long.MAX_VALUE;
        long now = tickNowMillis;
        if (throttle[2] != 0) {
            double elapsed = Math.max(0L, Math.min(500L, now - (long) throttle[2]));
            throttle[1] = Math.min(throttle[1] + throttle[0] * elapsed / 1000.0, throttle[0] * 0.3 + 32768);
        }
        throttle[2] = now;
        return throttle[1] > 0 ? (long) throttle[1] + 1 : 0;
    }

    private void throttleSpend(UUID uuid, long bytes) {
        double[] throttle = throttles.get(uuid);
        if (throttle != null) throttle[1] -= bytes;
    }

    public boolean isFrozenFor(@NotNull UUID uuid) {
        return frozenViewers.contains(uuid);
    }

    public void setFrozenFor(@NotNull UUID uuid, boolean frozen) {
        if (frozen) frozenViewers.add(uuid);
        else frozenViewers.remove(uuid);
    }

    private void markNotVisible(@NotNull UUID uuid) {
        visiblePlayers.remove(uuid);
    }

    private void onLeftRange(@NotNull UUID uuid) {
        boolean wasInRange = inRangePlayers.remove(uuid);
        animRangePlayers.remove(uuid);
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
        lastActiveNanos = System.nanoTime();

        double nearLimit = this.animatedDistance;
        boolean nearAnimation;
        if (nearLimit <= 0 || nearLimit >= viewDistance) {
            nearAnimation = true;
        } else {
            double limit = animRangePlayers.contains(uuid) ? nearLimit + ANIMATION_RANGE_HYSTERESIS : nearLimit;
            nearAnimation = distanceToBoundsSquared(pLoc.getX(), pLoc.getY(), pLoc.getZ()) <= limit * limit;
        }
        if (nearAnimation) {
            animRangePlayers.add(uuid);
        } else {
            animRangePlayers.remove(uuid);
        }

        if (calculateVisibility(eyeLoc, lookDir)) {
            visiblePlayers.add(uuid);
        } else {
            markNotVisible(uuid);
        }
    }

    public void onPlayerQuit(@NotNull UUID uuid) {
        inRangePlayers.remove(uuid);
        visiblePlayers.remove(uuid);
        animRangePlayers.remove(uuid);
        releaseBatch(uuid);
        frozenViewers.remove(uuid);
        throttles.remove(uuid);
        viewerSeq.remove(uuid);
        playerViews.remove(uuid);
        initializedMapsPerUser.remove(uuid);
        viewerStats.remove(uuid);
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
        releaseBatch(uuid);
        viewerSeq.remove(uuid);
        initializedMapsPerUser.remove(uuid);
    }

    public void forgetMaps(@NotNull UUID uuid, @NotNull Set<Integer> mapIds) {
        resetViewerMaps(uuid, mapIds);
        Set<Integer> sent = initializedMapsPerUser.get(uuid);
        if (sent != null) {
            sent.removeAll(mapIds);
        }
    }

    private void resetViewerMaps(UUID uuid, Iterable<Integer> mapIds) {
        Set<Integer> ids = new java.util.HashSet<>();
        for (Integer id : mapIds) ids.add(id);
        dropBatchItems(uuid, ids);
        int[] seqs = viewerSeq.get(uuid);
        if (seqs == null) return;
        Map<Integer, Integer> index = canvasIndex;
        for (Integer mapId : mapIds) {
            Integer i = index.get(mapId);
            if (i != null && i < seqs.length) seqs[i] = -1;
        }
    }

    public void markNodeInitialized(@NotNull UUID uuid, int mapId) {
        initializedMapsPerUser.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet()).add(mapId);
    }

    public void invalidateNodes(@NotNull UUID uuid, @NotNull List<FrameNode> unrenderedNodes) {
        Set<Integer> sent = initializedMapsPerUser.get(uuid);
        if (sent != null) {
            for (FrameNode node : unrenderedNodes) {
                sent.remove(node.getMapID());
                resetViewerMaps(uuid, java.util.List.of(node.getMapID()));
            }
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

    private void installBase(int mapId, @NotNull MapFrameUpdate update) {
        MapFrameUpdate existing = baseFrames.putIfAbsent(mapId, update);
        if (existing == null || existing == update) return;
        if (existing.isFreed() && baseFrames.replace(mapId, existing, update)) return;
        update.freeMemory();
    }

    private static void freeAll(@Nullable Map<Integer, MapFrameUpdate> frames) {
        if (frames == null) return;
        for (MapFrameUpdate update : frames.values()) {
            update.freeMemory();
        }
    }

    @Nullable
    private MapFrameUpdate frameFor(int frame, int mapId) {
        MapFrameUpdate update;
        if (frame == 0) {
            update = baseFrames.get(mapId);
        } else {
            Map<Integer, MapFrameUpdate> loaded = window.get(frame);
            update = loaded == null ? null : loaded.get(mapId);
        }
        return update != null && !update.isFreed() ? update : null;
    }

    private boolean isFrameReady(int frame) {
        for (FrameNode node : nodes) {
            if (frameFor(frame, node.getMapID()) == null) return false;
        }
        return true;
    }

    private void loadFrameAsync(int frame) {
        if (closed) return;
        long now = System.nanoTime();
        Long last = lastLoadAttempt.get(frame);
        if (last != null && now - last < LOAD_RETRY_NANOS) return;
        if (loadingFrames.contains(frame)) return;
        lastLoadAttempt.put(frame, now);
        CompletableFuture.runAsync(() -> loadFrameNow(frame), vtExecutor);
    }

    private void loadFrameNow(int frame) {
        if (closed || !loadingFrames.add(frame)) return;
        try {
            Map<Integer, MapFrameUpdate> bundled = flatFileStorage.loadBundledFrame(metadata.syncGroupID(), frame);
            if (closed) {
                freeAll(bundled);
                return;
            }
            if (frame == 0) {
                for (Map.Entry<Integer, MapFrameUpdate> entry : bundled.entrySet()) {
                    installBase(entry.getKey(), entry.getValue());
                }
            } else {
                Map<Integer, MapFrameUpdate> loaded = new ConcurrentHashMap<>();
                for (FrameNode node : nodes) {
                    int mapId = node.getMapID();
                    MapFrameUpdate update = bundled.remove(mapId);
                    loaded.put(mapId, update != null ? update : new MapFrameUpdate(new DeltaFrame[0]));
                }
                freeAll(bundled);
                if (window.putIfAbsent(frame, loaded) != null) {
                    freeAll(loaded);
                }
            }
            if (closed) releaseFrames();
        } catch (Exception ignored) {
        } finally {
            loadingFrames.remove(frame);
        }
    }

    private void startPreload() {
        preloadStarted = true;
        CompletableFuture.runAsync(() -> {
            for (int frame = 1; frame < frameCount() && !closed; frame++) {
                if (!window.containsKey(frame)) loadFrameNow(frame);
            }
        }, vtExecutor);
    }

    private void trimWindow(int current) {
        Set<Integer> keep = new java.util.HashSet<>();
        for (int i = 0; i <= PREFETCH_FRAMES; i++) {
            keep.add((current + i) % frameCount());
        }
        for (Integer frame : window.keySet()) {
            if (!keep.contains(frame)) {
                freeAll(window.remove(frame));
            }
        }
    }

    private int frameCount() {
        return Math.max(1, metadata.totalFrames());
    }

    public boolean shouldTick(long currentTimeMillis) {
        if (animated && anyAnimationViewer()) return true;
        for (UUID uuid : inRangePlayers) {
            if (animated) {
                int[] seqs = viewerSeq.get(uuid);
                if (seqs == null || canvas == null || seqs.length != canvas.count()) return true;
                for (int seq : seqs) {
                    if (seq < 0) return true;
                }
            } else {
                Set<Integer> sent = initializedMapsPerUser.get(uuid);
                if (sent == null || sent.size() < nodes.size()) return true;
            }
        }
        return (currentTimeMillis - lastTickTime) >= 1000;
    }

    public void tick(long currentTimeMillis, @NotNull FrameSink sink) {
        if (inRangePlayers.isEmpty()) return;
        tickNowMillis = currentTimeMillis;

        if (animated) {
            tickAnimated(currentTimeMillis, sink);
        } else {
            for (UUID uuid : inRangePlayers) {
                sendStills(uuid, sentMapsOf(uuid), sink);
            }
        }
        lastTickTime = currentTimeMillis;
    }

    private long nodesSignature() {
        long sum = 0;
        long mix = 0;
        for (FrameNode node : nodes) {
            sum += node.getMapID() * 31L + 1;
            mix ^= node.getMapID() * 0x9E3779B97F4A7C15L;
        }
        return sum * 1_000_003L ^ mix ^ nodes.size();
    }

    private boolean ensureCanvas(long nowMillis) {
        long signature = nodesSignature();
        if (canvas != null && signature == canvasSignature) return true;

        List<FrameNode> snapshot = new ArrayList<>(nodes);
        if (snapshot.isEmpty()) return false;
        for (FrameNode node : snapshot) {
            if (frameFor(0, node.getMapID()) == null) {
                loadFrameAsync(0);
                return false;
            }
        }

        AnimationCanvas fresh = new AnimationCanvas(snapshot.size());
        Map<Integer, Integer> index = new java.util.HashMap<>();
        int[] ids = new int[snapshot.size()];
        int frameSeq = fresh.beginFrame();
        for (int i = 0; i < snapshot.size(); i++) {
            int mapId = snapshot.get(i).getMapID();
            index.put(mapId, i);
            ids[i] = mapId;
            MapFrameUpdate base = frameFor(0, mapId);
            for (DeltaFrame part : base.parts()) {
                fresh.applyPacket(i, part.packetBuf(), frameSeq);
            }
        }
        canvasIndex = index;
        canvasMapIds = ids;
        canvasNodes = snapshot;
        canvas = fresh;
        canvasSignature = signature;
        canvasFrame = 0;
        lastClockMillis = nowMillis;
        releaseAllBatches();
        viewerSeq.clear();
        return true;
    }

    private int delayOf(int frame) {
        int[] delays = frameDelays;
        int delay = (delays != null && frame < delays.length) ? delays[frame] : metadata.delayMs();
        if (delay <= 0) delay = metadata.delayMs();
        return Math.max(20, delay);
    }

    private void advanceClock(long nowMillis) {
        AnimationCanvas current = canvas;
        for (int steps = 0; steps < MAX_CLOCK_STEPS; steps++) {
            int delay = delayOf(canvasFrame);
            if (nowMillis - lastClockMillis < delay) break;
            int next = (canvasFrame + 1) % frameCount();
            if (!isFrameReady(next)) {
                clockStalls++;
                clockWaitingFrame = next;
                loadFrameAsync(next);
                break;
            }
            clockWaitingFrame = -1;
            int frameSeq = current.beginFrame();
            List<FrameNode> snapshot = nodes;
            Map<Integer, Integer> index = canvasIndex;
            for (FrameNode node : snapshot) {
                Integer i = index.get(node.getMapID());
                if (i == null) continue;
                MapFrameUpdate update = frameFor(next, node.getMapID());
                if (update == null) continue;
                for (DeltaFrame part : update.parts()) {
                    current.applyPacket(i, part.packetBuf(), frameSeq);
                }
            }
            long changed = current.takeChangedBytes();
            frameBytesEwma = frameBytesEwma == 0 ? changed : frameBytesEwma * 0.9 + changed * 0.1;
            canvasFrame = next;
            lastClockMillis += delay;
            if (nowMillis - lastClockMillis > 4L * delay) lastClockMillis = nowMillis;
        }
    }

    private void tickAnimated(long nowMillis, FrameSink sink) {
        if (!ensureCanvas(nowMillis)) return;

        if (anyAnimationViewer()) {
            if (preloadAll && !preloadStarted) startPreload();
            if (!clockFrozen) advanceClock(nowMillis);
            if (!preloadAll) {
                trimWindow(canvasFrame);
                for (int i = 1; i <= PREFETCH_FRAMES; i++) {
                    int ahead = (canvasFrame + i) % frameCount();
                    if (!isFrameReady(ahead)) loadFrameAsync(ahead);
                }
            }
        } else {
            lastClockMillis = nowMillis;
        }

        clockTicks++;
        AnimationCanvas current = canvas;
        for (UUID uuid : inRangePlayers) {
            serveViewer(uuid, current, sink, isAnimating(uuid));
        }
    }

    private int clockTicks;

    private boolean cadenceDue(UUID uuid, @Nullable double[] view) {
        if (view == null) return true;
        double distance = Math.sqrt(distanceToBoundsSquared(view[0], view[1], view[2]));
        int divisor = distance < 12.0 ? 1 : (distance < 20.0 ? 2 : (distance < 32.0 ? 3 : 4));
        return divisor == 1 || (clockTicks + (uuid.hashCode() & 7)) % divisor == 0;
    }

    private record Pending(int index, int[] box, double score) {}

    private void serveViewer(UUID uuid, AnimationCanvas current, FrameSink sink, boolean animating) {
        int count = current.count();
        int[] seqs = viewerSeq.get(uuid);
        if (seqs == null || seqs.length != count) {
            releaseBatch(uuid);
            seqs = new int[count];
            java.util.Arrays.fill(seqs, -1);
            viewerSeq.put(uuid, seqs);
        }

        long budget = sink.animationBudget(uuid);
        if (budget <= 0 && sink.animationFullBudget(uuid, !animating) <= 0) return;

        long allowedBytes = throttleAllowed(uuid);
        Set<Integer> sentMaps = sentMapsOf(uuid);
        long spent = 0;
        int packets = 0;

        java.util.concurrent.ConcurrentLinkedDeque<BatchItem> batch = batches.get(uuid);
        if (batch != null) {
            if (batch.isEmpty()) {
                batches.remove(uuid, batch);
                batch = null;
            } else if (budget > 0 && allowedBytes > 0) {
                long[] done = drainBatch(uuid, batch, sentMaps, sink, Math.min(budget, allowedBytes));
                spent += done[0];
                packets += (int) done[1];
                if (batch.isEmpty()) batches.remove(uuid, batch);
            }
        }
        boolean batchActive = batch != null && !batch.isEmpty();

        List<FrameNode> frameNodes = canvasNodes;
        double[] view = playerViews.get(uuid);
        boolean diffsDue = animating && !batchActive && !frozenViewers.contains(uuid) && budget - spent > 0 && allowedBytes - spent > 0 && cadenceDue(uuid, view);

        List<Pending> diffs = null;
        List<Pending> fulls = null;
        int latestSeq = current.seq();
        for (int i = 0; i < count; i++) {
            if (seqs[i] < 0) {
                if (fulls == null) fulls = new ArrayList<>();
                fulls.add(new Pending(i, AnimationCanvas.FULL_BOX, score(view, frameNodes.get(i))));
            } else if (diffsDue) {
                int[] box = current.diffBox(i, seqs[i]);
                if (box != null) {
                    if (diffs == null) diffs = new ArrayList<>();
                    double stale = 1.0 + Math.max(0, latestSeq - seqs[i]);
                    diffs.add(new Pending(i, box, scatterOrder ? scatterKey(view, frameNodes.get(i), i, latestSeq) : score(view, frameNodes.get(i)) / stale));
                }
            }
        }

        if (fulls != null || diffs != null) {
            long fullBudget = fulls != null ? sink.animationFullBudget(uuid, !animating) : 0;
            long allowance = Math.max(budget - spent, fullBudget);
            if (fulls == null) allowance = Math.min(allowance, allowedBytes - spent);
            if (allowance > 0) {
                List<Pending> ordered = new ArrayList<>();
                long total = 0;
                if (fulls != null) {
                    fulls.sort(Comparator.comparingDouble(Pending::score));
                    ordered.addAll(fulls);
                    total += (long) fulls.size() * AnimationCanvas.packetBytes(AnimationCanvas.FULL_BOX);
                }
                if (diffs != null) {
                    diffs.sort(Comparator.comparingDouble(Pending::score));
                    ordered.addAll(diffs);
                    for (Pending pending : diffs) {
                        total += AnimationCanvas.packetBytes(pending.box());
                    }
                }
                if (total <= allowance) {
                    for (Pending pending : ordered) {
                        long bytes = sendCanvasMap(uuid, current, pending, seqs, sentMaps, sink);
                        if (bytes == 0) break;
                        spent += bytes;
                        packets++;
                    }
                } else {
                    java.util.concurrent.ConcurrentLinkedDeque<BatchItem> fresh = new java.util.concurrent.ConcurrentLinkedDeque<>();
                    int[] ids = canvasMapIds;
                    for (Pending pending : ordered) {
                        if (pending.index() >= ids.length) continue;
                        int mapId = ids[pending.index()];
                        io.netty.buffer.ByteBuf buf = current.buildPacket(pending.index(), mapId, pending.box());
                        fresh.add(new BatchItem(pending.index(), mapId, seqs[pending.index()], buf, AnimationCanvas.packetBytes(pending.box())));
                        seqs[pending.index()] = latestSeq;
                    }
                    batches.put(uuid, fresh);
                    long[] done = drainBatch(uuid, fresh, sentMaps, sink, Math.max(1, allowance));
                    spent += done[0];
                    packets += (int) done[1];
                    if (fresh.isEmpty()) batches.remove(uuid, fresh);
                }
            }
        }
        if (spent > 0) {
            recordServed(spent);
            recordViewer(uuid, spent, packets);
            throttleSpend(uuid, spent);
        }
        sink.spendAnimationBudget(uuid, spent);
    }

    private static double scatterKey(@Nullable double[] view, FrameNode node, int index, int seq) {
        int cls = 0;
        if (view != null) {
            double tx = node.getBlockX() + 0.5 - view[0];
            double ty = node.getBlockY() + 0.5 - view[1];
            double tz = node.getBlockZ() + 0.5 - view[2];
            double distance = Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (distance > 1.0e-6) {
                double facing = (tx * view[3] + ty * view[4] + tz * view[5]) / distance;
                cls = facing > 0.35 ? 0 : (facing > -0.1 ? 1 : 2);
            }
        }
        int h = index * 0x9E3779B1 ^ seq * 0x85EBCA6B;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        return cls + ((h & 0x7FFFFFFF) / (double) 0x7FFFFFFF) * 0.99;
    }

    private long[] drainBatch(UUID uuid, java.util.concurrent.ConcurrentLinkedDeque<BatchItem> batch, Set<Integer> sentMaps,
                              FrameSink sink, long allowance) {
        long spent = 0;
        long packets = 0;
        while (true) {
            if (packets > 0 && spent >= allowance) break;
            BatchItem item = batch.pollFirst();
            if (item == null) break;
            if (item.buf().refCnt() <= 0) continue;
            MapFrameUpdate update = new MapFrameUpdate(new DeltaFrame[]{new DeltaFrame(1, item.mapId(), item.buf())});
            boolean queued = sink.enqueueUpdate(uuid, update, PlayerSendQueue.TIER_ANIMATION);
            if (!queued) {
                batch.addFirst(item);
                break;
            }
            update.freeMemory();
            sentMaps.add(item.mapId());
            spent += item.bytes();
            packets++;
        }
        return new long[]{spent, packets};
    }

    private void releaseBatch(UUID uuid) {
        java.util.concurrent.ConcurrentLinkedDeque<BatchItem> batch = batches.remove(uuid);
        if (batch == null) return;
        BatchItem item;
        while ((item = batch.pollFirst()) != null) {
            if (item.buf().refCnt() > 0) item.buf().release();
        }
    }

    private void releaseAllBatches() {
        for (UUID uuid : batches.keySet()) {
            releaseBatch(uuid);
        }
    }

    private void dropBatchItems(UUID uuid, Set<Integer> mapIds) {
        java.util.concurrent.ConcurrentLinkedDeque<BatchItem> batch = batches.get(uuid);
        if (batch == null) return;
        for (BatchItem item : batch) {
            if (mapIds.contains(item.mapId()) && batch.remove(item)) {
                if (item.buf().refCnt() > 0) item.buf().release();
            }
        }
    }

    private long sendCanvasMap(UUID uuid, AnimationCanvas current, Pending pending, int[] seqs,
                               Set<Integer> sentMaps, FrameSink sink) {
        int[] ids = canvasMapIds;
        if (pending.index() >= ids.length) return 0;
        int mapId = ids[pending.index()];
        io.netty.buffer.ByteBuf buf = current.buildPacket(pending.index(), mapId, pending.box());
        MapFrameUpdate update = new MapFrameUpdate(new DeltaFrame[]{new DeltaFrame(1, mapId, buf)});
        boolean queued = sink.enqueueUpdate(uuid, update, PlayerSendQueue.TIER_ANIMATION);
        update.freeMemory();
        if (!queued) return 0;
        seqs[pending.index()] = current.seq();
        sentMaps.add(mapId);
        return AnimationCanvas.packetBytes(pending.box());
    }

    private Set<Integer> sentMapsOf(UUID uuid) {
        return initializedMapsPerUser.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());
    }

    private record Candidate(double score, FrameNode node) {}

    private void sendStills(UUID uuid, Set<Integer> sentMaps, FrameSink sink) {
        long budget = sink.stillBudget(uuid, 1);
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
            if (sink.stillBudget(uuid, tier) <= 0) break;
            int mapId = candidate.node().getMapID();
            MapFrameUpdate frame = frameFor(0, mapId);
            if (frame == null) {
                notLoaded = true;
                continue;
            }
            if (sink.enqueueUpdate(uuid, frame, tier)) {
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

    void setViewerStateForTesting(UUID uuid, boolean inAnimationRange, boolean visible) {
        inRangePlayers.add(uuid);
        if (inAnimationRange) animRangePlayers.add(uuid);
        else animRangePlayers.remove(uuid);
        if (visible) visiblePlayers.add(uuid);
        else visiblePlayers.remove(uuid);
    }

    boolean baseLoadedForTesting() {
        return baseFrames.size() >= nodes.size();
    }

    int loadedWindowFramesForTesting() {
        return window.size();
    }

    byte[] canvasPixelsForTesting(int mapId) {
        AnimationCanvas current = canvas;
        Integer i = canvasIndex.get(mapId);
        return current == null || i == null ? null : current.pixelsOf(i);
    }

    void loadFrameForTesting(int frame) {
        loadFrameNow(frame);
    }

    @Nullable
    MapFrameUpdate frameForTesting(int frame, int mapId) {
        return frameFor(frame, mapId);
    }

    boolean animationInfoLoadedForTesting() {
        return animationInfoLoaded;
    }

    Set<Integer> canvasMapIdsForTesting() {
        return canvasIndex.keySet();
    }

    void freezeClockForTesting() {
        clockFrozen = true;
    }

    int canvasFrameForTesting() {
        return canvasFrame;
    }

    boolean hasViewerStateForTesting(UUID uuid) {
        return viewerSeq.containsKey(uuid);
    }

    void setAnimatedForTesting(boolean value) {
        this.animated = value;
    }

    public boolean isAnimatedPicture() {
        return animated;
    }

    double servedScore(long nowMillis) {
        return decayedServed(nowMillis) * Math.sqrt(Math.max(1, nodes.size())) / focusWeight;
    }

    private double decayedServed(long nowMillis) {
        if (servedBytes <= 0) return 0.0;
        double elapsed = Math.max(0L, nowMillis - servedStamp);
        return servedBytes * Math.exp(-elapsed * Math.log(2.0) / SERVED_HALF_LIFE_MILLIS);
    }

    private void recordViewer(UUID uuid, long bytes, int packets) {
        ViewerStats stats = viewerStats.computeIfAbsent(uuid, k -> new ViewerStats());
        long now = tickNowMillis;
        double elapsed = stats.lastMillis == 0 ? 1000.0 : Math.max(1L, now - stats.lastMillis);
        double alpha = 1.0 - Math.exp(-elapsed / 3000.0);
        stats.bytesPerSecond += (bytes * 1000.0 / elapsed - stats.bytesPerSecond) * alpha;
        stats.packetsPerSecond += (packets * 1000.0 / elapsed - stats.packetsPerSecond) * alpha;
        stats.lastMillis = now;
        stats.bytes += bytes;
        stats.packets += packets;
    }

    private void recordServed(long bytes) {
        servedBytes = decayedServed(tickNowMillis) + bytes;
        servedStamp = tickNowMillis;
    }

    public boolean hasViewers() {
        return !inRangePlayers.isEmpty();
    }

    public String describe() {
        int animating = 0;
        for (UUID uuid : visiblePlayers) {
            if (animRangePlayers.contains(uuid)) animating++;
        }
        return "gif #" + metadata.syncGroupID() + ": " + metadata.columns() + "x" + metadata.rows() + " maps, " + frameCount() + " frames, "
                + inRangePlayers.size() + " in range, " + animating + " playing, frame " + canvasFrame
                + ", " + (preloadAll ? "fully loaded" : "streamed");
    }

    public int mapCount() {
        return nodes.size();
    }

    public boolean hasViewer(UUID uuid) {
        return inRangePlayers.contains(uuid);
    }

    public int deliveredMaps(UUID uuid) {
        if (animated) {
            int[] seqs = viewerSeq.get(uuid);
            if (seqs == null) return 0;
            int count = 0;
            for (int seq : seqs) if (seq >= 0) count++;
            return count;
        }
        Set<Integer> sent = initializedMapsPerUser.get(uuid);
        return sent == null ? 0 : sent.size();
    }

    public List<String> describeFull() {
        List<String> lines = new ArrayList<>();
        int maps = nodes.size();
        int delay = metadata.delayMs();
        double frameBytes = frameBytesEwma;
        double demand = frameBytes <= 0 ? 0 : frameBytes * 1000.0 / Math.max(20, delay);
        StringBuilder sb = new StringBuilder();
        sb.append("gif #").append(metadata.syncGroupID()).append(": ").append(metadata.columns()).append("x").append(metadata.rows())
                .append(" (").append(maps).append(" maps), ").append(frameCount()).append(" frames, ").append(delay).append(" ms/frame, ")
                .append(animated ? "animated" : "treated as a picture (" + (staticReason.isEmpty() ? "single frame" : staticReason) + ")");
        if (animated) {
            sb.append(", ").append(preloadAll ? "all frames in memory" : "frames streamed from disk")
                    .append(", loaded frames ").append(window.size()).append("/").append(Math.max(0, frameCount() - 1))
                    .append(", clock at frame ").append(canvasFrame);
            if (clockWaitingFrame >= 0) sb.append(" (waiting for frame ").append(clockWaitingFrame).append(" to load)");
            sb.append(", clock stalls ").append(clockStalls);
            sb.append(String.format(", changed data per frame %.0f KB (%.1f MB/s at original speed)", frameBytes / 1024.0, demand / (1024.0 * 1024.0)));
        }
        lines.add(sb.toString());
        AnimationCanvas current = canvas;
        for (UUID uuid : inRangePlayers) {
            Player player = Bukkit.getPlayer(uuid);
            String name = player == null ? uuid.toString().substring(0, 8) : player.getName();
            StringBuilder v = new StringBuilder("    viewer ").append(name).append(": ")
                    .append(animRangePlayers.contains(uuid) ? "close enough" : "too far to animate")
                    .append(", ").append(visiblePlayers.contains(uuid) ? "in view" : "out of view");
            if (frozenViewers.contains(uuid)) v.append(", PAUSED (does not fit this player's budget)");
            v.append(String.format(", priority %.2f", priorityFor(uuid)));
            if (animated && current != null) {
                int[] seqs = viewerSeq.get(uuid);
                if (seqs == null) {
                    v.append(", nothing sent yet");
                } else {
                    int unsent = 0;
                    int worstLag = 0;
                    int pending = 0;
                    int latest = current.seq();
                    for (int i = 0; i < seqs.length && i < current.count(); i++) {
                        if (seqs[i] < 0) {
                            unsent++;
                        } else {
                            worstLag = Math.max(worstLag, latest - seqs[i]);
                            if (current.diffBox(i, seqs[i]) != null) pending++;
                        }
                    }
                    java.util.concurrent.ConcurrentLinkedDeque<BatchItem> inProgress = batches.get(uuid);
                    if (inProgress != null && !inProgress.isEmpty()) {
                        long left = 0;
                        for (BatchItem item : inProgress) left += item.bytes();
                        v.append(", frame being sent: ").append(inProgress.size()).append(" maps (").append(left / 1024).append(" KB) left");
                    }
                    v.append(", maps without picture ").append(unsent).append("/").append(seqs.length)
                            .append(", maps waiting for update ").append(pending)
                            .append(", behind by up to ").append(worstLag).append(" frames");
                }
            } else if (!animated) {
                Set<Integer> sent = initializedMapsPerUser.get(uuid);
                v.append(", maps sent ").append(sent == null ? 0 : sent.size()).append("/").append(maps);
            }
            ViewerStats stats = viewerStats.get(uuid);
            if (stats != null) {
                v.append(String.format(", sending %.0f KB/s (%.0f packets/s), %d KB total", stats.bytesPerSecond / 1024.0, stats.packetsPerSecond, stats.bytes / 1024));
            }
            lines.add(v.toString());
        }
        return lines;
    }

    public int pinnedFrameCount() {
        return baseFrames.size();
    }

    public void releaseIfIdle(long nowNanos, long idleNanos) {
        if (!inRangePlayers.isEmpty()) {
            lastActiveNanos = nowNanos;
            return;
        }
        if (nowNanos - lastActiveNanos < idleNanos) return;
        if (baseFrames.isEmpty() && window.isEmpty()) return;
        releaseFrames();
    }

    private void releaseFrames() {
        canvas = null;
        canvasIndex = Map.of();
        releaseAllBatches();
        viewerSeq.clear();
        preloadStarted = false;
        for (Integer mapId : baseFrames.keySet()) {
            MapFrameUpdate update = baseFrames.remove(mapId);
            if (update != null) update.freeMemory();
        }

        for (Integer frame : window.keySet()) {
            freeAll(window.remove(frame));
        }
        lastLoadAttempt.clear();
    }

    public void cleanup() {
        closed = true;
        releaseFrames();
    }
}
