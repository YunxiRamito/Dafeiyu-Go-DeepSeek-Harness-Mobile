param(
    [ValidateSet('Delete', 'Storage', 'All')][string]$Stage = 'Delete',
    [ValidateRange(192, 1024)][int]$MaxHeapMiB = 384
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
$cacheRoot = Join-Path $tempRoot 'caches\gradle\caches\modules-2\files-2.1'
$testRoot = Join-Path $tempRoot ('qa\workspace-delete\run-' + [Guid]::NewGuid().ToString('N'))
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
$gson = Find-CachedJar 'com.google.code.gson\gson'
$compilerClasspath = @($compiler, $stdlib, $coroutines, $annotations, $reflect, $trove) -join ';'
$classpath = @($stdlib, $coroutines, $annotations, $json, $gson) -join ';'
$dataRoot = Join-Path $projectRoot 'app\src\main\java\com\dafeiyu\mobile\data'
$fixtures = Join-Path $projectRoot 'tools\tests\workspace-storage'
$repository = [IO.File]::ReadAllText((Join-Path $dataRoot 'DemoRepository.kt'))
function Extract-Section([string]$Start, [string]$End) {
    $from = $repository.IndexOf($Start, [StringComparison]::Ordinal)
    if ($from -lt 0) { throw "Missing production marker: $Start" }
    $to = $repository.IndexOf($End, $from, [StringComparison]::Ordinal)
    if ($to -lt 0) { throw "Missing production marker: $End" }
    return $repository.Substring($from, $to - $from)
}
$body = (Extract-Section '    fun updateComposerDraft(' '    /** Archive a conversation') +
    (Extract-Section '    fun deleteArchivedConversation(' '    /** Remove archives whose retention') +
    (Extract-Section '    private fun createEmptyConversation(' '    fun createWorkspace(') +
    (Extract-Section '    private fun workspaceSnapshot()' '    fun selectModel(')
$body = $body.Replace('Dispatchers.Main.immediate', 'fixtureMain')
$generated = Join-Path $testRoot 'WorkspaceDeleteSubject.kt'
$header = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'WorkspaceDeleteHarness.kt.txt'))
[IO.File]::WriteAllText($generated, $header + $body + "`n}`n")
$sources = @('AssistantStreamProtocol.kt', 'AssistantStreamSnapshotJson.kt', 'WorkspaceStore.kt',
    'WorkspaceDeletePolicy.kt', 'ChatMessage.kt') | ForEach-Object { Join-Path $dataRoot $_ }
$sources += @('WorkspaceStorageTestContext.kt', 'WorkspaceStorageTestAtomicFile.kt') |
    ForEach-Object { Join-Path $fixtures $_ }
$sources += Join-Path $PSScriptRoot 'WorkspaceDeleteJsonReaderFixture.kt'
$sources += $generated, (Join-Path $PSScriptRoot 'WorkspaceDeleteChecks.kt')
$jar = Join-Path $testRoot 'workspace-delete.jar'
$java = Join-Path $javaRoot 'bin\java.exe'
Write-Output "Workspace delete QA output: $testRoot"
if ($Stage -in @('Delete', 'All')) {
& $java "-Xmx${MaxHeapMiB}m" -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -Werror -classpath $classpath -d $jar @sources 2>&1 |
    Tee-Object -FilePath (Join-Path $testRoot 'compile.log')
if ($LASTEXITCODE -ne 0) { throw 'Workspace delete production fixture compilation failed' }
& $java "-Xmx${MaxHeapMiB}m" -cp "$jar;$classpath" com.dafeiyu.mobile.data.WorkspaceDeleteChecksKt (Join-Path $testRoot 'fixtures') |
    Tee-Object -FilePath (Join-Path $testRoot 'results.log')
if ($LASTEXITCODE -ne 0) { throw 'Workspace delete checks failed' }
}

# Existing checks expecting default resurrection are superseded by the new deletion contract.
# Run all remaining storage checks unchanged in an isolated, generated fixture.
if ($Stage -in @('Storage', 'All')) {
$storageChecks = [IO.File]::ReadAllText((Join-Path $fixtures 'WorkspaceStorageChecks.kt'))
$legacyStart = $storageChecks.IndexOf('    test("workspace load examines first 200 positions without recreating an absent default")')
$legacyEnd = $storageChecks.IndexOf('    test("attachment and collection limits are enforced during load")', $legacyStart)
if ($legacyStart -lt 0 -or $legacyEnd -lt 0) { throw 'Missing superseded default restoration check marker' }
$storageChecks = $storageChecks.Remove($legacyStart, $legacyEnd - $legacyStart)
$storageGenerated = Join-Path $testRoot 'WorkspaceStorageChecks.kt'
[IO.File]::WriteAllText($storageGenerated, $storageChecks)
$storageSources = $sources | Where-Object { $_ -ne $generated -and $_ -notlike '*WorkspaceDeleteChecks.kt' }
$storageJar = Join-Path $testRoot 'workspace-storage.jar'
& $java "-Xmx${MaxHeapMiB}m" -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -Werror -classpath $classpath -d $storageJar @storageSources $storageGenerated 2>&1 |
    Tee-Object -FilePath (Join-Path $testRoot 'storage-compile.log')
if ($LASTEXITCODE -ne 0) { throw 'Storage regression fixture compilation failed' }
& $java "-Xmx${MaxHeapMiB}m" -cp "$storageJar;$classpath" com.dafeiyu.mobile.data.WorkspaceStorageChecksKt (Join-Path $testRoot 'storage') |
    Tee-Object -FilePath (Join-Path $testRoot 'storage-results.log')
if ($LASTEXITCODE -ne 0) { throw 'Storage regression checks failed' }
}
