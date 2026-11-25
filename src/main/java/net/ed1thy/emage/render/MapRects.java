package net.ed1thy.emage.render;

/**
 * Turns "these 8x8 cells of a map changed" into a few rectangles that cover them.
 *
 * A map packet holds exactly one rectangle, and the bounding box of everything that changed in a map is usually
 * several times bigger than the changed pixels themselves (live numbers: 12-23% of the box on the biggest GIFs). Sending
 * a few smaller rectangles instead of one big box saves most of those bytes, but every extra packet also costs
 * the proxy and the client some work, so a rectangle is only split off when it saves more than {@link #OVERHEAD} bytes.
 */
final class MapRects {

    /** What one more packet costs, in bytes of map data: the real header is about 20 bytes, the rest stands for the work every packet causes: compressing it on the proxy, decoding it and handing it to the main thread of the client. */
    static final int OVERHEAD = 256;

    static final int CELL = 8;
    static final int CELLS = AnimationCanvas.MAP_SIZE / CELL;
    static final int MASK_WORDS = CELLS * CELLS / 64;

    /** Only tests switch this off, to compare what the rectangles save. */
    static volatile boolean enabled = true;

    private static final int MAX_START_RECTS = 40;
    private static final int MAX_RECTS = 8;

    private MapRects() {}

    static int area(int[] box) {
        return (box[2] - box[0] + 1) * (box[3] - box[1] + 1);
    }

    static long bytes(int[][] rects) {
        long sum = 0;
        for (int[] rect : rects) sum += AnimationCanvas.packetBytes(rect);
        return sum;
    }

    /**
     * @param mask bit {@code cellY * 16 + cellX} set = something changed in that 8x8 cell
     * @param box  the pixel bounding box of everything that changed, a rectangle inside it covers no more than needed
     * @return rectangles (x0, y0, x1, y1 inclusive) that cover every changed cell; just {@code box} when splitting does not pay
     */
    static int[][] cover(long[] mask, int[] box) {
        int[][] single = {box};
        if (!enabled) return single;
        long boxCost = area(box) + OVERHEAD;

        int[] rows = new int[CELLS];
        int cellsSet = 0;
        for (int cy = 0; cy < CELLS; cy++) {
            int bits = (int) ((mask[cy >> 2] >>> ((cy & 3) * 16)) & 0xFFFF);
            rows[cy] = bits;
            cellsSet += Integer.bitCount(bits);
        }
        if (cellsSet == 0) return single;
        if ((long) cellsSet * CELL * CELL + OVERHEAD >= boxCost) {
            // even the changed cells alone fill the box, nothing to gain
            return single;
        }

        int[] x0 = new int[MAX_START_RECTS];
        int[] x1 = new int[MAX_START_RECTS];
        int[] y0 = new int[MAX_START_RECTS];
        int[] y1 = new int[MAX_START_RECTS];
        int n = 0;
        for (int cy = 0; cy < CELLS; cy++) {
            int bits = rows[cy];
            int cx = 0;
            while (cx < CELLS) {
                if ((bits >>> cx & 1) == 0) {
                    cx++;
                    continue;
                }
                int start = cx;
                while (cx < CELLS && (bits >>> cx & 1) != 0) cx++;
                int end = cx - 1;
                boolean extended = false;
                for (int i = 0; i < n; i++) {
                    if (y1[i] == cy - 1 && x0[i] == start && x1[i] == end) {
                        y1[i] = cy;
                        extended = true;
                        break;
                    }
                }
                if (!extended) {
                    if (n == MAX_START_RECTS) return single;
                    x0[n] = start;
                    x1[n] = end;
                    y0[n] = cy;
                    y1[n] = cy;
                    n++;
                }
            }
        }

        while (n > 1) {
            int bestA = -1;
            int bestB = -1;
            long bestSaving = 0;
            for (int a = 0; a < n; a++) {
                long costA = cost(x0[a], y0[a], x1[a], y1[a]);
                for (int b = a + 1; b < n; b++) {
                    long merged = cost(Math.min(x0[a], x0[b]), Math.min(y0[a], y0[b]), Math.max(x1[a], x1[b]), Math.max(y1[a], y1[b]));
                    long saving = costA + cost(x0[b], y0[b], x1[b], y1[b]) - merged;
                    if (saving > bestSaving) {
                        bestSaving = saving;
                        bestA = a;
                        bestB = b;
                    }
                }
            }
            if (bestA < 0) break;
            x0[bestA] = Math.min(x0[bestA], x0[bestB]);
            y0[bestA] = Math.min(y0[bestA], y0[bestB]);
            x1[bestA] = Math.max(x1[bestA], x1[bestB]);
            y1[bestA] = Math.max(y1[bestA], y1[bestB]);
            n--;
            x0[bestB] = x0[n];
            x1[bestB] = x1[n];
            y0[bestB] = y0[n];
            y1[bestB] = y1[n];
        }
        if (n > MAX_RECTS) return single;

        int[][] rects = new int[n][];
        long cost = 0;
        for (int i = 0; i < n; i++) {
            int rx0 = Math.max(box[0], x0[i] * CELL);
            int ry0 = Math.max(box[1], y0[i] * CELL);
            int rx1 = Math.min(box[2], x1[i] * CELL + CELL - 1);
            int ry1 = Math.min(box[3], y1[i] * CELL + CELL - 1);
            if (rx1 < rx0 || ry1 < ry0) return single;
            rects[i] = new int[]{rx0, ry0, rx1, ry1};
            cost += area(rects[i]) + OVERHEAD;
        }
        // a split that saves only a little is not worth the extra packets (and the extra work on the proxy and the client)
        return cost * 10 < boxCost * 7 ? rects : single;
    }

    private static long cost(int cx0, int cy0, int cx1, int cy1) {
        return (long) (cx1 - cx0 + 1) * (cy1 - cy0 + 1) * CELL * CELL + OVERHEAD;
    }
}
