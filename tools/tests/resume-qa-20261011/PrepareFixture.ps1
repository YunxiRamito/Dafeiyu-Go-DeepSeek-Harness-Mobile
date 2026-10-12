#requires -Version 7.0
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'QaCommon.ps1')
$old = Join-Path (Get-QaProject) '../Temp_Android/qa/emulator-0.1.18/fixtures'
$root = Assert-QaOutputPath (Join-Path $PSScriptRoot 'fixtures')
Assert-Qa (-not (Test-Path -LiteralPath $root)) 'Fixture already exists; use its hashes or remove only this generated fixture after review'
$index = Get-Content -LiteralPath (Join-Path $old 'workspace-index.json') -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable
$index.selectedConversationId = 'qa-activity-019'
$index.workspaces[0].name = 'Offline QA 019'
$index.workspaces += @{ id = 'qa-empty-019'; name = 'QA inert empty workspace with a bounded long name' }
$ids = @{ 'qa-activity-018' = 'qa-activity-019'; 'qa-activity-errors-018' = 'qa-activity-errors-019' }
foreach ($conversation in $index.conversations) {
    $conversation.id = $ids[$conversation.id]
    $conversation.title = $conversation.title.Replace('018', '019')
}
$messages = @{}
foreach ($entry in $index.messages.GetEnumerator()) {
    $list = $entry.Value
    foreach ($message in $list) {
        $message.id += 1000
        $message.text = $message.text.Replace('_018', '_019')
        if ($message.ContainsKey('streamSnapshot')) {
            foreach ($round in $message.streamSnapshot.messages) {
                foreach ($block in $round.blocks) { $block.text = $block.text.Replace('_018', '_019') }
            }
        }
    }
    $messages[$ids[$entry.Key]] = $list
}
$long = @()
for ($i = 0; $i -lt 20; $i++) {
    $head = if ($i -eq 0) { 'QA_LONG_FIRST_019' } else { "QA_LONG_USER_019_$i" }
    $tail = if ($i -eq 19) { 'QA_LONG_LAST_019' } else { "QA_LONG_REPLY_END_019_$i" }
    $long += @{ id = 20000 + $i * 2; role = 'user'; text = "$head`nSynthetic archived conversation text. Never send." }
    $long += @{
        id = 20001 + $i * 2; role = 'assistant'
        text = "QA_LONG_REPLY_019_$i`n" + ('Bounded offline text for scrolling and memory observation. ' * 48) + "`n$tail"
    }
}
$index.conversations += @{ id = 'qa-long-019'; title = 'QA 019 bounded long chat'; workspaceId = 'default' }
$messages['qa-long-019'] = $long
$index.messages = $messages
New-Item -ItemType Directory -Path $root | Out-Null
$index | ConvertTo-Json -Depth 60 | Set-Content -LiteralPath (Join-Path $root 'workspace-index.json') -Encoding utf8NoBOM
Copy-Item -LiteralPath (Join-Path $old 'tasks.json') -Destination (Join-Path $root 'tasks.json')
Copy-Item -LiteralPath (Join-Path $old 'official-tool-outputs') -Destination (Join-Path $root 'official-tool-outputs') -Recurse
foreach ($file in Get-ChildItem -LiteralPath (Join-Path $root 'official-tool-outputs') -Filter 'metadata.json' -Recurse -File) {
    $metadata = Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json -AsHashtable
    $metadata.conversation_id = 'qa-activity-019'
    $metadata | ConvertTo-Json | Set-Content -LiteralPath $file.FullName -Encoding utf8NoBOM
}
$null = Test-QaFixture $root
[ordered]@{
    schema = 1; intended_version = '0.1.19'; intended_version_code = 20
    device_status = 'DEFERRED until main-agent FINAL_READY and APK gate'
    model_called = $false; total_budget_bytes = 524288; per_message_character_budget = 8192
    origin = Get-QaHashes $old
    fixture = Get-QaHashes $root
} | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'fixture-manifest.json') -Encoding utf8NoBOM
Write-Output "PREPARED bounded local synthetic fixture: $root; no ADB/JVM/network"
