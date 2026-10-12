$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
$cacheRoot = Join-Path $tempRoot 'caches\gradle\caches\modules-2\files-2.1'
$testRoot = Join-Path $tempRoot ('qa\conversation-attachments\run-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
Write-Output "Attachment test output: $testRoot"
$javaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\JDK21' }
function Find-CachedJar([string]$RelativeDirectory) {
    $directory = Join-Path $cacheRoot $RelativeDirectory
    if (-not (Test-Path -LiteralPath $directory)) { throw "Missing cached dependency: $RelativeDirectory" }
    $jar = Get-ChildItem -LiteralPath $directory -Recurse -File -Filter '*.jar' |
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
if (-not (Test-Path -LiteralPath $jsonJar)) { throw 'Missing cached org.json test runtime; offline test downloads nothing' }
if (-not (Test-Path -LiteralPath $androidJar)) { throw 'Missing cached Android API jar' }
$classpath = @($stdlib, $annotations, $jsonJar) -join ';'
$dataRoot = Join-Path $projectRoot 'app\src\main\java\com\dafeiyu\mobile\data'
$sources = @('ConversationAttachment.kt', 'ConversationAttachmentStore.kt') |
    ForEach-Object { Join-Path $dataRoot $_ }
$java = Join-Path $javaRoot 'bin\java.exe'
$apiJar = Join-Path $testRoot 'attachments-android-api.jar'
& $java -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -Werror -classpath "$classpath;$androidJar" -d $apiJar @sources
if ($LASTEXITCODE -ne 0) { throw 'Attachment store Android API compilation failed' }
Write-Output 'PASS real Android API compilation'
$fixtureSources = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'tests\conversation-attachments') `
    -File -Filter '*.kt' | Select-Object -ExpandProperty FullName
$outputJar = Join-Path $testRoot 'conversation-attachment-checks.jar'
& $java -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -Werror -classpath $classpath -d $outputJar @sources @fixtureSources
if ($LASTEXITCODE -ne 0) { throw 'Attachment fixture compilation failed' }
$fixtureRoot = Join-Path $testRoot 'fixtures'
& $java -cp "$outputJar;$classpath" com.dafeiyu.mobile.data.ConversationAttachmentChecksKt $fixtureRoot |
    Tee-Object -FilePath (Join-Path $testRoot 'fixture-results.log')
if ($LASTEXITCODE -ne 0) { throw 'Conversation attachment checks failed' }
