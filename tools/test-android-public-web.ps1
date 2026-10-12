param([switch]$UseHostProxy)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
$testRoot = Join-Path $tempRoot ('qa\android-public-web\run-' + [Guid]::NewGuid().ToString('N'))
$classes = Join-Path $testRoot 'classes'
$dex = Join-Path $testRoot 'dex'
New-Item -ItemType Directory -Force -Path $classes, $dex | Out-Null
$sdk = Join-Path $tempRoot 'toolchains\android-sdk'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$d8 = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Filter d8.bat -Recurse -File |
    Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
$javaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\JDK21' }
$source = Join-Path $PSScriptRoot 'tests\official-web\AndroidPublicNetworkProbe.java'
if (-not $d8) { throw 'Missing cached Android d8; no Gradle or downloads allowed' }
& (Join-Path $javaRoot 'bin\javac.exe') --release 17 -d $classes $source
if ($LASTEXITCODE -ne 0) { throw 'Probe javac failed' }
$compiled = Join-Path $classes 'com\dafeiyu\mobile\qa\AndroidPublicNetworkProbe.class'
& $d8 --min-api 26 --output $dex $compiled
if ($LASTEXITCODE -ne 0) { throw 'Probe dex compilation failed' }
$deviceFile = '/data/local/tmp/dafeiyu-public-web-' + [Guid]::NewGuid().ToString('N') + '.dex'
& $adb -s emulator-5554 push (Join-Path $dex 'classes.dex') $deviceFile
if ($LASTEXITCODE -ne 0) { throw 'Probe push failed' }
$probeOption = if ($UseHostProxy) { ' --host-proxy' } else { '' }
Write-Output "Android probe output: $testRoot"
& $adb -s emulator-5554 shell "CLASSPATH=$deviceFile app_process /system/bin com.dafeiyu.mobile.qa.AndroidPublicNetworkProbe$probeOption" 2>&1 |
    Tee-Object -FilePath (Join-Path $testRoot 'network-results.log')
if ($LASTEXITCODE -ne 0) { throw 'Android shell probe failed' }
