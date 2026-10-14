param([string]$InstalledModulesRoot = 'G:\DeepSeek DSH', [switch]$CompileOnly, [string]$EvidenceRoot)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
$testRoot = Join-Path $tempRoot ('qa\dsh-sharing-' + [Guid]::NewGuid().ToString('N'))
if ($EvidenceRoot) {
    $testRoot = [IO.Path]::GetFullPath($EvidenceRoot)
    if (!$testRoot.StartsWith($tempRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Evidence must remain inside Temp_Android.' }
}
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
    'LinuxManagedRuntimeIdentity.kt', 'LinuxManagedStartupOwnership.kt',
    'ComponentDownloadClient.kt', 'ArtifactDownloader.kt', 'DshRuntimeManifest.kt', 'DshRuntimeInstaller.kt',
    'DshRuntimeManager.kt', 'DshMobileUiPackage.kt', 'DshSandboxProbe.kt', 'DshSharedPersistenceBridge.kt', 'DshSharedHistoryAdapter.kt',
    'AssistantStreamProtocol.kt', 'ChatMessage.kt', 'DshExtensionTransaction.kt', 'DshProviderOrderProjection.kt',
    'ModelProviderConfig.kt', 'ModelProviderStore.kt', 'ConnectionPluginPackage.kt', 'ConnectionPackageValidator.kt',
    'OfficialDeepSeekCredentialTransaction.kt', 'OfficialDshStorageCredentialTransaction.kt', 'OfficialCredentialPortable.kt',
    'OfficialCredentialJournalCodec.kt', 'AndroidOfficialCredentialJournal.kt', 'AndroidOfficialDshStorageAuthorizer.kt') | ForEach-Object { Join-Path $dataRoot $_ }
$sources += Join-Path $PSScriptRoot 'tests\archive-startup\CompileDependencies.kt'
$sources += Join-Path $PSScriptRoot 'tests\provider\android-api\Resources.kt'
$javaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\JDK21' }
$jar = Join-Path $testRoot 'sharing.jar'
& (Join-Path $javaRoot 'bin\java.exe') '-Xmx256m' "-Djava.io.tmpdir=$testRoot" -cp $compilerClasspath `
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -Werror -no-stdlib -no-reflect -jvm-target 17 -classpath $classpath `
    -d $jar @sources (Join-Path $PSScriptRoot 'tests\dsh-sharing\DshSharingChecks.kt') *> (Join-Path $testRoot 'compile.log')
if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath (Join-Path $testRoot 'compile.log'); throw 'Shared history strict Android 35 compilation failed' }
if ($CompileOnly) { Write-Output "PASS strict production shared-history and fixture source compile only; no fixtures or model requests executed; evidence $testRoot"; return }
$native = Join-Path $testRoot 'native'
& (Join-Path $javaRoot 'bin\java.exe') '-Xmx256m' "-Djava.io.tmpdir=$testRoot" -cp ($jar + ';' + $classpath) `
    com.dafeiyu.mobile.data.DshSharingChecksKt $native *> (Join-Path $testRoot 'mapper.log')
if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath (Join-Path $testRoot 'mapper.log'); throw 'Shared history mapper checks failed' }
& node (Join-Path $PSScriptRoot 'tests\dsh-sharing\checks.mjs') `
    (Join-Path $projectRoot 'app\src\main\assets\dsh-runtime\shared-persistence.mjs') $InstalledModulesRoot $testRoot $native `
    *> (Join-Path $testRoot 'node.log')
if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath (Join-Path $testRoot 'node.log'); throw 'First-party DSH storage checks failed' }
Get-Content -LiteralPath (Join-Path $testRoot 'mapper.log')
Get-Content -LiteralPath (Join-Path $testRoot 'node.log')
Write-Output "PASS strict production compile and first-party storage fixtures; evidence $testRoot"
