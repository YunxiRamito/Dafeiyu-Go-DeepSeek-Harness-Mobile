$ErrorActionPreference = 'Stop'
$researchRoot = $PSScriptRoot
$head = Get-Content -LiteralPath (Join-Path $researchRoot 'api/head.json') -Raw | ConvertFrom-Json
$commit = $head.sha
if ($commit -notmatch '^[0-9a-f]{40}$') { throw 'Unexpected commit identity' }
$snapshots = @(
    Get-ChildItem -LiteralPath (Join-Path $researchRoot 'source') -File -Recurse |
        Where-Object { $_.Name -notmatch '\.(headers\.txt|stderr\.log|partial)$' } |
        ForEach-Object {
            $relative = [System.IO.Path]::GetRelativePath((Join-Path $researchRoot 'source'), $_.FullName).Replace('\', '/')
            $headers = Get-Content -LiteralPath "$($_.FullName).headers.txt" -Raw
            [pscustomobject]@{
                path = "source/$relative"
                url = "https://raw.githubusercontent.com/QWEA0/Liquid-Glass-Android/$commit/$relative"
                bytes = $_.Length
                sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
                http_status_lines = @([regex]::Matches($headers, '(?m)^HTTP/[^\r\n]+') | ForEach-Object { $_.Value })
                response_dates = @([regex]::Matches($headers, '(?mi)^Date: ([^\r\n]+)') | ForEach-Object { $_.Groups[1].Value })
            }
        }
)
$snapshotManifest = [ordered]@{
    audited_at = [DateTimeOffset]::Now.ToString('o')
    proxy_used = 'http://127.0.0.1:7890'
    commit = $commit
    commit_date_utc = $head.commit.committer.date
    tree = $head.commit.tree.sha
    tree_truncated = (Get-Content -LiteralPath (Join-Path $researchRoot 'api/tree.json') -Raw | ConvertFrom-Json).truncated
    head_body_sha256 = (Get-FileHash -LiteralPath (Join-Path $researchRoot 'api/head.json') -Algorithm SHA256).Hash.ToLowerInvariant()
    note = 'Retained first-pass HEAD JSON identifies the baseline, not a successfully repeated latest-HEAD check. HEAD headers describe the later HTTP 403 retry; download-manifest records retry failures, not source inventory. Pinned raw sources have their own successful HTTP headers.'
    sources = $snapshots
}
$snapshotManifest | ConvertTo-Json -Depth 8 |
    Set-Content -LiteralPath (Join-Path $researchRoot 'snapshot-manifest.json') -Encoding UTF8

function Smooth-Min([double]$a, [double]$b, [double]$k) {
    if ($k -le 0.5) { return [math]::Min($a, $b) }
    $h = [math]::Clamp(0.5 + 0.5 * ($b - $a) / $k, 0.0, 1.0)
    return $b * (1 - $h) + $a * $h - $k * $h * (1 - $h)
}
$radius = 24.0
$gap = 8.0
$endHalfHeight = 48.0 * (0.25 + 0.19)
$waist = $endHalfHeight * (0.18 + 0.30)
$circleShoulderX = $radius + [math]::Sqrt($radius * $radius - $endHalfHeight * $endHalfHeight)
$mathEvidence = [ordered]@{
    note = 'Read-only scalar calculations in dp-normalized units. No shader, JVM, GPU, device or production test was run.'
    circle_radius_dp = $radius; gap_dp = $gap
    midpoint_union = @(8, 16, 20, 24 | ForEach-Object {
        [pscustomobject]@{ smoothing_dp = $_; signed_field_dp = (Smooth-Min ($gap / 2) ($gap / 2) $_) }
    })
    current_bridge_at_strength_1 = [ordered]@{
        end_half_height_dp = $endHalfHeight; waist_half_height_dp = $waist
        left_bridge_x_dp = 48; circle_x_at_bridge_top_dp = $circleShoulderX
        shoulder_missing_span_dp = 48 - $circleShoulderX
    }
}
$mathEvidence | ConvertTo-Json -Depth 6 |
    Set-Content -LiteralPath (Join-Path $researchRoot 'geometry-evidence.json') -Encoding UTF8
$snapshots | Select-Object path, bytes, sha256 | Format-Table -AutoSize
$mathEvidence | ConvertTo-Json -Depth 6
