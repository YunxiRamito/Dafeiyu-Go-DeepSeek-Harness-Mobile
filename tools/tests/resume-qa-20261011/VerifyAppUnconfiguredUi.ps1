#requires -Version 7.0
[CmdletBinding()]
param([Parameter(Mandatory)][string]$UiXmlPath)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'QaCommon.ps1')
Assert-Qa (Test-Path -LiteralPath $UiXmlPath -PathType Leaf) 'Main-agent supplied App configuration XML required'
Assert-Qa ((Get-Item -LiteralPath $UiXmlPath).Length -le 1MB) 'Configuration XML exceeds bounded QA budget'
$document = [xml](Get-Content -LiteralPath $UiXmlPath -Raw -Encoding utf8)
Assert-QaAppUnconfiguredUi $document
Write-Output 'PASS saved tested-App Models UI shows empty key input and disabled save, no saved/busy state. Snapshot only: runtime credential-family existence gate still required; no whole-device credential claim. No ADB executed.'
