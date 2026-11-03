package net.ed1thy.emage.listener;

import com.github.retrooper.packetevents.protocol.player.User;
import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import io.papermc.paper.event.packet.PlayerChunkUnloadEvent;
import net.ed1thy.emage.Emage;
import net.ed1thy.emage.model.FrameNode;
import net.ed1thy.emage.render.ChunkViewerTracker;
import net.ed1thy.emage.render.RenderManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class ChunkTrackerListener implements Listener {

    private final Emage plugin;
    private final ChunkViewerTracker tracker;
    private final RenderManager renderManager;

    private final ConcurrentHashMap<Long, List<FrameNode>> chunkNodeCache = new ConcurrentHashMap<>();

    public ChunkTrackerListener(@NotNull Emage plugin, @NotNull ChunkViewerTracker tracker, @NotNull RenderManager renderManager) {
        this.plugin = plugin;
        this.tracker = tracker;
        this.renderManager = renderManager;
    }

    public void addNodeToCache(@NotNull FrameNode node) {
        List<FrameNode> list = chunkNodeCache.computeIfAbsent(node.getChunkKey(), k -> new CopyOnWriteArrayList<>());
        list.removeIf(existing -> existing.getFrameUUID().equals(node.getFrameUUID()));
        list.add(node);
    }

    public void removeNodeFromCache(@NotNull FrameNode node) {
        removeNodeFromCache(node.getFrameUUID(), node.getChunkKey());
    }

    public void removeNodeFromCache(@NotNull UUID frameUuid, long chunkKey) {
        List<FrameNode> nodes = chunkNodeCache.get(chunkKey);
        if (nodes != null) {
            nodes.removeIf(n -> n.getFrameUUID().equals(frameUuid));
            if (nodes.isEmpty()) {
                chunkNodeCache.remove(chunkKey);
            }
        }
    }

    public void clearCache() {
        chunkNodeCache.clear();
    }

    public ChunkViewerTracker getTracker() {
        return tracker;
    }

    private void initPlayerChunks(Player player, User user) {
        UUID uuid = player.getUniqueId();
        Set<Long> processedKeys = new HashSet<>();

        try {
            for (long chunkKey : player.getSentChunkKeys()) {
                processedKeys.add(chunkKey);
                tracker.addViewer(chunkKey, uuid);
                List<FrameNode> nodesInChunk = chunkNodeCache.get(chunkKey);
                if (nodesInChunk != null && !nodesInChunk.isEmpty()) {
                    for (FrameNode node : nodesInChunk) {
                        renderManager.sendNodeToUser(user, node);
                    }
                }
            }
        } catch (Throwable ignored) {}

        if (player.isOnline()) {
            int pcx = player.getLocation().getBlockX() >> 4;
            int pcz = player.getLocation().getBlockZ() >> 4;
            int vd = Math.min(8, Bukkit.getViewDistance());
            for (int dx = -vd; dx <= vd; dx++) {
                for (int dz = -vd; dz <= vd; dz++) {
                    long chunkKey = ((long) (pcx + dx) & 0xFFFFFFFFL) | (((long) (pcz + dz) & 0xFFFFFFFFL) << 32);
                    if (processedKeys.add(chunkKey)) {
                        tracker.addViewer(chunkKey, uuid);
                        List<FrameNode> nodesInChunk = chunkNodeCache.get(chunkKey);
                        if (nodesInChunk != null && !nodesInChunk.isEmpty()) {
                            for (FrameNode node : nodesInChunk) {
                                renderManager.sendNodeToUser(user, node);
                            }
                        }
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        renderManager.onPlayerQuit(uuid);
        tracker.removeViewerFromAll(uuid);
        tracker.registerPlayer(player);

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;

            User user = tracker.getUser(uuid);
            if (user == null) {
                tracker.registerPlayer(player);
                user = tracker.getUser(uuid);
            }
            if (user == null) return;

            initPlayerChunks(player, user);
            renderManager.onPlayerJoin(player);
        });

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            User user = tracker.getUser(uuid);
            if (user != null) {
                initPlayerChunks(player, user);
                renderManager.onPlayerJoin(player);
            }
        }, 10L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChunkLoad(PlayerChunkLoadEvent event) {
        long chunkKey = event.getChunk().getChunkKey();
        UUID uuid = event.getPlayer().getUniqueId();
        tracker.addViewer(chunkKey, uuid);

        User user = tracker.getUser(uuid);
        if (user == null) {
            tracker.registerPlayer(event.getPlayer());
            user = tracker.getUser(uuid);
        }
        if (user == null) return;

        List<FrameNode> nodesInChunk = chunkNodeCache.get(chunkKey);
        if (nodesInChunk != null && !nodesInChunk.isEmpty()) {
            for (FrameNode node : nodesInChunk) {
                renderManager.sendNodeToUser(user, node);
            }
            renderManager.onPlayerJoin(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                User user = tracker.getUser(player.getUniqueId());
                if (user != null) {
                    initPlayerChunks(player, user);
                }
                renderManager.onPlayerJoin(player);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                User user = tracker.getUser(player.getUniqueId());
                if (user != null) {
                    initPlayerChunks(player, user);
                }
                renderManager.onPlayerJoin(player);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChunkUnload(PlayerChunkUnloadEvent event) {
        long chunkKey = event.getChunk().getChunkKey();
        UUID uuid = event.getPlayer().getUniqueId();
        tracker.removeViewer(chunkKey, uuid);

        List<FrameNode> nodesInChunk = chunkNodeCache.get(chunkKey);
        if (nodesInChunk != null && !nodesInChunk.isEmpty()) {
            renderManager.invalidateNodesForUser(uuid, nodesInChunk);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        tracker.removeViewerFromAll(uuid);
        renderManager.onPlayerQuit(uuid);
    }
}
