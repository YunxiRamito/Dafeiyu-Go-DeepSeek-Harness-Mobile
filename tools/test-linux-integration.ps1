$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
$cacheRoot = Join-Path $tempRoot 'caches\gradle\caches\modules-2\files-2.1'
$testRoot = Join-Path $tempRoot 'qa\linux-integration-0.1.24'
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
function Cached([string]$path) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $cacheRoot $path) -Recurse -File -Filter '*.jar' | Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw "Missing dependency: $path" }
    return $jar
}
$stdlib = Cached 'org.jetbrains.kotlin\kotlin-stdlib\2.1.20'
$coroutines = Cached 'org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm\1.9.0'
$annotations = Cached 'org.jetbrains\annotations'
$compilerClasspath = @((Cached 'org.jetbrains.kotlin\kotlin-compiler-embeddable\2.1.20'), $stdlib, $coroutines,
    $annotations, (Cached 'org.jetbrains.kotlin\kotlin-reflect'), (Cached 'org.jetbrains.intellij.deps\trove4j')) -join ';'
$classpath = @($stdlib, $coroutines, $annotations,
    (Join-Path $tempRoot 'qa\workspace-presence\json-20240303.jar'),
    (Join-Path $tempRoot 'toolchains\android-sdk\platforms\android-35\android.jar'),
    (Cached 'com.squareup.okhttp3\okhttp\4.12.0'), (Cached 'com.squareup.okio\okio-jvm\3.6.0'),
    (Cached 'org.jsoup\jsoup\1.18.3')) -join ';'
$dataRoot = Join-Path $projectRoot 'app\src\main\java\com\dafeiyu\mobile\data'
$sources = @('BackgroundTaskModels.kt', 'BackgroundTaskEngine.kt', 'BackgroundTaskJson.kt',
    'AssistantStreamProtocol.kt', 'BalanceStore.kt', 'DeepSeekChatProtocol.kt', 'DeepSeekChatClient.kt',
    'DeepSeekThinkingPolicy.kt', 'DeepSeekModelCatalog.kt',
    'OfficialToolContract.kt', 'OfficialToolOutputStore.kt', 'OfficialToolDispatcher.kt',
    'OfficialToolSession.kt', 'OfficialWebClient.kt', 'ComponentDownloadClient.kt') | ForEach-Object { Join-Path $dataRoot $_ }
$sources += Join-Path $PSScriptRoot 'tests\official-web\OfficialWebChecks.kt'
$sources += Join-Path $PSScriptRoot 'tests\linux-integration\TestContext.kt'
$sources += Join-Path $PSScriptRoot 'tests\linux-integration\LinuxIntegrationChecks.kt'
$java = 'C:\Program Files\Java\JDK21\bin\java.exe'
$jar = Join-Path $testRoot 'checks.jar'
& $java -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -Werror -classpath $classpath -d $jar @sources *> (Join-Path $testRoot 'compile.log')
if ($LASTEXITCODE -ne 0) { throw "Compile failed; read $testRoot\compile.log" }
& $java -cp "$jar;$classpath" com.dafeiyu.mobile.data.LinuxIntegrationChecksKt (Join-Path $testRoot ('fixtures-' + [Guid]::NewGuid().ToString('N'))) *> (Join-Path $testRoot 'results.log')
if ($LASTEXITCODE -ne 0) { throw "Checks failed; read $testRoot\results.log" }
Get-Content -LiteralPath (Join-Path $testRoot 'results.log')
