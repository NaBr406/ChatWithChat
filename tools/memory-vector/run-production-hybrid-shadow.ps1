param(
    [string]$Serial,
    [switch]$BuildOnly,
    [switch]$SkipProvision
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$Gradle = Join-Path $ProjectRoot "gradlew.bat"
$ProvisionModel = Join-Path $ProjectRoot "tools/memory-model/provision-bge-small-zh-v1.5-production.ps1"
$UnsignedReleaseApk = Join-Path $ProjectRoot "app/build/outputs/apk/release/app-release-unsigned.apk"
$ReleaseTestApk = Join-Path $ProjectRoot "app/build/outputs/apk/androidTest/release/app-release-androidTest.apk"
$EvidenceDirectory = Join-Path $ProjectRoot "app/build/outputs/apk/memory-shadow"
$AlignedReleaseApk = Join-Path $EvidenceDirectory "app-release-memory-shadow-aligned.apk"
$AlignedReleaseTestApk = Join-Path $EvidenceDirectory "app-release-androidTest-memory-shadow-aligned.apk"
$SignedReleaseApk = Join-Path $EvidenceDirectory "app-release-memory-shadow-signed.apk"
$SignedReleaseTestApk = Join-Path $EvidenceDirectory "app-release-androidTest-memory-shadow-signed.apk"
$AppPackage = "dev.chungjungsoo.gptmobile"
$TestPackage = "dev.chungjungsoo.gptmobile.test"
$TestClass = "dev.chungjungsoo.gptmobile.data.memory.MemoryProductionHybridShadowInstrumentedTest"
$InstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
$InstrumentationComponent = "$TestPackage/$InstrumentationRunner"
$SuccessCheckpoint = "MEMORY_HYBRID_SHADOW_OK"
$ExpectedPageSize = "16384"

function Invoke-Checked {
    param(
        [string]$Label,
        [scriptblock]$Command
    )

    Write-Host "`n== $Label =="
    $global:LASTEXITCODE = 0
    & $Command
    $ExitCode = $global:LASTEXITCODE
    if ($ExitCode -ne 0) {
        throw "$Label failed with exit code $ExitCode"
    }
}

function Install-ApkWithRetry {
    param(
        [string]$Label,
        [string]$Apk
    )

    Write-Host "`n== $Label =="
    for ($Attempt = 1; $Attempt -le 3; $Attempt++) {
        & $Adb @AdbTarget install --no-incremental -r -d -t $Apk
        if ($LASTEXITCODE -eq 0) {
            return
        }
        if ($Attempt -lt 3) {
            & $Adb @AdbTarget wait-for-device
            Start-Sleep -Seconds 1
        }
    }
    throw "$Label failed after 3 attempts"
}

function Get-LatestBuildToolsDirectory {
    param([string]$SdkRoot)

    $Directory = Get-ChildItem -LiteralPath (Join-Path $SdkRoot "build-tools") -Directory |
        Where-Object {
            (Test-Path -LiteralPath (Join-Path $_.FullName "apksigner.bat")) -and
                (Test-Path -LiteralPath (Join-Path $_.FullName "zipalign.exe"))
        } |
        Sort-Object { [version]$_.Name } -Descending |
        Select-Object -First 1
    if ($null -eq $Directory) {
        throw "No Android build-tools directory contains apksigner and zipalign"
    }
    return $Directory.FullName
}

function Sign-ReleaseApk {
    param(
        [string]$Label,
        [string]$InputApk,
        [string]$AlignedApk,
        [string]$SignedApk
    )

    Invoke-Checked "Align $Label for 16 KB pages" {
        & $ZipAlign -P 16 -f 4 $InputApk $AlignedApk
    }
    Invoke-Checked "Sign $Label with the Android debug key" {
        & $ApkSigner sign `
            --ks $DebugKeyStore `
            --ks-key-alias androiddebugkey `
            --ks-pass pass:android `
            --key-pass pass:android `
            --out $SignedApk `
            $AlignedApk
    }
    Invoke-Checked "Verify $Label signature" {
        & $ApkSigner verify --verbose $SignedApk
    }
    Invoke-Checked "Verify signed $Label ZIP alignment" {
        & $ZipAlign -c -P 16 4 $SignedApk
    }
}

$SdkRoot = if ($env:ANDROID_SDK_ROOT) {
    $env:ANDROID_SDK_ROOT
} elseif ($env:ANDROID_HOME) {
    $env:ANDROID_HOME
} else {
    Join-Path $env:LOCALAPPDATA "Android/Sdk"
}
$Adb = Join-Path $SdkRoot "platform-tools/adb.exe"
$BuildTools = Get-LatestBuildToolsDirectory -SdkRoot $SdkRoot
$ApkSigner = Join-Path $BuildTools "apksigner.bat"
$ZipAlign = Join-Path $BuildTools "zipalign.exe"
$DebugKeyStore = Join-Path $env:USERPROFILE ".android/debug.keystore"
if (-not (Test-Path -LiteralPath $Adb)) {
    throw "adb not found: $Adb"
}
if (-not (Test-Path -LiteralPath $DebugKeyStore)) {
    throw "Android debug keystore not found: $DebugKeyStore"
}

Push-Location $ProjectRoot
try {
    if (-not $SkipProvision) {
        Invoke-Checked "Provision checksum-verified production embedding artifacts" {
            & $ProvisionModel
        }
    }
    Invoke-Checked "Compile release production Hybrid shadow instrumentation" {
        & $Gradle `
            "-PmemoryTestBuildType=release" `
            "-PmemoryTestInstrumentationRunner=$InstrumentationRunner" `
            :app:compileReleaseAndroidTestKotlin
    }
    Invoke-Checked "Assemble release target and instrumentation APKs" {
        & $Gradle `
            "-PmemoryTestBuildType=release" `
            "-PmemoryTestInstrumentationRunner=$InstrumentationRunner" `
            :app:assembleRelease `
            :app:assembleReleaseAndroidTest
    }

    if ($BuildOnly) {
        Write-Host "`nRelease production Hybrid shadow compiled successfully; device execution was skipped."
        return
    }

    $ConnectedDevices = @(
        & $Adb devices |
            Select-Object -Skip 1 |
            ForEach-Object {
                if ($_ -match '^([^\s]+)\s+device(?:\s|$)') {
                    $Matches[1]
                }
            }
    )
    if ([string]::IsNullOrWhiteSpace($Serial)) {
        if ($ConnectedDevices.Count -eq 0) {
            Write-Host "`nRelease production Hybrid shadow compiled successfully; no connected device was available."
            return
        }
        if ($ConnectedDevices.Count -ne 1) {
            throw "Multiple devices are connected; pass -Serial explicitly"
        }
        $Serial = $ConnectedDevices[0]
    }
    if ($Serial -notin $ConnectedDevices) {
        throw "Requested Android target is not connected: $Serial"
    }
    $AdbTarget = @("-s", $Serial)

    $PageSize = (& $Adb @AdbTarget shell getconf PAGE_SIZE).Trim()
    $ApiLevel = (& $Adb @AdbTarget shell getprop ro.build.version.sdk).Trim()
    $Abi = (& $Adb @AdbTarget shell getprop ro.product.cpu.abi).Trim()
    if ($PageSize -ne $ExpectedPageSize) {
        throw "Production Hybrid shadow requires PAGE_SIZE=16384; observed $PageSize"
    }
    if ([int]$ApiLevel -lt 35) {
        throw "Production Hybrid shadow requires Android 15/API 35 or newer; observed API $ApiLevel"
    }

    if (-not (Test-Path -LiteralPath $UnsignedReleaseApk)) {
        throw "Unsigned release APK not found: $UnsignedReleaseApk"
    }
    if (-not (Test-Path -LiteralPath $ReleaseTestApk)) {
        throw "Release instrumentation APK not found: $ReleaseTestApk"
    }
    New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null
    Remove-Item `
        -LiteralPath $AlignedReleaseApk,$AlignedReleaseTestApk,$SignedReleaseApk,$SignedReleaseTestApk `
        -Force `
        -ErrorAction SilentlyContinue
    Sign-ReleaseApk `
        -Label "release target APK" `
        -InputApk $UnsignedReleaseApk `
        -AlignedApk $AlignedReleaseApk `
        -SignedApk $SignedReleaseApk
    Sign-ReleaseApk `
        -Label "release instrumentation APK" `
        -InputApk $ReleaseTestApk `
        -AlignedApk $AlignedReleaseTestApk `
        -SignedApk $SignedReleaseTestApk

    & $Adb @AdbTarget uninstall $TestPackage 2>&1 | Out-Host
    & $Adb @AdbTarget uninstall $AppPackage 2>&1 | Out-Host
    Install-ApkWithRetry -Label "Install signed release target APK" -Apk $SignedReleaseApk
    Install-ApkWithRetry -Label "Install signed release instrumentation APK" -Apk $SignedReleaseTestApk
    Invoke-Checked "Clear shadow evidence log" { & $Adb @AdbTarget logcat -c }

    Write-Host "`n== Run release production Hybrid shadow gate =="
    $InstrumentationOutput = @(
        & $Adb @AdbTarget shell am instrument -w -r -e class $TestClass $InstrumentationComponent 2>&1
    )
    $InstrumentationExitCode = $LASTEXITCODE
    $InstrumentationText = $InstrumentationOutput -join "`n"
    $InstrumentationOutput | ForEach-Object { Write-Host $_ }
    if (
        $InstrumentationExitCode -ne 0 -or
        $InstrumentationText -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' -or
        $InstrumentationText -notmatch 'OK \('
    ) {
        throw "Release production Hybrid shadow instrumentation failed with exit code $InstrumentationExitCode"
    }

    Write-Host "`n== Production Hybrid shadow metadata evidence =="
    $Evidence = @(& $Adb @AdbTarget logcat -d -v brief -s "MemoryHybridShadow:I" "*:S" 2>&1)
    $EvidenceExitCode = $LASTEXITCODE
    $EvidenceText = $Evidence -join "`n"
    $Evidence | ForEach-Object { Write-Host $_ }
    if ($EvidenceExitCode -ne 0 -or $EvidenceText -notmatch [regex]::Escape($SuccessCheckpoint)) {
        throw "Production Hybrid shadow success checkpoint was not recorded"
    }

    $ReleaseHash = (Get-FileHash -LiteralPath $SignedReleaseApk -Algorithm SHA256).Hash.ToLowerInvariant()
    $TestHash = (Get-FileHash -LiteralPath $SignedReleaseTestApk -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Host "`nRelease production Hybrid shadow passed on $Serial."
    Write-Host "PAGE_SIZE=$PageSize API=$ApiLevel ABI=$Abi"
    Write-Host "TARGET_APK_SHA256=$ReleaseHash"
    Write-Host "TEST_APK_SHA256=$TestHash"
} finally {
    Pop-Location
}
