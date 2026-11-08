$ErrorActionPreference = 'Continue'

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$sdkCandidates = @()
if ($env:ANDROID_HOME) { $sdkCandidates += $env:ANDROID_HOME }
if ($env:ANDROID_SDK_ROOT) { $sdkCandidates += $env:ANDROID_SDK_ROOT }
$sdkCandidates += (Join-Path $root '.android-sdk')
$sdk = $null
foreach ($candidate in $sdkCandidates) {
    if ($candidate -and (Test-Path (Join-Path $candidate 'build-tools\35.0.0\aapt2.exe')) -and
        (Test-Path (Join-Path $candidate 'platforms\android-35\android.jar'))) {
        $sdk = $candidate
        break
    }
}
if (-not $sdk) {
    throw 'Android SDK not found. Install platforms;android-35 and build-tools;35.0.0, then set ANDROID_HOME.'
}

$bt = Join-Path $sdk 'build-tools\35.0.0'
$androidJar = Join-Path $sdk 'platforms\android-35\android.jar'
$javac = Get-Command javac -ErrorAction SilentlyContinue
if (-not $javac) { throw 'javac not found. JDK 8 is recommended for Android compilation.' }

$jdk17 = $null
$jdkCandidates = @($env:JAVA_HOME, 'C:\Program Files\Java\jdk-17', 'C:\Program Files\Java\jdk-21',
    'C:\Program Files\Eclipse Adoptium\jdk-17')
foreach ($candidate in $jdkCandidates) {
    if ($candidate -and (Test-Path (Join-Path $candidate 'bin\java.exe'))) {
        $jdk17 = $candidate
        break
    }
}
if (-not $jdk17) {
    throw 'JDK 17 not found. Set JAVA_HOME to a JDK 17 installation (needed by d8/apksigner).'
}

$build = Join-Path $root '.build\android'
$resZip = Join-Path $build 'res.zip'
$appUnsigned = Join-Path $build 'app-unsigned.apk'
$appWithDex = Join-Path $build 'app-with-dex.apk'
$appAligned = Join-Path $build 'app-aligned.apk'
$gen = Join-Path $build 'gen'
$classes = Join-Path $build 'classes'
$dex = Join-Path $build 'dex'
$dist = Join-Path $root 'dist\SaysProxyV3-Android'
$keystore = Join-Path $root 'android\keystore\saysproxy-debug.keystore'

Remove-Item -Recurse -Force $build -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $build, $gen, $classes, $dex, $dist | Out-Null

Write-Host 'Compiling Android resources...'
& (Join-Path $bt 'aapt2.exe') compile --dir (Join-Path $root 'android\res') -o $resZip
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }
& (Join-Path $bt 'aapt2.exe') link -o $appUnsigned -I $androidJar `
    --manifest (Join-Path $root 'android\AndroidManifest.xml') $resZip `
    --java $gen --min-sdk-version 26 --target-sdk-version 33 `
    --version-code 7 --version-name 3.2.1
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Write-Host 'Compiling Java...'
$sources = @()
$sources += Get-ChildItem (Join-Path $root 'src\common') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
$sources += Get-ChildItem (Join-Path $root 'android\java') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
$sources += Get-ChildItem $gen -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
& $javac.Source -encoding UTF-8 -source 8 -target 8 -bootclasspath $androidJar -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed; use JDK 8 for Android compilation' }

Write-Host 'Running d8...'
$oldPath = $env:Path
$oldJavaHome = $env:JAVA_HOME
$env:JAVA_HOME = $jdk17
$env:Path = (Join-Path $jdk17 'bin') + ';' + $oldPath
$classFiles = Get-ChildItem $classes -Recurse -Filter '*.class' | ForEach-Object { $_.FullName }
& (Join-Path $bt 'd8.bat') --lib $androidJar --min-api 26 --output $dex $classFiles
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Write-Host 'Packaging APK...'
Copy-Item $appUnsigned $appWithDex -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::Open($appWithDex, 'Update')
$oldEntry = $archive.GetEntry('classes.dex')
if ($oldEntry) { $oldEntry.Delete() }
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,
    (Join-Path $dex 'classes.dex'), 'classes.dex',
    [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
$archive.Dispose()

& (Join-Path $bt 'zipalign.exe') -f -p 4 $appWithDex $appAligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

if (-not (Test-Path $keystore)) {
    Write-Host 'Generating debug signing key...'
    New-Item -ItemType Directory -Force -Path (Split-Path $keystore -Parent) | Out-Null
    $keytool = Join-Path $jdk17 'bin\keytool.exe'
    & $keytool -genkeypair -keystore $keystore -storepass saysproxyv3 -keypass saysproxyv3 `
        -alias saysproxy -dname 'CN=SaysProxyV3,O=SaysProxyV3,C=CN' -keyalg RSA `
        -keysize 2048 -validity 10000 -noprompt 1>$null 2>$null
    if (-not (Test-Path $keystore)) { throw 'keytool failed' }
}

$apk = Join-Path $dist 'SaysProxyV3.apk'
if (Test-Path $apk) { Remove-Item $apk -Force }
& (Join-Path $bt 'apksigner.bat') sign --ks $keystore --ks-key-alias saysproxy `
    --ks-pass pass:saysproxyv3 --key-pass pass:saysproxyv3 --out $apk $appAligned
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }
& (Join-Path $bt 'apksigner.bat') verify --verbose $apk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }

Copy-Item (Join-Path $root 'README.md') $dist -Force
Copy-Item (Join-Path $root 'LICENSE') $dist -Force
$env:Path = $oldPath
$env:JAVA_HOME = $oldJavaHome

Write-Host ''
Write-Host "APK: $apk"
Get-Item $apk | Select-Object FullName, Length
exit 0
