package net.ed1thy.emage.render;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import io.netty.channel.Channel;
import net.ed1thy.emage.model.DeltaFrame;
import org.jetbrains.annotations.NotNull;

final class NettyTransport implements Transport {

    private final User user;
    private final Channel channel;
    private final PacketSender sender;

    NettyTransport(@NotNull User user, @NotNull Channel channel, @NotNull PacketSender sender) {
        this.user = user;
        this.channel = channel;
        this.sender = sender;
    }

    @Override
    public boolean isWritable() {
        return channel.isWritable();
    }

    @Override
    public long pendingBytes() {
        long high = channel.config().getWriteBufferHighWaterMark();
        return Math.max(0L, high - channel.bytesBeforeUnwritable());
    }

    @Override
    public boolean write(@NotNull DeltaFrame source) {
        PacketWrapper<?> packet = sender.createMapPacket(source);
        if (packet == null) return false;
        try {
            user.writePacketSilently(packet);
            return true;
        } catch (Exception e) {
            if (packet instanceof ZeroCopyMapWrapper map) {
                map.getDelta().freeMemory();
            }
            return false;
        }
    }

    @Override
    public void flush() {
        user.flushPackets();
    }

    @Override
    public boolean supportsProbe() {
        ClientVersion version = user.getClientVersion();
        return version == ClientVersion.UNKNOWN || version.isNewerThanOrEquals(ClientVersion.V_1_17);
    }

    @Override
    public boolean sendProbe(int id) {
        try {
            user.sendPacketSilently(new WrapperPlayServerPing(id));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
