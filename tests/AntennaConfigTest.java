package tests;

import JavaAPI.Core.ErrInfo;
import JavaAPI.Entities.AntennaStatus;
import JavaAPI.Entities.ConnectResponse;
import JavaAPI.Protocol.VRP.MsgAntennaConfig;
import JavaAPI.Protocol.VRP.Reader;
import JavaAPI.TcpClientPort;

public class AntennaConfigTest {

    private static AntennaStatus status(byte port, boolean enable) {
        AntennaStatus s = new AntennaStatus();
        s.AntennaNO = port;
        s.IsEnable = enable;
        return s;
    }

    public static void main(String[] args) {
        // Fix vendor SDK Linux bug where it strips leading '/' from jar path
        Utils.APIPath.folderName = "/tmp/";

        String host = "192.168.1.100";
        int port = 9090;

        System.out.println("Connecting to iData R400 at " + host + ":" + port + " via Java SDK...");
        Reader reader = new Reader("Device1", new TcpClientPort(host, port));
        ConnectResponse resp = reader.Connect();

        if (resp == null || !resp.IsSucessed) {
            ErrInfo err = resp == null ? null : resp.ErrorInfo;
            System.err.println("Connection failed: " + (err != null ? err.getErrMsg() : "unknown"));
            System.exit(1);
        }

        System.out.println("Connected to reader!");
        System.out.println("Model: " + reader.getModelNumber());
        System.out.println("Firmware: " + reader.getSoftwareVersion());

        // 1. Query current antenna status
        System.out.println("\n[1] Querying current antenna configuration...");
        MsgAntennaConfig queryMsg = new MsgAntennaConfig();
        if (reader.Send(queryMsg)) {
            MsgAntennaConfig.ReceivedInfo info = queryMsg.getReceivedMessage();
            if (info != null && info.getAntennaStatusAry() != null) {
                for (AntennaStatus as : info.getAntennaStatusAry()) {
                    System.out.println("    - ANT " + as.AntennaNO + ": " + (as.IsEnable ? "ENABLED" : "Disabled"));
                }
            }
        } else {
            System.err.println("Failed to query current antenna configuration.");
        }

        // 2. Set antennas (e.g. enable ANT 1 and ANT 2)
        System.out.println("\n[2] Setting antennas: Enabling ANT 1 and ANT 2...");
        AntennaStatus[] newSettings = new AntennaStatus[] {
            status((byte) 1, true),   // Ant 1 enabled
            status((byte) 2, true),   // Ant 2 enabled
            status((byte) 3, false),  // Ant 3 disabled
            status((byte) 4, false)   // Ant 4 disabled
        };

        MsgAntennaConfig setMsg = new MsgAntennaConfig(newSettings);
        boolean setOk = reader.Send(setMsg);
        System.out.println("MsgAntennaConfig Send result: " + (setOk ? "SUCCESS" : "FAILED"));

        // 3. Query again to verify
        System.out.println("\n[3] Verifying new antenna configuration...");
        MsgAntennaConfig verifyMsg = new MsgAntennaConfig();
        if (reader.Send(verifyMsg)) {
            MsgAntennaConfig.ReceivedInfo info = verifyMsg.getReceivedMessage();
            if (info != null && info.getAntennaStatusAry() != null) {
                for (AntennaStatus as : info.getAntennaStatusAry()) {
                    System.out.println("    - ANT " + as.AntennaNO + ": " + (as.IsEnable ? "ENABLED" : "Disabled"));
                }
            }
        } else {
            System.err.println("Failed to query verification antenna configuration.");
        }

        reader.Disconnect();
        System.out.println("\nDisconnected cleanly.");
    }
}
