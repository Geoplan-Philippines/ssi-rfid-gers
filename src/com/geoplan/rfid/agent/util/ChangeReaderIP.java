package com.geoplan.rfid.agent.util;

import JavaAPI.Entities.ConnectResponse;
import JavaAPI.Protocol.VRP.MsgIpAddressConfig;
import JavaAPI.Protocol.VRP.Reader;
import JavaAPI.TcpClientPort;

/**
 * Utility tool to query and configure the network IP, subnet, and gateway on
 * an iData R400 RFID reader.
 */
public final class ChangeReaderIP {

    private static final int EXIT_ERROR = 1;
    private static final int EXIT_INVALID_ARGUMENTS = 2;

    private ChangeReaderIP() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    private static int run(String[] args) {
        String currentHost = args.length > 0 ? args[0] : "192.168.1.100";
        int currentPort;
        try {
            currentPort = args.length > 1 ? Integer.parseInt(args[1]) : 9090;
        } catch (NumberFormatException error) {
            System.err.println("Invalid reader port: " + args[1]);
            return EXIT_INVALID_ARGUMENTS;
        }
        if (currentPort < 1 || currentPort > 65535) {
            System.err.println("Reader port must be between 1 and 65535: " + currentPort);
            return EXIT_INVALID_ARGUMENTS;
        }

        String newIp = args.length > 2 ? args[2].trim() : null;
        String newSubnet = args.length > 3 ? args[3].trim() : "255.255.255.0";
        String newGateway = args.length > 4 ? args[4].trim() : "172.16.210.250";

        if (newIp != null && !newIp.isEmpty()) {
            String validationError = validateNetworkSettings(newIp, newSubnet, newGateway);
            if (validationError != null) {
                System.err.println("Invalid target network settings: " + validationError);
                return EXIT_INVALID_ARGUMENTS;
            }
        }

        Reader reader = null;
        try {
            System.out.println("Connecting to reader at " + currentHost + ":" + currentPort + " ...");
            reader = new Reader("Device1", new TcpClientPort(currentHost, currentPort));
            ConnectResponse response = reader.Connect();

            if (response == null || !response.IsSucessed) {
                String error = response != null && response.ErrorInfo != null
                        ? response.ErrorInfo.getErrMsg()
                        : null;
                if (error == null || error.trim().isEmpty()) {
                    error = "Connection timed out (no TCP handshake response on port " + currentPort + ")";
                }
                System.err.println("FAILED to connect to reader at " + currentHost + ":" + currentPort
                        + ": " + error);
                return EXIT_ERROR;
            }

            System.out.println("Reader connected successfully!");
            System.out.println("Model:    " + reader.getModelNumber());
            System.out.println("Firmware: " + reader.getSoftwareVersion());

            MsgIpAddressConfig query = new MsgIpAddressConfig();
            boolean querySucceeded = reader.Send(query) && query.getReceivedMessage() != null;
            if (querySucceeded) {
                System.out.println("\n--- Current Reader Network Settings ---");
                System.out.println("IP Address:  " + query.getReceivedMessage().getIP());
                System.out.println("Subnet Mask: " + query.getReceivedMessage().getSubnet());
                System.out.println("Gateway:     " + query.getReceivedMessage().getGateway());
            } else {
                System.err.println("Failed to query reader IP settings: " + messageError(query));
                if (newIp == null || newIp.isEmpty()) {
                    return EXIT_ERROR;
                }
            }

            if (newIp == null || newIp.isEmpty()) {
                return 0;
            }

            System.out.println("\n--- Updating Reader Network Settings ---");
            System.out.println("New IP:      " + newIp);
            System.out.println("New Subnet:  " + newSubnet);
            System.out.println("New Gateway: " + newGateway);

            MsgIpAddressConfig update = new MsgIpAddressConfig(newIp, newSubnet, newGateway);
            if (!reader.Send(update)) {
                System.err.println("FAILED to update reader IP: " + messageError(update));
                return EXIT_ERROR;
            }

            System.out.println("\nSUCCESS: Reader accepted the new network settings.");
            return 0;
        } catch (Exception error) {
            String message = error.getMessage();
            System.err.println("Reader network configuration failed: "
                    + error.getClass().getSimpleName()
                    + (message == null || message.isBlank() ? "" : ": " + message));
            return EXIT_ERROR;
        } finally {
            if (reader != null) {
                try {
                    reader.Disconnect();
                } catch (Exception ignored) {
                    // The reader can reset its network stack immediately after the update.
                }
            }
        }
    }

    private static String messageError(MsgIpAddressConfig message) {
        if (message.getErrorInfo() == null || message.getErrorInfo().getErrMsg() == null
                || message.getErrorInfo().getErrMsg().isBlank()) {
            return "the reader returned no error details";
        }
        return message.getErrorInfo().getErrMsg();
    }

    private static String validateNetworkSettings(String ip, String subnet, String gateway) {
        if (!isIpv4(ip)) {
            return "IP address is not valid IPv4: " + ip;
        }
        if (!isIpv4(subnet)) {
            return "subnet mask is not valid IPv4: " + subnet;
        }
        if (!isIpv4(gateway)) {
            return "gateway is not valid IPv4: " + gateway;
        }

        long mask = ipv4ToLong(subnet);
        long inverseMask = (~mask) & 0xffffffffL;
        if (mask == 0 || (inverseMask & (inverseMask + 1)) != 0) {
            return "subnet mask is not contiguous: " + subnet;
        }

        long address = ipv4ToLong(ip);
        long gatewayAddress = ipv4ToLong(gateway);
        long network = address & mask;
        long broadcast = network | inverseMask;
        if (address == network || address == broadcast) {
            return "IP address is the subnet's network or broadcast address: " + ip;
        }
        if ((gatewayAddress & mask) != network) {
            return "gateway " + gateway + " is not in the same subnet as " + ip;
        }
        if (gatewayAddress == network || gatewayAddress == broadcast) {
            return "gateway is the subnet's network or broadcast address: " + gateway;
        }

        return null;
    }

    private static boolean isIpv4(String value) {
        if (value == null) {
            return false;
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int index = 0; index < part.length(); index++) {
                if (!Character.isDigit(part.charAt(index))) {
                    return false;
                }
            }
            try {
                if (Integer.parseInt(part) > 255) {
                    return false;
                }
            } catch (NumberFormatException error) {
                return false;
            }
        }
        return true;
    }

    private static long ipv4ToLong(String value) {
        long result = 0;
        for (String part : value.split("\\.")) {
            result = (result << 8) | Integer.parseInt(part);
        }
        return result;
    }
}
