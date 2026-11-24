package net.ed1thy.emage.processing;

import java.util.Arrays;

public class GifDecoder implements ImageFrameProvider {

    private static final int HARD_FRAME_LIMIT = 5000;

    private byte[] in;
    private int p;
    private int width;
    private int height;
    private boolean gctFlag;
    private int gctSize;
    private int[] gct;
    private int[] act;
    private int bgIndex;
    private int bgColor;
    private int lastBgColor;
    private int transIndex;
    private boolean interlace;
    private int ix, iy, iw, ih;
    private int lrx, lry, lrw, lrh;
    private final byte[] block = new byte[256];
    private int blockSize = 0;
    private int dispose = 0;
    private int lastDispose = 0;
    private boolean transparency = false;
    private int delay = 0;

    private byte[] pixels;
    private int[] mainPixels;
    private int[] lastPixels;

    private short[] prefix;
    private byte[] suffix;
    private byte[] pixelStack;

    private final int maxFrames;

    private int contentStart;
    private int frameTotal;
    private int[] delays = new int[16];
    private boolean staticImage;

    private int decodedFrames;
    private int[] staticPixels;

    public GifDecoder() {
        this(240);
    }

    public GifDecoder(int maxFrames) {
        this.maxFrames = Math.max(1, maxFrames);
    }

    public void read(byte[] data) {
        if (data == null) return;
        this.in = data;
        this.p = 0;
        readHeader();
        if (width <= 0 || height <= 0 || err()) return;

        contentStart = p;
        scan();
    }

    @Override public int getFrameCount() { return frameTotal; }

    @Override public int getDelayMs() { return frameTotal == 0 ? 100 : getFrameDelayMs(0); }

    @Override public int getFrameDelayMs(int index) {
        if (index < 0 || index >= frameTotal) return 100;
        int d = delays[index];
        return d <= 0 ? 100 : d;
    }

    @Override public int getFrameWidth(int index) { return width; }

    @Override public int getFrameHeight(int index) { return height; }

    @Override
    public int[] getFramePixels(int n) {
        if (n < 0 || n >= frameTotal) return new int[width * height];

        if (staticImage) {
            if (staticPixels == null) {
                resetDecode();
                if (nextFrame()) {
                    staticPixels = Arrays.copyOf(mainPixels, mainPixels.length);
                } else {
                    staticPixels = new int[width * height];
                }
                releaseWorkingBuffers();
            }
            return staticPixels;
        }

        if (mainPixels == null || n < decodedFrames - 1) {
            resetDecode();
        }
        while (decodedFrames <= n) {
            if (!nextFrame()) break;
            decodedFrames++;
        }
        return mainPixels;
    }

    @Override public void close() { releaseWorkingBuffers(); in = null; staticPixels = null; }

    private void scan() {
        resetDecode();

        long firstHash = 0;
        boolean allIdentical = true;
        int count = 0;

        while (nextFrame()) {
            long hash = fingerprint(mainPixels);
            if (count == 0) {
                firstHash = hash;
            } else if (hash != firstHash) {
                allIdentical = false;
            }
            if (count == delays.length) delays = Arrays.copyOf(delays, count * 2);
            delays[count] = delay;
            count++;

            if (!allIdentical && count > maxFrames) break;
            if (count >= HARD_FRAME_LIMIT) break;
        }

        staticImage = count > 1 && allIdentical && count < HARD_FRAME_LIMIT;
        frameTotal = staticImage ? 1 : count;

        resetDecode();
        if (frameTotal <= 1) {
            releaseWorkingBuffers();
        }
    }

    private static long fingerprint(int[] data) {
        long h = 0xcbf29ce484222325L;
        for (int v : data) {
            h = (h ^ v) * 0x100000001b3L;
        }
        return h;
    }

