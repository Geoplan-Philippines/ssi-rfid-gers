[CmdletBinding()]
param(
    [switch]$RequireService,

    [ValidateRange(5, 300)]
    [int]$StartupTimeoutSeconds = 45
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "common.ps1")
Add-Type -AssemblyName System.Net.Http

function Read-AgentEnvironment {
    param([string]$File)

    $values = @{}
    if (-not (Test-Path $File)) {
        return $values
    }

    foreach ($line in [System.IO.File]::ReadAllLines($File)) {
        $trimmed = $line.Trim()
        if ([string]::IsNullOrWhiteSpace($trimmed) -or $trimmed.StartsWith('#')) {
            continue
        }

        $separator = $trimmed.IndexOf('=')
        if ($separator -lt 1) {
            continue
        }

        $key = $trimmed.Substring(0, $separator).Trim()
        $value = $trimmed.Substring($separator + 1).Trim()
        if ($value.Length -ge 2 -and (
            ($value.StartsWith('"') -and $value.EndsWith('"')) -or
            ($value.StartsWith("'") -and $value.EndsWith("'"))
        )) {
            $value = $value.Substring(1, $value.Length - 2).Trim()
        }
        $values[$key] = $value
    }

    return $values
}

function Get-Setting {
    param(
        [hashtable]$FileValues,
        [string]$Name,
        [string]$Default = ""
    )

    # NSSM runs as LocalSystem by default, so machine-level environment values
    # (not the interactive administrator's user values) override agent.env.
    $environmentValue = [Environment]::GetEnvironmentVariable(
        $Name,
        [System.EnvironmentVariableTarget]::Machine
    )
    if (-not [string]::IsNullOrWhiteSpace($environmentValue)) {
        return $environmentValue.Trim()
    }
    if ($FileValues.ContainsKey($Name) -and -not [string]::IsNullOrWhiteSpace($FileValues[$Name])) {
        return ([string]$FileValues[$Name]).Trim()
    }
    return $Default
}

function Invoke-Get {
    param(
        [string]$Url,
        [string]$ApiKey = "",
        [switch]$IgnoreCertificateErrors
    )

    $handler = [System.Net.Http.HttpClientHandler]::new()
    if ($IgnoreCertificateErrors) {
        $handler.ServerCertificateCustomValidationCallback = { return $true }
    }

    $client = [System.Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(10)
    try {
        $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Get, $Url)
        if (-not [string]::IsNullOrWhiteSpace($ApiKey)) {
            $request.Headers.TryAddWithoutValidation("x-api-key", $ApiKey) | Out-Null
        }
        try {
            $response = $client.SendAsync($request).GetAwaiter().GetResult()
            $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            return [pscustomobject]@{
                StatusCode = [int]$response.StatusCode
                Body = $body
            }
        } finally {
            $request.Dispose()
        }
    } finally {
        $client.Dispose()
        $handler.Dispose()
    }
}

function Get-ResponseData {
    param([string]$Body)

    if ([string]::IsNullOrWhiteSpace($Body)) {
        return $null
    }
    $payload = $Body | ConvertFrom-Json
    if ($payload.PSObject.Properties.Name -contains "data") {
        return $payload.data
    }
    return $payload
}

function Get-Snippet {
    param([string]$Body)

    if ([string]::IsNullOrWhiteSpace($Body)) {
        return "<empty body>"
    }
    $flattened = ($Body -replace '\s+', ' ').Trim()
    if ($flattened.Length -le 200) {
        return $flattened
    }
    return $flattened.Substring(0, 200) + "..."
}

function Split-SettingList {
    param([string]$Raw, [string]$Pattern = '[,;]')

    if ([string]::IsNullOrWhiteSpace($Raw)) {
        return @()
    }
    return @(
        $Raw -split $Pattern |
            ForEach-Object { $_.Trim() } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    )
}

