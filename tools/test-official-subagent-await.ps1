$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
$cacheRoot = Join-Path $tempRoot 'caches\gradle\caches\modules-2\files-2.1'
$testRoot = Join-Path $tempRoot ('qa\official-subagent-await\run-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$javaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\JDK21' }
function Find-CachedJar([string]$RelativeDirectory) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $cacheRoot $RelativeDirectory) -Recurse -File -Filter '*.jar' |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw "Missing cached dependency: $RelativeDirectory" }
    return $jar
}
$compiler = Find-CachedJar 'org.jetbrains.kotlin\kotlin-compiler-embeddable\2.1.20'
$stdlib = Find-CachedJar 'org.jetbrains.kotlin\kotlin-stdlib\2.1.20'
$coroutines = Find-CachedJar 'org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm\1.9.0'
$annotations = Find-CachedJar 'org.jetbrains\annotations'
$reflect = Find-CachedJar 'org.jetbrains.kotlin\kotlin-reflect'
$trove = Find-CachedJar 'org.jetbrains.intellij.deps\trove4j'
$jsonJar = Join-Path $tempRoot 'qa\workspace-presence\json-20240303.jar'
$androidJar = Join-Path $tempRoot 'toolchains\android-sdk\platforms\android-35\android.jar'
foreach ($dependency in @($jsonJar, $androidJar, (Join-Path $javaRoot 'bin\java.exe'))) {
    if (-not (Test-Path -LiteralPath $dependency)) { throw "Missing local dependency: $dependency" }
}
$compileClasspath = @($stdlib, $coroutines, $annotations, $jsonJar, $androidJar) -join ';'
$compilerClasspath = @($compiler, $stdlib, $coroutines, $annotations, $reflect, $trove) -join ';'
$dataRoot = Join-Path $projectRoot 'app\src\main\java\com\dafeiyu\mobile\data'
$sources = @('AssistantStreamProtocol.kt', 'BalanceStore.kt', 'DeepSeekChatProtocol.kt', 'DeepSeekChatClient.kt',
    'DeepSeekThinkingPolicy.kt', 'DeepSeekModelCatalog.kt',
    'BackgroundTaskModels.kt', 'BackgroundTaskEngine.kt', 'BackgroundTaskJson.kt',
    'OfficialToolSession.kt', 'OfficialToolDispatcher.kt', 'OfficialToolContract.kt', 'OfficialToolOutputStore.kt') |
    ForEach-Object { Join-Path $dataRoot $_ }
$sources += Join-Path $PSScriptRoot 'tests\official-subagent-await\OfficialSubagentAwaitChecks.kt'
$sources += Join-Path $PSScriptRoot 'tests\OfficialToolSessionChecks.kt'
$outputJar = Join-Path $testRoot 'official-subagent-await-checks.jar'
$started = Get-Date
Write-Output "Official subagent await evidence: $testRoot"
& (Join-Path $javaRoot 'bin\java.exe') -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -Werror -classpath $compileClasspath -d $outputJar @sources 2>&1 |
    Tee-Object -FilePath (Join-Path $testRoot 'compile.log')
if ($LASTEXITCODE -ne 0) { throw 'Official subagent await compilation failed' }
Write-Output 'PASS production client/session/dispatcher compilation (local dependencies; no Gradle)'
& (Join-Path $javaRoot 'bin\java.exe') -cp "$outputJar;$compileClasspath" `
    com.dafeiyu.mobile.data.OfficialSubagentAwaitChecksKt (Join-Path $testRoot 'focused-fixtures') 2>&1 |
    Tee-Object -FilePath (Join-Path $testRoot 'focused-results.log')
$focusedExitCode = $LASTEXITCODE
& (Join-Path $javaRoot 'bin\java.exe') -cp "$outputJar;$compileClasspath" `
    com.dafeiyu.mobile.data.OfficialToolSessionChecksKt (Join-Path $testRoot 'session-fixtures') 2>&1 |
    Tee-Object -FilePath (Join-Path $testRoot 'session-results.log')
$sessionExitCode = $LASTEXITCODE
[ordered]@{
    script = 'tools/test-official-subagent-await.ps1'
    javaHome = $javaRoot
    started = $started.ToString('o')
    finished = (Get-Date).ToString('o')
    focusedExitCode = $focusedExitCode
    sessionExitCode = $sessionExitCode
    evidenceDirectory = $testRoot
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $testRoot 'results.json') -Encoding UTF8
if ($focusedExitCode -ne 0 -or $sessionExitCode -ne 0) {
    throw "Official subagent await checks failed: focused=$focusedExitCode; session=$sessionExitCode"
}
