param(
    [string]$JavaHome,
    [string]$AndroidSdk,
    [string]$OutputDir = "dist",
    [Alias("Abi")]
    [ValidateSet("arm64-v8a", "armeabi-v7a", "x86", "x86_64")]
    [string]$TargetAbi = "arm64-v8a",
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
$ExpectedApplicationId = "cn.nabr.chatwithchat"
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
    param(
        [string[]]$Tasks,
        [string[]]$CleanRetryTasks = @()
    )

    $gradlew = Join-Path $ProjectRoot "gradlew.bat"
    if (-not (Test-Path -LiteralPath $gradlew)) { throw "gradlew.bat not found in project root." }

    Write-Step "Running Gradle: $($Tasks -join ' ')"
    & $gradlew @Tasks
    $exitCode = $LASTEXITCODE
    if ($exitCode -eq 0) { return }

    if ($CleanRetryTasks.Count -eq 0) {
        throw "Gradle failed with exit code $exitCode."
    }

    Write-Step "Gradle failed with exit code $exitCode. Retrying once after clean."
    Write-Step "Running Gradle: $($CleanRetryTasks -join ' ')"
    & $gradlew @CleanRetryTasks
    $retryExitCode = $LASTEXITCODE
    if ($retryExitCode -ne 0) {
        throw "Gradle failed with exit code $exitCode and clean retry failed with exit code $retryExitCode."
    }
}

function Get-BuiltApkPath {
    param(
        [string]$Variant,
        [string]$ExpectedApplicationId
    )

    $outputDirectory = Join-Path $ProjectRoot "app\build\outputs\apk\$Variant"
    $metadataPath = Join-Path $outputDirectory "output-metadata.json"
    if (-not (Test-Path -LiteralPath $metadataPath)) {
        throw "Gradle APK output metadata not found for $Variant build: $metadataPath"
    }

    try {
        $metadata = Get-Content -Raw -Encoding UTF8 -LiteralPath $metadataPath | ConvertFrom-Json
    } catch {
        throw "Failed to read Gradle APK output metadata for $Variant build: $($_.Exception.Message)"
    }

    if ($metadata.applicationId -cne $ExpectedApplicationId) {
        throw "Gradle APK metadata applicationId mismatch. Expected '$ExpectedApplicationId', found '$($metadata.applicationId)'."
    }

    $outputFiles = @(
        $metadata.elements |
            ForEach-Object { $_.outputFile } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    )
    if ($outputFiles.Count -ne 1) {
        throw "Expected one APK output for $Variant build, found $($outputFiles.Count)."
    }

    $apkPath = Join-Path $outputDirectory $outputFiles[0]
    if (-not (Test-Path -LiteralPath $apkPath)) {
        throw "APK listed in Gradle output metadata was not found: $apkPath"
    }

    return (Resolve-Path -LiteralPath $apkPath).Path
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

    try {
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
    } finally {
        if (Test-Path -LiteralPath $alignedApk) {
            Remove-Item -LiteralPath $alignedApk -Force
        }
    }
}

function Test-ApkSignature {
    param([string]$ApkPath)

    $sdkPath = Resolve-AndroidSdkPath $AndroidSdk
    $apksigner = Resolve-BuildToolPath -SdkPath $sdkPath -ToolName "apksigner.bat"

    Write-Step "Verifying APK signature"
    & $apksigner verify --verbose $ApkPath
    if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed with exit code $LASTEXITCODE." }
}

function Test-ApkApplicationId {
    param(
        [string]$ApkPath,
        [string]$ExpectedApplicationId
    )

    $sdkPath = Resolve-AndroidSdkPath $AndroidSdk
    $aapt2 = Resolve-BuildToolPath -SdkPath $sdkPath -ToolName "aapt2.exe"
    $applicationIdOutput = @(& $aapt2 dump packagename $ApkPath)
    $inspectionExitCode = $LASTEXITCODE
    if ($inspectionExitCode -ne 0) {
        throw "APK applicationId inspection failed with exit code $inspectionExitCode."
    }
    if ($applicationIdOutput.Count -ne 1 -or [string]::IsNullOrWhiteSpace($applicationIdOutput[0])) {
        throw "APK applicationId inspection returned unexpected output."
    }
    $actualApplicationId = $applicationIdOutput[0].Trim()
    if ($actualApplicationId -cne $ExpectedApplicationId) {
        throw "APK applicationId mismatch. Expected '$ExpectedApplicationId', found '$actualApplicationId'."
    }

    Write-Step "Verified APK applicationId: $ExpectedApplicationId"
}

