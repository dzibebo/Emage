package net.ed1thy.emage.render;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.ed1thy.emage.model.FrameNode;
import net.ed1thy.emage.model.MapFrameUpdate;
import net.ed1thy.emage.model.MapMetadata;
import net.ed1thy.emage.processing.ColorPalette;
import net.ed1thy.emage.processing.GifDecoder;
import net.ed1thy.emage.processing.ImagePipeline;
import net.ed1thy.emage.storage.FlatFileStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GifEndToEndTest {

    private static byte[] movingSquareGif(int frames, int delayCs) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            writer.prepareWriteSequence(null);
            for (int i = 0; i < frames; i++) {
                BufferedImage frame = new BufferedImage(256, 128, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = frame.createGraphics();
                g.setColor(new Color(20, 60, 160));
                g.fillRect(0, 0, 256, 128);
                g.setColor(Color.WHITE);
                g.fillRect(20 + i * 45, 40, 30, 30);
                g.dispose();

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

    private static final class RecordingSink extends AnimationDistributionTest.ClientSink {
    }

    @Test
    void aGifGoesThroughProcessingStorageAndDistributionAsARealAnimation(@TempDir File dir) throws Exception {
        int frames = 5;
        GifDecoder decoder = new GifDecoder(240);
        decoder.read(movingSquareGif(frames, 5));
        assertEquals(frames, decoder.getFrameCount());

        ExecutorService executor = Executors.newFixedThreadPool(3);
        ForkJoinPool compute = new ForkJoinPool(2);
        try {
            ColorPalette palette = new ColorPalette(compute);
            palette.generateLUT().join();
            FlatFileStorage storage = new FlatFileStorage(dir, executor);
            ImagePipeline pipeline = new ImagePipeline(palette, storage, executor, compute);

            List<Integer> mapIds = List.of(500, 501);
            Map<Integer, MapFrameUpdate> firstFrames = pipeline.processStreamAsync(decoder, 7, mapIds, 2, 1, null).join();
            assertEquals(2, firstFrames.size());

            for (int frame = 1; frame < frames; frame++) {
                assertTrue(storage.hasFrameData(7, frame), "frame " + frame + " of a moving gif must have stored changes");
            }

            Cache<Long, MapFrameUpdate> cache = Caffeine.newBuilder().maximumSize(1000).build();
            UUID worldId = UUID.randomUUID();
            List<FrameNode> nodes = new CopyOnWriteArrayList<>();
            nodes.add(new FrameNode(1, UUID.randomUUID(), worldId, 0, 0, 0, 64, 0, 500, null));
            nodes.add(new FrameNode(2, UUID.randomUUID(), worldId, 0, 0, 1, 64, 0, 501, null));
            MapMetadata meta = new MapMetadata(7, UUID.randomUUID(), "http://example/moving.gif", 2, 1, frames, 50);
            SyncGroup group = new SyncGroup(meta, nodes, storage, cache, executor, firstFrames, 96.0);

            UUID viewer = UUID.randomUUID();
            group.setViewerStateForTesting(viewer, true, true);
            RecordingSink sink = new RecordingSink();
            long now = 1_000_000L;
            for (int i = 0; i < 300; i++) {
                now += 60;
                if (group.shouldTick(now)) group.tick(now, sink);
                sink.transmit(0.06);
                Thread.sleep(3);
            }
            group.freezeClockForTesting();
            for (int i = 0; i < 20; i++) {
                now += 60;
                if (group.shouldTick(now)) group.tick(now, sink);
                sink.transmit(0.06);
                Thread.sleep(3);
            }

            assertTrue(sink.updates >= 10, "a moving gif must keep sending updates: " + sink.updates);
            for (int mapId : new int[]{500, 501}) {
                assertTrue(sink.client.containsKey(mapId), "map " + mapId + " was never delivered");
                org.junit.jupiter.api.Assertions.assertArrayEquals(group.canvasPixelsForTesting(mapId), sink.client.get(mapId),
                        "the client must show exactly what the server shows for map " + mapId);
            }
            assertTrue(sink.log.stream().anyMatch(entry -> entry[1] < 16384), "after the first picture only changed regions are sent");
            group.cleanup();
        } finally {
            executor.shutdownNow();
            compute.shutdownNow();
        }
    }
}
