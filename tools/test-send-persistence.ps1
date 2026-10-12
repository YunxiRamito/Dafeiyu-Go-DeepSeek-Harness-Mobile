$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
$cacheRoot = Join-Path $tempRoot 'caches\gradle\caches\modules-2\files-2.1'
$testRoot = Join-Path $tempRoot ('qa\send-persistence\run-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$javaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\JDK21' }
function Find-CachedJar([string]$RelativeDirectory) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $cacheRoot $RelativeDirectory) -Recurse -File -Filter '*.jar' |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw "Missing offline dependency: $RelativeDirectory" }
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
$classpath = @($stdlib, $coroutines, $annotations, $json) -join ';'
$dataRoot = Join-Path $projectRoot 'app\src\main\java\com\dafeiyu\mobile\data'
$fixtures = Join-Path $PSScriptRoot 'tests\workspace-storage'
$repository = [IO.File]::ReadAllText((Join-Path $dataRoot 'DemoRepository.kt'))
function Extract-Section([string]$Start, [string]$End) {
    $from = $repository.IndexOf($Start, [StringComparison]::Ordinal)
    if ($from -lt 0) { throw "Missing production marker: $Start" }
    $to = $repository.IndexOf($End, $from, [StringComparison]::Ordinal)
    if ($to -lt 0) { throw "Missing production marker: $End" }
    return $repository.Substring($from, $to - $from)
}
$persistence = Extract-Section '    private fun workspaceSnapshot()' '    fun selectModel('
$precommit = Extract-Section '    suspend fun sendMessage(text: String)' '        val id = nextMessageId++'
# Main is replaced only by a real dedicated JVM dispatcher. IO, Mutex, cancellation,
# atomic saves, and all precommit branches execute the actual production source.
$body = ($persistence + $precommit).Replace('Dispatchers.Main.immediate', 'fixtureMain')
$generated = Join-Path $testRoot 'SendPersistenceSubject.kt'
$header = [IO.File]::ReadAllText((Join-Path $fixtures 'SendPersistenceHarness.kt.txt'))
[IO.File]::WriteAllText($generated, $header + $body + "`n        return true`n    }`n}`n")
$sources = @('AssistantStreamProtocol.kt', 'AssistantStreamSnapshotJson.kt', 'WorkspaceStore.kt', 'ChatMessage.kt') |
    ForEach-Object { Join-Path $dataRoot $_ }
$sources += @('WorkspaceStorageTestContext.kt', 'WorkspaceStorageTestAtomicFile.kt', 'SendPersistenceChecks.kt') |
    ForEach-Object { Join-Path $fixtures $_ }
$sources += $generated
$jar = Join-Path $testRoot 'send-persistence.jar'
$java = Join-Path $javaRoot 'bin\java.exe'
& $java -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -classpath $classpath -d $jar @sources
if ($LASTEXITCODE -ne 0) { throw 'Production send transaction fixture compilation failed' }
& $java -cp "$jar;$classpath" com.dafeiyu.mobile.data.SendPersistenceChecksKt (Join-Path $testRoot 'fixtures')
if ($LASTEXITCODE -ne 0) { throw 'Send persistence checks failed' }
