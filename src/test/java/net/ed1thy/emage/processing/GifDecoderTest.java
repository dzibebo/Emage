package net.ed1thy.emage.processing;

import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GifDecoderTest {

    private static BufferedImage solid(int w, int h, Color color, int markerX) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.setColor(Color.WHITE);
        g.fillRect(markerX, 2, 3, 3);
        g.dispose();
        return img;
    }

    private static byte[] writeGif(List<BufferedImage> frames, int delayCs) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            writer.prepareWriteSequence(null);
            for (BufferedImage frame : frames) {
                IIOMetadata meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(frame), param);
                String format = meta.getNativeMetadataFormatName();
                IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(format);
                IIOMetadataNode gce = new IIOMetadataNode("GraphicControlExtension");
                gce.setAttribute("disposalMethod", "none");
                gce.setAttribute("userInputFlag", "FALSE");
                gce.setAttribute("transparentColorFlag", "FALSE");
                gce.setAttribute("delayTime", String.valueOf(delayCs));
                gce.setAttribute("transparentColorIndex", "0");
                root.appendChild(gce);
                meta.setFromTree(format, root);
                writer.writeToSequence(new IIOImage(frame, null, meta), param);
            }
            writer.endWriteSequence();
        }
        writer.dispose();
        return bytes.toByteArray();
    }

    @Test
    void identicalFramesAreReportedAsOneStillFrame() throws Exception {
        BufferedImage frame = solid(32, 24, new Color(200, 30, 30), 5);
        byte[] gif = writeGif(List.of(frame, frame, frame, frame), 10);

        GifDecoder decoder = new GifDecoder(240);
        decoder.read(gif);

        assertEquals(1, decoder.getFrameCount(), "a gif without any motion must be treated as a still picture");
        int[] pixels = decoder.getFramePixels(0);
        assertEquals(32 * 24, pixels.length);
        assertEquals(new Color(200, 30, 30).getRGB() & 0xFFFFFF, pixels[10 * 32 + 20] & 0xFFFFFF);
    }

    @Test
    void realAnimationKeepsEveryFrameAndDecodesInOrder() throws Exception {
        BufferedImage a = solid(32, 24, new Color(30, 30, 200), 4);
        BufferedImage b = solid(32, 24, new Color(30, 30, 200), 14);
        BufferedImage c = solid(32, 24, new Color(30, 30, 200), 24);
        byte[] gif = writeGif(List.of(a, b, c), 7);

        GifDecoder decoder = new GifDecoder(240);
        decoder.read(gif);

        assertEquals(3, decoder.getFrameCount());
        assertEquals(70, decoder.getFrameDelayMs(1));

        int[] f0 = decoder.getFramePixels(0).clone();
        int[] f1 = decoder.getFramePixels(1).clone();
        int[] f2 = decoder.getFramePixels(2).clone();
        assertNotEquals(f0[3 * 32 + 5], f1[3 * 32 + 5], "marker moved away from x=5 in frame 1");
        assertNotEquals(f1[3 * 32 + 15], f2[3 * 32 + 15], "marker moved away from x=15 in frame 2");

        int[] again0 = decoder.getFramePixels(0);
        assertTrue(java.util.Arrays.equals(f0, again0));
        int[] again2 = decoder.getFramePixels(2);
        assertTrue(java.util.Arrays.equals(f2, again2));
    }

    @Test
    void singleFrameGifStillDecodes() throws Exception {
        byte[] gif = writeGif(List.of(solid(16, 16, new Color(10, 200, 10), 3)), 10);

        GifDecoder decoder = new GifDecoder(240);
        decoder.read(gif);

        assertEquals(1, decoder.getFrameCount());
        int[] pixels = decoder.getFramePixels(0);
        assertEquals(16 * 16, pixels.length);
        assertNotEquals(0, pixels[8 * 16 + 8]);
    }
}
