#requires -Version 7.0
[CmdletBinding()]
param(
    [switch]$Run,
    [switch]$DiagnoseScroll,
    [switch]$FinalReady,
    [switch]$MainAgentOwnsEmulator,
    [string]$ApkPath,
    [string]$ExpectedApkSha256,
    [string]$ExpectedVersion = '0.1.19',
    [int]$ExpectedVersionCode = 20,
    [string]$FreezeManifestPath,
    [ValidateSet('Legacy', 'Short', 'LongChat')][string]$Matrix = 'Legacy',
    [ValidateSet('Receipts', 'CopyReference', 'RunInfo320')][string[]]$ShortCases = @('Receipts', 'CopyReference', 'RunInfo320'),
    [switch]$OwnsDisposableClipboard,
    [ValidateRange(1, 160)][int]$LongMaxSwipes = 120,
    [ValidateRange(1, 900)][int]$LongMaxSeconds = 600,
    [ValidateRange(1, 300)][int]$LongNoProgressSeconds = 90,
    [string]$AaptPath = 'G:\Android SDK\build-tools\36.0.0\aapt.exe',
    [string]$Serial,
    [string]$AdbPath = 'G:\Android SDK\platform-tools\adb.exe'
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'QaCommon.ps1')
. (Join-Path $PSScriptRoot 'QaLongChat.ps1')
. (Join-Path $PSScriptRoot 'QaBatch020.ps1')
$binding = Get-QaVersionBinding $ExpectedVersion $ExpectedVersionCode
$fixtureRoot = Join-Path $PSScriptRoot 'fixtures'
$index = Test-QaFixture $fixtureRoot
$manifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'fixture-manifest.json') -Raw | ConvertFrom-Json
$actual = Get-QaHashes $fixtureRoot
Assert-Qa (($actual | ConvertTo-Json -Compress) -ceq ($manifest.fixture | ConvertTo-Json -Compress)) 'Fixture hashes changed; review/regenerate manifest'
if (-not $Run) {
    Write-Output "PREPARED $($binding.version)/code$($binding.version_code) $Matrix fixture/hash checks only. Main agent owns all device actions. No ADB executed."
    return
}
Assert-Qa ($FinalReady -and $MainAgentOwnsEmulator) 'Main-agent FINAL_READY and exclusive emulator ownership required'
Assert-Qa ($Serial -match '^emulator-\d+$') 'Only an explicitly selected emulator is accepted; phones forbidden'
Assert-Qa (-not $DiagnoseScroll -or $Matrix -eq 'Legacy') 'DiagnoseScroll requires Legacy matrix'
Assert-Qa ($binding.version -ceq '0.1.19' -or $Matrix -ne 'Legacy') '020 requires Short or LongChat; historical 48-swipe matrix is 019-only'
Assert-Qa ($Matrix -ne 'Short' -or $ShortCases.Count -gt 0) 'Select at least one short case'
Assert-Qa ($Matrix -ne 'Short' -or 'CopyReference' -notin $ShortCases -or $OwnsDisposableClipboard) 'CopyReference requires explicit OwnsDisposableClipboard; other clipboard formats/history are not backed up'
$null = & (Join-Path $PSScriptRoot 'VerifyFreeze.ps1') -CheckSourceContracts -ManifestPath $FreezeManifestPath -ExpectedVersion $binding.version -ExpectedVersionCode $binding.version_code
$artifact = & (Join-Path $PSScriptRoot 'VerifyFinalApk.ps1') -ApkPath $ApkPath -ExpectedApkSha256 $ExpectedApkSha256 -ExpectedVersion $binding.version -ExpectedVersionCode $binding.version_code -AaptPath $AaptPath
Assert-Qa (Test-Path -LiteralPath $AdbPath -PathType Leaf) 'ADB missing'
$package = 'com.dafeiyu.mobile.preview'
$runId = 'activity-' + $binding.version.Replace('0.1.', '0') + '-' + [Guid]::NewGuid().ToString('N')
$resultRoot = Assert-QaOutputPath (Join-Path $PSScriptRoot "runs/$runId")
New-Item -ItemType Directory -Path $resultRoot -Force | Out-Null
$remote = "/data/local/tmp/$runId"
$backup = "no_backup/$runId-backup"
$items = @(
    [pscustomobject]@{ Path = 'files/Dafeiyu-Works'; Backup = "$backup/workspace" },
    [pscustomobject]@{ Path = 'no_backup/background-tasks'; Backup = "$backup/tasks" },
    [pscustomobject]@{ Path = 'no_backup/official-tool-outputs'; Backup = "$backup/outputs" },
    [pscustomobject]@{ Path = 'no_backup/conversation-attachments'; Backup = "$backup/attachments" },
    [pscustomobject]@{ Path = 'shared_prefs'; Backup = "$backup/preferences" }
)
$records = @()
$mutationStarted = $false
$stopped = $false
$remoteCreated = $false
$displayChanged = $false
$originalSize = 'reset'
$sequence = 0
$title = 'QA 019 grouped activities'
$metrics = Get-QaCompleted019Metrics
$report = [Collections.Generic.List[string]]::new()
$cleanup = [Collections.Generic.List[string]]::new()
$batchStatus = 'INCOMPLETE'
$batchCases = [Collections.Generic.List[object]]::new()
$adbDeadline = $null