function Get-ConfiguredReaders {
    param([hashtable]$FileValues)

    $hosts = Split-SettingList (Get-Setting -FileValues $FileValues -Name "READER_HOST")
    $ids = Split-SettingList (Get-Setting -FileValues $FileValues -Name "READER_ID")
    $ports = Split-SettingList (Get-Setting -FileValues $FileValues -Name "READER_PORT")
    $names = Split-SettingList (Get-Setting -FileValues $FileValues -Name "READER_NAME")

    $indices = [System.Collections.Generic.SortedSet[int]]::new()
    foreach ($key in $FileValues.Keys) {
        if ($key -match '^READER_(\d+)_(HOST|PORT|NAME|MODE|ANTENNAS|ID)$' -and
            -not [string]::IsNullOrWhiteSpace($FileValues[$key])) {
            [void]$indices.Add([int]$Matches[1])
        }
    }

    $unnumberedCount = [Math]::Max($hosts.Count, $ids.Count)
    for ($i = 1; $i -le $unnumberedCount; $i++) {
        [void]$indices.Add($i)
    }
    if ($indices.Count -eq 0) {
        [void]$indices.Add(1)
    }

    $readers = @()
    $pos = 0
    foreach ($idx in $indices) {
        $prefix = "READER_${idx}_"

        $rHost = Get-Setting -FileValues $FileValues -Name "${prefix}HOST"
        if ([string]::IsNullOrWhiteSpace($rHost)) {
            if ($pos -lt $hosts.Count) { $rHost = $hosts[$pos] }
            elseif ($hosts.Count -eq 1) { $rHost = $hosts[0] }
            else { $rHost = "192.168.1.100" }
        }

        $rPortText = Get-Setting -FileValues $FileValues -Name "${prefix}PORT"
        if ([string]::IsNullOrWhiteSpace($rPortText)) {
            if ($pos -lt $ports.Count) { $rPortText = $ports[$pos] }
            elseif ($ports.Count -eq 1) { $rPortText = $ports[0] }
            else { $rPortText = "9090" }
        }

        $rName = Get-Setting -FileValues $FileValues -Name "${prefix}NAME"
        if ([string]::IsNullOrWhiteSpace($rName)) {
            if ($pos -lt $names.Count) { $rName = $names[$pos] }
            else { $rName = "Device$idx" }
        }

        $rId = Get-Setting -FileValues $FileValues -Name "${prefix}ID"
        if ([string]::IsNullOrWhiteSpace($rId) -and $pos -lt $ids.Count) {
            $rId = $ids[$pos]
        }

        $readers += [pscustomobject]@{
            Index    = $idx
            Name     = $rName
            Host     = $rHost
            PortText = $rPortText
            Port     = 0
            Id       = $rId
        }
        $pos++
    }

    return $readers
}

$root = Get-RepoRoot
$agentEnvFile = Join-Path $root "agent.env"
$values = Read-AgentEnvironment -File $agentEnvFile
$failures = @()

if (-not (Test-Path $agentEnvFile)) {
    $failures += "Missing $agentEnvFile"
}

$readers = Get-ConfiguredReaders -FileValues $values
$middlewareBaseUrl = (Get-Setting -FileValues $values -Name "MIDDLEWARE_BASE_URL").TrimEnd('/')
$middlewareApiKey = Get-Setting -FileValues $values -Name "MIDDLEWARE_API_KEY"
$controlPortText = Get-Setting -FileValues $values -Name "AGENT_CONTROL_PORT" -Default "8443"

foreach ($reader in $readers) {
    $parsedPort = 0
    if (-not [int]::TryParse($reader.PortText, [ref]$parsedPort) -or $parsedPort -lt 1 -or $parsedPort -gt 65535) {
        $failures += "READER_PORT for $($reader.Name) is invalid: '$($reader.PortText)'"
    } else {
        $reader.Port = $parsedPort
    }

    $parsedReaderId = [Guid]::Empty
    if (-not [Guid]::TryParse($reader.Id, [ref]$parsedReaderId)) {
        $failures += "READER_ID for $($reader.Name) must be the deployed middleware RFID reader UUID: '$($reader.Id)'"
    }
}

