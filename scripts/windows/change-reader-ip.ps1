[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$NewIP = "172.16.210.200",

    [Parameter(Position = 1)]
    [string]$NewSubnet = "255.255.255.0",

    [Parameter(Position = 2)]
    [string]$NewGateway = "172.16.210.250",

    [Parameter(Position = 3)]
    [string]$InterfaceAlias = "Ethernet",

    [Parameter(Position = 4)]
    [string]$CurrentIP = "192.168.1.100",

    [Parameter(Position = 5)]
    [ValidateRange(1, 65535)]
    [int]$ReaderPort = 9090,

    [Parameter(Position = 6)]
    [string]$TemporaryPCIP = "192.168.1.10"
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "common.ps1")

function Test-StrictIPv4 {
    param([string]$Address)

    if ([string]::IsNullOrWhiteSpace($Address)) {
        return $false
    }

    $parts = $Address.Split('.')
    if ($parts.Count -ne 4) {
        return $false
    }

    foreach ($part in $parts) {
        if ($part -notmatch '^\d{1,3}$' -or [int]$part -gt 255) {
            return $false
        }
    }

    return $true
}

function Test-SameIPv4Subnet {
    param(
        [string]$First,
        [string]$Second,
        [string]$Mask
    )

    $firstBytes = ([System.Net.IPAddress]::Parse($First)).GetAddressBytes()
    $secondBytes = ([System.Net.IPAddress]::Parse($Second)).GetAddressBytes()
    $maskBytes = ([System.Net.IPAddress]::Parse($Mask)).GetAddressBytes()

    for ($index = 0; $index -lt 4; $index++) {
        if (($firstBytes[$index] -band $maskBytes[$index]) -ne ($secondBytes[$index] -band $maskBytes[$index])) {
            return $false
        }
    }

    return $true
}

function Set-AgentReaderHost {
    param(
        [string]$File,
        [string]$Address
    )

    if (-not (Test-Path $File)) {
        throw "agent.env was not found at '$File'. The reader changed IP, but the agent cannot be updated automatically."
    }

    $content = [System.IO.File]::ReadAllText($File)
    $pattern = '(?m)^[ \t]*READER_HOST[ \t]*=.*$'
    if ([System.Text.RegularExpressions.Regex]::IsMatch($content, $pattern)) {
        $content = [System.Text.RegularExpressions.Regex]::Replace(
            $content,
            $pattern,
            "READER_HOST=$Address"
        )
    } else {
        if ($content.Length -gt 0 -and -not $content.EndsWith("`n")) {
            $content += [Environment]::NewLine
        }
        $content += "READER_HOST=$Address" + [Environment]::NewLine
    }

    $utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($File, $content, $utf8WithoutBom)
}

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "ERROR: This script must be run in a PowerShell window launched as Administrator." -ForegroundColor Red
    exit 1
}

foreach ($setting in @(
    @{ Name = "NewIP"; Value = $NewIP },
    @{ Name = "NewSubnet"; Value = $NewSubnet },
    @{ Name = "NewGateway"; Value = $NewGateway },
    @{ Name = "CurrentIP"; Value = $CurrentIP }
)) {
    if (-not (Test-StrictIPv4 $setting.Value)) {
        Write-Host "ERROR: $($setting.Name) is not a valid IPv4 address: $($setting.Value)" -ForegroundColor Red
        exit 2
    }
}
if (-not [string]::IsNullOrWhiteSpace($TemporaryPCIP) -and -not (Test-StrictIPv4 $TemporaryPCIP)) {
    Write-Host "ERROR: TemporaryPCIP is not a valid IPv4 address: $TemporaryPCIP" -ForegroundColor Red
    exit 2
}
if (-not (Test-SameIPv4Subnet -First $NewIP -Second $NewGateway -Mask $NewSubnet)) {
    Write-Host "ERROR: NewGateway $NewGateway is not in the $NewIP / $NewSubnet subnet." -ForegroundColor Red
    exit 2
}

