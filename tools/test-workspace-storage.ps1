$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
$cacheRoot = Join-Path $tempRoot 'caches\gradle\caches\modules-2\files-2.1'
$testRoot = Join-Path $tempRoot 'qa\workspace-storage'
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$javaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\JDK21' }

function Find-CachedJar([string]$RelativeDirectory) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $cacheRoot $RelativeDirectory) -Recurse -File -Filter '*.jar' |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw "Missing cached dependency: $RelativeDirectory" }
    return $jar
}

$compiler = Find-CachedJar 'org.jetbrains.kotlin\kotlin-compiler-embeddable\2.1.20'
$stdlib = Find-CachedJar 'org.jetbrains.kotlin\kotlin-stdlib\2.1.20'
$coroutines = Find-CachedJar 'org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm\1.9.0'
$annotations = Find-CachedJar 'org.jetbrains\annotations'
$reflect = Find-CachedJar 'org.jetbrains.kotlin\kotlin-reflect'
$trove = Find-CachedJar 'org.jetbrains.intellij.deps\trove4j'
$json = Join-Path $tempRoot 'qa\workspace-presence\json-20240303.jar'
if (-not (Test-Path -LiteralPath $json)) { throw 'Missing offline org.json runtime' }

$compilerClasspath = @($compiler, $stdlib, $coroutines, $annotations, $reflect, $trove) -join ';'
$classpath = @($stdlib, $annotations, $json) -join ';'
$dataRoot = Join-Path $projectRoot 'app\src\main\java\com\dafeiyu\mobile\data'
$sources = @(
    'AssistantStreamProtocol.kt',
    'AssistantStreamSnapshotJson.kt',
    'WorkspaceStore.kt',
    'ChatMessage.kt'
) | ForEach-Object { Join-Path $dataRoot $_ }
$sources += @(
    'WorkspaceStorageChecks.kt',
    'WorkspaceStorageTestContext.kt',
    'WorkspaceStorageTestAtomicFile.kt'
) | ForEach-Object { Join-Path $PSScriptRoot "tests\workspace-storage\$_" }

$outputJar = Join-Path $testRoot 'workspace-storage-checks.jar'
& (Join-Path $javaRoot 'bin\java.exe') -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -classpath $classpath -d $outputJar @sources
if ($LASTEXITCODE -ne 0) { throw 'Workspace storage checks compilation failed' }

$runRoot = Join-Path $testRoot ('run-' + [Guid]::NewGuid().ToString('N'))
& (Join-Path $javaRoot 'bin\java.exe') -cp "$outputJar;$classpath" `
    com.dafeiyu.mobile.data.WorkspaceStorageChecksKt $runRoot
if ($LASTEXITCODE -ne 0) { throw 'Workspace storage checks failed' }

