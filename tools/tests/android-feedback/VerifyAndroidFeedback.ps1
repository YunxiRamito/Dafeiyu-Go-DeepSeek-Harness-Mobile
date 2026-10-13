param([ValidateRange(256, 768)][int]$MaxHeapMiB = 512)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '../Temp_Android'))
$cacheRoot = Join-Path $tempRoot 'caches/gradle/caches'
$moduleRoot = Join-Path $cacheRoot 'modules-2/files-2.1'
$testRoot = Join-Path $tempRoot ('qa/android-feedback/run-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$javaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:/Program Files/Java/JDK21' }
function Find-CachedJar([string]$RelativeDirectory) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $moduleRoot $RelativeDirectory) -Recurse -File -Filter '*.jar' |
        Sort-Object FullName | Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw "Missing offline dependency: $RelativeDirectory" }
    return $jar
}
$stdlib = Find-CachedJar 'org.jetbrains.kotlin/kotlin-stdlib/2.1.20'
$annotations = Find-CachedJar 'org.jetbrains/annotations'
$coroutines = Find-CachedJar 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0'
$json = Join-Path $tempRoot 'qa/workspace-presence/json-20240303.jar'
$compiledProject = Join-Path $tempRoot 'build/app/tmp/kotlin-classes/release'
if (-not (Test-Path -LiteralPath (Join-Path $compiledProject 'com/dafeiyu/mobile/data/NoticeStore.class'))) {
    $compiledProject = Join-Path $tempRoot 'build/app/tmp/kotlin-classes/debug'
}
$androidJar = Join-Path $tempRoot 'toolchains/android-sdk/platforms/android-35/android.jar'
$compilerClasspath = @(
    (Find-CachedJar 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.20'),
    $stdlib, $annotations, $coroutines,
    (Find-CachedJar 'org.jetbrains.kotlin/kotlin-reflect'),
    (Find-CachedJar 'org.jetbrains.intellij.deps/trove4j')
) -join ';'
$dependencies = @($stdlib, $annotations, $coroutines, $json, $compiledProject, $androidJar,
    (Find-CachedJar 'com.squareup.okhttp3/okhttp/4.12.0'), (Find-CachedJar 'com.squareup.okio/okio-jvm'))
$classpath = $dependencies -join ';'
$dataRoot = Join-Path $projectRoot 'app/src/main/java/com/dafeiyu/mobile/data'
$sources = @('DeveloperCenterClient.kt', 'FeedbackDiagnostics.kt', 'FeedbackImageRules.kt', 'FeedbackAttachmentStore.kt',
    'ArtifactDownloader.kt', 'ComponentDownloadClient.kt', 'NoticeStore.kt') |
    ForEach-Object { Join-Path $dataRoot $_ }
$sources += Join-Path $PSScriptRoot 'AndroidFeedbackChecks.kt'
$jar = Join-Path $testRoot 'android-feedback.jar'
$java = Join-Path $javaRoot 'bin/java.exe'
Write-Output "Android feedback HTTP fixture output: $testRoot"
& $java "-Xmx${MaxHeapMiB}m" "-Djava.io.tmpdir=$testRoot" -cp $compilerClasspath `
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -Werror `
    "-Xfriend-paths=$compiledProject" -classpath $classpath -d $jar @sources 2>&1 |
    Tee-Object -FilePath (Join-Path $testRoot 'compile.log')
if ($LASTEXITCODE -ne 0) { throw 'Android feedback production helper compilation failed' }
& $java "-Xmx${MaxHeapMiB}m" "-Djava.io.tmpdir=$testRoot" "-Djdk.net.unixdomain.tmpdir=$tempRoot" --add-modules jdk.httpserver -cp "$jar;$classpath" `
    com.dafeiyu.mobile.data.AndroidFeedbackChecksKt (Join-Path $testRoot 'fixtures') 2>&1 |
    Tee-Object -FilePath (Join-Path $testRoot 'results.log')
if ($LASTEXITCODE -ne 0) { throw 'Android feedback HTTP checks failed' }
Write-Output 'No public-server mutations, TLS fixture bypass in production, Gradle build, ADB or device UI validation.'
