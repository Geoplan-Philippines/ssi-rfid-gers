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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SSI RMK reader agent.
 *
 * The middleware owns warehouse documents. This process drives one or more
 * iData R400 controllers: each controller polls commands for its READER_ID,
 * runs continuous inventory, pushes unique EPCs to
 * /epc-scan-processing/sessions/{sessionId}/reads, and stops on STOP.
 */
public final class AgentMain {

    public static void main(String[] args) {
        if (System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            Utils.APIPath.folderName = "/tmp/";
        }

        AgentConfig config = AgentConfig.load();

        Log.info("SSI RMK reader agent starting");
        config.logSummary();

        MiddlewareClient middleware = new MiddlewareClient(config);

        List<TagReader> readers = new ArrayList<>();
        List<ScanCoordinator> coordinators = new ArrayList<>();
        List<CommandPoller> pollers = new ArrayList<>();
        Map<String, ScanCoordinator> coordinatorsByEndpoint = new LinkedHashMap<>();

        for (AgentConfig.ReaderConfig rc : config.readers) {
            String endpointKey = rc.endpointKey();
            ScanCoordinator coordinator = coordinatorsByEndpoint.get(endpointKey);

            if (coordinator == null) {
                TagReader reader = rc.simulated()
                        ? new SimulatedTagReader(rc)
                        : new R400TagReader(rc);
                coordinator = new ScanCoordinator(config, rc, reader, middleware);
                readers.add(reader);
                coordinators.add(coordinator);
                coordinatorsByEndpoint.put(endpointKey, coordinator);
            } else {
                coordinator.registerReaderId(rc.readerId());
                Log.info("Reader config " + rc.readerName() + " shares hardware endpoint "
                        + endpointKey + " with " + coordinator.readerConfig().readerName());
            }

            pollers.add(new CommandPoller(config, rc, coordinator));
        }

        ControlServer controlServer = new ControlServer(config, coordinators);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.info("Shutting down");

            for (CommandPoller poller : pollers) {
                poller.stop();
            }
            controlServer.stop();
            for (ScanCoordinator coordinator : coordinators) {
                coordinator.stopAll();
            }
            for (TagReader reader : readers) {
                reader.close();
            }
        }, "agent-shutdown"));

        try {
            controlServer.start();
        } catch (Exception e) {
            Log.error("Control server failed to start on port " + config.controlPort, e);
            System.exit(1);
            return;
        }

        for (ScanCoordinator coordinator : coordinators) {
            coordinator.startBackgroundWork();
        }

        /*
         * Connecting here is a convenience: the desk sees a reader problem at
         * boot instead of at the first Start. A failure is not fatal because
         * /scan/start reconnects anyway.
         */
        for (TagReader reader : readers) {
            if (!reader.connect()) {
                Log.warn("Reader " + reader.describe() + " is not reachable yet. A START command will retry.");
            }
        }

        for (CommandPoller poller : pollers) {
            poller.start();
        }
        Log.info("Ready. Waiting for outbound-polled reader commands across "
                + coordinators.size() + " controller(s).");

        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
