param([switch]$Live)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
$cacheRoot = Join-Path $tempRoot 'caches\gradle\caches\modules-2\files-2.1'
$dependencyRoot = Join-Path $tempRoot 'qa\official-web\deps'
$testRoot = Join-Path $tempRoot ('qa\official-web\run-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$javaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\JDK21' }
function Find-CachedJar([string]$RelativeDirectory) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $cacheRoot $RelativeDirectory) -Recurse -File -Filter '*.jar' |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw "Missing cached dependency: $RelativeDirectory" }
    return $jar
}
function Find-WebJar([string]$Filename, [string]$RelativeDirectory) {
    $localJar = Join-Path $dependencyRoot $Filename
    if (Test-Path -LiteralPath $localJar) { return $localJar }
    return Find-CachedJar $RelativeDirectory
}
$compiler = Find-CachedJar 'org.jetbrains.kotlin\kotlin-compiler-embeddable\2.1.20'
$stdlib = Find-CachedJar 'org.jetbrains.kotlin\kotlin-stdlib\2.1.20'
$coroutines = Find-CachedJar 'org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm\1.9.0'
$annotations = Find-CachedJar 'org.jetbrains\annotations'
$reflect = Find-CachedJar 'org.jetbrains.kotlin\kotlin-reflect'
$trove = Find-CachedJar 'org.jetbrains.intellij.deps\trove4j'
$okhttp = Find-WebJar 'okhttp-4.12.0.jar' 'com.squareup.okhttp3\okhttp\4.12.0'
$okio = Find-WebJar 'okio-jvm-3.6.0.jar' 'com.squareup.okio\okio-jvm\3.6.0'
$jsoup = Find-WebJar 'jsoup-1.18.3.jar' 'org.jsoup\jsoup\1.18.3'
$json = Join-Path $tempRoot 'qa\workspace-presence\json-20240303.jar'
$android = Join-Path $tempRoot 'toolchains\android-sdk\platforms\android-35\android.jar'
$classpath = @($stdlib, $coroutines, $annotations, $json, $android, $okhttp, $okio, $jsoup) -join ';'
$compilerClasspath = @($compiler, $stdlib, $coroutines, $annotations, $reflect, $trove) -join ';'
$dataRoot = Join-Path $projectRoot 'app\src\main\java\com\dafeiyu\mobile\data'
$sources = @('BackgroundTaskModels.kt', 'BackgroundTaskEngine.kt', 'BackgroundTaskJson.kt',
    'AssistantStreamProtocol.kt', 'BalanceStore.kt', 'DeepSeekChatProtocol.kt', 'DeepSeekChatClient.kt',
    'OfficialToolContract.kt', 'OfficialToolOutputStore.kt', 'OfficialToolDispatcher.kt',
    'OfficialToolSession.kt', 'OfficialWebClient.kt') | ForEach-Object { Join-Path $dataRoot $_ }
$sources += Join-Path $PSScriptRoot 'tests\official-web\OfficialWebChecks.kt'
$outputJar = Join-Path $testRoot 'official-web-checks.jar'
$started = Get-Date
Write-Output "Official web evidence: $testRoot"
& (Join-Path $javaRoot 'bin\java.exe') -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -Werror -classpath $classpath -d $outputJar @sources 2>&1 |
    Tee-Object -FilePath (Join-Path $testRoot 'compile.log')
if ($LASTEXITCODE -ne 0) { throw 'Official web compilation failed' }
$testArgs = @((Join-Path $testRoot 'fixtures'))
if ($Live) { $testArgs += 'live' }
& (Join-Path $javaRoot 'bin\java.exe') -cp "$outputJar;$classpath" `
    com.dafeiyu.mobile.data.OfficialWebChecksKt @testArgs 2>&1 |
    Tee-Object -FilePath (Join-Path $testRoot 'results.log')
$testExitCode = $LASTEXITCODE
[ordered]@{
    script = 'tools/test-official-web.ps1'
    live = [bool]$Live
    javaHome = $javaRoot
    started = $started.ToString('o')
    finished = (Get-Date).ToString('o')
    exitCode = $testExitCode
    evidenceDirectory = $testRoot
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $testRoot 'results.json') -Encoding UTF8
if ($testExitCode -ne 0) { throw 'Official web checks failed' }
