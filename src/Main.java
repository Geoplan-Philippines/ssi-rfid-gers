import JavaAPI.Protocol.VRP.Reader;
import JavaAPI.Protocol.VRP.MsgTagInventory;
import JavaAPI.Protocol.VRP.MsgPowerOff;
import JavaAPI.Entities.ConnectResponse;
import JavaAPI.Entities.RxdTagData;
import JavaAPI.Entities.ReadTagParameter;
import JavaAPI.TcpClientPort;
import Utils.Event;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import JavaAPI.Protocol.VRP.MsgGpoConfig;
import JavaAPI.Entities.GpioLevelParameter;
import JavaAPI.Entities.GpioLevel;

public class Main {
    private Reader reader;
    private boolean connected = false;

    private final Set<String> knownEpcs = Set.of(
            "A0000010"
    );

    private final Map<String, Long> lastAlarmAt = new HashMap<>();

    private static final String READER_IP = "192.168.1.100";
    private static final int READER_PORT = 9090;

    private static final String NEST_ALARM_URL = "http://localhost:3000/rfid/alarm";

    private static final long SCAN_DURATION_MS = 30_000;
    private static final long ALARM_COOLDOWN_MS = 2_500;

    private static final byte ALARM_OUTPUT_PORT = 1; // OUT1, based on your R400 terminal label
    private static final long ALARM_ON_MS = 300;

    public static void main(String[] args) {
        new Main().run();
    }

    public void run() {
        try {
            System.out.println("Starting RFID SDK test...");

            reader = new Reader(
                    "Device1",
                    new TcpClientPort(READER_IP, READER_PORT)
            );

            ConnectResponse response = reader.Connect();

            if (!response.IsSucessed) {
                System.out.println("❌ Connection failed");
                System.out.println(response.ErrorInfo);
                return;
            }

            connected = true;
            System.out.println("✅ Connected!");

            Event tagEvent = new Event(this, "Reader_OnInventoryReceived");
            reader.OnInventoryReceived.addEvent(tagEvent);

            // This is based on the API manual's tag reading example.
            ReadTagParameter readTagParameter = new ReadTagParameter();
            readTagParameter.IsLoop = true;
            readTagParameter.ReadCount = 0;
            readTagParameter.TotalReadTime = 0;
            readTagParameter.IsReturnEPC = true;
            readTagParameter.IsReturnTID = false;

            MsgTagInventory inventory = new MsgTagInventory(readTagParameter);

            boolean started = reader.Send(inventory);

            if (!started) {
                System.out.println("❌ Failed to start scanning");
                System.out.println(inventory.getErrorInfo());
                return;
            }

            System.out.println("📡 Scanning for " + (SCAN_DURATION_MS / 1000) + " seconds...");
            Thread.sleep(SCAN_DURATION_MS);

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            stopReader();
        }
    }

    public void Reader_OnInventoryReceived(Reader sender, RxdTagData tagData) {
        System.out.println("🔥 EVENT FIRED!");

        if (tagData == null) {
            System.out.println("tagData is null");
            return;
        }

        byte[] epcBytes = tagData.getEPC();

        if (epcBytes == null || epcBytes.length == 0) {
            System.out.println("EPC is empty");
            return;
        }

        String epc = normalizeEpc(bytesToHex(epcBytes));

        System.out.println("📦 Scanned EPC: " + epc);

        if (!knownEpcs.contains(epc)) {
//            System.out.println("Ignored unknown EPC: " + epc);
            return;
        }

        if (!shouldAlarm(epc)) {
            System.out.println("⏳ Known EPC ignored due to cooldown: " + epc);
            return;
        }

        System.out.println("🚨 ALARM! Known EPC detected: " + epc);
        triggerAlarmOutput();
        sendAlarmToNest(epc);
    }

    private void stopReader() {
        try {
            if (reader == null || !connected) {
                return;
            }

            try {
                MsgPowerOff stop = new MsgPowerOff();
                reader.Send(stop);
                System.out.println("🛑 Stop scan command sent.");
            } catch (Exception stopError) {
                System.out.println("⚠️ Could not send stop command. Socket may already be closed.");
            }

            try {
                reader.Disconnect();
                System.out.println("Disconnected.");
            } catch (Exception disconnectError) {
                System.out.println("⚠️ Reader already disconnected.");
            }

            connected = false;

        } catch (Exception e) {
            System.out.println("❌ Error while stopping reader");
            e.printStackTrace();
        }
    }