$adapter = Get-NetAdapter -Name $InterfaceAlias -ErrorAction SilentlyContinue
if (-not $adapter) {
    Write-Host "ERROR: Network adapter '$InterfaceAlias' was not found." -ForegroundColor Red
    Write-Host "Available adapters:" -ForegroundColor Yellow
    Get-NetAdapter | Format-Table -AutoSize Name, Status, LinkSpeed
    exit 1
}

$projectRoot = Get-RepoRoot
Set-Location $projectRoot

$sourceFile = Join-Path $projectRoot "src\com\geoplan\rfid\agent\util\ChangeReaderIP.java"
$classFile = Join-Path $projectRoot "build\classes\com\geoplan\rfid\agent\util\ChangeReaderIP.class"
if (-not (Test-Path $classFile) -or (Get-Item $sourceFile).LastWriteTimeUtc -gt (Get-Item $classFile).LastWriteTimeUtc) {
    Write-Host "Building the current reader utility..." -ForegroundColor Gray
    & (Join-Path $PSScriptRoot "build.ps1")
}

$java = Get-JavaTool -Name "java"
$classPath = (Join-Path $projectRoot "build\classes") + ";" + (Get-SdkClassPath)
$agentEnv = Join-Path $projectRoot "agent.env"
$serviceName = "SSIRfidAgent"
$service = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
$serviceWasRunning = $service -and $service.Status -eq "Running"

if (-not $serviceWasRunning) {
    $standaloneAgent = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like "*com.geoplan.rfid.agent.AgentMain*" } |
        Select-Object -First 1
    if ($standaloneAgent) {
        Write-Host "ERROR: The reader agent is running outside the Windows service (PID $($standaloneAgent.ProcessId))." -ForegroundColor Red
        Write-Host "Stop it with scripts\windows\stop.ps1, then run this migration again." -ForegroundColor Yellow
        exit 1
    }
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " iData R400 Reader IP Migration Utility                   " -ForegroundColor Cyan
Write-Host " Current Reader  : $CurrentIP`:$ReaderPort" -ForegroundColor Cyan
Write-Host " Target Reader   : $NewIP`:$ReaderPort" -ForegroundColor Cyan
Write-Host " Subnet Mask     : $NewSubnet" -ForegroundColor Cyan
Write-Host " Default Gateway : $NewGateway" -ForegroundColor Cyan
Write-Host " Network Adapter : $InterfaceAlias" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# Add a secondary address instead of replacing the adapter's existing address.
# This preserves the desk's middleware/internet connection and any static LAN setup.
$temporaryAddressAdded = $false
$configurationAccepted = $false
$operationError = $null

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

