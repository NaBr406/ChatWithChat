# On-Device Vector Memory Readiness

Status updated on 2026-07-14 for schema 17 on branch
`codex/memory-consistency-risk-closure`. The schema 17 implementation baseline
is `6e2f0c92f9afa43a05b420b765af61b85e79a097`. Historical Task 8/schema 16,
Task 7, and 16 KB evidence remains below. The Task 8 implementation baseline is
`6db485ff8dfe7a4e225768260d8564a922aa73f7`, the Task 7 evidence baseline is
`b267b269f1651286d6d06a1dcb5fb89ce2ad87fd`, and the 16 KB extension started
from `dbc1148`.

## Current Ownership

- `filesDir/memory_store/MEMORY.md` is the only ordinary chat-recall source of
  truth.
- `filesDir/memory_store/memory/YYYY-MM-DD.md` is maintenance-only input until
  daily distillation commits selected content to `MEMORY.md`.
- Room schema 17 retains chats, messages, providers, pending turns,
  checkpoints, maintenance jobs, activity logs, mutation receipts, corpus
  generations, and distillation checkpoints. The retired derived-index and
  legacy semantic tables are no longer part of Room.
- ObjectBox 5.4.2 is a disposable HNSW store below
  `noBackupFilesDir/memory_vector_index`. Deleting or corrupting it must move
  derived state forward from current Markdown; it never rolls Markdown back.
- Production `MemoryRetriever` is `HybridMemoryRetriever` after the production
  shadow gate passed. It always derives lexical candidates from current
  `MEMORY.md` and permanently falls back to them whenever the vector branch is
  unavailable or fails freshness checks. The production model is
  checksum-provisioned from release assets, verified again below
  `noBackupFilesDir`, self-tested with ONNX Runtime, and published through a
  dynamic `MemoryEmbeddingCapability` only after it is `READY`.
- Startup creates an idempotent schema 17 bootstrap receipt for the current
  `MEMORY.md`; the existing recovery/index worker then builds the complete
  ObjectBox snapshot. Missing, corrupt, or mismatched artifacts remain
  `NOT_PROVISIONED` with no network, cloud, or fake fallback.

Schema 16 cleanup is complete under the explicit authorization in
`docs/superpowers/plans/2026-07-13-direct-task8-schema16-memory-index-cleanup-prompt.md`.
That authorization skipped the additional schema 15 soak period but did not
relax non-destructive migration, business-data preservation, or runtime
upgrade requirements.

## Schema 17 Consistency Closure Evidence

### Schema And Migration

- `MIGRATION_16_17` contains only:

```sql
DROP TABLE IF EXISTS `chat_classification`;
DROP TABLE IF EXISTS `personal_memory`;
```

It does not read or write Markdown, rebuild retained tables, or use destructive
fallback.
- Schema 17 contains exactly 13 retained tables, 151 fields, 37 indexes, and 6
  foreign keys:

```text
chats_v2
messages_v2
platform_v2
platform_model_v2
chat_platform_model_v2
memory_maintenance_job
memory_mutation_group
memory_mutation_receipt
memory_corpus_state
memory_distillation_checkpoint
memory_chat_checkpoint
memory_pending_turn
memory_activity_log
```

- The schema 17 Room identity hash is
  `44588a69a5805e538f86c014dbd6f8e8`. The LF-content SHA-256 of `17.json` is
  `9BC3F36D510C472D06E4ACDEB55408E2BC20D21D8BB273E95E100B384A991779`.
- `1.json` through `16.json` are unchanged from the implementation baseline.
  The recorded `16.json` SHA-256,
  `E2FC5D089F3DD6B29F55C51EC2387BD151329D068B63C8FD1672597DA71FCF67`,
  is over the tracked Git blob/LF bytes, not a Windows CRLF working-tree copy.
- Production Kotlin has zero references to `PersonalMemory`,
  `PersonalMemoryDao`, `ChatClassification`, `ChatClassificationDao`,
  `migrateActiveMemoriesToMarkdown`, or the retired `MemoryMarkdownCodec`.
  Historical migration SQL and exported schema history remain intact.

### Consistency Matrix

