package net.ed1thy.emage.listener;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong;
import net.ed1thy.emage.render.RenderManager;
import org.jetbrains.annotations.NotNull;

public class LatencyProbeListener extends PacketListenerAbstract {

    private final RenderManager renderManager;

    public LatencyProbeListener(@NotNull RenderManager renderManager) {
        super(PacketListenerPriority.LOWEST);
        this.renderManager = renderManager;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.PONG) return;
        try {
            int id = new WrapperPlayClientPong(event).getId();
            if (renderManager.onPong(event.getUser(), id)) {
                event.setCancelled(true);
            }
        } catch (Exception ignored) {
        }
    }
}
