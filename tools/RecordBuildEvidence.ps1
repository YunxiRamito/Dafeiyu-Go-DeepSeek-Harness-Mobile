param(
    [Parameter(Mandatory)][string]$ApkPath,
    [Parameter(Mandatory)][string]$BuildLog,
    [Parameter(Mandatory)][string]$AcceptancePath
)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$temp = [IO.Path]::GetFullPath((Join-Path $project '../Temp_Android'))
$apk = [IO.Path]::GetFullPath($ApkPath)
if (-not $apk.StartsWith($temp + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Expected the delivery APK under Temp_Android.'
}
foreach ($path in @($apk, $BuildLog, $AcceptancePath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing evidence: $path" }
}
$acceptance = Get-Content -LiteralPath $AcceptancePath -Raw | ConvertFrom-Json
$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
if ($acceptance.apkSha256 -ine $hash) { throw 'Acceptance report belongs to a different APK.' }
$build = Get-Content -LiteralPath $BuildLog -Raw
if ($build -notmatch 'BUILD SUCCESSFUL') { throw 'No successful build recorded.' }
$aapt = Join-Path $temp 'toolchains/android-sdk/build-tools/35.0.0/aapt.exe'
$signer = Join-Path $temp 'toolchains/android-sdk/build-tools/35.0.0/apksigner.bat'
if (-not (Test-Path -LiteralPath $aapt)) { $aapt = 'G:/Android SDK/build-tools/36.0.0/aapt.exe' }
if (-not (Test-Path -LiteralPath $signer)) { $signer = 'G:/Android SDK/build-tools/36.0.0/apksigner.bat' }
$badging = @(& $aapt dump badging $apk 2>&1)
if ($LASTEXITCODE -ne 0) { throw 'APK metadata verification failed.' }
$package = ($badging | Where-Object { $_ -like 'package:*' }) -join ''
if ($package -notmatch "name='com\.dafeiyu\.mobile\.preview' versionCode='(\d+)' versionName='([^']+)'") {
    throw 'Unexpected package.'
}
$code = [int]$Matches[1]
$version = $Matches[2]
$signature = @(& $signer verify --print-certs $apk 2>&1)
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
$certificate = ($signature | Where-Object { $_ -like 'Signer #1 certificate SHA-256 digest:*' }) -join ''
if ($certificate -notmatch 'digest:\s*([a-fA-F0-9]{64})') { throw 'Missing certificate digest.' }
$certificateHash = $Matches[1].ToLowerInvariant()
$inputs = @(Get-ChildItem -LiteralPath (Join-Path $project 'app/src/main') -File -Recurse |
    Sort-Object FullName | ForEach-Object {
        [ordered]@{
            path = [IO.Path]::GetRelativePath($project, $_.FullName).Replace('\', '/')
            bytes = $_.Length
            sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    })
foreach ($relative in @('app/build.gradle.kts', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'tools/build.ps1')) {
    $path = Join-Path $project $relative
    $inputs += [ordered]@{
        path = $relative
        bytes = (Get-Item -LiteralPath $path).Length
        sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}
$externalRoots = @('runtime/assets', 'runtime/jniLibs') | ForEach-Object { Join-Path $temp $_ }
$external = @(Get-ChildItem -LiteralPath $externalRoots -File -Recurse | Sort-Object FullName | ForEach-Object {
    [ordered]@{
        path = [IO.Path]::GetRelativePath($temp, $_.FullName).Replace('\', '/')
        bytes = $_.Length
        sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
})
if ((Get-FileHash -LiteralPath $apk).Hash -ine $hash) { throw 'APK changed while recording evidence.' }
[ordered]@{
    date = (Get-Date -Format 'yyyy-MM-dd')
    recordedAt = [DateTimeOffset]::Now.ToString('O')
    scope = $acceptance.scope
    apk = [ordered]@{
        path = $apk; bytes = (Get-Item -LiteralPath $apk).Length
        sha256 = $hash; package = 'com.dafeiyu.mobile.preview'
        version = $version; versionCode = $code; minSdk = 30; targetSdk = 35
        debuggable = $true; certificateSha256 = $certificateHash
    }
    validation = $acceptance.validation
    acceptanceReport = [IO.Path]::GetFullPath($AcceptancePath)
    buildLog = [IO.Path]::GetFullPath($BuildLog)
    inputsSnapshot = 'Post-build source/configuration hash snapshot. Acceptance describes the actual checks; hashes alone are not proof of a build or device test.'
    inputs = $inputs
    externalInputsScope = 'Gradle main sourceSets runtime/assets and runtime/jniLibs only; downloads and unpack staging are not packaged inputs.'
    externalInputs = $external
} | ConvertTo-Json -Depth 14 | Set-Content -LiteralPath (Join-Path $project 'docs/frontend-build.json') -Encoding utf8NoBOM
Write-Output "Recorded $version / code$code / $hash"