| Gate | Result |
| --- | --- |
| Full JVM suite | 91 suites, 671 tests, 0 failures/errors/skips |
| Duplicate writes | same-proposal normalized duplicate `CREATE` rejects the proposal; a later exact-text `CREATE` is a byte-identical no-op |
| Duplicate recall | lexical, Hybrid, fallback, prompt packing, and token-budget boundaries retain one highest-ranked normalized text |
| Large duplicate corpus | 13 real ObjectBox tests cover 401 and 601 entries, exact cosine ordering, query limits, and a unique candidate after historical duplicates |
| Canonical UI/export | one long-term revision subscription refreshes the view; export performs a fresh canonical read |
| Terminal receipt recovery | missing, invalid, and hash-mismatched staging become persisted conflicts; transient I/O remains retryable; crash-window source-job finalization is replayed exactly once |
| Provider prompt assembly | all six provider families retain tools/search/system/context content and merge the memory prompt exactly once |
| Backup rules and simulation | API 30 backup rules, API 31+ cloud/device-transfer rules, and restored-receipt simulation exclude transient memory directories and preserve terminal recovery semantics |
| Final connected integration | 9 tests passed across Room migration, schema 17 startup, backup rules, restored-receipt simulation, and Memory ViewModel refresh/export |
| Production release Hybrid shadow | 2 tests passed with real model/ObjectBox state transitions and `MEMORY_HYBRID_SHADOW_OK` |
| Build and formatting | Kotlin/AndroidTest compilation, debug, release R8, lint vital, and ktlint 1.3.1 passed |

Normal production recall remains bound to `HybridMemoryRetriever`, maintenance
working-set reads remain lexical, and the global retrieval default remains
lexical. Missing, corrupt, stale, or identity-mismatched vector state continues
to fail closed to current Markdown lexical recall.

### Runtime Upgrade And Rebuild

The final runtime gate used `emulator-5560`, API 35 x86_64, with
`PAGE_SIZE=16384` and Wi-Fi disabled for AVD stability. The exact artifacts
were:

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Saved schema 16 baseline debug APK | 212,408,807 | `07B2713A46E3F1F9ABFB757FB92491D4AF33864948C0080AB40A43941B0E38B9` |
| Final schema 17 debug APK | 231,383,296 | `F6A6F477AFB15B4D30CF700B32B173D12121C7A0510C10A7F55470AA60113868` |
| Final release unsigned APK | 153,875,546 | `0B8EF959C94BE2983F296BB403F26B88E76466336CFF4201745326715754E064` |

The final production Hybrid shadow used a signed release target with SHA-256
`A73EB2B1B5A989A0D419FC14B9A8CE410771CBB5C24D51BBEDC07CEFB1F917C9`
and test APK SHA-256
`9454AB3CA01F4D174DEDB22424BC605514202E91DB2339D0CB6C99C15A558C69`.

The baseline APK created one synthetic sentinel in every schema 16 table, with
all platform tokens null. Before upgrade, Room reported 15 tables, 185 fields,
37 indexes, 6 foreign keys, identity
`f932278bc936f80dea6a122f5a046403`, zero `foreign_key_check` rows, and
`integrity_check=ok`.

`adb install -r` installed the final APK without uninstalling or clearing app
data. Before first launch the database was still schema 16 and the APK install
time, database/file hashes, and inodes were unchanged. First cold launch then
migrated to schema 17. All 13 retained table sentinels and the pending turn
remained, both legacy tables were absent, all platform tokens remained null,
and the exact 13-table/151-field/37-index/6-foreign-key schema reopened with
clean FK and integrity checks.

Before and after migration, repeated force-stop/cold-start cycles, and derived
index deletion, `MEMORY.md` SHA-256 remained
`633EA02D15E3651DD099974B3CE0D74BE26ED6A51742F4AE13F8F5E42982F7AF`.
Its inode and the memory-enabled DataStore hash/inode also remained unchanged.
The Memory page and export dialog both displayed the complete current canonical
content on the final APK. Seeded job/group/receipt attempts and row versions did
not churn across repeated starts.

Deleting only `noBackupFilesDir/memory_vector_index` while the app was stopped
left canonical Markdown, Room, and DataStore unchanged. The next cold start
rebuilt ObjectBox `data.mdb` and `lock.mdb`; corpus and indexed generation were
both 7, source/indexed hashes matched current `MEMORY.md`, and `last_error` was
empty.

### Backup Boundary

Both backup XML formats exclude only `memory_store/.staging/` and
`memory_store/.backups/`. ObjectBox and installed model files remain derived
state under `noBackupFilesDir`. The rules intentionally do not change the
existing eligibility of Room, DataStore, SharedPreferences, or platform
credentials; that broader credential backup policy remains a separate product
decision.

