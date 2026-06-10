import JavaAPI.Protocol.VRP.Reader;
import JavaAPI.Protocol.VRP.MsgTagInventory;
import JavaAPI.Protocol.VRP.MsgPowerOff;
import JavaAPI.Protocol.VRP.MsgGpoConfig;

import JavaAPI.Entities.ConnectResponse;
import JavaAPI.Entities.RxdTagData;
import JavaAPI.Entities.ReadTagParameter;
import JavaAPI.Entities.GpioLevelParameter;
import JavaAPI.Entities.GpioLevel;

import JavaAPI.Core.ErrInfo;

import JavaAPI.TcpClientPort;
import Utils.Event;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import java.time.Duration;
import java.time.Instant;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {

    private Reader reader;
    private boolean connected = false;
    private volatile boolean inventoryRunning = false;

    /*
     * Known RFID tags are fetched from NestJS and refreshed while the app runs.
     * Alarm will only trigger for EPCs in this set.
     */
    private volatile Set<String> knownEpcs = Collections.emptySet();

    /*
     * Prevent repeated alarm spam per EPC.
     */
    private final Map<String, Long> lastAlarmAt = new HashMap<>();

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ScheduledExecutorService knownEpcRefreshExecutor = Executors.newSingleThreadScheduledExecutor((task) -> {
        Thread thread = new Thread(task, "known-epc-refresh");
        thread.setDaemon(true);
        return thread;
    });

    private static final Pattern KNOWN_EPCS_ARRAY_PATTERN = Pattern.compile(
            "\"knownEpcs\"\\s*:\\s*\\[(.*?)\\]",
            Pattern.DOTALL
    );
    private static final Pattern JSON_STRING_PATTERN = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");

    /*
     * Reader connection.
     */
    private static final String READER_IP = "192.168.1.100";
    private static final int READER_PORT = 9090;

    /*
     * Optional NestJS API.
     * Set ENABLE_NEST_NOTIFY to false if you only want physical alarm.
     */
    private static final boolean ENABLE_NEST_NOTIFY = true;
    private static final String ANTI_THEFT_API_BASE_URL = configValue(
            "ANTI_THEFT_API_BASE_URL",
            "http://localhost:8000/api/v1/anti-theft"
    );
    private static final String ALARMS_API_BASE_URL = configValue(
            "ALARMS_API_BASE_URL",
            "http://localhost:8000/api/v1/alarms"
    );
    private static final String NEST_ALARM_URL = ALARMS_API_BASE_URL;
    private static final String NEST_KNOWN_EPCS_URL = ANTI_THEFT_API_BASE_URL + "/known-epcs";
    private static final long KNOWN_EPCS_REFRESH_MS = configLongValue("KNOWN_EPCS_REFRESH_MS", 5_000);

    /*
     * Alarm output.
     *
     * OUT1 is physically Pin 6 on the R400 GPIO terminal.
     * If OUT1 does not work, test 2 or 3.
     */
    private static final byte ALARM_OUTPUT_PORT = 1;

    /*
     * Alarm duration.
     */
    private static final long ALARM_ON_MS = 500;

    /*
     * Alarm cooldown.
     */
    private static final long ALARM_COOLDOWN_MS = 2_500;

    /*
     * If alarm behavior is inverted, swap these:
     *
     * ON  = GpioLevel.Low
     * OFF = GpioLevel.High
     */
    private static final GpioLevel ALARM_ON_LEVEL = GpioLevel.High;
    private static final GpioLevel ALARM_OFF_LEVEL = GpioLevel.Low;
//    private static final GpioLevel ALARM_ON_LEVEL = GpioLevel.Low;
//    private static final GpioLevel ALARM_OFF_LEVEL = GpioLevel.High;

    public static void main(String[] args) {
        new Main().run();
    }

    public void run() {
        try {
            System.out.println("Starting RFID alarm demo...");

            connectReader();

            /*
             * Very important:
             * Always force alarm OFF on startup.
            */
            forceAlarmOff();

            refreshKnownEpcs();
            startKnownEpcRefresh();

            registerTagEvent();

            startInventory();

            System.out.println("🟢 System armed.");
            System.out.println("📡 Scanning continuously...");
            System.out.println("Known EPCs: " + knownEpcs);

            /*
             * Keep app alive.
             */
            while (true) {
                Thread.sleep(1000);
            }

        } catch (Exception e) {
            e.printStackTrace();

        } finally {
            stopReader();
        }
    }

    private void connectReader() throws Exception {
        reader = new Reader(
                "Device1",
                new TcpClientPort(READER_IP, READER_PORT)
        );

        ConnectResponse response = reader.Connect();

        if (!response.IsSucessed) {
            System.out.println("❌ Connection failed");
            System.out.println(response.ErrorInfo);
            throw new RuntimeException("Reader connection failed");
        }

        connected = true;
        System.out.println("✅ Connected to reader");
    }

    private void registerTagEvent() {
        Event tagEvent = new Event(
                this,
                "Reader_OnInventoryReceived"
        );

        reader.OnInventoryReceived.addEvent(tagEvent);

        System.out.println("✅ RFID tag event registered");
    }

    private synchronized void startInventory() {
        try {
            if (inventoryRunning) {
                return;
            }

            ReadTagParameter readTagParameter = new ReadTagParameter();

            readTagParameter.IsLoop = true;
            readTagParameter.ReadCount = 0;

            /*
             * 0 means no total time limit.
             * It will scan until MsgPowerOff is sent.
             */
            readTagParameter.TotalReadTime = 0;

            readTagParameter.IsReturnEPC = true;
            readTagParameter.IsReturnTID = false;

            MsgTagInventory inventory = new MsgTagInventory(readTagParameter);

            boolean started = reader.Send(inventory);

            if (!started) {
                inventoryRunning = false;

                System.out.println("❌ Failed to start inventory");
                System.out.println(inventory.getErrorInfo());
                return;
            }

            inventoryRunning = true;
            System.out.println("🚀 RFID inventory started");

        } catch (Exception e) {
            inventoryRunning = false;
            e.printStackTrace();
        }
    }

    /*
     * SDK callback method name must match the Utils.Event registration.
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

            String epc = normalizeEpc(bytesToHex(epcBytes));

            System.out.println("📦 EPC scanned: " + epc);

            /*
             * Unknown EPC = no alarm.
             */
            if (!knownEpcs.contains(epc)) {
                System.out.println("Ignored unknown EPC");
                return;
            }

            /*
             * Known EPC but still cooling down = no alarm.
             */
            if (!shouldAlarm(epc)) {
                System.out.println("⏳ Known EPC ignored due to cooldown");
                return;
            }

            System.out.println("🚨 KNOWN EPC DETECTED: " + epc);

            triggerAlarmPulse();

            if (ENABLE_NEST_NOTIFY) {
                sendAlarmToNest(epc);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private synchronized boolean shouldAlarm(String epc) {
        long now = System.currentTimeMillis();

        Long lastTime = lastAlarmAt.get(epc);

        if (lastTime == null || now - lastTime > ALARM_COOLDOWN_MS) {
            lastAlarmAt.put(epc, now);
            return true;
        }

        return false;
    }

    private synchronized void triggerAlarmPulse() {
        try {
            setAlarmOn();

            System.out.println("🚨 Alarm ON");

            Thread.sleep(ALARM_ON_MS);

        } catch (Exception e) {
            System.out.println("❌ Alarm pulse failed");
            e.printStackTrace();

        } finally {
            forceAlarmOff();
        }
    }

    private void setAlarmOn() {
        setGpoLevel(ALARM_OUTPUT_PORT, ALARM_ON_LEVEL);
    }

    private void forceAlarmOff() {
        try {
            setGpoLevel(ALARM_OUTPUT_PORT, ALARM_OFF_LEVEL);
            System.out.println("✅ Alarm OFF");

        } catch (Exception e) {
            System.out.println("⚠️ Failed to force alarm OFF");
            e.printStackTrace();
        }
    }

    private void setGpoLevel(byte portNo, GpioLevel level) {
        if (reader == null || !connected) {
            System.out.println("⚠️ Cannot set GPO. Reader not connected.");
            return;
        }

        GpioLevelParameter parameter = new GpioLevelParameter();
        parameter.PortNO = portNo;
        parameter.Level = level;

        MsgGpoConfig message = new MsgGpoConfig(parameter);

        boolean success = reader.Send(message, 2000);

        if (!success) {
            ErrInfo err = message.getErrorInfo();
            System.out.println("❌ Failed to set OUT" + portNo + " to " + level
                    + " | status=" + message.getStatus()
                    + " | code=" + (err != null ? err.getErrCode() : "?")
                    + " | msg=" + (err != null ? err.getErrMsg() : "?"));
        }
    }

    private synchronized void stopInventory() {
        try {
            if (!inventoryRunning) {
                return;
            }

            MsgPowerOff stop = new MsgPowerOff();
            reader.Send(stop, 2000);

            inventoryRunning = false;

            System.out.println("🛑 RFID inventory stopped");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void stopReader() {
        try {
            forceAlarmOff();
            knownEpcRefreshExecutor.shutdownNow();

            if (reader == null || !connected) {
                return;
            }

            stopInventory();

            try {
                reader.Disconnect();
                System.out.println("Disconnected.");
            } catch (Exception ignored) {
            }

            connected = false;

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startKnownEpcRefresh() {
        knownEpcRefreshExecutor.scheduleWithFixedDelay(
                this::refreshKnownEpcs,
                KNOWN_EPCS_REFRESH_MS,
                KNOWN_EPCS_REFRESH_MS,
                TimeUnit.MILLISECONDS
        );
    }

    private void refreshKnownEpcs() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(NEST_KNOWN_EPCS_URL))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                System.out.println("Failed to fetch known EPCs. HTTP " + response.statusCode());
                return;
            }

            Set<String> fetchedKnownEpcs = parseKnownEpcs(response.body());
            knownEpcs = fetchedKnownEpcs;

            System.out.println("Known EPCs refreshed: " + knownEpcs);

        } catch (Exception e) {
            System.out.println("Failed to refresh known EPCs. Keeping last known set: " + knownEpcs);
            e.printStackTrace();
        }
    }

    private Set<String> parseKnownEpcs(String responseBody) {
        Matcher arrayMatcher = KNOWN_EPCS_ARRAY_PATTERN.matcher(responseBody);

        if (!arrayMatcher.find()) {
            throw new IllegalArgumentException("knownEpcs array missing from NestJS response");
        }

        Set<String> parsedKnownEpcs = new HashSet<>();
        Matcher valueMatcher = JSON_STRING_PATTERN.matcher(arrayMatcher.group(1));

        while (valueMatcher.find()) {
            String epc = normalizeEpc(unescapeJsonString(valueMatcher.group(1)));

            if (!epc.isEmpty()) {
                parsedKnownEpcs.add(epc);
            }
        }

        return Set.copyOf(parsedKnownEpcs);
    }

    private String unescapeJsonString(String value) {
        return value
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private void sendAlarmToNest(String epc) {
        try {
            String json = """
                    {
                      "epc": "%s",
                      "readerName": "Device1",
                      "scannedAt": "%s"
                    }
                    """.formatted(
                    epc,
                    Instant.now().toString()
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(NEST_ALARM_URL))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            System.out.println("✅ NestJS response: " + response.body());

        } catch (Exception e) {
            System.out.println("⚠️ Failed to notify NestJS");
            e.printStackTrace();
        }
    }

    private String normalizeEpc(String epc) {
        return epc
                .replaceAll("\\s+", "")
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

    private static String configValue(String key, String defaultValue) {
        String systemValue = System.getProperty(key);

        if (systemValue != null && !systemValue.trim().isEmpty()) {
            return systemValue.trim();
        }

        String environmentValue = System.getenv(key);

        if (environmentValue != null && !environmentValue.trim().isEmpty()) {
            return environmentValue.trim();
        }

        return defaultValue;
    }

    private static long configLongValue(String key, long defaultValue) {
        String value = configValue(key, Long.toString(defaultValue));

        try {
            long parsedValue = Long.parseLong(value);

            if (parsedValue <= 0) {
                throw new NumberFormatException("Value must be positive");
            }

            return parsedValue;
        } catch (NumberFormatException e) {
            System.out.println("Invalid " + key + " value '" + value + "'. Using " + defaultValue + ".");
            return defaultValue;
        }
    }
}
