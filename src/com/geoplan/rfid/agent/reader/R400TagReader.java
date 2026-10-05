package com.geoplan.rfid.agent.reader;

import JavaAPI.Core.ErrInfo;
import JavaAPI.Entities.AntennaStatus;
import JavaAPI.Entities.ConnectResponse;
import JavaAPI.Entities.ReadTagParameter;
import JavaAPI.Entities.RxdTagData;
import JavaAPI.Protocol.VRP.MsgAntennaConfig;
import JavaAPI.Protocol.VRP.MsgPowerOff;
import JavaAPI.Protocol.VRP.MsgTagInventory;
import JavaAPI.Protocol.VRP.Reader;
import JavaAPI.TcpClientPort;
import Utils.Event;

import com.geoplan.rfid.agent.config.AgentConfig;
import com.geoplan.rfid.agent.util.Epc;
import com.geoplan.rfid.agent.util.Log;

import java.util.concurrent.ConcurrentHashMap;

/**
 * iData R400 adapter over the vendor VRP SDK. The connection and inventory
 * pattern is the one that was proven on the desk: TCP client port, Connect(),
 * a looping MsgTagInventory, MsgPowerOff to stop.
 *
 * This class must stay public, and so must Reader_OnInventoryReceived. The SDK
 * resolves the callback by name through reflection on the public methods of the
 * object handed to Utils.Event.
 */
public final class R400TagReader implements TagReader {

    private static final int SEND_TIMEOUT_MS = 2000;

    private final AgentConfig config;
    private final Object lock = new Object();
    private final ConcurrentHashMap<String, Long> lastLoggedTime = new ConcurrentHashMap<>();

    private volatile EpcListener listener;
    private volatile boolean inventoryRunning;

    private Reader reader;

    public R400TagReader(AgentConfig config) {
        this.config = config;
    }

    @Override
    public void setListener(EpcListener listener) {
        this.listener = listener;
    }

    @Override
    public boolean connect() {
        synchronized (lock) {
            if (isConnectedInternal()) {
                return true;
            }

            disconnectQuietly();

            try {
                Log.info("Connecting to reader " + config.readerHost + ":" + config.readerPort + " ...");

                Reader candidate = new Reader(
                        config.readerName,
                        new TcpClientPort(config.readerHost, config.readerPort)
                );

                ConnectResponse response = candidate.Connect();

                if (response == null || !response.IsSucessed) {
                    ErrInfo error = response == null ? null : response.ErrorInfo;

                    Log.error("Reader connection failed"
                            + (error != null ? " | code=" + error.getErrCode() + " | msg=" + error.getErrMsg() : ""));
                    return false;
                }

                candidate.OnInventoryReceived.addEvent(new Event(this, "Reader_OnInventoryReceived"));

                reader = candidate;
                inventoryRunning = false;

                Log.info("Reader connected"
                        + " | model=" + safe(candidate.getModelNumber())
                        + " | firmware=" + safe(candidate.getSoftwareVersion()));

                applyAntennaConfig(candidate);

                return true;
            } catch (Exception e) {
                Log.error("Reader connection threw", e);
                reader = null;
                return false;
            }
        }
    }

    @Override
    public boolean startInventory() {
        synchronized (lock) {
            if (!connect()) {
                return false;
            }

            if (inventoryRunning) {
                return true;
            }

            try {
                ReadTagParameter parameter = new ReadTagParameter();

                parameter.IsLoop = true;
                parameter.ReadCount = 0;

                /* 0 means no time limit. Inventory runs until MsgPowerOff. */
                parameter.TotalReadTime = 0;
                parameter.TagFilteringTime = config.readerTagFilteringTimeMs;

                parameter.IsReturnEPC = true;
                parameter.IsReturnTID = false;

                MsgTagInventory inventory = new MsgTagInventory(parameter);

                if (!reader.Send(inventory)) {
                    ErrInfo error = inventory.getErrorInfo();

                    Log.error("Failed to start inventory"
                            + " | status=" + inventory.getStatus()
                            + (error != null ? " | code=" + error.getErrCode() + " | msg=" + error.getErrMsg() : ""));
                    return false;
                }

                inventoryRunning = true;
                Log.info("Inventory started");

                return true;
            } catch (Exception e) {
                Log.error("Failed to start inventory", e);
                inventoryRunning = false;
                return false;
            }
        }
    }