A real Android backup-agent cycle passed with
`com.android.localtransport/.LocalTransport`: package-level `backupnow`
succeeded, `pm clear` removed all private data on the disposable emulator, and
`bmgr restore` completed a full, version-matched package restore. An earlier
attempt while Android still marked the package stopped was rejected and
discarded before any data clear; only the later package-level success was used.
Before first launch, the Room database, `MEMORY.md`, the memory-enabled
DataStore, and the daily Markdown file matched their source hashes byte for
byte. Synthetic files under `.staging/` and `.backups/` and the entire ObjectBox
directory were absent. Cold launch rebuilt ObjectBox from restored Markdown;
the restored Memory UI, 13 table sentinels, pending turn, FK/integrity checks,
and seeded receipt/job row versions remained correct. The original GMS
transport and disabled Backup Manager state were restored after the test.

This LocalTransport result is device-local backup-agent evidence only. No real
cloud transport restore was performed, so cloud restore remains OPEN.

## Task 8 / Schema 16 Evidence

### Migration And Runtime Cleanup

- Production Kotlin has zero references to the retired `MemoryIndexRepository`,
  `MemoryIndexDao`, Room `MemoryDocument`/`MemoryChunk`, old rebuilder/search
  DTOs, or `MarkdownMemoryDebugEditor`. Historical table-creation SQL remains
  only for supported older migrations.
- `MIGRATION_15_16` dismisses every legacy Room-index job not already
  `succeeded` or `dismissed` with reason
  `schema16_legacy_room_index_removed`, clears its lease/schedule, then drops
  child `memory_chunk` before parent `memory_document`.
- Schema 16 contains exactly the 15 retained tables. A structured comparison
  proved all 15 retained entity definitions identical to schema 15: 185
  fields, 37 indexes, and 6 foreign keys. Only the two derived tables, their
  19 fields, 7 indexes, and 1 foreign key were removed.
- `16.json` SHA-256 is
  `E2FC5D089F3DD6B29F55C51EC2387BD151329D068B63C8FD1672597DA71FCF67`;
  `17.json` does not exist. No destructive migration fallback is present.

### Test And Device Results

| Gate | Result |
| --- | --- |
| Focused migration JVM suite | 17 tests passed, including strict UPDATE -> child DROP -> parent DROP ordering |
| Memory JVM suite | 267 tests, 0 failures/errors |
| Populated Room `15 -> 16` | passed with all 17 schema 15 tables seeded, exact snapshots for 14 untouched tables, contract-field checks for maintenance jobs, and old tables/indexes absent |
| Chained Room `14 -> 15 -> 16` | passed with business data and recovery state retained |
| Fresh schema 16 open/reopen | passed with exactly 15 retained tables |
| Room validation | real `ChatDatabaseV2` reopen and business/recovery/job/batch/activity DAO reads passed; `foreign_key_check` returned 0 rows and `integrity_check` returned `ok` |
| Schema 16 startup recovery | current `MEMORY.md` bytes/hash preserved; absent ObjectBox rebuilt to READY and remained queryable after store reopen |
| Gradle connected migration run | 3 tests passed on API 35 x86_64 `PAGE_SIZE=16384` |
| Production Hybrid shadow | real ONNX/ObjectBox semantic hit, unavailable-model lexical equivalence, stale-store rejection, and deleted-entry exclusion passed |
| Populated APK upgrade | schema 15 debug APK upgraded in place with `adb install -r`; no uninstall or clear-data occurred between versions |
| Upgrade data/UI | chat, message, provider, pending turn, maintenance history, and Memory-page content remained readable |
| Production ObjectBox deletion/rebuild | deleting only `noBackupFilesDir/memory_vector_index` and cold-starting recreated ObjectBox from current Markdown; corpus returned READY |
| Legacy retry/notification loop | repeated starts left legacy jobs dismissed with stable attempts/row versions; 0 legacy activity rows and 0 active app notifications |
| Debug/release/R8/lint | `assembleDebug`, `assembleRelease`, R8, and `lintVitalRelease` passed |

The runtime upgrade used `emulator-5560`, API 35 x86_64, with
`PAGE_SIZE=16384`. The schema 15 APK SHA-256 was
`E3D908B564B34825CD59302CE39AAA15B9A234AFEB51D2A2C994AA111F163C4A`;
the schema 16 debug APK SHA-256 was
`CA55E22A14DF4001B288E9ED20F9D5A6C083ED767EBA80B90CFB30A85395442E`.
Before and after migration, `MEMORY.md` SHA-256 was
`633EA02D15E3651DD099974B3CE0D74BE26ED6A51742F4AE13F8F5E42982F7AF`.
Content-free warning/error logs and database/APK snapshots are stored outside
the repository under `E:\code\ChatWithChat-task8-evidence\runtime-upgrade`.

