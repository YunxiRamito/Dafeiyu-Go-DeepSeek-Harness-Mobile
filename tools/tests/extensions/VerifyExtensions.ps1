param([ValidateRange(256, 768)][int]$MaxHeapMiB = 384,
    [string]$PinnedDshBundle = 'G:/DeepSeek DSH',
    [string]$PinnedPnpmCli,
    [switch]$WithUi,
    [switch]$CompileOnly,
    [string]$EvidenceRoot)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '../Temp_Android'))
$cacheRoot = Join-Path $tempRoot 'caches/gradle/caches/modules-2/files-2.1'
$testRoot = Join-Path $tempRoot ('qa/extensions/run-' + [Guid]::NewGuid().ToString('N'))
if ($EvidenceRoot) {
    $testRoot = [IO.Path]::GetFullPath($EvidenceRoot)
    if (!$testRoot.StartsWith($tempRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Evidence must remain inside Temp_Android.' }
}
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$env:TMP = $testRoot
$env:TEMP = $testRoot
$socketRoot = Join-Path $tempRoot 'qa-sockets'
New-Item -ItemType Directory -Force -Path $socketRoot | Out-Null
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
# Build the current debug variant first; remaining application dependencies
# come from that build, never an older release variant's runtime signatures.
$compiledProject = Join-Path $tempRoot 'build/app/tmp/kotlin-classes/debug'
if (!(Test-Path -LiteralPath (Join-Path $compiledProject 'com/dafeiyu/mobile/data/AndroidOfficialDshLaunchFactory.class'))) {
    throw 'Current debug application classes are missing; build the current debug variant before focused extension compilation.'
}
$androidJar = Join-Path $tempRoot 'toolchains/android-sdk/platforms/android-35/android.jar'
$compilerClasspath = @(
    (Find-CachedJar 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.20'), $stdlib, $annotations, $coroutines,
    (Find-CachedJar 'org.jetbrains.kotlin/kotlin-reflect'), (Find-CachedJar 'org.jetbrains.intellij.deps/trove4j')
) -join ';'
$classpath = @($stdlib, $annotations, $coroutines, $json, $compiledProject, $androidJar,
    (Find-CachedJar 'org.apache.commons/commons-compress/1.27.1'),
    (Find-CachedJar 'commons-io/commons-io/2.16.1'), (Find-CachedJar 'org.apache.commons/commons-lang3'),
    (Find-CachedJar 'org.yaml/snakeyaml/2.4'), (Find-CachedJar 'com.squareup.okhttp3/okhttp/4.12.0'),
    (Find-CachedJar 'com.squareup.okio/okio-jvm')) -join ';'
$compilerOptions = @()
if ($WithUi) {
    $transforms = @(Get-ChildItem -LiteralPath (Join-Path $tempRoot 'caches/gradle/caches/8.11.1/transforms') -Recurse -File -Filter 'classes.jar' |
        Where-Object { $_.FullName -match '[\\/]jars[\\/]classes\.jar$' } | Select-Object -ExpandProperty FullName)
    $rJars = @(Get-ChildItem -LiteralPath (Join-Path $tempRoot 'build/app/intermediates') -Recurse -File -Filter 'R.jar' | Select-Object -ExpandProperty FullName)
    $classpath += ';' + (@((Find-CachedJar 'androidx.collection/collection-jvm'),
        (Find-CachedJar 'androidx.lifecycle/lifecycle-common-jvm')) + $transforms + $rJars -join ';')
    $compilerOptions += '-Xplugin=' + (Find-CachedJar 'org.jetbrains.kotlin/kotlin-compose-compiler-plugin-embeddable/2.1.20')
}
$dataRoot = Join-Path $projectRoot 'app/src/main/java/com/dafeiyu/mobile/data'
$sources = @('ExtensionFiles.kt', 'GithubExtensionSource.kt', 'SkillInstallStore.kt', 'RecommendationFeed.kt',
    'NpmArtifactRegistry.kt', 'NpmExtensionCatalog.kt', 'DshExtensionTransaction.kt', 'DshExtensionBridge.kt', 'DshExtensionLiveClient.kt', 'ExtensionPackageStore.kt',
    'ArtifactDownloader.kt', 'ComponentDownloadClient.kt', 'LinuxRuntimeManager.kt', 'LinuxManagedProcess.kt',
    'LinuxManagedRuntimeIdentity.kt', 'LinuxManagedStartupOwnership.kt', 'LinuxRuntimeContract.kt',
    'LinuxSandboxContract.kt', 'LinuxSandboxVerifier.kt') | ForEach-Object { Join-Path $dataRoot $_ }
$sources += Join-Path $PSScriptRoot 'ExtensionChecks.kt'
$sources += Join-Path $PSScriptRoot 'ExtensionRegistryChecks.kt'
if ($WithUi) { $sources += Join-Path $projectRoot 'app/src/main/java/com/dafeiyu/mobile/ui/ExtensionsScreen.kt' }
$java = Join-Path $javaRoot 'bin/java.exe'
$jar = Join-Path $testRoot 'extensions.jar'
Write-Output "Extensions QA output: $testRoot"
& $java "-Xmx${MaxHeapMiB}m" "-Djava.io.tmpdir=$testRoot" -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -Werror "-Xfriend-paths=$compiledProject" @compilerOptions -classpath $classpath -d $jar @sources *> (Join-Path $testRoot 'compile.log')
if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath (Join-Path $testRoot 'compile.log'); throw 'Extension production helper compilation failed' }
if ($CompileOnly) { Write-Output "PASS strict current extension/runtime source and fixture compilation only; remaining dependencies use current debug classes; no fixtures or package install executed; evidence $testRoot"; return }
$node = (Get-Command node -ErrorAction Stop).Source
if ($PinnedPnpmCli) { $pnpmScript = [IO.Path]::GetFullPath($PinnedPnpmCli) }
else {
    $pnpmCommand = (Get-Command pnpm -ErrorAction Stop).Source
    $pnpmScript = Join-Path (Split-Path $pnpmCommand -Parent) 'node_modules/pnpm/bin/pnpm.cjs'
    if (-not (Test-Path -LiteralPath $pnpmScript)) {
        $pnpmScript = Join-Path (Split-Path $pnpmCommand -Parent) 'node_modules/pnpm/pnpm.exe'
    }
}
if (-not (Test-Path -LiteralPath $pnpmScript)) { throw 'Host pnpm CLI is missing; no simulated install is used' }
& $java "-Xmx${MaxHeapMiB}m" "-Djava.io.tmpdir=$testRoot" "-Djdk.net.unixdomain.tmpdir=$socketRoot" -cp "$jar;$classpath" com.dafeiyu.mobile.data.ExtensionChecksKt `
    (Join-Path $testRoot 'fixtures') $node $PinnedDshBundle (Join-Path $projectRoot 'app/src/main/assets/dsh-runtime/plugin-management.mjs') $pnpmScript 2>&1 |
    Tee-Object -FilePath (Join-Path $testRoot 'results.log')
if ($LASTEXITCODE -ne 0) { throw 'Extension checks failed' }
