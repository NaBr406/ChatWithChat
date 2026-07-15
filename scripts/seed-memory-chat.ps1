param(
    [string]$DeviceSerial,
    [string]$User = "Please remember that I prefer direct, natural replies and I dislike preachy answers.",
    [string]$Assistant = "Got it. I will keep replies direct, natural, and not preachy.",
    [ValidatePattern("^[A-Za-z][A-Za-z0-9_.]*$")]
    [string]$PackageName = "cn.nabr.chatwithchat"
)

$ErrorActionPreference = "Stop"

function Invoke-CheckedNativeCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,
        [Parameter(Mandatory = $true)]
        [string[]]$ArgumentList,
        [Parameter(Mandatory = $true)]
        [string]$FailureContext
    )

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        # Windows PowerShell does not turn a non-zero native exit code into a terminating error.
        $ErrorActionPreference = "Continue"
        $output = & $FilePath @ArgumentList 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    $outputLines = @($output | ForEach-Object { $_.ToString() })
    if ($exitCode -ne 0) {
        $details = ($outputLines -join [Environment]::NewLine).Trim()
        if ($details) {
            throw "$FailureContext (exit code $exitCode): $details"
        }
        throw "$FailureContext (exit code $exitCode)."
    }

    return $outputLines
}

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

    $deviceLines = Invoke-CheckedNativeCommand `
        -FilePath $AdbPath `
        -ArgumentList @("devices") `
        -FailureContext "Unable to list Android devices" |
        Where-Object { $_ -match "^(?<serial>\S+)\s+device(\s|$)" }
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

function Invoke-AdbShell {
    param(
        [string]$AdbPath,
        [string]$Serial,
        [string]$Command,
        [string]$FailureContext
    )

    return Invoke-CheckedNativeCommand `
        -FilePath $AdbPath `
        -ArgumentList @("-s", $Serial, "shell", $Command) `
        -FailureContext $FailureContext
}

function Invoke-SqliteQuery {
    param(
        [string]$AdbPath,
        [string]$Serial,
        [string]$PackageName,
        [string]$Sql,
        [string]$FailureContext
    )

    $command = "run-as $PackageName sqlite3 databases/chat_v2 $(Quote-AdbShellArg $Sql)"
    return Invoke-AdbShell `
        -AdbPath $AdbPath `
        -Serial $Serial `
        -Command $command `
        -FailureContext $FailureContext
}

function Get-SingleOutputLine {
    param(
        [object[]]$Output,
        [string]$Context
    )

    $lines = @(
        $Output |
            Where-Object { $null -ne $_ } |
            ForEach-Object { $_.ToString().Trim() } |
            Where-Object { $_ }
    )
    if ($lines.Count -ne 1) {
        throw "$Context returned $($lines.Count) non-empty lines; expected exactly one."
    }
    return $lines[0]
}

function Test-DeviceVerificationTools {
    param(
        [string]$AdbPath,
        [string]$Serial,
        [string]$PackageName
    )

    foreach ($tool in @("sqlite3", "sha256sum")) {
        $null = Invoke-AdbShell `
            -AdbPath $AdbPath `
            -Serial $Serial `
            -Command "command -v $tool" `
            -FailureContext "Disposable debug device does not provide $tool"
    }
    $null = Invoke-AdbShell `
        -AdbPath $AdbPath `
        -Serial $Serial `
        -Command "run-as $PackageName pwd" `
        -FailureContext "Package '$PackageName' is not installed as a debuggable application"
}

function Start-DebugApplication {
    param(
        [string]$AdbPath,
        [string]$Serial,
        [string]$PackageName
    )

    $component = "$PackageName/cn.nabr.chatwithchat.presentation.ui.main.MainActivity"
    $null = Invoke-AdbShell `
        -AdbPath $AdbPath `
        -Serial $Serial `
        -Command "am start -n $component" `
        -FailureContext "Unable to start the debug application"
}

