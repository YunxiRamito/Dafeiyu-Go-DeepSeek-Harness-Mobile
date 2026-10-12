param(
    [string]$TempRoot = (Join-Path (Split-Path $PSScriptRoot -Parent) '..\Temp_Android')
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$archiveName = 'dafeiyu-go-connect-0.2.0.tgz'
$expectedSha = '9DF7085CD5FA9ECF0E6F9A31AACE8D0272EF60C4A9AEF12A2EA96EFAFDF6AA1B'
$sourceArchive = [IO.Path]::GetFullPath((Join-Path $projectRoot ("..\Dafeiyu-GO-Connect-Plugin\Temp\Runtime-20261011\package\" + $archiveName)))
$sourceManifest = [IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_SetupandLauncher\ConnectionIntegration-20261011\Prepared-Releases-Final\0.2.0\manifest.json'))
$TempRoot = [IO.Path]::GetFullPath($TempRoot)
$destination = Join-Path $TempRoot 'runtime\assets\connection-plugin'

if (-not (Test-Path -LiteralPath $sourceArchive -PathType Leaf) -or
    (Get-FileHash -LiteralPath $sourceArchive -Algorithm SHA256).Hash -cne $expectedSha) {
    throw 'Frozen connection plugin SHA256 mismatch or package missing; no assets were changed.'
}
if (-not (Test-Path -LiteralPath $sourceManifest -PathType Leaf) -or
    (Get-Item -LiteralPath $sourceManifest).Length -gt 8192) {
    throw 'Frozen connection plugin manifest missing or oversized; no assets were changed.'
}
$manifest = [IO.File]::ReadAllText($sourceManifest) | ConvertFrom-Json
$archiveSize = (Get-Item -LiteralPath $sourceArchive).Length
if ($archiveSize -le 0 -or $archiveSize -gt 16777216 -or
    $manifest.protocol -cne 'dafeiyu-go.connection.v1' -or $manifest.version -cne '0.2.0' -or
    $manifest.sha256.ToUpperInvariant() -cne $expectedSha -or $manifest.size_bytes -ne $archiveSize -or
    $manifest.file_name -cne $archiveName -or $manifest.platforms -cnotcontains 'android' -or
    $manifest.minimum_host_version -cne '1.8.0' -or
    $manifest.archive_path -cne '/api/connection-plugin/releases/0.2.0/package') {
    throw 'Frozen connection plugin manifest does not match the approved archive; no assets were changed.'
}

New-Item -ItemType Directory -Force -Path $destination | Out-Null
$copiedArchive = Join-Path $destination $archiveName
Copy-Item -LiteralPath $sourceArchive -Destination $copiedArchive -Force
if ((Get-FileHash -LiteralPath $copiedArchive -Algorithm SHA256).Hash -cne $expectedSha) {
    throw 'Prepared connection plugin copy failed SHA256 validation.'
}
Copy-Item -LiteralPath $sourceManifest -Destination (Join-Path $destination 'manifest.json') -Force
Write-Output "Prepared connection plugin 0.2.0 assets: $destination (package only; runtime not loaded)"
