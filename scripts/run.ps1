# Runs the agent. Build first with scripts\build.ps1.
#
#   powershell -ExecutionPolicy Bypass -File scripts\run.ps1
#
# Configuration comes from agent.env in the repo root, or from the environment.

. (Join-Path $PSScriptRoot 'common.ps1')

$root = Get-RepoRoot
$classes = Join-Path $root 'build\classes'

if (-not (Test-Path (Join-Path $classes 'com\geoplan\rfid\agent\AgentMain.class'))) {
    throw "build\classes is missing or stale. Run scripts\build.ps1 first."
}

Import-AgentEnv

$java = Get-JavaTool -Name 'java'
$classPath = "$classes;$(Get-SdkClassPath)"

Push-Location $root

try {
    # -Dfile.encoding keeps the vendor SDK's own console messages readable.
    & $java -Dfile.encoding=UTF-8 -cp $classPath com.geoplan.rfid.agent.AgentMain
} finally {
    Pop-Location
}
