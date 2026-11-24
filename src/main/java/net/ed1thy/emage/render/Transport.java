package net.ed1thy.emage.render;

import net.ed1thy.emage.model.DeltaFrame;
import org.jetbrains.annotations.NotNull;

interface Transport {

    boolean isWritable();

    long pendingBytes();

    boolean write(@NotNull DeltaFrame source);

    void flush();

    boolean supportsProbe();

    boolean sendProbe(int id);
}
