param(
    [string]$SdkPath = 'D:\Android\Sdk',
    [string]$JavaHome = 'C:\Program Files\JetBrains\PyCharm 2026.1\jbr',
    [string]$BuildRoot = 'D:\Android\Build\Chitalka',
    [switch]$WithChecks
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Get-AndroidSha256.ps1')
$sourceRoot = Split-Path -Parent $PSScriptRoot
$privateRoot = Join-Path $BuildRoot 'private'
$key = Join-Path $privateRoot 'debug.keystore'
$existingApks = Join-Path (Split-Path -Parent $sourceRoot) '_приложение'
if (-not (Test-Path -LiteralPath $key) -and @(Get-ChildItem -LiteralPath $existingApks -Filter '*.apk' -ErrorAction SilentlyContinue).Count -gt 0) {
    throw 'Signing key is missing. Restore the original private/debug.keystore before building updates; a new key would make existing installations incompatible.'
}
[xml]$manifest = Get-Content -LiteralPath (Join-Path $sourceRoot 'app\src\main\AndroidManifest.xml') -Raw -Encoding UTF8
$version = $manifest.manifest.GetAttribute('versionName', 'http://schemas.android.com/apk/res/android')
$apkName = "Chitalka-$version-debug.apk"
$outputRoot = Join-Path (Split-Path -Parent $sourceRoot) '_приложение'
$runRoot = Join-Path $BuildRoot (Get-Date -Format 'yyyyMMdd-HHmmss-fff')
$toolsRoot = Join-Path $SdkPath 'build-tools\36.0.0'
$androidJar = Join-Path $SdkPath 'platforms\android-36\android.jar'
$javaBin = Join-Path $JavaHome 'bin'
$env:JAVA_HOME = $JavaHome
$env:PATH = $javaBin + ';' + $env:PATH

function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Build command failed: $Program ($LASTEXITCODE)" }
}
foreach ($path in @($androidJar, (Join-Path $javaBin 'javac.exe'), (Join-Path $toolsRoot 'aapt2.exe'))) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing build tool: $path" }
}
$classes=Join-Path $runRoot 'classes'
$generated=Join-Path $runRoot 'generated'
$dex=Join-Path $runRoot 'dex'
New-Item -ItemType Directory -Path $classes,$generated,$dex,$outputRoot -Force | Out-Null
# The Windows AAPT2 binary cannot open this project's Cyrillic path. Stage an
# exact copy in an ASCII path; source files and final APK stay in the project.
$staged=Join-Path $runRoot 'project'
Copy-Item -LiteralPath (Join-Path $sourceRoot 'app') -Destination $staged -Recurse
$resources=Join-Path $runRoot 'resources.zip'
$unsigned=Join-Path $runRoot 'unsigned.apk'
$aligned=Join-Path $runRoot 'aligned.apk'
Invoke-Checked (Join-Path $toolsRoot 'aapt2.exe') @('compile','--dir',(Join-Path $staged 'src\main\res'),'-o',$resources)
Invoke-Checked (Join-Path $toolsRoot 'aapt2.exe') @('link','-o',$unsigned,'--manifest',(Join-Path $staged 'src\main\AndroidManifest.xml'),'-I',$androidJar,'--java',$generated,'--auto-add-overlay',$resources)
$javaFiles=@(Get-ChildItem -LiteralPath (Join-Path $staged 'src\main\java'),$generated -Recurse -Filter '*.java' | ForEach-Object {$_.FullName})
$argumentsFile=Join-Path $runRoot 'javac.args'
# javac argument files are UTF-8 in this JDK. Quote paths independently of the shell.
$compilerArgs=@('--release','17','-encoding','UTF-8','-g','-classpath',$androidJar,'-d',$classes)+$javaFiles
$quotedArgs=@($compilerArgs | ForEach-Object {'"'+$_.Replace('\','/')+'"'})
[IO.File]::WriteAllLines($argumentsFile,$quotedArgs,[Text.UTF8Encoding]::new($false))
Invoke-Checked (Join-Path $javaBin 'javac.exe') @('@'+$argumentsFile)
$classesJar=Join-Path $runRoot 'classes.jar'
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
function Write-ClassJar([string]$Folder,[string]$ArchivePath) {
    $archive=[IO.Compression.ZipFile]::Open($ArchivePath,[IO.Compression.ZipArchiveMode]::Create)
    try {
        Get-ChildItem -LiteralPath $Folder -Recurse -File | ForEach-Object {
            $entry=$_.FullName.Substring($Folder.Length+1).Replace('\','/')
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$_.FullName,$entry) | Out-Null
        }
    } finally { $archive.Dispose() }
}
Write-ClassJar $classes $classesJar
Invoke-Checked (Join-Path $toolsRoot 'd8.bat') @('--debug','--min-api','26','--lib',$androidJar,'--output',$dex,$classesJar)
$zip=[IO.Compression.ZipFile]::Open($unsigned,[IO.Compression.ZipArchiveMode]::Update)
try { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,(Join-Path $dex 'classes.dex'),'classes.dex') | Out-Null }
finally { $zip.Dispose() }
Invoke-Checked (Join-Path $toolsRoot 'zipalign.exe') @('-f','-p','4',$unsigned,$aligned)
New-Item -ItemType Directory -Path $privateRoot -Force | Out-Null
if (-not (Test-Path -LiteralPath $key)) {
    Invoke-Checked (Join-Path $javaBin 'keytool.exe') @('-genkeypair','-keystore',$key,'-storepass','android','-keypass','android','-alias','androiddebugkey','-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=Chitalka Development,O=Local Development,C=RU','-noprompt')
}
$apk=Join-Path $outputRoot $apkName
$stagedApk=Join-Path $runRoot $apkName
Invoke-Checked (Join-Path $toolsRoot 'apksigner.bat') @('sign','--ks',$key,'--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--key-pass','pass:android','--out',$stagedApk,$aligned)
Invoke-Checked (Join-Path $toolsRoot 'apksigner.bat') @('verify','--verbose',$stagedApk)
Copy-Item -LiteralPath $stagedApk -Destination $apk -Force
$hash=Get-AndroidSha256 -LiteralPath $apk
$checksums=@(Get-ChildItem -LiteralPath $outputRoot -Filter '*.apk' | Sort-Object Name | ForEach-Object { (Get-AndroidSha256 -LiteralPath $_.FullName)+'  '+$_.Name })
[IO.File]::WriteAllLines((Join-Path $outputRoot 'SHA256SUMS.txt'),$checksums,[Text.UTF8Encoding]::new($false))
Write-Output "APK: $apk"
Write-Output "SHA256: $hash"
if ($WithChecks) {
    $testClasses=Join-Path $runRoot 'test-classes'
    $testDex=Join-Path $runRoot 'test-dex'
    New-Item -ItemType Directory -Path $testClasses,$testDex -Force | Out-Null
    $testSources=@(Get-ChildItem -LiteralPath (Join-Path $staged 'src\androidTest\java') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
    $testArgs=Join-Path $runRoot 'test-javac.args'
    $testCompilerArgs=@('--release','17','-encoding','UTF-8','-classpath',($androidJar+';'+$classesJar),'-d',$testClasses)+$testSources
    [IO.File]::WriteAllLines($testArgs,@($testCompilerArgs | ForEach-Object {'"'+$_.Replace('\','/')+'"'}),[Text.UTF8Encoding]::new($false))
    Invoke-Checked (Join-Path $javaBin 'javac.exe') @('@'+$testArgs)
    $testJar=Join-Path $runRoot 'checks.jar'
    Write-ClassJar $testClasses $testJar
    Invoke-Checked (Join-Path $toolsRoot 'd8.bat') @('--min-api','26','--lib',$androidJar,'--classpath',$classesJar,'--output',$testDex,$testJar)
    $testUnsigned=Join-Path $runRoot 'checks-unsigned.apk'
    Invoke-Checked (Join-Path $toolsRoot 'aapt2.exe') @('link','-o',$testUnsigned,'--manifest',(Join-Path $staged 'src\androidTest\AndroidManifest.xml'),'-I',$androidJar)
    $testZip=[IO.Compression.ZipFile]::Open($testUnsigned,[IO.Compression.ZipArchiveMode]::Update)
    try { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($testZip,(Join-Path $testDex 'classes.dex'),'classes.dex') | Out-Null } finally {$testZip.Dispose()}
    $testAligned=Join-Path $runRoot 'checks-aligned.apk'
    Invoke-Checked (Join-Path $toolsRoot 'zipalign.exe') @('-f','4',$testUnsigned,$testAligned)
    $testApk=Join-Path $runRoot 'checks.apk'
    Invoke-Checked (Join-Path $toolsRoot 'apksigner.bat') @('sign','--ks',$key,'--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--key-pass','pass:android','--out',$testApk,$testAligned)
    New-Item -ItemType Directory -Path (Join-Path $sourceRoot '.local') -Force | Out-Null
    Copy-Item -LiteralPath $testApk -Destination (Join-Path $sourceRoot '.local\checks.apk') -Force
    Write-Output 'Separate parser-check APK prepared in .local/checks.apk'
}
