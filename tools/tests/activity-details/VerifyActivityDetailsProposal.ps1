param([string]$TempRoot, [string]$JavaRoot)

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
if (-not $TempRoot) { $TempRoot = Join-Path $projectRoot '../Temp_Android' }
$TempRoot = [System.IO.Path]::GetFullPath($TempRoot)
if (-not $JavaRoot) {
    $JavaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\JDK21' }
}
$java = Join-Path $JavaRoot 'bin/java.exe'
$cacheRoot = Join-Path $TempRoot 'caches/gradle/caches/modules-2/files-2.1'
function Find-CachedJar([string]$RelativeDirectory) {
    $directory = Join-Path $cacheRoot $RelativeDirectory
    if (-not (Test-Path -LiteralPath $directory)) { throw "Missing cached dependency: $RelativeDirectory" }
    $jar = Get-ChildItem -LiteralPath $directory -Recurse -File -Filter '*.jar' |
        Sort-Object FullName | Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw "Missing cached dependency: $RelativeDirectory" }
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
$testRoot = Join-Path $TempRoot ('qa/activity-details-proposal/run-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$outputJar = Join-Path $testRoot 'proposal-checks.jar'
$sources = @(
    (Join-Path $projectRoot 'app/src/main/java/com/dafeiyu/mobile/data/AssistantStreamProtocol.kt'),
    (Join-Path $PSScriptRoot 'ActivityDetailsProposal.kt'),
    (Join-Path $PSScriptRoot 'ActivityDetailsProposalChecks.kt')
)
Write-Output "Test-only activity detail proposal: $testRoot"
& $java -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -Werror -classpath $classpath -d $outputJar @sources 2>&1 |
    Tee-Object -FilePath (Join-Path $testRoot 'compile.log')
if ($LASTEXITCODE -ne 0) { throw 'Activity detail proposal compilation failed' }
& $java -cp "$outputJar;$classpath" com.dafeiyu.mobile.ui.proposal.ActivityDetailsProposalChecksKt 2>&1 |
    Tee-Object -FilePath (Join-Path $testRoot 'fixture-results.log')
if ($LASTEXITCODE -ne 0) { throw 'Activity detail proposal checks failed' }
