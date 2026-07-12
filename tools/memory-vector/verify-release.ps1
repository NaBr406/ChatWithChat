param(
    [switch]$SkipBuild,
    [switch]$RunDeviceTests,
    [switch]$Require16KbPageSize,
    [switch]$RequireAllTargetLibraries16Kb
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$Gradle = Join-Path $ProjectRoot "gradlew.bat"
$Apk = Join-Path $ProjectRoot "app/build/outputs/apk/release/app-release-unsigned.apk"

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

function Get-LatestToolDirectory {
    param(
        [string]$Root,
        [string]$RequiredRelativePath
    )

    $Candidates = Get-ChildItem -LiteralPath $Root -Directory -ErrorAction Stop |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName $RequiredRelativePath) } |
        Sort-Object { [version]$_.Name } -Descending
    $Candidate = $Candidates | Select-Object -First 1
    if ($null -eq $Candidate) {
        throw "No Android tool directory below $Root contains $RequiredRelativePath"
    }
    return $Candidate.FullName
}

$SdkRoot = if ($env:ANDROID_SDK_ROOT) {
    $env:ANDROID_SDK_ROOT
} elseif ($env:ANDROID_HOME) {
    $env:ANDROID_HOME
} else {
    Join-Path $env:LOCALAPPDATA "Android/Sdk"
}
$BuildTools = Get-LatestToolDirectory -Root (Join-Path $SdkRoot "build-tools") -RequiredRelativePath "zipalign.exe"
$Ndk = Get-LatestToolDirectory `
    -Root (Join-Path $SdkRoot "ndk") `
    -RequiredRelativePath "toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-objdump.exe"
$ZipAlign = Join-Path $BuildTools "zipalign.exe"
$ApkAnalyzerCommand = Get-Command apkanalyzer -ErrorAction SilentlyContinue
$ApkAnalyzer = if ($null -ne $ApkAnalyzerCommand) {
    $ApkAnalyzerCommand.Source
} else {
    Join-Path $SdkRoot "cmdline-tools/latest/bin/apkanalyzer.bat"
}
if (-not (Test-Path -LiteralPath $ApkAnalyzer)) {
    throw "apkanalyzer was not found on PATH or below Android SDK cmdline-tools/latest"
}
$Adb = Join-Path $SdkRoot "platform-tools/adb.exe"
$ObjDump = Join-Path $Ndk "toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-objdump.exe"

