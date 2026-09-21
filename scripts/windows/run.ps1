# Runs the agent in the foreground. Build first with scripts\windows\build.ps1.
#
#   powershell -ExecutionPolicy Bypass -File scripts\windows\run.ps1
#
# Java loads agent.env in the repo root; environment variables can override it.

. (Join-Path $PSScriptRoot 'common.ps1')

$root = Get-RepoRoot
$classes = Join-Path $root 'build\classes'

if (-not (Test-Path (Join-Path $classes 'com\geoplan\rfid\agent\AgentMain.class'))) {
    Write-Host "build\classes is missing or stale. Compiling first..."
    & (Join-Path $PSScriptRoot 'build.ps1')
}

$java = Get-JavaTool -Name 'java'
$classPath = "$classes;$(Get-SdkClassPath)"

Push-Location $root

try {
    # -Dfile.encoding keeps the vendor SDK's own console messages readable.
    & $java -Dfile.encoding=UTF-8 -cp $classPath com.geoplan.rfid.agent.AgentMain
} finally {
    Pop-Location
}
