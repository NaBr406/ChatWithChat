param(
    [string]$AvdName,
    [string]$DeviceSerial,
    [string]$JavaHome,
    [string]$PackageName = "cn.nabr.chatwithchat",
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
    if (-not [string]::IsNullOrWhiteSpace($DeviceSerial)) {
        foreach ($line in $deviceLines) {
            if ($line -match "^$([regex]::Escape($DeviceSerial))\s+device(\s|$)") {
                return $DeviceSerial
            }
        }
        return $null
    }

    foreach ($line in $deviceLines) {
        if ($line -match "^(?<serial>emulator-\d+)\s+device(\s|$)") {
            return $Matches.serial
        }
    }

    return $null
}

function Get-PreferredAvdName {
    param([string]$EmulatorPath)

    $avds = & $EmulatorPath -list-avds | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    if (-not $avds) { throw "No Android Virtual Devices found. Create an AVD in Android Studio first." }

    $rankedAvds = foreach ($avd in $avds) {
        $configPath = Join-Path $env:USERPROFILE ".android\avd\$avd.avd\config.ini"
        $abi = if (Test-Path -LiteralPath $configPath) {
            $abiLine = Get-Content -LiteralPath $configPath -ErrorAction SilentlyContinue |
                Where-Object { $_ -match '^abi\.type=' } |
                Select-Object -First 1
            if ($abiLine) { ($abiLine -split '=', 2)[1].Trim() } else { "" }
        } else {
            ""
        }

        $score = 100
        if ($abi -match 'x86_64') { $score -= 50 }
        elseif ($abi -match '^x86$') { $score -= 40 }
        elseif ($abi -match 'arm64') { $score += 20 }
        if ($avd -match 'ChatWithChat') { $score -= 10 }

        [PSCustomObject]@{
            Name = "$avd"
            Score = $score
        }
    }

    return ($rankedAvds | Sort-Object Score, Name | Select-Object -First 1).Name
}

function Get-EmulatorPort {
    param([string]$Serial)

    if ($Serial -match '^emulator-(?<port>\d+)$') {
        return [int]$Matches.port
    }

    return $null
}

function Get-RunningAvdName {
    param(
        [string]$AdbPath,
        [string]$Serial
    )

    $output = @(
        & $AdbPath -s $Serial emu avd name 2>$null |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and $_ -ne "OK" }
    )
    if (-not $output) { return $null }
    return "$($output[0])".Trim()
}

function Test-IsHeadlessEmulator {
    param([string]$Serial)

    $port = Get-EmulatorPort $Serial
    if ($null -eq $port) { return $false }

    $portPattern = "(?:^|\s)-port\s+$port(?:\s|$)"
    $process = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object {
            $_.Name -match '^(emulator|qemu-system.*)\.exe$' -and
            $_.CommandLine -match $portPattern -and
            $_.CommandLine -match '(?:^|\s)-no-window(?:\s|$)'
        } |
        Select-Object -First 1

    return $null -ne $process
}

function Wait-ForRunningEmulatorRegistration {
    param([string]$AdbPath)

    $emulatorProcess = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^(emulator|qemu-system.*)\.exe$' } |
        Select-Object -First 1
    if (-not $emulatorProcess) { return $null }

    $deadline = (Get-Date).AddSeconds(10)
    do {
        Start-Sleep -Milliseconds 500
        $serial = Get-ConnectedDeviceSerial $AdbPath
        if ($serial) { return $serial }
    } while ((Get-Date) -lt $deadline)

    return $null
}

function Stop-Emulator {
    param(
        [string]$AdbPath,
        [string]$Serial
    )

    & $AdbPath -s $Serial emu kill | Out-Null
    $deadline = (Get-Date).AddSeconds(30)
    do {
        Start-Sleep -Seconds 1
        $isConnected = & $AdbPath devices | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+" }
        if (-not $isConnected) { return }
    } while ((Get-Date) -lt $deadline)

    throw "Timed out stopping headless emulator '$Serial'."
}

