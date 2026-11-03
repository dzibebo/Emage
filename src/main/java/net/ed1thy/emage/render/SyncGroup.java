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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;

public class SyncGroup {

    private final MapMetadata metadata;
    private final List<FrameNode> nodes;
    private final FlatFileStorage flatFileStorage;
    private final Cache<Long, MapFrameUpdate> frameCache;
    private final ExecutorService vtExecutor;

    private int currentFrameIndex = 0;
    private long lastTickTime = 0;

    private final Map<Integer, MapFrameUpdate> baseFrames = new ConcurrentHashMap<>();
    private final Set<Integer> loadingFrames = ConcurrentHashMap.newKeySet();

    private final Set<UUID> initializedUsers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> visiblePlayers = ConcurrentHashMap.newKeySet();

    private final Set<UUID> pendingVisibilityChecks = ConcurrentHashMap.newKeySet();

    private final List<Location> centerLocs = new CopyOnWriteArrayList<>();
    private volatile World world;

    private int[] frameDelays;

    private volatile double minX, minY, minZ, maxX, maxY, maxZ;
    private final double viewDistance;
    private final double viewDistanceSquared;

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
        this.viewDistance = viewDistance > 0 ? viewDistance : 96.0;
        this.viewDistanceSquared = this.viewDistance * this.viewDistance;

        if (preloadedBaseFrames != null) {
            for (Map.Entry<Integer, MapFrameUpdate> entry : preloadedBaseFrames.entrySet()) {
                MapFrameUpdate update = entry.getValue();
                baseFrames.put(entry.getKey(), update);
                update.retain();
                long key = (0L << 32) | (entry.getKey() & 0xFFFFFFFFL);
                frameCache.put(key, update);
            }
        } else {
            loadingFrames.add(0);
        }

        CompletableFuture.runAsync(() -> {
            try {
                if (preloadedBaseFrames == null) {
                    Map<Integer, MapFrameUpdate> f0Bundle = flatFileStorage.loadBundledFrame(metadata.syncGroupID(), 0);
                    for (Map.Entry<Integer, MapFrameUpdate> entry : f0Bundle.entrySet()) {
                        MapFrameUpdate update = entry.getValue();
                        baseFrames.put(entry.getKey(), update);
                        update.retain();
                        long key = (0L << 32) | (entry.getKey() & 0xFFFFFFFFL);
                        frameCache.put(key, update);
                    }
                }

                frameDelays = flatFileStorage.loadFrameDelays(metadata.syncGroupID());
                if (frameDelays == null || frameDelays.length == 0) {
                    frameDelays = new int[] { metadata.delayMs() };
                }
            } catch (Exception ignored) {
            } finally {
                if (preloadedBaseFrames == null) {
                    loadingFrames.remove(0);
                }
            }
        }, vtExecutor);

        if (frameDelays == null) {
            frameDelays = new int[] { metadata.delayMs() };
        }

