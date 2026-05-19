# Idata RFID

Java-based RFID reader project using the vendor-provided UHF RFID SDK.

This repository contains the application code only. The vendor SDK files, user manual, and technical documentation are stored separately in Google Drive.

---

# Documentation and SDK

User manual, SDK, and technical documentation:

```txt
https://drive.google.com/drive/folders/13f68LSoOqnDyp51hAzygFOat2tn_JVxC?usp=drive_link
```

If you do not have access, ask Sir Miks to grant access.

---

# Required JAR Files

Inside the Google Drive folder, locate the SDK JAR files here:

```txt
SDK/JAVA/JAVA/JAVA API/libs
```

You need these two JAR files:

```txt
RXTXcomm.jar
UhfRfidAPI.jar
```

These files are required for the project to compile and run.

---

# Setup Guide

## 1. Clone the repository

```bash
git clone https://github.com/Geoplan-Philippines/idata-rfid-sdk-implementation.git
cd idata-rfid-sdk-implementation
```

---

# Configure Laptop Ethernet IP

Before connecting the RFID reader, configure the laptop Ethernet IPv4 settings.

1. Open **Control Panel**
2. Go to **Network and Internet**
3. Open **Network Connections**
4. Right click the Ethernet connection
5. Click **Properties**
6. Double click:

```txt
Internet Protocol Version 4 (TCP/IPv4)
```

7. Select:

```txt
Use the following IP address
```

8. Set the IP address to:

```txt
192.168.1.10
```

9. Click **OK**

---

# Wiring Configuration

## Devices

- iData R400
- R400 UHF antenna (SMA)
- AD16-22SM Indicator Light w/ Buzzer
- E3Z-R4MP3 Sensor
- 12V/24V DC Power Supply

---

# Wiring Table

| From | To | Purpose |
|---|---|---|
| R400 IN1 | E3Z Black Wire | Sensor Signal |
| R400 GND | E3Z Blue Wire | Ground |
| R400 12V/24V+ | E3Z Brown Wire | Sensor Power |
| R400 Out1 | AD16 X2 | Indicator Trigger |
| R400 12V/24V+ | AD16 X1 | Indicator Power |

---

# Wiring Notes

- The E3Z brown wire and AD16 X1 are connected together using a rat-tail splice.
- Common ground is required.
- The R400 controller must be connected via Ethernet to the laptop/PC.
- The R400 controller connects to the R400 antenna using the SMA connector.

---

# E3Z Sensor Wire Reference

| Wire Color | Function |
|---|---|
| Brown | V+ |
| Blue | GND |
| Black | Output Signal |

---

# IntelliJ IDEA Setup

1. Open the project in IntelliJ IDEA.
2. Go to **File > Project Structure**.
3. Go to **Libraries**.
4. Click the **+** button.
5. Select **Java**.
6. Navigate to the folder where you saved:

```txt
RXTXcomm.jar
UhfRfidAPI.jar
```

7. Select both JAR files.
8. Click **Apply**.
9. Click **OK**.

After this, IntelliJ should recognize the SDK classes.

If you're not using IntelliJ, you're on your own 😝

---

# RFID Test Tool Setup

1. Locate the C# files from the SDK folder.
2. Unzip the C# ZIP file first.
3. Open the:

```txt
IRFID test tool
```

folder.

4. Run:

```txt
RFIDTest.exe
```

---

# Connect RFIDTest.exe to the Reader

After opening the EXE file:

1. Click **Connect** on the upper left side of the interface.
2. Click **Reader Connect**.
3. A small window will appear.
4. Set the mode to:

```txt
Client
```

5. Set the IP to:

```txt
192.168.1.100
```

6. Set the port to:

```txt
9090
```

> The port should be the same as the one used in the code.

7. Click **Connect**.

---

# Running the Application

Once the JAR files are added:

1. Open the main Java file.
2. Make sure the correct SDK JARs are attached.
3. Connect the R400 controller via Ethernet.
4. Verify the wiring configuration is complete.
5. Click the **Run / Play** button in IntelliJ IDEA.

If you are not using IntelliJ IDEA, you're on your own 😝