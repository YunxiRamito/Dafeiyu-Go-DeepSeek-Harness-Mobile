param()
$ErrorActionPreference = 'Stop'
$proxy = 'http://127.0.0.1:7890'
$outputRoot = Join-Path $PSScriptRoot ('references-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $outputRoot | Out-Null
$handler = [Net.Http.HttpClientHandler]::new()
$handler.Proxy = [Net.WebProxy]::new($proxy)
$handler.UseProxy = $true
$handler.AllowAutoRedirect = $true
$handler.MaxAutomaticRedirections = 2
$client = [Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(25)
$client.DefaultRequestHeaders.UserAgent.ParseAdd('Dafeiyu-Mobile-ReadOnly-Roadmap/1.0')
$targets = @(
    @{ repo = 'langchain-ai/langgraph'; ref = 'main'; path = 'README.md' },
    @{ repo = 'langchain-ai/langgraph'; ref = 'main'; path = 'libs/langgraph/langgraph/pregel/_loop.py' },
    @{ repo = 'pydantic/pydantic-ai'; ref = 'main'; path = 'pydantic_ai_slim/pydantic_ai/usage.py' },
    @{ repo = 'quickjs-ng/quickjs'; ref = 'master'; path = 'quickjs.h' },
    @{ repo = 'quickjs-ng/quickjs'; ref = 'master'; path = 'README.md' },
    @{ repo = 'bytecodealliance/wasmtime'; ref = 'main'; path = 'crates/wasmtime/src/config.rs' },
    @{ repo = 'wasm-micro-runtime/wasm-micro-runtime'; ref = 'main'; path = 'README.md' },
    @{ repo = 'wasm-micro-runtime/wasm-micro-runtime'; ref = 'main'; path = 'core/iwasm/include/wasm_export.h' },
    @{ repo = 'nodejs-mobile/nodejs-mobile'; ref = 'main'; path = 'README.md' },
    @{ repo = 'termux/termux-packages'; ref = 'master'; path = 'packages/nodejs-lts/build.sh' },
    @{ repo = 'DSH-APP/DSHA'; ref = '18eaee90616808455abb225a8ae8ce5ab37c7f40'; path = 'app/src/main/java/com/deepseekharness/app/runtime/RuntimeLauncher.java' },
    @{ repo = 'DSH-APP/DSHA'; ref = '18eaee90616808455abb225a8ae8ce5ab37c7f40'; path = 'app/src/main/java/com/deepseekharness/app/runtime/WebProcessManager.java' },
    @{ repo = 'DSH-APP/DSHA'; ref = '18eaee90616808455abb225a8ae8ce5ab37c7f40'; path = 'docs/security-model.md' }
)
$records = [Collections.Generic.List[object]]::new()
try {
    foreach ($target in $targets) {
        $url = "https://raw.githubusercontent.com/$($target.repo)/$($target.ref)/$($target.path)"
        $record = [ordered]@{
            url = $url; browser_url = "https://github.com/$($target.repo)/blob/$($target.ref)/$($target.path)"
            proxy = $proxy; observed_at = [DateTimeOffset]::Now.ToString('o')
            ref_is_fixed_commit = $target.ref -match '^[a-f0-9]{40}$'
            status = $null; response_file = $null; bytes = 0; sha256 = $null; error = $null
        }
        $response = $null
        try {
            $response = $client.GetAsync($url, [Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
            $record.status = [int]$response.StatusCode
            $record.final_url = $response.RequestMessage.RequestUri.AbsoluteUri
            if ($response.RequestMessage.RequestUri.Host -ne 'raw.githubusercontent.com') { throw 'Unexpected redirect host.' }
            $stream = $response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
            $memory = [IO.MemoryStream]::new()
            $buffer = [byte[]]::new(8192)
            try {
                while (($count = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                    if ($memory.Length + $count -gt 512KB) { throw 'Reference text exceeds 512 KiB.' }
                    $memory.Write($buffer, 0, $count)
                }
                $bytes = $memory.ToArray()
            } finally { $stream.Dispose(); $memory.Dispose() }
            $filename = ($target.repo + '--' + $target.path).Replace('/', '--') + '.log'
            $path = Join-Path $outputRoot $filename
            [IO.File]::WriteAllBytes($path, $bytes)
            $record.response_file = $filename
            $record.bytes = $bytes.Length
            $record.sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
            if ($record.status -ne 200) { throw "HTTP $($record.status)" }
        } catch { $record.error = $_.Exception.Message }
        finally { if ($null -ne $response) { $response.Dispose() } }
        $records.Add([pscustomobject]$record)
        Write-Output "GET $url -> $($record.status) $($record.error)"
    }
    [ordered]@{
        observed_at = [DateTimeOffset]::Now.ToString('o'); proxy = $proxy
        scope = 'Reference text saved as inert .log data, never imported, installed, sourced or executed. Branch references are observed snapshots, not pinned releases.'
        requests = $records.ToArray()
    } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $outputRoot 'summary.json') -Encoding utf8
    Write-Output "Evidence: $outputRoot"
} finally { $client.Dispose(); $handler.Dispose() }
