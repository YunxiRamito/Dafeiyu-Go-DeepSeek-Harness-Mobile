#requires -Version 7.0
[CmdletBinding()]
param(
    [switch]$Freeze,
    [string]$OfflineResultPath,
    [switch]$CheckSourceContracts,
    [string]$ManifestPath,
    [string]$ExpectedVersion = '0.1.19',
    [int]$ExpectedVersionCode = 20
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'QaCommon.ps1')
$binding = Get-QaVersionBinding $ExpectedVersion $ExpectedVersionCode
$path = if ($ManifestPath) { Assert-QaOutputPath $ManifestPath } else { Join-Path $PSScriptRoot 'freeze-manifest.json' }
Assert-Qa ($binding.version -ceq '0.1.19' -or $ManifestPath) '0.1.20 requires a separate explicit freeze manifest'
Assert-Qa ($binding.version -ceq '0.1.19' -or $path -ine (Join-Path $PSScriptRoot 'freeze-manifest.json')) '020 cannot overwrite historical 019 freeze'
$inputs = Get-QaPreparationHashes
$sourcePaths = @('app/src/main/java/com/dafeiyu/mobile/ui/ChatRunInfoBar.kt',
    'app/src/main/java/com/dafeiyu/mobile/data/BalanceStore.kt',
    'app/src/main/java/com/dafeiyu/mobile/data/OfficialWebClient.kt')
if ($binding.version -ceq '0.1.20') {
    $sourcePaths += @('app/src/main/java/com/dafeiyu/mobile/ui/ChatScreen.kt',
        'app/src/main/java/com/dafeiyu/mobile/ui/GeneratedFiles.kt',
        'app/src/main/java/com/dafeiyu/mobile/ui/AssistantActivityFeed.kt',
        'app/src/main/java/com/dafeiyu/mobile/ui/AssistantActivitySummary.kt',
        'app/src/main/java/com/dafeiyu/mobile/ui/ActivityReceiptDetails.kt')
}
function SourceContracts {
    return @($sourcePaths | ForEach-Object {
        [ordered]@{ path = $_; sha256 = (Get-FileHash -LiteralPath (Join-Path (Get-QaProject) $_)).Hash.ToLowerInvariant() }
    })
}
if ($Freeze) {
    Assert-Qa (-not [string]::IsNullOrWhiteSpace($OfflineResultPath)) 'Fresh passing offline result required to freeze'
    $offlinePath = Assert-QaOutputPath $OfflineResultPath
    $offline = Get-Content -LiteralPath $offlinePath -Raw | ConvertFrom-Json
    Assert-Qa ($offline.total -gt 0 -and $offline.passed -eq $offline.total -and
        -not $offline.adb_executed -and -not $offline.jvm_executed -and -not $offline.model_called) 'Offline preparation has not passed'
    Assert-Qa (($inputs | ConvertTo-Json -Compress) -ceq ($offline.qa_inputs | ConvertTo-Json -Compress)) 'QA files changed since offline checks; rerun before freezing'
    [ordered]@{
        schema = 1; frozen_at = [DateTimeOffset]::Now.ToString('O')
        status = 'PREPARATION_FROZEN_DEVICE_AND_PRODUCTION_JVM_DEFERRED'
        intended_version = $binding.version; intended_version_code = $binding.version_code; final_apk_sha256 = $null
        qa_inputs = $inputs; source_contracts = SourceContracts
        offline_result = [IO.Path]::GetRelativePath($PSScriptRoot, $offlinePath).Replace('\', '/')
        offline_result_sha256 = (Get-FileHash -LiteralPath $offlinePath).Hash.ToLowerInvariant()
        current_device_app_credentials = 'UNKNOWN until main-agent runtime gate; no whole-device claim'
    } | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $path -Encoding utf8NoBOM
    Write-Output "PREPARATION FROZEN: $path; final APK/device/JVM results remain deferred"
    return
}
Assert-Qa (Test-Path -LiteralPath $path -PathType Leaf) 'QA preparation freeze missing'
$frozen = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
Assert-Qa ($frozen.intended_version -ceq $binding.version -and
    $frozen.intended_version_code -eq $binding.version_code) 'Frozen version binding mismatch'
Assert-Qa (($inputs | ConvertTo-Json -Compress) -ceq ($frozen.qa_inputs | ConvertTo-Json -Compress)) 'Frozen QA input drift; review, rerun offline and refreeze'
$offlinePath = Assert-QaOutputPath (Join-Path $PSScriptRoot $frozen.offline_result)
Assert-Qa ((Get-FileHash -LiteralPath $offlinePath).Hash -ieq $frozen.offline_result_sha256) 'Frozen offline evidence drift'
if ($CheckSourceContracts) {
    Assert-Qa (((SourceContracts) | ConvertTo-Json -Compress) -ceq
        ($frozen.source_contracts | ConvertTo-Json -Compress)) 'Source contract drift; review current expectations, rerun offline and refreeze before final APK QA'
}
Write-Output 'PASS frozen QA inputs and offline evidence; source contracts checked only when explicitly requested. No JVM/ADB executed.'
