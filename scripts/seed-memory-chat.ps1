param(
    [string]$DeviceSerial,
    [string]$User = "Please remember that I prefer direct, natural replies and I dislike preachy answers.",
    [string]$Assistant = "Got it. I will keep replies direct, natural, and not preachy.",
    [string]$PackageName = "dev.chungjungsoo.gptmobile"
)

$ErrorActionPreference = "Stop"

function Resolve-AndroidSdkPath {
    foreach ($candidate in @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        (Join-Path $env:LOCALAPPDATA "Android\Sdk")
    )) {
        if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
        $adb = Join-Path $candidate "platform-tools\adb.exe"
        if (Test-Path -LiteralPath $adb) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    throw "Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT."
}

function Get-ConnectedDeviceSerial {
    param([string]$AdbPath)

    $deviceLines = & $AdbPath devices | Where-Object { $_ -match "^(?<serial>\S+)\s+device(\s|$)" }
    if (-not $deviceLines) { throw "No connected Android device found." }

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

    throw "No connected Android device found."
}

function Quote-AdbShellArg {
    param([string]$Value)

    return "'" + ($Value -replace "'", "'\''") + "'"
}

$sdk = Resolve-AndroidSdkPath
$adbPath = Join-Path $sdk "platform-tools\adb.exe"
$serial = Get-ConnectedDeviceSerial $adbPath

Write-Host "==> Sending debug memory seed broadcast to '$serial'"
$broadcastCommand = @(
    "am broadcast",
    "-a dev.chungjungsoo.gptmobile.DEBUG_SEED_MEMORY_CHAT",
    "-n $PackageName/dev.chungjungsoo.gptmobile.debug.MemorySeedReceiver",
    "--es user $(Quote-AdbShellArg $User)",
    "--es assistant $(Quote-AdbShellArg $Assistant)"
) -join " "
& $adbPath -s $serial shell $broadcastCommand
if ($LASTEXITCODE -ne 0) { throw "Seed broadcast failed with exit code $LASTEXITCODE." }

Start-Sleep -Seconds 8

Write-Host "==> Recent memory rows"
$memorySql = "select memory_id, status, type, source, sensitivity, summary, recall_text from personal_memory order by memory_id desc limit 10;"
& $adbPath -s $serial shell "run-as $PackageName sqlite3 databases/chat_v2 $(Quote-AdbShellArg $memorySql)"

Write-Host "==> Recent debug seed chats"
$chatSql = "select c.chat_id, c.title, m.content from chats_v2 c join messages_v2 m on c.chat_id = m.chat_id where m.content like '%preachy%' order by c.chat_id desc, m.created_at asc limit 6;"
& $adbPath -s $serial shell "run-as $PackageName sqlite3 databases/chat_v2 $(Quote-AdbShellArg $chatSql)"
