param(
    [string]$AvdName,
    [string]$DeviceSerial,
    [string]$JavaHome,
    [string]$PackageName = "dev.chungjungsoo.gptmobile",
    [string]$ActivityName = ".presentation.ui.main.MainActivity",
    [int]$BootTimeoutSeconds = 480,
    [switch]$NoBuild,
    [switch]$ClearData,
    [switch]$Headless
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

function Resolve-JavaHomePath {
    param([string]$RequestedJavaHome)

    $candidates = New-Object System.Collections.Generic.List[string]
    foreach ($candidate in @(
        $RequestedJavaHome,
        $env:JAVA_HOME,
        (Join-Path $env:ProgramFiles "Android\Android Studio\jbr"),
        (Join-Path $env:ProgramFiles "Java\jdk-17"),
        (Join-Path $env:ProgramFiles "Java\jdk-21"),
        (Join-Path $env:ProgramFiles "Java\jdk-24")
    )) {
        if (-not [string]::IsNullOrWhiteSpace($candidate)) { $candidates.Add($candidate) }
    }

    $javaRoot = Join-Path $env:ProgramFiles "Java"
    if (Test-Path -LiteralPath $javaRoot) {
        Get-ChildItem -LiteralPath $javaRoot -Directory -Filter "jdk*" -ErrorAction SilentlyContinue |
            Sort-Object Name |
            ForEach-Object { $candidates.Add($_.FullName) }
    }

    foreach ($candidate in $candidates) {
        if (Test-JavaHome $candidate) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    throw "No valid JDK found. Pass -JavaHome or fix JAVA_HOME."
}

function Resolve-AndroidSdkPath {
    foreach ($candidate in @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        (Join-Path $env:LOCALAPPDATA "Android\Sdk")
    )) {
        if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
        $adb = Join-Path $candidate "platform-tools\adb.exe"
        $emulator = Join-Path $candidate "emulator\emulator.exe"
        if ((Test-Path -LiteralPath $adb) -and (Test-Path -LiteralPath $emulator)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    throw "Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT."
}

function Get-ConnectedDeviceSerial {
    param([string]$AdbPath)

    $deviceLines = & $AdbPath devices | Where-Object { $_ -match "^(?<serial>\S+)\s+device(\s|$)" }
    if (-not $deviceLines) { return $null }

    if (-not [string]::IsNullOrWhiteSpace($DeviceSerial)) {
        foreach ($line in $deviceLines) {
            if ($line -match "^$([regex]::Escape($DeviceSerial))\s+device(\s|$)") {
                return $DeviceSerial
            }
        }
        throw "Requested device '$DeviceSerial' is not connected."
    }

    $firstLine = @($deviceLines)[0]
    if ($firstLine -match "^(?<serial>\S+)\s+device(\s|$)") {
        return $Matches.serial
    }

    return $null
}

function Get-FirstAvdName {
    param([string]$EmulatorPath)

    $avds = & $EmulatorPath -list-avds | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    if (-not $avds) { throw "No Android Virtual Devices found. Create an AVD in Android Studio first." }
    return @($avds)[0]
}

function Ensure-DeviceBooted {
    param(
        [string]$AdbPath,
        [string]$EmulatorPath
    )

    & $AdbPath start-server | Out-Null
    $serial = Get-ConnectedDeviceSerial $AdbPath

    if (-not $serial) {
        if ([string]::IsNullOrWhiteSpace($AvdName)) {
            $script:AvdName = Get-FirstAvdName $EmulatorPath
        }

        $arguments = @("-avd", $script:AvdName, "-no-snapshot")
        if ($Headless) { $arguments += "-no-window" }

        Write-Step "Starting emulator '$script:AvdName'"
        Start-Process -FilePath $EmulatorPath -ArgumentList $arguments | Out-Null

        $deviceDeadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
        do {
            Start-Sleep -Seconds 5
            $serial = Get-ConnectedDeviceSerial $AdbPath
            if ((Get-Date) -gt $deviceDeadline) {
                throw "Timed out waiting for emulator device registration."
            }
        } until ($serial)
    } else {
        Write-Step "Using connected device '$serial'"
    }

    & $AdbPath -s $serial wait-for-device


    $bootDeadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
    do {
        $bootCompleted = (& $AdbPath -s $serial shell getprop sys.boot_completed 2>$null).Trim()
        $bootAnim = (& $AdbPath -s $serial shell getprop init.svc.bootanim 2>$null).Trim()
        if ($bootCompleted -eq "1" -and ($bootAnim -eq "stopped" -or [string]::IsNullOrWhiteSpace($bootAnim))) {
            break
        }

        Write-Step "Waiting for Android boot: boot_completed=$bootCompleted bootanim=$bootAnim"
        Start-Sleep -Seconds 5
        if ((Get-Date) -gt $bootDeadline) {
            throw "Timed out waiting for Android boot completion."
        }
    } while ($true)

    & $AdbPath -s $serial shell input keyevent 82 | Out-Null
    return $serial
}

function Invoke-GradleBuild {
    $gradlew = Join-Path $ProjectRoot "gradlew.bat"
    if (-not (Test-Path -LiteralPath $gradlew)) { throw "gradlew.bat not found in project root." }

    Write-Step "Building debug APK"
    & $gradlew :app:assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE." }
}

function Get-DebugApkPath {
    $apk = Join-Path $ProjectRoot "app\build\outputs\apk\debug\app-debug.apk"
    if (Test-Path -LiteralPath $apk) { return (Resolve-Path -LiteralPath $apk).Path }

    throw "Debug APK not found. Run without -NoBuild first."
}

function Get-FirstOutputLine {
    param([object]$Output)

    $lines = @($Output) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    if (-not $lines) { return "" }

    return "$($lines[0])"
}

function Get-LaunchDiagnosticLines {
    param(
        [string]$AdbPath,
        [string]$Serial,
        [string]$PackageName
    )

    $logLines = @(& $AdbPath -s $Serial logcat -d -v time)
    $pattern = "FATAL EXCEPTION|ANR in $([regex]::Escape($PackageName))|Process $([regex]::Escape($PackageName)) has died|$([regex]::Escape($PackageName)).*(Exception|Error)"
    $fatalIndexes = New-Object System.Collections.Generic.List[int]

    for ($i = 0; $i -lt $logLines.Count; $i++) {
        if ($logLines[$i] -match $pattern) {
            $fatalIndexes.Add($i)
        }
    }

    if ($fatalIndexes.Count -eq 0) {
        return @()
    }

    $start = $fatalIndexes[$fatalIndexes.Count - 1]
    $end = [Math]::Min($start + 80, $logLines.Count - 1)
    return $logLines[$start..$end]
}

function Install-And-Launch {
    param(
        [string]$AdbPath,
        [string]$Serial,
        [string]$ApkPath
    )

    Write-Step "Installing APK on '$Serial'"
    & $AdbPath -s $Serial install -r $ApkPath
    if ($LASTEXITCODE -ne 0) { throw "adb install failed with exit code $LASTEXITCODE." }

    if ($ClearData) {
        Write-Step "Clearing app data for $PackageName"
        & $AdbPath -s $Serial shell pm clear $PackageName | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "pm clear failed with exit code $LASTEXITCODE." }
    }

    Write-Step "Launching $PackageName/$ActivityName"
    & $AdbPath -s $Serial logcat -c
    & $AdbPath -s $Serial shell am force-stop $PackageName
    & $AdbPath -s $Serial shell am start -n "$PackageName/$ActivityName"
    if ($LASTEXITCODE -ne 0) { throw "am start failed with exit code $LASTEXITCODE." }

    Start-Sleep -Seconds 8
    $appPid = ((& $AdbPath -s $Serial shell pidof $PackageName 2>$null) -join "").Trim()
    $focusMatch = & $AdbPath -s $Serial shell dumpsys window |
        Select-String -Pattern "mCurrentFocus" |
        Select-Object -First 1
    $focus = if ($focusMatch) { $focusMatch.Line.Trim() } else { "" }
    Write-Host "PID: $appPid"
    Write-Host "Focus: $focus"

    $crashes = @(Get-LaunchDiagnosticLines -AdbPath $AdbPath -Serial $Serial -PackageName $PackageName)

    if ([string]::IsNullOrWhiteSpace($appPid)) {
        Write-Warning "App process was not found after launch."
        if ($crashes) {
            $crashes | ForEach-Object { Write-Warning $_ }
        } else {
            Write-Warning "No obvious crash lines found in logcat."
        }
        exit 2
    }

    if ($crashes) {
        Write-Warning "Potential crash/error logs detected after launch:"
        $crashes | ForEach-Object { Write-Warning $_ }
        exit 2
    }

    Write-Step "Launch check passed"
}

$sdk = Resolve-AndroidSdkPath
$adbPath = Join-Path $sdk "platform-tools\adb.exe"
$emulatorPath = Join-Path $sdk "emulator\emulator.exe"
$resolvedJavaHome = Resolve-JavaHomePath $JavaHome
$env:JAVA_HOME = $resolvedJavaHome

Write-Step "Project: $ProjectRoot"
Write-Step "Android SDK: $sdk"
Write-Step "JAVA_HOME: $resolvedJavaHome"

if (-not $NoBuild) {
    Invoke-GradleBuild
}

$serial = Ensure-DeviceBooted -AdbPath $adbPath -EmulatorPath $emulatorPath
$apkPath = Get-DebugApkPath
Install-And-Launch -AdbPath $adbPath -Serial $serial -ApkPath $apkPath
