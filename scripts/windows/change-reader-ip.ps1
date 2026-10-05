[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$NewIP = "172.16.210.200",

    [Parameter(Position = 1)]
    [string]$NewSubnet = "255.255.255.0",

    [Parameter(Position = 2)]
    [string]$NewGateway = "172.16.210.250",

    [Parameter(Position = 3)]
    [string]$InterfaceAlias = "Ethernet"
)

$ErrorActionPreference = "Stop"

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "ERROR: This script must be run in a PowerShell window launched as Administrator." -ForegroundColor Red
    exit 1
}

$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $projectRoot

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " iData R400 Reader IP Migration Utility                   " -ForegroundColor Cyan
Write-Host " Target Reader IP : $NewIP                                " -ForegroundColor Cyan
Write-Host " Subnet Mask      : $NewSubnet                            " -ForegroundColor Cyan
Write-Host " Default Gateway  : $NewGateway                           " -ForegroundColor Cyan
Write-Host " Network Adapter  : $InterfaceAlias                       " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# Prepare SendARP helper
$arpCode = @"
using System;
using System.Runtime.InteropServices;
using System.Net;

public class ArpChecker {
    [DllImport("iphlpapi.dll", ExactSpelling = true)]
    public static extern int SendARP(uint DestIP, uint SrcIP, byte[] pMacAddr, ref int PhyAddrLen);

    public static bool Check(string ipAddress) {
        IPAddress dst;
        if (!IPAddress.TryParse(ipAddress, out dst)) return false;
        uint dest = BitConverter.ToUInt32(dst.GetAddressBytes(), 0);
        byte[] mac = new byte[6];
        int len = mac.Length;
        return SendARP(dest, 0, mac, ref len) == 0;
    }
}
"@
if (-not ([System.Management.Automation.PSTypeName]'ArpChecker').Type) {
    Add-Type -TypeDefinition $arpCode
}

$success = $false
try {
    Write-Host "`n[Step 1/3] Temporarily assigning 192.168.1.10 to '$InterfaceAlias'..." -ForegroundColor Yellow
    netsh interface ipv4 set address name="$InterfaceAlias" static 192.168.1.10 255.255.255.0 | Out-Null

    # Wait for adapter link and IP address state to stabilize
    Write-Host "Waiting for network adapter link to settle..." -ForegroundColor Gray
    $settled = $false
    for ($i = 0; $i -lt 15; $i++) {
        Start-Sleep -Seconds 1
        $ipInfo = Get-NetIPAddress -InterfaceAlias $InterfaceAlias -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object { $_.IPAddress -eq "192.168.1.10" }
        if ($ipInfo -and $ipInfo.AddressState -eq "Preferred") {
            $settled = $true
            break
        }
        Write-Host "  ... waiting for IP state: Preferred (current: $($ipInfo.AddressState))" -ForegroundColor Gray
    }

    if (-not $settled) {
        Write-Host "Warning: Adapter IP state did not settle to Preferred within 15s. Proceeding anyway..." -ForegroundColor Yellow
    }

    # Verify reader responds on Layer 2
    Write-Host "Verifying reader presence at 192.168.1.100 via ARP..." -ForegroundColor Gray
    $readerSeen = $false
    for ($i = 0; $i -lt 10; $i++) {
        if ([ArpChecker]::Check("192.168.1.100")) {
            $readerSeen = $true
            Write-Host "Reader responded to ARP! (MAC confirmed online)" -ForegroundColor Green
            break
        }
        Start-Sleep -Seconds 1
    }

    if (-not $readerSeen) {
        Write-Host "Warning: Reader did not respond to ARP probe. Proceeding to TCP attempt..." -ForegroundColor Yellow
    }

    Write-Host "`n[Step 2/3] Connecting to reader at 192.168.1.100:9090 and reconfiguring IP..." -ForegroundColor Yellow
    
    # Try up to 3 attempts with small delay
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        Write-Host "Attempt $attempt of 3..." -ForegroundColor Gray
        java -cp "build\classes;libs\UhfRfidAPI.jar;libs\RXTXcomm.jar" com.geoplan.rfid.agent.util.ChangeReaderIP 192.168.1.100 9090 $NewIP $NewSubnet $NewGateway
        if ($LASTEXITCODE -eq 0) {
            $success = $true
            break
        }
        Start-Sleep -Seconds 2
    }
}
catch {
    Write-Host "Error occurred during IP reconfiguration: $_" -ForegroundColor Red
}
finally {
    Write-Host "`n[Step 3/3] Restoring '$InterfaceAlias' back to DHCP (re-enabling Internet)..." -ForegroundColor Yellow
    netsh interface ipv4 set address name="$InterfaceAlias" dhcp | Out-Null
    netsh interface ipv4 set dnsservers name="$InterfaceAlias" dhcp | Out-Null
    Start-Sleep -Seconds 4
    Write-Host "Ethernet interface restored to DHCP successfully." -ForegroundColor Green
}

if ($success) {
    Write-Host "`n==========================================================" -ForegroundColor Green
    Write-Host "SUCCESS! The iData R400 IP has been changed to: $NewIP" -ForegroundColor Green
    Write-Host "Testing connectivity on new IP: $NewIP:9090 ..." -ForegroundColor Cyan
    
    Start-Sleep -Seconds 3
    $tcp = Test-NetConnection -ComputerName $NewIP -Port 9090 -WarningAction SilentlyContinue
    if ($tcp.TcpTestSucceeded) {
        Write-Host "Reader responded on new IP $NewIP`:9090!" -ForegroundColor Green
    } else {
        Write-Host "Note: The reader may take 10-15 seconds to reboot its network stack. Run Test-NetConnection in a few seconds." -ForegroundColor Yellow
    }

    # Update agent.env and .env
    $updateFiles = @("agent.env", ".env")
    foreach ($file in $updateFiles) {
        if (Test-Path $file) {
            $content = Get-Content $file -Raw
            $content = $content -replace "READER_HOST=.*", "READER_HOST=$NewIP"
            Set-Content -Path $file -Value $content -NoNewline
            Write-Host "Updated READER_HOST=$NewIP in $file" -ForegroundColor Gray
        }
    }
    Write-Host "==========================================================" -ForegroundColor Green
    Write-Host "You can now restart the Windows service:" -ForegroundColor Cyan
    Write-Host "powershell -ExecutionPolicy Bypass -File scripts\windows\install-service.ps1" -ForegroundColor Cyan
} else {
    Write-Host "`n==========================================================" -ForegroundColor Red
    Write-Host "FAILED to connect to 192.168.1.100:9090 through the switch." -ForegroundColor Red
    Write-Host "If the switch port has VLAN / security isolation, please:" -ForegroundColor Yellow
    Write-Host "1. Unplug the reader from switch port 8." -ForegroundColor Yellow
    Write-Host "2. Plug it DIRECTLY into your PC's Ethernet port." -ForegroundColor Yellow
    Write-Host "3. Run this script again." -ForegroundColor Yellow
    Write-Host "4. Once configured, plug the reader back into switch port 8." -ForegroundColor Yellow
    Write-Host "==========================================================" -ForegroundColor Red
}
