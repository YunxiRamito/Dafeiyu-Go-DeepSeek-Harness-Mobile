param()
$ErrorActionPreference = 'Stop'
$candidateRoot = $PSScriptRoot
$manifestPath = Join-Path $candidateRoot 'manifest.json'
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$commit = '65ab177e90e5c1d8c62e70cf7755841982da65f6'
$rawRequests = @($manifest.requests | Where-Object { $_.url -like 'https://raw.githubusercontent.com/*' })
$checks = @()
foreach ($request in $manifest.requests) {
    $path = Join-Path $candidateRoot $request.path
    if (!(Test-Path -LiteralPath $path)) { throw "Missing evidence: $($request.path)" }
    $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($request.PSObject.Properties.Name -contains 'sha256') {
        if ($hash -ne $request.sha256) { throw "Body hash mismatch: $($request.path)" }
        $header = "$path.headers.txt"
        if ($path.EndsWith('.partial')) { $header = $path.Substring(0, $path.Length - 8) + '.headers.txt' }
        $headerHash = (Get-FileHash -LiteralPath $header -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($headerHash -ne $request.headers_sha256) { throw "Header hash mismatch: $($request.path)" }
    } else {
        $request | Add-Member -NotePropertyName sha256 -NotePropertyValue $hash
        $request | Add-Member -NotePropertyName bytes -NotePropertyValue (Get-Item -LiteralPath $path).Length
    }
    $checks += [pscustomobject]@{ path = $request.path; success = $request.success; sha256 = $hash; hash_matches = $true }
}
if ($rawRequests.Count -ne 8) { throw 'Expected exactly eight reference-file attempts' }
if (@($rawRequests | Where-Object { $_.url -notlike "https://raw.githubusercontent.com/Kyant0/AndroidLiquidGlass/$commit/*" }).Count) {
    throw 'Unpinned reference URL'
}
if (@($manifest.requests | Where-Object command).Count -ne 1 -or !$manifest.fallback_used) {
    throw 'Expected exactly one fallback'
}
$headLog = Get-Content -LiteralPath (Join-Path $candidateRoot 'api/head-git-fallback.log') -Raw
if ($headLog -notmatch "$commit\s+HEAD") { throw 'HEAD log does not identify the pinned commit' }
$frozen = @{
    'REPORT.md' = '52cdd6785987d7c11a4f11a401bede2323de99bb1e3a75bfa301317db4cead05'
    'snapshot-manifest.json' = '9dc89c037fee3ea760a8875d560e9f9d6e44e5d8464386e2803a43f154ae4f99'
    'download-manifest.json' = 'da0033d08200f6296f9a6fdf0ae5b6a79ef651846bfdf799c2e507a191bdbffd'
}
foreach ($name in $frozen.Keys) {
    $hash = (Get-FileHash -LiteralPath (Join-Path (Split-Path $candidateRoot -Parent) $name) -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($hash -ne $frozen[$name]) { throw "Frozen evidence changed since local comparison baseline: $name" }
}
$manifest | Add-Member -Force -NotePropertyName pinned_commit -NotePropertyValue $commit
$manifest | Add-Member -Force -NotePropertyName head_confirmation -NotePropertyValue 'Successful proxied git ls-remote HEAD; completed 2026-10-12T00:37:36.0596661+08:00. Commit author/committer date unavailable.'
$manifest | Add-Member -Force -NotePropertyName blocked -NotePropertyValue @(
    'HEAD and recursive-tree API responses are 403 rate limit; no successful API metadata.'
    'DrawBackdrop.kt was an inferred path and returned 404. Actual rendering entry and internal shader strings were not read.'
    'No remaining reference-file attempts or fallback requests; no further network requests authorized by this bounded run.'
    'Published artifact availability, transitive dependencies, AAR metadata and platform shader-support implementation were not verified.'
)
$manifest | Add-Member -Force -NotePropertyName reference_file_attempts -NotePropertyValue $rawRequests.Count
$manifest | Add-Member -Force -NotePropertyName reference_files_successful -NotePropertyValue @($rawRequests | Where-Object success).Count
$manifest | ConvertTo-Json -Depth 9 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
$validation = [pscustomobject]@{
    checked_at = [DateTimeOffset]::Now.ToString('o')
    offline_only = $true
    pinned_commit = $commit
    network_operations = $manifest.requests.Count
    fallback_requests = 1
    reference_file_attempts = $rawRequests.Count
    successful_reference_files = @($rawRequests | Where-Object success).Count
    successful_reference_bytes = ($rawRequests | Where-Object success | Measure-Object bytes -Sum).Sum
    evidence = $checks
    frozen_comparison_baseline = $frozen
    frozen_hashes_match_baseline = $true
    external_code_executed = $false
    production_edited = $false
    build_or_device_validation = 'Not run; source/document evidence only.'
}
$validation | ConvertTo-Json -Depth 9 | Set-Content -LiteralPath (Join-Path $candidateRoot 'validation.json') -Encoding UTF8
$validation | Select-Object checked_at,network_operations,fallback_requests,reference_file_attempts,successful_reference_files,successful_reference_bytes,frozen_hashes_match_baseline
