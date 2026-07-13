param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$Gradle = Join-Path $ProjectRoot "gradlew.bat"
$ProvisionModel = Join-Path $ProjectRoot "tools/memory-model/provision-bge-small-zh-v1.5-production.ps1"
$UnsignedReleaseApk = Join-Path $ProjectRoot "app/build/outputs/apk/release/app-release-unsigned.apk"
$ReleaseTestApk = Join-Path $ProjectRoot "app/build/outputs/apk/androidTest/release/app-release-androidTest.apk"
$EvidenceDirectory = Join-Path $ProjectRoot "app/build/outputs/apk/memory16k"
$SignedReleaseApk = Join-Path $EvidenceDirectory "app-release-memory16k-test-signed.apk"
$SignedReleaseTestApk = Join-Path $EvidenceDirectory "app-release-androidTest-memory16k-signed.apk"
$AppPackage = "dev.chungjungsoo.gptmobile"
$TestPackage = "dev.chungjungsoo.gptmobile.test"
$InstrumentationRunner = "dev.chungjungsoo.gptmobile.data.memory.vector.Memory16KbReleaseCompatibilityInstrumentedTest"
$InstrumentationComponent = "$TestPackage/$InstrumentationRunner"
$ExpectedPageSize = "16384"
$PhaseOneCheckpoint = "MEMORY_16KB_PHASE1_READY"
$PhaseTwoCheckpoint = "MEMORY_16KB_PHASE2_OK"
$ObjectBoxLoadPattern = 'Load .*/dev\.chungjungsoo\.gptmobile-[^/]+/base\.apk!/lib/[^/]+/libobjectbox-jni\.so'
$OnnxLoadPattern = 'Load .*/dev\.chungjungsoo\.gptmobile-[^/]+/base\.apk!/lib/[^/]+/libonnxruntime4j_jni\.so'

function Invoke-Checked {
    param(
        [string]$Label,
        [scriptblock]$Command
    )

    Write-Host "`n== $Label =="
    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Label failed with exit code $LASTEXITCODE"
    }
}

