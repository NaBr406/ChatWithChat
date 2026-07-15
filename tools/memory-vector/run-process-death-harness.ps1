param(
    [switch]$SkipBuild,
    [string]$Serial
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$Gradle = Join-Path $ProjectRoot "gradlew.bat"
$AppApk = Join-Path $ProjectRoot "app/build/outputs/apk/debug/app-debug.apk"
$TestApk = Join-Path $ProjectRoot "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
$AppPackage = "cn.nabr.chatwithchat"
$InstrumentationComponent = "cn.nabr.chatwithchat.test/androidx.test.runner.AndroidJUnitRunner"
$TestClass = "cn.nabr.chatwithchat.data.memory.MemoryMutationProcessDeathInstrumentedTest"

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

$SdkRoot = if ($env:ANDROID_SDK_ROOT) {
    $env:ANDROID_SDK_ROOT
} elseif ($env:ANDROID_HOME) {
    $env:ANDROID_HOME
} else {
    Join-Path $env:LOCALAPPDATA "Android/Sdk"
}
$Adb = Join-Path $SdkRoot "platform-tools/adb.exe"
if (-not (Test-Path -LiteralPath $Adb)) {
    throw "adb not found: $Adb"
}

$ConnectedDevices = @(
    & $Adb devices |
        Select-Object -Skip 1 |
        ForEach-Object {
            if ($_ -match '^([^\s]+)\s+device$') {
                $Matches[1]
            }
        }
)
if ($Serial) {
    if ($Serial -notin $ConnectedDevices) {
        throw "Requested device is not connected: $Serial"
    }
    $DeviceSerial = $Serial
} elseif ($ConnectedDevices.Count -eq 1) {
    $DeviceSerial = $ConnectedDevices[0]
} else {
    throw "Expected exactly one connected device or pass -Serial. Connected: $($ConnectedDevices -join ', ')"
}
$AdbTarget = @("-s", $DeviceSerial)

Push-Location $ProjectRoot
try {
    if (-not $SkipBuild) {
        Invoke-Checked "Build debug app and instrumentation APKs" {
            & $Gradle :app:assembleDebug :app:assembleDebugAndroidTest
        }
    }
    if (-not (Test-Path -LiteralPath $AppApk)) {
        throw "Debug app APK not found: $AppApk"
    }
    if (-not (Test-Path -LiteralPath $TestApk)) {
        throw "Debug instrumentation APK not found: $TestApk"
    }

    Invoke-Checked "Install debug app" { & $Adb @AdbTarget install -r -d -t $AppApk }
    Invoke-Checked "Install instrumentation APK" { & $Adb @AdbTarget install -r -d -t $TestApk }
    Invoke-Checked "Stop target app before phase one" { & $Adb @AdbTarget shell am force-stop $AppPackage }

    Write-Host "`n== Phase one: persist both crash windows and kill the process =="
    $PhaseOne = Invoke-AdbCaptured -CommandArguments @(
        "shell",
        "am",
        "instrument",
        "-w",
        "-r",
        "-e",
        "class",
        "$TestClass#phase1_prepareBothCrashWindowsAndKillProcess",
        $InstrumentationComponent
    )
    $PhaseOne.Output | ForEach-Object { Write-Host $_ }
    $ExpectedAbortPattern = 'INSTRUMENTATION_ABORTED|Process crashed|process crashed|Instrumentation run failed'
    if ($PhaseOne.Text -notmatch $ExpectedAbortPattern) {
        throw "Phase one did not abort at the process-death failpoint (exit code $($PhaseOne.ExitCode))"
    }

    Invoke-Checked "Force-stop target app without clearing persisted harness state" {
        & $Adb @AdbTarget shell am force-stop $AppPackage
    }

    Write-Host "`n== Phase two: restart and verify receipt recovery =="
    $PhaseTwo = Invoke-AdbCaptured -CommandArguments @(
        "shell",
        "am",
        "instrument",
        "-w",
        "-r",
        "-e",
        "class",
        "$TestClass#phase2_recoverBothCrashWindowsWithoutSemanticReplay",
        $InstrumentationComponent
    )
    $PhaseTwo.Output | ForEach-Object { Write-Host $_ }
    if ($PhaseTwo.ExitCode -ne 0 -or $PhaseTwo.Text -notmatch 'OK \(1 test\)') {
        throw "Phase two recovery assertion failed with exit code $($PhaseTwo.ExitCode)"
    }

    Write-Host "`nProcess-death harness passed on $DeviceSerial."
    Write-Host "Verified: PREPARED staged commit, rename-before-Room-update recognition, and idempotent index scheduling."
} finally {
    Pop-Location
}
