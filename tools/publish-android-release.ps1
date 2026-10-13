param(
    [Parameter(Mandatory = $true)][string]$ReleaseDirectory,
    [Parameter(Mandatory = $true)][string]$EvidenceName,
    [string]$SshIdentity = 'C:/Users/KitamaruRamito/.ssh/dafeiyu_ed25519',
    [string]$KnownHosts = 'C:/Users/KitamaruRamito/.ssh/known_hosts',
    [switch]$Execute
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
if ($EvidenceName -notmatch '^[a-z0-9-]{8,80}$') { throw 'Expected a unique lowercase evidence name.' }
$evidence = Join-Path $tempRoot ('qa\' + $EvidenceName)
if (Test-Path -LiteralPath $evidence) { throw 'Evidence directory already exists; never overwrite a release receipt.' }
New-Item -ItemType Directory -Path $evidence | Out-Null
$env:TEMP = $evidence
$env:TMP = $evidence
$source = [IO.Path]::GetFullPath($ReleaseDirectory)
if (!$source.StartsWith($tempRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
    (Split-Path $source -Leaf) -ne '0.1.27') { throw 'Use the fixed 0.1.27 release prepared under Temp_Android.' }
if (!(Test-Path -LiteralPath $source -PathType Container)) { throw 'Prepared release is missing.' }
$files = @('app.apk', 'manifest.json', 'manifest.sig')
$children = @(Get-ChildItem -LiteralPath $source -Force)
if ($children.Count -ne 3 -or @($children | Where-Object {
    $_.Name -notin $files -or $_.PSIsContainer -or ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0
}).Count -ne 0) { throw 'Prepared release must contain exactly three regular release files.' }
$manifest = Get-Content -LiteralPath (Join-Path $source 'manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$apk = Join-Path $source 'app.apk'
$digest = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
$size = (Get-Item -LiteralPath $apk).Length
if ($manifest.version -ne '0.1.27' -or $manifest.versionCode -ne 28 -or $manifest.channel -ne 'stable' -or
    $manifest.packageName -ne 'com.dafeiyu.mobile' -or $manifest.buildType -ne 'release' -or
    $manifest.signingCertificateSha256 -ne 'd4341e66075d1e35909796cb5962b22f8aaed378ca50e057a1b25ed97fe0c64b' -or
    $manifest.sha256 -cne $digest -or $manifest.byteSize -ne $size) { throw 'Fixed formal release identity differs from APK bytes.' }
$helper = Join-Path $PSScriptRoot 'android-release-helper.sh'
$helperHash = (Get-FileHash -LiteralPath $helper -Algorithm SHA256).Hash.ToLowerInvariant()
$receipt = [ordered]@{
    schema = 1; version = '0.1.27'; code = 28; channel = 'stable'; package = 'com.dafeiyu.mobile'
    sha256 = $digest; bytes = $size; helperSha256 = $helperHash
    sourceFiles = @($files | ForEach-Object {
        [ordered]@{ name = $_; sha256 = (Get-FileHash -LiteralPath (Join-Path $source $_) -Algorithm SHA256).Hash.ToLowerInvariant() }
    }); execute = [bool]$Execute; phases = @(); completed = $false
}
$utf8 = [Text.UTF8Encoding]::new($false)
function Save-Receipt { [IO.File]::WriteAllText((Join-Path $evidence 'receipt.json'), ($receipt | ConvertTo-Json -Depth 5), $utf8) }
Save-Receipt
$ssh = (Get-Command ssh -ErrorAction Stop).Source
$scp = (Get-Command scp -ErrorAction Stop).Source
$hostTarget = 'dafeiyu@202.189.21.218'
$sshArgs = @('-p', '23129', '-i', $SshIdentity, '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes',
    '-o', 'UpdateHostKeys=no', '-o', 'ConnectTimeout=15', '-o', ('UserKnownHostsFile=' + $KnownHosts))
$scpArgs = @('-P', '23129', '-i', $SshIdentity, '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes',
    '-o', 'UpdateHostKeys=no', '-o', 'ConnectTimeout=15', '-o', ('UserKnownHostsFile=' + $KnownHosts))
function Invoke-Process([string]$Executable, [string[]]$Arguments, [string]$Phase, [string]$InputFile = '', [int]$TimeoutMs = 60000) {
    $process = [Diagnostics.Process]::new()
    $process.StartInfo.FileName = $Executable
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardInput = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    foreach ($item in $Arguments) { $process.StartInfo.ArgumentList.Add($item) }
    if (!$process.Start()) { throw "Cannot start phase $Phase" }
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    try {
        if ($InputFile) {
            $stream = [IO.File]::OpenRead($InputFile)
            try { $stream.CopyTo($process.StandardInput.BaseStream) }
            finally { $stream.Dispose() }
        }
    } finally { $process.StandardInput.Close() }
    $timedOut = !$process.WaitForExit($TimeoutMs)
    if ($timedOut) { $process.Kill($true); $process.WaitForExit() }
    $output = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()
    [IO.File]::WriteAllText((Join-Path $evidence ($Phase + '.log')), $output, $utf8)
    $receipt.phases += [ordered]@{ phase = $Phase; exitCode = $process.ExitCode; timedOut = $timedOut }
    Save-Receipt
    if ($timedOut -or $process.ExitCode -ne 0) { throw "Release phase $Phase failed; read its evidence log. No later phase is executed." }
}
Invoke-Process $ssh ($sshArgs + @($hostTarget, 'sh -s -- review')) 'review' $helper
if (!$Execute) {
    Write-Output 'Read-only release layout reviewed. Signature verification, upload, deployment and publication were not executed.'
    return
}
$stage = '/home/dafeiyu/developer-center-staging/android-release-' + $EvidenceName
$receipt.remoteStage = $stage
Save-Receipt
# Paths contain only validated fixed segments. A new source parent is mandatory;
# no release path or current pointer is written by this upload step.
$prepareCommand = "set -eu; umask 077; test ! -e '$stage'; test ! -L '$stage'; mkdir '$stage'; mkdir '$stage/0.1.27'"
Invoke-Process $ssh ($sshArgs + @($hostTarget, $prepareCommand)) 'prepare-unique-source'
foreach ($file in $files) {
    Invoke-Process $scp ($scpArgs + @((Join-Path $source $file), ($hostTarget + ':' + $stage + '/0.1.27/' + $file))) `
        ('upload-' + $file.Replace('.', '-')) '' 600000
}
foreach ($phase in @('verify-source', 'deploy', 'verify-deployed', 'publish')) {
    if ((Get-FileHash -LiteralPath $helper -Algorithm SHA256).Hash.ToLowerInvariant() -cne $helperHash) {
        throw 'Reviewed helper changed between phases.'
    }
    foreach ($item in $receipt.sourceFiles) {
        if ((Get-FileHash -LiteralPath (Join-Path $source $item.name) -Algorithm SHA256).Hash.ToLowerInvariant() -cne $item.sha256) {
            throw 'Prepared immutable source changed between phases.'
        }
    }
    $command = 'sh -s -- ' + $phase + ' ' + $stage + ' ' + $digest + ' ' + $size.ToString([Globalization.CultureInfo]::InvariantCulture)
    Invoke-Process $ssh ($sshArgs + @($hostTarget, $command)) $phase $helper 180000
}
$receipt.completed = $true
Save-Receipt
Write-Output 'Formal APK verified, immutably deployed, verified as the actual API user, and selected as stable. Public HTTPS and Android update verification are still required.'
Write-Output (Join-Path $evidence 'receipt.json')
