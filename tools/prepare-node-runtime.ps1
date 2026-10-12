param([string]$TempRoot = (Join-Path (Split-Path $PSScriptRoot -Parent) '..\Temp_Android'))
$ErrorActionPreference = 'Stop'
$TempRoot = [IO.Path]::GetFullPath($TempRoot)
$runtime = Join-Path $TempRoot 'runtime'
$download = Join-Path $runtime 'downloads'
$native = Join-Path $runtime 'jniLibs\arm64-v8a'
$assets = Join-Path $runtime 'assets\node-runtime'
New-Item -ItemType Directory -Force -Path $download,$native,$assets | Out-Null
$indexPath = Join-Path $TempRoot 'termux-packages-aarch64.txt'
if (!(Test-Path -LiteralPath $indexPath)) {
    Invoke-WebRequest 'https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages' -OutFile $indexPath -TimeoutSec 90
}
$index = @{}
foreach ($block in ([IO.File]::ReadAllText($indexPath) -split '\r?\n\r?\n')) {
    $fields = @{}
    foreach ($line in ($block -split '\r?\n')) {
        if ($line -match '^([A-Za-z0-9-]+): (.*)$') { $fields[$matches[1]] = $matches[2] }
    }
    if ($fields.Package) { $index[$fields.Package] = $fields }
}
$names = @('nodejs-lts','libc++','openssl','c-ares','libicu','libsqlite','zlib')
$receipt = @()
$libraries = @()
foreach ($name in $names) {
    $p = $index[$name]
    if (!$p -or $p.Architecture -ne 'aarch64') { throw "Missing Android arm64 package: $name" }
    if ($name -eq 'nodejs-lts' -and $p.Version -ne '24.18.0-1') { throw 'Node package changed; review and pin before building.' }
    $deb = Join-Path $download (([IO.Path]::GetFileName($p.Filename)) -replace ':','_')
    if (!(Test-Path -LiteralPath $deb) -or (Get-FileHash -LiteralPath $deb -Algorithm SHA256).Hash.ToLowerInvariant() -ne $p.SHA256) {
        $partial = $deb + '.partial'
        & curl.exe --fail --location --connect-timeout 20 --max-time 120 --retry 2 --output $partial ("https://packages-cf.termux.dev/apt/termux-main/" + $p.Filename)
        if ($LASTEXITCODE -or !(Test-Path -LiteralPath $partial) -or
            (Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash.ToLowerInvariant() -ne $p.SHA256) {
            throw "Unable to download verified Android package: $name"
        }
        Move-Item -LiteralPath $partial -Destination $deb -Force
    }
    if ((Get-FileHash -LiteralPath $deb -Algorithm SHA256).Hash.ToLowerInvariant() -ne $p.SHA256) { throw "Package SHA256 mismatch: $name" }
    $extract = Join-Path $runtime ("unpack\" + $name)
    New-Item -ItemType Directory -Force -Path $extract | Out-Null
    & tar.exe -xf $deb -C $extract
    if ($LASTEXITCODE) { throw "Cannot extract DEB $name" }
    $data = Get-ChildItem -LiteralPath $extract -Filter 'data.tar.*' | Select-Object -First 1
    # Extract regular ELF files individually so Windows does not need symlink privileges.
    $entries = & tar.exe -tvf $data.FullName
    if ($LASTEXITCODE) { throw "Cannot list package $name" }
    $regular = @()
    foreach ($entry in $entries) {
        if ($entry -match '^-' -and $entry -match ' (\./data/data/com\.termux/files/usr/(?:lib/[^/]+\.so(?:\.[^/]+)*|bin/node))$') {
            $regular += $matches[1]
        }
    }
    foreach ($entry in $regular) {
        & tar.exe -xf $data.FullName -C $extract $entry
        if ($LASTEXITCODE) { throw "Cannot extract ELF $name" }
    }
    $prefix = Join-Path $extract 'data\data\com.termux\files\usr'
    if ($name -eq 'nodejs-lts') {
        Copy-Item -LiteralPath (Join-Path $prefix 'bin\node') -Destination (Join-Path $native 'libnode_exec.so') -Force
    }
    foreach ($file in (Get-ChildItem -LiteralPath (Join-Path $prefix 'lib') -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -match '\.so($|\.)' })) {
        # APK native-library names must end in .so. Restore each SONAME in private storage at startup.
        $packaged = 'librt_' + ($file.Name -replace '[^A-Za-z0-9_]','_') + '.so'
        Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $native $packaged) -Force
        $libraries += @{ packaged=$packaged; name=$file.Name; sha256=(Get-FileHash $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
    }
    $receipt += @{ name=$name; version=$p.Version; sha256=$p.SHA256; source=("https://packages.termux.dev/apt/termux-main/" + $p.Filename) }
}
$lock = @{ protocol='dafeiyu-android-node.v1'; node_version='24.18.0'; architecture='arm64-v8a';
    executable_sha256=(Get-FileHash (Join-Path $native 'libnode_exec.so') -Algorithm SHA256).Hash.ToLowerInvariant();
    packages=$receipt; libraries=$libraries }
$lock | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $assets 'runtime-lock.json') -Encoding utf8
Write-Output "Android Node assets prepared: $runtime"
