package net.ed1thy.emage.render;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapRectsTest {

    private static long[] maskOf(boolean[] cells) {
        long[] mask = new long[MapRects.MASK_WORDS];
        for (int i = 0; i < cells.length; i++) {
            if (cells[i]) mask[i >> 6] |= 1L << (i & 63);
        }
        return mask;
    }

    private static int[] boxOf(boolean[] cells) {
        int minX = 127, minY = 127, maxX = 0, maxY = 0;
        for (int i = 0; i < cells.length; i++) {
            if (!cells[i]) continue;
            int cx = i % 16;
            int cy = i / 16;
            minX = Math.min(minX, cx * 8);
            minY = Math.min(minY, cy * 8);
            maxX = Math.max(maxX, cx * 8 + 7);
            maxY = Math.max(maxY, cy * 8 + 7);
        }
        return new int[]{minX, minY, maxX, maxY};
    }

    private static void assertCovers(boolean[] cells, int[] box, int[][] rects) {
        for (int i = 0; i < cells.length; i++) {
            if (!cells[i]) continue;
            for (int y = (i / 16) * 8; y < (i / 16) * 8 + 8; y++) {
                for (int x = (i % 16) * 8; x < (i % 16) * 8 + 8; x++) {
                    if (x < box[0] || x > box[2] || y < box[1] || y > box[3]) continue;
                    boolean inside = false;
                    for (int[] r : rects) {
                        if (x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3]) {
                            inside = true;
                            break;
                        }
                    }
                    assertTrue(inside, "pixel " + x + "," + y + " of a changed cell is not covered");
                }
            }
        }
        for (int[] r : rects) {
            assertTrue(r[0] >= box[0] && r[1] >= box[1] && r[2] <= box[2] && r[3] <= box[3], "a rectangle leaves the box");
            assertTrue(r[0] <= r[2] && r[1] <= r[3]);
        }
    }

    @Test
    void everyChangedCellIsAlwaysCoveredAndTheCoverNeverCostsMoreThanTheBox() {
        Random random = new Random(7);
        for (int round = 0; round < 3000; round++) {
            boolean[] cells = new boolean[256];
            int kind = random.nextInt(4);
            if (kind == 0) {
                double density = random.nextDouble() * 0.5;
                for (int i = 0; i < 256; i++) cells[i] = random.nextDouble() < density;
            } else {
                int blobs = 1 + random.nextInt(4);
                for (int b = 0; b < blobs; b++) {
                    int cx = random.nextInt(16);
                    int cy = random.nextInt(16);
                    int w = 1 + random.nextInt(5);
                    int h = 1 + random.nextInt(5);
                    for (int y = cy; y < Math.min(16, cy + h); y++) {
                        for (int x = cx; x < Math.min(16, cx + w); x++) {
                            if (kind != 3 || random.nextInt(3) > 0) cells[y * 16 + x] = true;
                        }
                    }
                }
            }
            boolean any = false;
            for (boolean c : cells) any |= c;
            if (!any) continue;

            int[] box = boxOf(cells);
            int[][] rects = MapRects.cover(maskOf(cells), box);

            assertCovers(cells, box, rects);
            long cost = 0;
            for (int[] r : rects) cost += MapRects.area(r) + MapRects.OVERHEAD;
            assertTrue(cost <= MapRects.area(box) + MapRects.OVERHEAD, "the cover must never cost more than the single box");
            if (rects.length > 1) assertTrue(cost * 10 < (MapRects.area(box) + MapRects.OVERHEAD) * 7L, "a split must save at least 30%");
            assertTrue(rects.length <= 8, "too many rectangles: " + rects.length);
        }
    }

    @Test
    void twoBlobsInOppositeCornersBecomeTwoSmallRectangles() {
        boolean[] cells = new boolean[256];
        for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) cells[y * 16 + x] = true;
        for (int y = 13; y < 16; y++) for (int x = 13; x < 16; x++) cells[y * 16 + x] = true;
        int[] box = boxOf(cells);

        int[][] rects = MapRects.cover(maskOf(cells), box);

        assertEquals(2, rects.length);
        assertCovers(cells, box, rects);
        assertTrue(MapRects.bytes(rects) * 5 < AnimationCanvas.packetBytes(box), "two small blobs must cost a fraction of their bounding box");
    }

    @Test
    void aSolidBlockStaysOneRectangleAndFullMapsStayWhole() {
        boolean[] cells = new boolean[256];
        for (int y = 2; y < 9; y++) for (int x = 4; x < 12; x++) cells[y * 16 + x] = true;
        int[] box = boxOf(cells);
        int[][] rects = MapRects.cover(maskOf(cells), box);
        assertEquals(1, rects.length);
        assertEquals(box[0], rects[0][0]);
        assertEquals(box[3], rects[0][3]);

        boolean[] all = new boolean[256];
        java.util.Arrays.fill(all, true);
        int[][] whole = MapRects.cover(maskOf(all), AnimationCanvas.FULL_BOX);
        assertEquals(1, whole.length);
    }

    @Test
    void aSinglePixelBoxIsNotEnlarged() {
        boolean[] cells = new boolean[256];
        cells[5 * 16 + 5] = true;
        int[] box = {42, 42, 42, 42};
        int[][] rects = MapRects.cover(maskOf(cells), box);
        assertEquals(1, rects.length);
        assertEquals(1, MapRects.area(rects[0]));
    }
}
