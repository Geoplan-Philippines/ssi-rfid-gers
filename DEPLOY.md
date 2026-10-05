# SSI RMK Reader Agent Deployment Guide

This guide describes how to configure, deploy, and run the **SSI RMK Reader Agent** (`ssi-rfid-gers`) in the background on both **Linux** and **Windows** production desks.

---

## 1. System Architecture

```
[ Middleware (NestJS) ]
        │  ▲
        │  │  HTTPS POST /api/v1/epc-scan-processing/sessions/{sessionId}/reads
        │  │  (Header: x-api-key)
        ▼  │
[ Reader Agent (Java) ]   <--- Listens on HTTPS port 8443 (POST /scan/start, /scan/stop)
        │
        ▼  TCP socket port 9090
[ iData R400 / RM720X Reader ]  (Connected to physical antennas 1, 2)
```

- **Agent Control Server**: HTTPS on port `8443` (accepts start/stop commands from Middleware).
- **Reader Connection**: TCP client on port `9090` (factory default: `192.168.1.100:9090`; production desk assigned static IP, e.g. `172.16.210.200:9090`).
- **Middleware Push**: HTTP/HTTPS POST to `{MIDDLEWARE_BASE_URL}/api/v1/epc-scan-processing/sessions/{sessionId}/reads`.

---

## 2. Prerequisites & Including the JDK

The agent requires **Java 17 (LTS) or higher**. All scripts in `scripts/linux/` and `scripts/windows/` search for Java in this priority order:
1. **Bundled portable JDK** in `<repo-root>/jdk` (highest priority)
2. **`JAVA_HOME`** environment variable
3. System **`PATH`**

---

### Option A: Bundled Portable JDK (Recommended for Locked-Down Desks — Zero Installation)

If desk computers do not have Java installed or the user lacks Administrator / root privileges, you can bundle a portable JDK directly inside the project root:

