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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.util.List;
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

            try {
                for (long chunkKey : player.getSentChunkKeys()) {
                    tracker.addViewer(chunkKey, uuid);
                    List<FrameNode> nodesInChunk = chunkNodeCache.get(chunkKey);
                    if (nodesInChunk != null) {
                        for (FrameNode node : nodesInChunk) {
                            renderManager.queueSpoof(user, node);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            renderManager.onPlayerJoin(player);
        });
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
        if (nodesInChunk != null) {
            for (FrameNode node : nodesInChunk) {
                renderManager.queueSpoof(user, node);
            }
            renderManager.onPlayerJoin(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                renderManager.onPlayerJoin(player);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(org.bukkit.event.player.PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                renderManager.onPlayerJoin(player);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChunkUnload(PlayerChunkUnloadEvent event) {
        long chunkKey = event.getChunk().getChunkKey();
        tracker.removeViewer(chunkKey, event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        renderManager.onPlayerQuit(uuid);
        tracker.removeViewerFromAll(uuid);
    }
}