        if (!nodes.isEmpty()) {
            this.world = Bukkit.getWorld(nodes.get(0).getWorldUUID());
            addNewWallBounds(nodes);
        }
    }

    public void addNewWall(List<FrameNode> newNodes) {
        this.nodes.addAll(newNodes);
        if (this.world == null && !newNodes.isEmpty()) {
            this.world = Bukkit.getWorld(newNodes.get(0).getWorldUUID());
        }
        addNewWallBounds(newNodes);
        this.initializedUsers.clear();
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

        visiblePlayers.removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
        initializedUsers.removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
        pendingVisibilityChecks.removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
    }

    public void checkPlayerVisibility(@NotNull Player player) {
        if (world == null || centerLocs.isEmpty() || !player.getWorld().equals(world)) {
            visiblePlayers.remove(player.getUniqueId());
            return;
        }

        Location pLoc = player.getLocation();
        if (pLoc.getX() < minX - viewDistance || pLoc.getX() > maxX + viewDistance ||
                pLoc.getY() < minY - viewDistance || pLoc.getY() > maxY + viewDistance ||
                pLoc.getZ() < minZ - viewDistance || pLoc.getZ() > maxZ + viewDistance) {
            visiblePlayers.remove(player.getUniqueId());
            return;
        }

        UUID uuid = player.getUniqueId();
        if (pendingVisibilityChecks.contains(uuid)) return;

        boolean inRange = false;
        for (Location center : centerLocs) {
            if (center.distanceSquared(pLoc) <= viewDistanceSquared) {
                inRange = true;
                break;
            }
        }
        if (!inRange) {
            visiblePlayers.remove(uuid);
            return;
        }

        Location eyeLoc = player.getEyeLocation().clone();
        Vector lookDir = eyeLoc.getDirection().clone();

        pendingVisibilityChecks.add(uuid);

        CompletableFuture.runAsync(() -> {
            boolean canSee = calculateVisibilityAsync(eyeLoc, lookDir);

            if (canSee) {
                visiblePlayers.add(uuid);
            } else {
                visiblePlayers.remove(uuid);
            }

            pendingVisibilityChecks.remove(uuid);
        }, vtExecutor);
    }

    public void onPlayerQuit(@NotNull UUID uuid) {
        visiblePlayers.remove(uuid);
        initializedUsers.remove(uuid);
        pendingVisibilityChecks.remove(uuid);
    }

    private boolean calculateVisibilityAsync(Location eyeLoc, Vector lookDir) {
        Vector eye = eyeLoc.toVector();

        List<Vector> testPoints = new ArrayList<>(10 + centerLocs.size());
        testPoints.add(new Vector(minX, minY, minZ));
        testPoints.add(new Vector(maxX, minY, minZ));
        testPoints.add(new Vector(minX, maxY, minZ));
        testPoints.add(new Vector(maxX, maxY, minZ));
        testPoints.add(new Vector(minX, minY, maxZ));
        testPoints.add(new Vector(maxX, minY, maxZ));
        testPoints.add(new Vector(minX, maxY, maxZ));
        testPoints.add(new Vector(maxX, maxY, maxZ));
        testPoints.add(new Vector((minX + maxX) / 2.0, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0));
        for (Location loc : centerLocs) {
            testPoints.add(loc.toVector());
        }

        for (Vector point : testPoints) {
            Vector toPoint = point.clone().subtract(eye);
            double distSq = toPoint.lengthSquared();

            if (distSq < 25.0) return true;

            if (distSq > viewDistanceSquared) continue;

            toPoint.normalize();
            double dot = toPoint.dot(lookDir);

            if (dot > 0.0) {
                return true;
            }
        }

        return false;
    }

    public boolean shouldTick(long currentTimeMillis) {
        if (!metadata.isAnimated()) {
            for (UUID uuid : visiblePlayers) {
                if (!initializedUsers.contains(uuid)) {
                    return true;
                }
            }
            return (currentTimeMillis - lastTickTime) >= 1000;
        }
        int delay = (frameDelays != null && currentFrameIndex < frameDelays.length) ? frameDelays[currentFrameIndex] : metadata.delayMs();
        if (delay <= 0) delay = metadata.delayMs();
        return (currentTimeMillis - lastTickTime) >= delay;
    }

    public void tick(long currentTimeMillis, @NotNull ChunkViewerTracker tracker, @NotNull RenderManager renderManager, @NotNull PacketSender sender) {
        if (visiblePlayers.isEmpty()) return;

        boolean missingCurrent = false;
        for (FrameNode node : nodes) {
            long cacheKey = ((long) currentFrameIndex << 32) | (node.getMapID() & 0xFFFFFFFFL);
            MapFrameUpdate update = frameCache.getIfPresent(cacheKey);
            if (update == null || update.isFreed()) {
                if (currentFrameIndex == 0) {
                    MapFrameUpdate base = baseFrames.get(node.getMapID());
                    if (base != null && !base.isFreed()) {
                        frameCache.put(cacheKey, base);
                        continue;
                    }
                }
                missingCurrent = true;
                break;
            }
        }

        if (missingCurrent) {
            if (!loadingFrames.contains(currentFrameIndex)) {
                loadingFrames.add(currentFrameIndex);
                final int cFrame = currentFrameIndex;
                CompletableFuture.runAsync(() -> {
                    try {
                        Map<Integer, MapFrameUpdate> bundled = flatFileStorage.loadBundledFrame(metadata.syncGroupID(), cFrame);
                        for (FrameNode node : nodes) {
                            long key = ((long) cFrame << 32) | (node.getMapID() & 0xFFFFFFFFL);
                            MapFrameUpdate update = bundled.get(node.getMapID());
                            if (update != null && !update.isFreed()) {
                                frameCache.put(key, update);
                                if (cFrame == 0) {
                                    baseFrames.put(node.getMapID(), update);
                                    update.retain();
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        loadingFrames.remove(cFrame);
                    }
                }, vtExecutor);
            }
            return;
        }

        Set<UUID> usersFullySent = new HashSet<>(visiblePlayers);

        for (FrameNode node : nodes) {
            long cacheKey = ((long) currentFrameIndex << 32) | (node.getMapID() & 0xFFFFFFFFL);
            MapFrameUpdate update = frameCache.getIfPresent(cacheKey);
            if (update == null || update.isFreed()) {
                if (currentFrameIndex == 0) {
                    update = baseFrames.get(node.getMapID());
                }
            }

            if (update != null && !update.isFreed()) {
                for (UUID uuid : visiblePlayers) {
                    boolean isNewUser = !initializedUsers.contains(uuid);

                    if (isNewUser && currentFrameIndex != 0) {
                        MapFrameUpdate base = baseFrames.get(node.getMapID());
                        if (base == null || base.isFreed()) {
                            try {
                                Map<Integer, MapFrameUpdate> reloaded = flatFileStorage.loadBundledFrame(metadata.syncGroupID(), 0);
                                for (Map.Entry<Integer, MapFrameUpdate> entry : reloaded.entrySet()) {
                                    MapFrameUpdate reloadedUpdate = entry.getValue();
                                    baseFrames.put(entry.getKey(), reloadedUpdate);
                                    reloadedUpdate.retain();
                                }
                                base = baseFrames.get(node.getMapID());
                            } catch (Exception ignored) {}
                        }
                        if (base != null && !base.isFreed()) {
                            for (DeltaFrame subChunk : base.parts()) {
                                com.github.retrooper.packetevents.wrapper.PacketWrapper<?> pkt = sender.createMapPacket(subChunk);
                                if (pkt != null) {
                                    renderManager.enqueuePacket(uuid, pkt);
                                }
                            }
                        }
                    }

                    if (isNewUser || metadata.isAnimated()) {
                        for (DeltaFrame subChunk : update.parts()) {
                            com.github.retrooper.packetevents.wrapper.PacketWrapper<?> pkt = sender.createMapPacket(subChunk);
                            if (pkt != null) {
                                renderManager.enqueuePacket(uuid, pkt);
                            }
                        }
                    }
                }
            } else {
                usersFullySent.clear();
            }
        }

        initializedUsers.addAll(usersFullySent);

        lastTickTime = currentTimeMillis;

        if (metadata.isAnimated()) {
            currentFrameIndex++;
            if (currentFrameIndex >= metadata.totalFrames()) {
                currentFrameIndex = 0;
            }

            int nextFrame = currentFrameIndex;
            boolean missingNext = false;
            for (FrameNode node : nodes) {
                if (frameCache.getIfPresent(((long) nextFrame << 32) | (node.getMapID() & 0xFFFFFFFFL)) == null) {
                    missingNext = true; break;
                }
            }

            if (missingNext && !loadingFrames.contains(nextFrame)) {
                loadingFrames.add(nextFrame);
                final int nxtFrame = nextFrame;
                CompletableFuture.runAsync(() -> {
                    try {
                        Map<Integer, MapFrameUpdate> bundled = flatFileStorage.loadBundledFrame(metadata.syncGroupID(), nxtFrame);
                        for (FrameNode node : nodes) {
                            long key = ((long) nxtFrame << 32) | (node.getMapID() & 0xFFFFFFFFL);
                            MapFrameUpdate update = bundled.get(node.getMapID());
                            frameCache.put(key, update != null ? update : new MapFrameUpdate(new DeltaFrame[0]));
                        }
                    } catch (Exception ignored) {
                    } finally {
                        loadingFrames.remove(nxtFrame);
                    }
                }, vtExecutor);
            }
        } else {
            currentFrameIndex = 0;
        }
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
                long key = ((long) i << 32) | (node.getMapID() & 0xFFFFFFFFL);
                frameCache.invalidate(key);
            }
        }
    }
}
