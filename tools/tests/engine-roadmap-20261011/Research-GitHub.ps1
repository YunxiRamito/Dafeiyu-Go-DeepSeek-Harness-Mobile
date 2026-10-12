param(
    [string]$Proxy = 'http://127.0.0.1:7890'
)
$ErrorActionPreference = 'Stop'
$proxyUri = [Uri]$Proxy
if ($proxyUri.Scheme -ne 'http' -or $proxyUri.Host -ne '127.0.0.1' -or $proxyUri.Port -ne 7890) {
    throw 'Research requires the explicitly requested local proxy at 127.0.0.1:7890.'
}
$outputRoot = Join-Path $PSScriptRoot ('github-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $outputRoot | Out-Null
$handler = [Net.Http.HttpClientHandler]::new()
$handler.Proxy = [Net.WebProxy]::new($proxyUri)
$handler.UseProxy = $true
$handler.AllowAutoRedirect = $false
$client = [Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(30)
$client.DefaultRequestHeaders.UserAgent.ParseAdd('Dafeiyu-Mobile-ReadOnly-Roadmap/1.0')
$client.DefaultRequestHeaders.Accept.ParseAdd('application/vnd.github+json')
$client.DefaultRequestHeaders.Add('X-GitHub-Api-Version', '2022-11-28')
$receipts = [Collections.Generic.List[object]]::new()
$repositories = [Collections.Generic.List[object]]::new()
$searches = [Collections.Generic.List[object]]::new()

function Read-GitHub([string]$url, [string]$filename) {
    if (([Uri]$url).Host -ne 'api.github.com') { throw 'Only api.github.com GET is allowed.' }
    $started = [DateTimeOffset]::Now
    $response = $null
    $receipt = [ordered]@{
        method = 'GET'; url = $url; proxy = $Proxy; started_at = $started.ToString('o')
        status = $null; bytes = 0; sha256 = $null; response_file = $null
        rate_remaining = $null; rate_reset = $null; error = $null
    }
    try {
        $response = $client.GetAsync($url, [Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
        $receipt.status = [int]$response.StatusCode
        foreach ($name in @('X-RateLimit-Remaining', 'X-RateLimit-Reset')) {
            if ($response.Headers.Contains($name)) {
                $value = ($response.Headers.GetValues($name) -join ',')
                if ($name -eq 'X-RateLimit-Remaining') { $receipt.rate_remaining = $value }
                else { $receipt.rate_reset = $value }
            }
        }
        $stream = $response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
        $buffer = [byte[]]::new(8192)
        $memory = [IO.MemoryStream]::new()
        try {
            while (($count = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                if ($memory.Length + $count -gt 2MB) { throw 'Response exceeds the 2 MiB research limit.' }
                $memory.Write($buffer, 0, $count)
            }
            $bytes = $memory.ToArray()
        } finally { $memory.Dispose(); $stream.Dispose() }
        $path = Join-Path $outputRoot $filename
        [IO.File]::WriteAllBytes($path, $bytes)
        $receipt.bytes = $bytes.Length
        $receipt.sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        $receipt.response_file = $filename
        if ($receipt.status -ne 200) { throw "HTTP $($receipt.status)" }
        return ([Text.Encoding]::UTF8.GetString($bytes) | ConvertFrom-Json)
    } catch {
        $receipt.error = $_.Exception.Message
        return $null
    } finally {
        if ($null -ne $response) { $response.Dispose() }
        $receipt['finished_at'] = [DateTimeOffset]::Now.ToString('o')
        $receipts.Add([pscustomobject]$receipt)
        Write-Output "GET $url -> $($receipt.status) $($receipt.error)" | Out-Host
    }
}

try {
    $queries = @(
        'langgraph in:name archived:false',
        'quickjs android in:description archived:false',
        'nodejs-mobile in:name archived:false',
        'wasm-micro-runtime in:name archived:false'
    )
    for ($i = 0; $i -lt $queries.Count; $i++) {
        $url = 'https://api.github.com/search/repositories?q=' + [Uri]::EscapeDataString($queries[$i]) + '&sort=stars&per_page=5'
        $data = Read-GitHub $url ("search-$i.json")
        $searches.Add([pscustomobject]@{
            query = $queries[$i]; url = $url
            total_count = if ($null -ne $data) { $data.total_count } else { $null }
            results = @($data.items | ForEach-Object {
                [pscustomobject]@{ full_name = $_.full_name; html_url = $_.html_url; stars = $_.stargazers_count }
            })
        })
    }
    $targets = @(
        'langchain-ai/langgraph', 'pydantic/pydantic-ai', 'quickjs-ng/quickjs',
        'bytecodealliance/wasmtime', 'wasm-micro-runtime/wasm-micro-runtime',
        'nodejs-mobile/nodejs-mobile', 'termux/termux-packages'
    )
    foreach ($repo in $targets) {
        $prefix = $repo.Replace('/', '--')
        $metadata = Read-GitHub "https://api.github.com/repos/$repo" "$prefix-metadata.json"
        if ($null -eq $metadata) { continue }
        $commit = Read-GitHub ("https://api.github.com/repos/$repo/commits/" +
            [Uri]::EscapeDataString($metadata.default_branch)) "$prefix-commit.json"
        $ref = if ($null -ne $commit) { $commit.sha } else { $metadata.default_branch }
        $readme = Read-GitHub "https://api.github.com/repos/$repo/readme?ref=$ref" "$prefix-readme.json"
        $repositories.Add([pscustomobject]@{
            full_name = $repo; html_url = $metadata.html_url
            description = $metadata.description; archived = $metadata.archived
            pushed_at = $metadata.pushed_at; default_branch = $metadata.default_branch
            commit_sha = $commit.sha; stars = $metadata.stargazers_count
            license = $metadata.license.spdx_id
            readme_html_url = $readme.html_url; readme_blob_sha = $readme.sha
        })
    }
    $summary = [ordered]@{
        observed_at = [DateTimeOffset]::Now.ToString('o'); proxy = $Proxy
        scope = 'Unauthenticated read-only GET; no clone, install, release download, external script execution, model call or credentials.'
        searches = $searches.ToArray(); repositories = $repositories.ToArray(); requests = $receipts.ToArray()
    }
    $summary | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $outputRoot 'summary.json') -Encoding utf8
    Write-Output "Evidence: $outputRoot"
    if (@($receipts | Where-Object { $_.status -ne 200 -or $_.error }).Count -gt 0) {
        Write-Output 'Some requests failed; inspect summary.json. A failed search is not reference evidence.'
    }
} finally { $client.Dispose(); $handler.Dispose() }
