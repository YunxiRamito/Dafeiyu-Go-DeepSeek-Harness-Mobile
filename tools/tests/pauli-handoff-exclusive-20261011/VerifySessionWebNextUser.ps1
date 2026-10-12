param(
    [string]$WebRunName = 'run-0fa326b39af041c9ac9cf3ffa2a286bb'
)
$ErrorActionPreference = 'Stop'
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$temp = [IO.Path]::GetFullPath((Join-Path $project '../Temp_Android'))
if ($WebRunName -notmatch '^run-[a-f0-9]{32}$') { throw 'Invalid offline evidence run name' }
$webJar = Join-Path $temp "qa/official-web-focused/$WebRunName/official-web-focused.jar"
$webLog = Join-Path $temp "qa/official-web-focused/$WebRunName/fixture-results.log"
if (-not (Test-Path -LiteralPath $webJar)) { throw 'Run the offline official-web-focused runner first' }
if (-not (Select-String -LiteralPath $webLog -SimpleMatch 'RESULT 27 PASS 0 FAIL')) {
    throw 'The selected offline web run did not pass'
}
$cache = Join-Path $temp 'caches/gradle/caches/modules-2/files-2.1'
function CachedJar([string]$Relative) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $cache $Relative) -Recurse -File -Filter '*.jar' |
        Sort-Object FullName | Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw "Missing cached dependency: $Relative" }
    return $jar
}
$javaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\JDK21' }
$java = Join-Path $javaRoot 'bin/java.exe'
$compiler = CachedJar 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.20'
$stdlib = CachedJar 'org.jetbrains.kotlin/kotlin-stdlib/2.1.20'
$coroutines = CachedJar 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0'
$annotations = CachedJar 'org.jetbrains/annotations'
$reflect = CachedJar 'org.jetbrains.kotlin/kotlin-reflect'
$trove = CachedJar 'org.jetbrains.intellij.deps/trove4j'
$okhttp = CachedJar 'com.squareup.okhttp3/okhttp/4.12.0'
$okio = CachedJar 'com.squareup.okio/okio-jvm/3.6.0'
$jsoup = CachedJar 'org.jsoup/jsoup/1.18.3'
$json = Join-Path $temp 'qa/workspace-presence/json-20240303.jar'
$compilerClasspath = @($compiler, $stdlib, $coroutines, $annotations, $reflect, $trove) -join ';'
$classpath = @($webJar, $stdlib, $coroutines, $annotations, $okhttp, $okio, $jsoup, $json) -join ';'
$root = Join-Path $temp ('qa/pauli-handoff-exclusive-20261011/run-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root -Force | Out-Null
$jar = Join-Path $root 'next-user-checks.jar'
$sources = @(
    (Join-Path $PSScriptRoot 'SessionWebNextUserChecks.kt'),
    (Join-Path $project 'app/src/main/java/com/dafeiyu/mobile/ui/AssistantActivitySummary.kt')
)
Write-Output "Exclusive offline next-user/UI evidence: $root"
& $java -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -Werror "-Xfriend-paths=$webJar" `
    -classpath $classpath -d $jar @sources 2>&1 |
    Tee-Object -FilePath (Join-Path $root 'compile.log')
if ($LASTEXITCODE -ne 0) { throw 'Exclusive next-user checks compilation failed' }
& $java -cp "$jar;$classpath" com.dafeiyu.mobile.data.SessionWebNextUserChecksKt `
    (Join-Path $root 'fixtures') 2>&1 |
    Tee-Object -FilePath (Join-Path $root 'fixture-results.log')
if ($LASTEXITCODE -ne 0) { throw 'Exclusive next-user checks failed' }
[ordered]@{
    checkedAt = [DateTimeOffset]::Now.ToString('o')
    webRunName = $WebRunName
    webJarSha256 = (Get-FileHash -LiteralPath $webJar).Hash
    sourceHashes = @($sources | ForEach-Object {
        [ordered]@{ path = $_; sha256 = (Get-FileHash -LiteralPath $_).Hash }
    })
    adbCalled = $false
    networkCalled = $false
    modelCalled = $false
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $root 'evidence.json') -Encoding utf8
