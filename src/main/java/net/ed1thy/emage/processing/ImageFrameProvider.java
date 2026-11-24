package net.ed1thy.emage.processing;

public interface ImageFrameProvider extends AutoCloseable {
    int getFrameCount();
    int getDelayMs();
    int getFrameDelayMs(int index);
    int[] getFramePixels(int index);
    int getFrameWidth(int index);
    int getFrameHeight(int index);

    default java.awt.image.BufferedImage getFrameImage(int index) {
        return null;
    }

    @Override
    void close() throws Exception;
}
