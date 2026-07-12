# On-Device Vector Memory Readiness

Status captured on 2026-07-12 for schema 15 on branch
`codex/openclaw-vector-memory-maintenance`. The implementation baseline before
the Task 7 evidence slice is `b267b269f1651286d6d06a1dcb5fb89ce2ad87fd`.

## Current Ownership

- `filesDir/memory_store/MEMORY.md` is the only ordinary chat-recall source of
  truth.
- `filesDir/memory_store/memory/YYYY-MM-DD.md` is maintenance-only input until
  daily distillation commits selected content to `MEMORY.md`.
- Room schema 15 retains chats, messages, providers, pending turns,
  checkpoints, maintenance jobs, activity logs, mutation receipts, corpus
  generations, and distillation checkpoints.
- ObjectBox 5.4.2 is a disposable HNSW store below
  `noBackupFilesDir/memory_vector_index`. Deleting or corrupting it must move
  derived state forward from current Markdown; it never rolls Markdown back.
- Production `MemoryRetriever` remains `MarkdownLexicalRetriever`.
  `MemoryEmbeddingCapability` is `NOT_PROVISIONED`, so production hybrid DI and
  READY vector publication remain disabled.

Schema 16 and removal of the old Room `memory_document` / `memory_chunk`
tables are intentionally deferred to a later release after schema 15 has been
installed and observed.

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
| `:app:testDebugUnitTest --tests "*Memory*"` | 243 tests, 0 failures/errors |
| Daily distillation failpoint suite | 12 tests, 0 failures/errors |
| ObjectBox device suite | 11 tests passed |
| Room populated `14 -> 15` device migration | 1 test passed |
| Room recovery/lease and WorkManager device suites | 25 tests passed |
| Pinned ONNX Runtime build canary | 1 test passed |
| Real two-process receipt recovery harness | 1 recovery test passed |
| `:app:compileDebugKotlin` / AndroidTest compilation | passed |
| `:app:assembleRelease` with R8/lint vital | passed |
| Debug-key-signed release install and cold launch | passed; process remained resumed with no `AndroidRuntime` error |

The connected target was an API 35 x86_64 emulator reporting ABI list
`x86_64,arm64-v8a` and `PAGE_SIZE=4096`. These results are not physical arm64
or 16 KB page-size evidence.

### APK And Native Packaging

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Debug APK | 97,030,068 | `d68c012b1213c80399d22a8ac466960ec20eea6062811a0d38198c4412ae3462` |
| Release unsigned APK | 20,997,578 | `9c9f8caa901e2b3e7e1fc18793d9340af483c30b94529226bd03bac3df72f896` |
| AndroidTest APK | 136,066,348 | `e1059aed0616192ea35ad1ed272681f1fa8fdf94e5749fc98b310df0f1d69434` |

Compared with the pre-ObjectBox baseline in the artifact contract, the debug
APK is 10,150,479 bytes larger and the release APK is 9,922,521 bytes larger.
Compared with the Task 0 post-ObjectBox snapshot, the Task 7 debug APK is
2,054,082 bytes larger and the release APK is 263,188 bytes larger. ONNX
Runtime and the 24,010,842-byte canary model remain AndroidTest-only and are
absent from the release APK.

`zipalign -c -P 16 4` passed. NDK 28.2 `llvm-objdump -p` produced this
ObjectBox LOAD-segment evidence:

| ABI | LOAD alignment | 16 KB compatible evidence |
| --- | ---: | --- |
| arm64-v8a | `2**14` for all LOAD segments | yes |
| x86_64 | `2**14` for all LOAD segments | yes |
| armeabi-v7a | `2**12` for all LOAD segments | no |
| x86 | `2**12` for all LOAD segments | no |

The release APK contains no ONNX native library because production embedding
is not provisioned. `tools/memory-vector/verify-release.ps1` reproduces unit,
release, device, ZIP, ELF, hash, ABI, and page-size evidence.

## Failure Matrix Coverage

| Required behavior | Current evidence |
| --- | --- |
| Daily-only text is excluded until distillation | JVM lexical and distillation tests |
| Deleted/stale/mismatched vectors fail closed | hybrid retriever JVM tests and ObjectBox device tests |
| Markdown changes while embedding | synchronizer revision-retry JVM test |
| Missing embedding never falls back to cloud | `NOT_PROVISIONED` capability and lexical production DI tests |
| ObjectBox missing/corrupt rebuilds only derived state | recovery JVM tests and ObjectBox corruption device test |
| Dimension/fingerprint/model/tokenizer/chunker mismatch | JVM and device fail-closed tests |
| PREPARED and rename-before-Room process death | real two-invocation process-death harness |
| ObjectBox transaction succeeds before Room completion | manifest fast-forward/retry JVM tests |
| Semantic file commit precedes claimed-batch completion | deterministic consolidation and distillation failpoint tests |
| Two rapid mutations and `A -> B -> A` | persistent-generation JVM tests |
| Worker races, leases, bounded retries, and disabled memory | scheduler/processor/worker JVM tests and Room claim device tests |
| Populated Room `14 -> 15` upgrade | real Room instrumentation migration |

## Open Release Gates

The schema 15 lexical delivery is valid, but production semantic recall is not
release-ready until all of these are satisfied:

1. Provide a reproducible official INT8 export, pinned exporter/quantizer
   toolchain, checksum-verified release lifecycle, 50 tokenizer golden
   fixtures, Recall@5 evidence, and production on-device embedding provider.
2. Run release ObjectBox open/write/query/close/reopen on a physical arm64
   device reporting `PAGE_SIZE=16384`.
3. Decide whether 32-bit ABIs remain supported. Their packaged ObjectBox ELF
   LOAD segments are currently 4 KB aligned even though both packaged 64-bit
   ABIs are 16 KB aligned.
4. Perform an Android backup/restore integration run. The canonical Markdown
   and Room locations are backup-eligible while ObjectBox is under
   `noBackupFilesDir`, and missing-store recovery is tested, but a real backup
   agent restore was not run in this slice.
5. Record physical-device query latency, indexing time, peak memory, process
   restart, cancellation, and concurrency measurements with the approved
   production model.

Until these gates pass, keep production recall lexical, keep ObjectBox
shadow/rebuildable, do not publish a production READY HNSW manifest, and do not
claim Task 8/schema 16 completion.
