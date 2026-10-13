$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '../Temp_Android'))
$runRoot = Join-Path $tempRoot ('qa/notifications/run-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $runRoot | Out-Null
$cacheRoot = Join-Path $tempRoot 'caches/gradle/caches/modules-2/files-2.1'
function CachedJar([string]$relative) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $cacheRoot $relative) -Recurse -File -Filter '*.jar' |
        Sort-Object FullName | Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw "Missing cached dependency $relative" }
    return $jar
}
$stdlib = CachedJar 'org.jetbrains.kotlin/kotlin-stdlib/2.1.20'
$coroutines = CachedJar 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0'
$annotations = CachedJar 'org.jetbrains/annotations/23.0.0'
$compilerClasspath = @((CachedJar 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.20'), $stdlib, $coroutines,
    $annotations, (CachedJar 'org.jetbrains.kotlin/kotlin-reflect/2.1.0'),
    (CachedJar 'org.jetbrains.kotlin/kotlin-script-runtime/2.1.20'),
    (CachedJar 'org.jetbrains.intellij.deps/trove4j/1.0.20200330')) -join ';'
$classes = Join-Path $tempRoot 'build/app/tmp/kotlin-classes/release'
$json = Join-Path $tempRoot 'qa/workspace-presence/json-20240303.jar'
$android = Join-Path $tempRoot 'toolchains/android-sdk/platforms/android-35/android.jar'
$resources = Join-Path $tempRoot 'build/app/intermediates/compile_and_runtime_not_namespaced_r_class_jar/release/processReleaseResources/R.jar'
$api = @(Get-ChildItem -LiteralPath (Join-Path $tempRoot 'caches/gradle/caches/8.11.1/transforms') -Recurse -File -Filter '*api.jar' |
    Sort-Object FullName | Select-Object -ExpandProperty FullName)
$classpath = (@($stdlib, $coroutines, $annotations, $classes, $json, $android, $resources,
    (CachedJar 'com.squareup.okhttp3/okhttp/4.12.0'), (CachedJar 'com.squareup.okio/okio-jvm')) + $api) -join ';'
$sources = @('NotificationEventPolicy.kt', 'NativeNotificationMonitor.kt', 'NotificationPublisher.kt',
    'NotificationSettingsStore.kt', 'AndroidMessagesClient.kt', 'RemoteDshPersistence.kt',
    'DeveloperCenterClient.kt', 'FeedbackDiagnostics.kt', 'FeedbackImageRules.kt', 'FeedbackAttachmentStore.kt',
    'ArtifactDownloader.kt', 'ComponentDownloadClient.kt', 'NoticeStore.kt') |
    ForEach-Object { Join-Path $projectRoot "app/src/main/java/com/dafeiyu/mobile/data/$_" }
$sources += Join-Path $projectRoot 'tools/tests/StreamTestAtomicFile.kt'
$sources += Join-Path $PSScriptRoot 'NotificationChecks.kt'
$jar = Join-Path $runRoot 'notification-checks.jar'
$java = 'C:/Program Files/Java/JDK21/bin/java.exe'
& $java "-Djava.io.tmpdir=$runRoot" -Xmx384m -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -Werror -no-stdlib -no-reflect -jvm-target 17 "-Xfriend-paths=$classes" -classpath $classpath -d $jar @sources `
    *> (Join-Path $runRoot 'compile.log')
if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath (Join-Path $runRoot 'compile.log'); throw 'Notification production focused compilation failed' }
& $java "-Djava.io.tmpdir=$runRoot" -Xmx256m -cp "$jar;$classpath" com.dafeiyu.mobile.data.NotificationChecksKt `
    *> (Join-Path $runRoot 'checks.log')
Get-Content -LiteralPath (Join-Path $runRoot 'checks.log')
if ($LASTEXITCODE -ne 0) { throw 'Notification focused checks failed' }

# Run the actual publisher/settings sources against memory-only platform substitutes.
# The compilation above remains the real Android SDK/AndroidX Werror check; these
# substitutes exercise the permission race and retry boundary, not system delivery.
$publisherSources = @('NotificationPublisher.kt', 'NotificationSettingsStore.kt') |
    ForEach-Object { Join-Path $projectRoot "app/src/main/java/com/dafeiyu/mobile/data/$_" }
$publisherSources += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'publisher-fixture') -Filter '*.kt' -File |
    Sort-Object FullName | Select-Object -ExpandProperty FullName)
$publisherSources += Join-Path $PSScriptRoot 'NotificationPublisherChecks.kt'
$publisherJar = Join-Path $runRoot 'notification-publisher-checks.jar'
$publisherClasspath = @($jar, $stdlib, $annotations) -join ';'
& $java "-Djava.io.tmpdir=$runRoot" -Xmx256m -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -Werror -no-stdlib -no-reflect -jvm-target 17 -classpath $publisherClasspath -d $publisherJar @publisherSources `
    *> (Join-Path $runRoot 'publisher-compile.log')
if ($LASTEXITCODE -ne 0) {
    Get-Content -LiteralPath (Join-Path $runRoot 'publisher-compile.log')
    throw 'Notification publisher fixture compilation failed'
}
& $java "-Djava.io.tmpdir=$runRoot" -Xmx128m -cp "$publisherJar;$publisherClasspath" `
    com.dafeiyu.mobile.data.NotificationPublisherChecksKt *> (Join-Path $runRoot 'publisher-checks.log')
Get-Content -LiteralPath (Join-Path $runRoot 'publisher-checks.log')
if ($LASTEXITCODE -ne 0) { throw 'Notification publisher failure/retry checks failed' }
Write-Output "Focused production Werror and offline evidence: $runRoot"