    private boolean shouldAlarm(String epc) {
        long now = System.currentTimeMillis();

        Long lastTime = lastAlarmAt.get(epc);

        if (lastTime == null || now - lastTime > ALARM_COOLDOWN_MS) {
            lastAlarmAt.put(epc, now);
            return true;
        }

        return false;
    }

    private synchronized void triggerAlarmOutput() {
        try {
            setGpoLevel(ALARM_OUTPUT_PORT, GpioLevel.High);
            System.out.println("🚨 GPIO OUT" + ALARM_OUTPUT_PORT + " ON");

            Thread.sleep(ALARM_ON_MS);

        } catch (Exception e) {
            System.out.println("❌ Alarm trigger failed");
            e.printStackTrace();

        } finally {
            try {
                setGpoLevel(ALARM_OUTPUT_PORT, GpioLevel.Low);
                System.out.println("✅ GPIO OUT" + ALARM_OUTPUT_PORT + " OFF");
            } catch (Exception offError) {
                System.out.println("⚠️ CRITICAL: Failed to turn alarm OFF");
                offError.printStackTrace();
            }
        }
    }

    private void setGpoLevel(byte portNo, GpioLevel level) {
        GpioLevelParameter parameter = new GpioLevelParameter();
        parameter.PortNO = portNo;
        parameter.Level = level;

        MsgGpoConfig message = new MsgGpoConfig(parameter);

        boolean success = reader.Send(message);

        if (!success) {
            System.out.println("❌ Failed to set GPO output");
            System.out.println(message.getErrorInfo());
        }
    }

    public class AlarmOff {
        private static final String READER_IP = "192.168.1.100";
        private static final int READER_PORT = 9090;

        public static void main(String[] args) {
            Reader reader = null;

            try {
                reader = new Reader(
                        "Device1",
                        new TcpClientPort(READER_IP, READER_PORT)
                );

                ConnectResponse response = reader.Connect();

                if (!response.IsSucessed) {
                    System.out.println("Connection failed:");
                    System.out.println(response.ErrorInfo);
                    return;
                }

                System.out.println("Connected. Turning all outputs OFF...");

                turnOff(reader, (byte) 1);
                turnOff(reader, (byte) 2);
                turnOff(reader, (byte) 3);
                turnOff(reader, (byte) 4);

                System.out.println("Done. Alarm outputs should be OFF.");

            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                try {
                    if (reader != null) {
                        reader.Disconnect();
                    }
                } catch (Exception ignored) {}
            }
        }

        private static void turnOff(Reader reader, byte portNo) {
            try {
                GpioLevelParameter parameter = new GpioLevelParameter();
                parameter.PortNO = portNo;
                parameter.Level = GpioLevel.Low;

                MsgGpoConfig message = new MsgGpoConfig(parameter);
                boolean success = reader.Send(message, 2000);

                System.out.println("OUT" + portNo + " LOW = " + success);

                if (!success) {
                    System.out.println(message.getErrorInfo());
                }
            } catch (Exception e) {
                System.out.println("Failed to turn off OUT" + portNo);
                e.printStackTrace();
            }
        }
    }

    private void sendAlarmToNest(String epc) {
        try {
            String json = """
                    {
                      "epc": "%s",
                      "readerName": "Device1",
                      "scannedAt": "%s"
                    }
                    """.formatted(epc, Instant.now().toString());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(NEST_ALARM_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());

            System.out.println("✅ NestJS response: " + response.body());

        } catch (Exception e) {
            System.out.println("❌ Failed to send alarm to NestJS");
            e.printStackTrace();
        }
    }

    private String normalizeEpc(String epc) {
        return epc
                .replace(" ", "")
                .trim()
                .toUpperCase();
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();

        for (byte b : bytes) {
            result.append(String.format("%02X", b));
        }

        return result.toString();
    }
}