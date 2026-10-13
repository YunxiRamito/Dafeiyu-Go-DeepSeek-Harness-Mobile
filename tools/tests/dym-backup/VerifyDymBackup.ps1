param([ValidateRange(256, 768)][int]$MaxHeapMiB = 512,
    [string]$PinnedDshBundle = 'G:/DeepSeek DSH',
    [switch]$WithUi, [switch]$CompileOnly)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '../Temp_Android'))
$cacheRoot = Join-Path $tempRoot 'caches/gradle/caches/modules-2/files-2.1'
$testRoot = Join-Path $tempRoot ('qa/dym-backup/run-' + [Guid]::NewGuid().ToString('N'))
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
$compiledProject = @('debug', 'release') | ForEach-Object { Join-Path $tempRoot "build/app/tmp/kotlin-classes/$_" } |
    Where-Object { Test-Path -LiteralPath (Join-Path $_ 'com/dafeiyu/mobile/data/DemoRepository.class') } |
    Sort-Object { (Get-Item -LiteralPath (Join-Path $_ 'com/dafeiyu/mobile/data/DemoRepository.class')).LastWriteTime } -Descending |
    Select-Object -First 1
if (-not $compiledProject) { throw 'A production Android compilation is required for runtime interfaces.' }
$androidJar = Join-Path $tempRoot 'toolchains/android-sdk/platforms/android-35/android.jar'
$sharing = Get-ChildItem -LiteralPath (Join-Path $tempRoot 'qa') -Directory -Filter 'dsh-sharing-*' |
    Sort-Object LastWriteTime -Descending | ForEach-Object { Join-Path $_.FullName 'sharing.jar' } |
    Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $sharing) { throw 'Pinned DSH sharing production compilation is required for its actual idle-lease API.' }
$sharingFixtures = Split-Path $sharing -Parent
if (-not (Test-Path -LiteralPath (Join-Path $sharingFixtures 'none')) -or
    -not (Test-Path -LiteralPath (Join-Path $sharingFixtures 'zstd'))) {
    throw 'Actual first-party none and zstd fixtures are required.'
}
$node = (Get-Command node -ErrorAction Stop).Source
$persistenceScript = Join-Path $projectRoot 'app/src/main/assets/dsh-runtime/shared-persistence.mjs'
$compilerClasspath = @(
    (Find-CachedJar 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.20'),
    $stdlib, $annotations, $coroutines,
    (Find-CachedJar 'org.jetbrains.kotlin/kotlin-reflect'),
    (Find-CachedJar 'org.jetbrains.intellij.deps/trove4j')
) -join ';'
$classpath = @($stdlib, $annotations, $coroutines, $json, $compiledProject, $sharing, $androidJar,
    (Find-CachedJar 'org.apache.commons/commons-compress/1.27.1'),
    (Find-CachedJar 'commons-io/commons-io/2.16.1'),
    (Find-CachedJar 'org.apache.commons/commons-lang3'),
    (Find-CachedJar 'org.tukaani/xz/1.10')) -join ';'
$compilerOptions = @()
if ($WithUi) {
    $gradleCache = Join-Path $tempRoot 'caches/gradle/caches'
    $transforms = @(Get-ChildItem -LiteralPath (Join-Path $gradleCache '8.11.1/transforms') -Recurse -File -Filter 'classes.jar' |
        Where-Object { $_.FullName -match '[\\/]jars[\\/]classes\.jar$' } |
        Select-Object -ExpandProperty FullName)
    $rJars = @(Get-ChildItem -LiteralPath (Join-Path $tempRoot 'build/app/intermediates') -Recurse -File -Filter 'R.jar' |
        Select-Object -ExpandProperty FullName)
    $additionalClasspath = @(
        (Find-CachedJar 'androidx.collection/collection-jvm'),
        (Find-CachedJar 'androidx.lifecycle/lifecycle-common-jvm')) + $transforms + $rJars
    $classpath += ';' + ($additionalClasspath -join ';')
    $composePlugin = Find-CachedJar 'org.jetbrains.kotlin/kotlin-compose-compiler-plugin-embeddable/2.1.20'
    $compilerOptions += "-Xplugin=$composePlugin"
}
$dataRoot = Join-Path $projectRoot 'app/src/main/java/com/dafeiyu/mobile/data'
$sources = @('DymArchiveStore.kt', 'AndroidBackupStore.kt', 'WorkspaceStore.kt', 'WorkspaceLifecycle.kt',
    'WorkspaceFileRemoval.kt', 'DymRuntimeResourceStore.kt', 'DymHistoryConverter.kt',
    'DshSharedHistoryAdapter.kt', 'ChatMessage.kt', 'AssistantStreamProtocol.kt',
    'AssistantStreamSnapshotJson.kt', 'DymNativeHistoryStore.kt', 'DymBackupCoordinator.kt',
    'DymRetainedResourceStore.kt', 'AndroidDymPreferences.kt', 'SettingsContract.kt', 'LinuxSandboxContract.kt',
    'DymSharedCredentials.kt', 'ModelProviderConfig.kt', 'DshSharedPersistenceBridge.kt') | ForEach-Object { Join-Path $dataRoot $_ }
$sources += Join-Path $PSScriptRoot 'DymBackupChecks.kt'
$sources += Join-Path $projectRoot 'tools/tests/dym-pc-parity/AndroidRoundTrip.kt'
$fixture = Join-Path $testRoot 'DymBackupRepositoryFixture.kt'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'DymBackupRepositoryFixture.kt.txt') -Destination $fixture
$sources += $fixture
if ($WithUi) { $sources += Join-Path $projectRoot 'app/src/main/java/com/dafeiyu/mobile/ui/DymRetainedResourcesContent.kt' }
$jar = Join-Path $testRoot 'dym-backup.jar'
$java = Join-Path $javaRoot 'bin/java.exe'
Write-Output "Dym backup QA output: $testRoot"
& $java "-Xmx${MaxHeapMiB}m" "-Djava.io.tmpdir=$testRoot" -cp $compilerClasspath `
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -Werror `
    "-Xfriend-paths=$compiledProject,$sharing" @compilerOptions -classpath $classpath -d $jar @sources 2>&1 |
    Tee-Object -FilePath (Join-Path $testRoot 'compile.log')
if ($LASTEXITCODE -ne 0) { throw 'Dym backup production helper compilation failed' }
if ($CompileOnly) { Write-Output "Dym production compile complete: $testRoot"; return }
& $java "-Xmx${MaxHeapMiB}m" "-Djava.io.tmpdir=$testRoot" -cp "$jar;$classpath" `
    com.dafeiyu.mobile.data.DymBackupChecksKt (Join-Path $testRoot 'fixtures') $sharingFixtures `
    $node $PinnedDshBundle $persistenceScript 2>&1 |
    Tee-Object -FilePath (Join-Path $testRoot 'results.log')
if ($LASTEXITCODE -ne 0) { throw 'Dym backup checks failed' }
