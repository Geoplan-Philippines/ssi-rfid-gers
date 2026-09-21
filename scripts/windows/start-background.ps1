# Runs the agent as a background process on Windows.
#
#   powershell -ExecutionPolicy Bypass -File scripts\windows\start-background.ps1
#
. (Join-Path $PSScriptRoot 'common.ps1')

$root = Get-RepoRoot
$classes = Join-Path $root 'build\classes'
$logDir = Join-Path $root 'logs'
$pidFile = Join-Path $logDir 'agent.pid'
$logFile = Join-Path $logDir 'agent.log'
$errLogFile = Join-Path $logDir 'agent.err.log'

if (-not (Test-Path $logDir)) {
    New-Item -ItemType Directory -Path $logDir -Force | Out-Null
}

# Check if already running via PID file
if (Test-Path $pidFile) {
    $existingPid = (Get-Content $pidFile -Raw).Trim()
    if ($existingPid) {
        $existingProc = Get-Process -Id $existingPid -ErrorAction SilentlyContinue
        if ($existingProc) {
            Write-Host "Agent is already running with PID $existingPid."
            exit 0
        } else {
            Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
        }
    }
}

# Check port 8443
$portConflict = Get-NetTCPConnection -LocalPort 8443 -State Listen -ErrorAction SilentlyContinue
if ($portConflict) {
    Write-Warning "Port 8443 is already in use by PID $($portConflict.OwningProcess)."
    Write-Warning "Please stop it before starting."
    exit 1
}

# Build if needed
if (-not (Test-Path (Join-Path $classes 'com\geoplan\rfid\agent\AgentMain.class'))) {
    Write-Host "build\classes missing. Compiling first..."
    & (Join-Path $PSScriptRoot 'build.ps1')
}

$java = Get-JavaTool -Name 'java'
$classPath = "$classes;$(Get-SdkClassPath)"

Write-Host "Starting SSI RMK reader agent in background..."

$process = Start-Process -FilePath $java `
    -ArgumentList @("-Dfile.encoding=UTF-8", "-cp", "`"$classPath`"", "com.geoplan.rfid.agent.AgentMain") `
    -WorkingDirectory $root `
    -RedirectStandardOutput $logFile `
    -RedirectStandardError $errLogFile `
    -WindowStyle Hidden `
    -PassThru

$process.Id | Set-Content -Path $pidFile -Encoding ascii

Start-Sleep -Seconds 2

if (Get-Process -Id $process.Id -ErrorAction SilentlyContinue) {
    Write-Host "Agent started successfully in background (PID: $($process.Id))."
    Write-Host "Logs: $logFile"
} else {
    Write-Error "Agent failed to start. Check $logFile and $errLogFile for details."
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
    exit 1
}
