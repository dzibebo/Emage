package net.ed1thy.emage.listener;

import net.ed1thy.emage.Emage;
import net.ed1thy.emage.config.MessageManager;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

public class FrameInteractListener implements Listener {

    private final NamespacedKey emageKey;
    private final NamespacedKey loadingKey;
    private final MessageManager messageManager;

    public FrameInteractListener(@NotNull Emage plugin, @NotNull MessageManager messageManager) {
        this.emageKey = new NamespacedKey(plugin, "emage_map_id");
        this.loadingKey = new NamespacedKey(plugin, "emage_loading");
        this.messageManager = messageManager;
    }

    @NotNull
    public NamespacedKey getEmageKey() {
        return emageKey;
    }

    @NotNull
    public NamespacedKey getLoadingKey() {
        return loadingKey;
    }

    private boolean isProtected(ItemFrame frame) {
        return frame.getPersistentDataContainer().has(emageKey, PersistentDataType.INTEGER)
                || frame.getPersistentDataContainer().has(loadingKey, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFrameInteract(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof ItemFrame frame) {
            if (isProtected(frame)) {
                event.setCancelled(true);
                messageManager.sendProtectedFrame(event.getPlayer());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFrameDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof ItemFrame frame) {
            if (isProtected(frame)) {
                event.setCancelled(true);

                if (event.getDamager() instanceof Player player) {
                    messageManager.sendProtectedFrame(player);
                } else if (event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player player) {
                    messageManager.sendProtectedFrame(player);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFrameDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof ItemFrame frame) {
            if (isProtected(frame)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        if (event.getEntity() instanceof ItemFrame frame) {
            if (isProtected(frame)) {
                event.setCancelled(true);
            }
        }
    }
}