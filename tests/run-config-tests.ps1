# No SDK jars, reader hardware or live middleware required.
. (Join-Path $PSScriptRoot '..\scripts\common.ps1')

$repoRoot = Get-RepoRoot
$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('rfid-config-test-' + [guid]::NewGuid())
$classes = Join-Path $testRoot 'classes'
$fixture = Join-Path $testRoot 'fixture'
$emptyFixture = Join-Path $testRoot 'empty'
$previousKey = $env:MIDDLEWARE_API_KEY
$javac = Get-JavaTool -Name 'javac'
$java = Get-JavaTool -Name 'java'
New-Item -ItemType Directory -Path $classes, $fixture, $emptyFixture | Out-Null

function Test-Header {
    param([string]$ExpectedKey, [string]$Scenario, [string[]]$JavaOptions = @())
    & $java @JavaOptions -cp $classes AgentEnvHeaderTest $ExpectedKey $Scenario
    if ($LASTEXITCODE -ne 0) {
        throw "Config regression failed: $Scenario"
    }
}

try {
    $sources = @(
        'src\com\geoplan\rfid\agent\config\Env.java',
        'src\com\geoplan\rfid\agent\config\AgentConfig.java',
        'src\com\geoplan\rfid\agent\middleware\MiddlewareClient.java',
        'src\com\geoplan\rfid\agent\middleware\AppendOutcome.java',
        'src\com\geoplan\rfid\agent\util\Json.java',
        'src\com\geoplan\rfid\agent\util\Log.java',
        'tests\AgentEnvHeaderTest.java'
    ) | ForEach-Object { Join-Path $repoRoot $_ }
    & $javac -encoding UTF-8 -d $classes @sources
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }

    # Duplicate keys match the user's setup: the final saved value wins.
    $content = "# local config`nMIDDLEWARE_API_KEY=placeholder`nignored line`n MIDDLEWARE_API_KEY = `"file-test-key`" `n"
    [System.IO.File]::WriteAllText((Join-Path $fixture 'agent.env'), $content, [System.Text.UTF8Encoding]::new($true))
    Push-Location $fixture
    try {
        Remove-Item Env:MIDDLEWARE_API_KEY -ErrorAction SilentlyContinue
        Test-Header 'file-test-key' 'direct launch reads agent.env'
        $env:MIDDLEWARE_API_KEY = 'environment-test-key'
        Test-Header 'environment-test-key' 'environment overrides file'
        Test-Header 'property-test-key' 'Java property overrides environment and file' @('-DMIDDLEWARE_API_KEY=property-test-key')
    } finally {
        Pop-Location
    }
    Push-Location $emptyFixture
    try {
        Remove-Item Env:MIDDLEWARE_API_KEY -ErrorAction SilentlyContinue
        Test-Header 'empty' 'missing file leaves API key unset' @('-DTEST_NO_KEY=true')
    } finally {
        Pop-Location
    }
} finally {
    if ($null -eq $previousKey) {
        Remove-Item Env:MIDDLEWARE_API_KEY -ErrorAction SilentlyContinue
    } else {
        $env:MIDDLEWARE_API_KEY = $previousKey
    }
    $resolvedTestRoot = (Resolve-Path -LiteralPath $testRoot).Path
    $tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (-not $resolvedTestRoot.StartsWith($tempRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
        [System.IO.Path]::GetFileName($resolvedTestRoot) -notlike 'rfid-config-test-*') {
        throw 'Refusing to remove a test directory outside the temporary workspace'
    }
    Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
}
