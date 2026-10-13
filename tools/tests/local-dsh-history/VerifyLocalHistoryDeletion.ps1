param()
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '../Temp_Android'))
$testRoot = Join-Path $tempRoot ('qa/local-history-app-deletion/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$env:TEMP = $testRoot; $env:TMP = $testRoot
$cacheRoot = Join-Path $tempRoot 'caches/gradle/caches/modules-2/files-2.1'
function Cached([string]$relative) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $cacheRoot $relative) -Recurse -File -Filter '*.jar' | Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw "Missing cached dependency $relative" }; return $jar
}
$stdlib = Cached 'org.jetbrains.kotlin/kotlin-stdlib/2.1.20'
$coroutines = Cached 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0'
$annotations = Cached 'org.jetbrains/annotations'
$compiler = @((Cached 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.20'), $stdlib, $coroutines, $annotations,
    (Cached 'org.jetbrains.kotlin/kotlin-reflect'), (Cached 'org.jetbrains.intellij.deps/trove4j')) -join ';'
$projectClasses = Join-Path $tempRoot 'build/app/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar'
$work = Get-ChildItem -LiteralPath (Join-Path $tempRoot 'caches/gradle/caches/8.11.1/transforms') -Recurse -File -Filter 'work-runtime-2.12.0-api.jar' | Select-Object -First 1 -ExpandProperty FullName
$workKtx = Get-ChildItem -LiteralPath (Join-Path $tempRoot 'caches/gradle/caches/8.11.1/transforms') -Recurse -File -Filter 'work-runtime-ktx-2.12.0-api.jar' | Select-Object -First 1 -ExpandProperty FullName
if (-not $work -or -not $workKtx) { throw 'Missing real WorkManager compile artifacts' }
$classpath = @($projectClasses, $stdlib, $coroutines, $annotations,
    $work, $workKtx, (Cached 'com.google.guava/listenablefuture/1.0'), (Cached 'androidx.concurrent/concurrent-futures/1.1.0'),
    (Cached 'com.squareup.okhttp3/okhttp/4.12.0'), (Cached 'com.squareup.okio/okio-jvm/3.6.0'),
    (Join-Path $tempRoot 'qa/workspace-presence/json-20240303.jar'),
    (Join-Path $tempRoot 'toolchains/android-sdk/platforms/android-35/android.jar')) -join ';'
$javaRoot = 'C:/Program Files/Java/JDK21'
$jar = Join-Path $testRoot 'production.jar'
$sourceRoot = Join-Path $projectRoot 'app/src/main/java/com/dafeiyu/mobile/data'
$sources = @('LocalDshHistoryController.kt','WorkspaceStore.kt','DshSharedPersistenceBridge.kt','ArchiveCleanupWorker.kt') | ForEach-Object { Join-Path $sourceRoot $_ }
& (Join-Path $javaRoot 'bin/java.exe') '-Xmx256m' "-Djava.io.tmpdir=$testRoot" -cp $compiler org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -Werror -no-stdlib -no-reflect -jvm-target 17 "-Xfriend-paths=$projectClasses" -classpath $classpath -d $jar @sources *> (Join-Path $testRoot 'compile-production.log')
if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath (Join-Path $testRoot 'compile-production.log'); throw 'Production local history deletion strict compile failed' }
Write-Output 'PASS strict Android35 production Controller/Bridge/Store/Worker Werror compile'
$testApis = @('junit-1.2.1-api.jar','runner-1.6.2-api.jar','monitor-1.7.2-api.jar','core-1.6.1-api.jar') | ForEach-Object {
    $name = $_
    $artifact = Get-ChildItem -LiteralPath (Join-Path $tempRoot 'caches/gradle/caches/8.11.1/transforms') -Recurse -File -Filter $name | Select-Object -First 1 -ExpandProperty FullName
    if (-not $artifact) { throw "Missing real Android test compile artifact $name" }; $artifact
}
$smokeClasspath = @($jar, $classpath, (Cached 'junit/junit/4.13.2')) + $testApis
& (Join-Path $javaRoot 'bin/java.exe') '-Xmx256m' "-Djava.io.tmpdir=$testRoot" -cp $compiler org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -Werror -no-stdlib -no-reflect -jvm-target 17 "-Xfriend-paths=$jar,$projectClasses" -classpath ($smokeClasspath -join ';') `
    -d (Join-Path $testRoot 'android-smoke.jar') `
    (Join-Path $projectRoot 'app/src/androidTest/java/com/dafeiyu/mobile/data/LocalDshHistoryDeletionSmoke.kt') *> (Join-Path $testRoot 'compile-android-smoke.log')
if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath (Join-Path $testRoot 'compile-android-smoke.log'); throw 'Actual Android local history deletion smoke strict compile failed' }
Write-Output 'PASS actual Controller/Store/Bridge Android smoke Werror source compile (device execution pending)'
$checks = Join-Path $testRoot 'checks.jar'
& (Join-Path $javaRoot 'bin/java.exe') '-Xmx256m' "-Djava.io.tmpdir=$testRoot" -cp $compiler org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -Werror -no-stdlib -no-reflect -jvm-target 17 "-Xfriend-paths=$jar,$projectClasses" -classpath "$jar;$classpath" -d $checks `
    (Join-Path $PSScriptRoot 'LocalHistoryDeletionChecks.kt') `
    (Join-Path $PSScriptRoot 'LocalHistoryFingerprintChecks.kt') `
    (Join-Path $projectRoot 'tools/tests/workspace-storage/WorkspaceStorageTestContext.kt') `
    (Join-Path $projectRoot 'tools/tests/workspace-storage/WorkspaceStorageTestAtomicFile.kt') `
    (Join-Path $PSScriptRoot 'LocalHistoryDeletionTestOs.kt') *> (Join-Path $testRoot 'compile-checks.log')
if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath (Join-Path $testRoot 'compile-checks.log'); throw 'Local history checks compile failed' }
& (Join-Path $javaRoot 'bin/java.exe') '-Xmx256m' "-Djava.io.tmpdir=$testRoot" -cp "$checks;$jar;$classpath" `
    com.dafeiyu.mobile.data.LocalHistoryDeletionChecksKt (Join-Path $testRoot 'data') *> (Join-Path $testRoot 'checks.log')
Get-Content -LiteralPath (Join-Path $testRoot 'checks.log')
if ($LASTEXITCODE -ne 0) { throw 'Local history app deletion checks failed' }
& (Join-Path $javaRoot 'bin/java.exe') '-Xmx256m' "-Djava.io.tmpdir=$testRoot" -cp "$checks;$jar;$classpath" `
    com.dafeiyu.mobile.data.LocalHistoryFingerprintChecksKt (Join-Path $testRoot 'fingerprint-data') *> (Join-Path $testRoot 'fingerprint-checks.log')
Get-Content -LiteralPath (Join-Path $testRoot 'fingerprint-checks.log')
if ($LASTEXITCODE -ne 0) { throw 'Local history envelope fingerprint checks failed' }
Write-Output "Evidence $testRoot"
