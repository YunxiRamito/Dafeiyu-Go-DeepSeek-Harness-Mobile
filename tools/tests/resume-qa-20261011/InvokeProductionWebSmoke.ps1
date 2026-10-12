#requires -Version 7.0
[CmdletBinding()]
param([switch]$CompileWhenScheduled, [switch]$LiveHost)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'QaCommon.ps1')
if (-not $CompileWhenScheduled) {
    Write-Output 'DEFERRED: no JVM/Kotlin/Gradle started. Main agent must schedule memory and explicitly use -CompileWhenScheduled.'
    return
}
$project = Get-QaProject
$temp = [IO.Path]::GetFullPath((Join-Path $project '../Temp_Android'))
$cache = Join-Path $temp 'caches/gradle/caches/modules-2/files-2.1'
function Jar([string]$Relative) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $cache $Relative) -Recurse -File -Filter '*.jar' |
        Sort-Object FullName | Select-Object -First 1 -ExpandProperty FullName
    Assert-Qa ($null -ne $jar) "Missing cached dependency: $Relative; downloads forbidden"
    return $jar
}
$java = 'C:\Program Files\Java\JDK21\bin\java.exe'
$stdlib = Jar 'org.jetbrains.kotlin/kotlin-stdlib/2.1.20'
$coroutines = Jar 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0'
$annotations = Jar 'org.jetbrains/annotations'
$compiler = @(Jar 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.20'; $stdlib; $coroutines; $annotations
    Jar 'org.jetbrains.kotlin/kotlin-reflect'; Jar 'org.jetbrains.intellij.deps/trove4j') -join ';'
$deps = @($stdlib; $coroutines; $annotations
    Jar 'com.squareup.okhttp3/okhttp/4.12.0'; Jar 'com.squareup.okio/okio-jvm/3.6.0'
    Jar 'org.jsoup/jsoup/1.18.3'; (Join-Path $temp 'qa/workspace-presence/json-20240303.jar'))
$android = Join-Path $temp 'toolchains/android-sdk/platforms/android-35/android.jar'
$data = Join-Path $project 'app/src/main/java/com/dafeiyu/mobile/data'
$sources = @('BackgroundTaskModels.kt', 'OfficialToolContract.kt', 'OfficialWebClient.kt') |
    ForEach-Object { Join-Path $data $_ }
$sources += Join-Path $PSScriptRoot 'ProductionWebSmoke.kt'
$hashes = @($sources | ForEach-Object { @{ path = $_; sha256 = (Get-FileHash -LiteralPath $_).Hash } })
$root = Assert-QaOutputPath (Join-Path $PSScriptRoot ('runs/production-web-' + [Guid]::NewGuid().ToString('N')))
New-Item -ItemType Directory -Path $root -Force | Out-Null
$jar = Join-Path $root 'smoke.jar'
foreach ($path in @($java, $android) + $deps) { Assert-Qa (Test-Path -LiteralPath $path -PathType Leaf) "Cached file missing: $path" }
$oldTemp = $env:TEMP
$oldTmp = $env:TMP
try {
    $env:TEMP = $root
    $env:TMP = $root
    & $java "-Djava.io.tmpdir=$root" -Xmx256m -Xss512k -XX:MaxMetaspaceSize=128m `
        -XX:MaxDirectMemorySize=32m -XX:ReservedCodeCacheSize=32m -cp $compiler `
        org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -Werror -no-stdlib -no-reflect -jvm-target 17 `
        -classpath ((@($android) + $deps) -join ';') -d $jar @sources 2>&1 |
        Tee-Object -FilePath (Join-Path $root 'compile.log')
    Assert-Qa ($LASTEXITCODE -eq 0) 'Production focused compilation failed; no retry or parallel JVM'
    $mode = if ($LiveHost) { '--live-host' } else { '--offline' }
    & $java "-Djava.io.tmpdir=$root" -Xmx96m -XX:MaxMetaspaceSize=96m -cp ((@($jar) + $deps) -join ';') `
        com.dafeiyu.mobile.data.ProductionWebSmokeKt $mode 2>&1 |
        Tee-Object -FilePath (Join-Path $root 'result.log')
    $runnerExit = $LASTEXITCODE
    foreach ($source in $hashes) {
        Assert-Qa ((Get-FileHash -LiteralPath $source.path).Hash -ceq $source.sha256) 'Source changed during focused smoke; evidence stale'
    }
    [ordered]@{
        sources = $hashes; dependencies = @($deps | ForEach-Object { @{ path = $_; sha256 = (Get-FileHash -LiteralPath $_).Hash } })
        environment = 'host JVM; production transport; no APK UID'; mode = $mode
        model_called = $false; runner_exit = $runnerExit; source_version_binding = 'source hash only; final APK is separate'
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $root 'evidence.json') -Encoding utf8NoBOM
    Assert-Qa ($runnerExit -eq 0) "Production focused smoke failed: $root/result.log"
} finally {
    $env:TEMP = $oldTemp
    $env:TMP = $oldTmp
}
Write-Output "PASS focused host production smoke: $root; no APK UID/model/device"
