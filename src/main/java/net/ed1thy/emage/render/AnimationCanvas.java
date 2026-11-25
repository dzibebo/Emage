package net.ed1thy.emage.render;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;

final class AnimationCanvas {

    static final int MAP_SIZE = 128;
    static final int MAP_BYTES = MAP_SIZE * MAP_SIZE;
    private static final int HISTORY = 128;
    static final int[] FULL_BOX = {0, 0, MAP_SIZE - 1, MAP_SIZE - 1};

    private final int count;
    private final byte[][] pixels;
    private final int[][] historySeq;
    private final int[][] historyBox;
    private final int[] historyNext;
    private final int[] historySize;
    private int seq;
    private long changedBytes;
    private long changedPixels;

    AnimationCanvas(int count) {
        this.count = count;
        this.pixels = new byte[count][MAP_BYTES];
        this.historySeq = new int[count][HISTORY];
        this.historyBox = new int[count][HISTORY];
        this.historyNext = new int[count];
        this.historySize = new int[count];
    }

    int count() {
        return count;
    }

    int seq() {
        return seq;
    }

    long takeChangedBytes() {
        long value = changedBytes;
        changedBytes = 0;
        return value;
    }

    /** Pixels that really changed (the bounding boxes counted by {@link #takeChangedBytes()} also hold unchanged ones). */
    long takeChangedPixels() {
        long value = changedPixels;
        changedPixels = 0;
        return value;
    }

    /** Pixels of a map that are transparent (map colors 0-3 draw nothing): the picture shows the frame behind it there. */
    int transparentPixels(int index) {
        byte[] data = pixels[index];
        int n = 0;
        for (byte b : data) {
            if ((b & 0xFF) < 4) n++;
        }
        return n;
    }

    int beginFrame() {
        return ++seq;
    }

    byte[] pixelsOf(int index) {
        return pixels[index];
    }

    boolean applyPacket(int index, ByteBuf packet, int frameSeq) {
        int p = packet.readerIndex();
        while ((packet.getByte(p++) & 0x80) != 0) {
        }
        p += 3;
        int width = packet.getUnsignedByte(p++);
        int height = packet.getUnsignedByte(p++);
        int x = packet.getUnsignedByte(p++);
        int z = packet.getUnsignedByte(p++);
        int length = 0;
        int shift = 0;
        byte b;
        do {
            b = packet.getByte(p++);
            length |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);

        if (width <= 0 || height <= 0 || x + width > MAP_SIZE || z + height > MAP_SIZE || length < width * height) {
            return false;
        }

        byte[] target = pixels[index];
        int minX = MAP_SIZE, minY = MAP_SIZE, maxX = -1, maxY = -1;
        byte[] row = new byte[width];
        for (int dy = 0; dy < height; dy++) {
            packet.getBytes(p + dy * width, row, 0, width);
            int base = (z + dy) * MAP_SIZE + x;
            int first = java.util.Arrays.mismatch(target, base, base + width, row, 0, width);
            if (first < 0) continue;
            int last = width - 1;
            while (target[base + last] == row[last]) last--;
            for (int k = first; k <= last; k++) {
                if (target[base + k] != row[k]) changedPixels++;
            }
            System.arraycopy(row, first, target, base + first, last - first + 1);
            int py = z + dy;
            if (x + first < minX) minX = x + first;
            if (x + last > maxX) maxX = x + last;
            if (py < minY) minY = py;
            if (py > maxY) maxY = py;
        }
        if (maxX >= 0) {
            record(index, frameSeq, minX, minY, maxX, maxY);
        }
        return true;
    }

    private void record(int index, int frameSeq, int minX, int minY, int maxX, int maxY) {
        changedBytes += (long) (maxX - minX + 1) * (maxY - minY + 1);
        int slot = historyNext[index];
        historySeq[index][slot] = frameSeq;
        historyBox[index][slot] = (minX << 24) | (minY << 16) | (maxX << 8) | maxY;
        historyNext[index] = (slot + 1) % HISTORY;
        if (historySize[index] < HISTORY) historySize[index]++;
    }

    int[] diffBox(int index, int viewerSeq) {
        if (viewerSeq < 0) return FULL_BOX;
        int size = historySize[index];
        if (size == 0) return null;

        int next = historyNext[index];
        int oldest = size == HISTORY ? historySeq[index][next] : historySeq[index][0];
        if (size == HISTORY && oldest > viewerSeq) return FULL_BOX;

        int minX = MAP_SIZE, minY = MAP_SIZE, maxX = -1, maxY = -1;
        for (int i = 0; i < size; i++) {
            int slot = (next - 1 - i + HISTORY) % HISTORY;
            int recordSeq = historySeq[index][slot];
            if (recordSeq <= viewerSeq) break;
            int box = historyBox[index][slot];
            int bx0 = (box >>> 24) & 0xFF;
            int by0 = (box >>> 16) & 0xFF;
            int bx1 = (box >>> 8) & 0xFF;
            int by1 = box & 0xFF;
            if (bx0 < minX) minX = bx0;
            if (by0 < minY) minY = by0;
            if (bx1 > maxX) maxX = bx1;
            if (by1 > maxY) maxY = by1;
        }
        if (maxX < 0) return null;
        return new int[]{minX, minY, maxX, maxY};
    }

    ByteBuf buildPacket(int index, int mapId, int[] box) {
        int width = box[2] - box[0] + 1;
        int height = box[3] - box[1] + 1;
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer(width * height + 24);
        writeVarInt(buf, mapId);
        buf.writeByte(0);
        buf.writeBoolean(true);
        buf.writeBoolean(false);
        buf.writeByte(width);
        buf.writeByte(height);
        buf.writeByte(box[0]);
        buf.writeByte(box[1]);
        writeVarInt(buf, width * height);
        byte[] source = pixels[index];
        for (int y = 0; y < height; y++) {
            buf.writeBytes(source, (box[1] + y) * MAP_SIZE + box[0], width);
        }
        return buf;
    }

    static int packetArea(ByteBuf packet) {
        int p = packet.readerIndex();
        while ((packet.getByte(p++) & 0x80) != 0) {
        }
        p += 3;
        int width = packet.getUnsignedByte(p++);
        int height = packet.getUnsignedByte(p);
        return width * height;
    }

    static int packetBytes(int[] box) {
        return (box[2] - box[0] + 1) * (box[3] - box[1] + 1) + 16;
    }

    private static void writeVarInt(ByteBuf buf, int value) {
        while ((value & -128) != 0) {
            buf.writeByte(value & 127 | 128);
            value >>>= 7;
        }
        buf.writeByte(value);
    }
}