### Accepted Residual Risk

- The user explicitly accepted skipping an additional multi-day schema 15
  soak. This does not weaken the passing populated migration/runtime evidence.
- Direct installation of a schema 15 APK over a schema 16 database is
  unsupported. Recovery is a schema 17 forward fix or a user-directed reset or
  pre-upgrade snapshot restore, never an automatic destructive fallback.
- Real backup-agent/cloud restore, physical ARM64 performance/OEM validation,
  and the 32-bit ABI support decision remain OPEN, non-blocking follow-ups.

## Task 7 Evidence

### Automated Recovery

- Daily distillation has deterministic failpoints after receipt preparation,
  canonical file commit, and checkpoint completion. JVM recovery tests verify
  one semantic call, no duplicate Markdown, completed job/checkpoint state,
  and semantic acknowledgement.
- `tools/memory-vector/run-process-death-harness.ps1` performs two separate
  instrumentation invocations around a real process kill. Phase one persists a
  PREPARED staged daily mutation and a `MEMORY.md` target that was renamed
  before its Room receipt advanced. Phase two starts a new process, recognizes
  the exact target hash, completes both mutations, and proves repeated recovery
  creates neither duplicate content nor a second vector-sync job.
- The process-death harness passed on `emulator-5554`: phase one reported
  `Process crashed`; phase two reported `OK (1 test)`.

### Test And Runtime Results

| Gate | Result |
| --- | --- |
| `:app:testDebugUnitTest --tests "*Memory*"` | 269 tests, 0 failures/errors |
| Daily distillation failpoint suite | 12 tests, 0 failures/errors |
| ObjectBox device suite | 11 tests passed |
| Room populated `14 -> 15` device migration | 1 test passed |
| Room recovery/lease and WorkManager device suites | 25 tests passed |
| Pinned ONNX Runtime build canary | 1 test passed |
| Real two-process receipt recovery harness | 1 recovery test passed |
| `:app:compileDebugKotlin` / AndroidTest compilation | passed |
| `:app:assembleRelease` with R8/lint vital | passed |
| Debug-key-signed release install and cold launch | passed; process remained resumed with no `AndroidRuntime` error |
| Android 15 Experimental 16 KB emulator compatibility | passed on API 35 x86_64 `google_apis_ps16k`; `PAGE_SIZE=16384` |
| Release ObjectBox and production ONNX lifecycle | passed: initialize, infer, write, HNSW query, close/reopen, intentional process kill, new-process reopen, and persisted query |
| Physical ARM64 performance/OEM validation | pending as a separate final gate; it does not block 16 KB page-size compatibility |
| Production release Hybrid shadow | passed with the real tokenizer, ONNX model, and ObjectBox on API 35 x86_64 `PAGE_SIZE=16384` |

The earlier debug/device suites used an API 35 x86_64 emulator reporting ABI
list `x86_64,arm64-v8a` and `PAGE_SIZE=4096`; those results are not 16 KB
evidence. For the dedicated gate, the ARM64 16 KB AVD was attempted first, but
QEMU2 reported that an `arm64` AVD is unsupported on this x86_64 Windows host.
The permitted fallback used the API 35 x86_64
`system-images;android-35;google_apis_ps16k;x86_64` image on `emulator-5560`.
`adb shell getconf PAGE_SIZE` returned exactly `16384`, with runtime ABI
`x86_64` and ABI list `x86_64,arm64-v8a`.

| Runtime component | APK owner | Evidence meaning |
| --- | --- | --- |
| ObjectBox 5.4.2 | Installed, non-debuggable release target APK | Production release R8, Java/JNI, persistence, and HNSW runtime evidence |
| ONNX Runtime 1.27.0 and checksum-verified production model | Installed, non-debuggable release target APK | Production asset install, tokenizer, inference, CLS/L2, R8/JNI, and process-restart evidence |

The release target loaded both native runtimes again in the second process.
Android `nativeloader` evidence identified `libobjectbox-jni.so` and
`libonnxruntime4j_jni.so` under the release target APK in both invocations.
The companion contains neither ONNX Runtime nor production model assets.

### APK And Native Packaging

