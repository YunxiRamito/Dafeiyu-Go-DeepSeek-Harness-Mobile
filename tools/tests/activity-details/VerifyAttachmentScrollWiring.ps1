param()

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$sourceRoot = Join-Path $projectRoot 'app/src/main/java/com/dafeiyu/mobile/ui'
$fish = Get-Content -LiteralPath (Join-Path $sourceRoot 'components/FishComponents.kt') -Raw
$generated = Get-Content -LiteralPath (Join-Path $sourceRoot 'GeneratedFiles.kt') -Raw
$feed = Get-Content -LiteralPath (Join-Path $sourceRoot 'AssistantActivityFeed.kt') -Raw
$button = [regex]::Match($fish, '(?s)fun FishButton\(.*?(?=\r?\n@Composable\r?\nfun FishNavigationRow\()')
if (-not $button.Success) { throw 'FishButton source boundary changed; review wiring checks' }
$body = $button.Value

$checks = [ordered]@{
    'FishButton keeps default drag consumption and forwards the caller choice' =
        $body.Contains('consumeDrag: Boolean = true') -and
        $body.Contains('applyButtonDeformation(enabled, deformation, consumeDrag = consumeDrag)')
    'FishButton keeps one deformation handler and one enabled click callback' =
        ([regex]::Matches($body, '\.applyButtonDeformation\(').Count -eq 1) -and
        ([regex]::Matches($body, '\.clickable\(').Count -eq 1) -and
        ($body -match '(?s)\.clickable\(enabled = enabled,.*?role = Role\.Button, onClick = onClick')
    'Chat file title yields drag while retaining its expand toggle' =
        $generated.Contains('FishButton(onClick = { expanded = !expanded }, accent = false, consumeDrag = false,')
    'Chat file read retry yields drag while retaining its retry action' =
        $generated.Contains('FishButton(onClick = { readAttempt++ }, accent = false, consumeDrag = false)')
    'Generated text copy, quote and export actions yield drag to the chat list' =
        $generated.Contains('.applyButtonDeformation(enabled = enabled, state = deformation, circularHit = true, consumeDrag = false)') -and
        $generated.Contains('GeneratedFileAction("复制完整文本", "document", text != null)') -and
        $generated.Contains('GeneratedFileAction("引用到消息输入框", "chat", text != null && onReferenceText != null)') -and
        $generated.Contains('GeneratedFileAction("导出文本文件", "download", !choosingLocation, export)')
    'Receipt copy action yields drag and keeps clipboard text generated on click' =
        $feed.Contains('Modifier.size(48.dp).applyButtonDeformation(circularHit = true, consumeDrag = false)') -and
        $feed.Contains('IconButton(onClick = { clipboard.setText(AnnotatedString(text())) },')
    'Thinking and tool rows use the same disclosure geometry and row gap' =
        $feed.Contains('ActivityDisclosure(messageId, entry.id,') -and
        $feed.Contains('ActivityDisclosure(messageId, "row-${row.id}",') -and
        $feed.Contains('Modifier.fillMaxWidth().heightIn(min = 48.dp)') -and
        $feed.Contains('private val ActivityGlyphSlot = 44.dp') -and
        $feed.Contains('private val ActivityRowSpacing = 2.dp') -and
        $feed.Contains('Arrangement.spacedBy(ActivityRowSpacing)')
}
foreach ($check in $checks.GetEnumerator()) {
    if (-not $check.Value) { throw "FAIL $($check.Key)" }
    Write-Output "PASS $($check.Key)"
}
Write-Output "PASS $($checks.Count) attachment scroll wiring checks; source contracts only, no device pointer dispatch"
