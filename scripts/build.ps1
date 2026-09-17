# Compiles the agent into build\classes.
#
#   powershell -ExecutionPolicy Bypass -File scripts\build.ps1

. (Join-Path $PSScriptRoot 'common.ps1')

$root = Get-RepoRoot
$javac = Get-JavaTool -Name 'javac'
$classPath = Get-SdkClassPath
$outputDir = Join-Path $root 'build\classes'

if (Test-Path $outputDir) {
    Remove-Item -Recurse -Force $outputDir
}

New-Item -ItemType Directory -Force -Path $outputDir | Out-Null

$sources = Get-ChildItem -Path (Join-Path $root 'src') -Filter '*.java' -Recurse | ForEach-Object { $_.FullName }
$sourceList = Join-Path $env:TEMP 'rfid-agent-sources.txt'

# WriteAllLines, not Set-Content: javac chokes on the BOM Windows PowerShell adds.
[System.IO.File]::WriteAllLines($sourceList, [string[]]$sources)

Write-Host "javac      : $javac"
Write-Host "SDK jars   : $classPath"
Write-Host "Sources    : $($sources.Count) files"

& $javac -encoding UTF-8 -d $outputDir -cp $classPath "@$sourceList"

if ($LASTEXITCODE -ne 0) {
    Remove-Item $sourceList -ErrorAction SilentlyContinue
    throw "Compilation failed"
}

Remove-Item $sourceList -ErrorAction SilentlyContinue

Write-Host "Built $outputDir"
