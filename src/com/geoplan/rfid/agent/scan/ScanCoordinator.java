package com.geoplan.rfid.agent.scan;

import com.geoplan.rfid.agent.config.AgentConfig;
import com.geoplan.rfid.agent.middleware.AppendOutcome;
import com.geoplan.rfid.agent.middleware.MiddlewareClient;
import com.geoplan.rfid.agent.reader.EpcListener;
import com.geoplan.rfid.agent.reader.TagReader;
import com.geoplan.rfid.agent.util.Log;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns the one active scan session: starts and stops inventory, dedupes EPCs and
 * pushes batches to the middleware.
 *
 * Exactly one session is active at a time because the agent drives one reader.
 * A start for a different session while one is running follows
 * SCAN_START_CONFLICT (takeover by default, see README).
 */
public final class ScanCoordinator implements EpcListener {

    public enum StartStatus {
        /** Inventory is now running for the requested session. */
        STARTED,
        /** The same session was already running. Nothing changed. */
        ALREADY_RUNNING,
        /** Another session was stopped so this one could start. */
        REPLACED,
        /** Another session is running and the policy is reject. */
        CONFLICT,
        /** The reader could not be connected or inventory would not start. */
        READER_UNAVAILABLE
    }

    public enum StopStatus {
        /** The session was running and is now stopped. */
        STOPPED,
        /** Nothing was running. Treated as success so cancel stays idempotent. */
        NOT_RUNNING,
        /** A different session is running. It was left alone. */
        SESSION_MISMATCH
    }

    public record StartResult(StartStatus status, String sessionId, String replacedSessionId) {
    }

    public record StopResult(StopStatus status, String sessionId, String activeSessionId,
                             long unique, long sent, int pending) {
    }

    public record Health(boolean readerConnected, boolean inventoryRunning, String readerTarget,
                         String activeSessionId, long unique, long sent, int pending) {
    }

    /** Batches pushed per flush tick before yielding, so one tick cannot run away. */
    private static final int MAX_BATCHES_PER_TICK = 10;

    /** How long a stopped session keeps retrying its leftovers in the background. */
    private static final long DRAIN_GRACE_MS = 30_000;

    private final AgentConfig config;
    private final TagReader reader;
    private final MiddlewareClient middleware;