function Invoke-AdbCaptured {
    param([string[]]$CommandArguments)

    $CommandOutput = @(& $Adb @AdbTarget @CommandArguments 2>&1)
    $CommandExitCode = $LASTEXITCODE
    [pscustomobject]@{
        Output = $CommandOutput
        ExitCode = $CommandExitCode
        Text = $CommandOutput -join "`n"
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
            Write-Host "$Label attempt $Attempt failed; retrying the streamed install"
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

function Get-LatestLlvmReadElf {
    param([string]$SdkRoot)

    $NdkRoot = Join-Path $SdkRoot "ndk"
    $Candidate = Get-ChildItem -LiteralPath $NdkRoot -Directory -ErrorAction SilentlyContinue |
        ForEach-Object {
            $Executable = Join-Path $_.FullName "toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe"
            if (Test-Path -LiteralPath $Executable) {
                [pscustomobject]@{
                    Version = [version](($_.Name -split "-")[0])
                    Path = $Executable
                }
            }
        } |
        Sort-Object Version -Descending |
        Select-Object -First 1
    if ($null -eq $Candidate) {
        throw "llvm-readelf.exe not found below $NdkRoot"
    }
    return $Candidate.Path
}

function Assert-ApkElfLoadAlignment {
    param(
        [string]$Label,
        [string]$ApkPath,
        [string]$LlvmReadElf,
        [string[]]$RequiredEntries,
        [uint64]$MinimumAlignment = 0x4000
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $Archive = [System.IO.Compression.ZipFile]::OpenRead(
        (Resolve-Path -LiteralPath $ApkPath).Path
    )
    try {
        $NativeEntries = @(
            $Archive.Entries |
                Where-Object {
                    $_.FullName -match '^lib/(arm64-v8a|x86_64)/.+\.so$'
                } |
                Sort-Object FullName
        )
        foreach ($Abi in @("arm64-v8a", "x86_64")) {
            $AbiEntries = @($NativeEntries | Where-Object { $_.FullName -like "lib/$Abi/*.so" })
            if ($AbiEntries.Count -eq 0) {
                throw "$Label contains no $Abi native libraries"
            }
        }
        foreach ($RequiredEntry in $RequiredEntries) {
            if ($null -eq $Archive.GetEntry($RequiredEntry)) {
                throw "$Label is missing required native library: $RequiredEntry"
            }
        }

        foreach ($Entry in $NativeEntries) {
            $TempFile = [System.IO.Path]::GetTempFileName()
            try {
                [System.IO.Compression.ZipFileExtensions]::ExtractToFile($Entry, $TempFile, $true)
                $HeaderOutput = @(& $LlvmReadElf --program-headers --wide $TempFile 2>&1)
                if ($LASTEXITCODE -ne 0) {
                    throw "llvm-readelf failed for $($Entry.FullName) in $Label"
                }
                $LoadLines = @(
                    $HeaderOutput |
                        ForEach-Object { "$_" } |
                        Where-Object { $_ -match '^\s*LOAD\s+' }
                )
                if ($LoadLines.Count -eq 0) {
                    throw "No ELF LOAD segments found in $($Entry.FullName)"
                }

                [uint64]$SmallestAlignment = [uint64]::MaxValue
                foreach ($LoadLine in $LoadLines) {
                    if ($LoadLine -notmatch '\s(0x[0-9A-Fa-f]+)\s*$') {
                        throw "Unable to parse p_align: $LoadLine"
                    }
                    [uint64]$Alignment = [Convert]::ToUInt64($Matches[1].Substring(2), 16)
                    if ($Alignment -lt $MinimumAlignment) {
                        throw "$Label/$($Entry.FullName) has LOAD p_align=$($Matches[1]); expected at least 0x4000"
                    }
                    if ($Alignment -lt $SmallestAlignment) {
                        $SmallestAlignment = $Alignment
                    }
                }
                Write-Host (
                    "ELF OK: {0} / {1}: {2} LOAD segment(s), minimum p_align=0x{3}" -f
                        $Label,
                        $Entry.FullName,
                        $LoadLines.Count,
                        $SmallestAlignment.ToString("x")
                )
            } finally {
                Remove-Item -LiteralPath $TempFile -Force -ErrorAction SilentlyContinue
            }
        }
    } finally {
        $Archive.Dispose()
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
$LlvmReadElf = Get-LatestLlvmReadElf -SdkRoot $SdkRoot
$DebugKeyStore = Join-Path $env:USERPROFILE ".android/debug.keystore"
if (-not (Test-Path -LiteralPath $Adb)) {
    throw "adb not found: $Adb"
}
if (-not (Test-Path -LiteralPath $DebugKeyStore)) {
    throw "Android debug keystore not found: $DebugKeyStore"
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
if ($Serial -notin $ConnectedDevices) {
    throw "Requested emulator is not connected: $Serial"
}
$AdbTarget = @("-s", $Serial)
$PageSize = (& $Adb @AdbTarget shell getconf PAGE_SIZE).Trim()
$ApiLevel = (& $Adb @AdbTarget shell getprop ro.build.version.sdk).Trim()
$PrimaryAbi = (& $Adb @AdbTarget shell getprop ro.product.cpu.abi).Trim()
$AbiList = (& $Adb @AdbTarget shell getprop ro.product.cpu.abilist).Trim()
$IsEmulator = (& $Adb @AdbTarget shell getprop ro.kernel.qemu).Trim()
if ($PageSize -ne $ExpectedPageSize) {
    throw "16 KB compatibility requires adb shell getconf PAGE_SIZE to return 16384; observed $PageSize"
}
if ([int]$ApiLevel -lt 35) {
    throw "Android 15/API 35 or newer is required; observed API $ApiLevel"
}
if ($IsEmulator -ne "1") {
    throw "The selected target is not reported as an Android emulator"
}
if ($PrimaryAbi -notin @("arm64-v8a", "x86_64")) {
    throw "A 64-bit ARM64 or x86_64 emulator is required; observed $PrimaryAbi"
}

Push-Location $ProjectRoot
try {
    if (-not $SkipBuild) {
        Invoke-Checked "Provision checksum-verified production ONNX model" { & $ProvisionModel }
        Invoke-Checked "Build release app and release-under-test instrumentation APK" {
            & $Gradle "-PmemoryTestBuildType=release" :app:assembleRelease :app:assembleReleaseAndroidTest
        }
    }
    if (-not (Test-Path -LiteralPath $UnsignedReleaseApk)) {
        throw "Unsigned release APK not found: $UnsignedReleaseApk"
    }
    if (-not (Test-Path -LiteralPath $ReleaseTestApk)) {
        throw "Release instrumentation APK not found: $ReleaseTestApk"
    }

    Invoke-Checked "Verify unsigned release APK ZIP alignment" {
        & $ZipAlign -c -P 16 4 $UnsignedReleaseApk
    }
    New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null
    Remove-Item -LiteralPath $SignedReleaseApk,$SignedReleaseTestApk -Force -ErrorAction SilentlyContinue
    Invoke-Checked "Sign release-under-test APK with the Android debug key" {
        & $ApkSigner sign `
            --ks $DebugKeyStore `
            --ks-key-alias androiddebugkey `
            --ks-pass pass:android `
            --key-pass pass:android `
            --out $SignedReleaseApk `
            $UnsignedReleaseApk
    }
    Invoke-Checked "Verify release-under-test APK signature" { & $ApkSigner verify --verbose $SignedReleaseApk }
    Invoke-Checked "Verify signed release-under-test APK ZIP alignment" {
        & $ZipAlign -c -P 16 4 $SignedReleaseApk
    }
    Invoke-Checked "Verify unsigned release instrumentation APK ZIP alignment" {
        & $ZipAlign -c -P 16 4 $ReleaseTestApk
    }
    Invoke-Checked "Sign release instrumentation APK with the Android debug key" {
        & $ApkSigner sign `
            --ks $DebugKeyStore `
            --ks-key-alias androiddebugkey `
            --ks-pass pass:android `
            --key-pass pass:android `
            --out $SignedReleaseTestApk `
            $ReleaseTestApk
    }
    Invoke-Checked "Verify release instrumentation APK signature" {
        & $ApkSigner verify --verbose $SignedReleaseTestApk
    }
    Invoke-Checked "Verify signed release instrumentation APK ZIP alignment" {
        & $ZipAlign -c -P 16 4 $SignedReleaseTestApk
    }

    Write-Host "`n== Verify arm64-v8a and x86_64 ELF LOAD alignment =="
    Assert-ApkElfLoadAlignment `
        -Label "signed release target APK" `
        -ApkPath $SignedReleaseApk `
        -LlvmReadElf $LlvmReadElf `
        -RequiredEntries @(
            "lib/arm64-v8a/libobjectbox-jni.so",
            "lib/arm64-v8a/libonnxruntime.so",
            "lib/arm64-v8a/libonnxruntime4j_jni.so",
            "lib/x86_64/libobjectbox-jni.so",
            "lib/x86_64/libonnxruntime.so",
            "lib/x86_64/libonnxruntime4j_jni.so"
        )

    & $Adb @AdbTarget uninstall $TestPackage 2>&1 | Out-Host
    & $Adb @AdbTarget uninstall $AppPackage 2>&1 | Out-Host
    Install-ApkWithRetry -Label "Install signed release APK" -Apk $SignedReleaseApk
    Install-ApkWithRetry -Label "Install release instrumentation companion APK" -Apk $SignedReleaseTestApk
    Invoke-Checked "Stop release target before phase one" { & $Adb @AdbTarget shell am force-stop $AppPackage }
    Invoke-Checked "Clear logcat evidence buffer" { & $Adb @AdbTarget logcat -c }

    Write-Host "`n== Phase one: initialize, persist, reopen, and kill target process =="
    $PhaseOne = Invoke-AdbCaptured -CommandArguments @(
        "shell",
        "am",
        "instrument",
        "-w",
        "-r",
        "-e",
        "phase",
        "phase1",
        $InstrumentationComponent
    )
    $PhaseOne.Output | ForEach-Object { Write-Host $_ }
    $ExpectedAbortPattern = 'INSTRUMENTATION_ABORTED|Process crashed|process crashed|Instrumentation run failed'
    $PhaseOneLogcat = Invoke-AdbCaptured -CommandArguments @(
        "logcat",
        "-d",
        "-v",
        "brief",
        "-s",
        "Memory16KbGate:I",
        "nativeloader:D",
        "*:S"
    )
    $PhaseOneLogcat.Output | ForEach-Object { Write-Host $_ }
    $PhaseOneCheckpointPattern = [regex]::Escape($PhaseOneCheckpoint)
    if (
        $PhaseOne.Text -notmatch $ExpectedAbortPattern -or
        $PhaseOneLogcat.ExitCode -ne 0 -or
        $PhaseOneLogcat.Text -notmatch $PhaseOneCheckpointPattern -or
        $PhaseOneLogcat.Text -notmatch $ObjectBoxLoadPattern -or
        $PhaseOneLogcat.Text -notmatch $OnnxLoadPattern
    ) {
        throw "Phase one did not prove the process-death checkpoint (exit code $($PhaseOne.ExitCode))"
    }

    Invoke-Checked "Force-stop release target while preserving persisted canary state" {
        & $Adb @AdbTarget shell am force-stop $AppPackage
    }

    Write-Host "`n== Phase two: restart ONNX Runtime and reopen ObjectBox =="
    $PhaseTwo = Invoke-AdbCaptured -CommandArguments @(
        "shell",
        "am",
        "instrument",
        "-w",
        "-r",
        "-e",
        "phase",
        "phase2",
        $InstrumentationComponent
    )
    $PhaseTwo.Output | ForEach-Object { Write-Host $_ }
    $PhaseTwoCheckpointPattern = [regex]::Escape($PhaseTwoCheckpoint)
    if ($PhaseTwo.ExitCode -ne 0 -or $PhaseTwo.Text -notmatch $PhaseTwoCheckpointPattern) {
        throw "Phase two compatibility assertions failed with exit code $($PhaseTwo.ExitCode)"
    }

    Write-Host "`n== Native mapping evidence =="
    $FinalNativeLog = Invoke-AdbCaptured -CommandArguments @(
        "logcat",
        "-d",
        "-v",
        "brief",
        "-s",
        "Memory16KbGate:I",
        "nativeloader:D",
        "*:S"
    )
    $FinalNativeLog.Output | ForEach-Object { Write-Host $_ }
    $ObjectBoxLoadCount = [regex]::Matches($FinalNativeLog.Text, $ObjectBoxLoadPattern).Count
    $OnnxLoadCount = [regex]::Matches($FinalNativeLog.Text, $OnnxLoadPattern).Count
    if ($ObjectBoxLoadCount -lt 2 -or $OnnxLoadCount -lt 2) {
        throw "Native libraries were not reloaded from their owning APKs after process restart"
    }
    $ReleaseInfo = Get-Item -LiteralPath $SignedReleaseApk
    $TestInfo = Get-Item -LiteralPath $SignedReleaseTestApk
    $ReleaseHash = (Get-FileHash -LiteralPath $SignedReleaseApk -Algorithm SHA256).Hash.ToLowerInvariant()
    $TestHash = (Get-FileHash -LiteralPath $SignedReleaseTestApk -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Host "`n16 KB release compatibility passed on $Serial."
    Write-Host "PAGE_SIZE=$PageSize API=$ApiLevel ABI=$PrimaryAbi ABI_LIST=$AbiList"
    Write-Host "Installed release target: $($ReleaseInfo.Length) bytes, SHA-256 $ReleaseHash"
    Write-Host "Installed instrumentation companion: $($TestInfo.Length) bytes, SHA-256 $TestHash"
    Write-Host "ObjectBox, ONNX Runtime, and the model were all loaded from the release target APK."
} finally {
    Pop-Location
}
