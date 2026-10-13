param([ValidateRange(256, 1024)][int]$MaxHeapMiB = 512)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
$cacheRoot = Join-Path $tempRoot 'caches\gradle\caches\modules-2\files-2.1'
$testRoot = Join-Path $tempRoot ('qa\workspace-storage\run-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$env:TEMP = $testRoot; $env:TMP = $testRoot
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
$json = Join-Path $tempRoot 'qa\workspace-presence\json-20240303.jar'
if (-not (Test-Path -LiteralPath $json)) { throw 'Missing offline org.json runtime' }
$projectClasses = Join-Path $tempRoot 'build\app\intermediates\compile_app_classes_jar\debug\bundleDebugClassesToCompileJar\classes.jar'
$android = Join-Path $tempRoot 'toolchains\android-sdk\platforms\android-35\android.jar'
if (-not (Test-Path -LiteralPath $projectClasses)) { throw 'Build the app first; storage QA needs its real shared dependency classes and never runs Gradle itself' }
if (-not (Test-Path -LiteralPath $android)) { throw 'Missing installed Android35 compile API' }

$compilerClasspath = @($compiler, $stdlib, $coroutines, $annotations, $reflect, $trove) -join ';'
$classpath = @($projectClasses, $stdlib, $coroutines, $annotations, $json, $android,
    (Find-CachedJar 'com.squareup.okhttp3\okhttp\4.12.0'),
    (Find-CachedJar 'com.squareup.okio\okio-jvm\3.6.0')) -join ';'
$dataRoot = Join-Path $projectRoot 'app\src\main\java\com\dafeiyu\mobile\data'
$productionSources = @(
    'AssistantStreamProtocol.kt',
    'AssistantStreamSnapshotJson.kt',
    'WorkspaceStore.kt',
    'ChatMessage.kt',
    'LocalDshHistoryController.kt',
    'DshSharedPersistenceBridge.kt'
) | ForEach-Object { Join-Path $dataRoot $_ }
$fixtureSources = @(
    'WorkspaceStorageChecks.kt',
    'WorkspaceStorageTestContext.kt',
    'WorkspaceStorageTestAtomicFile.kt'
) | ForEach-Object { Join-Path $PSScriptRoot "tests\workspace-storage\$_" }
$fixtureSources += Join-Path $PSScriptRoot 'tests\local-dsh-history\LocalHistoryDeletionTestOs.kt'

$java = Join-Path $javaRoot 'bin\java.exe'
$productionJar = Join-Path $testRoot 'workspace-storage-production.jar'
$outputJar = Join-Path $testRoot 'workspace-storage-checks.jar'
$report = [ordered]@{
    passed = $false
    productionCompile = $false
    checksCompile = $false
    behaviorPassed = $false
    boundary = 'Current production storage/controller/bridge sources and real Android35 compile API; behavior uses JVM Context/AtomicFile/Os fixtures. No Android runtime or DSH process is started.'
    projectClassesSha256 = (Get-FileHash -LiteralPath $projectClasses -Algorithm SHA256).Hash.ToLowerInvariant()
    sourceSha256 = [ordered]@{}
    paidModelRequests = 0
}
foreach ($source in $productionSources) {
    $report.sourceSha256[[IO.Path]::GetFileName($source)] = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
}
function Save-Report { $report | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $testRoot 'result.json') -Encoding utf8 }
Write-Output "Workspace storage QA output: $testRoot"
Save-Report
try {
    & $java '-Xmx256m' "-Djava.io.tmpdir=$testRoot" -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
        -Werror -no-stdlib -no-reflect -jvm-target 17 "-Xfriend-paths=$projectClasses" -classpath $classpath `
        -d $productionJar @productionSources *> (Join-Path $testRoot 'compile-production.log')
    Get-Content -LiteralPath (Join-Path $testRoot 'compile-production.log')
    if ($LASTEXITCODE -ne 0) { throw 'Current production workspace storage dependencies compilation failed' }
    $report.productionCompile = $true; Save-Report
    Write-Output 'PASS current production Store/Controller/Bridge and stream source Werror compile against Android35'

    & $java '-Xmx256m' "-Djava.io.tmpdir=$testRoot" -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
        -Werror -no-stdlib -no-reflect -jvm-target 17 "-Xfriend-paths=$productionJar,$projectClasses" `
        -classpath "$productionJar;$classpath" -d $outputJar @fixtureSources *> (Join-Path $testRoot 'compile-checks.log')
    Get-Content -LiteralPath (Join-Path $testRoot 'compile-checks.log')
    if ($LASTEXITCODE -ne 0) { throw 'Workspace storage checks compilation failed' }
    $report.checksCompile = $true; Save-Report

    & $java "-Xmx${MaxHeapMiB}m" "-Djava.io.tmpdir=$testRoot" -cp "$outputJar;$productionJar;$classpath" `
        com.dafeiyu.mobile.data.WorkspaceStorageChecksKt (Join-Path $testRoot 'fixtures') *> (Join-Path $testRoot 'results.log')
    Get-Content -LiteralPath (Join-Path $testRoot 'results.log')
    if ($LASTEXITCODE -ne 0) { throw 'Workspace storage regression checks failed' }
    $report.behaviorPassed = $true; $report.passed = $true
} finally { Save-Report }

