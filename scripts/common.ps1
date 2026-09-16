# Shared helpers for build.ps1 and run.ps1.
# Dot-source this file, do not run it directly.

$ErrorActionPreference = 'Stop'

function Get-RepoRoot {
    return (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
}

# Folder holding UhfRfidAPI.jar and RXTXcomm.jar.
# Order: SDK_LIB_DIR, then <repo>\libs, then the vendor default install path.
function Get-SdkLibDir {
    $candidates = @()

    if ($env:SDK_LIB_DIR) {
        $candidates += $env:SDK_LIB_DIR
    }

    $candidates += (Join-Path (Get-RepoRoot) 'libs')
    $candidates += (Join-Path $env:USERPROFILE 'Documents\Fixed RFID SDK\JAVA\JAVA API\libs')

    foreach ($candidate in $candidates) {
        if (Test-Path (Join-Path $candidate 'UhfRfidAPI.jar')) {
            return (Resolve-Path $candidate).Path
        }
    }

    throw "UhfRfidAPI.jar not found. Set SDK_LIB_DIR to the folder holding UhfRfidAPI.jar and RXTXcomm.jar. Checked: $($candidates -join '; ')"
}

function Get-SdkClassPath {
    $libs = Get-SdkLibDir

    return @(
        (Join-Path $libs 'UhfRfidAPI.jar'),
        (Join-Path $libs 'RXTXcomm.jar')
    ) -join ';'
}

# The agent needs Java 17 or newer. JAVA_HOME wins, then PATH.
function Get-JavaTool {
    param([string]$Name)

    if ($env:JAVA_HOME) {
        $fromHome = Join-Path $env:JAVA_HOME "bin\$Name.exe"

        if (Test-Path $fromHome) {
            return $fromHome
        }
    }

    $onPath = Get-Command $Name -ErrorAction SilentlyContinue

    if ($onPath) {
        return $onPath.Source
    }

    throw "$Name not found. Install a JDK 17+ and set JAVA_HOME."
}

# Loads KEY=VALUE lines from <repo>\agent.env into the process environment.
# Keep secrets there, it is gitignored.
function Import-AgentEnv {
    $envFile = Join-Path (Get-RepoRoot) 'agent.env'

    if (-not (Test-Path $envFile)) {
        return
    }

    Write-Host "Loading environment from $envFile"

    foreach ($line in Get-Content $envFile) {
        $trimmed = $line.Trim()

        if ($trimmed -eq '' -or $trimmed.StartsWith('#')) {
            continue
        }

        $separator = $trimmed.IndexOf('=')

        if ($separator -lt 1) {
            continue
        }

        $key = $trimmed.Substring(0, $separator).Trim()
        $value = $trimmed.Substring($separator + 1).Trim().Trim('"')

        Set-Item -Path "env:$key" -Value $value
    }
}
