package net.ed1thy.emage.model;

public final class MapFrameUpdate {
    private final DeltaFrame[] parts;
    private final int totalBytes;

    public MapFrameUpdate(DeltaFrame[] parts) {
        this.parts = parts;
        int bytes = 0;
        for (DeltaFrame df : parts) {
            if (df.packetBuf() != null) {
                bytes += df.packetBuf().readableBytes();
            }
        }
        this.totalBytes = bytes;
    }

    public DeltaFrame[] parts() {
        return parts;
    }

    public int totalBytes() {
        return totalBytes;
    }

    public void freeMemory() {
        for (DeltaFrame df : parts) {
            df.freeMemory();
        }
    }

    public void retain() {
        for (DeltaFrame df : parts) {
            df.retain();
        }
    }

    public boolean isFreed() {
        for (DeltaFrame df : parts) {
            if (df.packetBuf() == null || df.packetBuf().refCnt() <= 0) {
                return true;
            }
        }
        return false;
    }
}