#### For Windows:
1. Download the portable zip of [Eclipse Temurin 17 (x64 Windows)](https://adoptium.net/temurin/releases/?version=17&os=windows&arch=x64&package=jdk).
2. Extract the archive into the `ssi-rfid-gers` root folder and rename the extracted folder to `jdk`:
   ```text
   ssi-rfid-gers/
   ├── jdk/
   │   ├── bin/
   │   │   ├── java.exe
   │   │   └── javac.exe
   │   ├── conf/
   │   ├── include/
   │   └── lib/
   ├── libs/
   │   ├── UhfRfidAPI.jar
   │   └── RXTXcomm.jar
   ├── scripts/
   └── agent.env
   ```
3. The PowerShell scripts will automatically detect and use `.\jdk\bin\java.exe`. No installer or system PATH changes needed!

#### For Linux:
1. Download the portable `.tar.gz` of [Eclipse Temurin 17 (x64 Linux)](https://adoptium.net/temurin/releases/?version=17&os=linux&arch=x64&package=jdk).
2. Extract directly into the project directory:
   ```bash
   tar -xzf OpenJDK17U-jdk_x64_linux_hotspot_*.tar.gz
   mv jdk-17* jdk
   ```
3. Verify that `./jdk/bin/java -version` runs. The shell scripts will automatically prioritize `./jdk/bin/java`.

*(Note: The `jdk/` directory is automatically ignored by `.gitignore`).*

---

### Option B: System-Wide JDK Installation

#### Linux (Ubuntu / Debian):
```bash
sudo apt update
sudo apt install -y openjdk-17-jdk

# Verify installation:
java -version
javac -version
```

#### Windows:

**Method 1: 1-Line Command via `winget` (Recommended — equivalent to `apt`)**  
Run in PowerShell as Administrator:
```powershell
winget install EclipseAdoptium.Temurin.17.JDK
```
*(Or Microsoft's build: `winget install Microsoft.OpenJDK.17`)*
- Automatically adds `java` and `javac` to system `PATH`.
- Automatically configures `JAVA_HOME`.
- Restart PowerShell and verify:
  ```powershell
  java -version
  javac -version
  ```

**Method 2: Official MSI Installer (Point & Click)**
1. Download and run the **[Eclipse Temurin 17 MSI Installer](https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.12%2B7/OpenJDK17U-jdk_x64_windows_hotspot_17.0.12_7.msi)** from [adoptium.net](https://adoptium.net/).
2. During the setup wizard, ensure both features are enabled:
   - ✅ **Add to PATH**
   - ✅ **Set JAVA_HOME variable**
3. Open a fresh PowerShell terminal and verify:
   ```powershell
   java -version
   javac -version
   ```

---

### Option C: Create a Minimal Custom JRE (~45 MB) with `jlink`

If you want to distribute a lightweight bundled runtime instead of the full 250 MB JDK:
```bash
# Run from a machine that has JDK 17 installed:
jlink \
  --add-modules java.base,java.logging,java.desktop,jdk.httpserver,java.management \
  --strip-debug \
  --no-man-pages \
  --no-header-files \
  --compress=2 \
  --output jdk
```
Drop this generated `jdk` folder onto the desk computer. It contains only the exact modules needed by the agent and vendor SDK.

---

### Vendor SDK JARs
The vendor libraries must reside in the [`libs/`](libs/) directory:
- `UhfRfidAPI.jar`
- `RXTXcomm.jar`

### Network & Firewall
- **Port 8443 (TCP inbound)**: Must be open to allow the Middleware to call `/scan/start` and `/scan/stop`.
- **Port 9090 (TCP outbound)**: Must be reachable from the agent PC to the R400 reader IP (default: `192.168.1.100:9090`).
- **Port 8000 (TCP outbound)**: Must be reachable from the agent PC to the Middleware API.

---

## 3. Configuration (`agent.env`)

Create or edit `agent.env` in the root of `ssi-rfid-gers`:

```properties
# --- Reader Hardware Settings ---
# Factory default is 192.168.1.100:9090. On warehouse/office LAN, assign a static IP on the subnet:
READER_HOST=172.16.210.200
READER_PORT=9090
READER_NAME=Device1
READER_MODE=hardware
READER_ANTENNAS=1,2

# --- Agent Control Server ---
AGENT_BIND_ADDRESS=0.0.0.0
AGENT_CONTROL_PORT=8443

# --- Middleware Connection ---
MIDDLEWARE_BASE_URL=https://api-stg-sling.rgoc.com.ph
MIDDLEWARE_API_KEY=rfid_BiJ2iO2ywe9ifihVXlTYZGaLTB_SodUP4-7HK8IWug4

# --- Batching & Flush Settings ---
EPC_FLUSH_INTERVAL_MS=1000
EPC_BATCH_SIZE=50
```

> **Note**: If testing without physical RFID hardware, set `READER_MODE=simulator` to emit deterministic synthetic EPCs.

---

## 4. Reader Hardware Network Setup & IP Migration (iData R400 / RM720X)

### Factory Default Behavior
Out of the box, the iData R400 (model RM720X) uses:
- **Default IP**: `192.168.1.100`
- **TCP Port**: `9090` (Server / listening mode)
- **Subnet Mask**: `255.255.255.0`
- **Gateway**: `192.168.1.1`
- **DHCP**: **Disabled** by default.

When plugged into an enterprise or warehouse switch whose subnet is different (e.g. `172.16.210.0/24`), the reader **will not** receive a DHCP IP automatically. Because the host PC and reader reside in different IP subnets, normal TCP connections to `192.168.1.100:9090` will fail with `Connection timed out`.

---

### Automated Reader IP Migration Tool (`change-reader-ip.ps1`)

An automated utility is provided in `scripts\windows\change-reader-ip.ps1` that flashes the reader's internal IP address to match your local network:

```powershell
# Run in PowerShell as Administrator:
powershell -ExecutionPolicy Bypass -File scripts\windows\change-reader-ip.ps1 -NewIP 172.16.210.200 -NewSubnet 255.255.255.0 -NewGateway 172.16.210.250
```

#### What the script does:
1. Temporarily assigns `192.168.1.10` to your PC's Ethernet interface.
2. Waits for link stabilization and sends low-level ARP probes to locate the reader at `192.168.1.100`.
3. Connects over port `9090` using the vendor VRP protocol (`MsgIpAddressConfig`) and flashes the reader to the new static IP (`172.16.210.200`). **The reader will emit an audible beep** when the new configuration is applied and its network stack reboots.
4. Restores your PC's network adapter back to DHCP in a guaranteed `finally` block (restoring internet access).
5. Automatically updates `READER_HOST` in `agent.env` and `.env`.

#### Switch Port Isolation Fallback (Direct Cable)
If your managed switch (e.g. Cisco Meraki) has 802.1X, port security, or client isolation that drops `192.168.1.x` packets:
1. Unplug the reader's Ethernet cable from the switch.
2. Plug the cable directly into your PC's Ethernet port.
3. Run `scripts\windows\change-reader-ip.ps1 -NewIP 172.16.210.200`.
4. Once the reader beeps and reports success, plug both the PC and the reader back into the switch.

#### Verify Reader Connectivity
After migration, test that the reader responds on its new IP:
```powershell
Test-NetConnection -ComputerName 172.16.210.200 -Port 9090
```
Expected output: `TcpTestSucceeded : True`.

---

## 5. Linux Deployment

### Method A: Background Scripts (Recommended for Quick Desk Setup)

All scripts reside in the `scripts/linux/` directory:

1. **Start Agent in Background**:
   ```bash
   ./scripts/linux/start-background.sh
   ```
   - Automatically builds the code if `build/classes` is missing.
   - Spawns Java in the background with `nohup`.
   - Stores PID in `logs/agent.pid`.
   - Output logs are written to `logs/agent.log`.

2. **Check Agent Status**:
   ```bash
   ./scripts/linux/status.sh
   ```
   Outputs current PID, checks `https://localhost:8443/health`, and displays recent logs.

3. **Stop Agent**:
   ```bash
   ./scripts/linux/stop.sh
   ```
   Sends graceful `SIGTERM` so the agent disconnects cleanly from the reader socket, followed by fallback cleanup.

---

### Method B: Systemd Service (Recommended for Dedicated Production Servers)

To automatically start the agent on boot and restart on crash:

1. Create a service file:
   ```bash
   sudo nano /etc/systemd/system/ssi-rfid-gers.service
   ```

2. Paste the following configuration (replace `/home/kukaass` with your actual user home path):
   ```ini
   [Unit]
   Description=SSI RMK RFID Reader Agent
   After=network.target

   [Service]
   Type=simple
   User=kukaass
   WorkingDirectory=/home/kukaass/Documents/Geoplan/ssi/ssi-rfid-gers
   ExecStart=/usr/bin/java -Dfile.encoding=UTF-8 -cp "build/classes:libs/UhfRfidAPI.jar:libs/RXTXcomm.jar" com.geoplan.rfid.agent.AgentMain
   Restart=always
   RestartSec=5s
   StandardOutput=append:/home/kukaass/Documents/Geoplan/ssi/ssi-rfid-gers/logs/agent.log
   StandardError=append:/home/kukaass/Documents/Geoplan/ssi/ssi-rfid-gers/logs/agent.log

   [Install]
   WantedBy=multi-user.target
   ```

3. Enable and start the service:
   ```bash
   sudo systemctl daemon-reload
   sudo systemctl enable ssi-rfid-gers
   sudo systemctl start ssi-rfid-gers
   ```

4. Manage the service:
   ```bash
   sudo systemctl status ssi-rfid-gers
   sudo systemctl restart ssi-rfid-gers
   sudo journalctl -u ssi-rfid-gers -f
   ```

---

## 6. Windows Deployment

### Method A: Background PowerShell Scripts

All scripts reside in the `scripts\windows\` directory:

1. **Start Agent in Background**:
   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\windows\start-background.ps1
   ```
   - Automatically compiles source if `build\classes` is missing.
   - Spawns Java in a hidden window (`WindowStyle Hidden`).
   - Stores PID in `logs\agent.pid`.
   - Output logs are written to `logs\agent.log` and `logs\agent.err.log`.

2. **Check Agent Status**:
   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\windows\status.ps1
   ```

3. **Stop Agent**:
   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\windows\stop.ps1
   ```

---

### Method B: Windows Service via NSSM (Recommended for Production Desks)

NSSM (Non-Sucking Service Manager) allows running the Java agent as a true Windows background service that boots before user login.

1. Ensure `nssm.exe` is available (e.g. in `C:\Program Files\nssm\win64`, `C:\Windows\System32`, or your PATH).
2. Open **PowerShell as Administrator**:

   **Automated 1-command installation (Recommended):**
   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\windows\install-service.ps1
   ```
   *(To uninstall: `powershell -ExecutionPolicy Bypass -File scripts\windows\uninstall-service.ps1`)*

   **Managing the Service:**
   ```powershell
   # Start the service
   nssm start SSIRfidAgent
   # or: Start-Service SSIRfidAgent

   # Stop the service
   nssm stop SSIRfidAgent
   # or: Stop-Service SSIRfidAgent

   # Restart the service
   nssm restart SSIRfidAgent
   # or: Restart-Service SSIRfidAgent

   # Check status
   nssm status SSIRfidAgent
   # or: Get-Service SSIRfidAgent

   # View live logs
   Get-Content logs\agent.log -Tail 30 -Wait
   ```

   **Or Manual setup via NSSM commands:**
   ```powershell
   # 1. Resolve variables in current directory
   $AgentDir = (Get-Item .).FullName
   $JavaExe = (Get-Command java).Source

   # 2. Install & Configure Service (Note: $JavaExe must not be empty or NSSM will open the GUI installer)
   nssm install SSIRfidAgent "$JavaExe"
   nssm set SSIRfidAgent AppDirectory "$AgentDir"
   nssm set SSIRfidAgent AppParameters "-Dfile.encoding=UTF-8 -cp `"build\classes;libs\UhfRfidAPI.jar;libs\RXTXcomm.jar`" com.geoplan.rfid.agent.AgentMain"
   nssm set SSIRfidAgent AppStdout "$AgentDir\logs\agent.log"
   nssm set SSIRfidAgent AppStderr "$AgentDir\logs\agent.err.log"
   nssm set SSIRfidAgent Start SERVICE_AUTO_START

   # 3. Start Service
   nssm start SSIRfidAgent
   ```

---

### Method C: Windows Task Scheduler (Native Startup Task)

1. Open **Task Scheduler** (`taskschd.msc`).
2. Click **Create Task**:
   - **General**: Name: `SSI RFID Reader Agent`, Check *Run whether user is logged on or not*.
   - **Triggers**: *At startup*.
   - **Actions**:
     - Action: *Start a program*
     - Program/script: `powershell.exe`
     - Arguments: `-ExecutionPolicy Bypass -WindowStyle Hidden -File "C:\Geoplan\ssi-rfid-gers\scripts\windows\start-background.ps1"`
     - Start in: `C:\Geoplan\ssi-rfid-gers`
   - **Conditions**: Uncheck *Stop if the computer switches to battery power*.

---

## 7. Verification & Health Monitoring

Test the agent control server from the local machine or over LAN:

```bash
curl -k https://<AGENT_IP>:8443/health
```

Expected JSON Response:
```json
{
  "status": "ok",
  "reader": "172.16.210.200:9090",
  "readerConnected": true,
  "inventoryRunning": false,
  "activeSessionId": null,
  "uniqueEpcs": 0,
  "sentEpcs": 0,
  "pendingEpcs": 0
}
```

### Key Health Fields:
| Field | Expected Healthy Value | Meaning |
| :--- | :--- | :--- |
| `status` | `"ok"` | Control HTTP server is responsive. |
| `readerConnected` | `true` | TCP connection to `READER_HOST:9090` is established. |
| `inventoryRunning` | `true` / `false` | `true` when a warehouse scan is in progress. |
| `activeSessionId` | UUID / `null` | The active scan session ID receiving tag reads. |

---

## 8. Troubleshooting

### 1. `Address already in use: bind` on Port 8443
Another instance of the agent is already running.
- **Linux**: Run `./scripts/stop.sh` or check `lsof -i :8443` and kill the PID.
- **Windows**: Run `powershell -File scripts\windows\stop.ps1`, or `nssm stop SSIRfidAgent`, or check `Get-NetTCPConnection -LocalPort 8443`.

### 2. Reader Connection Fails (`ErrCode:FF19 Connection timed out: connect`)
The agent cannot reach the reader at `READER_HOST:9090`:
- **Subnet mismatch (Factory default IP)**: Brand new or reset iData R400 readers default to `192.168.1.100:9090` with DHCP disabled. If your desk network is on another subnet (e.g. `172.16.210.x`), run:
  ```powershell
  powershell -ExecutionPolicy Bypass -File scripts\windows\change-reader-ip.ps1 -NewIP 172.16.210.200
  ```
- **Switch VLAN isolation**: If the corporate switch blocks `192.168.1.x` packets, connect the reader directly to the PC's Ethernet port with a patch cable, run `change-reader-ip.ps1`, and then plug both back into the switch.
- **Verify reader port**: Check TCP 9090 using PowerShell:
  ```powershell
  Test-NetConnection -ComputerName 172.16.210.200 -Port 9090
  ```

### 3. Middleware returns HTTP 401 or 403 on Tag Append
The agent is rejecting or receiving rejection from the Middleware:
- Verify `MIDDLEWARE_API_KEY` in `agent.env` matches a valid API key record in the Middleware database (`api_keys` table).
- Verify `MIDDLEWARE_BASE_URL` points to the correct origin (e.g. `https://api-stg-sling.rgoc.com.ph` or `http://localhost:8000`).

### 4. Linux `/tmp/` SDK Path Issue
The vendor `UhfRfidAPI.jar` SDK expects temporary extraction folders for native library unpacking. `AgentMain.java` automatically sets `Utils.APIPath.folderName = "/tmp/"` on Linux. Ensure `/tmp` is writable by the running user.
