# Stops and removes SSIRfidAgent Windows Service.
# Must be run as Administrator.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\windows\uninstall-service.ps1
#

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'common.ps1')

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Error "This script must be run as Administrator."
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
    throw "nssm.exe not found."
}

$nssm = Find-Nssm
$serviceName = 'SSIRfidAgent'

Write-Host "Stopping service '$serviceName'..."
& $nssm stop $serviceName 2>$null

Write-Host "Removing service '$serviceName'..."
& $nssm remove $serviceName confirm

Write-Host "Service '$serviceName' removed successfully."
