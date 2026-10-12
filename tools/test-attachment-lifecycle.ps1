$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
$cacheRoot = Join-Path $tempRoot 'caches\gradle\caches\modules-2\files-2.1'
$testRoot = Join-Path $tempRoot ('qa\attachment-lifecycle\run-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
Write-Output "Attachment lifecycle test output: $testRoot"
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
$compilerClasspath = @($compiler, $stdlib, $coroutines, $annotations, $reflect, $trove) -join ';'
$jsonJar = Join-Path $tempRoot 'qa\workspace-presence\json-20240303.jar'
$androidJar = Join-Path $tempRoot 'toolchains\android-sdk\platforms\android-35\android.jar'
if (-not (Test-Path -LiteralPath $jsonJar)) { throw 'Missing cached JSON runtime' }
if (-not (Test-Path -LiteralPath $androidJar)) { throw 'Missing cached Android API' }
$classpath = @($stdlib, $coroutines, $annotations, $jsonJar) -join ';'
$dataRoot = Join-Path $projectRoot 'app\src\main\java\com\dafeiyu\mobile\data'
$fixtureRoot = Join-Path $PSScriptRoot 'tests\attachment-lifecycle'
$repository = [IO.File]::ReadAllText((Join-Path $dataRoot 'DemoRepository.kt'))
function Extract-Section([string]$Start, [string]$End) {
    $startIndex = $repository.IndexOf($Start, [StringComparison]::Ordinal)
    if ($startIndex -lt 0) { throw "Missing source marker: $Start" }
    $endIndex = $repository.IndexOf($End, $startIndex, [StringComparison]::Ordinal)
    if ($endIndex -lt 0) { throw "Missing source marker: $End" }
    return $repository.Substring($startIndex, $endIndex - $startIndex)
}
# Compile the current production method bodies, not handwritten behavioral copies.
$model = Extract-Section 'data class AttachmentPreview(' 'data class ExtensionItem('
$fields = Extract-Section '    private val importedAttachments =' '    private var settingsPreferences:'
$lists = Extract-Section '    private val liteAttachments =' '    val messages: SnapshotStateList'
$methods = Extract-Section '    fun toggleAttachment(' '    fun exportResponse('
$removal = Extract-Section '    fun removeAttachment(' '    fun selectMode('
$header = [IO.File]::ReadAllText((Join-Path $fixtureRoot 'HarnessHeader.kt.txt'))
$generated = Join-Path $testRoot 'AttachmentLifecycleSubject.kt'
[IO.File]::WriteAllText($generated, $header + "`n" + $fields + $lists + $methods + $removal + "`n}`n" + $model)
$sources = @('ConversationAttachment.kt', 'ConversationAttachmentStore.kt') |
    ForEach-Object { Join-Path $dataRoot $_ }
$sources += @($generated, (Join-Path $fixtureRoot 'LifecycleDependencies.kt'))
$java = Join-Path $javaRoot 'bin\java.exe'
& $java -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -Werror -classpath "$classpath;$androidJar" `
    -d (Join-Path $testRoot 'lifecycle-android-api.jar') @sources
if ($LASTEXITCODE -ne 0) { throw 'Lifecycle Android API compilation failed' }
Write-Output 'PASS actual attachment methods Android API compilation'
$existingFixtures = Join-Path $PSScriptRoot 'tests\conversation-attachments'
$sources += @('TestContext.kt', 'TestCursor.kt', 'TestOpenableColumns.kt') |
    ForEach-Object { Join-Path $existingFixtures $_ }
$sources += @((Join-Path $fixtureRoot 'TestUri.kt'), (Join-Path $fixtureRoot 'AttachmentLifecycleChecks.kt'))
$outputJar = Join-Path $testRoot 'attachment-lifecycle-checks.jar'
& $java -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -Werror -classpath $classpath -d $outputJar @sources
if ($LASTEXITCODE -ne 0) { throw 'Lifecycle fixture compilation failed' }
& $java -cp "$outputJar;$classpath" com.dafeiyu.mobile.data.AttachmentLifecycleChecksKt (Join-Path $testRoot 'fixtures') |
    Tee-Object -FilePath (Join-Path $testRoot 'fixture-results.log')
if ($LASTEXITCODE -ne 0) { throw 'Attachment lifecycle checks failed' }
