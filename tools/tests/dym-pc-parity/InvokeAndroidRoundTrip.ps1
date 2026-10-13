param([Parameter(Mandatory)][string]$CompiledCheckDirectory, [Parameter(Mandatory)][string]$PcArchive,
    [Parameter(Mandatory)][string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$temp = [IO.Path]::GetFullPath((Join-Path $project '../Temp_Android'))
$cache = Join-Path $temp 'caches/gradle/caches/modules-2/files-2.1'
function Cached([string]$directory) {
    Get-ChildItem -LiteralPath (Join-Path $cache $directory) -Recurse -File -Filter '*.jar' |
        Sort-Object FullName | Select-Object -First 1 -ExpandProperty FullName
}
$sharing = Get-ChildItem -LiteralPath (Join-Path $temp 'qa') -Directory -Filter 'dsh-sharing-*' |
    Sort-Object LastWriteTime -Descending | ForEach-Object { Join-Path $_.FullName 'sharing.jar' } |
    Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
$projectClasses = @('debug', 'release') | ForEach-Object { Join-Path $temp "build/app/tmp/kotlin-classes/$_" } |
    Where-Object { Test-Path -LiteralPath (Join-Path $_ 'com/dafeiyu/mobile/data/DemoRepository.class') } |
    Sort-Object { (Get-Item -LiteralPath (Join-Path $_ 'com/dafeiyu/mobile/data/DemoRepository.class')).LastWriteTime } -Descending | Select-Object -First 1
$classpath = @((Join-Path $CompiledCheckDirectory 'dym-backup.jar'),
    (Cached 'org.jetbrains.kotlin/kotlin-stdlib/2.1.20'), (Cached 'org.jetbrains/annotations'),
    (Cached 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0'), (Join-Path $temp 'qa/workspace-presence/json-20240303.jar'),
    $projectClasses, $sharing, (Join-Path $temp 'toolchains/android-sdk/platforms/android-35/android.jar'),
    (Cached 'org.apache.commons/commons-compress/1.27.1'), (Cached 'commons-io/commons-io/2.16.1'),
    (Cached 'org.apache.commons/commons-lang3'), (Cached 'org.tukaani/xz/1.10')) -join ';'
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
& 'C:/Program Files/Java/JDK21/bin/java.exe' -Xmx512m "-Djava.io.tmpdir=$OutputDirectory" -cp $classpath `
    com.dafeiyu.mobile.data.AndroidRoundTripKt $OutputDirectory $PcArchive
if ($LASTEXITCODE -ne 0) { throw 'Production Android sevenzip roundtrip failed.' }
