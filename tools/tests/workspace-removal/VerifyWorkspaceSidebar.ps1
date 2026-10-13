param([ValidateRange(192, 768)][int]$MaxHeapMiB = 384)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '../Temp_Android'))
$cacheRoot = Join-Path $tempRoot 'caches/gradle/caches'
$moduleRoot = Join-Path $cacheRoot 'modules-2/files-2.1'
$testRoot = Join-Path $tempRoot ('qa/workspace-sidebar/run-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$javaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:/Program Files/Java/JDK21' }
function Find-CachedJar([string]$RelativeDirectory) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $moduleRoot $RelativeDirectory) -Recurse -File -Filter '*.jar' |
        Sort-Object FullName | Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw "Missing offline dependency: $RelativeDirectory" }
    return $jar
}
$stdlib = Find-CachedJar 'org.jetbrains.kotlin/kotlin-stdlib/2.1.20'
$coroutines = Find-CachedJar 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0'
$annotations = Find-CachedJar 'org.jetbrains/annotations'
$compilerClasspath = @(
    (Find-CachedJar 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.20'),
    $stdlib, $coroutines, $annotations,
    (Find-CachedJar 'org.jetbrains.kotlin/kotlin-reflect'),
    (Find-CachedJar 'org.jetbrains.intellij.deps/trove4j')
) -join ';'
$transforms = @(Get-ChildItem -LiteralPath (Join-Path $cacheRoot '8.11.1/transforms') -Recurse -File -Filter 'classes.jar' |
    Where-Object { $_.FullName -match '[\\/]jars[\\/]classes\.jar$' })
if (-not $transforms.Count) { throw 'Missing cached Compose implementation jars' }
$classpath = (@($stdlib, $coroutines, $annotations, (Find-CachedJar 'androidx.collection/collection-jvm')) +
    $transforms.FullName) -join ';'
$sourceRoot = Join-Path $projectRoot 'app/src/main/java/com/dafeiyu/mobile'
$source = [IO.File]::ReadAllText((Join-Path $sourceRoot 'ui/WorkspaceSidebar.kt'))
$start = $source.IndexOf('internal enum class SidebarSwipe', [StringComparison]::Ordinal)
$end = $source.IndexOf('@Stable', $start, [StringComparison]::Ordinal)
if ($start -lt 0 -or $end -lt 0) { throw 'Missing production swipe/color/geometry helper markers' }
$subject = Join-Path $testRoot 'WorkspaceSidebarSubject.kt'
[IO.File]::WriteAllText($subject, "package com.dafeiyu.mobile.ui`nimport androidx.compose.ui.graphics.Color`nimport androidx.compose.ui.graphics.luminance`nimport androidx.compose.ui.geometry.Offset`nimport androidx.compose.ui.geometry.Rect`n" +
    $source.Substring($start, $end - $start))
$java = Join-Path $javaRoot 'bin/java.exe'
$jar = Join-Path $testRoot 'workspace-sidebar-checks.jar'
Write-Output "Workspace sidebar QA output: $testRoot"
& $java "-Xmx${MaxHeapMiB}m" "-Djava.io.tmpdir=$testRoot" -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -Werror -classpath $classpath -d $jar `
    $subject (Join-Path $PSScriptRoot 'WorkspaceSidebarSwipeChecks.kt') 2>&1 |
    Tee-Object -FilePath (Join-Path $testRoot 'compile.log')
if ($LASTEXITCODE -ne 0) { throw 'Production sidebar gesture helper compilation failed' }
& $java "-Xmx${MaxHeapMiB}m" "-Djava.io.tmpdir=$testRoot" -cp "$jar;$classpath" `
    com.dafeiyu.mobile.ui.WorkspaceSidebarSwipeChecksKt 2>&1 |
    Tee-Object -FilePath (Join-Path $testRoot 'results.log')
if ($LASTEXITCODE -ne 0) { throw 'Production two-gesture regression checks failed' }
Write-Output 'No Gradle, Compose runtime, RuntimeShader, ADB or device pixels executed.'
