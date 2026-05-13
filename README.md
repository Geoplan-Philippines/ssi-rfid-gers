# Idata RFID

Java-based RFID reader project using the vendor-provided UHF RFID SDK.

This repository contains the application code only. The vendor SDK files, user manual, and technical documentation are stored separately in Google Drive.

## Documentation and SDK

User manual, SDK, and technical documentation:
```txt
https://drive.google.com/drive/folders/13f68LSoOqnDyp51hAzygFOat2tn_JVxC?usp=drive_link
```
If you do not have access, ask Sir Miks to grant access.

## Required JAR Files

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

## Setup Guide

### 1. Clone the repository

```bash
git clone https://github.com/Geoplan-Philippines/idata-rfid-sdk-implementation.git
cd idata-rfid-sdk-implementation
```

## IntelliJ IDEA Setup

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
if you're not using intellij you're on your own

## Running the Application

Once the JAR files are added:

1. Open the main Java file.
2. Make sure the correct SDK JARs are attached.
3. Connect the r400 controller via ethernet.
4. Click the **Run / Play** button in IntelliJ IDEA.

If you are not using IntelliJ IDEA, you're on your own 😝