$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
$cacheRoot = Join-Path $tempRoot 'caches\gradle\caches\modules-2\files-2.1'
$testRoot = Join-Path $tempRoot ('qa\official-attachment-preflight\run-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
Write-Output "Official attachment preflight output: $testRoot"
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
foreach ($dependency in @($jsonJar, $androidJar)) {
    if (-not (Test-Path -LiteralPath $dependency)) { throw "Missing cached dependency: $dependency" }
}
$classpath = @($stdlib, $coroutines, $annotations, $jsonJar, $androidJar) -join ';'
$dataRoot = Join-Path $projectRoot 'app\src\main\java\com\dafeiyu\mobile\data'
$repository = [IO.File]::ReadAllText((Join-Path $dataRoot 'DemoRepository.kt'))
function Extract-Section([string]$Start, [string]$End) {
    $startIndex = $repository.IndexOf($Start, [StringComparison]::Ordinal)
    if ($startIndex -lt 0) { throw "Missing source marker: $Start" }
    $endIndex = $repository.IndexOf($End, $startIndex, [StringComparison]::Ordinal)
    if ($endIndex -lt 0) { throw "Missing source marker: $End" }
    return $repository.Substring($startIndex, $endIndex - $startIndex)
}
$models = Extract-Section 'data class ReasoningOption(' '/** Imported files'
$gate = Extract-Section '    private fun supportsImages(' '    fun refreshDeepSeekModels('
$imageTest = Extract-Section '    private fun isImageAttachment(' '    private fun sendDeepSeekMessage('
$preflightStart = $repository.IndexOf('        var preparedHistory:', [StringComparison]::Ordinal)
$preflight = Extract-Section '            if (targetMode == WorkspaceMode.Lite) {' '            saveJob?.cancelAndJoin()'
if ($repository.IndexOf('            if (targetMode == WorkspaceMode.Lite) {', [StringComparison]::Ordinal) -lt $preflightStart) {
    throw 'Preflight source marker unexpectedly matched a different method'
}
# Production statements are extracted verbatim; no save/draft-clearing code is substituted.
$fixtureRoot = Join-Path $PSScriptRoot 'tests\official-attachment-preflight'
$header = [IO.File]::ReadAllText((Join-Path $fixtureRoot 'HarnessHeader.kt.txt'))
$tail = @'
        result = preparedHistory
        return true
    }
}
'@
$generated = Join-Path $testRoot 'PreflightSubject.kt'
[IO.File]::WriteAllText($generated, $header + $gate + $imageTest +
    "`n    suspend fun preflight(candidate: ChatMessage, sentModel: ModelChoice): Boolean {`n" +
    "        val conversationId = `"conversation`"`n" +
    "        val targetMode = WorkspaceMode.Lite`n" +
    "        val sentReasoning: ReasoningOption? = null`n" +
    "        var preparedHistory: List<DeepSeekChatClient.HistoryMessage> = emptyList()`n" +
    $preflight + $tail + "`n" + $models)
$sources = @('AssistantStreamProtocol.kt', 'BalanceStore.kt', 'DeepSeekChatProtocol.kt', 'DeepSeekChatClient.kt',
    'DeepSeekThinkingPolicy.kt', 'DeepSeekModelCatalog.kt',
    'BackgroundTaskModels.kt', 'BackgroundTaskEngine.kt', 'BackgroundTaskJson.kt',
    'OfficialToolSession.kt', 'OfficialToolDispatcher.kt', 'OfficialToolContract.kt', 'OfficialToolOutputStore.kt',
    'OfficialWeatherClient.kt', 'OfficialChatHistory.kt', 'ChatMessage.kt',
    'ConversationAttachment.kt', 'ConversationAttachmentStore.kt') |
    ForEach-Object { Join-Path $dataRoot $_ }
$sources += $generated
$java = Join-Path $javaRoot 'bin\java.exe'
& $java -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -Werror -classpath $classpath `
    -d (Join-Path $testRoot 'preflight-android-api.jar') @sources
if ($LASTEXITCODE -ne 0) { throw 'Production preflight/client/store Android API compilation failed' }
Write-Output 'PASS actual preflight/client/store Android API compilation'
$storeFixtureRoot = Join-Path $PSScriptRoot 'tests\conversation-attachments'
$sources += @('TestContext.kt', 'TestCursor.kt', 'TestOpenableColumns.kt', 'TestUri.kt') |
    ForEach-Object { Join-Path $storeFixtureRoot $_ }
$sources += Join-Path $fixtureRoot 'PreflightChecks.kt'
$outputJar = Join-Path $testRoot 'official-attachment-preflight-checks.jar'
& $java -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -Werror -classpath $classpath -d $outputJar @sources
if ($LASTEXITCODE -ne 0) { throw 'Official attachment preflight fixture compilation failed' }
& $java -cp "$outputJar;$classpath" com.dafeiyu.mobile.data.PreflightChecksKt `
    (Join-Path $testRoot 'fixtures') | Tee-Object -FilePath (Join-Path $testRoot 'fixture-results.log')
if ($LASTEXITCODE -ne 0) { throw 'Official attachment preflight checks failed' }
