package net.ed1thy.emage.processing;

import org.junit.jupiter.api.Test;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BandedScalingTest {

    private static Graphics2D graphics(BufferedImage target) {
        Graphics2D g = target.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        g.setComposite(AlphaComposite.Src);
        return g;
    }

    private static BufferedImage randomImage(int w, int h, long seed) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(seed);
        Graphics2D g = img.createGraphics();
        for (int i = 0; i < 400; i++) {
            g.setColor(new Color(random.nextInt(256), random.nextInt(256), random.nextInt(256), random.nextInt(4) == 0 ? 0 : 255));
            g.fillRect(random.nextInt(w), random.nextInt(h), 1 + random.nextInt(60), 1 + random.nextInt(60));
        }
        g.dispose();
        return img;
    }

    private static void assertBandedEqualsFull(BufferedImage source, int columns, int rows) {
        int totalWidth = columns * 128;
        int totalHeight = rows * 128;

        BufferedImage full = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D fullGraphics = graphics(full);
        fullGraphics.drawImage(source, 0, 0, totalWidth, totalHeight, null);
        fullGraphics.dispose();
        int[] expected = ((DataBufferInt) full.getRaster().getDataBuffer()).getData();

        BufferedImage band = new BufferedImage(totalWidth, 128, BufferedImage.TYPE_INT_ARGB);
        Graphics2D bandGraphics = graphics(band);
        int[] bandPixels = ((DataBufferInt) band.getRaster().getDataBuffer()).getData();
        for (int row = 0; row < rows; row++) {
            bandGraphics.setTransform(AffineTransform.getTranslateInstance(0, -(double) row * 128));
            bandGraphics.drawImage(source, 0, 0, totalWidth, totalHeight, null);
            int[] expectedBand = new int[totalWidth * 128];
            System.arraycopy(expected, row * 128 * totalWidth, expectedBand, 0, expectedBand.length);
            assertArrayEquals(expectedBand, bandPixels, "band " + row + " differs from the full-image rendering");
        }
        bandGraphics.dispose();
    }

    @Test
    void bandedRenderingGivesExactlyTheSamePixelsAsRenderingTheWholeImage() {
        assertBandedEqualsFull(randomImage(300, 200, 1), 3, 2);
        assertBandedEqualsFull(randomImage(1000, 1500, 2), 4, 6);
        assertBandedEqualsFull(randomImage(90, 90, 3), 5, 5);
    }

    @Test
    void wrappedPixelsShareTheSourceArrayWithoutCopying() {
        int[] pixels = new int[16 * 8];
        pixels[5 * 16 + 3] = 0xFF123456;
        BufferedImage image = ImagePipeline.wrapPixels(pixels, 16, 8);
        assertEquals(16, image.getWidth());
        assertEquals(8, image.getHeight());
        assertEquals(0xFF123456, image.getRGB(3, 5));
        pixels[0] = 0xFF00FF00;
        assertEquals(0xFF00FF00, image.getRGB(0, 0));
    }
}