    private void resetDecode() {
        p = contentStart;
        dispose = 0;
        lastDispose = 0;
        transparency = false;
        delay = 0;
        transIndex = 0;
        ix = iy = iw = ih = 0;
        lrx = lry = lrw = lrh = 0;
        lastBgColor = 0;
        bgColor = (gctFlag && gct != null && bgIndex < gct.length) ? gct[bgIndex] : 0;
        act = gct;
        blockSize = 0;
        decodedFrames = 0;
        if (mainPixels == null) {
            mainPixels = new int[width * height];
            lastPixels = new int[width * height];
        } else {
            Arrays.fill(mainPixels, 0);
            Arrays.fill(lastPixels, 0);
        }
    }

    private void releaseWorkingBuffers() {
        mainPixels = null;
        lastPixels = null;
        pixels = null;
        decodedFrames = 0;
    }

    private boolean err() { return in == null || p >= in.length; }
    private int read() { return err() ? 0 : in[p++] & 0xFF; }
    private int readShort() { return read() | (read() << 8); }

    private void readBlock() {
        blockSize = read();
        int n = 0;
        if (blockSize > 0) {
            try {
                int count;
                while (n < blockSize) {
                    count = blockSize - n;
                    System.arraycopy(in, p, block, n, count);
                    p += count;
                    n += count;
                }
            } catch (Exception ignored) {
                p = in.length;
            }
        }
    }

    private void readHeader() {
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < 6; i++) id.append((char) read());
        if (!id.toString().startsWith("GIF")) return;
        width = readShort();
        height = readShort();

        if (width > 4096 || height > 4096 || width <= 0 || height <= 0) {
            throw new SecurityException("Security Exception: GIF dimensions (" + width + "x" + height + ") exceed the safe limit of 4096x4096 pixels.");
        }

