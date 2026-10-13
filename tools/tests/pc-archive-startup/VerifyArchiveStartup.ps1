param([string]$Archive = (Join-Path $PSScriptRoot '../../../../Temp_Android/connect-0.3.0-files-rebuilt/dafeiyu-go-connect-0.3.0.tgz'),
    [string]$EvidenceName = 'pc-archive-startup-r1')
$ErrorActionPreference = 'Stop'
if ($EvidenceName -notmatch '^[A-Za-z0-9._-]{1,80}$') { throw 'Invalid evidence name' }
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../../'))
$evidence = Join-Path $workspace ('Temp_Android/qa/' + $EvidenceName)
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
foreach ($entry in @{TEMP='tmp'; TMP='tmp'; TMPDIR='tmp'; DOTNET_CLI_HOME='dotnet-home'; NUGET_SCRATCH='nuget-scratch'}.GetEnumerator()) {
    $directory = Join-Path $evidence $entry.Value
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    Set-Item -Path ('Env:' + $entry.Key) -Value $directory
}
$env:NUGET_PACKAGES = Join-Path $workspace 'Temp_SetupandLauncher/AriesClient-20261011/nuget'
$env:DOTNET_ROOT = Join-Path $workspace 'Temp_SetupandLauncher/DotnetSdk'
$env:DOTNET_SKIP_FIRST_TIME_EXPERIENCE = '1'
$env:DOTNET_GENERATE_ASPNET_CERTIFICATE = 'false'
$env:DOTNET_CLI_TELEMETRY_OPTOUT = '1'
$dotnet = Join-Path $env:DOTNET_ROOT 'dotnet.exe'
& $dotnet build (Join-Path $PSScriptRoot 'ArchiveStartupChecks.csproj') -c Release --disable-build-servers -m:1 `
    ('-p:BaseIntermediateOutputPath=' + (Join-Path $evidence 'obj/')) ('-p:OutputPath=' + (Join-Path $evidence 'bin/')) `
    --configfile (Join-Path $workspace 'Temp_SetupandLauncher/Revision180-20261011/Presence/NuGet.Config') *> (Join-Path $evidence 'build.log')
if ($LASTEXITCODE -ne 0) { throw "PC startup strict build failed: $evidence/build.log" }
& $dotnet (Join-Path $evidence 'bin/ArchiveStartupChecks.dll') ([IO.Path]::GetFullPath($Archive)) (Join-Path $evidence 'runtime') *> (Join-Path $evidence 'checks.log')
if ($LASTEXITCODE -ne 0) { throw "PC startup checks failed: $evidence/checks.log" }
Get-Content -LiteralPath (Join-Path $evidence 'build.log') -Tail 6
Get-Content -LiteralPath (Join-Path $evidence 'checks.log') -Tail 6
