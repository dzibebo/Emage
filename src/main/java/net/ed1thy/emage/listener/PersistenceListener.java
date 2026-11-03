package net.ed1thy.emage.listener;

import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import net.ed1thy.emage.Emage;
import net.ed1thy.emage.config.ConfigManager;
import net.ed1thy.emage.model.FrameNode;
import net.ed1thy.emage.model.MapMetadata;
import net.ed1thy.emage.render.RenderManager;
import net.ed1thy.emage.render.SyncGroup;
import net.ed1thy.emage.storage.FlatFileStorage;
import net.ed1thy.emage.storage.MapMetadataRepository;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;

public class PersistenceListener implements Listener {

    private final Emage plugin;
    private final FrameInteractListener interactListener;
    private final MapMetadataRepository repository;
    private final RenderManager renderManager;
    private final ChunkTrackerListener chunkTrackerListener;
    private final ConfigManager configManager;
    private final FlatFileStorage flatFileStorage;
    private final ExecutorService vtExecutor;

    public PersistenceListener(@NotNull Emage plugin, @NotNull FrameInteractListener interactListener,
                               @NotNull MapMetadataRepository repository, @NotNull RenderManager renderManager,
                               @NotNull ChunkTrackerListener chunkTrackerListener, @NotNull ConfigManager configManager,
                               @NotNull FlatFileStorage flatFileStorage, @NotNull ExecutorService vtExecutor) {
        this.plugin = plugin;
        this.interactListener = interactListener;
        this.repository = repository;
        this.renderManager = renderManager;
        this.chunkTrackerListener = chunkTrackerListener;
        this.configManager = configManager;
        this.flatFileStorage = flatFileStorage;
        this.vtExecutor = vtExecutor;
    }

