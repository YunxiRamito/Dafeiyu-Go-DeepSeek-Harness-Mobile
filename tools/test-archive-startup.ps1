param([switch]$CompileOnly)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
$testRoot = Join-Path $tempRoot 'qa\archive-startup'
$cacheRoot = Join-Path $tempRoot 'caches\gradle\caches\modules-2\files-2.1'
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$env:TMP = $testRoot; $env:TEMP = $testRoot
function Cached([string]$relative) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $cacheRoot $relative) -Recurse -File -Filter '*.jar' |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw "Missing cached dependency $relative" }
    return $jar
}
$stdlib = Cached 'org.jetbrains.kotlin\kotlin-stdlib\2.1.20'
$coroutines = Cached 'org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm\1.9.0'
$annotations = Cached 'org.jetbrains\annotations'
$compilerClasspath = @((Cached 'org.jetbrains.kotlin\kotlin-compiler-embeddable\2.1.20'), $stdlib, $coroutines,
    $annotations, (Cached 'org.jetbrains.kotlin\kotlin-reflect'), (Cached 'org.jetbrains.intellij.deps\trove4j')) -join ';'
$classpath = @($stdlib, $coroutines, $annotations, (Cached 'org.apache.commons\commons-compress\1.27.1'),
    (Cached 'commons-io\commons-io\2.16.1'), (Cached 'org.apache.commons\commons-lang3\3.16.0'),
    (Cached 'com.squareup.okhttp3\okhttp\4.12.0'), (Cached 'com.squareup.okio\okio-jvm\3.6.0'),
    (Join-Path $tempRoot 'qa\workspace-presence\json-20240303.jar'),
    (Join-Path $tempRoot 'toolchains\android-sdk\platforms\android-35\android.jar')) -join ';'
$dataRoot = Join-Path $projectRoot 'app\src\main\java\com\dafeiyu\mobile\data'
$sources = @('LinuxRuntimeContract.kt', 'LinuxSandboxContract.kt', 'LinuxSandboxVerifier.kt', 'LinuxArchiveExtractor.kt',
    'LinuxRuntimeManager.kt', 'LinuxManagedProcess.kt', 'ComponentDownloadClient.kt', 'ArtifactDownloader.kt',
    'DshRuntimeManifest.kt', 'DshRuntimeInstaller.kt', 'DshRuntimeManager.kt', 'DshSandboxProbe.kt', 'DshExtensionTransaction.kt',
    'DshProviderOrderProjection.kt', 'ModelProviderConfig.kt', 'ModelProviderStore.kt', 'ConnectionPluginPackage.kt',
    'ConnectionPackageValidator.kt') | ForEach-Object { Join-Path $dataRoot $_ }
$sources += Join-Path $PSScriptRoot 'tests\archive-startup\CompileDependencies.kt'
$sources += Join-Path $PSScriptRoot 'tests\provider\android-api\Resources.kt'
$javaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\JDK21' }
$productionJar = Join-Path $testRoot 'archive-startup-android35.jar'
& (Join-Path $javaRoot 'bin\java.exe') '-Xmx256m' "-Djava.io.tmpdir=$testRoot" -cp $compilerClasspath `
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -Werror -no-stdlib -no-reflect -jvm-target 17 -classpath $classpath `
    -d $productionJar @sources
if ($LASTEXITCODE -ne 0) { throw 'Archive startup production Android35 compilation failed' }
Write-Output 'PASS real Android35 production RuntimeManager/LinuxManager/ConnectionPackage/Installer strict Werror compile (feed constant and generated R fixture only)'
if ($CompileOnly) { return }
$fixtureJar = Join-Path $testRoot 'archive-startup-checks.jar'
& (Join-Path $javaRoot 'bin\java.exe') '-Xmx256m' "-Djava.io.tmpdir=$testRoot" -cp $compilerClasspath `
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -Werror -no-stdlib -no-reflect -jvm-target 17 "-Xfriend-paths=$productionJar" `
    -classpath "$productionJar;$classpath" -d $fixtureJar `
    (Join-Path $PSScriptRoot 'tests\archive-startup\ArchiveStartupChecks.kt') (Join-Path $PSScriptRoot 'tests\pi-npm-archives\Os.kt')
if ($LASTEXITCODE -ne 0) { throw 'Archive startup fixture compilation failed' }
$runRoot = Join-Path $testRoot ('run-' + [Guid]::NewGuid().ToString('N'))
& (Join-Path $javaRoot 'bin\java.exe') '-Xmx256m' "-Djava.io.tmpdir=$testRoot" -cp "$fixtureJar;$productionJar;$classpath" `
    com.dafeiyu.mobile.data.ArchiveStartupChecksKt $runRoot (Join-Path $tempRoot 'runtime\assets\connection-plugin') `
    (Join-Path $tempRoot 'qa\remote-attachments-linux\bundle\node_modules\@deepseek-ai\dsh\package.json')
if ($LASTEXITCODE -ne 0) { throw 'Archive startup focused fixtures failed' }
& node (Join-Path $PSScriptRoot 'tests\archive-startup\checks.mjs') `
    (Join-Path $projectRoot 'app\src\main\assets\dsh-runtime\android-launch.mjs') (Join-Path $testRoot ('node-' + [Guid]::NewGuid().ToString('N')))
if ($LASTEXITCODE -ne 0) { throw 'Archive Android launcher order checks failed' }
