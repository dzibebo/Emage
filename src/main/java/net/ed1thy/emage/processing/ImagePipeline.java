package net.ed1thy.emage.processing;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import net.ed1thy.emage.model.DeltaFrame;
import net.ed1thy.emage.model.MapFrameUpdate;
import net.ed1thy.emage.processing.dither.BlueNoiseDither;
import net.ed1thy.emage.storage.FlatFileStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBufferInt;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import java.util.stream.IntStream;

public class ImagePipeline {

    private static final int MAP_SIZE = 128;
    private static final int MAX_FRAMES_BEING_SAVED = 4;

    private final ColorPalette lut;
    private final FlatFileStorage storage;
    private final BlueNoiseDither dither;
    private final ExecutorService vtExecutor;

    public ImagePipeline(@NotNull ColorPalette lut, @NotNull FlatFileStorage storage, @NotNull ExecutorService vtExecutor, @NotNull ForkJoinPool computePool) {
        this.lut = lut;
        this.storage = storage;
        this.dither = new BlueNoiseDither(computePool);
        this.vtExecutor = vtExecutor;
    }

    public void shutdown() {}

    private void writeVarInt(ByteBuf buf, int value) {
        while ((value & -128) != 0) {
            buf.writeByte(value & 127 | 128);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    static BufferedImage wrapPixels(int[] pixels, int width, int height) {
        DataBufferInt buffer = new DataBufferInt(pixels, width * height);
        WritableRaster raster = Raster.createPackedRaster(buffer, width, height, width,
                new int[]{0x00FF0000, 0x0000FF00, 0x000000FF, 0xFF000000}, null);
        BufferedImage image = new BufferedImage(ColorModel.getRGBdefault(), raster, false, null);
        buffer.getData();
        return image;
    }

    private static Graphics2D createBandGraphics(BufferedImage band) {
        Graphics2D g = band.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        g.setComposite(AlphaComposite.Src);
        return g;
    }

    public CompletableFuture<Map<Integer, MapFrameUpdate>> processStreamAsync(
            @NotNull ImageFrameProvider decoder, int syncGroupId, @NotNull List<Integer> virtualMapIds, int columns, int rows,
            @Nullable Consumer<Double> progressCallback) {

        return CompletableFuture.supplyAsync(() -> {
            Map<Integer, MapFrameUpdate> firstFrameMap = new ConcurrentHashMap<>();
            List<CompletableFuture<Void>> ioTasks = new ArrayList<>();
            ArrayDeque<CompletableFuture<Void>> framesBeingSaved = new ArrayDeque<>();
            Graphics2D bandGraphics = null;

            try (decoder) {
                while (!lut.isReady()) {
                    Thread.sleep(50);
                }

                int totalWidth = columns * MAP_SIZE;
                int totalHeight = rows * MAP_SIZE;

                BufferedImage band = new BufferedImage(totalWidth, MAP_SIZE, BufferedImage.TYPE_INT_ARGB);
                bandGraphics = createBandGraphics(band);
                int[] bandPixels = ((DataBufferInt) band.getRaster().getDataBuffer()).getData();
                byte[] bandColors = new byte[totalWidth * MAP_SIZE];

                byte[][] prevMapFrames = new byte[virtualMapIds.size()][];
                byte[][] currMapFrames = new byte[virtualMapIds.size()][];
                for (int i = 0; i < virtualMapIds.size(); i++) {
                    prevMapFrames[i] = new byte[16384];
                    currMapFrames[i] = new byte[16384];
                }

                int totalFramesCount = decoder.getFrameCount();

                int[] frameDelays = new int[totalFramesCount];
                for (int i = 0; i < totalFramesCount; i++) {
                    frameDelays[i] = decoder.getFrameDelayMs(i);
                }
                ioTasks.add(storage.saveFrameDelaysAsync(syncGroupId, frameDelays));

                for (int frameIndex = 0; frameIndex < totalFramesCount; frameIndex++) {
                    BufferedImage source = decoder.getFrameImage(frameIndex);
                    if (source == null) {
                        source = wrapPixels(decoder.getFramePixels(frameIndex), decoder.getFrameWidth(frameIndex), decoder.getFrameHeight(frameIndex));
                    }

                    final boolean firstFrame = (frameIndex == 0);
                    final int fIndex = frameIndex;
                    Map<Integer, MapFrameUpdate> frameUpdates = new ConcurrentHashMap<>();

                    try {
                        for (int row = 0; row < rows; row++) {
                            bandGraphics.setTransform(AffineTransform.getTranslateInstance(0, -(double) row * MAP_SIZE));
                            bandGraphics.drawImage(source, 0, 0, totalWidth, totalHeight, null);
                            dither.applyDither(bandPixels, totalWidth, MAP_SIZE, lut, bandColors);

                            final int bandRow = row;
                            IntStream.range(0, columns).parallel().forEach(col -> {
                                int mapIndex = bandRow * columns + col;
                                int mapId = virtualMapIds.get(mapIndex);

                                byte[] prevMap = prevMapFrames[mapIndex];
                                byte[] currMap = currMapFrames[mapIndex];

                                if (firstFrame) {
                                    extractMapArray(bandColors, totalWidth, col * MAP_SIZE, 0, currMap);
                                    ByteBuf packetBuf = PooledByteBufAllocator.DEFAULT.directBuffer(16384 + 16);
                                    writeVarInt(packetBuf, mapId);
                                    packetBuf.writeByte(0);
                                    packetBuf.writeBoolean(true);
                                    packetBuf.writeBoolean(false);
                                    packetBuf.writeByte(128);
                                    packetBuf.writeByte(128);
                                    packetBuf.writeByte(0);
                                    packetBuf.writeByte(0);
                                    writeVarInt(packetBuf, 16384);
                                    packetBuf.writeBytes(currMap);

                                    DeltaFrame fullPart = new DeltaFrame(fIndex, mapId, packetBuf);
                                    MapFrameUpdate update = new MapFrameUpdate(new DeltaFrame[]{fullPart});

                                    frameUpdates.put(mapId, update);
                                    firstFrameMap.put(mapId, update);

                                    currMapFrames[mapIndex] = prevMap;
                                    prevMapFrames[mapIndex] = currMap;
                                } else {
                                    MapTileData tileData = extractMapArrayWithBounds(bandColors, totalWidth, col * MAP_SIZE, 0, prevMap, currMap);
                                    if (tileData.maxX != -1) {
                                        MapFrameUpdate update = calculateDelta(fIndex, mapId, tileData.mapTile, tileData.minX, tileData.minY, tileData.maxX, tileData.maxY);
                                        frameUpdates.put(mapId, update);
                                    }
                                    currMapFrames[mapIndex] = prevMap;
                                    prevMapFrames[mapIndex] = currMap;
                                }
                            });
                        }
                    } catch (Exception e) {
                        for (MapFrameUpdate update : frameUpdates.values()) {
                            update.freeMemory();
                        }
                        throw e;
                    }

                    CompletableFuture<Void> saved = storage.saveBundledFrameAsync(syncGroupId, fIndex, frameUpdates).whenComplete((v, ex) -> {
                        if (fIndex != 0) {
                            for (MapFrameUpdate update : frameUpdates.values()) {
                                update.freeMemory();
                            }
                        }
                    });
                    ioTasks.add(saved);
                    framesBeingSaved.add(saved);
                    while (framesBeingSaved.size() > MAX_FRAMES_BEING_SAVED) {
                        framesBeingSaved.poll().join();
                    }

                    if (progressCallback != null) {
                        progressCallback.accept((double) (frameIndex + 1) / totalFramesCount);
                    }
                }

                CompletableFuture.allOf(ioTasks.toArray(new CompletableFuture[0])).join();

            } catch (Exception e) {
                for (MapFrameUpdate update : firstFrameMap.values()) {
                    update.freeMemory();
                }
                throw new RuntimeException("Failed to process image stream: " + e.getMessage(), e);
            } finally {
                if (bandGraphics != null) bandGraphics.dispose();
            }
            return firstFrameMap;
        }, vtExecutor);
    }

    private void extractMapArray(byte[] fullGrid, int gridWidth, int startX, int startY, byte[] outMapTile) {
        for (int y = 0; y < 128; y++) {
            System.arraycopy(fullGrid, (startY + y) * gridWidth + startX, outMapTile, y * 128, 128);
        }
    }

    private MapTileData extractMapArrayWithBounds(byte[] fullGrid, int gridWidth, int startX, int startY, byte[] prev, byte[] curr) {
        int minX = 128, minY = 128, maxX = -1, maxY = -1;
        for (int y = 0; y < 128; y++) {
            int srcPos = (startY + y) * gridWidth + startX;
            int destPos = y * 128;
            System.arraycopy(fullGrid, srcPos, curr, destPos, 128);
            for (int x = 0; x < 128; x++) {
                if (curr[destPos + x] != prev[destPos + x]) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        return new MapTileData(curr, minX, minY, maxX, maxY);
    }

    private MapFrameUpdate calculateDelta(int frameIndex, int mapId, byte[] curr, int minX, int minY, int maxX, int maxY) {
        int updateWidth = (maxX - minX) + 1;
        int updateHeight = (maxY - minY) + 1;

        ByteBuf packetBuf = PooledByteBufAllocator.DEFAULT.directBuffer(updateWidth * updateHeight + 18);
        writeVarInt(packetBuf, mapId);
        packetBuf.writeByte(0);
        packetBuf.writeBoolean(true);
        packetBuf.writeBoolean(false);
        packetBuf.writeByte(updateWidth);
        packetBuf.writeByte(updateHeight);
        packetBuf.writeByte(minX);
        packetBuf.writeByte(minY);
        writeVarInt(packetBuf, updateWidth * updateHeight);

        for (int y = 0; y < updateHeight; y++) {
            packetBuf.writeBytes(curr, (minY + y) * 128 + minX, updateWidth);
        }

        DeltaFrame part = new DeltaFrame(frameIndex, mapId, packetBuf);
        return new MapFrameUpdate(new DeltaFrame[]{part});
    }

    private record MapTileData(byte[] mapTile, int minX, int minY, int maxX, int maxY) {}
}
