param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
$testRoot = Join-Path $tempRoot 'qa\android-artifact-smoke\compile'
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
$stdlib = Cached 'org.jetbrains.kotlin\kotlin-stdlib\2.1.20'
$coroutines = Cached 'org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm\1.9.0'
$annotations = Cached 'org.jetbrains\annotations'
$compilerClasspath = @((Cached 'org.jetbrains.kotlin\kotlin-compiler-embeddable\2.1.20'), $stdlib, $coroutines,
    $annotations, (Cached 'org.jetbrains.kotlin\kotlin-reflect'), (Cached 'org.jetbrains.intellij.deps\trove4j')) -join ';'
$classpath = @($stdlib, $coroutines, $annotations, (Cached 'com.squareup.okhttp3\okhttp\4.12.0'),
    (Cached 'com.squareup.okio\okio-jvm\3.6.0'), (Cached 'junit\junit\4.13.2'),
    (Join-Path $tempRoot 'qa\workspace-presence\json-20240303.jar'),
    (Join-Path $tempRoot 'toolchains\android-sdk\platforms\android-35\android.jar'),
    (AarClasses 'androidx.test.ext\junit\1.2.1' 'androidx-junit.jar'),
    (AarClasses 'androidx.test\monitor\1.7.2' 'androidx-monitor.jar'),
    (AarClasses 'androidx.test\runner\1.6.2' 'androidx-runner.jar')) -join ';'
$dataRoot = Join-Path $projectRoot 'app\src\main\java\com\dafeiyu\mobile\data'
$sources = @('ComponentDownloadClient.kt', 'ArtifactDownloader.kt') | ForEach-Object { Join-Path $dataRoot $_ }
$sources += Join-Path $projectRoot 'app\src\androidTest\java\com\dafeiyu\mobile\AndroidArtifactDownloadSmoke.kt'
$javaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\JDK21' }
& (Join-Path $javaRoot 'bin\java.exe') '-Xmx256m' "-Djava.io.tmpdir=$testRoot" -cp $compilerClasspath `
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -Werror -no-stdlib -no-reflect -jvm-target 17 -classpath $classpath `
    -d (Join-Path $testRoot 'android-artifact-smoke-android35.jar') @sources 2>&1 |
    Tee-Object -FilePath (Join-Path $testRoot 'compile.log')
if ($LASTEXITCODE -ne 0) { throw 'Strict production downloader and actual Android35 artifact instrumentation compilation failed' }
Write-Output 'PASS strict production downloader, real Android35/AndroidX and public artifact instrumentation compilation; no device or network test executed'
