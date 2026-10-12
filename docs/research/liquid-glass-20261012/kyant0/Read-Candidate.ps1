param(
    [ValidateSet('Head', 'Tree', 'Files')][string]$Stage,
    [string]$Commit,
    [string[]]$Paths
)
$ErrorActionPreference = 'Stop'
$candidateRoot = $PSScriptRoot
$manifestPath = Join-Path $candidateRoot 'manifest.json'
$manifest = if (Test-Path -LiteralPath $manifestPath) {
    Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
} else {
    [pscustomobject]@{
        repository = 'https://github.com/Kyant0/AndroidLiquidGlass'
        proxy = 'http://127.0.0.1:7890'
        policy = 'One attempt per resource; at most one fallback request overall; at most eight reference files. No downloaded code is executed.'
        fallback_used = $false
        requests = @()
    }
}
function Write-Manifest {
    $manifest | ConvertTo-Json -Depth 9 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
}
function Read-Url([string]$Url, [string]$RelativePath) {
    if (@($manifest.requests | Where-Object url -eq $Url).Count) { throw "Already attempted: $Url" }
    $destination = Join-Path $candidateRoot $RelativePath
    $null = New-Item -ItemType Directory -Path (Split-Path $destination -Parent) -Force
    $started = [DateTimeOffset]::Now.ToString('o')
    $status = & curl.exe --proxy $manifest.proxy --noproxy 'example.invalid' --http1.1 `
        --connect-timeout 10 --max-time 30 --max-filesize 1048576 `
        --silent --show-error --location --stderr "$destination.stderr.log" `
        --dump-header "$destination.headers.txt" --output "$destination.partial" `
        --write-out '%{http_code}' $Url
    $exitCode = $LASTEXITCODE
    $ok = $exitCode -eq 0 -and "$status" -eq '200'
    if ($ok) { Move-Item -LiteralPath "$destination.partial" -Destination $destination -Force }
    $actual = if ($ok) { $destination } else { "$destination.partial" }
    $manifest.requests += [pscustomobject]@{
        url = $Url; started_at = $started; completed_at = [DateTimeOffset]::Now.ToString('o')
        http_status = "$status"; curl_exit = $exitCode; success = $ok
        path = if ($ok) { $RelativePath } else { "$RelativePath.partial" }
        bytes = if (Test-Path -LiteralPath $actual) { (Get-Item -LiteralPath $actual).Length } else { 0 }
        sha256 = if (Test-Path -LiteralPath $actual) { (Get-FileHash -LiteralPath $actual -Algorithm SHA256).Hash.ToLowerInvariant() } else { $null }
        headers_sha256 = (Get-FileHash -LiteralPath "$destination.headers.txt" -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    Write-Manifest
    Write-Output "$status curl=$exitCode $RelativePath"
    return $ok
}
if ($Stage -eq 'Head') {
    $results = @(Read-Url 'https://api.github.com/repos/Kyant0/AndroidLiquidGlass/commits/HEAD' 'api/head.json')
    $results | Where-Object { $_ -is [string] } | Write-Output
    if ($results[-1] -eq $true) { return }
    if ($manifest.fallback_used) { throw 'Fallback already consumed' }
    $manifest.fallback_used = $true
    Write-Manifest
    $info = [System.Diagnostics.ProcessStartInfo]::new('git')
    $info.WorkingDirectory = $candidateRoot
    $info.Arguments = '-c http.proxy=http://127.0.0.1:7890 ls-remote https://github.com/Kyant0/AndroidLiquidGlass.git HEAD'
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::Start($info)
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $ended = $process.WaitForExit(30000)
    if (!$ended) { $process.Kill() }
    $log = @("completed_at=$([DateTimeOffset]::Now.ToString('o'))", "timed_out=$(!$ended)",
        "exit=$($process.ExitCode)", $stdout.Result, $stderr.Result)
    $log | Set-Content -LiteralPath (Join-Path $candidateRoot 'api/head-git-fallback.log') -Encoding UTF8
    $manifest.requests += [pscustomobject]@{
        command = $info.Arguments; exit = $process.ExitCode; timed_out = !$ended
        path = 'api/head-git-fallback.log'; success = $ended -and $process.ExitCode -eq 0
    }
    Write-Manifest
    $process.Dispose()
    $log | Write-Output
    return
}
if ($Commit -notmatch '^[0-9a-f]{40}$') { throw 'A full pinned commit is required' }
if ($Stage -eq 'Tree') {
    $null = Read-Url "https://api.github.com/repos/Kyant0/AndroidLiquidGlass/git/trees/${Commit}?recursive=1" 'api/tree.json'
    return
}
$existingFiles = @($manifest.requests | Where-Object { $_.url -like 'https://raw.githubusercontent.com/*' }).Count
if ($existingFiles + $Paths.Count -gt 8) { throw 'Eight-reference-file budget exceeded' }
foreach ($sourcePath in $Paths) {
    if ($sourcePath -match '(^/|\\|\.\.)') { throw 'Invalid repository path' }
    $null = Read-Url "https://raw.githubusercontent.com/Kyant0/AndroidLiquidGlass/$Commit/$sourcePath" "source/$sourcePath"
}
