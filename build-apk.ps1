param(
    [string]$JavaHome,
    [string]$AndroidSdk,
    [string]$OutputDir = "dist",
    [string]$Keystore,
    [string]$KeyAlias = "androiddebugkey",
    [string]$KeystorePassword = "android",
    [string]$KeyPassword = "android",
    [switch]$Release,
    [switch]$Clean,
    [switch]$RunTests
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location -LiteralPath $ProjectRoot

function Write-Step {
    param([string]$Message)
    Write-Host "==> $Message"
}

function Test-JavaHome {
    param([string]$Path)

    if ([string]::IsNullOrWhiteSpace($Path)) { return $false }

    try {
        return Test-Path -LiteralPath (Join-Path $Path "bin\java.exe")
    } catch {
        return $false
    }
}

function Add-JavaHomeCandidate {
    param(
        [System.Collections.Generic.List[string]]$Candidates,
        [string]$Path
    )

    if ([string]::IsNullOrWhiteSpace($Path)) { return }
    if ($Candidates.Contains($Path)) { return }
    $Candidates.Add($Path)
}

function Resolve-JavaHomePath {
    param([string]$RequestedJavaHome)

    $candidates = New-Object System.Collections.Generic.List[string]
    Add-JavaHomeCandidate $candidates $RequestedJavaHome
    Add-JavaHomeCandidate $candidates ([Environment]::GetEnvironmentVariable("JAVA_HOME", "User"))
    Add-JavaHomeCandidate $candidates ([Environment]::GetEnvironmentVariable("JAVA_HOME", "Machine"))
    Add-JavaHomeCandidate $candidates $env:JAVA_HOME

    foreach ($candidate in @(
        (Join-Path $env:ProgramFiles "Java\jdk-24"),
        (Join-Path $env:ProgramFiles "Java\jdk-21"),
        (Join-Path $env:ProgramFiles "Java\jdk-17"),
        (Join-Path $env:ProgramFiles "Android\Android Studio\jbr")
    )) {
        Add-JavaHomeCandidate $candidates $candidate
    }

    $javaRoot = Join-Path $env:ProgramFiles "Java"
    if (Test-Path -LiteralPath $javaRoot) {
        Get-ChildItem -LiteralPath $javaRoot -Directory -Filter "jdk*" -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object { Add-JavaHomeCandidate $candidates $_.FullName }
    }

    foreach ($candidate in $candidates) {
        if (Test-JavaHome $candidate) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    throw "No valid JDK found. Pass -JavaHome or fix JAVA_HOME."
}

function Resolve-AndroidSdkPath {
    param([string]$RequestedAndroidSdk)

    foreach ($candidate in @(
        $RequestedAndroidSdk,
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        (Join-Path $env:LOCALAPPDATA "Android\Sdk")
    )) {
        if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
        if (Test-Path -LiteralPath (Join-Path $candidate "build-tools")) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    throw "Android SDK not found. Pass -AndroidSdk or set ANDROID_HOME/ANDROID_SDK_ROOT."
}

function Resolve-BuildToolPath {
    param(
        [string]$SdkPath,
        [string]$ToolName
    )

    $buildToolsRoot = Join-Path $SdkPath "build-tools"
    if (-not (Test-Path -LiteralPath $buildToolsRoot)) { throw "Android build-tools not found under $SdkPath." }

    $tool = Get-ChildItem -LiteralPath $buildToolsRoot -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        ForEach-Object { Join-Path $_.FullName $ToolName } |
        Where-Object { Test-Path -LiteralPath $_ } |
        Select-Object -First 1

    if ([string]::IsNullOrWhiteSpace($tool)) { throw "$ToolName not found in Android build-tools." }
    return (Resolve-Path -LiteralPath $tool).Path
}

function Resolve-KeystorePath {
    param([string]$RequestedKeystore)

    if (-not [string]::IsNullOrWhiteSpace($RequestedKeystore)) {
        if (Test-Path -LiteralPath $RequestedKeystore) { return (Resolve-Path -LiteralPath $RequestedKeystore).Path }
        throw "Keystore not found: $RequestedKeystore"
    }

    $debugKeystore = Join-Path $env:USERPROFILE ".android\debug.keystore"
    if (Test-Path -LiteralPath $debugKeystore) { return (Resolve-Path -LiteralPath $debugKeystore).Path }

    throw "Debug keystore not found. Build a debug APK once or pass -Keystore."
}

function Invoke-Gradle {
    param([string[]]$Tasks)

    $gradlew = Join-Path $ProjectRoot "gradlew.bat"
    if (-not (Test-Path -LiteralPath $gradlew)) { throw "gradlew.bat not found in project root." }

    Write-Step "Running Gradle: $($Tasks -join ' ')"
    & $gradlew @Tasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE." }
}

function Get-BuiltApkPath {
    param([string]$Variant)

    $apkCandidates = if ($Variant -eq "release") {
        @(
            (Join-Path $ProjectRoot "app\build\outputs\apk\release\app-release.apk")
            (Join-Path $ProjectRoot "app\build\outputs\apk\release\app-release-unsigned.apk")
        )
    } else {
        @(Join-Path $ProjectRoot "app\build\outputs\apk\debug\app-debug.apk")
    }

    foreach ($apk in $apkCandidates) {
        if (Test-Path -LiteralPath $apk) {
            return (Resolve-Path -LiteralPath $apk).Path
        }
    }

    throw "APK output not found for $Variant build."
}

function Copy-InstallableApk {
    param(
        [string]$SourceApk,
        [string]$DestinationApk,
        [string]$Variant
    )

    if ($Variant -ne "release" -or ((Split-Path -Leaf $SourceApk) -notmatch "unsigned")) {
        Copy-Item -LiteralPath $SourceApk -Destination $DestinationApk -Force
        return
    }

    $sdkPath = Resolve-AndroidSdkPath $AndroidSdk
    $zipalign = Resolve-BuildToolPath -SdkPath $sdkPath -ToolName "zipalign.exe"
    $apksigner = Resolve-BuildToolPath -SdkPath $sdkPath -ToolName "apksigner.bat"
    $keystorePath = Resolve-KeystorePath $Keystore
    $alignedApk = Join-Path ([System.IO.Path]::GetDirectoryName($DestinationApk)) ([System.IO.Path]::GetFileNameWithoutExtension($DestinationApk) + "-aligned.apk")

    Write-Step "Android SDK: $sdkPath"
    Write-Step "Signing release APK with keystore: $keystorePath"

    & $zipalign -f -P 16 4 $SourceApk $alignedApk
    if ($LASTEXITCODE -ne 0) { throw "zipalign failed with exit code $LASTEXITCODE." }

    & $apksigner sign `
        --ks $keystorePath `
        --ks-key-alias $KeyAlias `
        --ks-pass "pass:$KeystorePassword" `
        --key-pass "pass:$KeyPassword" `
        --out $DestinationApk `
        $alignedApk
    if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed with exit code $LASTEXITCODE." }

    & $zipalign -c -P 16 4 $DestinationApk
    if ($LASTEXITCODE -ne 0) { throw "Signed APK 16 KB alignment verification failed with exit code $LASTEXITCODE." }

    Remove-Item -LiteralPath $alignedApk -Force
}

function Test-ApkSignature {
    param([string]$ApkPath)

    $sdkPath = Resolve-AndroidSdkPath $AndroidSdk
    $apksigner = Resolve-BuildToolPath -SdkPath $sdkPath -ToolName "apksigner.bat"

    Write-Step "Verifying APK signature"
    & $apksigner verify --verbose $ApkPath
    if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed with exit code $LASTEXITCODE." }
}

$variant = if ($Release) { "release" } else { "debug" }
$assembleTask = if ($Release) { ":app:assembleRelease" } else { ":app:assembleDebug" }
$resolvedJavaHome = Resolve-JavaHomePath $JavaHome
$env:JAVA_HOME = $resolvedJavaHome

Write-Step "Project: $ProjectRoot"
Write-Step "JAVA_HOME: $resolvedJavaHome"
Write-Step "Variant: $variant"

if ($Release) {
    $provisionModel = Join-Path $ProjectRoot "tools\memory-model\provision-bge-small-zh-v1.5-production.ps1"
    Write-Step "Provisioning checksum-verified production memory model"
    & $provisionModel
    if ($LASTEXITCODE -ne 0) { throw "Memory model provisioning failed with exit code $LASTEXITCODE." }
}

$tasks = New-Object System.Collections.Generic.List[string]
if ($Clean) { $tasks.Add("clean") }
if ($RunTests) { $tasks.Add("test") }
$tasks.Add($assembleTask)

Invoke-Gradle -Tasks $tasks.ToArray()

$apkPath = Get-BuiltApkPath $variant
$outputPath = if ([System.IO.Path]::IsPathRooted($OutputDir)) { $OutputDir } else { Join-Path $ProjectRoot $OutputDir }
New-Item -ItemType Directory -Force -Path $outputPath | Out-Null

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$sourceFileName = Split-Path -Leaf $apkPath
$packageKind = if ($sourceFileName -match "unsigned") { "$variant-debug-signed" } else { $variant }
$destinationPath = Join-Path $outputPath "ChatWithChat-$packageKind-$timestamp.apk"
Copy-InstallableApk -SourceApk $apkPath -DestinationApk $destinationPath -Variant $variant
Test-ApkSignature $destinationPath

Write-Step "APK built successfully"
Write-Host "Source APK: $apkPath"
Write-Host "Copied APK: $destinationPath"
