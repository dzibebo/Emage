package net.ed1thy.emage.render;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.ed1thy.emage.model.DeltaFrame;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerSendQueueTest {

    private static PlayerSendQueue newQueue() {
        return new PlayerSendQueue(new PlayerSendQueue.Tuning(16_384, 16_777_216, 98_304, 80, false));
    }

    private static DeltaFrame part(int mapId, int bytes) {
        ByteBuf buf = Unpooled.directBuffer(bytes);
        buf.writeZero(bytes);
        return new DeltaFrame(0, mapId, buf);
    }

    @Test
    void queueKeepsExactlyOneReferencePerQueuedPart() {
        PlayerSendQueue queue = newQueue();
        DeltaFrame a = part(1, 100);
        DeltaFrame b = part(2, 100);

        assertTrue(queue.add(a, PlayerSendQueue.TIER_ANIMATION));
        assertTrue(queue.add(b, 3));
        assertEquals(2, a.packetBuf().refCnt());
        assertEquals(2, b.packetBuf().refCnt());
        assertEquals(2, queue.size());

        queue.drain();

        assertEquals(1, a.packetBuf().refCnt(), "draining must give back every reference the queue took");
        assertEquals(1, b.packetBuf().refCnt());
        assertTrue(queue.isEmpty());
    }

    @Test
    void purgeDropsOnlyRequestedMapsInEveryTier() {
        PlayerSendQueue queue = newQueue();
        DeltaFrame a = part(1, 100);
        DeltaFrame b = part(2, 100);
        DeltaFrame c = part(1, 100);

        queue.add(a, 0);
        queue.add(b, 2);
        queue.add(c, 3);

        Set<Integer> dropped = queue.purge(Set.of(1));

        assertEquals(Set.of(1), dropped);
        assertEquals(1, queue.size());
        assertEquals(1, a.packetBuf().refCnt());
        assertEquals(1, c.packetBuf().refCnt());
        assertEquals(2, b.packetBuf().refCnt());

        queue.drain();
        assertEquals(1, b.packetBuf().refCnt());
    }

    @Test
    void closedQueueRefusesNewDataAndFreedBuffersAreRejected() {
        PlayerSendQueue queue = newQueue();
        DeltaFrame freed = part(5, 100);
        freed.packetBuf().release();
        assertFalse(queue.add(freed, 1), "a buffer that was already freed must not be queued");

        DeltaFrame live = part(6, 100);
        queue.close();
        assertFalse(queue.add(live, 1));
        assertEquals(1, live.packetBuf().refCnt(), "nothing may be retained by a closed queue");
    }

    @Test
    void animationDrainEstimateFollowsQueuedAnimationBytesOnly() {
        PlayerSendQueue queue = newQueue();
        double empty = queue.urgentDrainSeconds(0);
        assertEquals(0.0, empty, 1e-9);

        DeltaFrame animation = part(1, 65_536);
        DeltaFrame still = part(2, 65_536);
        queue.add(animation, PlayerSendQueue.TIER_ANIMATION);
        queue.add(still, 2);

        double withAnimation = queue.urgentDrainSeconds(0);
        assertEquals(65_536 / PlayerSendQueue.initialRate(new PlayerSendQueue.Tuning(16_384, 16_777_216, 98_304, 80, false)), withAnimation, 1e-6);

        queue.purge(Set.of(1));
        assertEquals(0.0, queue.urgentDrainSeconds(0), 1e-9, "still pictures must not count as animation backlog");
        queue.drain();
    }

    @Test
    void probeIdsAreRecognisedOnlyInTheirOwnRange() {
        assertTrue(PlayerSendQueue.isProbeId(0x4D450000));
        assertTrue(PlayerSendQueue.isProbeId(0x4D45FFFF));
        assertFalse(PlayerSendQueue.isProbeId(0));
        assertFalse(PlayerSendQueue.isProbeId(-1));
        assertFalse(PlayerSendQueue.isProbeId(0x4D460000));
    }
}