    @Override
    public void stopInventory() {
        synchronized (lock) {
            if (!inventoryRunning) {
                return;
            }

            inventoryRunning = false;

            try {
                if (reader != null) {
                    reader.Send(new MsgPowerOff(), SEND_TIMEOUT_MS);
                }

                Log.info("Inventory stopped");
            } catch (Exception e) {
                Log.warn("Failed to stop inventory cleanly", e);
            }
        }
    }

    @Override
    public boolean isConnected() {
        synchronized (lock) {
            return isConnectedInternal();
        }
    }

    @Override
    public boolean isInventoryRunning() {
        return inventoryRunning;
    }

    @Override
    public void close() {
        synchronized (lock) {
            stopInventory();
            disconnectQuietly();
        }
    }

    @Override
    public String describe() {
        return config.readerHost + ":" + config.readerPort;
    }

    /**
     * SDK callback. The method name must match the Utils.Event registration.
     * Runs on an SDK receive thread, so it only normalises and forwards. Any
     * blocking work belongs downstream.
     */
    public void Reader_OnInventoryReceived(Reader sender, RxdTagData tagData) {
        try {
            if (tagData == null) {
                return;
            }

            byte[] epcBytes = tagData.getEPC();

            if (epcBytes == null || epcBytes.length == 0) {
                return;
            }

            String epc = Epc.normalize(Epc.fromBytes(epcBytes));

            if (!Epc.isValid(epc)) {
                return;
            }

            byte antenna = tagData.getAntenna();
            String key = antenna + ":" + epc;
            long now = System.currentTimeMillis();
            Long last = lastLoggedTime.put(key, now);
            if (last == null || now - last > 1500) {
                Log.info(String.format("📡 [Antenna %d] Read tag: %s (RSSI: %.1f dBm)", antenna, epc, tagData.GetRSSI()));
            }

            EpcListener target = listener;

            if (target != null) {
                target.onEpc(epc);
            }
        } catch (Exception e) {
            Log.warn("Tag callback failed", e);
        }
    }

    private boolean isConnectedInternal() {
        return reader != null && Boolean.TRUE.equals(reader.getIsConnected());
    }

    private void disconnectQuietly() {
        if (reader == null) {
            return;
        }

        try {
            reader.Disconnect();
        } catch (Exception ignored) {
            /* The SDK throws when the socket is already gone. */
        }

        reader = null;
        inventoryRunning = false;
    }

    private void applyAntennaConfig(Reader candidate) {
        try {
            boolean[] enabled = new boolean[5];
            for (String part : config.readerAntennas.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    int p = Integer.parseInt(trimmed);
                    if (p >= 1 && p <= 4) {
                        enabled[p] = true;
                    }
                }
            }

            AntennaStatus[] antennas = new AntennaStatus[4];
            for (byte i = 1; i <= 4; i++) {
                AntennaStatus status = new AntennaStatus();
                status.AntennaNO = i;
                status.IsEnable = enabled[i];
                antennas[i - 1] = status;
            }

            MsgAntennaConfig msg = new MsgAntennaConfig(antennas);
            if (candidate.Send(msg)) {
                Log.info("Antennas configured | active ports=" + config.readerAntennas);
            } else {
                Log.warn("Failed to apply antenna configuration to reader");
            }
        } catch (Exception e) {
            Log.warn("Failed setting antenna configuration", e);
        }
    }

    private static String safe(String value) {
        return value == null || value.isEmpty() ? "?" : value;
    }
}
