$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
$moduleRoot = Join-Path $tempRoot 'caches\gradle\caches\modules-2\files-2.1'
$testRoot = Join-Path $tempRoot 'qa\background-tasks'
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$javaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\JDK21' }
function Find-CachedJar([string]$RelativeDirectory) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $moduleRoot $RelativeDirectory) -Recurse -File -Filter '*.jar' |
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
if (-not (Test-Path -LiteralPath $jsonJar)) { throw 'Missing cached org.json test runtime' }
$classpath = @($stdlib, $coroutines, $annotations, $jsonJar) -join ';'
$dataRoot = Join-Path $projectRoot 'app\src\main\java\com\dafeiyu\mobile\data'
$sources = @('BackgroundTaskModels.kt', 'BackgroundTaskEngine.kt', 'BackgroundTaskJson.kt', 'BackgroundTaskCredentialBoundary.kt') |
    ForEach-Object { Join-Path $dataRoot $_ }
$outputJar = Join-Path $testRoot 'background-task-checks.jar'
& (Join-Path $javaRoot 'bin\java.exe') -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -classpath $classpath -d $outputJar `
    @sources (Join-Path $PSScriptRoot 'tests\background-tasks\BackgroundTaskChecks.kt') `
    (Join-Path $PSScriptRoot 'tests\background-tasks\BackgroundTaskAwaitChecks.kt')
if ($LASTEXITCODE -ne 0) { throw 'Background task checks compilation failed' }
& (Join-Path $javaRoot 'bin\java.exe') -cp "$outputJar;$classpath" `
    com.dafeiyu.mobile.data.BackgroundTaskChecksKt (Join-Path $testRoot 'fixtures')
if ($LASTEXITCODE -ne 0) { throw 'Background task checks failed' }
& (Join-Path $javaRoot 'bin\java.exe') -cp "$outputJar;$classpath" `
    com.dafeiyu.mobile.data.BackgroundTaskAwaitChecksKt
if ($LASTEXITCODE -ne 0) { throw 'Background task await/recursion checks failed' }
$androidJar = Join-Path $tempRoot 'toolchains\android-sdk\platforms\android-35\android.jar'
if (-not (Test-Path -LiteralPath $androidJar)) { throw 'Missing cached Android 35 API jar' }
& (Join-Path $javaRoot 'bin\java.exe') -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -classpath "$classpath;$androidJar" `
    -d (Join-Path $testRoot 'background-task-android-compile-only.jar') `
    @sources (Join-Path $dataRoot 'BackgroundTaskAndroidStore.kt')
if ($LASTEXITCODE -ne 0) { throw 'Background task Android API compile-only verification failed' }
Write-Output 'PASS BackgroundTaskAndroidStore compiles against cached Android 35 API (compile only)'
$composePlugin = Find-CachedJar 'org.jetbrains.kotlin\kotlin-compose-compiler-plugin-embeddable\2.1.20'
$uiDependencies = @()
foreach ($group in @('androidx.compose.runtime', 'androidx.compose.ui', 'androidx.compose.foundation',
        'androidx.compose.animation', 'androidx.compose.material3', 'androidx.collection', 'androidx.annotation')) {
    $groupDirectory = Join-Path $moduleRoot $group
    if (-not (Test-Path -LiteralPath $groupDirectory)) { continue }
    foreach ($artifact in (Get-ChildItem -LiteralPath $groupDirectory -Directory)) {
        $version = Get-ChildItem -LiteralPath $artifact.FullName -Directory |
            Sort-Object Name -Descending | Select-Object -First 1
        if (-not $version) { continue }
        $jars = Get-ChildItem -LiteralPath $version.FullName -Recurse -File -Filter '*.jar'
        $uiDependencies += @($jars | Select-Object -ExpandProperty FullName)
        foreach ($aar in (Get-ChildItem -LiteralPath $version.FullName -Recurse -File -Filter '*.aar')) {
            $destination = Join-Path $testRoot ($artifact.Name + '-' + $version.Name + '-classes.jar')
            $archive = [System.IO.Compression.ZipFile]::OpenRead($aar.FullName)
            try {
                $classes = $archive.GetEntry('classes.jar')
                if ($classes) {
                    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($classes, $destination, $true)
                    $uiDependencies += $destination
                }
            } finally { $archive.Dispose() }
        }
    }
}
$uiClasspath = (@($classpath, $androidJar) + $uiDependencies) -join ';'
& (Join-Path $javaRoot 'bin\java.exe') -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 "-Xplugin=$composePlugin" -classpath $uiClasspath `
    -d (Join-Path $testRoot 'background-task-ui-compile-only.jar') @sources `
    (Join-Path $projectRoot 'app\src\main\java\com\dafeiyu\mobile\ui\BackgroundTasksScreen.kt') `
    (Join-Path $projectRoot 'app\src\main\java\com\dafeiyu\mobile\ui\components\GlassMotion.kt') `
    (Join-Path $PSScriptRoot 'tests\background-tasks\BackgroundTaskUiCompileFixtures.kt') `
    (Join-Path $PSScriptRoot 'tests\background-tasks\BackgroundTaskPageCompileFixtures.kt')
if ($LASTEXITCODE -ne 0) { throw 'Background task Compose UI compile-only verification failed' }
Write-Output 'PASS BackgroundTasksScreen compiles with cached Compose compiler/APIs (compile only, glass signatures fixture)'
