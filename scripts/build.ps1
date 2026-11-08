$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$javacCmd = Get-Command javac -ErrorAction SilentlyContinue
if (-not $javacCmd) {
    throw 'javac not found. Install JDK 8 or newer and add it to PATH.'
}
$jarCmd = Get-Command jar -ErrorAction SilentlyContinue
if (-not $jarCmd) {
    throw 'jar not found. Use a JDK, not a JRE.'
}

$build = Join-Path $root '.build'
$commonClasses = Join-Path $build 'common'
$serverClasses = Join-Path $build 'server'
$desktopClasses = Join-Path $build 'desktop'
$dist = Join-Path $root 'dist'
$serverDist = Join-Path $dist 'SaysProxyV3-Server'
$clientDist = Join-Path $dist 'SaysProxyV3-Client'

foreach ($dir in @($commonClasses, $serverClasses, $desktopClasses, $serverDist, $clientDist)) {
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
}
Remove-Item -Recurse -Force $commonClasses, $serverClasses, $desktopClasses -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $commonClasses, $serverClasses, $desktopClasses | Out-Null

$commonSources = Get-ChildItem (Join-Path $root 'src\common') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
$serverSources = Get-ChildItem (Join-Path $root 'src\server') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
$desktopSources = Get-ChildItem (Join-Path $root 'src\desktop') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }

& $javacCmd.Source -encoding UTF-8 -source 8 -target 8 -d $commonClasses $commonSources
if ($LASTEXITCODE -ne 0) { throw 'common compilation failed' }
& $javacCmd.Source -encoding UTF-8 -source 8 -target 8 -cp $commonClasses -d $serverClasses $serverSources
if ($LASTEXITCODE -ne 0) { throw 'server compilation failed' }
& $javacCmd.Source -encoding UTF-8 -source 8 -target 8 -cp $commonClasses -d $desktopClasses $desktopSources
if ($LASTEXITCODE -ne 0) { throw 'desktop compilation failed' }

$serverManifest = Join-Path $build 'server-manifest.txt'
$clientManifest = Join-Path $build 'client-manifest.txt'
Set-Content -Path $serverManifest -Encoding ASCII -Value @(
    'Manifest-Version: 1.0',
    'Main-Class: com.saysproxy.v3.server.Main',
    'Implementation-Title: SaysProxyV3 Server',
    'Implementation-Version: 3.2'
)
Set-Content -Path $clientManifest -Encoding ASCII -Value @(
    'Manifest-Version: 1.0',
    'Main-Class: com.saysproxy.v3.desktop.SaysProxyV3Client',
    'Implementation-Title: SaysProxyV3 Desktop Client',
    'Implementation-Version: 3.2'
)

$serverJar = Join-Path $serverDist 'SaysProxyV3-Server.jar'
$clientJar = Join-Path $clientDist 'SaysProxyV3-Client.jar'
if (Test-Path $serverJar) { Remove-Item $serverJar -Force }
if (Test-Path $clientJar) { Remove-Item $clientJar -Force }

Push-Location $commonClasses
& $jarCmd.Source cfm $serverJar $serverManifest .
Pop-Location
Push-Location $serverClasses
& $jarCmd.Source uf $serverJar .
Pop-Location
Push-Location $commonClasses
& $jarCmd.Source cfm $clientJar $clientManifest .
Pop-Location
Push-Location $desktopClasses
& $jarCmd.Source uf $clientJar .
Pop-Location

Copy-Item (Join-Path $root 'packaging\start-server.bat') $serverDist -Force
Copy-Item (Join-Path $root 'packaging\start-client.bat') $clientDist -Force
Copy-Item (Join-Path $root 'README.md') $serverDist -Force
Copy-Item (Join-Path $root 'README.md') $clientDist -Force
Copy-Item (Join-Path $root 'LICENSE') $serverDist -Force
Copy-Item (Join-Path $root 'LICENSE') $clientDist -Force

Write-Host ''
Write-Host 'Build complete:'
Get-ChildItem $serverDist, $clientDist | Select-Object FullName, Length
Write-Host ''
Write-Host 'Server: dist\SaysProxyV3-Server\SaysProxyV3-Server.jar'
Write-Host 'Client: dist\SaysProxyV3-Client\SaysProxyV3-Client.jar'