The earlier Task 7 debug size baseline remains:

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Debug APK | 97,030,068 | `d68c012b1213c80399d22a8ac466960ec20eea6062811a0d38198c4412ae3462` |

The original 16 KB gate installed these historical signed artifacts:

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Release unsigned build input | 20,997,578 | `04e3c27d290d057c3ee7ae6ef6f029182ea686ec9d2685daf73d3a2b30c3421b` |
| Installed signed release target | 21,022,625 | `f06297cca457d4914d13e5606b2420bd312566506588361b586a640f9a38d88e` |
| Release AndroidTest build input | 134,867,195 | `0b1c8ef248a51eec793d5307d86c87c43fc22a5177dc3a093d6050fb172ba7c2` |
| Installed signed instrumentation companion | 134,881,735 | `b641b056f5990bcef5547d482283175a2be9ade7a894a9842c93ee1be90a0146` |

At that historical Task 7 point, compared with the pre-ObjectBox baseline in the artifact contract, the debug
APK is 10,150,479 bytes larger and the release APK is 9,922,521 bytes larger.
Compared with the Task 0 post-ObjectBox snapshot, the Task 7 debug APK is
2,054,082 bytes larger and the release APK is 263,188 bytes larger. ONNX
Runtime and the 24,010,842-byte canary model remain AndroidTest-only and are
absent from the release APK.

`zipalign -c -P 16 4` passed for both signed APKs. NDK 28.2
`llvm-readelf --program-headers --wide` inspected every native library under
the two requested 64-bit ABIs:

| APK | ABI | Native libraries checked | Minimum LOAD alignment | Result |
| --- | --- | --- | ---: | --- |
| Release target | arm64-v8a | `libandroidx.graphics.path.so`, `libdatastore_shared_counter.so`, `libobjectbox-jni.so` | `0x4000` | pass |
| Release target | x86_64 | `libandroidx.graphics.path.so`, `libdatastore_shared_counter.so`, `libobjectbox-jni.so` | `0x4000` | pass |
| Instrumentation companion | arm64-v8a | `libonnxruntime.so`, `libonnxruntime4j_jni.so` | `0x4000` | pass |
| Instrumentation companion | x86_64 | `libonnxruntime.so`, `libonnxruntime4j_jni.so` | `0x4000` | pass |

An earlier inspection found the packaged 32-bit ObjectBox variants aligned to
`0x1000`; deciding whether those ABIs remain supported is a separate product
gate and does not change the passing arm64-v8a/x86_64 result.

The production-provisioned 16 KB rerun installed these exact artifacts:

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Release unsigned build input | 153,883,230 | `7e8d75883a7cce7a9ceaefbfe4abc100260c65748e718a58136385162f5ac74b` |
| Installed signed release target | 153,910,465 | `6e49f9e50396253e833bb62e1fa0b1658ae5bb3b665c8179b78ac96ad8b146c7` |
| Release AndroidTest build input | 2,059,615 | `f2c186791ea30eef9cb9056924316fe0c75e62951343e232e9bc558487526c20` |
| Installed signed instrumentation companion | 2,075,815 | `9b9bceb119303d1e5089011f271077dfeb54cd9d440d32075cb9206267180d56` |

The target APK owns ObjectBox, ONNX Runtime, and all seven fixed model/tokenizer
assets. `tools/memory-vector/run-16kb-release-compatibility.ps1` reproduced
initialization, inference, ObjectBox write/query, close/reopen, intentional
process death, new-process reopen/query, ZIP alignment, both 64-bit ELF checks,
and target-APK native ownership with `PAGE_SIZE=16384`.

`tools/memory-vector/run-production-hybrid-shadow.ps1` separately passed the
real production Hybrid shadow. Generation 2 indexed six visible chunks; after
canonical deletion, generation 3 rejected the stale six-row store without an
HNSW query, rebuilt five rows, and permanently excluded the deleted entry.
Daily-only content was never embedded or returned, a low-overlap Chinese
paraphrase hit the intended visible entry in the top five, and
`NOT_PROVISIONED` produced the same entry-ID/content-hash projection as current
lexical recall.

## Failure Matrix Coverage

