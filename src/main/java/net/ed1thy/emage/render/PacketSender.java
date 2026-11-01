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
import org.jetbrains.annotations.Nullable;

import java.util.Collections;

public class PacketSender {

    @Nullable
    public PacketWrapper<?> createMapPacket(@NotNull DeltaFrame delta) {
        ByteBuf src = delta.packetBuf();
        if (src == null || src.refCnt() <= 0 || src.readableBytes() <= 0) {
            return null;
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