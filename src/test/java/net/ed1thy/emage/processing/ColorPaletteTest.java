package net.ed1thy.emage.processing;

import org.junit.jupiter.api.Test;
import java.util.concurrent.ForkJoinPool;
import static org.junit.jupiter.api.Assertions.*;

class ColorPaletteTest {

    @Test
    void testLUTLoadingAndColorMapping() {
        ForkJoinPool pool = ForkJoinPool.commonPool();
        ColorPalette palette = new ColorPalette(pool);

        palette.generateLUT().join();
        assertTrue(palette.isReady(), "ColorPalette LUT should be initialized and ready");

        byte blackId = palette.getMappedColorFast(0, 0, 0);
        byte whiteId = palette.getMappedColorFast(255, 255, 255);
        byte redId = palette.getMappedColorFast(255, 0, 0);
        byte greenId = palette.getMappedColorFast(0, 255, 0);
        byte blueId = palette.getMappedColorFast(0, 0, 255);

        assertNotEquals(blackId, whiteId, "Black and white should map to different palette indices");
        assertNotEquals(redId, blueId, "Red and blue should map to different palette indices");
        assertNotEquals(greenId, blueId, "Green and blue should map to different palette indices");
    }
}
