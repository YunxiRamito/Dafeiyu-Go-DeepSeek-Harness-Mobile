$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$source = Join-Path $projectRoot 'app/src/main/java/com/dafeiyu/mobile/ui/ChatScreen.kt'
$text = [IO.File]::ReadAllText($source)

# Source contracts only. Compilation, coroutine behavior and pixels require main-thread QA.
$contracts = [ordered]@{
    'sampling state resets with conversation generation and reply snapshot' =
        'val runMetrics by key\(conversationId, composerGeneration, runMessage\)'
    'old ticker checks conversation before publishing' =
        'if \(currentConversation\(\)\) value = runMessage\?\.let\(repository::responseRunMetrics\)'
    'exit cache has conversation generation and reply ownership' =
        'key\(conversationId, composerGeneration, runMessage\?\.id\) \{\s+ChatRunInfoBar'
    'follow waits for idle and aligns only when displaced' =
        'snapshotFlow \{\s+currentConversation\(\) && hasConversation && scrollIntent.followingLatest &&\s+!scrollIntent.pointerPressed && !dragging && !listState.isScrollInProgress &&\s+listState.canScrollBackward\s+\}.collect \{ shouldAlign ->\s+if \(shouldAlign\) listState.scrollToItem\(0\)'
    'return completion also yields to current touch drag and inertia' =
        'scrollIntent.finishReturn\(revision,\s+!listState.canScrollBackward && !scrollIntent.pointerPressed &&\s+!dragging && !listState.isScrollInProgress\)'
}
foreach ($entry in $contracts.GetEnumerator()) {
    if ($text -notmatch $entry.Value) { throw ('FAIL ' + $entry.Key) }
    Write-Output ('PASS ' + $entry.Key)
}
Write-Output 'PASS 5 source boundary contracts; no JVM, Gradle, model calls or device verification'
