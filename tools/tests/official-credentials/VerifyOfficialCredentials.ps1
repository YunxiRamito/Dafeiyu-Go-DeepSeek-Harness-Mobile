param([ValidateRange(256, 768)][int]$MaxHeapMiB = 384, [string]$InstalledModulesRoot = 'G:/DeepSeek DSH', [switch]$CompileOnly, [string]$EvidenceRoot)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '../Temp_Android'))
$runRoot = Join-Path $tempRoot ('qa/official-credentials/run-' + [Guid]::NewGuid().ToString('N'))
if ($EvidenceRoot) {
    $runRoot = [IO.Path]::GetFullPath($EvidenceRoot)
    if (!$runRoot.StartsWith($tempRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Evidence must remain inside Temp_Android.' }
}
New-Item -ItemType Directory -Force -Path $runRoot | Out-Null
$env:TMP = $runRoot
$env:TEMP = $runRoot
$cacheRoot = Join-Path $tempRoot 'caches/gradle/caches/modules-2/files-2.1'
function Cached([string]$relative) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $cacheRoot $relative) -Recurse -File -Filter '*.jar' |
        Sort-Object FullName | Select-Object -First 1 -ExpandProperty FullName
    if (!$jar) { throw "Missing offline dependency: $relative" }
    return $jar
}
$stdlib = Cached 'org.jetbrains.kotlin/kotlin-stdlib/2.1.20'
$coroutines = Cached 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0'
$annotations = Cached 'org.jetbrains/annotations'
$compilerClasspath = @((Cached 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.20'), $stdlib, $coroutines,
    $annotations, (Cached 'org.jetbrains.kotlin/kotlin-reflect'), (Cached 'org.jetbrains.intellij.deps/trove4j')) -join ';'
$compiled = Join-Path $tempRoot 'build/app/tmp/kotlin-classes/debug'
if (!(Test-Path -LiteralPath (Join-Path $compiled 'com/dafeiyu/mobile/data/AndroidOfficialDshLaunchFactory.class'))) {
    throw 'Current debug application classes are missing; build the current debug variant before focused official credential compilation.'
}
$classpath = @($compiled, $stdlib, $coroutines, $annotations, (Cached 'com.squareup.okhttp3/okhttp/4.12.0'),
    (Cached 'org.apache.commons/commons-compress/1.27.1'), (Cached 'commons-io/commons-io/2.16.1'),
    (Cached 'org.apache.commons/commons-lang3/3.16.0'),
    (Cached 'com.squareup.okio/okio-jvm/3.6.0'), (Join-Path $tempRoot 'qa/workspace-presence/json-20240303.jar'),
    (Join-Path $tempRoot 'toolchains/android-sdk/platforms/android-35/android.jar')) -join ';'
$javaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:/Program Files/Java/JDK21' }
$java = Join-Path $javaRoot 'bin/java.exe'
$candidateJar = Join-Path $runRoot 'official-credential-candidate.jar'
$dataRoot = Join-Path $projectRoot 'app/src/main/java/com/dafeiyu/mobile/data'
$sources = @('BalanceStore.kt', 'ModelProviderStore.kt', 'DymSharedCredentials.kt',
    'OfficialDeepSeekCredentialTransaction.kt', 'OfficialCredentialRecoveryGate.kt',
    'OfficialCredentialPortable.kt', 'OfficialCredentialJournalCodec.kt',
    'AndroidOfficialCredentialJournal.kt', 'AndroidOfficialCredentialBinding.kt',
    'OfficialDshStorageCredentialTransaction.kt', 'AndroidOfficialDshStorageAuthorizer.kt', 'AndroidOfficialDshControlledPort.kt',
    'AndroidOfficialDshLaunchFactory.kt', 'AndroidDshNodeTraceeVerifier.kt',
    'LinuxRuntimeManager.kt', 'LinuxManagedProcess.kt', 'LinuxManagedRuntimeIdentity.kt', 'LinuxManagedStartupOwnership.kt') |
    ForEach-Object { Join-Path $dataRoot $_ }
$sources += Join-Path $PSScriptRoot 'OfficialCredentialChecks.kt'
Write-Output "Official credential production-source evidence: $runRoot"
& $java "-Xmx${MaxHeapMiB}m" "-Djava.io.tmpdir=$runRoot" -cp $compilerClasspath `
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -Werror -no-stdlib -no-reflect -jvm-target 17 `
    "-Xfriend-paths=$compiled" -classpath $classpath -d $candidateJar @sources *> (Join-Path $runRoot 'compile.log')
if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath (Join-Path $runRoot 'compile.log'); throw 'Candidate strict focused compilation failed' }
if ($CompileOnly) { Write-Output "PASS strict current official credential/factory/runtime source and fixture compilation only; remaining dependencies use current debug classes; no fixture, Keystore or model request executed; evidence $runRoot"; return }
& $java "-Xmx${MaxHeapMiB}m" "-Djava.io.tmpdir=$runRoot" -cp "$candidateJar;$classpath" `
    com.dafeiyu.mobile.data.OfficialCredentialChecksKt $runRoot *> (Join-Path $runRoot 'results.log')
if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath (Join-Path $runRoot 'results.log'); throw 'Candidate transaction/recovery checks failed' }
& node (Join-Path $PSScriptRoot 'pinned-official-credentials.mjs') `
    (Join-Path $projectRoot 'app/src/main/assets/dsh-runtime/shared-persistence.mjs') $InstalledModulesRoot `
    (Join-Path $runRoot 'official-stage-frame.json') (Join-Path $runRoot 'pinned-credentials') *> (Join-Path $runRoot 'pinned-credentials.log')
if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath (Join-Path $runRoot 'pinned-credentials.log'); throw 'Pinned official storage/metadata checks failed' }
Get-Content -LiteralPath (Join-Path $runRoot 'results.log')
Get-Content -LiteralPath (Join-Path $runRoot 'pinned-credentials.log')
Write-Output 'Strict production-source compile, synthetic JVM state-machine/file tests, actual pinned credential helper. No Android Keystore runtime, Gradle/ADB/deploy/model/login call.'
