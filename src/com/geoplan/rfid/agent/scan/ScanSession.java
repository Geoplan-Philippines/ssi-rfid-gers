package com.geoplan.rfid.agent.scan;

import com.geoplan.rfid.agent.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One middleware scan session. Holds the EPCs seen so far and the batch that is
 * still waiting to be pushed.
 *
 * The middleware dedupes server side as well. The set here exists so the agent
 * does not resend the same EPC on every inventory tick.
 */
public final class ScanSession {

    /** Serialises flushes so a background flush and a stop flush cannot interleave. */
    final Object flushLock = new Object();

    private final String sessionId;

    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<String> pending = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingCount = new AtomicInteger();

    private final AtomicLong sentCount = new AtomicLong();
    private final AtomicLong droppedCount = new AtomicLong();
    private final AtomicLong overflowCount = new AtomicLong();

    private volatile boolean accepting = true;

    ScanSession(String sessionId) {
        this.sessionId = sessionId;
    }

    public String sessionId() {
        return sessionId;
    }

    void stopAccepting() {
        accepting = false;
    }

    /** Queues an EPC the first time it is seen. Returns true when it was new. */
    boolean offer(String epc, int maxPending) {
        if (!accepting || !seen.add(epc)) {
            return false;
        }

        if (pendingCount.get() >= maxPending) {
            long overflows = overflowCount.incrementAndGet();

            if (overflows == 1 || overflows % 1000 == 0) {
                Log.warn("Pending EPC queue is full for session " + sessionId
                        + " (" + maxPending + "). Dropped " + overflows + " reads so far."
                        + " Is the middleware reachable?");
            }

            return false;
        }

        pending.add(epc);
        pendingCount.incrementAndGet();

        return true;
    }

    List<String> drain(int max) {
        List<String> batch = new ArrayList<>(Math.min(max, pendingCount.get() + 1));

        for (int index = 0; index < max; index++) {
            String epc = pending.poll();

            if (epc == null) {
                break;
            }

            pendingCount.decrementAndGet();
            batch.add(epc);
        }

        return batch;
    }

    void requeue(List<String> epcs) {
        for (String epc : epcs) {
            pending.add(epc);
            pendingCount.incrementAndGet();
        }
    }

    void recordSent(int count) {
        sentCount.addAndGet(count);
    }

    void recordDropped(int count) {
        droppedCount.addAndGet(count);
    }

    public int pending() {
        return pendingCount.get();
    }

    public long unique() {
        return seen.size();
    }

    public long sent() {
        return sentCount.get();
    }

    public long dropped() {
        return droppedCount.get();
    }

    public String summary() {
        return "session " + sessionId
                + " | unique=" + unique()
                + " | sent=" + sent()
                + " | pending=" + pending()
                + " | dropped=" + dropped();
    }
}
