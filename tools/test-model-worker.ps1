param([string]$InstalledModulesRoot = 'G:\DeepSeek DSH', [switch]$GenerationChecks, [switch]$HttpFixtureChecks, [switch]$CompileOnly, [string]$EvidenceRoot)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
$testRoot = Join-Path $tempRoot ('qa/model-worker/run-' + [Guid]::NewGuid().ToString('N'))
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
    'LinuxManagedRuntimeIdentity.kt', 'LinuxManagedStartupOwnership.kt', 'ConnectionPackageValidator.kt',
    'ComponentDownloadClient.kt', 'ArtifactDownloader.kt', 'ModelProviderConfig.kt', 'ModelProviderStore.kt', 'PiAiNodeWorker.kt',
    'ModelRuntimeController.kt',
    'PiAiRuntimeInstaller.kt', 'PiAiChatAdapter.kt', 'AssistantStreamProtocol.kt', 'ChatMessage.kt', 'DshProviderOrderProjection.kt',
    'OfficialDeepSeekCredentialTransaction.kt', 'OfficialCredentialPortable.kt', 'OfficialCredentialRecoveryGate.kt',
    'OfficialCredentialJournalCodec.kt', 'AndroidOfficialCredentialJournal.kt',
    'OfficialDshStorageCredentialTransaction.kt', 'AndroidOfficialDshStorageAuthorizer.kt') |
    ForEach-Object { Join-Path $dataRoot $_ }
$javaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\JDK21' }
$sources += Join-Path $PSScriptRoot 'tests/model-worker/RecoveryFixture.kt'
& (Join-Path $javaRoot 'bin\java.exe') '-Xmx256m' "-Djava.io.tmpdir=$testRoot" -cp $compilerClasspath `
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -Werror -no-stdlib -no-reflect -jvm-target 17 -classpath $classpath `
    -d (Join-Path $testRoot 'runtime-worker.jar') @sources *> (Join-Path $testRoot 'compile.log')
if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath (Join-Path $testRoot 'compile.log'); throw 'Runtime and worker Android API compilation failed' }
Write-Output 'PASS production Linux/process/provider/worker strict Android 35 API compilation (no Android process launched)'
if ($CompileOnly) { Write-Output "Compile only; no generation/HTTP fixtures executed; evidence $testRoot"; return }
if ($GenerationChecks) {
    & (Join-Path $javaRoot 'bin\java.exe') '-Xmx256m' "-Djava.io.tmpdir=$testRoot" -cp $compilerClasspath `
        org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -Werror -no-stdlib -no-reflect -jvm-target 17 -classpath $classpath `
        -d (Join-Path $testRoot 'generation-checks.jar') @sources (Join-Path $PSScriptRoot 'tests\model-worker\GenerationChecks.kt')
    if ($LASTEXITCODE -ne 0) { throw 'Generation fixture compilation failed' }
    & (Join-Path $javaRoot 'bin\java.exe') '-Xmx256m' "-Djava.io.tmpdir=$testRoot" -cp `
        ((Join-Path $testRoot 'generation-checks.jar') + ';' + $classpath) com.dafeiyu.mobile.data.GenerationChecksKt
    if ($LASTEXITCODE -ne 0) { throw 'Generation fixtures failed' }
}
if ($HttpFixtureChecks) {
    function AarClasses([string]$relative, [string]$name) {
        $aar = Get-ChildItem -LiteralPath (Join-Path $cacheRoot $relative) -Recurse -File -Filter '*.aar' |
            Select-Object -First 1 -ExpandProperty FullName
        if (-not $aar) { throw "Missing cached dependency $relative" }
        $target = Join-Path $testRoot $name
        $archive = [IO.Compression.ZipFile]::OpenRead($aar)
        try {
            $entry = $archive.GetEntry('classes.jar')
            if (-not $entry) { throw "Missing classes.jar in $relative" }
            $source = $entry.Open()
            $output = [IO.File]::Create($target)
            try { $source.CopyTo($output) } finally { $source.Dispose(); $output.Dispose() }
        } finally { $archive.Dispose() }
        return $target
    }
    $fixtureClasspath = $classpath + ';' + (@((Cached 'junit\junit\4.13.2'),
        (AarClasses 'androidx.test.ext\junit\1.2.1' 'androidx-junit.jar'),
        (AarClasses 'androidx.test\monitor\1.7.2' 'androidx-monitor.jar'),
        (AarClasses 'androidx.test\runner\1.6.2' 'androidx-runner.jar')) -join ';')
    $fixtureJar = Join-Path $testRoot 'http-fixture-checks.jar'
    & (Join-Path $javaRoot 'bin\java.exe') '-Xmx256m' "-Djava.io.tmpdir=$testRoot" -cp $compilerClasspath `
        org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -Werror -no-stdlib -no-reflect -jvm-target 17 -classpath $fixtureClasspath `
        -d $fixtureJar @sources (Join-Path $projectRoot 'app\src\androidTest\java\com\dafeiyu\mobile\PiAiWorkerSmokeTest.kt') `
        (Join-Path $PSScriptRoot 'tests\model-worker\HttpFixtureChecks.kt')
    if ($LASTEXITCODE -ne 0) { throw 'Strict Android35 instrumentation and HTTP fixture compilation failed' }
    Write-Output 'PASS strict production/Android35 instrumentation and HTTP fixture compilation'
    & (Join-Path $javaRoot 'bin\java.exe') '-Xmx256m' "-Djava.io.tmpdir=$testRoot" -cp "$fixtureJar;$fixtureClasspath" `
        com.dafeiyu.mobile.data.HttpFixtureChecksKt (Join-Path $InstalledModulesRoot 'node_modules\node\bin\node.exe') `
        (Join-Path $projectRoot 'app\src\main\assets\model-worker\worker.mjs') $InstalledModulesRoot $testRoot
    if ($LASTEXITCODE -ne 0) { throw 'HTTP fixture/controller cancellation checks failed' }
}
