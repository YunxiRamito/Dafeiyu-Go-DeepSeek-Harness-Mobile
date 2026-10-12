param(
    [ValidateSet('BingRss', 'BingHtml', 'DdgHtml', 'DdgLite', 'Page')]
    [string]$Provider = 'BingRss',
    [switch]$UseHostProxy
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
$testRoot = Join-Path $tempRoot ('qa\public-web-paths\run-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$query = [Uri]::EscapeDataString('Kotlin official documentation')
$urls = @{
    BingRss = "https://www.bing.com/search?format=rss&q=$query"
    BingHtml = "https://www.bing.com/search?q=$query"
    DdgHtml = "https://html.duckduckgo.com/html/?q=$query"
    DdgLite = "https://lite.duckduckgo.com/lite/?q=$query"
    Page = 'https://kotlinlang.org/docs/home.html'
}
$report = [ordered]@{
    observed_at_utc = [DateTime]::UtcNow.ToString('o')
    provider = $Provider
    environment = 'Windows host; not Android'
    transport = $(if ($UseHostProxy) { 'explicit host proxy 127.0.0.1:7890' } else { 'host default route' })
    requested_url = $urls[$Provider]
    success = $false
    status = $null
    content_type = $null
    sources = @()
    failure = $null
}
$timer = [Diagnostics.Stopwatch]::StartNew()
try {
    $options = @{
        Uri = $urls[$Provider]
        UseBasicParsing = $true
        TimeoutSec = 20
        MaximumRedirection = 3
        Headers = @{ 'User-Agent' = 'Dafeiyu-Go-Offline-QA/1.0'; 'Accept' = 'text/html,application/rss+xml,application/xml;q=0.9' }
    }
    if ($UseHostProxy) { $options.Proxy = 'http://127.0.0.1:7890' }
    $response = Invoke-WebRequest @options
    $report.status = [int]$response.StatusCode
    $report.content_type = [string]$response.Headers['Content-Type']
    $content = [string]$response.Content
    $report.received_characters = $content.Length
    if ($content.Length -gt 2 * 1024 * 1024) { throw 'Public response exceeds probe character cap' }
    if ($Provider -eq 'BingRss') {
        $settings = New-Object System.Xml.XmlReaderSettings
        $settings.DtdProcessing = [System.Xml.DtdProcessing]::Prohibit
        $settings.XmlResolver = $null
        $reader = [System.Xml.XmlReader]::Create((New-Object System.IO.StringReader($content)), $settings)
        try {
            $document = New-Object System.Xml.XmlDocument
            $document.XmlResolver = $null
            $document.Load($reader)
            $report.sources = @($document.SelectNodes('/rss/channel/item') | Select-Object -First 5 |
                ForEach-Object { [ordered]@{ title = [string]$_.title; url = [string]$_.link } })
        } finally { $reader.Dispose() }
        if ($report.sources.Count -eq 0) { throw 'RSS contains no result items' }
    } elseif ($Provider -in @('DdgHtml', 'DdgLite')) {
        $pattern = '<a\b(?=[^>]*class=["''][^"'']*(?:result__a|result-link))[^>]*href=["'']([^"'']+)["''][^>]*>(.*?)</a>'
        $matches = [regex]::Matches($content, $pattern, 'IgnoreCase, Singleline')
        $report.sources = @($matches | Select-Object -First 5 | ForEach-Object {
            $href = [System.Net.WebUtility]::HtmlDecode($_.Groups[1].Value)
            $target = if ($href -match '[?&]uddg=([^&]+)') { [Uri]::UnescapeDataString($Matches[1]) } else { $href }
            [ordered]@{
                title = [System.Net.WebUtility]::HtmlDecode(($_.Groups[2].Value -replace '<[^>]*>', '')).Trim()
                url = $target
            }
        })
        if ($report.sources.Count -eq 0) {
            if ($content -match 'anomaly|challenge|captcha|Unfortunately, bots') { throw 'Search challenge; not search results' }
            throw 'No structured DDG result links'
        }
    } elseif ($Provider -eq 'BingHtml') {
        $matches = [regex]::Matches($content, '<h2[^>]*>\s*<a[^>]*href=["'']([^"'']+)["''][^>]*>(.*?)</a>', 'IgnoreCase, Singleline')
        $report.sources = @($matches | Select-Object -First 5 | ForEach-Object {
            [ordered]@{
                title = [System.Net.WebUtility]::HtmlDecode(($_.Groups[2].Value -replace '<[^>]*>', '')).Trim()
                url = [System.Net.WebUtility]::HtmlDecode($_.Groups[1].Value)
            }
        })
        if ($report.sources.Count -eq 0) { throw 'No structured Bing result links; page is not usable search evidence' }
    } else {
        if ($content -notmatch 'Kotlin' -or $content -notmatch '<title>') { throw 'Unexpected public documentation page' }
        $report.sources = @([ordered]@{ title = 'Kotlin documentation'; url = $urls[$Provider] })
    }
    $report.success = $true
} catch {
    $report.failure = $_.Exception.Message
} finally {
    $timer.Stop()
    $report.elapsed_ms = $timer.ElapsedMilliseconds
    $reportPath = Join-Path $testRoot 'public-network-result.json'
    $report | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $reportPath -Encoding UTF8
    $report | ConvertTo-Json -Depth 5 | Write-Output
    Write-Output "Evidence: $reportPath"
}