if ($serviceWasRunning) {
    Write-Host "`n[Step 1/4] Stopping '$serviceName' so it releases the reader..." -ForegroundColor Yellow
    try {
        Stop-Service -Name $serviceName -Force
        (Get-Service -Name $serviceName).WaitForStatus("Stopped", [TimeSpan]::FromSeconds(15))
    } catch {
        $currentService = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
        if ($currentService -and $currentService.Status -ne "Running") {
            Start-Service -Name $serviceName -ErrorAction SilentlyContinue
        }
        Write-Host "ERROR: Could not stop '$serviceName': $($_.Exception.Message)" -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host "`n[Step 1/4] Reader agent is not running; no service needs to be stopped." -ForegroundColor Gray
}

try {
    if (-not [string]::IsNullOrWhiteSpace($TemporaryPCIP)) {
        Write-Host "`n[Step 2/4] Adding temporary address $TemporaryPCIP/24 to '$InterfaceAlias'..." -ForegroundColor Yellow
        $existingTemporaryAddress = Get-NetIPAddress -InterfaceAlias $InterfaceAlias -AddressFamily IPv4 -ErrorAction SilentlyContinue |
            Where-Object { $_.IPAddress -eq $TemporaryPCIP }

        if (-not $existingTemporaryAddress) {
            New-NetIPAddress -InterfaceAlias $InterfaceAlias -IPAddress $TemporaryPCIP -PrefixLength 24 -SkipAsSource $true -PolicyStore ActiveStore | Out-Null
            $temporaryAddressAdded = $true
        }

        $settled = $false
        for ($index = 0; $index -lt 15; $index++) {
            Start-Sleep -Seconds 1
            $ipInfo = Get-NetIPAddress -InterfaceAlias $InterfaceAlias -AddressFamily IPv4 -ErrorAction SilentlyContinue |
                Where-Object { $_.IPAddress -eq $TemporaryPCIP }
            if ($ipInfo -and $ipInfo.AddressState -eq "Preferred") {
                $settled = $true
                break
            }
            if ($ipInfo -and $ipInfo.AddressState -eq "Duplicate") {
                throw "Temporary address $TemporaryPCIP is already in use by another device."
            }
        }
        if (-not $settled) {
            throw "Temporary address $TemporaryPCIP did not become usable within 15 seconds."
        }
    } else {
        Write-Host "`n[Step 2/4] No temporary PC address requested." -ForegroundColor Gray
    }

    Write-Host "Checking reader presence at $CurrentIP via ARP..." -ForegroundColor Gray
    $readerSeen = $false
    for ($index = 0; $index -lt 10; $index++) {
        if ([ArpChecker]::Check($CurrentIP)) {
            $readerSeen = $true
            Write-Host "Reader responded to ARP." -ForegroundColor Green
            break
        }
        Start-Sleep -Seconds 1
    }
    if (-not $readerSeen) {
        Write-Host "Warning: no ARP response; trying the vendor TCP protocol anyway." -ForegroundColor Yellow
    }

    if ($NewIP -ne $CurrentIP -and [ArpChecker]::Check($NewIP)) {
        throw "Target IP $NewIP already responds to ARP. Choose an unused address to avoid an IP conflict."
    }

    Write-Host "`n[Step 3/4] Updating the reader through $CurrentIP`:$ReaderPort..." -ForegroundColor Yellow
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        Write-Host "Attempt $attempt of 3..." -ForegroundColor Gray
        & $java -cp $classPath com.geoplan.rfid.agent.util.ChangeReaderIP $CurrentIP $ReaderPort $NewIP $NewSubnet $NewGateway
        $utilityExitCode = $LASTEXITCODE
        if ($utilityExitCode -eq 0) {
            $configurationAccepted = $true
            break
        }
        if ($utilityExitCode -eq 2) {
            break
        }
        Start-Sleep -Seconds 2
    }
} catch {
    $operationError = $_.Exception.Message
} finally {
    Write-Host "`n[Step 4/4] Removing only the temporary PC address..." -ForegroundColor Yellow
    if ($temporaryAddressAdded) {
        try {
            Remove-NetIPAddress -InterfaceAlias $InterfaceAlias -IPAddress $TemporaryPCIP -Confirm:$false -ErrorAction Stop
            Write-Host "Removed $TemporaryPCIP. The adapter's original IP, gateway, and DNS were preserved." -ForegroundColor Green
        } catch {
            Write-Host "WARNING: Could not remove temporary address $TemporaryPCIP`: $($_.Exception.Message)" -ForegroundColor Yellow
        }
    } else {
        Write-Host "No temporary address was added; the adapter was not changed." -ForegroundColor Gray
    }
}

$configUpdated = $false
$targetReachable = $false
if ($configurationAccepted) {
    try {
        Set-AgentReaderHost -File $agentEnv -Address $NewIP
        $configUpdated = $true
        Write-Host "Updated READER_HOST=$NewIP in agent.env." -ForegroundColor Gray
    } catch {
        $operationError = $_.Exception.Message
    }

    Write-Host "Waiting for $NewIP`:$ReaderPort to become reachable..." -ForegroundColor Cyan
    for ($attempt = 1; $attempt -le 12; $attempt++) {
        Start-Sleep -Seconds 2
        try {
            if (Test-NetConnection -ComputerName $NewIP -Port $ReaderPort -InformationLevel Quiet -WarningAction SilentlyContinue) {
                $targetReachable = $true
                break
            }
        } catch {
            $operationError = "Could not test $NewIP`:$ReaderPort`: $($_.Exception.Message)"
            break
        }
    }
}

