# On-Device Vector Memory Readiness

Status updated on 2026-07-14 for schema 16 on branch
`codex/task8-schema16-cleanup`. The Task 8 implementation baseline is
`6db485ff8dfe7a4e225768260d8564a922aa73f7`; the earlier Task 7 evidence
baseline remains `b267b269f1651286d6d06a1dcb5fb89ce2ad87fd`, and the 16 KB
extension started from `dbc1148`.

## Current Ownership

- `filesDir/memory_store/MEMORY.md` is the only ordinary chat-recall source of
  truth.
- `filesDir/memory_store/memory/YYYY-MM-DD.md` is maintenance-only input until
  daily distillation commits selected content to `MEMORY.md`.
- Room schema 16 retains chats, messages, providers, pending turns,
  checkpoints, maintenance jobs, activity logs, mutation receipts, corpus
  generations, and distillation checkpoints. The retired derived-index tables
  `memory_chunk` and `memory_document` are no longer part of Room.
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
- Startup creates an idempotent schema 16 bootstrap receipt for the current
  `MEMORY.md`; the existing recovery/index worker then builds the complete
  ObjectBox snapshot. Missing, corrupt, or mismatched artifacts remain
  `NOT_PROVISIONED` with no network, cloud, or fake fallback.

Schema 16 cleanup is complete under the explicit authorization in
`docs/superpowers/plans/2026-07-13-direct-task8-schema16-memory-index-cleanup-prompt.md`.
That authorization skipped the additional schema 15 soak period but did not
relax non-destructive migration, business-data preservation, or runtime
upgrade requirements.

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

## Release Gates

| Gate | Status | Scope |
| --- | --- | --- |
| 16 KB emulator compatibility | PASSED | Clears page-size compatibility after release lifecycle, process restart, ZIP alignment, and both 64-bit ABI ELF checks |
| Real ARM64 performance/OEM validation | OPEN | Final latency, indexing, peak RSS, cancellation, concurrency, restart, and OEM-environment evidence; it does not block or reopen the page-size gate |
| Production embedding provisioning/quality | PASSED | Immutable revision/hash contract, 72 tokenizer fixtures, release asset/install checks, real ONNX inference, shadow semantic top-5, and process restart |
| Production Hybrid cutover | PASSED | Production recall uses Hybrid DI after shadow passed; missing, stale, corrupt, or unavailable vectors permanently fall back to current Markdown lexical recall |
| Room schema 16 / Task 8 cleanup | PASSED | Non-destructive populated and chained migrations, exact retained-schema comparison, in-place APK upgrade, Room reopen, Markdown preservation, and ObjectBox rebuild |
| 32-bit ABI policy | OPEN | Separate support decision because packaged 32-bit ObjectBox LOAD alignment is `0x1000` |
| Backup/restore integration | OPEN | Separate real backup-agent restore validation |

The schema 16 cleanup, production provisioning, Hybrid shadow/cutover, and 16
KB page-size compatibility gates have passed. The real ARM64 gate remains only
a final performance/OEM supplement; 32-bit support and real backup-agent
restore remain separate OPEN decisions. Keep ObjectBox rebuildable and preserve
lexical fallback permanently.
