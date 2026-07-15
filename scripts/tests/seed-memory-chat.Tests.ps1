$scriptPath = Join-Path $PSScriptRoot "..\seed-memory-chat.ps1"
. $scriptPath

Describe "seed-memory-chat.ps1" {
    It "parses without PowerShell syntax errors" {
        $tokens = $null
        $parseErrors = $null
        [System.Management.Automation.Language.Parser]::ParseFile(
            $scriptPath,
            [ref]$tokens,
            [ref]$parseErrors
        ) | Out-Null

        @($parseErrors).Count | Should Be 0
    }

    It "throws when an external command returns a non-zero exit code" {
        {
            Invoke-CheckedNativeCommand `
                -FilePath $env:ComSpec `
                -ArgumentList @("/d", "/c", "exit 23") `
                -FailureContext "expected failure"
        } | Should Throw "expected failure (exit code 23)."
    }

    It "reads schema 17 memory state without querying the removed personal memory table" {
        $content = Get-Content -Raw -Encoding UTF8 $scriptPath

        $content | Should Match "PRAGMA user_version"
        $content | Should Match "from memory_corpus_state"
        $content | Should Match "Wait-ForStableCanonicalState"
        $content | Should Match "memory_enabled true"
        $content | Should Not Match "(?i)from\s+personal_memory"
    }

    It "routes every native invocation through the checked command helper" {
        $tokens = $null
        $parseErrors = $null
        $ast = [System.Management.Automation.Language.Parser]::ParseFile(
            $scriptPath,
            [ref]$tokens,
            [ref]$parseErrors
        )
        $nativeInvocations = @($ast.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.CommandAst] -and
                $node.InvocationOperator -eq [System.Management.Automation.Language.TokenKind]::Ampersand
        }, $true))

        $nativeInvocations.Count | Should Be 1
        $nativeInvocations[0].Extent.Text | Should Match '& \$FilePath'
    }

    It "preflights sqlite3 sha256sum and run-as" {
        $script:preflightCommands = @()
        Mock Invoke-AdbShell {
            param($AdbPath, $Serial, $Command, $FailureContext)
            $script:preflightCommands += $Command
            @("verified")
        }

        Test-DeviceVerificationTools "adb" "emulator-test" "dev.test"

        $script:preflightCommands.Count | Should Be 3
        ($script:preflightCommands -join "|") | Should Match "command -v sqlite3"
        ($script:preflightCommands -join "|") | Should Match "command -v sha256sum"
        ($script:preflightCommands -join "|") | Should Match "run-as dev[.]test pwd"
    }

    It "waits for a fresh database to advance from version zero to schema 17" {
        $script:schemaReadCount = 0
        Mock Invoke-AdbShell { @("verified") }
        Mock Invoke-SqliteQuery {
            param($AdbPath, $Serial, $PackageName, $Sql, $FailureContext)
            if ($Sql -match "PRAGMA user_version") {
                $script:schemaReadCount += 1
                if ($script:schemaReadCount -eq 1) { return @("0") }
                return @("17")
            }
            @("0")
        }
        Mock Start-Sleep { }

        Wait-ForSchema17 "adb" "emulator-test" "dev.test" 2 0

        $script:schemaReadCount | Should Be 2
    }

    It "reports a persistent pre-17 schema only after exhausting the poll window" {
        $script:schemaReadCount = 0
        Mock Invoke-AdbShell { @("verified") }
        Mock Invoke-SqliteQuery {
            param($AdbPath, $Serial, $PackageName, $Sql, $FailureContext)
            if ($Sql -match "PRAGMA user_version") {
                $script:schemaReadCount += 1
                return @("16")
            }
            @("0")
        }
        Mock Start-Sleep { }

        $message = try {
            Wait-ForSchema17 "adb" "emulator-test" "dev.test" 2 0
            $null
        } catch {
            $_.Exception.Message
        }

        $script:schemaReadCount | Should Be 2
        $message | Should Be "Expected Room schema 17, found '16'."
    }

    It "requires this broadcast to create a chat with a pending memory turn" {
        $script:seedEvidenceSql = $null
        Mock Invoke-SqliteQuery {
            param($AdbPath, $Serial, $PackageName, $Sql, $FailureContext)
            $script:seedEvidenceSql = $Sql
            @("41|1")
        }

        $evidence = Wait-ForSeedEvidence `
            -AdbPath "adb" `
            -Serial "emulator-test" `
            -PackageName "dev.test" `
            -SeedMarker "memory-seed-test" `
            -PollAttempts 1 `
            -PollDelayMilliseconds 0

        $evidence.ChatId | Should Be 41
        $evidence.PendingCount | Should Be 1
        $script:seedEvidenceSql | Should Match "memory-seed-test"
        $script:seedEvidenceSql | Should Match "from memory_pending_turn"
    }

    It "rejects broadcast exit zero when no seed evidence appears" {
        Mock Invoke-SqliteQuery { @() }
        Mock Start-Sleep { }

        $message = try {
            Wait-ForSeedEvidence `
                -AdbPath "adb" `
                -Serial "emulator-test" `
                -PackageName "dev.test" `
                -SeedMarker "memory-seed-missing" `
                -PollAttempts 2 `
                -PollDelayMilliseconds 0
            $null
        } catch {
            $_.Exception.Message
        }

        $message | Should Match "^Timed out waiting for this broadcast to create a memory-enabled debug chat"
    }

    It "rejects a canonical file that changes between the two hash reads" {
        $sourceHash = ("a" * 64) -join ""
        $changedHash = ("b" * 64) -join ""
        $script:hashReadCount = 0
        Mock Read-LongTermCorpusState {
            [pscustomobject]@{
                Raw = "MEMORY.md|$sourceHash|1|ready|2"
                SourceHash = $sourceHash
                Generation = 1
                IndexStatus = "ready"
                RowVersion = 2
            }
        }
        Mock Read-CanonicalMemoryHash {
            $script:hashReadCount += 1
            if ($script:hashReadCount -eq 1) { $sourceHash } else { $changedHash }
        }
        Mock Start-Sleep { }

        $message = try {
            Wait-ForStableCanonicalState `
                -AdbPath "adb" `
                -Serial "emulator-test" `
                -PackageName "dev.test" `
                -CanonicalPath "files/memory_store/MEMORY.md" `
                -PollAttempts 1 `
                -PollDelayMilliseconds 0
            $null
        } catch {
            $_.Exception.Message
        }

        $message | Should Match "^Unable to observe a stable canonical MEMORY.md snapshot"
        $script:hashReadCount | Should Be 2
    }

    It "retries a changing corpus snapshot and returns only the stable generation" {
        $oldHash = ("c" * 64) -join ""
        $newHash = ("d" * 64) -join ""
        $script:stateReadCount = 0
        $script:hashReadCount = 0
        Mock Read-LongTermCorpusState {
            $script:stateReadCount += 1
            $isFirstAttempt = $script:stateReadCount -le 2
            $hash = if ($isFirstAttempt) { $oldHash } else { $newHash }
            $generation = if ($isFirstAttempt) { 1 } else { 2 }
            [pscustomobject]@{
                Raw = "MEMORY.md|$hash|$generation|ready|$generation"
                SourceHash = $hash
                Generation = $generation
                IndexStatus = "ready"
                RowVersion = $generation
            }
        }
        Mock Read-CanonicalMemoryHash {
            $script:hashReadCount += 1
            if ($script:hashReadCount -eq 1) { $oldHash } else { $newHash }
        }
        Mock Start-Sleep { }

        $state = Wait-ForStableCanonicalState `
            -AdbPath "adb" `
            -Serial "emulator-test" `
            -PackageName "dev.test" `
            -CanonicalPath "files/memory_store/MEMORY.md" `
            -PollAttempts 2 `
            -PollDelayMilliseconds 0

        $state.Generation | Should Be 2
        $script:stateReadCount | Should Be 4
        $script:hashReadCount | Should Be 4
    }

    It "broadcasts a unique marker with memory explicitly enabled" {
        $sourceHash = ("c" * 64) -join ""
        $script:capturedBroadcast = $null
        $script:capturedWaitMarker = $null
        $script:callOrder = @()
        Mock Resolve-AndroidSdkPath { "C:\Android\Sdk" }
        Mock Get-ConnectedDeviceSerial { "emulator-test" }
        Mock Test-DeviceVerificationTools { $script:callOrder += "tools" }
        Mock Start-DebugApplication { $script:callOrder += "start" }
        Mock Wait-ForSchema17 { $script:callOrder += "schema" }
        Mock Invoke-AdbShell {
            param($AdbPath, $Serial, $Command, $FailureContext)
            if ($Command -match "^am broadcast") {
                $script:capturedBroadcast = $Command
                $script:callOrder += "broadcast"
                return @("Broadcast completed: result=0")
            }
            return @("# ChatWithChat Memory")
        }
        Mock Wait-ForSeedEvidence {
            param($AdbPath, $Serial, $PackageName, $SeedMarker, $PollAttempts, $PollDelayMilliseconds)
            $script:capturedWaitMarker = $SeedMarker
            $script:callOrder += "wait"
            [pscustomobject]@{ ChatId = 51; PendingCount = 1 }
        }
        Mock Invoke-SqliteQuery {
            param($AdbPath, $Serial, $PackageName, $Sql, $FailureContext)
            if ($Sql -match "PRAGMA user_version") { return @("17") }
            if ($Sql -match "sqlite_master") { return @("0") }
            return @("verified-row")
        }
        Mock Wait-ForStableCanonicalState {
            $script:callOrder += "stable"
            [pscustomobject]@{
                Raw = "MEMORY.md|$sourceHash|1|ready|2"
                SourceHash = $sourceHash
                Generation = 1
                IndexStatus = "ready"
                RowVersion = 2
            }
        }

        Invoke-MemorySeedVerification -PollAttempts 1 -PollDelayMilliseconds 0

        $script:capturedBroadcast | Should Match "memory-seed-[0-9a-f]{32}"
        $script:capturedBroadcast | Should Match ([regex]::Escape($script:capturedWaitMarker))
        $script:capturedBroadcast | Should Match "--ez memory_enabled true"
        ($script:callOrder -join ",") | Should Be "tools,start,schema,broadcast,wait,stable"
    }
}
