param(
    [string]$DeviceSerial,
    [string]$ConnectEndpoint,
    [string]$SdkPath = 'D:\Android\Sdk',
    [string]$JavaHome = 'C:\Program Files\JetBrains\PyCharm 2026.1\jbr',
    [switch]$Build
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Get-AndroidSha256.ps1')
$sourceRoot = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $SdkPath 'platform-tools\adb.exe'
$aapt = Join-Path $SdkPath 'build-tools\36.0.0\aapt2.exe'
$apksigner = Join-Path $SdkPath 'build-tools\36.0.0\apksigner.bat'
$env:JAVA_HOME = $JavaHome
$env:PATH = (Join-Path $JavaHome 'bin') + ';' + $env:PATH

function Invoke-Adb([string[]]$AdbArguments) {
    $lines = @(& $adb @AdbArguments)
    if ($LASTEXITCODE -ne 0) { throw ('Ошибка связи с Android: ' + ($lines -join "`n")) }
    return $lines
}
function Get-ApkCertificate([string]$Path) {
    $lines = @(& $apksigner verify --print-certs $Path)
    if ($LASTEXITCODE -ne 0) { throw "Не удалось проверить подпись APK: $Path" }
    $digests = @($lines | Where-Object { $_ -match '^Signer #\d+ certificate SHA-256 digest:' } | ForEach-Object { ($_ -split ': ', 2)[1].Trim() } | Sort-Object)
    if ($digests.Count -ne 1) { throw 'Ожидалась одна проверенная подпись APK.' }
    return $digests[0]
}

if ($ConnectEndpoint) {
    if ($ConnectEndpoint -notmatch '^\d{1,3}(\.\d{1,3}){3}:\d{1,5}$') { throw 'Адрес подключения должен иметь вид 192.168.1.10:12345.' }
    Invoke-Adb @('connect', $ConnectEndpoint) | Write-Output
    if (-not $DeviceSerial) { $DeviceSerial = $ConnectEndpoint }
}
$devices = @(Invoke-Adb @('devices') | Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
if (-not $DeviceSerial) {
    # Automatic selection accepts Wi-Fi transports only. Never choose the emulator or a USB device.
    $wireless = @($devices | Where-Object { $_ -match '^\d{1,3}(\.\d{1,3}){3}:\d+$|\._adb-tls-connect\._tcp\.?$' })
    if ($wireless.Count -ne 1) {
        throw 'Нужен один подключённый по Wi-Fi телефон. Включите беспроводную отладку; если устройств несколько, укажите -DeviceSerial. При смене порта используйте -ConnectEndpoint IP:порт с главного экрана беспроводной отладки.'
    }
    $DeviceSerial = $wireless[0]
}
if ($devices -notcontains $DeviceSerial) { throw "Устройство $DeviceSerial не подключено или не разрешило отладку." }
$model = (Invoke-Adb @('-s', $DeviceSerial, 'shell', 'getprop', 'ro.product.model') -join '').Trim()
Write-Output "Устройство: $model ($DeviceSerial)"

if ($Build) {
    & (Join-Path $PSScriptRoot 'Build-Android.ps1') -SdkPath $SdkPath -JavaHome $JavaHome
}
[xml]$manifest = Get-Content -LiteralPath (Join-Path $sourceRoot 'app\src\main\AndroidManifest.xml') -Raw -Encoding UTF8
$ns = 'http://schemas.android.com/apk/res/android'
$package = $manifest.manifest.GetAttribute('package')
$version = $manifest.manifest.GetAttribute('versionName', $ns)
$versionCode = [long]$manifest.manifest.GetAttribute('versionCode', $ns)
$apk = Join-Path (Join-Path (Split-Path -Parent $sourceRoot) '_приложение') "Chitalka-$version-debug.apk"
if (-not (Test-Path -LiteralPath $apk)) { throw 'APK не найден. Повторите с параметром -Build.' }

# Keep a fixed APK snapshot in an ASCII path for the Windows Android tools.
$checkRoot = Join-Path 'D:\Android\Build\Chitalka\device-checks' ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $checkRoot -Force | Out-Null
$candidateApk = Join-Path $checkRoot 'candidate.apk'
$installedApk = Join-Path $checkRoot 'installed.apk'
try {
    Copy-Item -LiteralPath $apk -Destination $candidateApk
    # Inspect the APK itself rather than trusting its filename or the source manifest.
    $badging = @(& $aapt dump badging $candidateApk)
    if ($LASTEXITCODE -ne 0) { throw 'Не удалось прочитать сведения APK.' }
    $packageLine = @($badging | Where-Object { $_ -match '^package:' }) -join ''
    if ($packageLine -notmatch "name='([^']+)' versionCode='(\d+)' versionName='([^']+)'") { throw 'Не удалось определить пакет и версию APK.' }
    if ($Matches[1] -ne $package -or [long]$Matches[2] -ne $versionCode -or $Matches[3] -ne $version) {
        throw 'APK не соответствует текущим исходникам. Повторите с параметром -Build.'
    }
    $newCertificate = Get-ApkCertificate $candidateApk
    $installedPackages = @(Invoke-Adb @('-s', $DeviceSerial, 'shell', 'pm', 'list', 'packages', $package))
    $previousVersionCode = $null
    if ($installedPackages -contains "package:$package") {
        $paths = @(Invoke-Adb @('-s', $DeviceSerial, 'shell', 'pm', 'path', $package) | Where-Object { $_ -match '^package:' })
        if ($paths.Count -eq 0) { throw 'Не удалось получить установленный APK; обновление остановлено.' }
        $details = Invoke-Adb @('-s', $DeviceSerial, 'shell', 'dumpsys', 'package', $package)
        $versionLine = @($details | Where-Object { $_ -match '^\s*versionCode=' }) | Select-Object -First 1
        if ($versionLine -notmatch 'versionCode=(\d+)') { throw 'Не удалось определить установленную версию; обновление остановлено.' }
        $previousVersionCode = [long]$Matches[1]
        if ($versionCode -lt $previousVersionCode) { throw 'APK старее установленной версии. Понижение версии запрещено; данные не менялись.' }
        $basePath = @($paths | Where-Object { $_ -match '/base\.apk\s*$' } | Select-Object -First 1)
        if ($basePath.Count -eq 0) { $basePath = @($paths[0]) }
        $remoteApk = ($basePath[0] -replace '^package:', '').Trim()
        Invoke-Adb @('-s', $DeviceSerial, 'pull', $remoteApk, $installedApk) | Write-Output
        if ((Get-ApkCertificate $installedApk) -ne $newCertificate) {
            throw 'Подпись APK отличается от установленной версии. Обновление остановлено; приложение и данные сохранены. Нужен прежний ключ подписи.'
        }
        # HOME lets onPause save the current note and reading position before replacement.
        Invoke-Adb @('-s', $DeviceSerial, 'shell', 'input', 'keyevent', 'KEYCODE_HOME') | Out-Null
        Start-Sleep -Milliseconds 700
        Write-Output "Обновление до $version с сохранением данных..."
    } else {
        Write-Output "Первая установка версии $version..."
    }
    # Never uninstall, clear application data, bypass signatures, or allow a downgrade.
    $install = Invoke-Adb @('-s', $DeviceSerial, 'install', '-r', $candidateApk)
    $install | Write-Output
    if (($install -join "`n") -notmatch '(?m)^Success\s*$') { throw 'Android не подтвердил успешную установку.' }
    $after = Invoke-Adb @('-s', $DeviceSerial, 'shell', 'dumpsys', 'package', $package)
    if (-not ($after | Where-Object { $_ -match "^\s*versionCode=$versionCode(?:\s|$)" })) { throw 'Установленная версия не подтвердилась.' }
    $launch = Invoke-Adb @('-s', $DeviceSerial, 'shell', 'am', 'start', '-W', '-n', "$package/.MainActivity")
    $launch | Write-Output
    if (($launch -join "`n") -notmatch '(?m)^Status: ok\s*$') { throw 'Версия установлена, но Android не подтвердил открытие приложения.' }
    $localRoot = Join-Path $sourceRoot '.local'
    New-Item -ItemType Directory -Path $localRoot -Force | Out-Null
    $record = [ordered]@{
        completedAt = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTimeOffset]::UtcNow, 'Russian Standard Time').ToString('o')
        device = $DeviceSerial; model = $model; package = $package
        version = $version; versionCode = $versionCode; previousVersionCode = $previousVersionCode
        certificateSha256 = $newCertificate; apkSha256 = Get-AndroidSha256 -LiteralPath $candidateApk
        installation = 'adb install -r'; launchConfirmed = $true
    }
    [IO.File]::WriteAllText((Join-Path $localRoot 'last-update.json'), ($record | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
    Write-Output "Готово: Читалка $version установлена и открыта на $model."
} finally {
    foreach ($temporaryApk in @($candidateApk, $installedApk)) {
        if (Test-Path -LiteralPath $temporaryApk) { Remove-Item -LiteralPath $temporaryApk -Force }
    }
    Remove-Item -LiteralPath $checkRoot -ErrorAction SilentlyContinue
}
