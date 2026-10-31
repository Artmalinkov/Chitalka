param(
    [string]$DeviceName = 'Chitalka_Phone_API_36',
    [ValidateSet('auto', 'host', 'software', 'swiftshader')]
    [string]$Graphics = 'swiftshader'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$sdkRoot = $env:ANDROID_HOME
if ([string]::IsNullOrWhiteSpace($sdkRoot)) {
    $sdkRoot = [Environment]::GetEnvironmentVariable('ANDROID_HOME', 'User')
}
if ([string]::IsNullOrWhiteSpace($sdkRoot)) {
    $sdkRoot = 'D:\Android\Sdk'
}

# Read saved paths even when this terminal was opened before SDK installation.
$env:ANDROID_HOME = $sdkRoot
foreach ($variableName in @('ANDROID_AVD_HOME', 'ANDROID_USER_HOME')) {
    $savedValue = [Environment]::GetEnvironmentVariable($variableName, 'User')
    if (-not [string]::IsNullOrWhiteSpace($savedValue)) {
        [Environment]::SetEnvironmentVariable($variableName, $savedValue, 'Process')
    }
}

$emulatorPath = Join-Path $sdkRoot 'emulator\emulator.exe'
$adbPath = Join-Path $sdkRoot 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $emulatorPath)) {
    throw "Android Emulator is not installed at $emulatorPath"
}

$availableDevices = @(& $emulatorPath -list-avds)
if ($LASTEXITCODE -ne 0 -or $availableDevices -notcontains $DeviceName) {
    throw "Virtual device '$DeviceName' is not available. Finish Android setup first."
}

if (Test-Path -LiteralPath $adbPath) {
    $deviceLines = @(& $adbPath devices)
    foreach ($deviceLine in $deviceLines) {
        if ($deviceLine -match '^(emulator-\d+)\s+device$') {
            $deviceSerial = $Matches[1]
            $runningName = @(& $adbPath -s $deviceSerial emu avd name)
            if ($runningName -contains $DeviceName) {
                Write-Output "$DeviceName is already running ($deviceSerial)."
                exit 0
            }
        }
    }
}

$accelerationResult = @(& $emulatorPath -accel-check 2>&1)
if ($LASTEXITCODE -ne 0) {
    throw ('Hardware acceleration is unavailable: ' + ($accelerationResult -join ' '))
}

$logDirectory = Join-Path (Split-Path -Parent $sdkRoot) 'ChitalkaLogs'
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
$logStamp = Get-Date -Format 'yyyyMMdd-HHmmss'

# Keep the emulator visible for interactive testing. No wipe-data option is used.
Start-Process -FilePath $emulatorPath `
    -ArgumentList @('-avd', $DeviceName, '-gpu', $Graphics, '-timezone', 'Europe/Moscow', '-no-metrics', '-netdelay', 'none', '-netspeed', 'full') `
    -WindowStyle Normal `
    -RedirectStandardOutput (Join-Path $logDirectory "emulator-$logStamp.stdout.log") `
    -RedirectStandardError (Join-Path $logDirectory "emulator-$logStamp.stderr.log") | Out-Null

Write-Output "Starting $DeviceName. Logs: $logDirectory"
