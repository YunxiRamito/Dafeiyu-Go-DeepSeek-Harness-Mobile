param()
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$outputRoot = Join-Path $PSScriptRoot ('local-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $outputRoot | Out-Null
$files = @(
    'HANDOVER.md', 'app/build.gradle.kts', 'app/src/main/AndroidManifest.xml',
    'app/src/main/java/com/dafeiyu/mobile/MainActivity.kt',
    'app/src/main/java/com/dafeiyu/mobile/data/DemoRepository.kt',
    'app/src/main/java/com/dafeiyu/mobile/data/OfficialToolContract.kt',
    'app/src/main/java/com/dafeiyu/mobile/data/OfficialToolDispatcher.kt',
    'app/src/main/java/com/dafeiyu/mobile/data/OfficialToolSession.kt',
    'app/src/main/java/com/dafeiyu/mobile/data/OfficialToolOutputStore.kt',
    'app/src/main/java/com/dafeiyu/mobile/data/OfficialWebClient.kt',
    'app/src/main/java/com/dafeiyu/mobile/data/DeepSeekChatClient.kt',
    'app/src/main/java/com/dafeiyu/mobile/data/ConversationAttachmentStore.kt',
    'app/src/main/java/com/dafeiyu/mobile/data/OfficialChatHistory.kt',
    'app/src/main/java/com/dafeiyu/mobile/ui/AttachmentPanel.kt',
    'app/src/main/java/com/dafeiyu/mobile/ui/ScannerScreen.kt',
    'app/src/main/java/com/dafeiyu/mobile/data/BackgroundTaskEngine.kt',
    'app/src/main/java/com/dafeiyu/mobile/data/BackgroundTaskModels.kt',
    'app/src/main/java/com/dafeiyu/mobile/data/BackgroundTaskCredentialBoundary.kt',
    'app/src/main/java/com/dafeiyu/mobile/data/ConnectionPluginPackage.kt',
    'app/src/main/java/com/dafeiyu/mobile/data/LocalPluginBridge.kt',
    'app/src/main/java/com/dafeiyu/mobile/data/LocalPluginIpcPolicy.kt',
    'app/src/main/java/com/dafeiyu/mobile/data/AriesConnectionProvider.kt',
    'app/src/main/java/com/dafeiyu/mobile/data/DeveloperCenterPresenceClient.kt',
    'app/src/main/java/com/dafeiyu/mobile/ui/SettingsScreens.kt',
    'mobile-dsh-plugin/package.json', 'mobile-dsh-plugin/HOST-CONTRACT.md',
    'mobile-dsh-plugin/EVIDENCE.md', 'mobile-dsh-plugin/README.md',
    'mobile-dsh-plugin/src/core.js', 'mobile-dsh-plugin/src/client.js',
    'mobile-dsh-plugin/src/index.js', 'mobile-dsh-plugin/lib/client.js',
    'tools/prepare-node-runtime.ps1', 'tools/prepare-connection-plugin.ps1'
)
$manifest = [Collections.Generic.List[object]]::new()
$sources = [Collections.Generic.List[string]]::new()
foreach ($file in $files) {
    $path = Join-Path $projectRoot $file
    if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing source: $file" }
    $before = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    $lines = [IO.File]::ReadAllLines($path)
    $after = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($before -ne $after) { throw "Source changed while reading; retry: $file" }
    $manifest.Add([pscustomobject]@{ path = $file; sha256 = $after; lines = $lines.Length })
    $sources.Add("FILE: $file SHA256: $after")
    for ($i = 0; $i -lt $lines.Length; $i++) { $sources.Add(('{0,5}: {1}' -f ($i + 1), $lines[$i])) }
}
$sources | Set-Content -LiteralPath (Join-Path $outputRoot 'numbered-sources.log') -Encoding utf8

# Parse ELF64 metadata as data. Do not invoke Android binaries or loaders.
function Read-Elf64([string]$path) {
    $fileStream = [IO.File]::OpenRead($path)
    $reader = [IO.BinaryReader]::new($fileStream)
    try {
    $header = $reader.ReadBytes(64)
    if ($header.Length -ne 64 -or $header[0] -ne 127 -or $header[1] -ne 69 -or
        $header[2] -ne 76 -or $header[3] -ne 70 -or $header[4] -ne 2 -or $header[5] -ne 1) {
        throw "Expected little-endian ELF64: $path"
    }
    function Read-BytesAt([long]$offset, [int]$length) {
        if ($offset -lt 0 -or $length -lt 0 -or $offset + $length -gt $fileStream.Length) {
            throw 'Invalid ELF read range.'
        }
        $fileStream.Position = $offset
        $result = $reader.ReadBytes($length)
        if ($result.Length -ne $length) { throw 'Incomplete ELF read.' }
        return ,$result
    }
    function Read-ZeroString([long]$offset) {
        if ($offset -lt 0 -or $offset -ge $fileStream.Length) { throw 'Invalid ELF string offset.' }
        $fileStream.Position = $offset
        $text = [Collections.Generic.List[byte]]::new()
        while ($text.Count -lt 4096 -and $fileStream.Position -lt $fileStream.Length) {
            $value = $reader.ReadByte()
            if ($value -eq 0) { return [Text.Encoding]::ASCII.GetString($text.ToArray()) }
            $text.Add($value)
        }
        throw 'Unterminated or oversized ELF string.'
    }
    $phoff = [BitConverter]::ToUInt64($header, 32)
    $phsize = [BitConverter]::ToUInt16($header, 54)
    $phcount = [BitConverter]::ToUInt16($header, 56)
    if ($phcount -gt 1024 -or $phsize -lt 56) { throw 'ELF program header limit.' }
    $segments = @()
    $interpreter = $null
    for ($i = 0; $i -lt $phcount; $i++) {
        $bytes = Read-BytesAt ($phoff + $i * $phsize) 56
        $segment = [pscustomobject]@{
            type = [BitConverter]::ToUInt32($bytes, 0)
            offset = [BitConverter]::ToUInt64($bytes, 8)
            address = [BitConverter]::ToUInt64($bytes, 16)
            size = [BitConverter]::ToUInt64($bytes, 32)
        }
        $segments += $segment
        if ($segment.type -eq 3) { $interpreter = Read-ZeroString $segment.offset }
    }
    $needed = @()
    $sonameOffset = $null
    $stringAddress = $null
    $pathOffsets = @()
    foreach ($segment in @($segments | Where-Object type -eq 2)) {
        if ($segment.size -gt 1MB) { throw 'ELF dynamic table limit.' }
        for ($at = $segment.offset; $at + 16 -le $segment.offset + $segment.size; $at += 16) {
            $bytes = Read-BytesAt $at 16
            $tag = [BitConverter]::ToInt64($bytes, 0)
            $value = [BitConverter]::ToUInt64($bytes, 8)
            if ($tag -eq 0) { break }
            if ($tag -eq 1) { $needed += $value }
            if ($tag -eq 5) { $stringAddress = $value }
            if ($tag -eq 14) { $sonameOffset = $value }
            if ($tag -in @(15, 29)) { $pathOffsets += $value }
        }
    }
    $names = @()
    $paths = @()
    $soname = $null
    if ($null -ne $stringAddress) {
        $load = $segments | Where-Object {
            $_.type -eq 1 -and $stringAddress -ge $_.address -and $stringAddress -lt $_.address + $_.size
        } | Select-Object -First 1
        if ($null -eq $load) { throw 'ELF dynamic string table cannot be mapped.' }
        $base = $load.offset + ($stringAddress - $load.address)
        $names = @($needed | ForEach-Object { Read-ZeroString ($base + $_) })
        $paths = @($pathOffsets | ForEach-Object { Read-ZeroString ($base + $_) })
        if ($null -ne $sonameOffset) { $soname = Read-ZeroString ($base + $sonameOffset) }
    }
    [pscustomobject]@{
        file = [IO.Path]::GetFileName($path); machine = [BitConverter]::ToUInt16($header, 18)
        interpreter = $interpreter; needed = $names; soname = $soname; rpath_runpath = $paths
    }
    } finally { $reader.Dispose(); $fileStream.Dispose() }
}

$runtimeRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '../Temp_Android/runtime'))
$lockPath = Join-Path $runtimeRoot 'assets/node-runtime/runtime-lock.json'
$runtime = [ordered]@{ root = $runtimeRoot; present = (Test-Path -LiteralPath $runtimeRoot); lock = $null; entries = @(); hashes = @(); elf = @() }
if (Test-Path -LiteralPath $runtimeRoot) {
    $runtime.entries = @(Get-ChildItem -LiteralPath (Join-Path $runtimeRoot 'assets') -Recurse -File |
        ForEach-Object { $_.FullName.Substring($runtimeRoot.Length + 1) })
    $runtime.abi_directories = @(Get-ChildItem -LiteralPath (Join-Path $runtimeRoot 'jniLibs') -Directory | ForEach-Object Name)
}
if (Test-Path -LiteralPath $lockPath) {
    $lock = Get-Content -LiteralPath $lockPath -Raw | ConvertFrom-Json
    $runtime.lock = $lock
    $native = Join-Path $runtimeRoot ('jniLibs/' + $lock.architecture)
    $records = @([pscustomobject]@{ packaged = 'libnode_exec.so'; sha256 = $lock.executable_sha256 }) + @($lock.libraries)
    foreach ($record in $records) {
        $path = Join-Path $native $record.packaged
        $present = Test-Path -LiteralPath $path -PathType Leaf
        $hash = if ($present) { (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() } else { $null }
        $runtime.hashes += [pscustomobject]@{
            file = $record.packaged; exists = $present; expected = $record.sha256
            actual = $hash; matches = $present -and $hash -eq $record.sha256
        }
        if ($present) { $runtime.elf += Read-Elf64 $path }
    }
}
$report = [ordered]@{
    observed_at = [DateTimeOffset]::Now.ToString('o'); project = $projectRoot
    scope = 'Static source/hash/ELF data only; no behavioral test, runtime launch, Gradle, ADB, account, model or private application data.'
    git_directory_present = (Test-Path -LiteralPath (Join-Path $projectRoot '.git'))
    sources = $manifest.ToArray(); runtime = $runtime
}
$report | ConvertTo-Json -Depth 16 | Set-Content -LiteralPath (Join-Path $outputRoot 'audit.json') -Encoding utf8
Write-Output "Evidence: $outputRoot"
Write-Output "Sources: $($manifest.Count); matched runtime files: $(@($runtime.hashes | Where-Object matches).Count)/$($runtime.hashes.Count)"
