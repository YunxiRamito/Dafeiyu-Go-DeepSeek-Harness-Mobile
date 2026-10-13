$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
$cacheRoot = Join-Path $tempRoot 'caches\gradle\caches\modules-2\files-2.1'
$testRoot = Join-Path $tempRoot 'qa\connection-package'
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$javaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\JDK21' }
function Find-CachedJar([string]$RelativeDirectory) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $cacheRoot $RelativeDirectory) -Recurse -File -Filter '*.jar' |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw "Missing cached dependency (run the main build first): $RelativeDirectory" }
    return $jar
}
$compiler = Find-CachedJar 'org.jetbrains.kotlin\kotlin-compiler-embeddable\2.1.20'
$stdlib = Find-CachedJar 'org.jetbrains.kotlin\kotlin-stdlib\2.1.20'
$coroutines = Find-CachedJar 'org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm\1.9.0'
$annotations = Find-CachedJar 'org.jetbrains\annotations'
$reflect = Find-CachedJar 'org.jetbrains.kotlin\kotlin-reflect'
$trove = Find-CachedJar 'org.jetbrains.intellij.deps\trove4j'
$compress = Find-CachedJar 'org.apache.commons\commons-compress\1.27.1'
$commonsIo = Find-CachedJar 'commons-io\commons-io\2.16.1'
$commonsCodec = Find-CachedJar 'commons-codec\commons-codec\1.17.1'
$commonsLang = Find-CachedJar 'org.apache.commons\commons-lang3'
$okhttp = Find-CachedJar 'com.squareup.okhttp3\okhttp\4.12.0'
$okio = Find-CachedJar 'com.squareup.okio\okio-jvm\3.6.0'
$json = Join-Path $tempRoot 'qa\workspace-presence\json-20240303.jar'
if (-not (Test-Path -LiteralPath $json)) { throw 'Missing cached org.json offline test runtime' }
$compilerClasspath = @($compiler, $stdlib, $coroutines, $annotations, $reflect, $trove) -join ';'
$classpath = @($stdlib, $annotations, $coroutines, $compress, $commonsIo, $commonsCodec, $commonsLang, $okhttp, $okio, $json) -join ';'
$outputJar = Join-Path $testRoot 'connection-package-checks.jar'
$dataRoot = Join-Path $projectRoot 'app\src\main\java\com\dafeiyu\mobile\data'
$sources = @('ConnectionPackageValidator.kt', 'ConnectionPluginPackage.kt', 'ArtifactDownloader.kt') | ForEach-Object { Join-Path $dataRoot $_ }
$sources += Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'tests\connection-package') -Filter '*.kt' -File |
    Select-Object -ExpandProperty FullName
& (Join-Path $javaRoot 'bin\java.exe') -Xmx384m "-Djava.io.tmpdir=$testRoot" -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -classpath $classpath -d $outputJar @sources
if ($LASTEXITCODE -ne 0) { throw 'Connection package offline checks compilation failed' }
& (Join-Path $PSScriptRoot 'prepare-connection-plugin.ps1')
$assetsRoot = Join-Path $tempRoot 'runtime\assets\connection-plugin'
$fixtureRoot = Join-Path $testRoot ('fixture-' + [Guid]::NewGuid().ToString('N'))
& (Join-Path $javaRoot 'bin\java.exe') -Xmx256m -cp "$outputJar;$classpath" `
    com.dafeiyu.mobile.data.ConnectionPackageChecksKt $fixtureRoot `
    (Join-Path $assetsRoot 'dafeiyu-go-connect-0.3.0.tgz') (Join-Path $assetsRoot 'manifest.json')
if ($LASTEXITCODE -ne 0) { throw 'Connection package offline checks failed' }

# Test the real preparation script in an isolated copy, without changing the shared frozen release.
$prepareFixture = Join-Path $testRoot ('prepare-fixture-' + [Guid]::NewGuid().ToString('N'))
$fakeProject = Join-Path $prepareFixture 'Project\Mobile'
$fakeTools = Join-Path $fakeProject 'tools'
$fakeArchiveDirectory = Join-Path $fakeProject 'third_party\connection-plugin'
$fakeManifestDirectory = $fakeArchiveDirectory
$fakeDestination = Join-Path $prepareFixture 'Temp_Android'
New-Item -ItemType Directory -Force -Path $fakeTools,$fakeArchiveDirectory,$fakeManifestDirectory | Out-Null
$fakeScript = Join-Path $fakeTools 'prepare-connection-plugin.ps1'
$fakeArchive = Join-Path $fakeArchiveDirectory 'dafeiyu-go-connect-0.3.0.tgz'
$fakeManifest = Join-Path $fakeManifestDirectory 'manifest.json'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'prepare-connection-plugin.ps1') -Destination $fakeScript
Copy-Item -LiteralPath (Join-Path $assetsRoot 'dafeiyu-go-connect-0.3.0.tgz') -Destination $fakeArchive
Copy-Item -LiteralPath (Join-Path $assetsRoot 'manifest.json') -Destination $fakeManifest
& $fakeScript -TempRoot $fakeDestination
$preparedArchive = Join-Path $fakeDestination 'runtime\assets\connection-plugin\dafeiyu-go-connect-0.3.0.tgz'
$preparedManifest = Join-Path $fakeDestination 'runtime\assets\connection-plugin\manifest.json'
$archiveHash = (Get-FileHash -LiteralPath $preparedArchive -Algorithm SHA256).Hash
$manifestHash = (Get-FileHash -LiteralPath $preparedManifest -Algorithm SHA256).Hash
Write-Output 'PASS prepare -TempRoot copies exact frozen archive and manifest'
function Assert-PreparationFails([string]$Name) {
    $rejected = $false
    try { & $fakeScript -TempRoot $fakeDestination } catch { $rejected = $true }
    if (-not $rejected) { throw "Expected fail-closed prepare: $Name" }
    if ((Get-FileHash -LiteralPath $preparedArchive -Algorithm SHA256).Hash -cne $archiveHash -or
        (Get-FileHash -LiteralPath $preparedManifest -Algorithm SHA256).Hash -cne $manifestHash) {
        throw "Failed prepare modified existing assets: $Name"
    }
    Write-Output "PASS prepare rejects $Name and preserves existing assets"
}
[IO.File]::WriteAllBytes($fakeArchive, [byte[]](1, 2, 3))
Assert-PreparationFails 'SHA mismatch'
[IO.File]::Delete($fakeArchive)
Assert-PreparationFails 'missing frozen package'
Copy-Item -LiteralPath (Join-Path $assetsRoot 'dafeiyu-go-connect-0.3.0.tgz') -Destination $fakeArchive
$invalidManifest = [IO.File]::ReadAllText($fakeManifest) | ConvertFrom-Json
$invalidManifest.archive_path = '/other/package'
[IO.File]::WriteAllText($fakeManifest, ($invalidManifest | ConvertTo-Json -Depth 8))
Assert-PreparationFails 'manifest archive path mismatch'
[IO.File]::WriteAllText($fakeManifest, (' ' * 8193))
Assert-PreparationFails 'oversized manifest'
[IO.File]::WriteAllText($fakeManifest, 'invalid-json')
Assert-PreparationFails 'invalid JSON'
[IO.File]::Delete($fakeManifest)
Assert-PreparationFails 'missing manifest'
Write-Output 'PASS 7 preparation script checks (isolated frozen-release copies only)'
