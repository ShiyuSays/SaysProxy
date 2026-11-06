$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$javac = Get-Command javac -ErrorAction SilentlyContinue
$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $javac -or -not $java) {
    throw 'JDK not found. Add javac and java to PATH.'
}

$build = Join-Path $root '.build'
$common = Join-Path $build 'common'
$server = Join-Path $build 'server'
$testClasses = Join-Path $build 'test-classes'
foreach ($dir in @($common, $server, $testClasses)) {
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
}

$commonSources = Get-ChildItem (Join-Path $root 'src\common') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
$serverSources = Get-ChildItem (Join-Path $root 'src\server') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }

& $javac.Source -encoding UTF-8 -source 8 -target 8 -d $common $commonSources
if ($LASTEXITCODE -ne 0) { throw 'common compilation failed' }
& $javac.Source -encoding UTF-8 -source 8 -target 8 -cp $common -d $server $serverSources
if ($LASTEXITCODE -ne 0) { throw 'server compilation failed' }
& $javac.Source -encoding UTF-8 -source 8 -target 8 -cp "$common;$server" -d $testClasses (Join-Path $root 'test\IntegrationTest.java')
if ($LASTEXITCODE -ne 0) { throw 'test compilation failed' }

& $java.Source '-Dfile.encoding=UTF-8' -cp "$common;$server;$testClasses" IntegrationTest
if ($LASTEXITCODE -ne 0) { throw 'integration test failed' }
