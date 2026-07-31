package net.ed1thy.emage.render;

import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import net.ed1thy.emage.model.DeltaFrame;
import net.ed1thy.emage.model.FrameNode;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;

public class PacketSender {

    @NotNull
    public PacketWrapper<?> createMapPacket(@NotNull DeltaFrame delta) {
        // Create a private copy of the data for this specific player.
        // This avoids the race condition where the shared source buffer
        // gets freed by one player before another player's write() runs.
        ByteBuf src = delta.packetBuf();
        if (src == null || src.refCnt() <= 0) {
            return new ZeroCopyMapWrapper(new DeltaFrame(delta.frameIndex(), delta.mapId(), null));
        }
        ByteBuf copy = PooledByteBufAllocator.DEFAULT.directBuffer(src.readableBytes());
        copy.writeBytes(src, src.readerIndex(), src.readableBytes());
        return new ZeroCopyMapWrapper(new DeltaFrame(delta.frameIndex(), delta.mapId(), copy));
    }

    public void spoofItemFrameMap(@NotNull User user, @NotNull FrameNode node) {
        Equipment equipment = new Equipment(EquipmentSlot.MAIN_HAND, node.getCachedItem());
        WrapperPlayServerEntityEquipment equipmentPacket = new WrapperPlayServerEntityEquipment(
                node.getEntityID(),
                Collections.singletonList(equipment)
        );
        user.sendPacket(equipmentPacket);
    }
}