$controlPort = 0
if (-not [int]::TryParse($controlPortText, [ref]$controlPort) -or $controlPort -lt 1 -or $controlPort -gt 65535) {
    $failures += "AGENT_CONTROL_PORT is invalid: '$controlPortText'"
}

$middlewareUri = $null
if ([string]::IsNullOrWhiteSpace($middlewareBaseUrl) -or
    -not [Uri]::TryCreate($middlewareBaseUrl, [UriKind]::Absolute, [ref]$middlewareUri) -or
    $middlewareUri.Scheme -notin @("http", "https")) {
    $failures += "MIDDLEWARE_BASE_URL must be an absolute HTTP(S) URL: '$middlewareBaseUrl'"
}
if ([string]::IsNullOrWhiteSpace($middlewareApiKey) -or $middlewareApiKey -eq "replace-me") {
    $failures += "MIDDLEWARE_API_KEY is missing or still uses the example value"
}

if ($failures.Count -gt 0) {
    foreach ($failure in $failures) {
        Write-Host "FAIL: $failure" -ForegroundColor Red
    }
    throw "Deployment verification failed because agent.env is incomplete."
}

Write-Host "Checking SSI RFID reader-agent deployment ($($readers.Count) reader(s))..." -ForegroundColor Cyan
Write-Host "PASS: agent.env contains the required reader and middleware settings." -ForegroundColor Green

foreach ($reader in $readers) {
    try {
        $readerTcpReachable = Test-NetConnection -ComputerName $reader.Host -Port $reader.Port -InformationLevel Quiet -WarningAction SilentlyContinue
    } catch {
        $readerTcpReachable = $false
    }
    if ($readerTcpReachable) {
        Write-Host "PASS: [$($reader.Name)] R400 TCP port is reachable at $($reader.Host):$($reader.Port)." -ForegroundColor Green
    } else {
        Write-Host "WARN: [$($reader.Name)] A second TCP probe to $($reader.Host):$($reader.Port) was not accepted; checking agent health instead." -ForegroundColor Yellow
    }
}

$healthUrl = $middlewareBaseUrl + "/api/v1/health"
try {
    $middlewareHealth = Invoke-Get -Url $healthUrl
    if ($middlewareHealth.StatusCode -ge 200 -and $middlewareHealth.StatusCode -lt 300) {
        Write-Host "PASS: Deployed middleware health endpoint is reachable." -ForegroundColor Green
    } else {
        $failures += "Middleware health returned HTTP $($middlewareHealth.StatusCode)"
        Write-Host "FAIL: Middleware health returned HTTP $($middlewareHealth.StatusCode): $(Get-Snippet $middlewareHealth.Body)" -ForegroundColor Red
    }
} catch {
    $failures += "Cannot reach deployed middleware: $($_.Exception.Message)"
    Write-Host "FAIL: Cannot reach $healthUrl`: $($_.Exception.Message)" -ForegroundColor Red
}

$service = Get-Service -Name "SSIRfidAgent" -ErrorAction SilentlyContinue
if ($RequireService) {
    if (-not $service) {
        $failures += "SSIRfidAgent Windows service is not installed"
        Write-Host "FAIL: SSIRfidAgent Windows service is not installed." -ForegroundColor Red
    } elseif ($service.Status -ne "Running") {
        $failures += "SSIRfidAgent service is $($service.Status)"
        Write-Host "FAIL: SSIRfidAgent service is $($service.Status)." -ForegroundColor Red
    } else {
        Write-Host "PASS: SSIRfidAgent Windows service is running." -ForegroundColor Green
    }
}

$localHealth = $null
$startupDeadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
do {
    try {
        $localResponse = Invoke-Get -Url "https://127.0.0.1:$controlPort/health" -IgnoreCertificateErrors
        if ($localResponse.StatusCode -eq 200) {
            $localHealth = Get-ResponseData -Body $localResponse.Body
            if ($localHealth) {
                break
            }
        }
    } catch {
        # Certificate creation and reader connection can take a few seconds.
    }
    Start-Sleep -Seconds 2
} while ((Get-Date) -lt $startupDeadline)

