# SSI RMK reader agent

Java agent that drives one iData R400 for the SSI RMK warehouse document flow.

The middleware owns the document. The agent owns the reader. It does three
things:

1. Accepts `POST /scan/start` and `POST /scan/stop` over HTTPS.
2. Runs continuous inventory on the R400 while a session is active.
3. Pushes unique EPCs to the middleware session.

There is no SKU matching, no Lawson audit and no anti-theft alarm here. The
previous anti-theft demo has been removed.

The middleware contract is defined in
[`warehouse-document-scan-start-change-note.md`](warehouse-document-scan-start-change-note.md).
That note wins if anything below disagrees with it.

---

## Contract

### Middleware calls the agent

```http
POST https://<reader-ip>:8443/scan/start
Content-Type: application/json

{ "sessionId": "a2c0b6d4-6f1e-4a3b-8c77-2f9f1d4e5b10" }
```

```http
POST https://<reader-ip>:8443/scan/stop
Content-Type: application/json

{ "sessionId": "a2c0b6d4-6f1e-4a3b-8c77-2f9f1d4e5b10" }
```

The port is `RFIDReader.controlPort`, and the middleware falls back to 8443 when
that column is null.

A non 2xx answer to start makes the middleware delete the OPEN session and put
the document back to NEW (502), and an unreachable agent does the same with 503.
So the agent only answers 200 to start once inventory is really running.

### Agent calls the middleware

```http
POST {MIDDLEWARE_BASE_URL}/api/v1/epc-scan-processing/sessions/{sessionId}/reads
Content-Type: application/json
x-api-key: <MIDDLEWARE_API_KEY>

{ "epcs": ["E280117000000001", "E280117000000002"] }
```

Between 1 and 1000 EPCs per request, uppercase hex. The server dedupes, and the
agent dedupes too: an EPC is sent once per session no matter how many times the
antenna sees it.

### Agent responses

| Request | Situation | Status | Body |
|---|---|---|---|
| `POST /scan/start` | inventory started | 200 | `{"status":"started","sessionId":"..."}` |
| `POST /scan/start` | same sessionId already scanning | 200 | `{"status":"already_running","sessionId":"..."}` |
| `POST /scan/start` | another session active, `SCAN_START_CONFLICT=takeover` (default) | 200 | `{"status":"started","sessionId":"...","replacedSessionId":"..."}` |
| `POST /scan/start` | another session active, `SCAN_START_CONFLICT=reject` | 409 | `{"error":"session_already_active","activeSessionId":"..."}` |
| `POST /scan/start` | reader offline or inventory refused | 503 | `{"error":"reader_unavailable","reader":"192.168.1.100:9090"}` |
| `POST /scan/start` | body has no usable `sessionId` | 400 | `{"error":"invalid_session_id"}` |
| `POST /scan/stop` | that session was running | 200 | `{"status":"stopped","uniqueEpcs":42,"sentEpcs":42}` |
| `POST /scan/stop` | nothing running, or a different session is running | 200 | `{"status":"not_running","activeSessionId":"..."}` |
| `GET /health` | always | 200 | reader and session state |

### A second start while one is active

One reader means one session. The default policy is `takeover`: the agent stops
the running session, pushes whatever it still has queued, then starts the new
one and reports `replacedSessionId`. This keeps the desk usable when the
middleware and the agent disagree about what is running, for example after a
middleware restart that left the agent scanning.

Set `SCAN_START_CONFLICT=reject` if you would rather protect a scan in progress.
The agent then answers 409 and the middleware rolls the new document back to
NEW. The desk is blocked until the first session is stopped.

Stop is deliberately lenient. A stop for an unknown session answers 200 and
leaves the active session alone, so a cancel can never wedge the document.

If the middleware answers 404, 409 or 410 to a reads append, the agent treats
the session as closed, stops inventory and goes idle. That is the safety valve
for a cancel the agent never heard about.

---

## Requirements

- JDK 17 or newer (the desk uses Temurin 17).
- The vendor SDK jars `UhfRfidAPI.jar` and `RXTXcomm.jar`.
- The R400 reachable over Ethernet, default `192.168.1.100:9090`.

The jars are not in this repo. Get them from the SDK folder on Drive:

```txt
https://drive.google.com/drive/folders/13f68LSoOqnDyp51hAzygFOat2tn_JVxC?usp=drive_link
SDK/JAVA/JAVA/JAVA API/libs
```

