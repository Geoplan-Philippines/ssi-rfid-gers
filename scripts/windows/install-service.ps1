# Installs and starts SSIRfidAgent as a Windows Service using NSSM.
# Must be run as Administrator.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\windows\install-service.ps1
#

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'common.ps1')

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Error "This script must be run as Administrator. Please open PowerShell as Administrator and run: powershell -ExecutionPolicy Bypass -File scripts\windows\install-service.ps1"
    exit 1
}

function Find-Nssm {
    $onPath = Get-Command nssm -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }

    $candidates = @(
        "C:\Program Files\nssm\win64\nssm.exe",
        "C:\Program Files\nssm\win32\nssm.exe",
        "C:\Program Files (x86)\nssm\win32\nssm.exe",
        "C:\Windows\System32\nssm.exe",
        (Join-Path (Get-RepoRoot) "nssm.exe")
    )
    foreach ($c in $candidates) {
        if (Test-Path $c) { return (Resolve-Path $c).Path }
    }
    throw "nssm.exe not found. Place nssm.exe in C:\Program Files\nssm\win64 or C:\Windows\System32."
}

$nssm = Find-Nssm
$java = Get-JavaTool -Name 'java'
$root = Get-RepoRoot
$classes = Join-Path $root 'build\classes'
$logDir = Join-Path $root 'logs'
$logFile = Join-Path $logDir 'agent.log'
$errLogFile = Join-Path $logDir 'agent.err.log'
$serviceName = 'SSIRfidAgent'

if (-not (Test-Path $logDir)) {
    New-Item -ItemType Directory -Path $logDir -Force | Out-Null
}

# Build if needed
if (-not (Test-Path (Join-Path $classes 'com\geoplan\rfid\agent\AgentMain.class'))) {
    Write-Host "build\classes missing. Compiling first..."
    & (Join-Path $PSScriptRoot 'build.ps1')
}

$classPath = "build\classes;libs\UhfRfidAPI.jar;libs\RXTXcomm.jar"
$appParams = "-Dfile.encoding=UTF-8 -cp `"$classPath`" com.geoplan.rfid.agent.AgentMain"

Write-Host "Configuring Windows Service '$serviceName'..."
Write-Host "  NSSM:       $nssm"
Write-Host "  Java:       $java"
Write-Host "  Directory:  $root"

$existing = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
if ($existing) {
    Write-Host "Existing service found ($($existing.Status)). Stopping and updating..."
    & $nssm stop $serviceName 2>$null
    Start-Sleep -Seconds 1
} else {
    Write-Host "Installing service '$serviceName'..."
    & $nssm install $serviceName "$java"
}

# Ensure all properties are correctly set
& $nssm set $serviceName Application "$java"
& $nssm set $serviceName AppDirectory "$root"
& $nssm set $serviceName AppParameters $appParams
& $nssm set $serviceName AppStdout "$logFile"
& $nssm set $serviceName AppStderr "$errLogFile"
& $nssm set $serviceName Start SERVICE_AUTO_START

Write-Host "Starting service '$serviceName'..."
& $nssm start $serviceName

Start-Sleep -Seconds 2
$status = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
Write-Host "Service '$serviceName' status: $($status.Status)"
