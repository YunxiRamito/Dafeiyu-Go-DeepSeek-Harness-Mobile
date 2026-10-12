param(
    [string]$TempRoot,
    [string]$JavaRoot,
    [ValidateRange(128, 512)][int]$HeapMiB = 512
)

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
if (-not $TempRoot) { $TempRoot = Join-Path $projectRoot '../Temp_Android' }
$TempRoot = [System.IO.Path]::GetFullPath($TempRoot)
if (-not $JavaRoot) {
    $JavaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\JDK21' }
}
$java = Join-Path $JavaRoot 'bin/java.exe'
$moduleRoot = Join-Path $TempRoot 'caches/gradle/caches/modules-2/files-2.1'
function Find-CachedJar([string]$RelativeDirectory) {
    $directory = Join-Path $moduleRoot $RelativeDirectory
    $jar = Get-ChildItem -LiteralPath $directory -Recurse -File -Filter '*.jar' |
        Sort-Object FullName | Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw "Missing offline dependency: $RelativeDirectory" }
    return $jar
}
$compiler = Find-CachedJar 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.20'
$stdlib = Find-CachedJar 'org.jetbrains.kotlin/kotlin-stdlib/2.1.20'
$coroutines = Find-CachedJar 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0'
$reflect = Find-CachedJar 'org.jetbrains.kotlin/kotlin-reflect/2.1.0'
$scriptRuntime = Find-CachedJar 'org.jetbrains.kotlin/kotlin-script-runtime/2.1.20'
$annotations = Find-CachedJar 'org.jetbrains/annotations/23.0.0'
$trove = Find-CachedJar 'org.jetbrains.intellij.deps/trove4j/1.0.20200330'
$json = Join-Path $TempRoot 'qa/workspace-presence/json-20240303.jar'
foreach ($required in @($java, $json)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing offline dependency: $required" }
}
$compilerClasspath = @($compiler, $stdlib, $coroutines, $reflect, $scriptRuntime, $annotations, $trove) -join ';'
$classpath = @($stdlib, $annotations, $json) -join ';'
$sourceRoot = Join-Path $projectRoot 'app/src/main/java/com/dafeiyu/mobile'
$fixtureRoot = Join-Path $projectRoot 'tools/tests/chat-stream-ui'
$sources = @(
    (Join-Path $sourceRoot 'data/AssistantStreamProtocol.kt'),
    (Join-Path $sourceRoot 'ui/AssistantActivitySummary.kt'),
    (Join-Path $sourceRoot 'ui/ActivityReceiptDetails.kt'),
    (Join-Path $fixtureRoot 'AssistantActivitySummaryChecks.kt'),
    (Join-Path $fixtureRoot 'AssistantWebReceiptChecks.kt'),
    (Join-Path $fixtureRoot 'AssistantActivityTimelineChecks.kt'),
    (Join-Path $PSScriptRoot 'ActivityReceiptDetailsChecks.kt')
)
foreach ($source in $sources) {
    if (-not (Test-Path -LiteralPath $source)) { throw "Missing production/check source: $source" }
}
$testRoot = Join-Path $TempRoot ('qa/activity-receipt-model/run-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$outputJar = Join-Path $testRoot 'activity-receipt-model.jar'
$jvmArguments = @("-Xmx${HeapMiB}m", "-Djava.io.tmpdir=$testRoot")
Write-Output "Activity receipt model test output: $testRoot"
& $java @jvmArguments -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -Werror -classpath $classpath -d $outputJar @sources 2>&1 |
    Tee-Object -FilePath (Join-Path $testRoot 'compile.log')
if ($LASTEXITCODE -ne 0) { throw 'Activity receipt model compilation failed' }
foreach ($check in @('AssistantActivitySummaryChecks', 'AssistantWebReceiptChecks',
    'AssistantActivityTimelineChecks', 'ActivityReceiptDetailsChecks')) {
    & $java @jvmArguments -cp "$outputJar;$classpath" "com.dafeiyu.mobile.ui.${check}Kt" 2>&1 |
        Tee-Object -FilePath (Join-Path $testRoot "$check-results.log")
    if ($LASTEXITCODE -ne 0) { throw "Focused check failed: $check" }
}
