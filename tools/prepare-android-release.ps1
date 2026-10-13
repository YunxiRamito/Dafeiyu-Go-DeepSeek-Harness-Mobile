param(
    [Parameter(Mandatory)][string]$Apk,
    [Parameter(Mandatory)][string]$ReleaseNotesFile,
    [Parameter(Mandatory)][string]$OutputRoot,
    [ValidateSet('stable', 'preview')][string]$Channel = 'stable',
    [string]$ManifestPrivateKey = (Join-Path $PSScriptRoot '../../Temp_Android/signing/apk-metadata-private.pem')
)
$ErrorActionPreference = 'Stop'
$taskTemp = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../Temp_Android'))
$taskRoot = [IO.Path]::GetFullPath($OutputRoot)
if (!$taskRoot.StartsWith($taskTemp + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Release preparation must stay under Temp_Android.'
}
$env:TEMP = Join-Path $taskTemp 'caches/tmp'
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Force -Path $env:TEMP, $taskRoot | Out-Null
$taskSource = [IO.Path]::GetFullPath($Apk)
$taskCertificate = 'd4341e66075d1e35909796cb5962b22f8aaed378ca50e057a1b25ed97fe0c64b'
$taskTools = Join-Path $taskTemp 'toolchains/android-sdk/build-tools/35.0.0'
if (!(Test-Path -LiteralPath $taskSource -PathType Leaf)) { throw 'APK missing.' }
$taskStage = Join-Path $taskRoot ('.stage-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskStage | Out-Null
try {
$taskApk = Join-Path $taskStage 'app.apk'
Copy-Item -LiteralPath $taskSource -Destination $taskApk
$taskHash = (Get-FileHash -LiteralPath $taskApk -Algorithm SHA256).Hash.ToLowerInvariant()
$taskSize = (Get-Item -LiteralPath $taskApk).Length
if ($taskSize -le 0 -or $taskSize -gt 1GB) { throw 'APK size exceeds the release contract.' }
$taskBadging = @(& (Join-Path $taskTools 'aapt.exe') dump badging $taskApk 2>&1)
if ($LASTEXITCODE -ne 0) { throw 'APK metadata could not be read.' }
$taskPackage = ($taskBadging | Where-Object { $_ -like 'package:*' }) -join ''
if ($taskPackage -notmatch "name='com\.dafeiyu\.mobile' versionCode='(\d+)' versionName='([0-9]+\.[0-9]+\.[0-9]+)'") {
    throw 'Expected a formal Android package and exact release version.'
}
$taskCode = [int]$Matches[1]
$taskVersion = $Matches[2]
if ($taskBadging | Where-Object { $_ -eq 'application-debuggable' }) { throw 'Debuggable packages cannot be published.' }
if (($taskBadging | Where-Object { $_ -like 'sdkVersion:*' }) -notcontains "sdkVersion:'30'" -or
    ($taskBadging | Where-Object { $_ -like 'targetSdkVersion:*' }) -notcontains "targetSdkVersion:'35'") {
    throw 'Unexpected minimum or target Android API.'
}
$taskNative = ($taskBadging | Where-Object { $_ -like 'native-code:*' }) -join ''
$taskAbis = @([regex]::Matches($taskNative, "'([^']+)'") | ForEach-Object { $_.Groups[1].Value })
if ($taskAbis.Count -eq 0 -or @($taskAbis | Where-Object { $_ -notin @('arm64-v8a', 'x86_64') }).Count -gt 0) {
    throw 'Expected the verified ARM64 and/or x86_64 runtime ABI.'
}
$taskVerification = @(& (Join-Path $taskTools 'apksigner.bat') verify --verbose --print-certs $taskApk 2>&1)
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
$taskCertLine = ($taskVerification | Where-Object { $_ -like 'Signer #1 certificate SHA-256 digest:*' }) -join ''
if ($taskCertLine -notmatch 'digest:\s*([0-9a-fA-F]{64})' -or $Matches[1].ToLowerInvariant() -cne $taskCertificate -or
    @($taskVerification | Where-Object { $_ -like 'Signer #* certificate SHA-256 digest:*' }).Count -ne 1) {
    throw 'The APK is not signed by the fixed formal release identity.'
}
$taskSchemes = @($taskVerification | ForEach-Object {
    if ($_ -match '^Verified using (v[123](?:\.1)?) scheme .*:\s*true$') { $Matches[1] }
})
if ('v2' -notin $taskSchemes) { throw 'An authenticated v2 APK signature is required.' }
$taskNotes = [IO.File]::ReadAllText([IO.Path]::GetFullPath($ReleaseNotesFile))
$taskControlCharacters = @($taskNotes.ToCharArray() | Where-Object { [char]::IsControl($_) -and $_ -notin @("`r", "`n", "`t") })
if ($taskNotes.Length -gt 12000 -or $taskControlCharacters.Count -gt 0) {
    throw 'Invalid release notes.'
}
$taskDestination = Join-Path $taskRoot $taskVersion
if (Test-Path -LiteralPath $taskDestination) { throw 'Release version already staged; never overwrite an immutable release.' }
    if ((Get-FileHash -LiteralPath $taskApk -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskHash) {
        throw 'Staged APK changed during metadata and certificate verification.'
    }
    $taskManifest = [ordered]@{
        schemaVersion = 1; version = $taskVersion; packageName = 'com.dafeiyu.mobile'; versionCode = $taskCode
        minSdk = 30; targetSdk = 35; abis = $taskAbis; sha256 = $taskHash
        byteSize = $taskSize; fileName = 'app.apk'
        signingCertificateSha256 = $taskCertificate; apkSignatureSchemes = $taskSchemes
        channel = $Channel; buildType = 'release'; publishedAt = [DateTimeOffset]::UtcNow.ToString('O')
        releaseNotes = $taskNotes
        installationPolicy = [ordered]@{
            mode = 'user-confirmed'; allowDowngrade = $false; allowSilentInstall = $false
            recoveryStrategy = 'higher-version-code-reissue'
        }
    }
    $taskUtf8 = [Text.UTF8Encoding]::new($false)
    $taskBytes = $taskUtf8.GetBytes(($taskManifest | ConvertTo-Json -Depth 4))
    if ($taskBytes.Length -gt 32768) { throw 'UTF-8 release metadata exceeds the client and server limit.' }
    $taskSigner = [Security.Cryptography.RSA]::Create()
    $taskPublic = [Security.Cryptography.RSA]::Create()
    try {
        $taskSigner.ImportFromPem([IO.File]::ReadAllText([IO.Path]::GetFullPath($ManifestPrivateKey)))
        $taskPublic.ImportFromPem([IO.File]::ReadAllText((Join-Path $PSScriptRoot '../app/src/main/res/raw/apk_metadata_public.pem')))
        $taskSignature = $taskSigner.SignData($taskBytes, [Security.Cryptography.HashAlgorithmName]::SHA256,
            [Security.Cryptography.RSASignaturePadding]::Pkcs1)
        if (!$taskPublic.VerifyData($taskBytes, $taskSignature, [Security.Cryptography.HashAlgorithmName]::SHA256,
            [Security.Cryptography.RSASignaturePadding]::Pkcs1)) { throw 'Manifest private key does not match installed public trust.' }
    } finally { $taskSigner.Dispose(); $taskPublic.Dispose() }
    [IO.File]::WriteAllBytes((Join-Path $taskStage 'manifest.json'), $taskBytes)
    [IO.File]::WriteAllText((Join-Path $taskStage 'manifest.sig'), [Convert]::ToBase64String($taskSignature), $taskUtf8)
    if ((Get-FileHash -LiteralPath $taskApk -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskHash) {
        throw 'Staged APK changed after verification.'
    }
    Move-Item -LiteralPath $taskStage -Destination $taskDestination
    Write-Output ('Prepared signed Android release: ' + $taskDestination)
    Write-Output ('APK SHA256: ' + $taskHash)
} finally {
    if (Test-Path -LiteralPath $taskStage -PathType Container) {
        foreach ($taskName in @('app.apk', 'manifest.json', 'manifest.sig')) {
            $taskFile = Join-Path $taskStage $taskName
            if (Test-Path -LiteralPath $taskFile -PathType Leaf) { Remove-Item -LiteralPath $taskFile }
        }
        Remove-Item -LiteralPath $taskStage
    }
}
