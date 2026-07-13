# On-Device Vector Memory Artifact Contract

This is the immutable production artifact contract for the schema 15 on-device embedding implementation. It authorizes local provisioning and READY capability publication after runtime verification; production Hybrid recall remains subject to the separate shadow gate.

## Baseline

- Branch baseline: `e26c5912ede95b2dfe71f9bc89c856271eb51b71`.
- Debug APK: 86,879,589 bytes, SHA-256 `e2a355b7d5d7ec018b0abe7106218dc277ee7f1674e8a5e4d6689085ce88f617`.
- Release unsigned APK: 11,075,057 bytes, SHA-256 `7d6c97afb19e348740383830a979da412c1877dc1b196d901ccfd829a9a765e4`.
- Connected target: API 35 x86_64 emulator, reported ABI list `x86_64,arm64-v8a`, page size 4096.
- The emulator is not evidence for arm64 physical-device behavior or 16 KB page compatibility.

## Runtime And Store

- ObjectBox: `5.4.2`, upstream tag `V5.4.2` at `4de0fdfde28983162f820bbfdcd8397abb27fdb3`.
- ONNX Runtime Android: `1.27.0`, upstream tag at `8f0278c77bf44b0cc83c098c6c722b92a36ac4b5`.
- KSP is raised from `2.3.4` to `2.3.5`; `2.3.4` has the AGP 9 built-in Kotlin/legacy-kapt task cycle fixed by `google/ksp#2743`.
- ObjectBox runtime API is Apache-2.0. `objectbox-android-db` also declares the ObjectBox Binary License and OpenLDAP Public License 2.8. The Gradle plugin and processor are AGPL-3.0 build-time tools.
- ONNX Runtime is MIT licensed.
- The ObjectBox store is a separate derived store below `noBackupFilesDir`; Task 0 opens only a disposable canary directory.
- The production HNSW schema is fixed at 512 dimensions with cosine distance.

## Model Source

The tokenizer and model-card source is `BAAI/bge-small-zh-v1.5` at immutable revision `7999e1d3359715c523056ef9478215996d62a620`.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `model.safetensors` | 95,827,648 | `354763b9b1357bc9c44f62c6be2276321081ed2567773608c0d0785b61d5a026` |
| `README.md` / MIT model card | 27,670 | `c48a4eeea77f6b1d38b48ec1c5b8d4f86d5550cc43fa345a0db1b2ca1d082369` |
| `config.json` | 776 | `3853a7979202c348751b753e36f579c41d8da7d36af617d3d907e1fc9b441f2a` |
| `tokenizer.json` | 439,125 | `48cea5d44424912a6fd1ea647bf4fe50b55ab8b1e5879c3275f80e339e8fae26` |
| `tokenizer_config.json` | 367 | `e6f3b96db926a37d4039995fbf5ad17de158dfb8f6343d607e4dbaad18d75f5a` |
| `vocab.txt` | 109,540 | `45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c` |

The production ONNX binary is the exact companion-validated artifact from `Xenova/bge-small-zh-v1.5` revision `75c43b069aac4d136ba6bc1122f995fedcfd2781`:

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `onnx/model_quantized.onnx` | 24,010,842 | `15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc` |
| `quantize_config.json` | 674 | `2cc488b20fa05fe86aba2fdc2be44d24827e11e2b7c7a0753d1427da6797b46f` |

This contract deliberately treats the Xenova ONNX binary as an immutable upstream artifact identified by repository, revision, byte length, and SHA-256. It does not claim that this binary is a locally reproducible export of the BAAI safetensors checkpoint.

Expected tensors and retrieval semantics:

- Inputs: `input_ids`, `attention_mask`, `token_type_ids`; INT64 `[batch, sequence]`.
- Output: `last_hidden_state`; FLOAT `[batch, sequence, 512]`.
- Maximum input: 256 WordPiece tokens.
- Pooling: CLS (`last_hidden_state[:, 0]`).
- Normalization: L2.
- Query prefix: `为这个句子生成表示以用于检索相关文章：`.
- Document prefix: none.
- Distance: cosine.

## Delivery Choice

The selected lifecycle is a checksum-verified build-provisioned bundled asset:

1. `tools/memory-model/provision-bge-small-zh-v1.5-production.ps1` downloads only immutable revision URLs and verifies every byte length and SHA-256 before writing `app/src/main/assets/memory-model/bge-small-zh-v1.5/`.
2. Generated model assets are intentionally ignored by Git. The script and this immutable contract are tracked.
3. `:app:verifyProductionMemoryModelArtifacts` independently verifies all seven files. Every release build depends on that task and fails when an artifact is absent, truncated, replaced, or mismatched.
4. At runtime, the app verifies the packaged assets and atomically installs them below `noBackupFilesDir/memory_models/bge-small-zh-v1.5/15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc/`.
5. The installed files are verified again before ONNX Runtime opens a session. Missing, corrupt, or mismatched files keep `MemoryEmbeddingCapability` at `NOT_PROVISIONED` and never invoke a network, cloud embedding, or fake provider.
6. Only successful artifact verification, tokenizer construction, ONNX session initialization, 512-dimensional CLS extraction, and L2 normalization may transition the process-local capability to `READY`.

The release provisioning command is:

```powershell
.\tools\memory-model\provision-bge-small-zh-v1.5-production.ps1
.\gradlew.bat :app:verifyProductionMemoryModelArtifacts :app:assembleRelease
```

## Gate Status

The companion-validated Xenova model, BAAI tokenizer, and inference parameters are promoted by this contract for production provisioning. The Android 15 Experimental 16 KB x86_64 emulator gate passed with `PAGE_SIZE=16384`; both arm64-v8a and x86_64 ELF LOAD alignment checks passed, so page-size compatibility is no longer blocked. A real ARM64 device remains a separate final performance and OEM-environment supplement, not a page-size or provisioning prerequisite.

Production Hybrid DI still requires the independent shadow gate: ordinary recall must exclude daily notes, stale vectors must fail closed, deleted content must become immediately ineligible, and lexical fallback must remain permanent. Schema 16 cleanup is not authorized by this contract.

## Task 0 Verification

- `:app:testDebugUnitTest --tests "*Memory*"`, debug compilation, debug assembly, release R8 assembly, and the vector canary instrumentation package passed.
- The API 35 x86_64 emulator opened, wrote, queried, closed, and reopened the 512-dimension ObjectBox HNSW store below `noBackupFilesDir`.
- The same emulator verified the pinned model SHA-256, created an ONNX Runtime session, ran all three INT64 inputs, and produced a finite normalized 512-dimension CLS embedding.
- Post-ObjectBox debug APK: 94,975,986 bytes, SHA-256 `466ec3580f5de2dcc3cae150a2ada0a7cce7228bb2b7e2908154521745d1edf2`.
- Post-ObjectBox release unsigned APK: 20,734,390 bytes, SHA-256 `5fe5da2838881021a10b63e1632b6ada3233b1eedaf45fe71ca5f3379fd684ae`.
- Historical Task 0 release delta from the recorded baseline was +8,096,397 bytes debug and +9,659,333 bytes release; ONNX Runtime was still AndroidTest-only at that stage. The production evidence is recorded in `on-device-vector-memory-readiness.md`.
- `zipalign -c -P 16 -v 4` passed for the release APK.
- `llvm-objdump -p` reports ObjectBox LOAD alignment `2**14` for arm64-v8a and x86_64, and `2**12` for armeabi-v7a and x86.
- The connected emulator reports page size 4096. Real arm64 process-restart, performance, cancellation/concurrency, and 16 KB device checks remain unverified and keep production hybrid disabled.
