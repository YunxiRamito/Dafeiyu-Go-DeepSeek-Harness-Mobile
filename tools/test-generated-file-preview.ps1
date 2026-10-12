param(
    [string]$TempRoot,
    [string]$JavaRoot
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $TempRoot) { $TempRoot = Join-Path $projectRoot '..\Temp_Android' }
$TempRoot = [System.IO.Path]::GetFullPath($TempRoot)
if (-not $JavaRoot) {
    $JavaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\JDK21' }
}
$java = Join-Path $JavaRoot 'bin\java.exe'
if (-not (Test-Path -LiteralPath $java)) { throw "Missing Java runtime: $java" }
$moduleRoot = Join-Path $TempRoot 'caches\gradle\caches\modules-2\files-2.1'
$transformRoot = Join-Path $TempRoot 'caches\gradle\caches\8.11.1\transforms'
$androidJar = Join-Path $TempRoot 'toolchains\android-sdk\platforms\android-35\android.jar'
$jsonJar = Join-Path $TempRoot 'qa\workspace-presence\json-20240303.jar'
foreach ($required in @($transformRoot, $androidJar, $jsonJar)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing offline dependency: $required" }
}
function Find-CachedJar([string]$RelativeDirectory) {
    $directory = Join-Path $moduleRoot $RelativeDirectory
    if (-not (Test-Path -LiteralPath $directory)) { throw "Missing cached dependency: $RelativeDirectory" }
    $jar = Get-ChildItem -LiteralPath $directory -Recurse -File -Filter '*.jar' |
        Sort-Object FullName | Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw "Missing cached dependency: $RelativeDirectory" }
    return $jar
}
$compiler = Find-CachedJar 'org.jetbrains.kotlin\kotlin-compiler-embeddable\2.1.20'
$stdlib = Find-CachedJar 'org.jetbrains.kotlin\kotlin-stdlib\2.1.20'
$coroutines = Find-CachedJar 'org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm\1.9.0'
$reflect = Find-CachedJar 'org.jetbrains.kotlin\kotlin-reflect\2.1.0'
$scriptRuntime = Find-CachedJar 'org.jetbrains.kotlin\kotlin-script-runtime\2.1.20'
$annotations = Find-CachedJar 'org.jetbrains\annotations\23.0.0'
$trove = Find-CachedJar 'org.jetbrains.intellij.deps\trove4j\1.0.20200330'
$composePlugin = Find-CachedJar 'org.jetbrains.kotlin\kotlin-compose-compiler-plugin-embeddable\2.1.20'
$collection = Find-CachedJar 'androidx.collection\collection-jvm\1.5.0'
$compilerClasspath = @($compiler, $stdlib, $coroutines, $reflect, $scriptRuntime, $annotations, $trove) -join ';'
$apiJars = @(Get-ChildItem -LiteralPath $transformRoot -Recurse -File -Filter '*api.jar' |
    Sort-Object FullName | Select-Object -ExpandProperty FullName)
if (-not ($apiJars | Where-Object { [System.IO.Path]::GetFileName($_) -like 'material3*' })) {
    throw 'Missing cached Material3 API; this script downloads nothing'
}
$classpath = (@($stdlib, $coroutines, $annotations, $collection, $androidJar, $jsonJar) + $apiJars) -join ';'
$sourceRoot = Join-Path $projectRoot 'app\src\main\java\com\dafeiyu\mobile'
$fixtureRoot = Join-Path $PSScriptRoot 'tests\generated-file-preview'
$sources = @(
    (Join-Path $sourceRoot 'ui\GeneratedFiles.kt'),
    (Join-Path $sourceRoot 'ui\GeneratedFilePreview.kt'),
    (Join-Path $sourceRoot 'ui\components\GlassMotion.kt'),
    (Join-Path $sourceRoot 'data\OfficialToolOutputStore.kt'),
    (Join-Path $sourceRoot 'data\OfficialToolContract.kt'),
    (Join-Path $sourceRoot 'data\BackgroundTaskModels.kt'),
    (Join-Path $fixtureRoot 'GeneratedFilePreviewGlassFixture.kt'),
    (Join-Path $fixtureRoot 'GeneratedFilePreviewRepositoryFixture.kt'),
    (Join-Path $fixtureRoot 'GeneratedFilePreviewChecks.kt')
)
foreach ($source in $sources) {
    if (-not (Test-Path -LiteralPath $source)) { throw "Missing test source: $source" }
}
$testRoot = Join-Path $TempRoot ('qa\generated-file-preview\run-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$outputJar = Join-Path $testRoot 'generated-file-preview-checks.jar'
Write-Output "Generated file preview output: $testRoot"

# Real UI/helper/store/contract compile. Only external repository/glass signatures are fixtures.
& $java -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -Werror "-Xplugin=$composePlugin" `
    -classpath $classpath -d $outputJar @sources 2>&1 |
    Tee-Object -FilePath (Join-Path $testRoot 'compile.log')
if ($LASTEXITCODE -ne 0) { throw 'GeneratedFiles Compose compilation failed' }
Write-Output 'PASS GeneratedFiles Compose compilation (external repository/glass signatures only are fixtures)'

# Execute pure safe-read checks, with real managed store and org.json. No UI or network starts.
$runtimeClasspath = @($outputJar, $stdlib, $coroutines, $annotations, $jsonJar) -join ';'
& $java -cp $runtimeClasspath com.dafeiyu.mobile.ui.GeneratedFilePreviewChecksKt `
    (Join-Path $testRoot 'fixtures') |
    Tee-Object -FilePath (Join-Path $testRoot 'fixture-results.log')
if ($LASTEXITCODE -ne 0) { throw 'Generated file preview checks failed' }