| Required behavior | Current evidence |
| --- | --- |
| Daily-only text is excluded until distillation | JVM lexical and distillation tests |
| Deleted/stale/mismatched vectors fail closed | hybrid retriever JVM tests and ObjectBox device tests |
| Markdown changes while embedding | synchronizer revision-retry JVM test |
| Missing embedding never falls back to cloud | provisioning tests and real release Hybrid lexical-fallback shadow |
| ObjectBox missing/corrupt rebuilds only derived state | recovery JVM tests, ObjectBox corruption device test, schema 16 startup test, and populated runtime delete/restart smoke |
| Dimension/fingerprint/model/tokenizer/chunker mismatch | JVM and device fail-closed tests |
| PREPARED and rename-before-Room process death | real two-invocation process-death harness |
| ObjectBox transaction succeeds before Room completion | manifest fast-forward/retry JVM tests |
| Semantic file commit precedes claimed-batch completion | deterministic consolidation and distillation failpoint tests |
| Two rapid mutations and `A -> B -> A` | persistent-generation JVM tests |
| Worker races, leases, bounded retries, and disabled memory | scheduler/processor/worker JVM tests and Room claim device tests |
| Populated Room `14 -> 15 -> 16` upgrade | real Room instrumentation migration with business/recovery data retained |
| Populated Room `15 -> 16` upgrade | all retained rows/columns preserved; only two derived tables/indexes removed; real Room reopen passed |
| Persisted legacy Room-index job after schema 16 | migration dismissal plus startup repair test; no retry or notification loop in runtime smoke |
| Populated Room `16 -> 17` upgrade | all 13 retained tables and sentinels preserved; only `chat_classification` and `personal_memory` removed; FK/integrity clean |
| Duplicate normalized `CREATE` operations | strict same-proposal rejection plus cross-batch byte-identical no-op tests |
| Historical exact-text duplicates in recall | lexical, Hybrid, fallback, prompt-packing, and 401/601-entry ObjectBox tests |
| Background canonical update while Memory is open | long-term revision Flow instrumentation plus fresh-export instrumentation |
| Missing, invalid, or hash-mismatched staging | persisted receipt/group conflict, exact terminal source-job reason, repeated-startup no-churn tests |
| Process death after conflict persistence | persisted conflict scan completes source-job finalization once; exact terminal state is skipped thereafter |
| Transient staging I/O | receipt remains PREPARED and bounded repair remains scheduled |
| Restored PREPARED receipt without transient files | restore simulation terminates missing staging once or completes an already-written target idempotently |
| Backup of transient memory directories | both Android backup XML formats exclude only `.staging/` and `.backups/` within `memory_store` |

## Release Gates

| Gate | Status | Scope |
| --- | --- | --- |
| 16 KB emulator compatibility | PASSED | Clears page-size compatibility after release lifecycle, process restart, ZIP alignment, and both 64-bit ABI ELF checks |
| Real ARM64 performance/OEM validation | OPEN | Final latency, indexing, peak RSS, cancellation, concurrency, restart, and OEM-environment evidence; it does not block or reopen the page-size gate |
| Production embedding provisioning/quality | PASSED | Immutable revision/hash contract, 72 tokenizer fixtures, release asset/install checks, real ONNX inference, shadow semantic top-5, and process restart |
| Production Hybrid cutover | PASSED | Production recall uses Hybrid DI after shadow passed; missing, stale, corrupt, or unavailable vectors permanently fall back to current Markdown lexical recall |
| Room schema 16 / Task 8 cleanup | PASSED | Non-destructive populated and chained migrations, exact retained-schema comparison, in-place APK upgrade, Room reopen, Markdown preservation, and ObjectBox rebuild |
| Room schema 17 consistency | PASSED | Non-destructive populated/chained migration, exact 13-table schema, legacy runtime removal, duplicate defense, canonical-live UI/export, and terminal receipt recovery |
| 32-bit ABI policy | OPEN | Separate support decision because packaged 32-bit ObjectBox LOAD alignment is `0x1000` |
| Backup XML and restore simulation | PASSED | API 30 and API 31+ rules exclude only transient memory directories; restored receipt simulations preserve idempotent/terminal behavior |
| LocalTransport backup-agent restore | PASSED | Real package backup, private-data clear, full version-matched restore, byte-identical canonical/Room/DataStore/daily recovery, transient/derived exclusion, and ObjectBox rebuild |
| Cloud backup restore | OPEN | No real cloud transport restore has been performed; LocalTransport cannot close this gate |

The schema 16 cleanup, schema 17 consistency closure, LocalTransport restore,
production provisioning, Hybrid shadow/cutover, and 16 KB page-size
compatibility gates have passed. The real ARM64 gate remains only a final
performance/OEM supplement; 32-bit support and real cloud restore remain
separate OPEN decisions. Keep ObjectBox rebuildable and preserve lexical
fallback permanently.