        int packed = read();
        gctFlag = (packed & 0x80) != 0;
        gctSize = 2 << (packed & 7);
        bgIndex = read();
        read();
        if (gctFlag) {
            gct = readColorTable(gctSize);
            bgColor = gct[bgIndex];
        }
    }

    private int[] readColorTable(int ncolors) {
        int[] tab = new int[256];
        for (int i = 0; i < ncolors; i++) {
            int r = read(); int g = read(); int b = read();
            tab[i] = 0xff000000 | (r << 16) | (g << 8) | b;
        }
        return tab;
    }

    private boolean nextFrame() {
        while (!err()) {
            int code = read();
            switch (code) {
                case 0x2C:
                    if (readImage()) return true;
                    break;
                case 0x21:
                    code = read();
                    switch (code) {
                        case 0xF9:
                            read();
                            int packed = read();
                            dispose = (packed & 0x1c) >> 2;
                            if (dispose == 0) dispose = 1;
                            transparency = (packed & 1) != 0;
                            delay = readShort() * 10;
                            transIndex = read();
                            read();
                            break;
                        default: skip();
                    }
                    break;
                case 0x3B: return false;
                case 0x00: break;
                default: skip();
            }
        }
        return false;
    }

    private void skip() {
        do { readBlock(); } while (blockSize > 0 && !err());
    }

    private boolean readImage() {
        ix = readShort(); iy = readShort(); iw = readShort(); ih = readShort();
        int packed = read();
        boolean lctFlag = (packed & 0x80) != 0;
        interlace = (packed & 0x40) != 0;
        int lctSize = 2 << (packed & 7);
        if (lctFlag) act = readColorTable(lctSize);
        else {
            act = gct;
            if (bgIndex == transIndex) bgColor = 0;
        }
        if (act == null) {
            read();
            skip();
            return false;
        }
        int save = 0;
        if (transparency) {
            save = act[transIndex];
            act[transIndex] = 0;
        }
        decodeImageData();
        skip();
        setPixels();

        if (transparency) act[transIndex] = save;

        lastDispose = dispose;
        lrx = ix; lry = iy; lrw = iw; lrh = ih;
        lastBgColor = bgColor;
        return true;
    }

    private void setPixels() {
        if (lastDispose > 0) {
            if (lastDispose == 3) {
                System.arraycopy(lastPixels, 0, mainPixels, 0, width * height);
            } else if (lastDispose == 2) {
                int c = transparency ? 0 : lastBgColor;
                for (int i = 0; i < lrh; i++) {
                    int line = (lry + i) * width + lrx;
                    for (int j = 0; j < lrw; j++) {
                        int pos = line + j;
                        if (pos >= 0 && pos < mainPixels.length) mainPixels[pos] = c;
                    }
                }
            }
        }

        if (dispose == 3) {
            System.arraycopy(mainPixels, 0, lastPixels, 0, width * height);
        }

        int pass = 1, inc = 8, iline = 0;
        for (int i = 0; i < ih; i++) {
            int line = i;
            if (interlace) {
                if (iline >= ih) {
                    pass++;
                    switch (pass) {
                        case 2: iline = 4; break;
                        case 3: iline = 2; inc = 4; break;
                        case 4: iline = 1; inc = 2; break;
                    }
                }
                line = iline;
                iline += inc;
            }
            line += iy;
            if (line < height) {
                int k = line * width;
                int dx = k + ix;
                int dmax = dx + iw;
                if (k + width < dmax) dmax = k + width;
                int sx = i * iw;
                while (dx < dmax) {
                    int index = ((int) pixels[sx++]) & 0xff;
                    int c = act[index];
                    if (c != 0) mainPixels[dx] = c;
                    dx++;
                }
            }
        }
    }

    private void decodeImageData() {
        int npix = iw * ih;
        if (pixels == null || pixels.length < npix) pixels = new byte[npix];
        if (prefix == null) prefix = new short[4096];
        if (suffix == null) suffix = new byte[4096];
        if (pixelStack == null) pixelStack = new byte[4097];
        int data_size = read();
        int clear = 1 << data_size;
        int end_of_information = clear + 1;
        int available = clear + 2;
        int old_code = -1;
        int code_size = data_size + 1;
        int code_mask = (1 << code_size) - 1;
        for (int code = 0; code < clear; code++) { prefix[code] = 0; suffix[code] = (byte) code; }
        int datum = 0, bits = 0, count = 0, first = 0, top = 0, pi = 0, bi = 0;
        for (int i = 0; i < npix; ) {
            if (top == 0) {
                if (bits < code_size) {
                    if (count == 0) {
                        readBlock();
                        if (blockSize <= 0) break;
                        count = blockSize;
                        bi = 0;
                    }
                    datum += (((int) block[bi]) & 0xff) << bits;
                    bits += 8; bi++; count--; continue;
                }
                int code = datum & code_mask;
                datum >>= code_size; bits -= code_size;
                if (code > available || code == end_of_information) break;
                if (code == clear) {
                    code_size = data_size + 1;
                    code_mask = (1 << code_size) - 1;
                    available = clear + 2;
                    old_code = -1;
                    continue;
                }
                if (old_code == -1) {
                    pixelStack[top++] = suffix[code];
                    old_code = code; first = code;
                    continue;
                }
                int in_code = code;
                if (code == available) {
                    pixelStack[top++] = (byte) first;
                    code = old_code;
                }
                while (code > clear) {
                    pixelStack[top++] = suffix[code];
                    code = prefix[code];
                }
                first = ((int) suffix[code]) & 0xff;
                if (available >= 4096) break;
                pixelStack[top++] = (byte) first;
                prefix[available] = (short) old_code;
                suffix[available] = (byte) first;
                available++;
                if (((available & code_mask) == 0) && (available < 4096)) {
                    code_size++;
                    code_mask += available;
                }
                old_code = in_code;
            }
            top--;
            pixels[pi++] = pixelStack[top];
            i++;
        }
        for (int i = pi; i < npix; i++) pixels[i] = 0;
    }
}
