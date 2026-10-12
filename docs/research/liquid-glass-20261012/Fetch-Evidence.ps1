param([switch]$MetadataOnly)

$ErrorActionPreference = 'Stop'
$researchRoot = $PSScriptRoot
$proxyUri = 'http://127.0.0.1:7890'
$repoUri = 'https://api.github.com/repos/QWEA0/Liquid-Glass-Android'
$records = [System.Collections.Generic.List[object]]::new()
$manifestPath = Join-Path $researchRoot 'download-manifest.json'
if (Test-Path -LiteralPath $manifestPath) {
    foreach ($record in @(Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json)) {
        $records.Add($record)
    }
}

function Save-Evidence([string]$Url, [string]$RelativePath) {
    $destination = Join-Path $researchRoot $RelativePath
    $null = New-Item -ItemType Directory -Force -Path (Split-Path $destination -Parent)
    $headerPath = "$destination.headers.txt"
    $partialPath = "$destination.partial"
    $started = [DateTimeOffset]::Now.ToString('o')
    $status = & curl.exe --proxy $proxyUri --noproxy 'example.invalid' --connect-timeout 15 --max-time 45 `
        --max-filesize 2097152 --silent --show-error --location `
        --stderr "$destination.stderr.log" --dump-header $headerPath --output $partialPath --write-out '%{http_code}' $Url
    $exitCode = $LASTEXITCODE
    $entry = [ordered]@{
        url = $Url; proxy = $proxyUri; started_at = $started
        completed_at = [DateTimeOffset]::Now.ToString('o')
        http_status = "$status"; curl_exit = $exitCode; path = $RelativePath
        bytes = if (Test-Path -LiteralPath $partialPath) { (Get-Item -LiteralPath $partialPath).Length } else { 0 }
        sha256 = if (Test-Path -LiteralPath $partialPath) { (Get-FileHash -LiteralPath $partialPath -Algorithm SHA256).Hash.ToLowerInvariant() } else { $null }
        headers_sha256 = if (Test-Path -LiteralPath $headerPath) { (Get-FileHash -LiteralPath $headerPath -Algorithm SHA256).Hash.ToLowerInvariant() } else { $null }
    }
    $records.Add([pscustomobject]$entry)
    $records | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
    if ($exitCode -ne 0 -or "$status" -ne '200') {
        throw "Bounded proxy request failed: HTTP $status / curl $exitCode / $Url"
    }
    Move-Item -LiteralPath $partialPath -Destination $destination -Force
    Write-Output "$status $($entry.bytes) $RelativePath"
}

try {
    Save-Evidence "$repoUri/commits/HEAD" 'api/head.json'
} catch {
    # Read-only fallback resolves HEAD without cloning or executing repository code.
    $gitInfo = [System.Diagnostics.ProcessStartInfo]::new('git')
    $gitInfo.WorkingDirectory = $researchRoot
    $gitInfo.Arguments = "-c http.proxy=$proxyUri ls-remote https://github.com/QWEA0/Liquid-Glass-Android.git HEAD"
    $gitInfo.UseShellExecute = $false
    $gitInfo.CreateNoWindow = $true
    $gitInfo.RedirectStandardOutput = $true
    $gitInfo.RedirectStandardError = $true
    $gitProcess = [System.Diagnostics.Process]::Start($gitInfo)
    $stdout = $gitProcess.StandardOutput.ReadToEndAsync()
    $stderr = $gitProcess.StandardError.ReadToEndAsync()
    if (!$gitProcess.WaitForExit(45000)) {
        $gitProcess.Kill()
        'Git fallback timed out after 45 seconds.' | Set-Content -LiteralPath (Join-Path $researchRoot 'git-ls-remote-fallback.log') -Encoding UTF8
    } else {
        @("exit=$($gitProcess.ExitCode)", $stdout.Result, $stderr.Result) |
            Set-Content -LiteralPath (Join-Path $researchRoot 'git-ls-remote-fallback.log') -Encoding UTF8
    }
    $gitProcess.Dispose()
    throw
}
$head = Get-Content -LiteralPath (Join-Path $researchRoot 'api/head.json') -Raw | ConvertFrom-Json
$commit = $head.sha
if ($commit -notmatch '^[0-9a-f]{40}$') { throw 'Unexpected commit identity' }
Save-Evidence "$repoUri/git/trees/$($head.commit.tree.sha)?recursive=1" 'api/tree.json'
$tree = Get-Content -LiteralPath (Join-Path $researchRoot 'api/tree.json') -Raw | ConvertFrom-Json
if ($tree.truncated) { throw 'Tree response is truncated' }
if ($MetadataOnly) { return }

$paths = @('README.md', 'LICENSE', 'liquidglass/build.gradle.kts')
$paths += @($tree.tree | Where-Object {
    $_.type -eq 'blob' -and $_.path -match '/(LiquidGlassView|LiquidGlassShader|LiquidGlassTouch|GlassLensRenderer|GlassShader|GlassTouch|GlassMaterial|LiquidGlassButton|BackdropCapture|BackgroundCapture|HardwareBackdropBlur|WindowStackBackdrop|BitmapPool)\.(kt|java|cpp|h)$'
} | ForEach-Object { $_.path })
foreach ($sourcePath in $paths | Select-Object -Unique) {
    Save-Evidence "https://raw.githubusercontent.com/QWEA0/Liquid-Glass-Android/$commit/$sourcePath" "source/$sourcePath"
}
Write-Output "Pinned commit: $commit. Source files are inert evidence; do not execute or import them."