function Wait-ForSchema17 {
    param(
        [string]$AdbPath,
        [string]$Serial,
        [string]$PackageName,
        [int]$PollAttempts,
        [int]$PollDelayMilliseconds
    )

    $lastFailure = "Room database was not initialized"
    $lastObservedSchemaVersion = $null
    foreach ($attempt in 1..$PollAttempts) {
        try {
            $null = Invoke-AdbShell `
                -AdbPath $AdbPath `
                -Serial $Serial `
                -Command "run-as $PackageName test -f databases/chat_v2" `
                -FailureContext "Room database is not initialized"
            $schemaVersion = Get-SingleOutputLine `
                -Output (Invoke-SqliteQuery `
                    -AdbPath $AdbPath `
                    -Serial $Serial `
                    -PackageName $PackageName `
                    -Sql "PRAGMA user_version;" `
                    -FailureContext "Unable to read Room schema version") `
                -Context "Room schema version query"
            if ($schemaVersion -match "^[0-9]+$") {
                $lastObservedSchemaVersion = $schemaVersion
            }
            if ($schemaVersion -match "^[0-9]+$" -and $schemaVersion -ne "17") {
                throw "Room schema has not reached 17; currently '$schemaVersion'."
            } elseif ($schemaVersion -ne "17") {
                throw "Unexpected Room schema version '$schemaVersion'."
            }

            $legacyTableCount = Get-SingleOutputLine `
                -Output (Invoke-SqliteQuery `
                    -AdbPath $AdbPath `
                    -Serial $Serial `
                    -PackageName $PackageName `
                    -Sql "select count(*) from sqlite_master where type = 'table' and name in ('personal_memory', 'chat_classification');" `
                    -FailureContext "Unable to verify removed legacy memory tables") `
                -Context "Legacy memory table query"
            if ($legacyTableCount -ne "0") {
                throw "Schema 17 still contains $legacyTableCount legacy memory table(s)."
            }
            return
        } catch {
            $lastFailure = $_.Exception.Message
            if ($lastFailure -match "^Schema 17 still contains") {
                throw
            }
        }

        if ($attempt -lt $PollAttempts -and $PollDelayMilliseconds -gt 0) {
            Start-Sleep -Milliseconds $PollDelayMilliseconds
        }
    }

    if ($null -ne $lastObservedSchemaVersion -and $lastObservedSchemaVersion -ne "17") {
        throw "Expected Room schema 17, found '$lastObservedSchemaVersion'."
    }
    throw "Unable to verify Room schema 17 before seeding ($lastFailure)."
}

