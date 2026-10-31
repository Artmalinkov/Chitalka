param([string]$DeviceSerial='emulator-5554')
$ErrorActionPreference='Stop'
$sourceRoot=Split-Path -Parent $PSScriptRoot
[xml]$manifest=Get-Content -LiteralPath (Join-Path $sourceRoot 'app\src\main\AndroidManifest.xml') -Raw -Encoding UTF8
$version=$manifest.manifest.GetAttribute('versionName','http://schemas.android.com/apk/res/android')
$apk=Join-Path (Join-Path (Split-Path -Parent $sourceRoot) '_приложение') "Chitalka-$version-debug.apk"
if(-not(Test-Path -LiteralPath $apk)){throw 'Build the application first: tools/Build-Android.ps1'}
& (Join-Path $PSScriptRoot 'Start-AndroidEmulator.ps1')
$sdk=[Environment]::GetEnvironmentVariable('ANDROID_HOME','User')
if([string]::IsNullOrWhiteSpace($sdk)){$sdk='D:\Android\Sdk'}
$adb=Join-Path $sdk 'platform-tools\adb.exe'
$ready=$false
for($i=0;$i -lt 45;$i++){
    $state=& $adb -s $DeviceSerial get-state 2>$null
    if($state -eq 'device'){
        $boot=& $adb -s $DeviceSerial shell getprop sys.boot_completed
        if(($boot -join '').Trim() -eq '1'){$ready=$true;break}
    }
    Start-Sleep -Seconds 2
}
if(-not $ready){throw 'Android did not finish booting. Check emulator logs in D:/Android/ChitalkaLogs.'}
& $adb -s $DeviceSerial install -r $apk
if($LASTEXITCODE -ne 0){throw 'APK installation failed'}
& $adb -s $DeviceSerial shell am start -n ru.devimind.chitalka/.MainActivity
if($LASTEXITCODE -ne 0){throw 'Application start failed'}