$serviceRestarted = $true
if ($serviceWasRunning) {
    try {
        Write-Host "Restarting '$serviceName' so it loads the new READER_HOST..." -ForegroundColor Cyan
        Start-Service -Name $serviceName
        (Get-Service -Name $serviceName).WaitForStatus("Running", [TimeSpan]::FromSeconds(15))
    } catch {
        $serviceRestarted = $false
        $operationError = "Reader settings were processed, but '$serviceName' could not be restarted: $($_.Exception.Message)"
    }
}

$deploymentVerified = $null
if ($serviceWasRunning -and $serviceRestarted -and $configUpdated) {
    try {
        Write-Host "`nVerifying the complete R400 -> GERS -> deployed middleware path..." -ForegroundColor Cyan
        & (Join-Path $PSScriptRoot "verify-deployment.ps1") -RequireService
        $deploymentVerified = $true
    } catch {
        $deploymentVerified = $false
        $operationError = $_.Exception.Message
    }
}

if (-not $configurationAccepted) {
    Write-Host "`n==========================================================" -ForegroundColor Red
    Write-Host "FAILED to update the reader at $CurrentIP`:$ReaderPort." -ForegroundColor Red
    if ($operationError) {
        Write-Host $operationError -ForegroundColor Red
    }
    Write-Host "If the reader is no longer at its factory IP, rerun with -CurrentIP <its-current-IP>." -ForegroundColor Yellow
    Write-Host "If it is still at the factory IP, connect it directly to the PC and retry." -ForegroundColor Yellow
    Write-Host "==========================================================" -ForegroundColor Red
    exit 1
}

if (-not $configUpdated -or -not $serviceRestarted) {
    Write-Host "`n==========================================================" -ForegroundColor Red
    Write-Host "PARTIAL SUCCESS: The reader accepted $NewIP, but the agent was not fully updated." -ForegroundColor Red
    if ($operationError) {
        Write-Host $operationError -ForegroundColor Red
    }
    Write-Host "Set READER_HOST=$NewIP in agent.env and restart the reader agent." -ForegroundColor Yellow
    Write-Host "==========================================================" -ForegroundColor Red
    exit 1
}

Write-Host "`n==========================================================" -ForegroundColor Green
Write-Host "SUCCESS: The iData R400 accepted the new IP $NewIP." -ForegroundColor Green
if ($targetReachable) {
    Write-Host "Reader TCP check passed at $NewIP`:$ReaderPort." -ForegroundColor Green
} else {
    Write-Host "WARNING: $NewIP`:$ReaderPort is not reachable from this PC yet." -ForegroundColor Yellow
    Write-Host "Confirm that the PC has an address in the $NewIP / $NewSubnet network and that the switch/VLAN permits client traffic." -ForegroundColor Yellow
}
if ($deploymentVerified -eq $false) {
    Write-Host "WARNING: The reader IP changed, but complete middleware verification failed." -ForegroundColor Yellow
    Write-Host "Run scripts\windows\verify-deployment.ps1 after checking agent.env and the logs." -ForegroundColor Yellow
}
if (-not $serviceWasRunning) {
    Write-Host "The agent was not running. Start it, then run scripts\windows\verify-deployment.ps1." -ForegroundColor Cyan
}
Write-Host "The R400 IP is READER_HOST. It does not replace MIDDLEWARE_BASE_URL or READER_ID." -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Green

if (-not $targetReachable -or $deploymentVerified -eq $false) {
    exit 2
}