    public void restoreLoadedChunks() {
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                List<ItemFrame> framesInChunk = new ArrayList<>();
                for (Entity entity : chunk.getEntities()) {
                    if (entity instanceof ItemFrame frame) {
                        if (frame.getPersistentDataContainer().has(interactListener.getEmageKey(), PersistentDataType.INTEGER)) {
                            framesInChunk.add(frame);
                        }
                    }
                }
                if (!framesInChunk.isEmpty()) {
                    processLoadedFrames(framesInChunk, chunk);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        List<ItemFrame> framesInChunk = new ArrayList<>();
        for (Entity entity : event.getEntities()) {
            if (entity instanceof ItemFrame frame) {
                if (frame.getPersistentDataContainer().has(interactListener.getEmageKey(), PersistentDataType.INTEGER)) {
                    framesInChunk.add(frame);
                }
            }
        }
        if (!framesInChunk.isEmpty()) {
            processLoadedFrames(framesInChunk, event.getChunk());
        }
    }

    public void processLoadedFrames(List<ItemFrame> frames, org.bukkit.Chunk chunk) {
        List<ItemFrame> loadedFrames = new ArrayList<>();
        List<FrameNode> createdNodes = new ArrayList<>();
        List<Integer> loadedMapIds = new ArrayList<>();

        for (ItemFrame frame : frames) {
            if (frame.getPersistentDataContainer().has(interactListener.getEmageKey(), PersistentDataType.INTEGER)) {
                Integer mapId = frame.getPersistentDataContainer().get(interactListener.getEmageKey(), PersistentDataType.INTEGER);
                if (mapId == null) continue;

                loadedFrames.add(frame);
                loadedMapIds.add(mapId);

                ItemStack bukkitMap = new ItemStack(Material.FILLED_MAP);
                if (bukkitMap.getItemMeta() instanceof MapMeta mapMeta) {
                    mapMeta.setMapId(mapId);
                    bukkitMap.setItemMeta(mapMeta);
                }
                boolean needUpdate = false;
                if (frame.getItem().getType() != Material.FILLED_MAP) {
                    needUpdate = true;
                } else if (frame.getItem().getItemMeta() instanceof MapMeta existingMeta) {
                    if (!existingMeta.hasMapId() || existingMeta.getMapId() != mapId) {
                        needUpdate = true;
                    }
                } else {
                    needUpdate = true;
                }
                if (needUpdate) {
                    frame.setItem(bukkitMap);
                }
                if (frame.isVisible()) {
                    frame.setVisible(false);
                }
                com.github.retrooper.packetevents.protocol.item.ItemStack peItem = SpigotConversionUtil.fromBukkitItemStack(bukkitMap);

                FrameNode node = new FrameNode(frame.getEntityId(), frame.getUniqueId(), frame.getWorld().getUID(),
                        frame.getLocation().getChunk().getX(), frame.getLocation().getChunk().getZ(),
                        frame.getLocation().getBlockX(), frame.getLocation().getBlockY(), frame.getLocation().getBlockZ(), mapId, peItem);

                chunkTrackerListener.addNodeToCache(node);
                createdNodes.add(node);
            }
        }

        if (loadedFrames.isEmpty()) return;

        UUID worldUuid = chunk.getWorld().getUID();
        int cx = chunk.getX();
        int cz = chunk.getZ();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<UUID> dbExpected = repository.getPlacedFramesInChunk(worldUuid, cx, cz);

                for (int i = 0; i < loadedFrames.size(); i++) {
                    ItemFrame frame = loadedFrames.get(i);

                    if (!dbExpected.contains(frame.getUniqueId())) {
                        int mapId = loadedMapIds.get(i);
                        Optional<MapMetadata> metaOpt = repository.getMetadataByMapId(mapId);
                        metaOpt.ifPresent(meta -> {
                            try { repository.addPlacedFrame(meta.syncGroupID(), frame); } catch (Exception ignored) {}
                        });
                    }
                }

                Map<Integer, List<FrameNode>> nodesByGroup = new HashMap<>();
                Map<Integer, MapMetadata> metaByGroup = new HashMap<>();
                List<ItemFrame> brokenFrames = new ArrayList<>();

                for (int i = 0; i < loadedFrames.size(); i++) {
                    ItemFrame frame = loadedFrames.get(i);
                    FrameNode node = createdNodes.get(i);
                    int mapId = node.getMapID();

                    Optional<MapMetadata> metaOpt = repository.getMetadataByMapId(mapId);
                    if (metaOpt.isPresent() && flatFileStorage.groupExists(metaOpt.get().syncGroupID())) {
                        MapMetadata meta = metaOpt.get();
                        nodesByGroup.computeIfAbsent(meta.syncGroupID(), k -> new ArrayList<>()).add(node);
                        metaByGroup.putIfAbsent(meta.syncGroupID(), meta);
                    } else {
                        brokenFrames.add(frame);
                    }
                }

                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (Map.Entry<Integer, List<FrameNode>> entry : nodesByGroup.entrySet()) {
                        int groupId = entry.getKey();
                        List<FrameNode> groupNodes = entry.getValue();
                        MapMetadata meta = metaByGroup.get(groupId);
                        if (meta == null) continue;

                        SyncGroup group = renderManager.getSyncGroup(groupId);
                        if (group == null) {
                            group = new SyncGroup(meta, new CopyOnWriteArrayList<>(groupNodes), flatFileStorage, renderManager.getGlobalFrameCache(), vtExecutor, null, renderManager.getViewDistance());
                            renderManager.registerSyncGroup(groupId, group);
                        } else {
                            List<FrameNode> toAdd = new ArrayList<>();
                            for (FrameNode node : groupNodes) {
                                boolean exists = false;
                                for (FrameNode existing : group.getNodes()) {
                                    if (existing.getFrameUUID().equals(node.getFrameUUID())) {
                                        exists = true;
                                        break;
                                    }
                                }
                                if (!exists) {
                                    toAdd.add(node);
                                }
                            }
                            if (!toAdd.isEmpty()) {
                                group.addNewWall(toAdd);
                            }
                        }
                    }

                    for (Player player : chunk.getWorld().getPlayers()) {
                        int pcx = player.getLocation().getBlockX() >> 4;
                        int pcz = player.getLocation().getBlockZ() >> 4;
                        if (Math.abs(pcx - cx) <= 8 && Math.abs(pcz - cz) <= 8) {
                            com.github.retrooper.packetevents.protocol.player.User user = chunkTrackerListener.getTracker().getUser(player.getUniqueId());
                            if (user != null) {
                                for (FrameNode node : createdNodes) {
                                    renderManager.sendNodeToUser(user, node);
                                }
                            }
                        }
                    }

                    for (ItemFrame frame : brokenFrames) {
                        frame.getPersistentDataContainer().remove(interactListener.getEmageKey());
                        frame.getPersistentDataContainer().remove(interactListener.getLoadingKey());
                        frame.setItem(new ItemStack(Material.AIR));
                        frame.setVisible(true);
                        chunkTrackerListener.removeNodeFromCache(frame.getUniqueId(), chunk.getChunkKey());
                        try {
                            repository.removePlacedFrameByUUID(frame.getUniqueId());
                        } catch (Exception ignored) {}
                    }
                });

            } catch (Exception e) {
                plugin.getLogger().warning("Failed GC healing / restore for Emage chunk: " + e.getMessage());
            }
        });
    }
}