    private final Object lock = new Object();
    private final Map<ScanSession, Long> draining = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, task -> {
        Thread thread = new Thread(task, "scan-worker");
        thread.setDaemon(true);
        return thread;
    });

    private volatile ScanSession active;

    public ScanCoordinator(AgentConfig config, TagReader reader, MiddlewareClient middleware) {
        this.config = config;
        this.reader = reader;
        this.middleware = middleware;

        reader.setListener(this);
    }

    public void startBackgroundWork() {
        scheduler.scheduleWithFixedDelay(
                this::flushTick,
                config.flushIntervalMs,
                config.flushIntervalMs,
                TimeUnit.MILLISECONDS
        );

        scheduler.scheduleWithFixedDelay(
                this::watchdogTick,
                config.readerWatchdogIntervalMs,
                config.readerWatchdogIntervalMs,
                TimeUnit.MILLISECONDS
        );
    }

    public StartResult start(String sessionId) {
        synchronized (lock) {
            ScanSession current = active;

            if (current != null && current.sessionId().equals(sessionId)) {
                Log.info("Start ignored, already scanning " + sessionId);

                if (reader.isInventoryRunning() || reader.startInventory()) {
                    return new StartResult(StartStatus.ALREADY_RUNNING, sessionId, null);
                }

                return new StartResult(StartStatus.READER_UNAVAILABLE, sessionId, null);
            }

            String replacedSessionId = null;

            if (current != null) {
                if (config.startConflictPolicy == AgentConfig.StartConflictPolicy.REJECT) {
                    Log.warn("Start rejected for " + sessionId + ", session " + current.sessionId() + " is active");
                    return new StartResult(StartStatus.CONFLICT, sessionId, current.sessionId());
                }

                Log.warn("Start for " + sessionId + " takes over from active session " + current.sessionId());
                replacedSessionId = current.sessionId();
                active = null;
                closeSession(current, "replaced by " + sessionId);
            }

            if (!reader.startInventory()) {
                Log.error("Cannot start session " + sessionId + ", reader " + reader.describe() + " is unavailable");
                return new StartResult(StartStatus.READER_UNAVAILABLE, sessionId, replacedSessionId);
            }

            active = new ScanSession(sessionId);

            Log.info("Scanning for session " + sessionId + " on reader " + reader.describe());

            return new StartResult(
                    replacedSessionId == null ? StartStatus.STARTED : StartStatus.REPLACED,
                    sessionId,
                    replacedSessionId
            );
        }
    }

    public StopResult stop(String sessionId) {
        synchronized (lock) {
            ScanSession current = active;

            if (current == null) {
                ScanSession stopped = findDrainingSession(sessionId);
                if (stopped == null) {
                    Log.info("Stop for " + sessionId + " ignored, no session is active");
                    return new StopResult(StopStatus.NOT_RUNNING, sessionId, null, 0, 0, 0);
                }

                flush(stopped, System.currentTimeMillis() + config.stopFlushTimeoutMs);
                if (stopped.pending() == 0) {
                    draining.remove(stopped);
                }
                return new StopResult(StopStatus.STOPPED, sessionId, null,
                        stopped.unique(), stopped.sent(), stopped.pending());
            }

            if (!current.sessionId().equals(sessionId)) {
                Log.warn("Stop for " + sessionId + " ignored, active session is " + current.sessionId());
                return new StopResult(StopStatus.SESSION_MISMATCH, sessionId,
                        current.sessionId(), 0, 0, 0);
            }

            active = null;
            closeSession(current, "stopped by middleware");

            return new StopResult(StopStatus.STOPPED, sessionId, null,
                    current.unique(), current.sent(), current.pending());
        }
    }

    /** Stops whatever is running. Used on shutdown. */
    public void stopAll() {
        synchronized (lock) {
            ScanSession current = active;

            if (current != null) {
                active = null;
                closeSession(current, "agent shutting down");
            }

            scheduler.shutdownNow();
        }
    }

    @Override
    public void onEpc(String epc) {
        ScanSession current = active;

        if (current == null) {
            return;
        }

        if (current.offer(epc, config.maxPendingEpcs) && current.pending() >= config.batchSize) {
            scheduler.execute(this::flushTick);
        }
    }

    public Health health() {
        ScanSession current = active;

        return new Health(
                reader.isConnected(),
                reader.isInventoryRunning(),
                reader.describe(),
                current == null ? null : current.sessionId(),
                current == null ? 0 : current.unique(),
                current == null ? 0 : current.sent(),
                current == null ? 0 : current.pending()
        );
    }

    /**
     * Stops inventory, pushes what is left within a short budget, and hands any
     * remainder to the background drain. Callers hold the coordinator lock.
     */
    private void closeSession(ScanSession session, String reason) {
        session.stopAccepting();
        reader.stopInventory();

        long deadline = System.currentTimeMillis() + config.stopFlushTimeoutMs;
        flush(session, deadline);

        if (session.pending() > 0) {
            draining.put(session, System.currentTimeMillis() + DRAIN_GRACE_MS);
            Log.warn("Session " + session.sessionId() + " still has " + session.pending()
                    + " EPCs queued. Retrying in the background.");
        }

        Log.info("Session closed (" + reason + ") | " + session.summary());
    }

    private void flushTick() {
        try {
            ScanSession current = active;

            if (current != null) {
                flush(current, 0);
            }

            drainTick();
        } catch (Exception e) {
            Log.warn("Flush tick failed", e);
        }
    }

    private void drainTick() {
        for (Map.Entry<ScanSession, Long> entry : draining.entrySet()) {
            ScanSession session = entry.getKey();

            flush(session, 0);

            if (session.pending() == 0) {
                draining.remove(session);
                Log.info("Background drain finished | " + session.summary());
            } else if (System.currentTimeMillis() > entry.getValue()) {
                draining.remove(session);
                Log.error("Giving up on " + session.pending() + " unsent EPCs | " + session.summary());
            }
        }
    }

    /**
     * Pushes pending EPCs. With a deadline of 0 it makes a single pass and leaves
     * retries to the next tick. With a deadline it keeps retrying transient
     * failures until the clock runs out.
     */
    private void flush(ScanSession session, long deadlineMillis) {
        synchronized (session.flushLock) {
            int batches = 0;

            while (batches < MAX_BATCHES_PER_TICK || deadlineMillis > 0) {
                if (deadlineMillis > 0 && System.currentTimeMillis() > deadlineMillis) {
                    return;
                }

                List<String> batch = session.drain(config.batchSize);

                if (batch.isEmpty()) {
                    return;
                }

                batches++;

                AppendOutcome outcome = middleware.appendReads(session.sessionId(), batch);

                switch (outcome) {
                    case SENT -> session.recordSent(batch.size());
                    case DROPPED -> session.recordDropped(batch.size());
                    case RETRY -> {
                        session.requeue(batch);
                        if (deadlineMillis <= 0) {
                            return;
                        }
                        long remaining = deadlineMillis - System.currentTimeMillis();
                        if (remaining <= 0) {
                            return;
                        }
                        try {
                            Thread.sleep(Math.min(200, remaining));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    case SESSION_CLOSED -> {
                        session.requeue(batch);
                        abandon(session);
                        return;
                    }
                }
            }
        }
    }

    private ScanSession findDrainingSession(String sessionId) {
        for (ScanSession session : draining.keySet()) {
            if (session.sessionId().equals(sessionId)) {
                return session;
            }
        }
        return null;
    }

    /** The middleware says this session is closed. Stop scanning for it. */
    private void abandon(ScanSession session) {
        draining.remove(session);
        session.stopAccepting();

        scheduler.execute(() -> {
            synchronized (lock) {
                if (active != session) {
                    return;
                }

                active = null;
                reader.stopInventory();

                Log.warn("Stopped scanning, middleware closed the session | " + session.summary());
            }
        });
    }

    private void watchdogTick() {
        try {
            ScanSession current = active;

            if (current == null) {
                return;
            }

            if (reader.isConnected() && reader.isInventoryRunning()) {
                return;
            }

            synchronized (lock) {
                if (active != current) {
                    return;
                }

                Log.warn("Reader " + reader.describe() + " stopped reporting for session "
                        + current.sessionId() + ". Reconnecting.");

                if (reader.startInventory()) {
                    Log.info("Reader recovered for session " + current.sessionId());
                }
            }
        } catch (Exception e) {
            Log.warn("Reader watchdog failed", e);
        }
    }
}
