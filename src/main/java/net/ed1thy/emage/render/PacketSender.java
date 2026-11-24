package net.ed1thy.emage.render;

import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import io.netty.buffer.ByteBuf;
import io.netty.util.IllegalReferenceCountException;
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
        ByteBuf view;
        try {
            view = src.retainedDuplicate();
        } catch (IllegalReferenceCountException e) {
            return null;
        }
        return new ZeroCopyMapWrapper(new DeltaFrame(delta.frameIndex(), delta.mapId(), view));
    }

    public void writeSpoofItemFrameMap(@NotNull User user, @NotNull FrameNode node) {
        Equipment equipment = new Equipment(EquipmentSlot.MAIN_HAND, node.getCachedItem());
        WrapperPlayServerEntityEquipment equipmentPacket = new WrapperPlayServerEntityEquipment(
                node.getEntityID(),
                Collections.singletonList(equipment)
        );
        user.writePacket(equipmentPacket);
    }

    public void spoofItemFrameMap(@NotNull User user, @NotNull FrameNode node) {
        writeSpoofItemFrameMap(user, node);
        user.flushPackets();
    }
}
