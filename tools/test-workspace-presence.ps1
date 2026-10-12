param([switch]$ProbeLive)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
$cacheRoot = Join-Path $tempRoot 'caches\gradle\caches\modules-2\files-2.1'
$testRoot = Join-Path $tempRoot 'qa\workspace-presence'
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$javaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\JDK21' }
$jsonJar = Join-Path $testRoot 'json-20240303.jar'
if (-not (Test-Path -LiteralPath $jsonJar)) {
    Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar' -OutFile $jsonJar
}
function Find-CachedJar([string]$RelativeDirectory, [string]$Pattern) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $cacheRoot $RelativeDirectory) -Recurse -File -Filter $Pattern |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw "Missing cached dependency: $RelativeDirectory/$Pattern" }
    return $jar
}
$compiler = Find-CachedJar 'org.jetbrains.kotlin\kotlin-compiler-embeddable\2.1.20' '*.jar'
$stdlib = Find-CachedJar 'org.jetbrains.kotlin\kotlin-stdlib\2.1.20' '*.jar'
$coroutines = Find-CachedJar 'org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm\1.9.0' '*.jar'
$annotations = Find-CachedJar 'org.jetbrains\annotations' '*.jar'
$reflect = Find-CachedJar 'org.jetbrains.kotlin\kotlin-reflect' '*.jar'
$trove = Find-CachedJar 'org.jetbrains.intellij.deps\trove4j' '*.jar'
$androidJar = Join-Path $tempRoot 'toolchains\android-sdk\platforms\android-35\android.jar'
$compileClasspath = @($stdlib, $coroutines, $annotations, $jsonJar, $androidJar) -join ';'
$compilerClasspath = @($compiler, $stdlib, $coroutines, $annotations, $reflect, $trove) -join ';'
$outputJar = Join-Path $testRoot 'workspace-presence-checks.jar'
& (Join-Path $javaRoot 'bin\java.exe') -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -classpath $compileClasspath -d $outputJar `
    (Join-Path $projectRoot 'app\src\main\java\com\dafeiyu\mobile\data\DeveloperCenterPresenceClient.kt') `
    (Join-Path $PSScriptRoot 'tests\WorkspacePresenceChecks.kt')
if ($LASTEXITCODE -ne 0) { throw 'Presence checks compilation failed' }
[string[]]$testArgs = @(if ($ProbeLive) { '--live' })
& (Join-Path $javaRoot 'bin\java.exe') "-Djdk.net.unixdomain.tmpdir=$testRoot" --add-modules jdk.httpserver -cp "$outputJar;$compileClasspath" `
    com.dafeiyu.mobile.data.WorkspacePresenceChecksKt @testArgs
if ($LASTEXITCODE -ne 0) { throw 'Presence checks failed' }
