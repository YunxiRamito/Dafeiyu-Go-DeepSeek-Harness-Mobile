param(
    [string]$TempRoot = (Join-Path (Split-Path $PSScriptRoot -Parent) '..\Temp_Android'),
    [string]$Proxy = 'http://127.0.0.1:7890'
)
$ErrorActionPreference = 'Stop'
$TempRoot = [IO.Path]::GetFullPath($TempRoot)
if ([IO.Path]::GetPathRoot($TempRoot) -eq 'C:\') { throw 'Runtime preparation must not write to C:.' }
$runtime = Join-Path $TempRoot 'runtime'
$download = Join-Path $runtime 'downloads\linux-runtime'
$unpack = Join-Path $runtime 'unpack\linux-runtime'
$native = Join-Path $runtime 'jniLibs\arm64-v8a'
$assets = Join-Path $runtime 'assets\linux-runtime'
$sources = Join-Path $assets 'sources'
$licenses = Join-Path $assets 'licenses'
$backup = Join-Path $TempRoot 'backup-0.1.24\runtime\prepared'
New-Item -ItemType Directory -Force -Path $download,$unpack,$native,$sources,$licenses,$backup | Out-Null

function Receive-Locked([string]$Name, [string]$Url, [string]$Sha256) {
    $destination = Join-Path $download $Name
    if (!(Test-Path -LiteralPath $destination) -or (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Sha256) {
        $partial = $destination + '.partial'
        & curl.exe --fail --location --connect-timeout 20 --max-time 180 --retry 2 --proxy $Proxy --output $partial $Url
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $Name" }
        if ((Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Sha256) {
            Remove-Item -LiteralPath $partial -Force
            throw "SHA-256 mismatch: $Name"
        }
        Move-Item -LiteralPath $partial -Destination $destination -Force
    }
    return $destination
}

function Copy-Prepared([string]$Source, [string]$Destination, [string]$ExpectedSha256) {
    if ((Get-FileHash -LiteralPath $Source -Algorithm SHA256).Hash.ToLowerInvariant() -ne $ExpectedSha256) {
        throw "Native/source input mismatch: $Source"
    }
    if (Test-Path -LiteralPath $Destination) {
        $oldSha = (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($oldSha -eq $ExpectedSha256) { return }
        Copy-Item -LiteralPath $Destination -Destination (Join-Path $backup ((Split-Path $Destination -Leaf) + '.' + $oldSha))
    }
    Copy-Item -LiteralPath $Source -Destination $Destination -Force
}

$packages = @(
    @{ name='proot'; version='5.1.107.96'; filename='proot_5.1.107.96_aarch64.deb';
        url='https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.96_aarch64.deb';
        sha256='8199dca06dccb693ec09fb1759e3e1ad08b4863f0c11c612f89c20bd9ecdc1a0' },
    @{ name='libandroid-shmem'; version='0.7'; filename='libandroid-shmem_0.7_aarch64.deb';
        url='https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb';
        sha256='0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6' },
    @{ name='libtalloc'; version='2.5.0'; filename='libtalloc_2.5.0_aarch64.deb';
        url='https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.5.0_aarch64.deb';
        sha256='556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7' }
)
$elf = @(
    @{ package='proot'; member='./data/data/com.termux/files/usr/bin/proot'; packaged='liblinux_proot.so';
        sha256='1545b85b312505db6eb6908ff8b2ded0a77a3bd689c50ae85aa7c1d8445dd717' },
    @{ package='proot'; member='./data/data/com.termux/files/usr/libexec/proot/loader'; packaged='liblinux_prootloader.so';
        sha256='cbdef0e652c2b78af25d867e1719fdebbb0915e25aae2dd35b3b5c1835f6b551' },
    @{ package='proot'; member='./data/data/com.termux/files/usr/libexec/proot/loader32'; packaged='liblinux_prootloader32.so';
        sha256='c429996fee730e1a4fe12625b22dab5a5bc2847a4cae274d36fd4a19cd8299a8' },
    @{ package='libtalloc'; member='./data/data/com.termux/files/usr/lib/libtalloc.so.2.5.0'; packaged='liblinux_talloc.so';
        sha256='742b438c4d09e276985a61d44164c9de207b6d8cc268f3018cb3001961bcb309' },
    @{ package='libandroid-shmem'; member='./data/data/com.termux/files/usr/lib/libandroid-shmem.so'; packaged='liblinux_androidshmem.so';
        sha256='84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731' }
)
foreach ($package in $packages) {
    $deb = Receive-Locked $package.filename $package.url $package.sha256
    $directory = Join-Path $unpack $package.name
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $members = @(& tar.exe -tf $deb)
    if ($LASTEXITCODE -ne 0) { throw "Cannot list verified DEB: $($package.name)" }
    $data = @($members | Where-Object { $_ -match '^data\.tar\.(xz|gz)$' })
    if ($data.Count -ne 1) { throw 'Expected one data archive in DEB.' }
    & tar.exe -xf $deb -C $directory $data[0]
    if ($LASTEXITCODE -ne 0) { throw 'Cannot extract DEB data archive.' }
    $tarball = Join-Path $directory $data[0]
    foreach ($entry in ($elf | Where-Object { $_.package -eq $package.name })) {
        $listed = @(& tar.exe -tvf $tarball $entry.member)
        if ($LASTEXITCODE -ne 0 -or $listed.Count -ne 1 -or !$listed[0].StartsWith('-')) { throw 'Native member must be a regular file.' }
        & tar.exe -xf $tarball -C $directory $entry.member
        if ($LASTEXITCODE -ne 0) { throw 'Cannot extract native member.' }
        $inputFile = Join-Path $directory $entry.member.Substring(2)
        Copy-Prepared $inputFile (Join-Path $native $entry.packaged) $entry.sha256
    }
    if ($package.name -eq 'libandroid-shmem') {
        $copyright = './data/data/com.termux/files/usr/share/doc/libandroid-shmem/copyright'
        & tar.exe -xf $tarball -C $directory $copyright
        if ($LASTEXITCODE -ne 0) { throw 'Cannot preserve shmem license.' }
        Copy-Prepared (Join-Path $directory $copyright.Substring(2)) (Join-Path $licenses 'libandroid-shmem-BSD-3-Clause.txt') '0dd43abe17196b0224cb0b159819af798cd508a82a21e5870126fff4e899a7eb'
    }
}

# Complete native source archives and the fixed packaging/build system are carried with the APK.
# These are data only: no downloaded script or build command is executed.
$sourceInputs = @(
    @{ name='proot-v5.1.107.96.zip'; url='https://codeload.github.com/termux/proot/zip/refs/tags/v5.1.107.96';
        sha256='75f654fe60dea92dabff2bf083ae8bfe4f91baa6a1a374786a6bf391015eebaa'; revision='a179d3e8a4e045aaa1fb8cc3284f23509d96d353'; license='GPL-2.0-or-later' },
    @{ name='libandroid-shmem-v0.7.tar.gz'; url='https://codeload.github.com/termux/libandroid-shmem/tar.gz/refs/tags/v0.7';
        sha256='1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867'; revision='7f0bd7e25dbdd146265aff7c6a890029e374622d'; license='BSD-3-Clause' },
    @{ name='talloc-2.5.0.tar.gz'; url='https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz';
        sha256='912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007'; revision='2.5.0'; license='LGPL-3.0-or-later library; mixed source tree licenses retained' },
    @{ name='termux-packages-3e13ceaa.zip'; url='https://codeload.github.com/termux/termux-packages/zip/3e13ceaa08e462bb3e0eb6529135ad67f9a3a9c5';
        sha256='31987ac168b14d636c6cd5ed76edcc598c2d28a01b345d6279f8e11c28190c4f'; revision='3e13ceaa08e462bb3e0eb6529135ad67f9a3a9c5'; license='per-file licenses in build system' }
)
foreach ($sourceInput in $sourceInputs) {
    $file = Receive-Locked $sourceInput.name $sourceInput.url $sourceInput.sha256
    Copy-Prepared $file (Join-Path $sources $sourceInput.name) $sourceInput.sha256
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$prootSource = [IO.Compression.ZipFile]::OpenRead((Join-Path $sources 'proot-v5.1.107.96.zip'))
try {
    $entry = $prootSource.GetEntry('proot-5.1.107.96/COPYING')
    if (!$entry) { throw 'PRoot source license missing.' }
    $destination = Join-Path $licenses 'proot-COPYING.txt'
    if (Test-Path -LiteralPath $destination) { Copy-Item -LiteralPath $destination -Destination (Join-Path $backup 'proot-COPYING.txt') -Force }
    [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $destination, $true)
} finally { $prootSource.Dispose() }

$lock = @{ protocol='dafeiyu-linux-runtime.v1'; architecture='arm64-v8a'; native_packages=$packages; native_files=$elf;
    sources=$sourceInputs; dsha=@{ tag='v0.2.0-rc2'; commit='8362bba747fcf84c19e436c3dbb594a0fc388a67';
        url='https://github.com/DSH-APP/DSHA/releases/download/v0.2.0-rc2/dsha-0.2.0-rc2.apk';
        sha256='1fedf883d431af658d89c28b86d85974cc1d11b74e0c5d46ef3d3cf8650b1961' }; provenance='Official Termux DEBs, source hashes match fixed recipes; no byte-for-byte rebuild claim.' }
$lockPath = Join-Path $assets 'runtime-lock.json'
if (Test-Path -LiteralPath $lockPath) { Copy-Item -LiteralPath $lockPath -Destination (Join-Path $backup 'runtime-lock.json') -Force }
$lock | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $lockPath -Encoding utf8
$noticePath = Join-Path $assets 'NOTICE.txt'
if (Test-Path -LiteralPath $noticePath) { Copy-Item -LiteralPath $noticePath -Destination (Join-Path $backup 'NOTICE.txt') -Force }
@'
Native executables: official Termux PRoot 5.1.107.96, GPL-2.0-or-later; talloc 2.5.0 library, LGPL-3.0-or-later; libandroid-shmem 0.7, BSD-3-Clause.
Executable files are unchanged upstream ELF files renamed for Android APK packaging. Complete source archives and the pinned Termux packaging/build system are included in linux-runtime/sources. Licenses and copyright notices remain in the source archives and linux-runtime/licenses. No downloaded script is executed by this preparation tool. Rebuilding the official DEBs byte-for-byte has not been performed.
The arm64 guest is downloaded on request from DSHA v0.2.0-rc2 (commit 8362bba747fcf84c19e436c3dbb594a0fc388a67, MIT application code). Its Ubuntu 24.04.3/Node 24.19.0/Git/Python/pnpm payloads retain their individual licenses, /usr/share/doc, common-licenses, and npm package LICENSE files. DSHA's top-level MIT license does not replace these licenses. The locked standard rootfs digest is the optimized release-APK digest, not the pre-optimization source descriptor digest.
The separately prepared native x86_64 guest is fixed by x86_64-release.json and its archive SHA-256 0ba96d0433d4512d4c8693c8c1d49425c82f2a4e38798dec7674471a51d94b64. It contains the pinned official Ubuntu noble 24.04.3 rootfs, Node 24.19.0, Ubuntu Git/Python packages and pnpm 10.34.5; original individual licenses and package notices remain in the archive. Its preparation, upstream URLs, source digests and complete Node LICENSE are recorded separately in tools/prepare-linux-x86_64.py and the x86_64 runtime lock. Both guests share the Android kernel and are not full Linux virtual machines.
Ubuntu is run through PRoot userspace path/syscall translation, shares the Android kernel, and grants no Android root or security sandbox. Android target 35 runs packaged native executables/loaders from ApplicationInfo.nativeLibraryDir; downloaded guest ELF files are never directly executed by Android.
'@ | Set-Content -LiteralPath $noticePath -Encoding utf8
Write-Output "Prepared five verified native files and complete corresponding native source archives in $runtime"
