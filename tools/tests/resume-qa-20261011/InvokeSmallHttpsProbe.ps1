#requires -Version 7.0
[CmdletBinding()]
param([switch]$Run)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'QaCommon.ps1')
if (-not $Run) {
    Write-Output 'PREPARED native host HTTPS probe, no JVM. Use -Run for RFC768 and Bing RSS GET only.'
    return
}
$id = 'host-https-' + [Guid]::NewGuid().ToString('N')
$root = Assert-QaOutputPath (Join-Path $PSScriptRoot "runs/$id")
New-Item -ItemType Directory -Path $root -Force | Out-Null
$handler = [Net.Http.HttpClientHandler]::new()
$handler.UseProxy = $false
$handler.AllowAutoRedirect = $false
$handler.UseCookies = $false
$handler.AutomaticDecompression = [Net.DecompressionMethods]::GZip -bor [Net.DecompressionMethods]::Deflate
$client = [Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(12)
$client.DefaultRequestHeaders.UserAgent.ParseAdd('DafeiyuGo-QA/0.1 (bounded host probe; no APK UID)')
$results = [Collections.Generic.List[object]]::new()
try {
    foreach ($case in @(
        @{ name = 'browser-target'; url = 'https://www.rfc-editor.org/rfc/rfc768.html'; maximum = 32KB },
        @{ name = 'search-transport'; url = 'https://www.bing.com/search?q=Kotlin%20official%20documentation&format=rss'; maximum = 64KB }
    )) {
        $response = $null
        $stream = $null
        $buffer = [byte[]]::new(4096)
        $body = [IO.MemoryStream]::new()
        $timer = [Diagnostics.Stopwatch]::StartNew()
        $record = [ordered]@{
            name = $case.name; url = $case.url; maximum_bytes = $case.maximum
            status = 'FAIL'; environment = 'host .NET HttpClient, direct public HTTPS, no proxy/cookies'
            production_client = $false; apk_uid = $false; model_called = $false
            started_at = [DateTimeOffset]::Now.ToString('O')
        }
        $deadline = [Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds(15))
        try {
            $requestUrl = [uri]$case.url
            $hops = [Collections.Generic.List[string]]::new()
            for ($hop = 0; $hop -lt 4; $hop++) {
                $response = $client.GetAsync($requestUrl, [Net.Http.HttpCompletionOption]::ResponseHeadersRead,
                    $deadline.Token).GetAwaiter().GetResult()
                if ([int]$response.StatusCode -notin @(301, 302, 303, 307, 308)) { break }
                Assert-Qa ($hop -lt 3 -and $null -ne $response.Headers.Location) 'Redirect limit/missing location'
                $next = [uri]::new($requestUrl, $response.Headers.Location)
                Assert-Qa ($next.Scheme -ceq 'https' -and $next.Port -eq 443 -and
                    [string]::IsNullOrEmpty($next.UserInfo) -and
                    ($next.Host -eq 'www.rfc-editor.org' -or $next.Host -eq 'bing.com' -or $next.Host.EndsWith('.bing.com'))) 'Redirect outside public HTTPS host allowlist'
                $hops.Add("$([int]$response.StatusCode) $requestUrl -> $next")
                $response.Dispose()
                $response = $null
                $requestUrl = $next
            }
            $record.redirects = $hops.ToArray()
            $record.final_url = $requestUrl.AbsoluteUri
            $record.http_status = [int]$response.StatusCode
            Assert-Qa ([int]$response.StatusCode -eq 200) 'Expected HTTP 200; redirect/retry not hidden'
            Assert-Qa (-not $response.Content.Headers.ContentLength -or
                $response.Content.Headers.ContentLength -le $case.maximum) 'Declared response exceeds small probe budget'
            $stream = $response.Content.ReadAsStreamAsync($deadline.Token).GetAwaiter().GetResult()
            while (($count = $stream.ReadAsync($buffer, 0, $buffer.Length, $deadline.Token).GetAwaiter().GetResult()) -gt 0) {
                Assert-Qa ($body.Length + $count -le $case.maximum) 'Actual response exceeds small probe budget'
                $body.Write($buffer, 0, $count)
            }
            $bytes = $body.ToArray()
            $record.response_bytes = $bytes.Length
            $record.response_sha256 = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
            $text = [Text.UTF8Encoding]::new($false, $true).GetString($bytes)
            if ($case.name -eq 'browser-target') {
                Assert-Qa ($response.Content.Headers.ContentType.MediaType -in @('text/html', 'application/xhtml+xml')) 'Expected static HTML'
                Assert-Qa ($text.Contains('User Datagram Protocol')) 'RFC768 body sentinel missing'
                $record.body_sentinel = 'User Datagram Protocol'
                $record.assertion_scope = 'small official static HTML target; production extraction remains deferred'
            } else {
                $settings = [Xml.XmlReaderSettings]::new()
                $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
                $settings.XmlResolver = $null
                $reader = [Xml.XmlReader]::Create([IO.StringReader]::new($text), $settings)
                try {
                    $xml = [Xml.XmlDocument]::new()
                    $xml.XmlResolver = $null
                    $xml.Load($reader)
                    $items = @($xml.SelectNodes('/rss/channel/item'))
                    Assert-Qa ($items.Count -gt 0) 'Bing RSS returned no items'
                    $record.result_count = $items.Count
                    $record.titles = @($items | Select-Object -First 3 | ForEach-Object { $_.SelectSingleNode('title').InnerText })
                    $record.assertion_scope = 'search transport/RSS shape only; relevance and production fallback not accepted'
                } finally { $reader.Dispose() }
            }
            [IO.File]::WriteAllBytes((Join-Path $root "$($case.name).response"), $bytes)
            $record.status = 'PASS'
        } catch {
            $record.error = $_.Exception.Message
        } finally {
            $record.elapsed_millis = $timer.ElapsedMilliseconds
            $record.finished_at = [DateTimeOffset]::Now.ToString('O')
            if ($stream) { $stream.Dispose() }
            if ($response) { $response.Dispose() }
            $body.Dispose()
            $deadline.Dispose()
        }
        $results.Add($record)
        Write-Output ("QA_HOST_HTTPS " + ($record | ConvertTo-Json -Depth 8 -Compress))
    }
} finally { $client.Dispose() }
$passed = @($results | Where-Object { $_.status -eq 'PASS' }).Count
[ordered]@{
    schema = 1; passed = $passed; total = 2; results = $results.ToArray()
    scope = 'native bounded host HTTPS probe; not Kotlin production smoke or APK UID evidence'
    production_smoke = 'DEFERRED by memory scheduling instruction; InvokeProductionWebSmoke.ps1'
} | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $root 'result.json') -Encoding utf8NoBOM
Write-Output "RESULT $passed/2 host probes; evidence=$root"
Assert-Qa ($passed -eq 2) 'Host probe failed; retain actual logs and do not count deferred production smoke as PASS'
