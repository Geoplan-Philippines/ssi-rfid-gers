package com.geoplan.rfid.agent;

import com.geoplan.rfid.agent.command.CommandPoller;
import com.geoplan.rfid.agent.config.AgentConfig;
import com.geoplan.rfid.agent.http.ControlServer;
import com.geoplan.rfid.agent.middleware.MiddlewareClient;
import com.geoplan.rfid.agent.reader.R400TagReader;
import com.geoplan.rfid.agent.reader.SimulatedTagReader;
import com.geoplan.rfid.agent.reader.TagReader;
import com.geoplan.rfid.agent.scan.ScanCoordinator;
import com.geoplan.rfid.agent.util.Log;

/**
 * SSI RMK reader agent.
 *
 * The middleware owns warehouse documents. This process owns one iData R400: it
 * waits for POST /scan/start, runs inventory, pushes unique EPCs to
 * /epc-scan-processing/sessions/{sessionId}/reads, and stops on POST /scan/stop.
 */
public final class AgentMain {

    public static void main(String[] args) {
        if (System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            Utils.APIPath.folderName = "/tmp/";
        }

        AgentConfig config = AgentConfig.load();

        Log.info("SSI RMK reader agent starting");
        config.logSummary();

        TagReader reader = config.simulated
                ? new SimulatedTagReader(config)
                : new R400TagReader(config);

        MiddlewareClient middleware = new MiddlewareClient(config);
        ScanCoordinator coordinator = new ScanCoordinator(config, reader, middleware);
        ControlServer controlServer = new ControlServer(config, coordinator);
        CommandPoller commandPoller = new CommandPoller(config, coordinator);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.info("Shutting down");

            commandPoller.stop();
            controlServer.stop();
            coordinator.stopAll();
            reader.close();
        }, "agent-shutdown"));

        try {
            controlServer.start();
        } catch (Exception e) {
            Log.error("Control server failed to start on port " + config.controlPort, e);
            System.exit(1);
            return;
        }

        coordinator.startBackgroundWork();

        /*
         * Connecting here is a convenience: the desk sees a reader problem at
         * boot instead of at the first Start. A failure is not fatal because
         * /scan/start reconnects anyway.
         */
        if (!reader.connect()) {
            Log.warn("Reader " + reader.describe() + " is not reachable yet. A START command will retry.");
        }

        commandPoller.start();
        Log.info("Ready. Waiting for outbound-polled reader commands.");

        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
