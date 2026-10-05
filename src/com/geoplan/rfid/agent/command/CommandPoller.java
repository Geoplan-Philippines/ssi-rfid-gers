package com.geoplan.rfid.agent.command;

import com.geoplan.rfid.agent.config.AgentConfig;
import com.geoplan.rfid.agent.scan.ScanCoordinator;
import com.geoplan.rfid.agent.util.Log;

/**
 * Keeps the cloud-to-desk control path outbound-only. Commands are durable in
 * the middleware and idempotent in ScanCoordinator, so lease redelivery is
 * safe after a lost response or acknowledgement.
 */
public final class CommandPoller {

    private record Outcome(boolean succeeded, String error) {
        static Outcome success() {
            return new Outcome(true, null);
        }

        static Outcome failure(String error) {
            return new Outcome(false, error);
        }
    }

    private final AgentConfig config;
    private final ScanCoordinator coordinator;
    private final ReaderCommandClient client;

    private volatile boolean running;
    private Thread thread;

    public CommandPoller(AgentConfig config, ScanCoordinator coordinator) {
        this.config = config;
        this.coordinator = coordinator;
        this.client = new ReaderCommandClient(config);
    }

    public void start() {
        if (config.readerId.isEmpty()) {
            Log.warn("Command poller not started because READER_ID is missing");
            return;
        }
        if (running) {
            return;
        }

        running = true;
        thread = new Thread(this::runLoop, "command-poller");
        thread.setDaemon(true);
        thread.start();
        Log.info("Outbound command polling started for reader " + config.readerId);
    }

    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void runLoop() {
        while (running) {
            try {
                ReaderCommand command = client.poll(coordinator.health());
                if (command == null) {
                    continue;
                }

                Log.info("Claimed " + command.type() + " command " + command.commandId()
                        + " for session " + command.sessionId());
                Outcome outcome = execute(command);
                client.acknowledge(
                        command,
                        outcome.succeeded(),
                        outcome.error(),
                        coordinator.health()
                );
                if (outcome.succeeded()) {
                    Log.info("Completed " + command.type() + " command " + command.commandId());
                } else {
                    Log.warn("Failed " + command.type() + " command " + command.commandId()
                            + ": " + outcome.error());
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception error) {
                if (!running) {
                    return;
                }
                Log.warn("Command polling failed: " + error.getClass().getSimpleName()
                        + " " + error.getMessage());
                sleepBeforeRetry();
            }
        }
    }

    private Outcome execute(ReaderCommand command) {
        return switch (command.type()) {
            case START -> executeStart(command.sessionId());
            case STOP -> executeStop(command.sessionId());
        };
    }

    private Outcome executeStart(String sessionId) {
        ScanCoordinator.StartResult result = coordinator.start(sessionId);
        return switch (result.status()) {
            case STARTED, ALREADY_RUNNING, REPLACED -> Outcome.success();
            case CONFLICT -> Outcome.failure(
                    "another session is active: " + result.replacedSessionId()
            );
            case READER_UNAVAILABLE -> Outcome.failure("reader is unavailable");
        };
    }

    private Outcome executeStop(String sessionId) {
        ScanCoordinator.StopResult result = coordinator.stop(sessionId);
        return switch (result.status()) {
            case NOT_RUNNING -> Outcome.success();
            case SESSION_MISMATCH -> Outcome.failure(
                    "active session is " + result.activeSessionId()
            );
            case STOPPED -> result.pending() == 0
                    ? Outcome.success()
                    : Outcome.failure(result.pending() + " EPCs remain unflushed");
        };
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(config.commandRetryDelayMs);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
