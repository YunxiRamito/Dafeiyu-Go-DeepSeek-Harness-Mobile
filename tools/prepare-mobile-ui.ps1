param(
    [string]$TempRoot = (Join-Path (Split-Path $PSScriptRoot -Parent) '..\Temp_Android')
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$source = Join-Path $projectRoot 'third_party\mobile-ui'
$manifestFile = Join-Path $source 'manifest.json'
if (-not (Test-Path -LiteralPath $manifestFile -PathType Leaf) -or (Get-Item -LiteralPath $manifestFile).Length -gt 16384) {
    throw 'Frozen mobile UI manifest missing or oversized; no assets were changed.'
}
$manifest = [IO.File]::ReadAllText($manifestFile) | ConvertFrom-Json
if ($manifest.schema -ne 1 -or $manifest.name -cne 'dafeiyu-mobile-ui' -or $manifest.version -cne '0.1.5' -or
    $manifest.file_name -cne 'dafeiyu-mobile-ui-0.1.5.tgz' -or $manifest.sha256 -cnotmatch '^[a-f0-9]{64}$' -or
    $manifest.size_bytes -le 0 -or $manifest.size_bytes -gt 1048576 -or $manifest.files.Count -lt 3 -or $manifest.files.Count -gt 32) {
    throw 'Frozen mobile UI manifest does not match the reviewed package.'
}
$archive = Join-Path $source $manifest.file_name
if (-not (Test-Path -LiteralPath $archive -PathType Leaf) -or (Get-Item -LiteralPath $archive).Length -ne $manifest.size_bytes -or
    (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -cne $manifest.sha256) {
    throw 'Frozen mobile UI archive missing or SHA256 mismatch; no assets were changed.'
}
$destination = Join-Path ([IO.Path]::GetFullPath($TempRoot)) 'runtime\assets\mobile-ui'
New-Item -ItemType Directory -Force -Path $destination | Out-Null
$copied = Join-Path $destination $manifest.file_name
Copy-Item -LiteralPath $archive -Destination $copied -Force
if ((Get-FileHash -LiteralPath $copied -Algorithm SHA256).Hash.ToLowerInvariant() -cne $manifest.sha256) {
    throw 'Prepared mobile UI asset copy failed SHA256 validation.'
}
Copy-Item -LiteralPath $manifestFile -Destination (Join-Path $destination 'manifest.json') -Force
Write-Output "Prepared mobile UI $($manifest.version), separate from Connect; actual WebView loading still requires device verification."
