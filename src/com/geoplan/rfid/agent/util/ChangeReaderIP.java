package com.geoplan.rfid.agent.util;

import JavaAPI.Entities.ConnectResponse;
import JavaAPI.Protocol.VRP.MsgIpAddressConfig;
import JavaAPI.Protocol.VRP.Reader;
import JavaAPI.TcpClientPort;

/**
 * Utility tool to query and configure the network IP, Subnet, and Gateway
 * on the iData R400 RFID reader.
 */
public final class ChangeReaderIP {

    public static void main(String[] args) {
        String currentHost = args.length > 0 ? args[0] : "192.168.1.100";
        int currentPort = args.length > 1 ? Integer.parseInt(args[1]) : 9090;
        String newIp = args.length > 2 ? args[2] : null;
        String newSubnet = args.length > 3 ? args[3] : "255.255.255.0";
        String newGateway = args.length > 4 ? args[4] : "172.16.210.250";

        System.out.println("Connecting to reader at " + currentHost + ":" + currentPort + " ...");
        Reader reader = new Reader("Device1", new TcpClientPort(currentHost, currentPort));
        ConnectResponse response = null;
        try {
            response = reader.Connect();
        } catch (Exception e) {
            System.err.println("Exception connecting to reader: " + e.getMessage());
            System.exit(1);
        }

        if (response == null || !response.IsSucessed) {
            String err = "";
            if (response != null && response.ErrorInfo != null) {
                err = response.ErrorInfo.getErrMsg();
            }
            if (err == null || err.trim().isEmpty()) {
                err = "Connection timed out (no TCP handshake response on port " + currentPort + ")";
            }
            System.err.println("FAILED to connect to reader at " + currentHost + ":" + currentPort + ": " + err);
            System.exit(1);
        }

        System.out.println("Reader connected successfully!");
        System.out.println("Model:    " + reader.getModelNumber());
        System.out.println("Firmware: " + reader.getSoftwareVersion());

        // Query current configuration
        MsgIpAddressConfig query = new MsgIpAddressConfig();
        if (reader.Send(query)) {
            System.out.println("\n--- Current Reader Network Settings ---");
            System.out.println("IP Address:  " + query.getReceivedMessage().getIP());
            System.out.println("Subnet Mask: " + query.getReceivedMessage().getSubnet());
            System.out.println("Gateway:     " + query.getReceivedMessage().getGateway());
        } else {
            System.err.println("Failed to query reader IP settings: " + query.getErrorInfo().getErrMsg());
        }

        // Apply new configuration if specified
        if (newIp != null && !newIp.trim().isEmpty()) {
            System.out.println("\n--- Updating Reader Network Settings ---");
            System.out.println("New IP:      " + newIp);
            System.out.println("New Subnet:  " + newSubnet);
            System.out.println("New Gateway: " + newGateway);

            MsgIpAddressConfig updateMsg = new MsgIpAddressConfig(newIp, newSubnet, newGateway);
            if (reader.Send(updateMsg)) {
                System.out.println("\nSUCCESS: Reader IP successfully updated to " + newIp + "!");
            } else {
                System.err.println("FAILED to update reader IP: " + updateMsg.getErrorInfo().getErrMsg());
            }
        }

        try {
            reader.Disconnect();
        } catch (Exception ignored) {}
    }
}
