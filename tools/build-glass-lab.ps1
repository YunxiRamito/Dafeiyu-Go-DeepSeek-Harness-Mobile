param(
    [ValidateSet('release','debug')][string]$Variant = 'release',
    [switch]$WithTests,
    [switch]$VerifyMainApp
)
$ErrorActionPreference = 'Stop'
$labRoot = Split-Path $PSScriptRoot -Parent
$labTempRoot = [System.IO.Path]::GetFullPath((Join-Path $labRoot '..\Temp_Android'))
$labVariantTitle = (Get-Culture).TextInfo.ToTitleCase($Variant)
$labTasks = @(":glass-lab:assemble$labVariantTitle", ":glass-lab:lint$labVariantTitle")
if ($WithTests) { $labTasks += ':glass-lab:assembleDebugAndroidTest' }
if ($VerifyMainApp) { $labTasks += ':app:compileDebugKotlin' }
& (Join-Path $PSScriptRoot 'build.ps1') -Tasks $labTasks -TempRoot $labTempRoot -MaxWorkers 2
if ($LASTEXITCODE -ne 0) { throw "Lab build failed: $LASTEXITCODE" }
$labOutput = Join-Path $labTempRoot "build\glass-lab\outputs\apk\$Variant\glass-lab-$Variant.apk"
$labDelivery = Join-Path $labTempRoot ('Dist\Dafeiyu-Palette-1.0.2' + $(if ($Variant -eq 'debug') { '-dev' } else { '' }) + '.apk')
Copy-Item -LiteralPath $labOutput -Destination $labDelivery -Force
((Get-FileHash -LiteralPath $labDelivery -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + (Split-Path $labDelivery -Leaf)) |
    Set-Content -LiteralPath "$labDelivery.sha256" -Encoding ascii
Write-Output "Material Lab delivery: $labDelivery"
