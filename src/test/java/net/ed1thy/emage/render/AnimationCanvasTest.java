package net.ed1thy.emage.render;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationCanvasTest {

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

    private static byte[] filled(int size, int value) {
        byte[] data = new byte[size];
        java.util.Arrays.fill(data, (byte) value);
        return data;
    }

    private static void applyToClient(byte[] client, ByteBuf packet) {
        AnimationCanvas scratch = new AnimationCanvas(1);
        scratch.applyPacket(0, packet, 1);
        byte[] parsed = scratch.pixelsOf(0);
        int p = packet.readerIndex();
        while ((packet.getByte(p++) & 0x80) != 0) {
        }
        p += 3;
        int width = packet.getUnsignedByte(p++);
        int height = packet.getUnsignedByte(p++);
        int x = packet.getUnsignedByte(p++);
        int z = packet.getUnsignedByte(p++);
        for (int dy = 0; dy < height; dy++) {
            for (int dx = 0; dx < width; dx++) {
                client[(z + dy) * 128 + x + dx] = parsed[(z + dy) * 128 + x + dx];
            }
        }
    }

    /** A frame the way the stored GIF frames look: a bounding box around a few scattered blobs, the rest of the box unchanged. */
    private static void sparseFrame(AnimationCanvas canvas, int map, int mapId, Random random, int blobs) {
        int w = 40 + random.nextInt(89);
        int h = 40 + random.nextInt(89);
        int x = random.nextInt(128 - w + 1);
        int z = random.nextInt(128 - h + 1);
        byte[] current = canvas.pixelsOf(map);
        byte[] data = new byte[w * h];
        for (int dy = 0; dy < h; dy++) System.arraycopy(current, (z + dy) * 128 + x, data, dy * w, w);
        for (int b = 0; b < blobs; b++) {
            int bw = 2 + random.nextInt(12);
            int bh = 2 + random.nextInt(12);
            int bx = random.nextInt(Math.max(1, w - bw));
            int by = random.nextInt(Math.max(1, h - bh));
            for (int yy = by; yy < Math.min(h, by + bh); yy++) {
                for (int xx = bx; xx < Math.min(w, bx + bw); xx++) data[yy * w + xx] = (byte) (4 + random.nextInt(200));
            }
        }
        canvas.applyPacket(map, packet(mapId, x, z, w, h, data), canvas.beginFrame());
    }

    @Test
    void viewersWithAnyLagRebuildExactlyTheServerPictureFromSmallRectangles() {
        Random random = new Random(21);
        int maps = 3;
        AnimationCanvas canvas = new AnimationCanvas(maps);
        for (int m = 0; m < maps; m++) {
            canvas.applyPacket(m, packet(100 + m, 0, 0, 128, 128, filled(16384, 10 + m)), canvas.beginFrame());
        }
        int[] lags = {1, 2, 5, 17, 60, 200};
        byte[][][] clients = new byte[lags.length][maps][16384];
        int[][] seqs = new int[lags.length][maps];
        for (int v = 0; v < lags.length; v++) {
            for (int m = 0; m < maps; m++) {
                clients[v][m] = canvas.pixelsOf(m).clone();
                seqs[v][m] = canvas.seq();
            }
        }
        long sparseBytes = 0;
        long boxBytes = 0;
        for (int frame = 1; frame <= 600; frame++) {
            for (int m = 0; m < maps; m++) {
                if (random.nextInt(3) > 0) sparseFrame(canvas, m, 100 + m, random, 1 + random.nextInt(3));
            }
            for (int v = 0; v < lags.length; v++) {
                if (frame % lags[v] != 0) continue;
                for (int m = 0; m < maps; m++) {
                    AnimationCanvas.Diff diff = canvas.diffRects(m, seqs[v][m]);
                    if (diff == null) continue;
                    boxBytes += diff.boxBytes();
                    for (int[] rect : diff.rects()) {
                        ByteBuf update = canvas.buildPacket(m, 100 + m, rect);
                        sparseBytes += update.readableBytes() + 3;
                        applyToClient(clients[v][m], update);
                        update.release();
                    }
                    seqs[v][m] = canvas.seq();
                }
            }
        }
        for (int v = 0; v < lags.length; v++) {
            for (int m = 0; m < maps; m++) {
                AnimationCanvas.Diff diff = canvas.diffRects(m, seqs[v][m]);
                if (diff != null) {
                    for (int[] rect : diff.rects()) {
                        ByteBuf update = canvas.buildPacket(m, 100 + m, rect);
                        applyToClient(clients[v][m], update);
                        update.release();
                    }
                }
                assertArrayEquals(canvas.pixelsOf(m), clients[v][m], "viewer " + v + " (syncs every " + lags[v] + " frames) must end up identical to the server");
            }
        }
        System.out.printf("RECTS sparse blobs inside bounding boxes: %d KB sent as rectangles instead of %d KB (%.0f%%)%n",
                sparseBytes / 1024, boxBytes / 1024, 100.0 * sparseBytes / boxBytes);
        assertTrue(sparseBytes < boxBytes * 0.7, "small rectangles must beat the bounding boxes for scattered blobs: " + sparseBytes + " vs " + boxBytes);
    }

    @Test
    void workingOutSmallRectanglesIsCheap() {
        Random random = new Random(5);
        int maps = 256;
        AnimationCanvas canvas = new AnimationCanvas(maps);
        for (int m = 0; m < maps; m++) {
            canvas.applyPacket(m, packet(100 + m, 0, 0, 128, 128, filled(16384, 10)), canvas.beginFrame());
        }
        int[] seqs = new int[maps];
        java.util.Arrays.fill(seqs, canvas.seq());
        long applyNanos = 0;
        long diffNanos = 0;
        long rectCount = 0;
        long calls = 0;
        for (int frame = 0; frame < 60; frame++) {
            long t0 = System.nanoTime();
            for (int m = 0; m < maps; m++) sparseFrame(canvas, m, 100 + m, random, 3);
            applyNanos += System.nanoTime() - t0;
            if (frame % 3 == 2) {
                long t1 = System.nanoTime();
                for (int m = 0; m < maps; m++) {
                    AnimationCanvas.Diff diff = canvas.diffRects(m, seqs[m]);
                    if (diff != null) rectCount += diff.rects().length;
                    seqs[m] = canvas.seq();
                    calls++;
                }
                diffNanos += System.nanoTime() - t1;
            }
        }
        System.out.printf("RECTS cost: applying a stored frame %.1f us per map, working out the rectangles %.1f us per map (%.1f rectangles on average)%n",
                applyNanos / 1000.0 / (60.0 * maps), diffNanos / 1000.0 / calls, rectCount / (double) calls);
        assertTrue(diffNanos / 1000.0 / calls < 200, "working out the rectangles must stay cheap");
    }

    @Test
    void theCanvasCountsReallyChangedPixelsApartFromTheirBoundingBoxAndTransparentOnes() {
        AnimationCanvas canvas = new AnimationCanvas(1);
        canvas.applyPacket(0, packet(1, 0, 0, 128, 128, filled(128 * 128, 40)), canvas.beginFrame());
        canvas.takeChangedBytes();
        canvas.takeChangedPixels();
        assertEquals(0, canvas.transparentPixels(0));

        byte[] patch = filled(20 * 20, 40);
        patch[0] = 50;
        patch[19 * 20 + 19] = 0;
        patch[5 * 20 + 7] = 3;
        canvas.applyPacket(0, packet(1, 10, 10, 20, 20, patch), canvas.beginFrame());

        assertEquals(3, canvas.takeChangedPixels());
        assertEquals(20 * 20, canvas.takeChangedBytes());
        assertEquals(2, canvas.transparentPixels(0));
    }

    @Test
    void fullAndPartialPacketsAreAppliedToTheRightPlace() {
        AnimationCanvas canvas = new AnimationCanvas(2);
        canvas.applyPacket(0, packet(7, 0, 0, 128, 128, filled(16384, 5)), canvas.beginFrame());
        canvas.applyPacket(0, packet(7, 10, 20, 3, 2, filled(6, 9)), canvas.beginFrame());

        byte[] pixels = canvas.pixelsOf(0);
        assertEquals(5, pixels[0]);
        assertEquals(9, pixels[20 * 128 + 10]);
        assertEquals(9, pixels[21 * 128 + 12]);
        assertEquals(5, pixels[20 * 128 + 13]);
        assertEquals(0, canvas.pixelsOf(1)[0]);
    }

    @Test
    void aViewerOnlyReceivesTheRegionThatChangedSinceItsLastUpdate() {
        AnimationCanvas canvas = new AnimationCanvas(1);
        canvas.applyPacket(0, packet(1, 0, 0, 128, 128, filled(16384, 1)), canvas.beginFrame());
        int viewerSeq = canvas.seq();

        assertNull(canvas.diffBox(0, viewerSeq), "nothing changed yet");

        canvas.applyPacket(0, packet(1, 10, 10, 4, 4, filled(16, 2)), canvas.beginFrame());
        canvas.applyPacket(0, packet(1, 50, 60, 2, 2, filled(4, 3)), canvas.beginFrame());

        int[] box = canvas.diffBox(0, viewerSeq);
        assertNotNull(box);
        assertArrayEquals(new int[]{10, 10, 51, 61}, box);

        int[] recent = canvas.diffBox(0, canvas.seq() - 1);
        assertArrayEquals(new int[]{50, 60, 51, 61}, recent);
        assertArrayEquals(AnimationCanvas.FULL_BOX, canvas.diffBox(0, -1));
    }

    @Test
    void unchangedPixelsAreNotReportedAsChanges() {
        AnimationCanvas canvas = new AnimationCanvas(1);
        canvas.applyPacket(0, packet(1, 0, 0, 128, 128, filled(16384, 4)), canvas.beginFrame());
        int viewerSeq = canvas.seq();
        canvas.applyPacket(0, packet(1, 5, 5, 10, 10, filled(100, 4)), canvas.beginFrame());
        assertNull(canvas.diffBox(0, viewerSeq));
    }

    @Test
    void aViewerWhoFellFarBehindGetsAFullMapInsteadOfALostChange() {
        AnimationCanvas canvas = new AnimationCanvas(1);
        canvas.applyPacket(0, packet(1, 0, 0, 128, 128, filled(16384, 1)), canvas.beginFrame());
        int viewerSeq = canvas.seq();
        for (int i = 0; i < 300; i++) {
            canvas.applyPacket(0, packet(1, i % 100, 0, 1, 1, filled(1, 2 + i % 200)), canvas.beginFrame());
        }
        assertArrayEquals(AnimationCanvas.FULL_BOX, canvas.diffBox(0, viewerSeq));
    }

    @Test
    void aClientThatAppliesTheSentDiffsEndsUpWithExactlyTheServerState() {
        Random random = new Random(3);
        AnimationCanvas canvas = new AnimationCanvas(1);
        byte[] client = new byte[16384];
        int viewerSeq = -1;

        canvas.applyPacket(0, packet(9, 0, 0, 128, 128, filled(16384, 1)), canvas.beginFrame());

        for (int frame = 0; frame < 400; frame++) {
            int w = 1 + random.nextInt(40);
            int h = 1 + random.nextInt(40);
            int x = random.nextInt(128 - w + 1);
            int z = random.nextInt(128 - h + 1);
            byte[] data = new byte[w * h];
            random.nextBytes(data);
            canvas.applyPacket(0, packet(9, x, z, w, h, data), canvas.beginFrame());

            if (random.nextInt(4) == 0) {
                int[] box = canvas.diffBox(0, viewerSeq);
                if (box != null) {
                    ByteBuf update = canvas.buildPacket(0, 9, box);
                    applyToClient(client, update);
                    update.release();
                    viewerSeq = canvas.seq();
                }
            }
        }
        int[] box = canvas.diffBox(0, viewerSeq);
        if (box != null) {
            ByteBuf update = canvas.buildPacket(0, 9, box);
            applyToClient(client, update);
            update.release();
        }
        assertArrayEquals(canvas.pixelsOf(0), client, "the client must end up identical to the server canvas");
    }

    @Test
    void builtPacketsHaveTheProtocolLayout() {
        AnimationCanvas canvas = new AnimationCanvas(1);
        canvas.applyPacket(0, packet(300, 0, 0, 128, 128, filled(16384, 6)), canvas.beginFrame());
        ByteBuf built = canvas.buildPacket(0, 300, new int[]{4, 5, 7, 9});

        AnimationCanvas other = new AnimationCanvas(1);
        assertTrue(other.applyPacket(0, built, 1));
        assertEquals(6, other.pixelsOf(0)[5 * 128 + 4]);
        assertEquals(0, other.pixelsOf(0)[0]);
        assertEquals(4 * 5 + 16, AnimationCanvas.packetBytes(new int[]{4, 5, 7, 9}));
        built.release();
    }

    @Test
    void applyingTheFramesOfAHugeGifIsCheap() {
        int maps = 256;
        AnimationCanvas canvas = new AnimationCanvas(maps);
        Random random = new Random(5);
        byte[][][] frames = new byte[8][maps][];
        for (int f = 0; f < frames.length; f++) {
            for (int m = 0; m < maps; m++) {
                byte[] data = new byte[16384];
                for (int i = 0; i < data.length; i++) data[i] = (byte) ((i / 512 + f * 3 + (random.nextInt(4) == 0 ? 1 : 0)) & 0x3F);
                frames[f][m] = data;
            }
        }
        for (int m = 0; m < maps; m++) canvas.applyPacket(m, packet(m, 0, 0, 128, 128, frames[0][m]), canvas.beginFrame());
        ByteBuf[][] packets = new ByteBuf[frames.length][maps];
        for (int f = 0; f < frames.length; f++) for (int m = 0; m < maps; m++) packets[f][m] = packet(m, 0, 0, 128, 128, frames[f][m]);
        for (int round = 0; round < 5; round++) {
            for (int f = 0; f < frames.length; f++) for (int m = 0; m < maps; m++) canvas.applyPacket(m, packets[f][m], canvas.beginFrame());
        }
        int rounds = 40;
        long start = System.nanoTime();
        for (int round = 0; round < rounds; round++) {
            for (int f = 0; f < frames.length; f++) {
                int seq = canvas.beginFrame();
                for (int m = 0; m < maps; m++) canvas.applyPacket(m, packets[f][m], seq);
            }
        }
        double millisPerFrame = (System.nanoTime() - start) / 1e6 / (rounds * frames.length);
        System.out.printf("APPLY one frame of a 16x16 gif (256 maps) to the canvas: %.2f ms%n", millisPerFrame);
        assertTrue(millisPerFrame < 6.0, "applying a frame is too slow: " + millisPerFrame + " ms");
    }
}
