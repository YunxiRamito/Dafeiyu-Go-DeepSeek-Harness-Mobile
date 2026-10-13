param(
    [string[]]$Tasks = @(':app:assembleRelease', ':app:lintRelease'),
    [string]$TempRoot = (Join-Path (Split-Path $PSScriptRoot -Parent) '..\Temp_Android'),
    [ValidateRange(1, 4)][int]$MaxWorkers = 2,
    [ValidateRange(1024, 16384)][int]$MinimumFreeMemoryMiB = 1536
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$TempRoot = [System.IO.Path]::GetFullPath($TempRoot)
$env:TEMP = Join-Path $TempRoot 'caches\tmp'
$env:TMP = $env:TEMP
$env:TMPDIR = $env:TEMP
$env:GRADLE_USER_HOME = Join-Path $TempRoot 'caches\gradle'
$env:ANDROID_USER_HOME = Join-Path $TempRoot 'caches\android-user'
$env:ANDROID_HOME = Join-Path $TempRoot 'toolchains\android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
New-Item -ItemType Directory -Force -Path $env:TEMP,$env:GRADLE_USER_HOME,$env:ANDROID_USER_HOME | Out-Null
$gradle = Join-Path $TempRoot 'toolchains\gradle-8.11.1\bin\gradle.bat'
if (-not (Test-Path -LiteralPath $gradle)) { throw "请先准备 Gradle 8.11.1：$gradle" }
if (-not (Test-Path -LiteralPath (Join-Path $env:ANDROID_HOME 'platforms\android-35\android.jar'))) {
    throw '请先在 Temp_Android/toolchains/android-sdk 安装 Android SDK 35。'
}
if (-not $env:JAVA_HOME) { throw '请将 JAVA_HOME 设置为 JDK 17 或 21。' }
if ($IsWindows -or $env:OS -eq 'Windows_NT') {
    $availableMiB = [math]::Floor((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1024)
    Write-Output "Build memory: $availableMiB MiB available; max workers: $MaxWorkers"
    if ($availableMiB -lt $MinimumFreeMemoryMiB) {
        throw "Only $availableMiB MiB available; build requires $MinimumFreeMemoryMiB MiB. Release idle build processes before retrying."
    }
}
Push-Location $projectRoot
try {
    & (Join-Path $PSScriptRoot 'prepare-connection-plugin.ps1') -TempRoot $TempRoot
    & (Join-Path $PSScriptRoot 'prepare-mobile-ui.ps1') -TempRoot $TempRoot
    & $gradle --no-daemon --no-parallel "--max-workers=$MaxWorkers" --project-cache-dir (Join-Path $TempRoot 'caches\project') "-Pmobile.outputRoot=$TempRoot/build" @Tasks --console plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle 构建失败，退出码 $LASTEXITCODE" }
    if ($Tasks -contains ':app:assembleRelease' -or $Tasks -contains ':app:assembleDebug') {
        $delivery = Join-Path $TempRoot 'Dist'
        New-Item -ItemType Directory -Force -Path $delivery | Out-Null
        $isRelease = $Tasks -contains ':app:assembleRelease'
        $apk = Join-Path $delivery ('Dafeiyu-Go-Mobile-0.1.27' + $(if ($isRelease) { '' } else { '-dev' }) + '.apk')
        $variant = if ($isRelease) { 'release' } else { 'debug' }
        Copy-Item -LiteralPath (Join-Path $TempRoot "build\app\outputs\apk\$variant\app-$variant.apk") -Destination $apk -Force
        ((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + (Split-Path $apk -Leaf)) |
            Set-Content -LiteralPath "$apk.sha256" -Encoding ascii
        Write-Output "Android delivery: $apk"
    }
} finally { Pop-Location }