Ask Sir Miks for access. Put them anywhere and point `SDK_LIB_DIR` at that
folder, or drop them in `libs\` here. The build also falls back to
`%USERPROFILE%\Documents\Fixed RFID SDK\JAVA\JAVA API\libs`.

---

## Configuration

Copy `agent.env.example` to `agent.env` and edit. `agent.env` is gitignored and
is loaded automatically by `scripts\run.ps1`. Every key also works as a plain
environment variable or a `-DKEY=value` system property.

| Key | Default | Meaning |
|---|---|---|
| `READER_HOST` | `192.168.1.100` | R400 IP |
| `READER_PORT` | `9090` | R400 TCP port |
| `READER_NAME` | `Device1` | Name passed to the SDK |
| `READER_MODE` | `hardware` | `simulator` emits fake EPCs with no R400 |
| `READER_TAG_FILTERING_TIME_MS` | `0` | Reader side repeat filter, 0 keeps the vendor default |
| `READER_WATCHDOG_INTERVAL_MS` | `5000` | How often a live session checks the reader link |
| `AGENT_BIND_ADDRESS` | `0.0.0.0` | Control server bind address |
| `AGENT_CONTROL_PORT` | `8443` | Control server port, match `RFIDReader.controlPort` |
| `AGENT_API_KEY` | empty | Optional `x-api-key` required on `/scan/*` |
| `AGENT_TLS_KEYSTORE` | `certs/agent-keystore.p12` | Keystore, generated if missing |
| `AGENT_TLS_KEYSTORE_PASSWORD` | `changeit` | Keystore password |
| `AGENT_TLS_KEYSTORE_TYPE` | `PKCS12` | Keystore type |
| `AGENT_TLS_SAN` | empty | Extra SAN entries, for example `ip:192.168.1.10,dns:rmk-desk` |
| `MIDDLEWARE_BASE_URL` | `http://localhost:8000` | Middleware origin, no trailing slash |
| `MIDDLEWARE_API_KEY` | empty | Sent as `x-api-key` on every append |
| `MIDDLEWARE_READS_PATH` | `/api/v1/epc-scan-processing/sessions/{sessionId}/reads` | Append path template |
| `MIDDLEWARE_TIMEOUT_MS` | `5000` | Connect and request timeout |
| `EPC_BATCH_SIZE` | `200` | EPCs per append, 1 to 1000 |
| `EPC_FLUSH_INTERVAL_MS` | `1000` | How often queued EPCs are pushed |
| `EPC_MAX_PENDING` | `50000` | Queue cap while the middleware is unreachable |
| `SCAN_START_CONFLICT` | `takeover` | `takeover` or `reject`, see above |

No secrets live in source. The API key belongs in `agent.env` or in the service
environment.

---

## Build and run

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build.ps1
powershell -ExecutionPolicy Bypass -File scripts\run.ps1
```

`build.ps1` compiles `src` into `build\classes`. `run.ps1` loads `agent.env` and
starts `com.geoplan.rfid.agent.AgentMain`.

Plain commands, if you prefer them:

```powershell
$libs = "$env:USERPROFILE\Documents\Fixed RFID SDK\JAVA\JAVA API\libs"
javac -encoding UTF-8 -d build\classes -cp "$libs\UhfRfidAPI.jar;$libs\RXTXcomm.jar" (Get-ChildItem -Recurse src -Filter *.java).FullName
java -cp "build\classes;$libs\UhfRfidAPI.jar;$libs\RXTXcomm.jar" com.geoplan.rfid.agent.AgentMain
```

In IntelliJ, add both jars as libraries (File > Project Structure > Libraries),
then run `AgentMain`.

A healthy start looks like this:

```txt
INFO  Reader          : 192.168.1.100:9090 (name=Device1)
INFO  Control server  : https://0.0.0.0:8443 (auth=none)
INFO  Middleware      : http://localhost:8000/api/v1/epc-scan-processing/sessions/{sessionId}/reads (x-api-key=set)
INFO  Control server listening on https://0.0.0.0:8443
INFO  Reader connected | model=R400 | firmware=...
INFO  Ready. Waiting for POST /scan/start from the middleware.
```

---

## TLS

The agent serves HTTPS. If `AGENT_TLS_KEYSTORE` does not exist, it generates a
self signed certificate with `keytool` from the running JDK. The SAN covers
`localhost`, every IPv4 address on the machine and the hostname, so the
middleware can reach the agent at whatever address the reader row holds.

Because that certificate is self signed, the middleware needs:

```env
READER_AGENT_TLS_REJECT_UNAUTHORIZED=false
```

For a signed certificate, put it in a PKCS12 keystore and point
`AGENT_TLS_KEYSTORE` and `AGENT_TLS_KEYSTORE_PASSWORD` at it.

Open the port on the desk PC once:

```powershell
New-NetFirewallRule -DisplayName "SSI RFID agent 8443" -Direction Inbound -Protocol TCP -LocalPort 8443 -Action Allow
```

---

## Desk smoke test, one reader

### 1. Network

Set the desk Ethernet adapter to a static IPv4 of `192.168.1.10`, subnet
`255.255.255.0`. The R400 answers at `192.168.1.100:9090`. Confirm with the
vendor tool `RFIDTest.exe` (Connect > Reader Connect > Client, IP
`192.168.1.100`, port `9090`) before blaming the agent.

### 2. Start the agent

Fill in `agent.env`, then run `scripts\run.ps1`. Check it locally:

```powershell
curl.exe -k https://localhost:8443/health
```

```json
{"status":"ok","reader":"192.168.1.100:9090","readerConnected":true,"inventoryRunning":false,"activeSessionId":null,"uniqueEpcs":0,"sentEpcs":0,"pendingEpcs":0}
```

### 3. Register the reader in the middleware

The `RFIDReader` row needs:

- `ip` set to the desk PC LAN address that the middleware can reach, not the
  reader address. The middleware connects to the agent, and the agent connects
  to the R400.
- `controlPort` set to `8443`, or left null since the middleware defaults to
  8443.
- status `ACTIVE`.

Keep that reader UUID. It is the `deviceId` used to start a document.

### 4. Start a warehouse document

```http
POST /warehouse-documents/:id/start
{ "deviceId": "<ACTIVE reader UUID>" }
```

200 means the document is `in progress`, `sessionId` is set, and the middleware
already called `/scan/start` on the agent. The agent log shows:

```txt
INFO  Scanning for session <uuid> on reader 192.168.1.100:9090
INFO  Inventory started
```

### 5. Confirm reads land

Wave tags at the antenna. The agent logs each batch:

```txt
INFO  Appended 12 EPCs to session <uuid> (HTTP 201)
```

Then check the middleware side with `GET /epc-scan-processing/sessions/:sessionId`
and compare the EPC count with `GET /health` on the agent.

### 6. Cancel

```http
POST /warehouse-documents/:id/cancel
```

The middleware calls `/scan/stop`, the agent stops inventory and logs the
session summary. `GET /health` goes back to `inventoryRunning: false`.

### Without a reader on the desk

Set `READER_MODE=simulator` to exercise the whole path with fake EPCs:

```powershell
curl.exe -k -X POST https://localhost:8443/scan/start -H "Content-Type: application/json" -d "{\"sessionId\":\"a2c0b6d4-6f1e-4a3b-8c77-2f9f1d4e5b10\"}"
curl.exe -k https://localhost:8443/health
curl.exe -k -X POST https://localhost:8443/scan/stop -H "Content-Type: application/json" -d "{\"sessionId\":\"a2c0b6d4-6f1e-4a3b-8c77-2f9f1d4e5b10\"}"
```

---

## Layout

```txt
src/com/geoplan/rfid/agent/
  AgentMain.java              wiring and shutdown
  config/                     AgentConfig, Env lookup
  http/                       ControlServer (/scan/start, /scan/stop, /health), Tls
  reader/                     TagReader seam, R400TagReader (vendor SDK), SimulatedTagReader
  middleware/                 MiddlewareClient, append outcomes
  scan/                       ScanCoordinator (one active session), ScanSession (dedupe and queue)
  util/                       Log, Json, Epc
scripts/                      build.ps1, run.ps1, common.ps1
```

---

## Behaviour worth knowing

- **Dedupe.** Each EPC is sent once per session. Restarting a session resets the
  set, and the middleware dedupes again server side.
- **Buffering.** If the middleware is down, EPCs queue up to `EPC_MAX_PENDING`
  and go out when it comes back. Reads are not lost to a short outage.
- **Stop flushes.** Stop pushes what is queued before answering, with a short
  budget so cancel stays fast, then keeps retrying the remainder in the
  background for 30 seconds.
- **Reader watchdog.** While a session is active the agent checks the reader link
  every `READER_WATCHDOG_INTERVAL_MS` and reconnects if the R400 drops.
- **Bad payloads.** A 400 or 422 from the middleware drops that batch instead of
  retrying it forever. It is logged as an error.

---

## Troubleshooting

| Symptom | Look at |
|---|---|
| Start returns 503 | Reader unreachable. Ping `192.168.1.100`, check the Ethernet static IP, try `RFIDTest.exe`. |
| Middleware start returns 503 | Middleware cannot reach the agent. Check the reader `ip` and `controlPort` rows, the firewall rule, and that the agent is running. |
| Middleware start returns 502 | The agent answered non 2xx. Read the agent log at that timestamp. |
| TLS errors on the middleware | Set `READER_AGENT_TLS_REJECT_UNAUTHORIZED=false`, or install a trusted certificate. |
| Appends log HTTP 401 or 403 | `MIDDLEWARE_API_KEY` is wrong or missing. |
| Appends log HTTP 404 or 409 | The session is cancelled or completed. The agent stops itself. |
| Tags scan but nothing appends | Check `pendingEpcs` in `/health`, and the append errors in the log. |
| `keytool failed` at startup | Delete `certs\` and restart, or set `AGENT_TLS_KEYSTORE` to a keystore you control. |

---

## Hardware notes

- iData R400 controller, R400 UHF antenna on the SMA connector.
- 12V or 24V DC supply for the controller.
- Ethernet from the controller to the desk PC.

The GPIO wiring from the old anti-theft demo (E3Z sensor on IN1, AD16 indicator
on OUT1) is not used by this agent. The agent never touches GPIO.