function Show-EmulatorWindow {
    param(
        [string]$AvdName,
        [int]$TimeoutSeconds = 30
    )

    if ([string]::IsNullOrWhiteSpace($AvdName)) { return $false }

    $escapedAvdName = [regex]::Escape($AvdName)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $windowProcess = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
            Where-Object {
                $_.Name -match '^(emulator|qemu-system.*)\.exe$' -and
                $_.CommandLine -match "(?:^|\s)-avd\s+$escapedAvdName(?:\s|$)"
            } |
            ForEach-Object { Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue } |
            Where-Object { $_.MainWindowHandle -ne 0 } |
            Select-Object -First 1

        if ($windowProcess) {
            $shell = New-Object -ComObject WScript.Shell
            return $shell.AppActivate($windowProcess.Id)
        }

        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    return $false
}

function Ensure-DeviceBooted {
    param(
        [string]$AdbPath,
        [string]$EmulatorPath
    )

    & $AdbPath start-server | Out-Null
    $serial = Get-ConnectedDeviceSerial $AdbPath
    if (-not $serial) {
        $serial = Wait-ForRunningEmulatorRegistration $AdbPath
    }
    $runningAvdName = $null
    $emulatorPort = $null

    if ($serial -and $serial -match '^emulator-') {
        $runningAvdName = Get-RunningAvdName -AdbPath $AdbPath -Serial $serial
        if (-not $Headless -and (Test-IsHeadlessEmulator $serial)) {
            $emulatorPort = Get-EmulatorPort $serial
            Write-Step "Restarting headless emulator '$serial' with a visible window"
            Stop-Emulator -AdbPath $AdbPath -Serial $serial
            $script:AvdName = $runningAvdName
            $serial = $null
        }
    }

    if (-not $serial -and -not [string]::IsNullOrWhiteSpace($DeviceSerial)) {
        if ($DeviceSerial -notmatch '^emulator-\d+$') {
            throw "Requested device '$DeviceSerial' is not connected."
        }
        $emulatorPort = Get-EmulatorPort $DeviceSerial
    }

    if (-not $serial) {
        if ([string]::IsNullOrWhiteSpace($AvdName)) {
            $script:AvdName = Get-PreferredAvdName $EmulatorPath
        }

        $arguments = @("-avd", $script:AvdName, "-no-snapshot")
        if ($null -ne $emulatorPort) { $arguments += @("-port", "$emulatorPort") }
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

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        & $AdbPath -s $serial shell cmd package wait-for-handler --timeout 60000 2>$null | Out-Null
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    & $AdbPath -s $serial shell input keyevent 82 | Out-Null

    if (-not $Headless -and $serial -match '^emulator-') {
        if ([string]::IsNullOrWhiteSpace($runningAvdName)) {
            $runningAvdName = Get-RunningAvdName -AdbPath $AdbPath -Serial $serial
        }
        if (-not (Show-EmulatorWindow -AvdName $runningAvdName)) {
            throw "Emulator '$serial' booted, but its visible window could not be found. Use -Headless only when a window is not required."
        }
    }

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
    $appPid = ""
    $resumedActivity = $null
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        & $AdbPath -s $Serial shell am start -W -n "$PackageName/$ActivityName"
        if ($LASTEXITCODE -ne 0) { throw "am start failed with exit code $LASTEXITCODE." }

        Start-Sleep -Seconds 3
        $appPid = ((& $AdbPath -s $Serial shell pidof $PackageName 2>$null) -join "").Trim()
        $resumedActivity = & $AdbPath -s $Serial shell dumpsys activity activities |
            Select-String -Pattern "topResumedActivity=.*$([regex]::Escape($PackageName))/" |
            Select-Object -First 1
        if (-not [string]::IsNullOrWhiteSpace($appPid) -and $resumedActivity) {
            break
        }

        if ($attempt -lt 3) {
            Write-Step "App is not foreground yet; retrying launch ($attempt/3)"
        }
    }

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

    if (-not $resumedActivity) {
        Write-Warning "App process is running, but its activity is not resumed in the foreground."
        if ($crashes) {
            $crashes | ForEach-Object { Write-Warning $_ }
        }
        exit 2
    }

    if ($crashes) {
        Write-Warning "Potential crash/error logs detected after launch:"
        $crashes | ForEach-Object { Write-Warning $_ }
        exit 2
    }

    if (-not $Headless -and $Serial -match '^emulator-') {
        $runningAvdName = Get-RunningAvdName -AdbPath $AdbPath -Serial $Serial
        if (-not (Show-EmulatorWindow -AvdName $runningAvdName)) {
            throw "App launched, but the emulator window could not be brought to the foreground."
        }
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
