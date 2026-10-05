# Stops the background agent on Windows.
#
#   powershell -ExecutionPolicy Bypass -File scripts\windows\stop.ps1
#
. (Join-Path $PSScriptRoot 'common.ps1')

$root = Get-RepoRoot
$pidFile = Join-Path $root 'logs\agent.pid'

$pids = @()

if (Test-Path $pidFile) {
    $val = (Get-Content $pidFile -Raw).Trim()
    if ($val) {
        $pids += [int]$val
    }
}

# Fallback: check who owns port 8443
$portConn = Get-NetTCPConnection -LocalPort 8443 -State Listen -ErrorAction SilentlyContinue
if ($portConn) {
    $pids += [int]$portConn.OwningProcess
}

$pids = $pids | Select-Object -Unique

if (-not $pids) {
    Write-Host "No running agent found."
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
    exit 0
}

foreach ($pidToStop in $pids) {
    $proc = Get-Process -Id $pidToStop -ErrorAction SilentlyContinue
    if ($proc) {
        Write-Host "Stopping agent process (PID: $pidToStop)..."
        $proc.CloseMainWindow() | Out-Null
        Start-Sleep -Seconds 1
        if (Get-Process -Id $pidToStop -ErrorAction SilentlyContinue) {
            Stop-Process -Id $pidToStop -Force -ErrorAction SilentlyContinue
        }
    }
}

Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
Write-Host "Agent stopped successfully."