if (-not $localHealth) {
    $failures += "Local agent health did not answer on port $controlPort"
    Write-Host "FAIL: Local agent health did not answer at https://127.0.0.1:$controlPort/health." -ForegroundColor Red
} else {
    Write-Host "PASS: Local agent health endpoint is responding." -ForegroundColor Green
    if ($localHealth.readerConnected -eq $true) {
        Write-Host "PASS: Agent reports readerConnected=true." -ForegroundColor Green
    } else {
        $failures += "Local agent reports readerConnected=false"
        Write-Host "FAIL: Agent reports readerConnected=false." -ForegroundColor Red
    }
}

foreach ($reader in $readers) {
    $readerUrl = $middlewareBaseUrl + "/api/v1/rfid-readers/" + $reader.Id
    $cloudReader = $null
    $cloudStatus = 0
    $cloudBody = ""
    $cloudDeadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    do {
        try {
            $readerResponse = Invoke-Get -Url $readerUrl -ApiKey $middlewareApiKey
            $cloudStatus = $readerResponse.StatusCode
            $cloudBody = $readerResponse.Body
            if ($cloudStatus -ge 200 -and $cloudStatus -lt 300) {
                $cloudReader = Get-ResponseData -Body $cloudBody
                if ($cloudReader -and $cloudReader.agentOnline -eq $true -and $cloudReader.readerConnected -eq $true) {
                    break
                }
            } else {
                break
            }
        } catch {
            $cloudBody = $_.Exception.Message
            break
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $cloudDeadline)

    if ($cloudStatus -lt 200 -or $cloudStatus -ge 300) {
        $failures += "[$($reader.Name)] Middleware rejected the API key or READER_ID $($reader.Id) (HTTP $cloudStatus)"
        Write-Host "FAIL: [$($reader.Name)] Middleware reader lookup returned HTTP $cloudStatus`: $(Get-Snippet $cloudBody)" -ForegroundColor Red
    } elseif (-not $cloudReader) {
        $failures += "[$($reader.Name)] Middleware returned an invalid RFID reader response"
        Write-Host "FAIL: [$($reader.Name)] Middleware returned an invalid RFID reader response." -ForegroundColor Red
    } else {
        Write-Host "PASS: [$($reader.Name)] MIDDLEWARE_API_KEY and READER_ID ($($reader.Id)) are accepted by the deployed middleware." -ForegroundColor Green
        if ($cloudReader.status -ne "ACTIVE") {
            $failures += "[$($reader.Name)] Middleware reader status is $($cloudReader.status), not ACTIVE"
            Write-Host "FAIL: [$($reader.Name)] Middleware reader status is $($cloudReader.status), not ACTIVE." -ForegroundColor Red
        } else {
            Write-Host "PASS: [$($reader.Name)] Middleware reader is ACTIVE." -ForegroundColor Green
        }
        if ($cloudReader.agentOnline -eq $true) {
            Write-Host "PASS: [$($reader.Name)] Middleware received the agent heartbeat (agentOnline=true)." -ForegroundColor Green
        } else {
            $failures += "[$($reader.Name)] Middleware did not receive an agent heartbeat within $StartupTimeoutSeconds seconds"
            Write-Host "FAIL: [$($reader.Name)] Middleware still reports agentOnline=false." -ForegroundColor Red
        }
        if ($cloudReader.readerConnected -eq $true) {
            Write-Host "PASS: [$($reader.Name)] Middleware reports readerConnected=true." -ForegroundColor Green
        } else {
            $failures += "[$($reader.Name)] Middleware reports readerConnected=false"
            Write-Host "FAIL: [$($reader.Name)] Middleware reports readerConnected=false." -ForegroundColor Red
        }
    }
}

if ($failures.Count -gt 0) {
    Write-Host "`nDeployment verification failed:" -ForegroundColor Red
    foreach ($failure in $failures) {
        Write-Host " - $failure" -ForegroundColor Red
    }
    throw "SSI RFID deployment is not ready."
}

Write-Host "`nSUCCESS: R400 -> GERS agent -> deployed middleware connectivity is healthy." -ForegroundColor Green
