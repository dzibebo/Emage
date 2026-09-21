package net.ed1thy.emage.listener;

import com.github.retrooper.packetevents.protocol.player.User;
import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import io.papermc.paper.event.packet.PlayerChunkUnloadEvent;
import net.ed1thy.emage.model.FrameNode;
import net.ed1thy.emage.render.ChunkViewerTracker;
import net.ed1thy.emage.render.RenderManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class ChunkTrackerListener implements Listener {

    private final ChunkViewerTracker tracker;
    private final RenderManager renderManager;

    private final ConcurrentHashMap<Long, List<FrameNode>> chunkNodeCache = new ConcurrentHashMap<>();

    public ChunkTrackerListener(@NotNull ChunkViewerTracker tracker, @NotNull RenderManager renderManager) {
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
        tracker.registerPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChunkLoad(PlayerChunkLoadEvent event) {
        long chunkKey = event.getChunk().getChunkKey();
        tracker.addViewer(chunkKey, event.getPlayer().getUniqueId());

        User user = tracker.getUser(event.getPlayer().getUniqueId());
        if (user == null) return;

        List<FrameNode> nodesInChunk = chunkNodeCache.get(chunkKey);
        if (nodesInChunk != null) {
            for (FrameNode node : nodesInChunk) {
                renderManager.queueSpoof(user, node);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChunkUnload(PlayerChunkUnloadEvent event) {
        long chunkKey = event.getChunk().getChunkKey();
        tracker.removeViewer(chunkKey, event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        tracker.removeViewerFromAll(event.getPlayer().getUniqueId());
    }
}