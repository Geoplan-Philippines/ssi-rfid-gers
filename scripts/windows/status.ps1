# Checks the background agent status on Windows.
#
#   powershell -ExecutionPolicy Bypass -File scripts\windows\status.ps1
#
. (Join-Path $PSScriptRoot 'common.ps1')

$root = Get-RepoRoot
$pidFile = Join-Path $root 'logs\agent.pid'
$logFile = Join-Path $root 'logs\agent.log'

$running = $false
$agentPid = $null

if (Test-Path $pidFile) {
    $val = (Get-Content $pidFile -Raw).Trim()
    if ($val) {
        $proc = Get-Process -Id $val -ErrorAction SilentlyContinue
        if ($proc) {
            $running = $true
            $agentPid = $val
        }
    }
}

if (-not $running) {
    $portConn = Get-NetTCPConnection -LocalPort 8443 -State Listen -ErrorAction SilentlyContinue
    if ($portConn) {
        $running = $true
        $agentPid = $portConn.OwningProcess
    }
}

if ($running) {
    Write-Host "Agent is RUNNING (PID: $agentPid)"
    try {
        $handler = [System.Net.Http.HttpClientHandler]::new()
        $handler.ServerCertificateCustomValidationCallback = { $true }
        $client = [System.Net.Http.HttpClient]::new($handler)
        $client.Timeout = [TimeSpan]::FromSeconds(2)
        $response = $client.GetStringAsync("https://localhost:8443/health").GetAwaiter().GetResult()
        Write-Host "Health: $response"
    } catch {
        Write-Host "Could not query health endpoint: $($_.Exception.Message)"
    }

    if (Test-Path $logFile) {
        Write-Host "`nRecent log entries:"
        Get-Content $logFile -Tail 10
    }
    exit 0
} else {
    Write-Host "Agent is STOPPED."
    exit 1
}