function Test-ApkAbi {
    param(
        [string]$ApkPath,
        [string]$ExpectedAbi
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($ApkPath)
    try {
        $packagedAbis = @(
            $archive.Entries |
                Where-Object { $_.FullName -match "^lib/[^/]+/.+\.so$" } |
                ForEach-Object {
                    [regex]::Match($_.FullName, "^lib/([^/]+)/").Groups[1].Value
                } |
                Sort-Object -Unique
        )
    } finally {
        $archive.Dispose()
    }

    if ($packagedAbis.Count -ne 1 -or $packagedAbis[0] -ne $ExpectedAbi) {
        throw "APK ABI verification failed. Expected only '$ExpectedAbi', found: $($packagedAbis -join ', ')."
    }

    Write-Step "Verified APK contains only $ExpectedAbi native libraries"
}

$variant = if ($Release) { "release" } else { "debug" }
$assembleTask = if ($Release) { ":app:assembleRelease" } else { ":app:assembleDebug" }
$resolvedJavaHome = Resolve-JavaHomePath $JavaHome
$env:JAVA_HOME = $resolvedJavaHome

Write-Step "Project: $ProjectRoot"
Write-Step "JAVA_HOME: $resolvedJavaHome"
Write-Step "Variant: $variant"
Write-Step "Target ABI: $TargetAbi"

if ($Release) {
    $provisionModel = Join-Path $ProjectRoot "tools\memory-model\provision-bge-small-zh-v1.5-production.ps1"
    if (-not (Test-Path -LiteralPath $provisionModel)) {
        throw "Memory model provisioning script not found: $provisionModel"
    }

    Write-Step "Provisioning checksum-verified production memory model"
    try {
        & $provisionModel
    } catch {
        throw "Memory model provisioning failed: $($_.Exception.Message)"
    }
}

$tasks = New-Object System.Collections.Generic.List[string]
$tasks.Add("-PchatWithChatApkAbi=$TargetAbi")
if ($Clean) { $tasks.Add("clean") }
if ($RunTests) { $tasks.Add("test") }
$tasks.Add($assembleTask)

if ($Clean) {
    Invoke-Gradle -Tasks $tasks.ToArray()
} else {
    $cleanRetryTasks = New-Object System.Collections.Generic.List[string]
    $cleanRetryTasks.Add("-PchatWithChatApkAbi=$TargetAbi")
    $cleanRetryTasks.Add("clean")
    if ($RunTests) { $cleanRetryTasks.Add("test") }
    $cleanRetryTasks.Add($assembleTask)
    Invoke-Gradle -Tasks $tasks.ToArray() -CleanRetryTasks $cleanRetryTasks.ToArray()
}

$apkPath = Get-BuiltApkPath -Variant $variant -ExpectedApplicationId $ExpectedApplicationId
$outputPath = if ([System.IO.Path]::IsPathRooted($OutputDir)) { $OutputDir } else { Join-Path $ProjectRoot $OutputDir }
New-Item -ItemType Directory -Force -Path $outputPath | Out-Null

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$sourceFileName = Split-Path -Leaf $apkPath
$packageKind = if ($sourceFileName -match "unsigned") { "$variant-$TargetAbi-debug-signed" } else { "$variant-$TargetAbi" }
$destinationPath = Join-Path $outputPath "ChatWithChat-$packageKind-$timestamp.apk"
try {
    Copy-InstallableApk -SourceApk $apkPath -DestinationApk $destinationPath -Variant $variant
    Test-ApkSignature $destinationPath
    Test-ApkApplicationId -ApkPath $destinationPath -ExpectedApplicationId $ExpectedApplicationId
    Test-ApkAbi -ApkPath $destinationPath -ExpectedAbi $TargetAbi
} catch {
    foreach ($partialOutput in @($destinationPath, "$destinationPath.idsig")) {
        if (Test-Path -LiteralPath $partialOutput) {
            Remove-Item -LiteralPath $partialOutput -Force
        }
    }
    throw
}

Write-Step "APK built successfully"
Write-Host "Source APK: $apkPath"
Write-Host "Copied APK: $destinationPath"
