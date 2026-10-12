#requires -Version 7.0
[CmdletBinding()]
param(
    [switch]$Run,
    [switch]$FinalReady,
    [switch]$MainAgentOwnsEmulator,
    [Parameter(Mandatory)][int]$ExpectedVersionCode,
    [Parameter(Mandatory)][string]$FreezeManifestPath,
    [ValidateSet('Short', 'LongChat')][string]$Matrix = 'Short',
    [ValidateSet('Receipts', 'CopyReference', 'RunInfo320')][string[]]$ShortCases = @('Receipts', 'CopyReference', 'RunInfo320'),
    [switch]$OwnsDisposableClipboard,
    [ValidateRange(1, 160)][int]$LongMaxSwipes = 120,
    [ValidateRange(1, 900)][int]$LongMaxSeconds = 600,
    [ValidateRange(1, 300)][int]$LongNoProgressSeconds = 90,
    [string]$ApkPath,
    [string]$ExpectedApkSha256,
    [string]$Serial,
    [string]$AaptPath = 'G:\Android SDK\build-tools\36.0.0\aapt.exe',
    [string]$AdbPath = 'G:\Android SDK\platform-tools\adb.exe'
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'QaCommon.ps1')
$null = Get-QaVersionBinding '0.1.20' $ExpectedVersionCode
$manifest = Assert-QaOutputPath $FreezeManifestPath
Assert-Qa ($manifest -ine (Join-Path $PSScriptRoot 'freeze-manifest.json')) '020 must not use or overwrite the historical 019 freeze'
$arguments = @{} + $PSBoundParameters
$arguments.ExpectedVersion = '0.1.20'
$arguments.Matrix = $Matrix
$arguments.FreezeManifestPath = $manifest
& (Join-Path $PSScriptRoot 'VerifyActivity019.ps1') @arguments
