package com.geoplan.rfid.agent.reader;

import com.geoplan.rfid.agent.config.AgentConfig;
import com.geoplan.rfid.agent.util.Log;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fake reader for desk smoke tests with no R400 attached. Emits a small rotating
 * set of EPCs, repeating them so the dedupe path gets exercised too.
 * Enabled with READER_MODE=simulator.
 */
public final class SimulatedTagReader implements TagReader {

    private final AgentConfig.ReaderConfig readerConfig;
    private final AtomicInteger counter = new AtomicInteger();
    private final ScheduledExecutorService ticker;

    private volatile EpcListener listener;
    private volatile ScheduledFuture<?> tick;

    public SimulatedTagReader(AgentConfig config) {
        this(config.primaryReader());
    }

    public SimulatedTagReader(AgentConfig.ReaderConfig readerConfig) {
        this.readerConfig = readerConfig;
        this.ticker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "simulated-reader-" + readerConfig.readerName());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void setListener(EpcListener listener) {
        this.listener = listener;
    }

    @Override
    public boolean connect() {
        return true;
    }

    @Override
    public synchronized boolean startInventory() {
        if (tick != null) {
            return true;
        }

        tick = ticker.scheduleWithFixedDelay(
                this::emit,
                0,
                readerConfig.simulatedIntervalMs(),
                TimeUnit.MILLISECONDS
        );

        Log.info("Simulated inventory started (" + readerConfig.readerName() + ": "
                + readerConfig.simulatedTagCount() + " tags every "
                + readerConfig.simulatedIntervalMs() + "ms)");

        return true;
    }

    @Override
    public synchronized void stopInventory() {
        if (tick == null) {
            return;
        }

        tick.cancel(false);
        tick = null;

        Log.info("Simulated inventory stopped (" + readerConfig.readerName() + ")");
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public boolean isInventoryRunning() {
        return tick != null;
    }

    @Override
    public void close() {
        stopInventory();
        ticker.shutdownNow();
    }

    @Override
    public String describe() {
        return "simulator:" + readerConfig.readerName();
    }

    private void emit() {
        EpcListener target = listener;

        if (target == null) {
            return;
        }

        int index = counter.getAndIncrement() % readerConfig.simulatedTagCount();

        target.onEpc(String.format("E2801170%02d0000%02d", readerConfig.index() % 100, index + 1));
    }
}
