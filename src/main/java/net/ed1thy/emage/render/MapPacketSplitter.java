package net.ed1thy.emage.render;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;

import java.util.ArrayList;
import java.util.List;

final class MapPacketSplitter {

    private static final int HEADER_ALLOWANCE = 16;

    private MapPacketSplitter() {}

    static List<ByteBuf> split(ByteBuf packet, int maxBytes) {
        int start = packet.readerIndex();
        int p = start;
        if (packet.writerIndex() - p < 12) return null;
        while ((packet.getByte(p++) & 0x80) != 0) {
            if (p >= packet.writerIndex()) return null;
        }
        int idEnd = p;
        if (packet.getByte(idEnd + 2) != 0) return null;
        int q = idEnd + 3;
        int width = packet.getUnsignedByte(q++);
        int height = packet.getUnsignedByte(q++);
        int x = packet.getUnsignedByte(q++);
        int z = packet.getUnsignedByte(q++);
        int length = 0;
        int shift = 0;
        byte b;
        do {
            if (q >= packet.writerIndex()) return null;
            b = packet.getByte(q++);
            length |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        int dataStart = q;
        if (width <= 0 || height <= 0 || length != width * height || dataStart + length > packet.writerIndex()) return null;
        if (x + width > 128 || z + height > 128) return null;

        int rows = Math.max(1, (maxBytes - HEADER_ALLOWANCE) / width);
        if (rows >= height) return null;

        List<ByteBuf> bands = new ArrayList<>();
        for (int row = 0; row < height; row += rows) {
            int bandRows = Math.min(rows, height - row);
            int bandLength = width * bandRows;
            ByteBuf out = PooledByteBufAllocator.DEFAULT.directBuffer(HEADER_ALLOWANCE + bandLength);
            out.writeBytes(packet, start, idEnd - start);
            out.writeByte(packet.getByte(idEnd));
            out.writeByte(packet.getByte(idEnd + 1));
            out.writeByte(0);
            out.writeByte(width);
            out.writeByte(bandRows);
            out.writeByte(x);
            out.writeByte(z + row);
            writeVarInt(out, bandLength);
            out.writeBytes(packet, dataStart + row * width, bandLength);
            bands.add(out);
        }
        return bands;
    }

    private static void writeVarInt(ByteBuf buf, int value) {
        while ((value & -128) != 0) {
            buf.writeByte(value & 127 | 128);
            value >>>= 7;
        }
        buf.writeByte(value);
    }
}
