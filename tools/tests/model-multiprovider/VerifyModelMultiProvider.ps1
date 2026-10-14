param([ValidateRange(256, 768)][int]$MaxHeapMiB = 512)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '../Temp_Android'))
$gradleCache = Join-Path $tempRoot 'caches/gradle/caches'
$cacheRoot = Join-Path $gradleCache 'modules-2/files-2.1'
$testRoot = Join-Path $tempRoot ('qa/model-multiprovider/run-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$javaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:/Program Files/Java/JDK21' }
function Find-CachedJar([string]$RelativeDirectory) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $cacheRoot $RelativeDirectory) -Recurse -File -Filter '*.jar' |
        Sort-Object FullName | Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw "Missing offline dependency: $RelativeDirectory" }
    return $jar
}
$stdlib = Find-CachedJar 'org.jetbrains.kotlin/kotlin-stdlib/2.1.20'
$annotations = Find-CachedJar 'org.jetbrains/annotations'
$coroutines = Find-CachedJar 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0'
$json = Join-Path $tempRoot 'qa/workspace-presence/json-20240303.jar'
$compiled = Join-Path $tempRoot 'build/app/tmp/kotlin-classes/debug'
if (-not (Test-Path -LiteralPath (Join-Path $compiled 'com/dafeiyu/mobile/data/OfficialMultiProviderSession.class'))) {
    throw 'Focused production Kotlin output is missing; run :app:compileDebugKotlin first.'
}
$compilerClasspath = @(
    (Find-CachedJar 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.20'),
    $stdlib, $annotations, $coroutines,
    (Find-CachedJar 'org.jetbrains.kotlin/kotlin-reflect'),
    (Find-CachedJar 'org.jetbrains.intellij.deps/trove4j')
) -join ';'
$transforms = @(Get-ChildItem -LiteralPath (Join-Path $gradleCache '8.11.1/transforms') -Recurse -File -Filter 'classes.jar' |
    Where-Object { $_.FullName -match '[\\/]jars[\\/]classes\.jar$' })
$androidJar = Join-Path $tempRoot 'toolchains/android-sdk/platforms/android-35/android.jar'
$rJars = @(Get-ChildItem -LiteralPath (Join-Path $tempRoot 'build/app/intermediates') -Recurse -File -Filter 'R.jar' |
    Select-Object -ExpandProperty FullName)
$classpath = (@($stdlib, $annotations, $coroutines, $json, $compiled, $androidJar,
    (Find-CachedJar 'androidx.collection/collection-jvm'),
    (Find-CachedJar 'androidx.lifecycle/lifecycle-common-jvm')) + $transforms.FullName + $rJars) -join ';'
$sourceRoot = Join-Path $projectRoot 'app/src/main/java/com/dafeiyu/mobile'
$sources = @('data/ModelRuntimeController.kt', 'data/OfficialMultiProviderSession.kt', 'data/OfficialCredentialRecoveryUiState.kt', 'ui/ModelProvidersContent.kt') |
    ForEach-Object { Join-Path $sourceRoot $_ }
$sources += Join-Path $PSScriptRoot 'ModelMultiProviderChecks.kt'
$sources += Join-Path $projectRoot 'tools/tests/model-worker/RecoveryFixture.kt'
$composePlugin = Find-CachedJar 'org.jetbrains.kotlin/kotlin-compose-compiler-plugin-embeddable/2.1.20'
$jar = Join-Path $testRoot 'model-multiprovider.jar'
$java = Join-Path $javaRoot 'bin/java.exe'
Write-Output "Model multi-provider QA output: $testRoot"
& $java "-Xmx${MaxHeapMiB}m" "-Djava.io.tmpdir=$testRoot" -cp $compilerClasspath `
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -Werror `
    "-Xplugin=$composePlugin" "-Xfriend-paths=$compiled" -classpath $classpath -d $jar @sources 2>&1 |
    Tee-Object -FilePath (Join-Path $testRoot 'compile.log')
if ($LASTEXITCODE -ne 0) { throw 'Model multi-provider checks failed to compile' }
& $java "-Xmx${MaxHeapMiB}m" "-Djava.io.tmpdir=$testRoot" -cp "$jar;$classpath" `
    com.dafeiyu.mobile.data.ModelMultiProviderChecksKt 2>&1 |
    Tee-Object -FilePath (Join-Path $testRoot 'results.log')
if ($LASTEXITCODE -ne 0) { throw 'Model multi-provider checks failed' }
Write-Output 'Offline injected worker/dispatcher checks only; no paid API, public service, Gradle build or device validation.'
