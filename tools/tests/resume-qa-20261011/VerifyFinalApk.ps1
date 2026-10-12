#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ApkPath,
    [Parameter(Mandatory)][string]$ExpectedApkSha256,
    [string]$ExpectedVersion = '0.1.19',
    [int]$ExpectedVersionCode = 20,
    [string]$AaptPath = 'G:\Android SDK\build-tools\36.0.0\aapt.exe'
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'QaCommon.ps1')
$binding = Get-QaVersionBinding $ExpectedVersion $ExpectedVersionCode
Assert-Qa ($ExpectedApkSha256 -match '^[a-fA-F0-9]{64}$') 'Main-agent final SHA256 required'
Assert-Qa (Test-Path -LiteralPath $ApkPath -PathType Leaf) 'Final APK does not exist; no fixture staging'
Assert-Qa (Test-Path -LiteralPath $AaptPath -PathType Leaf) 'Native aapt unavailable'
$hash = (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash
Assert-Qa ($hash -ieq $ExpectedApkSha256) 'Host APK differs from main-agent final SHA256'
$badging = @(& $AaptPath dump badging $ApkPath 2>&1)
Assert-Qa ($LASTEXITCODE -eq 0) 'aapt badging failed'
$line = Assert-QaApkBadging @($badging | ForEach-Object { [string]$_ }) $binding.version $binding.version_code
Assert-Qa ((Get-FileHash -LiteralPath $ApkPath).Hash -ceq $hash) 'APK changed during metadata gate'
[pscustomobject]@{
    path = (Resolve-Path -LiteralPath $ApkPath).Path
    sha256 = $hash.ToLowerInvariant(); version = $binding.version; version_code = $binding.version_code
    bytes = (Get-Item -LiteralPath $ApkPath).Length
    badging = $line; evidence_scope = 'host artifact only; installed APK gate remains required'
}
