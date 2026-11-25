package net.ed1thy.emage.render;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;

final class AnimationCanvas {

    static final int MAP_SIZE = 128;
    static final int MAP_BYTES = MAP_SIZE * MAP_SIZE;
    private static final int HISTORY = 128;
    static final int[] FULL_BOX = {0, 0, MAP_SIZE - 1, MAP_SIZE - 1};
    private static final int[][] FULL_RECTS = {FULL_BOX};
    private static final java.lang.invoke.VarHandle LONGS =
            java.lang.invoke.MethodHandles.byteArrayViewVarHandle(long[].class, java.nio.ByteOrder.LITTLE_ENDIAN);

    /** What a viewer still lacks of one map: a few rectangles and what the single bounding box would have cost. */
    record Diff(int[][] rects, long boxBytes) {}

    private final int count;
    private final byte[][] pixels;
    private final int[][] historySeq;
    private final int[][] historyBox;
    private final long[][] historyCells;
    private final long[] cellScratch = new long[MapRects.MASK_WORDS];
    private final long[] maskScratch = new long[MapRects.MASK_WORDS];
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
        this.historyCells = new long[count][HISTORY * MapRects.MASK_WORDS];
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
        long[] cells = cellScratch;
        java.util.Arrays.fill(cells, 0L);
        for (int dy = 0; dy < height; dy++) {
            packet.getBytes(p + dy * width, row, 0, width);
            int base = (z + dy) * MAP_SIZE + x;
            int first = java.util.Arrays.mismatch(target, base, base + width, row, 0, width);
            if (first < 0) continue;
            int last = width - 1;
            while (target[base + last] == row[last]) last--;
            markChanged(target, row, base, x, first, last, ((z + dy) >> 3) << 4, cells);
            System.arraycopy(row, first, target, base + first, last - first + 1);
            int py = z + dy;
            if (x + first < minX) minX = x + first;
            if (x + last > maxX) maxX = x + last;
            if (py < minY) minY = py;
            if (py > maxY) maxY = py;
        }
        if (maxX >= 0) {
            record(index, frameSeq, minX, minY, maxX, maxY, cells);
        }
        return true;
    }

    /** Marks the 8x8 cells that hold a changed pixel of one row (target and row still differ at {@code first} and {@code last}). */
    private void markChanged(byte[] target, byte[] row, int base, int x, int first, int last, int cellRow, long[] cells) {
        int absFirst = x + first;
        int absLast = x + last;
        int rowStart = base - x;
        for (int cx = absFirst >> 3, cxLast = absLast >> 3; cx <= cxLast; cx++) {
            int lo = Math.max(absFirst, cx << 3);
            int hi = Math.min(absLast, (cx << 3) + 7);
            int n = hi - lo + 1;
            int rb = lo - x;
            int tb = rowStart + lo;
            int differing;
            if (n == 8) {
                long d = (long) LONGS.get(target, tb) ^ (long) LONGS.get(row, rb);
                if (d == 0) continue;
                d |= d >>> 4;
                d |= d >>> 2;
                d |= d >>> 1;
                differing = Long.bitCount(d & 0x0101010101010101L);
            } else {
                differing = 0;
                for (int i = 0; i < n; i++) {
                    if (target[tb + i] != row[rb + i]) differing++;
                }
                if (differing == 0) continue;
            }
            changedPixels += differing;
            int bit = cellRow + cx;
            cells[bit >> 6] |= 1L << (bit & 63);
        }
    }

    private void record(int index, int frameSeq, int minX, int minY, int maxX, int maxY, long[] cells) {
        changedBytes += (long) (maxX - minX + 1) * (maxY - minY + 1);
        int slot = historyNext[index];
        historySeq[index][slot] = frameSeq;
        historyBox[index][slot] = (minX << 24) | (minY << 16) | (maxX << 8) | maxY;
        System.arraycopy(cells, 0, historyCells[index], slot * MapRects.MASK_WORDS, MapRects.MASK_WORDS);
        historyNext[index] = (slot + 1) % HISTORY;
        if (historySize[index] < HISTORY) historySize[index]++;
    }

    int[] diffBox(int index, int viewerSeq) {
        return scan(index, viewerSeq, null);
    }

    /**
     * What a viewer who last saw sequence {@code viewerSeq} still lacks of this map, as a few rectangles (all of it when it
     * never got the map or fell too far behind), or {@code null} when nothing changed.
     */
    Diff diffRects(int index, int viewerSeq) {
        long[] mask = maskScratch;
        java.util.Arrays.fill(mask, 0L);
        int[] box = scan(index, viewerSeq, mask);
        if (box == null) return null;
        if (box == FULL_BOX) return new Diff(FULL_RECTS, packetBytes(FULL_BOX));
        int[][] rects = MapRects.cover(mask, box);
        return new Diff(rects, packetBytes(box));
    }

    private int[] scan(int index, int viewerSeq, long[] mask) {
        if (viewerSeq < 0) return FULL_BOX;
        int size = historySize[index];
        if (size == 0) return null;

        int next = historyNext[index];
        int oldest = size == HISTORY ? historySeq[index][next] : historySeq[index][0];
        if (size == HISTORY && oldest > viewerSeq) return FULL_BOX;

        long[] cells = historyCells[index];
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
            if (mask != null) {
                int offset = slot * MapRects.MASK_WORDS;
                for (int w = 0; w < MapRects.MASK_WORDS; w++) mask[w] |= cells[offset + w];
            }
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

    static long packetBytes(int[][] rects) {
        long sum = 0;
        for (int[] rect : rects) sum += packetBytes(rect);
        return sum;
    }

    private static void writeVarInt(ByteBuf buf, int value) {
        while ((value & -128) != 0) {
            buf.writeByte(value & 127 | 128);
            value >>>= 7;
        }
        buf.writeByte(value);
    }
}