function Adb([string[]]$Arguments) {
    # Every device call is bounded; a stuck dump must not defeat the leg budget.
    $timeout = 30000
    if ($null -ne $script:adbDeadline) {
        $timeout = [int][Math]::Min($timeout, ($script:adbDeadline - [DateTimeOffset]::UtcNow).TotalMilliseconds)
        if ($timeout -le 0) { throw 'INCOMPLETE: LongChat leg time budget exhausted before ADB' }
    }
    $start = [Diagnostics.ProcessStartInfo]::new($AdbPath)
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in @('-s', $Serial) + $Arguments) { $start.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    try {
        $null = $process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($timeout)) {
            $process.Kill($true)
            $null = $process.WaitForExit(5000)
            throw "INCOMPLETE: ADB command exceeded ${timeout}ms budget: $($Arguments -join ' ')"
        }
        $output = $stdout.GetAwaiter().GetResult()
        $errorText = $stderr.GetAwaiter().GetResult()
        Assert-Qa ($process.ExitCode -eq 0) "ADB failed: $($Arguments -join ' '): $errorText"
        return $output.TrimEnd()
    } finally { $process.Dispose() }
}
function Private([string]$Command) {
    Assert-Qa (-not $Command.Contains("'")) 'Unsafe private shell quote'
    return Adb @('shell', "run-as $package sh -c 'set -e; $Command'")
}
function Exists([string]$Path) {
    $answer = (Private "if [ -e $Path ] || [ -L $Path ]; then echo yes; else echo no; fi").Trim()
    Assert-Qa ($answer -cin @('yes', 'no')) 'Unexpected private path existence result'
    return $answer -ceq 'yes'
}
function QaCopy([string]$Left, [string]$Right) { $null = Private "cp -R $Left $Right" }
function QaRemove([string]$Path) {
    Assert-Qa ($Path -in $items.Path) 'Removal outside exact transaction allowlist'
    $null = Private "rm -rf $Path"
}
function QaCompare([string]$Left, [string]$Right) {
    Assert-Qa ($Left -in $items.Backup -and $Right -in $items.Path) 'Comparison outside transaction'
    # Toybox manifests include all directory/file names and bytes without exposing user payloads.
    $command = "test -z `"`$(find $Left $Right ! -type f ! -type d -print)`"; " +
        "for tree in $Left $Right; do (cd `"`$tree`"; find . -type d | sort; " +
        "find . -type f -exec sha256sum {} \; | sort) > $backup/manifest-`$(basename `"`$tree`"); done; " +
        "cmp $backup/manifest-`$(basename $Left) $backup/manifest-`$(basename $Right)"
    $null = Private $command
}
function QaAssertBackupParent {
    $state = (Private 'if [ -d no_backup ] && [ ! -L no_backup ]; then echo ready; else echo missing-or-unsafe; fi').Trim()
    Assert-Qa ($state -ceq 'ready') ('Backup parent no_backup missing or unsafe. Fresh install prerequisite: ' +
        'main agent must first launch/stop the credential-absent App on an isolated emulator to initialize no_backup; ' +
        'runner does not create this root.')
}
function Capture([string]$Name) {
    $script:sequence++
    $stem = '{0:D3}-{1}' -f $script:sequence, $Name
    $null = Adb @('shell', 'uiautomator', 'dump', "$remote/ui.xml")
    $null = Adb @('pull', "$remote/ui.xml", (Join-Path $resultRoot "$stem.xml"))
    $null = Adb @('shell', 'screencap', '-p', "$remote/ui.png")
    $null = Adb @('pull', "$remote/ui.png", (Join-Path $resultRoot "$stem.png"))
    $xmlPath = Join-Path $resultRoot "$stem.xml"
    $script:lastCapture = [pscustomobject]@{
        xml = "$stem.xml"; png = "$stem.png"
        xml_sha256 = (Get-FileHash -LiteralPath $xmlPath).Hash.ToLowerInvariant()
        png_sha256 = (Get-FileHash -LiteralPath (Join-Path $resultRoot "$stem.png")).Hash.ToLowerInvariant()
    }
    Assert-Qa ((Get-Item -LiteralPath $xmlPath).Length -le 1MB) 'UI XML exceeds bounded QA memory budget'
    return [xml](Get-Content -LiteralPath $xmlPath -Raw -Encoding utf8)
}
function QaCaptureChat([string]$Name, [xml]$FirstDocument) {
    for ($attempt = 0; $attempt -lt 3; $attempt++) {
        $doc = $null
        try {
            $doc = if ($attempt -eq 0 -and $null -ne $FirstDocument) { $FirstDocument }
                else { Capture "$Name-scene-$attempt" }
            # Positive evidence of another scene is never treated as a missing dump subtree.
            if (Get-QaNode $doc '关闭侧栏') { throw 'Scene drift: sidebar is open' }
            if (Get-QaNode $doc '停止生成') { throw 'Scene drift: unexpected generation' }
            Assert-QaChat $doc $title $metrics
            return $doc
        } catch {
            $reason = $_.Exception.Message
            $report.Add("CAPTURE scene rejected $Name attempt=$($attempt + 1)/3: $reason")
            if ($reason -in @('Scene drift: sidebar is open', 'Scene drift: unexpected generation') -or
                $attempt -eq 2) { throw }
            Start-Sleep -Milliseconds 350
        }
    }
}
function QaChatSurfaceFingerprint([xml]$Document) {
    $surface = Get-QaChatSurface $Document
    Assert-Qa ($null -ne $surface) 'No vertical chat scroll surface; fingerprint unavailable'
    $text = [Text.StringBuilder]::new()
    foreach ($node in @($surface) + @($surface.SelectNodes('.//node'))) {
        foreach ($attribute in @('class', 'bounds', 'text', 'content-desc', 'clickable', 'long-clickable', 'scrollable')) {
            $value = $node.GetAttribute($attribute)
            $null = $text.Append($value.Length).Append(':').Append($value).Append('|')
        }
        $null = $text.AppendLine()
    }
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData(
        [Text.Encoding]::UTF8.GetBytes($text.ToString()))).ToLowerInvariant()
}
function QaDiagnosticRightPaddingX([xml]$Document) {
    Assert-QaChat $Document $title $metrics
    $surface = Get-QaChatSurface $Document
    Assert-Qa ($null -ne $surface) 'No vertical chat scroll surface; no padding fallback'
    $b = Get-QaBounds $surface
    $occupiedRight = $b[0]
    $occupied = 0
    foreach ($node in $surface.SelectNodes('.//node')) {
        if ($node.GetAttribute('bounds') -ceq '[0,0][0,0]') { continue }
        if ($node.GetAttribute('text') -eq '' -and $node.GetAttribute('content-desc') -eq '' -and
            $node.GetAttribute('clickable') -ne 'true' -and $node.GetAttribute('long-clickable') -ne 'true' -and
            $node.GetAttribute('scrollable') -ne 'true' -and
            $node.GetAttribute('class') -notin @('android.widget.Button', 'android.widget.EditText', 'android.widget.ScrollView')) {
            continue
        }
        $child = Get-QaBounds $node
        if ($child[3] -le $b[1] -or $child[1] -ge $b[3]) { continue }
        $occupiedRight = [Math]::Max($occupiedRight, $child[2])
        $occupied++
    }
    Assert-Qa ($occupied -gt 0 -and $occupiedRight -gt $b[0] -and $b[2] - $occupiedRight -ge 8) `
        'No recorded right padding corridor; diagnostic will not invent a screen coordinate'
    $x = [int][Math]::Floor(($occupiedRight + $b[2]) / 2.0)
    Assert-Qa ($x -gt $occupiedRight -and $x -lt $b[2]) 'Right padding candidate outside chat'
    return $x
}
function Tap($Node) {
    Assert-QaTap $Node
    $b = Get-QaBounds $Node
    $null = Adb @('shell', 'input', 'tap', [string][int](($b[0] + $b[2]) / 2), [string][int](($b[1] + $b[3]) / 2))
    Start-Sleep -Milliseconds 350
}
function Swipe([xml]$Document, [bool]$Earlier, [Nullable[int]]$DiagnosticX) {
    Assert-QaChat $Document $title $metrics
    $surface = Get-QaChatSurface $Document
    Assert-Qa ($null -ne $surface) 'No vertical chat scroll surface; no coordinate fallback'
    $b = Get-QaBounds $surface
    $x = if ($null -ne $DiagnosticX) { [int]$DiagnosticX } else { [int](($b[0] + $b[2]) / 2) }
    Assert-Qa ($x -gt $b[0] -and $x -lt $b[2]) 'Swipe x outside actual chat surface'
    $top = [string][int]($b[1] + ($b[3] - $b[1]) * .2)
    $bottom = [string][int]($b[1] + ($b[3] - $b[1]) * .8)
    $from = if ($Earlier) { $top } else { $bottom }
    $to = if ($Earlier) { $bottom } else { $top }
    $hits = @($surface.SelectNodes('.//node') | Where-Object {
        if ($_.GetAttribute('bounds') -ceq '[0,0][0,0]') { return $false }
        $child = Get-QaBounds $_
        $x -ge $child[0] -and $x -lt $child[2] -and [int]$from -ge $child[1] -and [int]$from -lt $child[3]
    } | ForEach-Object {
        [ordered]@{ class = $_.GetAttribute('class'); bounds = $_.GetAttribute('bounds')
            text = $_.GetAttribute('text'); description = $_.GetAttribute('content-desc')
            clickable = $_.GetAttribute('clickable'); long_clickable = $_.GetAttribute('long-clickable') }
    })
    $record = [ordered]@{
        recorded_at = [DateTimeOffset]::Now.ToString('O'); earlier = $Earlier
        bounds = $surface.GetAttribute('bounds'); from = @($x, [int]$from); to = @($x, [int]$to)
        duration_ms = 400; lane = if ($null -ne $DiagnosticX) { 'diagnostic-right-padding' } else { 'center' }
        surface_sha256_before = QaChatSurfaceFingerprint $Document; start_hits = $hits; command_succeeded = $false
    }
    $report.Add("SWIPE lane=$($record.lane) earlier=$Earlier bounds=$($record.bounds) from=($x,$from) to=($x,$to) duration=400ms")
    try {
        $null = Adb @('shell', 'input', 'swipe', [string]$x, $from, [string]$x, $to, '400')
        $record.command_succeeded = $true
    } finally {
        $record | ConvertTo-Json -Depth 7 -Compress |
            Add-Content -LiteralPath (Join-Path $resultRoot 'swipe-log.jsonl') -Encoding utf8NoBOM
    }
}
function Find([string]$Label, [string]$Name, [bool]$Earlier = $true, [switch]$Contains) {
    foreach ($direction in @($Earlier, (-not $Earlier))) {
        $missing = 0
        for ($i = 0; $i -lt 24; $i++) {
            $doc = QaCaptureChat "$Name-$direction-$i"
            Assert-QaChat $doc $title $metrics
            $node = Get-QaVisibleChatNode $doc $Label -Contains:$Contains
            if ($node) { return [pscustomobject]@{ Document = $doc; Node = $node } }
            if ($null -eq (Get-QaChatSurface $doc)) {
                $missing++
                Assert-Qa ($missing -lt 3) 'No vertical chat scroll surface after 3 captures'
                continue
            }
            $missing = 0
            Swipe $doc $direction
        }
    }
    throw "Label not found after bounded chat scrolling: $Label"
}
function SelectConversation([string]$ExpectedTitle, [string[]]$ExpectedMetrics) {
    $doc = QaCaptureChat 'before-sidebar'
    Assert-QaChat $doc $title $metrics
    Tap (Get-QaNode $doc '打开菜单')
    $doc = Capture 'sidebar-open'
    Assert-QaSidebar $doc
    $target = Get-QaNode $doc $ExpectedTitle
    Assert-Qa ($null -ne $target) 'Synthetic sidebar target missing; drawer scrolling must use a separate selector'
    Tap $target
    $script:title = $ExpectedTitle
    $script:metrics = $ExpectedMetrics
    $ready = $false
    for ($i = 0; $i -lt 4; $i++) {
        $doc = Capture 'after-sidebar-selection'
        if (Get-QaNode $doc '关闭侧栏') { Start-Sleep -Milliseconds 350; continue }
        $doc = QaCaptureChat 'after-sidebar-chat' -FirstDocument $doc
        $ready = $true
        break
    }
    Assert-Qa $ready 'Sidebar did not close after selection; no chat gesture'
}
function MemorySample([string]$Name) {
    Adb @('shell', 'dumpsys', 'meminfo', $package) |
        Set-Content -LiteralPath (Join-Path $resultRoot "$Name-meminfo.txt") -Encoding utf8NoBOM
}

try {
    Assert-Qa ((Adb @('get-state')).Trim() -ceq 'device') 'Emulator is not ready'
    $dump = Adb @('shell', 'dumpsys', 'package', $package)
    $paths = (Adb @('shell', 'pm', 'path', $package)).Trim() -split '\r?\n'
    Assert-Qa ($paths.Count -eq 1 -and $paths[0] -match '^package:(/data/app/[A-Za-z0-9_/=+.~-]+/base\.apk)$') 'Expected one safe installed APK path'
    $installedPath = $Matches[1]
    $hashOutput = Adb @('shell', 'sha256sum', $installedPath)
    Assert-Qa ($hashOutput -match '^([a-fA-F0-9]{64})\s') 'Installed APK hash unavailable'
    $installedHash = $Matches[1]
    Assert-QaInstalledMetadata $dump $installedHash $artifact.sha256 $binding.version $binding.version_code
    $credentialState = Get-QaAppCredentialState ${function:Exists}
    [ordered]@{
        package = $package; paths = $credentialState
        scope = 'Only tested App provider credential family existence; no payload read, no other App or whole-device claim'
    } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $resultRoot 'credential-gate.json') -Encoding utf8NoBOM
    Assert-QaAppCredentialsAbsent $credentialState
    $report.Add('PASS tested App credential family absent; other Apps and whole-device credentials are outside this assertion')
    QaAssertBackupParent
    $report.Add('PASS existing no_backup is a normal directory; root creation/deletion is outside this transaction')
    [ordered]@{
        artifact = $artifact; serial = $Serial; installed_sha256 = $installedHash
        fixture = $actual; started_at = [DateTimeOffset]::Now.ToString('O')
        evidence_scope = 'synthetic fixture UI only; no model request'
        mode = if ($DiagnoseScroll) { 'DIAGNOSE_SCROLL_TWO_SWIPES' } else { $Matrix }
        short_cases = $ShortCases; disposable_clipboard_owned = [bool]$OwnsDisposableClipboard
    } | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $resultRoot 'gate.json') -Encoding utf8NoBOM
    $size = Adb @('shell', 'wm', 'size')
    $densityOutput = Adb @('shell', 'wm', 'density')
    $size | Set-Content -LiteralPath (Join-Path $resultRoot 'original-size.txt')
    $densityOutput | Set-Content -LiteralPath (Join-Path $resultRoot 'original-density.txt')
    if ($size -match 'Override size:\s*(\d+x\d+)') { $originalSize = $Matches[1] }
    $density = if ($densityOutput -match 'Override density:\s*(\d+)') { [int]$Matches[1] }
        elseif ($densityOutput -match 'Physical density:\s*(\d+)') { [int]$Matches[1] }
        else { throw 'Display density unavailable' }
    $null = Adb @('shell', 'am', 'force-stop', $package)
    $stopped = $true
    $null = Private "mkdir $backup"
    $records = Save-QaState $items ${function:Exists} ${function:QaCopy} ${function:QaCompare}
    $records | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $resultRoot 'restore-records.json') -Encoding utf8NoBOM
    $null = Adb @('shell', 'mkdir', $remote)
    $remoteCreated = $true
    $null = Adb @('push', "$fixtureRoot/.", $remote)
    foreach ($file in $actual) {
        $hash = Adb @('shell', 'sha256sum', "$remote/$($file.path)")
        Assert-Qa ($hash.StartsWith($file.sha256, [StringComparison]::OrdinalIgnoreCase)) 'Uploaded fixture bytes changed'
    }
    # Replace entire roots only after all backups and all uploaded fixture hashes have passed.
    $mutationStarted = $true
    foreach ($item in $items) { QaRemove $item.Path }
    $null = Private 'mkdir -p files/Dafeiyu-Works no_backup/background-tasks'
    QaCopy "$remote/workspace-index.json" 'files/Dafeiyu-Works/workspace-index.json'
    QaCopy "$remote/tasks.json" 'no_backup/background-tasks/tasks.json'
    QaCopy "$remote/official-tool-outputs" 'no_backup/official-tool-outputs'
    # Recheck immediately before launch; no QA action may create or remove a credential.
    Assert-QaAppCredentialsAbsent (Get-QaAppCredentialState ${function:Exists})
    $null = Adb @('shell', 'am', 'start', '-n', "$package/com.dafeiyu.mobile.MainActivity")
    Start-Sleep -Seconds 2
    MemorySample 'activity-baseline'
    if ($Matrix -ne 'Legacy') {
        if ($Matrix -eq 'LongChat') { Invoke-QaBatchLong }
        else { Invoke-QaBatchShort }
        $report.Add("$batchStatus $Matrix; see batch-result.json and hashed XML/PNG evidence")
        return
    }
    if ($DiagnoseScroll) {
        $doc = QaCaptureChat 'diagnose-collapsed'
        $completedNode = Get-QaNode $doc '已完成 · 用时 2 分 5 秒'
        Assert-Qa ($null -ne $completedNode) 'Diagnostic collapsed label must already be visible; no search loop'
        Assert-Qa ($null -eq (Get-QaNode $doc 'QA_EARLIER_ROUND' -Contains)) `
            'Diagnostic initial process must be collapsed; do not toggle an unknown expanded state'
        Tap $completedNode
        $doc = QaCaptureChat 'diagnose-expanded'
        Assert-Qa ($null -ne (Get-QaNode $doc 'QA_EARLIER_ROUND_MUST_NOT_BE_FINAL_BODY' -Contains)) `
            'Diagnostic expansion not visible; do not swipe an unverified process scene'
        $before = QaChatSurfaceFingerprint $doc
        Swipe $doc $true
        $doc = QaCaptureChat 'diagnose-after-center'
        $after = QaChatSurfaceFingerprint $doc
        $report.Add("DIAGNOSE center surface_before=$before surface_after=$after changed=$($before -cne $after)")
        $rightX = QaDiagnosticRightPaddingX $doc
        $surfaceBounds = Get-QaBounds (Get-QaChatSurface $doc)
        Assert-Qa ($rightX -ne [int](($surfaceBounds[0] + $surfaceBounds[2]) / 2)) 'Diagnostic x must differ from center'
        $before = $after
        Swipe $doc $true -DiagnosticX $rightX
        $doc = QaCaptureChat 'diagnose-after-right-padding'
        $after = QaChatSurfaceFingerprint $doc
        $report.Add("DIAGNOSE right-padding x=$rightX surface_before=$before surface_after=$after changed=$($before -cne $after)")
        MemorySample 'diagnose-scroll'
        $report.Add('DIAGNOSTIC ONLY collapsed->expand and two different-x swipes captured; no full activity UI PASS claimed')
        return
    }
    $completed = Find '已完成 · 用时 2 分 5 秒' 'completed-collapsed'
    Assert-Qa ($null -eq (Get-QaNode $completed.Document 'QA_EARLIER_ROUND' -Contains)) 'Collapsed process contains earlier-round text'
    Tap $completed.Node
    foreach ($label in @('已建立 3 个子智能体', '编写了 2 个文件', '查看了 1 个图片',
        '运行了 3 个命令', '已调用 Skill 1 次：research', 'QA_EARLIER_ROUND_MUST_NOT_BE_FINAL_BODY')) {
        $null = Find $label 'expanded-receipts' $true -Contains
    }
    MemorySample 'expanded-receipts'
    $report.Add('PASS terminal collapse and expanded bounded activity/image receipts; image is display-only metadata, no bitmap decode tested')
    $completed = Find '已完成 · 用时 2 分 5 秒' 'collapse'
    Tap $completed.Node
    $final = Find 'QA_FINAL_REPLY_019' 'final-body' $false -Contains
    Assert-Qa ($null -eq (Get-QaNode $final.Document 'QA_EARLIER_ROUND' -Contains)) 'Earlier-round process leaked into final body'
    $report.Add('PASS final-body association after manual collapse')
    for ($cycle = 0; $cycle -lt 2; $cycle++) {
        $completed = Find '已完成 · 用时 2 分 5 秒' 'receipt-repeat-expand'
        Tap $completed.Node
        $null = Find '查看了 1 个图片' 'receipt-repeat-image' $true -Contains
        $completed = Find '已完成 · 用时 2 分 5 秒' 'receipt-repeat-collapse'
        Tap $completed.Node
        $null = Find 'QA_FINAL_REPLY_019' 'receipt-repeat-final' $false -Contains
    }
    MemorySample 'receipts-repeated'
    $report.Add('PASS 3 bounded process expansion/collapse cycles with image receipt metadata; actual bitmap memory remains deferred')
    SelectConversation 'QA 019 bounded long chat' @()
    $null = Find 'QA_LONG_FIRST_019' 'long-first' $true -Contains
    $null = Find 'QA_LONG_LAST_019' 'long-last' $false -Contains
    MemorySample 'long-chat-first-pass'
    for ($i = 0; $i -lt 2; $i++) {
        $null = Find 'QA_LONG_FIRST_019' 'long-first-repeat' $true -Contains
        $null = Find 'QA_LONG_LAST_019' 'long-last-repeat' $false -Contains
    }
    MemorySample 'long-chat-repeated'
    $report.Add('PASS both long-chat ends reachable across 3 cycles; no generation input')
    $displayChanged = $true
    $width = [int][Math]::Round(320 * $density / 160)
    $height = [int][Math]::Round(640 * $density / 160)
    $null = Adb @('shell', 'wm', 'size', "${width}x${height}")
    $doc = QaCaptureChat 'narrow-long-chat'
    Assert-QaChat $doc $title $metrics
    Assert-Qa ($null -ne (Get-QaChatSurface $doc)) 'Narrow long chat has no usable vertical surface'
    SelectConversation 'QA 019 grouped activities' (Get-QaCompleted019Metrics)
    $doc = QaCaptureChat 'narrow-current-run-info'
    Assert-QaChat $doc $title $metrics
    $report.Add('PASS current merged run-info accessibility values and composer separation at 320dp; compact glyph/text wrapping requires PNG review')
    SelectConversation 'QA 019 interrupted activities' @()
    $incomplete = Find '未完成 · 用时 3 秒' 'interrupted-collapsed'
    Tap $incomplete.Node
    foreach ($label in @('未完成', '失败', '已取消等待')) { $null = Find $label 'interrupted-states' $true -Contains }
    $report.Add('PASS interrupted terminal labels; no queue entry resumed')
    $appProcessId = (Adb @('shell', 'pidof', $package)).Trim()
    Assert-Qa ($appProcessId -match '^\d+$') 'Expected one live app process for memory evidence'
    Adb @('logcat', '-d', "--pid=$appProcessId", '-t', '400') |
        Set-Content -LiteralPath (Join-Path $resultRoot 'app-logcat.txt') -Encoding utf8NoBOM
    $log = Get-Content -LiteralPath (Join-Path $resultRoot 'app-logcat.txt') -Raw
    Assert-Qa ($log -notmatch 'OutOfMemoryError|FATAL EXCEPTION|ANR in') 'App crash/OOM marker found'
    $report.Add('PASS current PID log sample contains no OOM/fatal/ANR marker; peak memory/leak and prior killed PID remain manual review')
    $report.Add('DEFERRED copy/reference clipboard: requires isolated emulator clipboard ownership and exact byte validation')
    $report.Add('DEFERRED compact metric pixels/wrapping, IME anchoring, archive, workspace removal, footer, large font, actual image decode and APK UID network; see README matrix')
} catch {
    $batchStatus = if ($_.Exception.Message.StartsWith('INCOMPLETE:')) { 'INCOMPLETE' } else { 'FAIL' }
    $report.Add("${batchStatus}: $($_.Exception.Message)")
    throw
} finally {
    $script:adbDeadline = $null
    $safe = $true
    $restored = -not $mutationStarted
    if ($stopped) {
        try { $null = Adb @('shell', 'am', 'force-stop', $package) }
        catch { $safe = $false; $cleanup.Add('Force-stop failed; retain backup and do not overwrite potentially running app') }
    }
    if ($mutationStarted -and $safe) {
        try {
            Restore-QaState $records ${function:QaRemove} ${function:QaCopy} ${function:QaCompare} ${function:Exists}
            $restored = $true
            $report.Add('RESTORED full workspace/task/output roots, complete membership+SHA256 and original absence')
        } catch { $cleanup.Add($_.Exception.Message) }
    }
    if ($displayChanged) {
        try { $null = Adb @('shell', 'wm', 'size', $originalSize) }
        catch { $cleanup.Add("Display restore: $($_.Exception.Message)") }
    }
    if ($safe -and $restored -and $cleanup.Count -eq 0) {
        try {
            Assert-Qa ($backup -match '^no_backup/activity-0(?:19|20)-[a-f0-9]{32}-backup$') 'Unsafe backup cleanup'
            if ($stopped) { $null = Private "rm -rf $backup" }
            Assert-Qa ($remote -match '^/data/local/tmp/activity-0(?:19|20)-[a-f0-9]{32}$') 'Unsafe remote cleanup'
            if ($remoteCreated) { $null = Adb @('shell', 'rm', '-rf', $remote) }
        } catch { $cleanup.Add("Cleanup: $($_.Exception.Message)") }
    }
    foreach ($failure in $cleanup) { $report.Add("CLEANUP FAIL: $failure; backup=$backup") }
    if ($stopped) { $report.Add('Restored app remains stopped. XML/PNG are sequential captures, not atomic pairs.') }
    else { $report.Add('Rejected before force-stop/staging/launch; App lifecycle was not changed by this runner.') }
    $report | Set-Content -LiteralPath (Join-Path $resultRoot 'result.txt') -Encoding utf8NoBOM
    $report | Write-Output
    if ($Matrix -ne 'Legacy') {
        [ordered]@{
            schema = 1; status = if ($cleanup.Count) { 'FAIL' } else { $batchStatus }
            matrix = $Matrix; artifact = $artifact; serial = $Serial; cases = $batchCases.ToArray()
            private_data_restored = $restored; cleanup_errors = $cleanup.ToArray()
            retained_backup = if (-not $restored -or $cleanup.Count) { $backup } else { $null }
            clipboard_scope = 'CopyReference requires disposable clipboard ownership; clipboard content/history is not restored'
        } | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath (Join-Path $resultRoot 'batch-result.json') -Encoding utf8NoBOM
    }
    Assert-Qa ($cleanup.Count -eq 0) "Restoration incomplete; keep backup $backup"
}
