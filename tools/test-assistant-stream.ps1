$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
$cacheRoot = Join-Path $tempRoot 'caches\gradle\caches\modules-2\files-2.1'
$testCacheRoot = Join-Path $tempRoot 'qa\assistant-stream'
$testRoot = Join-Path $testCacheRoot ('run-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$javaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\JDK21' }
$jsonJar = Join-Path $tempRoot 'qa\workspace-presence\json-20240303.jar'
if (-not (Test-Path -LiteralPath $jsonJar)) {
    $jsonJar = Join-Path $testCacheRoot 'json-20240303.jar'
    if (-not (Test-Path -LiteralPath $jsonJar)) {
        throw 'Missing cached JSON dependency; assistant stream harness downloads nothing'
    }
}
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
$compileClasspath = @($stdlib, $annotations, $jsonJar) -join ';'
$compilerClasspath = @($compiler, $stdlib, $coroutines, $annotations, $reflect, $trove) -join ';'
$outputJar = Join-Path $testRoot 'assistant-stream-checks.jar'
$dataRoot = Join-Path $projectRoot 'app\src\main\java\com\dafeiyu\mobile\data'
$sources = @('AssistantStreamProtocol.kt', 'AssistantStreamAdapters.kt', 'AssistantStreamSnapshotJson.kt', 'ChatRunMetrics.kt', 'WorkspaceStore.kt', 'ChatMessage.kt') |
    ForEach-Object { Join-Path $dataRoot $_ }
$sources += @('AssistantStreamChecks.kt', 'StreamTestContext.kt', 'StreamTestAtomicFile.kt') |
    ForEach-Object { Join-Path $PSScriptRoot "tests\$_" }
Write-Output "Assistant stream QA output: $testRoot"
& (Join-Path $javaRoot 'bin\java.exe') -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -Werror -classpath $compileClasspath -d $outputJar @sources 2>&1 |
    Tee-Object -FilePath (Join-Path $testRoot 'compile.log')
if ($LASTEXITCODE -ne 0) { throw 'Assistant stream checks compilation failed' }
& (Join-Path $javaRoot 'bin\java.exe') -cp "$outputJar;$compileClasspath" com.dafeiyu.mobile.data.AssistantStreamChecksKt |
    Tee-Object -FilePath (Join-Path $testRoot 'fixture-results.log')
if ($LASTEXITCODE -ne 0) { throw 'Assistant stream checks failed' }
