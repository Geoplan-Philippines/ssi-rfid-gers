import JavaAPI.Protocol.VRP.Reader;
import JavaAPI.Protocol.VRP.MsgTagInventory;
import JavaAPI.Protocol.VRP.MsgPowerOff;
import JavaAPI.Protocol.VRP.MsgGpoConfig;

import JavaAPI.Entities.ConnectResponse;
import JavaAPI.Entities.RxdTagData;
import JavaAPI.Entities.ReadTagParameter;
import JavaAPI.Entities.GpioLevelParameter;
import JavaAPI.Entities.GpioLevel;

import JavaAPI.TcpClientPort;
import Utils.Event;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import java.time.Instant;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class Main {

    private Reader reader;

    private boolean connected = false;

    /*
     * RFID inventory state
     */
    private volatile boolean inventoryRunning = false;

    /*
     * Used for noisy trigger protection
     */
    private long lastMotionAt = 0;

    /*
        * Last sensor state change time
     */
    private volatile long lastSensorTriggerTime = 0;

    /*
        * Latest sensor level when available
        */
    private volatile Boolean gpiActive = null;

        private volatile boolean loggedUnknownGpi = false;

    /*
     * Prevent excessive logs / rapid retriggers
     */
    private static final long MOTION_COOLDOWN_MS = 700;

    /*
     * RFID stays active this long AFTER
     * the LAST sensor trigger
     */
    private static final long INVENTORY_DURATION_MS = 5000;

    /*
     * Known RFID tags
     */
    private final Set<String> knownEpcs = Set.of(
            "A0000010",
            "E2806995000040039398BA49"
    );

    /*
     * EPC cooldown tracking
     */
    private final Map<String, Long> lastAlarmAt =
            new HashMap<>();

    /*
     * Reader connection
     */
    private static final String READER_IP =
            "192.168.1.100";

    private static final int READER_PORT = 9090;

    /*
     * NestJS API
     */
    private static final String NEST_ALARM_URL =
            "http://localhost:3000/rfid/alarm";

    /*
     * Prevent repeated alarms
     */
    private static final long ALARM_COOLDOWN_MS =
            2500;

    /*
     * GPIO output port
     */
    private static final byte ALARM_OUTPUT_PORT = 1;

    /*
     * Alarm pulse duration
     */
    private static final long ALARM_ON_MS = 300;

    public static void main(String[] args) {

        new Main().run();
    }

    public void run() {

        try {

            System.out.println(
                    "Starting RFID SDK test..."
            );

            reader = new Reader(
                    "Device1",
                    new TcpClientPort(
                            READER_IP,
                            READER_PORT
                    )
            );

            ConnectResponse response =
                    reader.Connect();

            if (!response.IsSucessed) {

                System.out.println(
                        "❌ Connection failed"
                );

                System.out.println(
                        response.ErrorInfo
                );

                return;
            }

            connected = true;

            System.out.println(
                    "✅ Connected!"
            );

            /*
             * RFID TAG EVENT
             */
            Event tagEvent =
                    new Event(
                            this,
                            "Reader_OnInventoryReceived"
                    );

            reader.OnInventoryReceived
                    .addEvent(tagEvent);

            /*
             * GPI SENSOR EVENT
             */
            Event gpiEvent =
                    new Event(
                            this,
                            "Reader_OnGpiTriggerReceived"
                    );

            reader.OnGpiTriggerReceived
                    .addEvent(gpiEvent);

            System.out.println(
                    "🟢 System armed."
            );

            System.out.println(
                    "Waiting for sensor trigger..."
            );

            /*
             * Keep app alive
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

    /*
     * SENSOR CALLBACK
     */
    public synchronized void Reader_OnGpiTriggerReceived(
            Object sender,
            Object gpiInfo
    ) {

        try {

            long now =
                    System.currentTimeMillis();

            Boolean active =
                    extractGpiActive(gpiInfo);

            if (active == null) {

                if (!loggedUnknownGpi) {

                    loggedUnknownGpi = true;

                    System.out.println(
                            "⚠️ Unknown GPI level, using edge toggle. Payload: "
                                    + gpiInfo.getClass().getName()
                                    + " => "
                                    + gpiInfo
                    );
                }

                active =
                        (gpiActive == null)
                                ? true
                                : !gpiActive;
            }

            /*
             * Always refresh state change time
             */
            lastSensorTriggerTime = now;

            gpiActive = active;

            if (active != null
                    && !active) {

                return;
            }

            /*
             * Prevent noisy logs
             */
            if (now - lastMotionAt
                    < MOTION_COOLDOWN_MS) {

                return;
            }

            lastMotionAt = now;

            System.out.println(
                    "📥 SENSOR ACTIVE"
            );

            /*
             * If already scanning,
             * just extend runtime
             */
            if (inventoryRunning) {

                System.out.println(
                        "🔄 Inventory extended"
                );

                return;
            }

            /*
             * Start RFID inventory
             */
            startInventory();

        } catch (Exception e) {

            e.printStackTrace();
        }
    }

    /*
     * START RFID INVENTORY
     */
    private synchronized void startInventory() {

        try {

            inventoryRunning = true;

            System.out.println(
                    "🚀 Starting RFID inventory..."
            );

            ReadTagParameter readTagParameter =
                    new ReadTagParameter();

            readTagParameter.IsLoop = true;

            readTagParameter.ReadCount = 0;

            /*
             * IMPORTANT:
             * Infinite inventory until manually stopped
             */
            readTagParameter.TotalReadTime = 0;

            readTagParameter.IsReturnEPC = true;

            readTagParameter.IsReturnTID = false;

            MsgTagInventory inventory =
                    new MsgTagInventory(
                            readTagParameter
                    );

            boolean started =
                    reader.Send(inventory);

            if (!started) {

                inventoryRunning = false;

                System.out.println(
                        "❌ Failed to start inventory"
                );

                System.out.println(
                        inventory.getErrorInfo()
                );

                return;
            }

            System.out.println(
                    "📡 RFID ACTIVE"
            );

            /*
             * WATCHDOG THREAD
             *
             * Keeps RFID alive while
             * sensor keeps triggering.
             *
             * Stops RFID after
             * no motion for 5 seconds.
             */
            new Thread(() -> {

                try {

                    while (inventoryRunning) {

                                                Boolean active = gpiActive;

                                                if (active != null
                                                                && active) {

                                                        Thread.sleep(200);

                                                        continue;
                                                }

                        long idleTime =
                                System.currentTimeMillis()
                                        - lastSensorTriggerTime;

                        /*
                         * No motion timeout
                         */
                        if (idleTime
                                >= INVENTORY_DURATION_MS) {

                            System.out.println(
                                    "⌛ No motion for "
                                            + INVENTORY_DURATION_MS
                                            + "ms"
                            );

                            stopInventory();

                            break;
                        }

                        Thread.sleep(200);
                    }

                } catch (Exception e) {

                    e.printStackTrace();
                }

            }).start();

        } catch (Exception e) {

            inventoryRunning = false;

            e.printStackTrace();
        }
    }

        /*
         * Best-effort level extraction for SDK event payloads.
         */
        private Boolean extractGpiActive(Object gpiInfo) {

                if (gpiInfo == null) {

                        return null;
                }

                Boolean isHigh =
                                callBooleanMethod(gpiInfo, "isHigh");

                if (isHigh != null) {

                        return isHigh;
                }

                Boolean isLow =
                                callBooleanMethod(gpiInfo, "isLow");

                if (isLow != null) {

                        return !isLow;
                }

                Boolean isTriggered =
                                callBooleanMethod(gpiInfo, "isTriggered");

                if (isTriggered != null) {

                        return isTriggered;
                }

                if (gpiInfo instanceof GpioLevel) {

                        return gpiInfo == GpioLevel.High;
                }

                Object level =
                                readGpiLevelMember(gpiInfo);

                if (level instanceof GpioLevel) {

                        return level == GpioLevel.High;
                }

                if (level instanceof Enum<?>) {

                        return parseActiveFromString(
                                        ((Enum<?>) level).name()
                        );
                }

                if (level instanceof Boolean) {

                        return (Boolean) level;
                }

                if (level instanceof Number) {

                        return ((Number) level).intValue() != 0;
                }

                if (level != null) {

                        return parseActiveFromString(
                                        level.toString()
                        );
                }

                return parseActiveFromString(
                                gpiInfo.toString()
                );
        }

        private Boolean parseActiveFromString(String value) {

                if (value == null) {

                        return null;
                }

                String normalized =
                                value.trim()
                                                .toUpperCase(Locale.ROOT);

                if (normalized.contains("HIGH")
                                || normalized.contains("ON")
                                || normalized.contains("TRUE")
                                || normalized.contains("ACTIVE")
                                || normalized.contains("TRIGGER")
                                || normalized.equals("1")) {

                        return true;
                }

                if (normalized.contains("LOW")
                                || normalized.contains("OFF")
                                || normalized.contains("FALSE")
                                || normalized.contains("INACTIVE")
                                || normalized.contains("IDLE")
                                || normalized.equals("0")) {

                        return false;
                }

                return null;
        }

        private Boolean callBooleanMethod(Object target, String name) {

                try {

                        Method method =
                                        target.getClass()
                                                        .getMethod(name);

                        Object value =
                                        method.invoke(target);

                        if (value instanceof Boolean) {

                                return (Boolean) value;
                        }

                } catch (Exception ignored) {
                }

                return null;
        }

        private Object readGpiLevelMember(Object gpiInfo) {

                String[] fieldNames = {
                                "Level",
                                "level",
                                "GpioLevel",
                                "gpiLevel",
                                "State",
                                "state",
                                "Status",
                                "status",
                                "Value",
                                "value",
                                "InputLevel",
                                "inputLevel"
                };

                for (String fieldName : fieldNames) {

                        try {

                                Field field =
                                                gpiInfo.getClass()
                                                                .getField(fieldName);

                                return field.get(gpiInfo);

                        } catch (Exception ignored) {
                        }

                        try {

                                Field field =
                                                gpiInfo.getClass()
                                                                .getDeclaredField(fieldName);

                                field.setAccessible(true);

                                return field.get(gpiInfo);

                        } catch (Exception ignored) {
                        }
                }

                String[] getters = {
                                "getLevel",
                                "getGpiLevel",
                                "getInputLevel",
                                "getState",
                                "getStatus",
                                "getValue"
                };

                for (String getter : getters) {

                        try {

                                Method method =
                                                gpiInfo.getClass()
                                                                .getMethod(getter);

                                return method.invoke(gpiInfo);

                        } catch (Exception ignored) {
                        }
                }

                return null;
        }

    /*
     * STOP RFID INVENTORY
     */
    private synchronized void stopInventory() {

        try {

            MsgPowerOff stop =
                    new MsgPowerOff();

            reader.Send(stop);

            inventoryRunning = false;

            System.out.println(
                    "🛑 RFID inventory stopped"
            );

        } catch (Exception e) {

            e.printStackTrace();
        }
    }

    /*
     * RFID TAG EVENT
     */
    public void Reader_OnInventoryReceived(
            Reader sender,
            RxdTagData tagData
    ) {

        try {

            if (tagData == null) {

                System.out.println(
                        "tagData is null"
                );

                return;
            }

            byte[] epcBytes =
                    tagData.getEPC();

            if (epcBytes == null
                    || epcBytes.length == 0) {

                System.out.println(
                        "EPC empty"
                );

                return;
            }

            String epc =
                    normalizeEpc(
                            bytesToHex(epcBytes)
                    );

            System.out.println(
                    "📦 EPC: " + epc
            );

            /*
             * Ignore unknown EPCs
             */
            if (!knownEpcs.contains(epc)) {

                System.out.println(
                        "Ignored unknown EPC"
                );

                return;
            }

            /*
             * Prevent spam alarms
             */
            if (!shouldAlarm(epc)) {

                System.out.println(
                        "⏳ EPC cooldown active"
                );

                return;
            }

            System.out.println(
                    "🚨 KNOWN EPC DETECTED"
            );

            triggerAlarmOutput();

            sendAlarmToNest(epc);

        } catch (Exception e) {

            e.printStackTrace();
        }
    }

    /*
     * SHUTDOWN READER
     */
    private void stopReader() {

        try {

            if (reader == null
                    || !connected) {

                return;
            }

            try {

                MsgPowerOff stop =
                        new MsgPowerOff();

                reader.Send(stop);

            } catch (Exception ignored) {
            }

            try {

                reader.Disconnect();

            } catch (Exception ignored) {
            }

            connected = false;

            System.out.println(
                    "Disconnected."
            );

        } catch (Exception e) {

            e.printStackTrace();
        }
    }

    /*
     * EPC COOLDOWN
     */
    private boolean shouldAlarm(String epc) {

        long now =
                System.currentTimeMillis();

        Long lastTime =
                lastAlarmAt.get(epc);

        if (lastTime == null
                || now - lastTime
                > ALARM_COOLDOWN_MS) {

            lastAlarmAt.put(epc, now);

            return true;
        }

        return false;
    }

    /*
     * TRIGGER GPIO OUTPUT
     */
    private synchronized void triggerAlarmOutput() {

        try {

            setGpoLevel(
                    ALARM_OUTPUT_PORT,
                    GpioLevel.High
            );

            System.out.println(
                    "🚨 GPIO ON"
            );

            Thread.sleep(ALARM_ON_MS);

        } catch (Exception e) {

            e.printStackTrace();

        } finally {

            try {

                setGpoLevel(
                        ALARM_OUTPUT_PORT,
                        GpioLevel.Low
                );

                System.out.println(
                        "✅ GPIO OFF"
                );

            } catch (Exception e) {

                e.printStackTrace();
            }
        }
    }

    /*
     * SET GPO LEVEL
     */
    private void setGpoLevel(
            byte portNo,
            GpioLevel level
    ) {

        GpioLevelParameter parameter =
                new GpioLevelParameter();

        parameter.PortNO = portNo;

        parameter.Level = level;

        MsgGpoConfig message =
                new MsgGpoConfig(parameter);

        boolean success =
                reader.Send(message);

        if (!success) {

            System.out.println(
                    "❌ Failed GPO"
            );

            System.out.println(
                    message.getErrorInfo()
            );
        }
    }

    /*
     * SEND TO NESTJS
     */
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

            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(
                                    URI.create(
                                            NEST_ALARM_URL
                                    )
                            )
                            .header(
                                    "Content-Type",
                                    "application/json"
                            )
                            .POST(
                                    HttpRequest.BodyPublishers
                                            .ofString(json)
                            )
                            .build();

            HttpClient client =
                    HttpClient.newHttpClient();

            HttpResponse<String> response =
                    client.send(
                            request,
                            HttpResponse.BodyHandlers
                                    .ofString()
                    );

            System.out.println(
                    "✅ NestJS response: "
                            + response.body()
            );

        } catch (Exception e) {

            e.printStackTrace();
        }
    }

    /*
     * NORMALIZE EPC
     */
    private String normalizeEpc(String epc) {

        return epc
                .replace(" ", "")
                .trim()
                .toUpperCase();
    }

    /*
     * BYTE ARRAY TO HEX
     */
    private String bytesToHex(byte[] bytes) {

        StringBuilder result =
                new StringBuilder();

        for (byte b : bytes) {

            result.append(
                    String.format("%02X", b)
            );
        }

        return result.toString();
    }
}