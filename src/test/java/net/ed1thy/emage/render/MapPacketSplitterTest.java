package net.ed1thy.emage.render;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.ed1thy.emage.model.DeltaFrame;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapPacketSplitterTest {

    private static ByteBuf packet(int mapId, int x, int z, int width, int height, byte[] data) {
        ByteBuf buf = Unpooled.buffer();
        int value = mapId;
        while ((value & -128) != 0) {
            buf.writeByte(value & 127 | 128);
            value >>>= 7;
        }
        buf.writeByte(value);
        buf.writeByte(0);
        buf.writeBoolean(true);
        buf.writeBoolean(false);
        buf.writeByte(width);
        buf.writeByte(height);
        buf.writeByte(x);
        buf.writeByte(z);
        int length = width * height;
        while ((length & -128) != 0) {
            buf.writeByte(length & 127 | 128);
            length >>>= 7;
        }
        buf.writeByte(length);
        buf.writeBytes(data, 0, width * height);
        return buf;
    }

    private static byte[] random(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    @Test
    void theBandsOfAFullMapRebuildExactlyTheSamePicture() {
        byte[] data = random(16384, 1);
        ByteBuf original = packet(300_123, 0, 0, 128, 128, data);

        List<ByteBuf> bands = MapPacketSplitter.split(original, 2048);

        assertNotNull(bands);
        assertTrue(bands.size() >= 8, "a 16 KB map must become several thin bands: " + bands.size());
        AnimationCanvas whole = new AnimationCanvas(1);
        whole.applyPacket(0, original, 1);
        AnimationCanvas rebuilt = new AnimationCanvas(1);
        for (ByteBuf band : bands) {
            assertTrue(band.readableBytes() <= 2048, "band too big: " + band.readableBytes());
            assertTrue(rebuilt.applyPacket(0, band, 1));
        }
        assertArrayEquals(whole.pixelsOf(0), rebuilt.pixelsOf(0));
    }

    @Test
    void aPartialRectangleIsSplitInsideItsOwnArea() {
        byte[] data = random(70 * 90, 2);
        ByteBuf original = packet(5, 30, 20, 70, 90, data);

        List<ByteBuf> bands = MapPacketSplitter.split(original, 1600);

        assertNotNull(bands);
        AnimationCanvas whole = new AnimationCanvas(1);
        whole.applyPacket(0, original, 1);
        AnimationCanvas rebuilt = new AnimationCanvas(1);
        for (ByteBuf band : bands) rebuilt.applyPacket(0, band, 1);
        assertArrayEquals(whole.pixelsOf(0), rebuilt.pixelsOf(0));
        assertEquals(90, bands.stream().mapToInt(b -> AnimationCanvas.packetArea(b) / 70).sum());
    }

    @Test
    void packetsThatAlreadyFitOrAreNotMapDataAreLeftAlone() {
        assertNull(MapPacketSplitter.split(packet(1, 0, 0, 128, 128, random(16384, 3)), 20_000));
        assertNull(MapPacketSplitter.split(Unpooled.wrappedBuffer(new byte[]{1, 2, 3}), 2000));
    }

    @Test
    void aSlowConnectionsQueueStoresThinBandsAndAFastOneKeepsWholeMaps() {
        PlayerSendQueue slow = new PlayerSendQueue(new PlayerSendQueue.Tuning(16_384, 100_000, 98_304, 20, false));
        ByteBuf slowBuf = packet(9, 0, 0, 128, 128, random(16384, 4));
        assertTrue(slow.add(new DeltaFrame(0, 9, slowBuf), 1));
        assertTrue(slow.size() > 4, "a slow link must get thin bands: " + slow.size());
        slow.drain();

        PlayerSendQueue fast = new PlayerSendQueue(new PlayerSendQueue.Tuning(16_384, 33_554_432, 98_304, 20, false));
        ByteBuf fastBuf = packet(9, 0, 0, 128, 128, random(16384, 5));
        assertTrue(fast.add(new DeltaFrame(0, 9, fastBuf), 1));
        assertEquals(1, fast.size());
        fast.drain();
        slowBuf.release();
        fastBuf.release();
    }
}
