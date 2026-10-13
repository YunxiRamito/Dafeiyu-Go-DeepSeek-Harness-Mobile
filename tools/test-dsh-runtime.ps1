param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
$testRoot = Join-Path $tempRoot 'qa\local-dsh-0.1.25'
$cacheRoot = Join-Path $tempRoot 'caches\gradle\caches\modules-2\files-2.1'
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$env:TMP = $testRoot
$env:TEMP = $testRoot
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
    (Cached 'com.squareup.okhttp3\okhttp\4.12.0'), (Cached 'com.squareup.okio\okio-jvm\3.6.0'),
    (Join-Path $tempRoot 'qa\workspace-presence\json-20240303.jar'),
    (Join-Path $tempRoot 'toolchains\android-sdk\platforms\android-35\android.jar')) -join ';'
$dataRoot = Join-Path $projectRoot 'app\src\main\java\com\dafeiyu\mobile\data'
$sources = @('LinuxRuntimeContract.kt', 'LinuxSandboxContract.kt', 'LinuxSandboxVerifier.kt', 'LinuxArchiveExtractor.kt', 'LinuxRuntimeManager.kt', 'LinuxManagedProcess.kt',
    'ComponentDownloadClient.kt', 'ArtifactDownloader.kt', 'DshRuntimeManifest.kt', 'DshRuntimeInstaller.kt',
    'DshRuntimeManager.kt', 'DshSandboxProbe.kt', 'DshExtensionTransaction.kt', 'DshProviderOrderProjection.kt',
    'ModelProviderConfig.kt', 'ModelProviderStore.kt', 'ConnectionPluginPackage.kt', 'ConnectionPackageValidator.kt') | ForEach-Object { Join-Path $dataRoot $_ }
$sources += Join-Path $PSScriptRoot 'tests\archive-startup\CompileDependencies.kt'
$sources += Join-Path $PSScriptRoot 'tests\provider\android-api\Resources.kt'
$test = Join-Path $PSScriptRoot 'tests\dsh-runtime\DshRuntimeChecks.kt'
$lifecycleTest = Join-Path $PSScriptRoot 'tests\dsh-runtime\DshLifecycleChecks.kt'
$javaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\JDK21' }
$jar = Join-Path $testRoot 'dsh-runtime.jar'
$compileLog = Join-Path $testRoot 'compile.log'
$checksLog = Join-Path $testRoot 'checks.log'
Set-Content -LiteralPath $compileLog -Value ''
Set-Content -LiteralPath $checksLog -Value ''
& (Join-Path $javaRoot 'bin\java.exe') '-Xmx256m' "-Djava.io.tmpdir=$testRoot" -cp $compilerClasspath `
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -Werror -no-stdlib -no-reflect -jvm-target 17 -classpath $classpath `
    -d $jar @sources $test $lifecycleTest 2>&1 | Tee-Object -FilePath $compileLog
if ($LASTEXITCODE -ne 0) { throw 'DSH runtime strict Android 35 compilation failed' }
& (Join-Path $javaRoot 'bin\java.exe') '-Xmx256m' "-Djava.io.tmpdir=$testRoot" -cp ($jar + ';' + $classpath) com.dafeiyu.mobile.data.DshRuntimeChecksKt |
    Tee-Object -FilePath $checksLog
if ($LASTEXITCODE -ne 0) { throw 'DSH runtime checks failed' }
& (Join-Path $javaRoot 'bin\java.exe') '-Xmx256m' "-Djava.io.tmpdir=$testRoot" -cp ($jar + ';' + $classpath) com.dafeiyu.mobile.data.DshLifecycleChecksKt |
    Tee-Object -FilePath $checksLog -Append
if ($LASTEXITCODE -ne 0) { throw 'DSH lifecycle fixture checks failed' }
Write-Output 'PASS DSH manifest and production lifecycle fixture checks'

