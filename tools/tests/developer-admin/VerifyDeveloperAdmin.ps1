param()
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '../Temp_Android'))
$testRoot = Join-Path $tempRoot ('qa/developer-admin/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$env:TEMP = $testRoot
$env:TMP = $testRoot
$cacheRoot = Join-Path $tempRoot 'caches/gradle/caches/modules-2/files-2.1'
function Cached([string]$relative) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $cacheRoot $relative) -Recurse -File -Filter '*.jar' |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw "Missing cached dependency $relative" }
    return $jar
}
$stdlib = Cached 'org.jetbrains.kotlin/kotlin-stdlib/2.1.20'
$coroutines = Cached 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0'
$annotations = Cached 'org.jetbrains/annotations'
$compiler = @((Cached 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.20'), $stdlib, $coroutines, $annotations,
    (Cached 'org.jetbrains.kotlin/kotlin-reflect'), (Cached 'org.jetbrains.intellij.deps/trove4j')) -join ';'
$compiled = Join-Path $tempRoot 'build/app/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar'
$classpath = @($compiled, $stdlib, $coroutines, $annotations,
    (Join-Path $tempRoot 'qa/workspace-presence/json-20240303.jar'),
    (Join-Path $tempRoot 'toolchains/android-sdk/platforms/android-35/android.jar'),
    (Cached 'com.squareup.okhttp3/okhttp/4.12.0'), (Cached 'com.squareup.okio/okio-jvm')) -join ';'
$javaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:/Program Files/Java/JDK21' }
$jar = Join-Path $testRoot 'checks.jar'
$data = Join-Path $projectRoot 'app/src/main/java/com/dafeiyu/mobile/data'
$sources = @('DeveloperAdminClient.kt', 'MobileRecommendationAdmin.kt', 'ComponentDownloadClient.kt') | ForEach-Object { Join-Path $data $_ }
$sources += Join-Path $PSScriptRoot 'DeveloperAdminChecks.kt'
& (Join-Path $javaRoot 'bin/java.exe') '-Xmx256m' "-Djava.io.tmpdir=$testRoot" -cp $compiler `
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -Werror -no-stdlib -no-reflect -jvm-target 17 `
    "-Xfriend-paths=$compiled" -classpath $classpath -d $jar @sources *> (Join-Path $testRoot 'compile.log')
if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath (Join-Path $testRoot 'compile.log'); throw 'Developer admin strict compile failed' }
& (Join-Path $javaRoot 'bin/java.exe') '-Xmx256m' "-Djava.io.tmpdir=$testRoot" -cp ($jar + ';' + $classpath) `
    com.dafeiyu.mobile.data.DeveloperAdminChecksKt *> (Join-Path $testRoot 'checks.log')
Get-Content -LiteralPath (Join-Path $testRoot 'checks.log')
if ($LASTEXITCODE -ne 0) { throw 'Developer admin behavior checks failed' }
Write-Output "PASS strict production admin/recommendation compilation; evidence $testRoot"