Push-Location $ProjectRoot
try {
    if (-not $SkipBuild) {
        Invoke-Checked "Memory JVM tests" { & $Gradle :app:testDebugUnitTest --tests "*Memory*" }
        Invoke-Checked "Debug Kotlin compilation" { & $Gradle :app:compileDebugKotlin }
        Invoke-Checked "Release R8 assembly" { & $Gradle :app:assembleRelease }
    }

    if (-not (Test-Path -LiteralPath $Apk)) {
        throw "Release APK not found: $Apk"
    }

    if ($RunDeviceTests) {
        Invoke-Checked "ObjectBox device tests" {
            & $Gradle :app:connectedDebugAndroidTest `
                "-Pandroid.testInstrumentationRunnerArguments.class=dev.chungjungsoo.gptmobile.data.memory.vector.ObjectBoxMemoryVectorStoreInstrumentedTest"
        }
        Invoke-Checked "Room 14 to 15 migration device test" {
            & $Gradle :app:connectedDebugAndroidTest `
                "-Pandroid.testInstrumentationRunnerArguments.class=dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2MigrationInstrumentedTest"
        }
        Invoke-Checked "Room recovery, lease, and WorkManager device tests" {
            & $Gradle :app:connectedDebugAndroidTest `
                "-Pandroid.testInstrumentationRunnerArguments.class=dev.chungjungsoo.gptmobile.data.database.MemoryRecoveryDaoInstrumentedTest,dev.chungjungsoo.gptmobile.data.database.MemoryMaintenanceClaimInstrumentedTest,dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceWorkSchedulerInstrumentedTest"
        }
        Invoke-Checked "ONNX Runtime device canary" {
            & $Gradle :app:connectedDebugAndroidTest `
                "-Pandroid.testInstrumentationRunnerArguments.class=dev.chungjungsoo.gptmobile.data.memory.vector.OnnxRuntimeBuildCanaryInstrumentedTest"
        }
    }

    $ApkItem = Get-Item -LiteralPath $Apk
    $ApkHash = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash.ToLowerInvariant()
    Invoke-Checked "APK ZIP 16 KB alignment" { & $ZipAlign -c -P 16 4 $Apk }
    Invoke-Checked "APK native library listing" {
        $ApkFiles = & $ApkAnalyzer files list $Apk
        $ApkFiles | Where-Object { $_ -match '^/lib/' }
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $TempRoot = Join-Path ([System.IO.Path]::GetTempPath()) "chatwithchat-native-$([guid]::NewGuid())"
    New-Item -ItemType Directory -Path $TempRoot | Out-Null
    $NativeEvidence = @()
    try {
        $Archive = [System.IO.Compression.ZipFile]::OpenRead($Apk)
        try {
            $TargetEntries = $Archive.Entries | Where-Object {
                $_.FullName -match '^lib/[^/]+/.*(objectbox|onnxruntime).*\.so$'
            }
            if (@($TargetEntries).Count -eq 0) {
                throw "Release APK contains no ObjectBox or ONNX Runtime native libraries"
            }
            foreach ($Entry in $TargetEntries) {
                $Destination = Join-Path $TempRoot $Entry.FullName
                New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Destination) | Out-Null
                [System.IO.Compression.ZipFileExtensions]::ExtractToFile($Entry, $Destination, $true)
                $Headers = & $ObjDump -p $Destination
                if ($LASTEXITCODE -ne 0) {
                    throw "llvm-objdump failed for $($Entry.FullName)"
                }
                $Alignments = @(
                    $Headers | ForEach-Object {
                        if ($_ -match '^\s*LOAD\s+.*align 2\*\*(\d+)') {
                            [int]$Matches[1]
                        }
                    }
                )
                if ($Alignments.Count -eq 0) {
                    throw "No ELF LOAD alignment evidence found for $($Entry.FullName)"
                }
                $MinimumAlignmentExponent = ($Alignments | Measure-Object -Minimum).Minimum
                $PathParts = $Entry.FullName.Split('/')
                $NativeEvidence += [pscustomobject]@{
                    abi = $PathParts[1]
                    library = $PathParts[-1]
                    loadAlignmentExponents = $Alignments
                    minimumAlignmentBytes = [math]::Pow(2, $MinimumAlignmentExponent)
                    is16KbAligned = $MinimumAlignmentExponent -ge 14
                }
            }
        } finally {
            $Archive.Dispose()
        }
    } finally {
        Remove-Item -LiteralPath $TempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }

    $DeviceLines = & $Adb devices
    if ($LASTEXITCODE -ne 0) {
        throw "adb devices failed"
    }
    $ConnectedDevices = @($DeviceLines | Where-Object { $_ -match '^\S+\s+device(?:\s|$)' })
    $AbiList = $null
    $PageSize = $null
    if ($ConnectedDevices.Count -gt 0) {
        $AbiList = (& $Adb shell getprop ro.product.cpu.abilist).Trim()
        $PageSize = (& $Adb shell getconf PAGE_SIZE).Trim()
    }

    $Evidence = [pscustomobject]@{
        apk = $ApkItem.FullName
        apkBytes = $ApkItem.Length
        apkSha256 = $ApkHash
        buildTools = Split-Path -Leaf $BuildTools
        ndk = Split-Path -Leaf $Ndk
        zipAlignment16Kb = "passed"
        nativeLibraries = $NativeEvidence
        connectedDeviceCount = $ConnectedDevices.Count
        deviceAbiList = $AbiList
        devicePageSize = $PageSize
        pageSize16KbObserved = $PageSize -eq "16384"
    }
    Write-Host "`n== Verification evidence =="
    $Evidence | ConvertTo-Json -Depth 6

    if ($RequireAllTargetLibraries16Kb -and @($NativeEvidence | Where-Object { -not $_.is16KbAligned }).Count -gt 0) {
        throw "At least one packaged ObjectBox or ONNX Runtime ELF is not 16 KB aligned"
    }
    if ($Require16KbPageSize -and $PageSize -ne "16384") {
        throw "A connected device with PAGE_SIZE=16384 is required"
    }
} finally {
    Pop-Location
}
