package net.ed1thy.emage.render;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.retrooper.packetevents.protocol.player.User;
import io.netty.channel.Channel;
import net.ed1thy.emage.Emage;
import net.ed1thy.emage.config.ConfigManager;
import net.ed1thy.emage.model.DeltaFrame;
import net.ed1thy.emage.model.FrameNode;
import net.ed1thy.emage.model.MapFrameUpdate;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class RenderManager {

    private final Emage plugin;
    private final ChunkViewerTracker chunkViewerTracker;
    private final PacketSender packetSender;

    private final int maxPacketsPerTick;
    private final double viewDistance;
    private final double animatedViewDistance;

    private final PlayerSendQueue.Tuning tuning;
    private final double initialRate;
    private final SendBudget totalBudget;
    private int rotation;

    private final ReentrantLock renderLock = new ReentrantLock();

    private final Map<Integer, SyncGroup> activeGroups = new ConcurrentHashMap<>();
    private final Queue<SpoofTask> pendingSpoofs = new ConcurrentLinkedQueue<>();
    private final Queue<PurgeRequest> pendingPurges = new ConcurrentLinkedQueue<>();
    private final Map<User, PlayerSendQueue> userPacketQueues = new ConcurrentHashMap<>();

    private final Cache<Long, MapFrameUpdate> globalFrameCache;
    private final ScheduledExecutorService executorService;
    private ScheduledFuture<?> tickTask;
    private BukkitTask visibilityTask;

    public record SpoofTask(@NotNull User user, @NotNull FrameNode node) {}

    private record PurgeRequest(@NotNull UUID uuid, @NotNull Set<Integer> mapIds, @NotNull SyncGroup group) {}

    public RenderManager(@NotNull Emage plugin, @NotNull ChunkViewerTracker chunkViewerTracker, @NotNull PacketSender packetSender, @NotNull ConfigManager configManager, @NotNull ScheduledExecutorService scheduledExecutor) {
        this.plugin = plugin;
        this.chunkViewerTracker = chunkViewerTracker;
        this.packetSender = packetSender;
        this.maxPacketsPerTick = configManager.maxPacketsPerTick;
        this.viewDistance = configManager.viewDistanceBlocks > 0 ? (double) configManager.viewDistanceBlocks : 96.0;
        this.animatedViewDistance = configManager.animatedViewDistanceBlocks;
        this.tuning = new PlayerSendQueue.Tuning(
                configManager.minBytesPerSecondPerPlayer,
                configManager.maxBytesPerSecondPerPlayer,
                configManager.maxPendingBytesPerPlayer,
                configManager.targetQueueDelayMs,
                configManager.latencyProbe);
        this.initialRate = PlayerSendQueue.initialRate(tuning);
        this.totalBudget = new SendBudget(configManager.maxTotalBytesPerSecond);
        this.executorService = scheduledExecutor;

        long maxBytes = (long) configManager.cacheMaxMemoryMb * 1024L * 1024L;
        this.globalFrameCache = Caffeine.newBuilder()
                .maximumWeight(maxBytes)
                .weigher((Long key, MapFrameUpdate value) -> value.totalBytes())
                .expireAfterAccess(configManager.cacheExpireMinutes, TimeUnit.MINUTES)
                .removalListener((Long key, MapFrameUpdate value, RemovalCause cause) -> {
                    if (value != null) {
                        value.freeMemory();
                    }
                })
                .build();
    }

    public ChunkViewerTracker getChunkViewerTracker() {
        return chunkViewerTracker;
    }

    public Cache<Long, MapFrameUpdate> getGlobalFrameCache() {
        return globalFrameCache;
    }

    public double getViewDistance() {
        return viewDistance;
    }

    public double getAnimatedViewDistance() {
        return animatedViewDistance;
    }

    public void start() {
        tickTask = executorService.scheduleWithFixedDelay(this::tickAll, 20, 20, TimeUnit.MILLISECONDS);

        visibilityTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (SyncGroup group : activeGroups.values()) {
                group.updateVisibility();
            }
        }, 5L, 5L);
    }

    private void tickAll() {
        renderLock.lock();
        try {
            long currentTime = System.currentTimeMillis();

            processPurges();
            processSpoofs();

            for (SyncGroup group : activeGroups.values()) {
                try {
                    if (group.shouldTick(currentTime)) {
                        group.tick(currentTime, this);
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to tick a picture group: " + e);
                }
            }

            long now = System.nanoTime();
            totalBudget.refill(now);

            List<Map.Entry<User, PlayerSendQueue>> entries = new ArrayList<>(userPacketQueues.entrySet());
            int count = entries.size();
            int start = count == 0 ? 0 : Math.floorMod(rotation++, count);
            for (int i = 0; i < count; i++) {
                Map.Entry<User, PlayerSendQueue> entry = entries.get((start + i) % count);
                User user = entry.getKey();
                PlayerSendQueue queue = entry.getValue();

                if (queue.isClosed()) {
                    queue.drain();
                    userPacketQueues.remove(user, queue);
                    continue;
                }

                Object rawChannel = user.getChannel();
                if (!(rawChannel instanceof Channel channel) || !channel.isActive()) {
                    queue.drain();
                    userPacketQueues.remove(user, queue);
                    continue;
                }

                try {
                    queue.flush(user, channel, packetSender, totalBudget, now);
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to flush map packets for " + user.getUUID() + ": " + e);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Render Thread Error: " + e);
            e.printStackTrace();
        } finally {
            renderLock.unlock();
        }
    }

    private void processSpoofs() {
        int budget = Math.max(200, maxPacketsPerTick * 5);
        int candidates = Math.min(pendingSpoofs.size(), budget);
        if (candidates == 0) return;

        Set<User> touched = new HashSet<>();
        for (int i = 0; i < candidates; i++) {
            SpoofTask task = pendingSpoofs.poll();
            if (task == null) break;

            Object rawChannel = task.user().getChannel();
            if (!(rawChannel instanceof Channel channel) || !channel.isActive()) {
                continue;
            }
            if (!channel.isWritable()) {
                pendingSpoofs.add(task);
                continue;
            }
            try {
                packetSender.writeSpoofItemFrameMap(task.user(), task.node());
                touched.add(task.user());
            } catch (Exception ignored) {
            }
        }
        for (User user : touched) {
            try {
                user.flushPackets();
            } catch (Exception ignored) {
            }
        }
    }

    private void processPurges() {
        PurgeRequest request;
        int budget = 256;
        while (budget-- > 0 && (request = pendingPurges.poll()) != null) {
            User user = chunkViewerTracker.getUser(request.uuid());
            if (user == null) continue;
            PlayerSendQueue queue = userPacketQueues.get(user);
            if (queue == null || queue.isEmpty()) continue;
            Set<Integer> dropped = queue.purge(request.mapIds());
            if (!dropped.isEmpty()) {
                request.group().forgetMaps(request.uuid(), dropped);
            }
        }
    }

    public boolean enqueueMap(@NotNull UUID uuid, @NotNull DeltaFrame part, int tier) {
        User user = chunkViewerTracker.getUser(uuid);
        if (user == null) return false;
        return userPacketQueues.computeIfAbsent(user, k -> new PlayerSendQueue(tuning)).add(part, tier);
    }

    public boolean enqueueUpdate(@NotNull UUID uuid, @NotNull MapFrameUpdate update, int tier) {
        boolean all = true;
        for (DeltaFrame part : update.parts()) {
            if (!enqueueMap(uuid, part, tier)) all = false;
        }
        return all;
    }

    public double animationDrainSeconds(@NotNull UUID uuid, long extraBytes) {
        User user = chunkViewerTracker.getUser(uuid);
        PlayerSendQueue queue = user == null ? null : userPacketQueues.get(user);
        if (queue == null || queue.isClosed()) {
            return Math.max(0L, extraBytes) / initialRate;
        }
        return queue.urgentDrainSeconds(extraBytes);
    }

    public long stillBudget(@NotNull UUID uuid, int tier) {
        User user = chunkViewerTracker.getUser(uuid);
        if (user == null) return 0;
        PlayerSendQueue queue = userPacketQueues.get(user);
        if (queue == null) return (long) PlayerSendQueue.lookaheadFor(initialRate);
        if (queue.isClosed()) return 0;
        return (long) (queue.lookaheadBytes() - queue.stillBytesUpToTier(tier));
    }

    public List<String> debugLines() {
        List<String> lines = new ArrayList<>();
        lines.add("Emage: " + activeGroups.size() + " picture group(s), frame cache "
                + globalFrameCache.estimatedSize() + " entries, total send limit "
                + (long) (totalBudget.rate() / 1024) + " KB/s, " + userPacketQueues.size() + " active sender(s)");
        for (Map.Entry<User, PlayerSendQueue> entry : userPacketQueues.entrySet()) {
            String name = entry.getKey().getProfile() != null ? entry.getKey().getProfile().getName() : String.valueOf(entry.getKey().getUUID());
            lines.add(name + ": " + entry.getValue().describe());
        }
        return lines;
    }

    public boolean hasPendingPackets(@NotNull UUID uuid) {
        User user = chunkViewerTracker.getUser(uuid);
        if (user == null) return false;
        PlayerSendQueue queue = userPacketQueues.get(user);
        return queue != null && !queue.isEmpty();
    }

    public void requestPurge(@NotNull UUID uuid, @NotNull Set<Integer> mapIds, @NotNull SyncGroup group) {
        pendingPurges.add(new PurgeRequest(uuid, mapIds, group));
    }

    public boolean onPong(@NotNull User user, int id) {
        if (!PlayerSendQueue.isProbeId(id)) return false;
        PlayerSendQueue queue = userPacketQueues.get(user);
        if (queue != null) queue.onPong(id);
        return true;
    }

    public void clearQueue(@NotNull UUID uuid) {
        User user = chunkViewerTracker.getUser(uuid);
        if (user != null) {
            PlayerSendQueue queue = userPacketQueues.get(user);
            if (queue != null) queue.close();
        }
        for (Map.Entry<User, PlayerSendQueue> entry : userPacketQueues.entrySet()) {
            if (entry.getKey().getUUID().equals(uuid)) {
                entry.getValue().close();
            }
        }
    }

    public void onPlayerQuit(@NotNull UUID uuid) {
        clearQueue(uuid);
        for (SyncGroup group : activeGroups.values()) {
            group.onPlayerQuit(uuid);
        }
    }

    public void onPlayerJoin(@NotNull org.bukkit.entity.Player player) {
        for (SyncGroup group : activeGroups.values()) {
            group.checkPlayerVisibility(player);
        }
    }

    public void reset() {
        renderLock.lock();
        try {
            for (SyncGroup group : activeGroups.values()) {
                group.cleanup();
            }
            activeGroups.clear();
            pendingSpoofs.clear();
            pendingPurges.clear();

            for (PlayerSendQueue queue : userPacketQueues.values()) {
                queue.drain();
            }
            userPacketQueues.clear();
            globalFrameCache.invalidateAll();
        } finally {
            renderLock.unlock();
        }
    }

    public void queueSpoof(@NotNull User user, @NotNull FrameNode node) {
        pendingSpoofs.add(new SpoofTask(user, node));
    }

    public void sendNodeToUser(@NotNull User user, @NotNull FrameNode node) {
        UUID uuid = user.getUUID();
        int mapId = node.getMapID();

        for (SyncGroup group : activeGroups.values()) {
            if (!group.ownsMap(mapId)) continue;
            if (group.isNodeInitialized(uuid, mapId)) return;
        }

        queueSpoof(user, node);
    }

    public void resetUserState(@NotNull UUID uuid) {
        for (SyncGroup group : activeGroups.values()) {
            group.clearUserState(uuid);
        }
    }

    public void invalidateNodesForUser(@NotNull UUID uuid, @NotNull List<FrameNode> nodes) {
        for (SyncGroup group : activeGroups.values()) {
            group.invalidateNodes(uuid, nodes);
        }
    }

    public void registerSyncGroup(int syncGroupID, @NotNull SyncGroup group) {
        group.attach(this);
        group.updateVisibility();
        activeGroups.put(syncGroupID, group);
    }

    public void unregisterSyncGroup(int syncGroupID) {
        SyncGroup group = activeGroups.remove(syncGroupID);
        if (group != null) {
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, group::cleanup, 2L);
        }
    }

    public SyncGroup getSyncGroup(int syncGroupID) {
        return activeGroups.get(syncGroupID);
    }

    public void shutdown() {
        if (visibilityTask != null) visibilityTask.cancel();
        if (tickTask != null) tickTask.cancel(false);

        renderLock.lock();
        try {
            for (SyncGroup group : activeGroups.values()) {
                group.cleanup();
            }

            activeGroups.clear();
            pendingSpoofs.clear();
            pendingPurges.clear();

            for (PlayerSendQueue queue : userPacketQueues.values()) {
                queue.drain();
            }
            userPacketQueues.clear();
        } finally {
            renderLock.unlock();
        }
    }
}