function Wait-ForSeedEvidence {
    param(
        [string]$AdbPath,
        [string]$Serial,
        [string]$PackageName,
        [string]$SeedMarker,
        [int]$PollAttempts,
        [int]$PollDelayMilliseconds
    )

    $seedSql = @"
select c.chat_id || '|' || (
    select count(*) from memory_pending_turn p where p.chat_id = c.chat_id
)
from chats_v2 c
where exists (
    select 1 from messages_v2 m
    where m.chat_id = c.chat_id and instr(m.content, '$SeedMarker') > 0
)
order by c.chat_id desc
limit 1;
"@ -replace "\r?\n", " "
    $lastFailure = "no matching chat was visible"

    foreach ($attempt in 1..$PollAttempts) {
        try {
            $line = Get-SingleOutputLine `
                -Output (Invoke-SqliteQuery `
                    -AdbPath $AdbPath `
                    -Serial $Serial `
                    -PackageName $PackageName `
                    -Sql $seedSql `
                    -FailureContext "Unable to inspect this seed run") `
                -Context "Seed evidence query"
            if ($line -match "^(?<chatId>[0-9]+)[|](?<pendingCount>[1-9][0-9]*)$") {
                return [pscustomobject]@{
                    ChatId = [int]$Matches.chatId
                    PendingCount = [int]$Matches.pendingCount
                }
            }
            $lastFailure = "unexpected seed evidence '$line'"
        } catch {
            $lastFailure = $_.Exception.Message
        }

        if ($attempt -lt $PollAttempts -and $PollDelayMilliseconds -gt 0) {
            Start-Sleep -Milliseconds $PollDelayMilliseconds
        }
    }

    throw "Timed out waiting for this broadcast to create a memory-enabled debug chat ($lastFailure)."
}

function Read-LongTermCorpusState {
    param(
        [string]$AdbPath,
        [string]$Serial,
        [string]$PackageName
    )

    $line = Get-SingleOutputLine `
        -Output (Invoke-SqliteQuery `
            -AdbPath $AdbPath `
            -Serial $Serial `
            -PackageName $PackageName `
            -Sql "select source_path || '|' || source_hash || '|' || generation || '|' || index_status || '|' || row_version from memory_corpus_state where corpus = 'chat_recall_long_term';" `
            -FailureContext "Unable to read authoritative memory corpus state") `
        -Context "Long-term memory corpus state query"
    if (
        $line -notmatch `
            "^MEMORY[.]md[|](?<hash>[0-9a-f]{64})[|](?<generation>[1-9][0-9]*)[|](?<status>pending|ready|blocked_dependency|waiting_repair)[|](?<rowVersion>[0-9]+)$"
    ) {
        throw "Unexpected long-term memory corpus state: '$line'."
    }

    return [pscustomobject]@{
        Raw = $line
        SourceHash = $Matches.hash
        Generation = [long]$Matches.generation
        IndexStatus = $Matches.status
        RowVersion = [long]$Matches.rowVersion
    }
}

function Read-CanonicalMemoryHash {
    param(
        [string]$AdbPath,
        [string]$Serial,
        [string]$PackageName,
        [string]$CanonicalPath
    )

    $line = Get-SingleOutputLine `
        -Output (Invoke-AdbShell `
            -AdbPath $AdbPath `
            -Serial $Serial `
            -Command "run-as $PackageName sha256sum $CanonicalPath" `
            -FailureContext "Unable to hash canonical MEMORY.md") `
        -Context "Canonical MEMORY.md hash"
    if ($line -notmatch "^(?<hash>[0-9a-fA-F]{64})\s+") {
        throw "Unexpected canonical MEMORY.md hash output: '$line'."
    }
    return $Matches.hash.ToLowerInvariant()
}

function Wait-ForStableCanonicalState {
    param(
        [string]$AdbPath,
        [string]$Serial,
        [string]$PackageName,
        [string]$CanonicalPath,
        [int]$PollAttempts,
        [int]$PollDelayMilliseconds
    )

    $lastFailure = "no stable corpus snapshot was visible"
    foreach ($attempt in 1..$PollAttempts) {
        try {
            $before = Read-LongTermCorpusState $AdbPath $Serial $PackageName
            $beforeHash = Read-CanonicalMemoryHash $AdbPath $Serial $PackageName $CanonicalPath
            $after = Read-LongTermCorpusState $AdbPath $Serial $PackageName
            $afterHash = Read-CanonicalMemoryHash $AdbPath $Serial $PackageName $CanonicalPath
            if (
                $before.Raw -eq $after.Raw -and
                $beforeHash -eq $afterHash -and
                $afterHash -eq $after.SourceHash
            ) {
                return $after
            }
            $lastFailure = "corpus/file snapshot changed or hash did not match"
        } catch {
            $lastFailure = $_.Exception.Message
        }

        if ($attempt -lt $PollAttempts -and $PollDelayMilliseconds -gt 0) {
            Start-Sleep -Milliseconds $PollDelayMilliseconds
        }
    }

    throw "Unable to observe a stable canonical MEMORY.md snapshot ($lastFailure)."
}

function Invoke-MemorySeedVerification {
    param(
        [ValidateRange(1, 240)]
        [int]$PollAttempts = 120,
        [ValidateRange(0, 5000)]
        [int]$PollDelayMilliseconds = 500
    )

    $sdk = Resolve-AndroidSdkPath
    $adbPath = Join-Path $sdk "platform-tools\adb.exe"
    $serial = Get-ConnectedDeviceSerial $adbPath
    $seedMarker = "memory-seed-$([Guid]::NewGuid().ToString('N'))"
    $seedUser = "$User [$seedMarker]"

    Write-Host "==> Verifying disposable debug-device tools"
    Test-DeviceVerificationTools $adbPath $serial $PackageName

    Write-Host "==> Starting debug application"
    Start-DebugApplication $adbPath $serial $PackageName

    Write-Host "==> Verifying Room schema 17 before seeding"
    Wait-ForSchema17 `
        -AdbPath $adbPath `
        -Serial $serial `
        -PackageName $PackageName `
        -PollAttempts $PollAttempts `
        -PollDelayMilliseconds $PollDelayMilliseconds

    Write-Host "==> Sending debug memory seed broadcast to '$serial'"
    $broadcastCommand = @(
        "am broadcast",
        "-a cn.nabr.chatwithchat.DEBUG_SEED_MEMORY_CHAT",
        "-n $PackageName/cn.nabr.chatwithchat.debug.MemorySeedReceiver",
        "--es user $(Quote-AdbShellArg $seedUser)",
        "--es assistant $(Quote-AdbShellArg $Assistant)",
        "--ez memory_enabled true"
    ) -join " "
    Invoke-AdbShell `
        -AdbPath $adbPath `
        -Serial $serial `
        -Command $broadcastCommand `
        -FailureContext "Seed broadcast failed" |
        ForEach-Object { Write-Host $_ }

    Write-Host "==> Waiting for this seed run"
    $seedEvidence = Wait-ForSeedEvidence `
        -AdbPath $adbPath `
        -Serial $serial `
        -PackageName $PackageName `
        -SeedMarker $seedMarker `
        -PollAttempts $PollAttempts `
        -PollDelayMilliseconds $PollDelayMilliseconds
    Write-Host "seed_marker=$seedMarker | chat_id=$($seedEvidence.ChatId) | pending_turns=$($seedEvidence.PendingCount)"

    $canonicalPath = "files/memory_store/MEMORY.md"
    Write-Host "==> Waiting for stable authoritative long-term memory state"
    $corpusState = Wait-ForStableCanonicalState `
        -AdbPath $adbPath `
        -Serial $serial `
        -PackageName $PackageName `
        -CanonicalPath $canonicalPath `
        -PollAttempts $PollAttempts `
        -PollDelayMilliseconds $PollDelayMilliseconds
    Write-Host "MEMORY.md | generation=$($corpusState.Generation) | index_status=$($corpusState.IndexStatus) | row_version=$($corpusState.RowVersion)"
    Write-Host "canonical_sha256=$($corpusState.SourceHash)"

    Write-Host "==> Canonical MEMORY.md"
    Invoke-AdbShell `
        -AdbPath $adbPath `
        -Serial $serial `
        -Command "run-as $PackageName cat $canonicalPath" `
        -FailureContext "Unable to read canonical MEMORY.md" |
        ForEach-Object { Write-Host $_ }

    Write-Host "==> Recent pending memory turns"
    $pendingTurnSql = "select p.turn_key, p.chat_id, coalesce(p.claimed_job_id, 'pending'), p.completed_at from memory_pending_turn p where p.chat_id = $($seedEvidence.ChatId) order by p.created_at desc limit 10;"
    Invoke-SqliteQuery `
        -AdbPath $adbPath `
        -Serial $serial `
        -PackageName $PackageName `
        -Sql $pendingTurnSql `
        -FailureContext "Unable to read recent pending memory turns" |
        ForEach-Object { Write-Host $_ }

    Write-Host "==> Recent debug seed chats"
    $chatSql = "select c.chat_id, c.title, m.content from chats_v2 c join messages_v2 m on c.chat_id = m.chat_id where c.chat_id = $($seedEvidence.ChatId) order by m.created_at asc limit 6;"
    Invoke-SqliteQuery `
        -AdbPath $adbPath `
        -Serial $serial `
        -PackageName $PackageName `
        -Sql $chatSql `
        -FailureContext "Unable to read recent debug seed chats" |
        ForEach-Object { Write-Host $_ }
}

if ($MyInvocation.InvocationName -ne ".") {
    Invoke-MemorySeedVerification
}
