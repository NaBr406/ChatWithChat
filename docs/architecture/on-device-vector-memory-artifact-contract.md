# On-Device Vector Memory Artifact Contract

This contract records the Task 0 dependency and model gate for the first schema 15 delivery. It does not enable production hybrid recall.

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
- The fixed candidate HNSW schema is 512 dimensions with cosine distance. No production READY manifest is published in Task 0.

## Model Source

The candidate model is `BAAI/bge-small-zh-v1.5` at immutable revision `7999e1d3359715c523056ef9478215996d62a620`.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `model.safetensors` | 95,827,648 | `354763b9b1357bc9c44f62c6be2276321081ed2567773608c0d0785b61d5a026` |
| `README.md` / MIT model card | 27,670 | `c48a4eeea77f6b1d38b48ec1c5b8d4f86d5550cc43fa345a0db1b2ca1d082369` |
| `config.json` | 776 | `3853a7979202c348751b753e36f579c41d8da7d36af617d3d907e1fc9b441f2a` |
| `tokenizer.json` | 439,125 | `48cea5d44424912a6fd1ea647bf4fe50b55ab8b1e5879c3275f80e339e8fae26` |
| `tokenizer_config.json` | 367 | `e6f3b96db926a37d4039995fbf5ad17de158dfb8f6343d607e4dbaad18d75f5a` |
| `vocab.txt` | 109,540 | `45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c` |

The feasibility artifact is `Xenova/bge-small-zh-v1.5` revision `75c43b069aac4d136ba6bc1122f995fedcfd2781`:

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `onnx/model_quantized.onnx` | 24,010,842 | `15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc` |
| `quantize_config.json` | 674 | `2cc488b20fa05fe86aba2fdc2be44d24827e11e2b7c7a0753d1427da6797b46f` |

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

The selected lifecycle is a checksum-verified build-provisioned bundled asset. `tools/memory-model/provision-bge-small-zh-v1.5-canary.ps1` obtains the pinned feasibility artifacts without a mutable URL and puts them only in an ignored instrumentation asset directory.

A future release-hybrid provisioning task must:

1. Accept only an immutable official export whose exporter, quantizer, opset, and dependency versions are pinned.
2. Verify every artifact size and SHA-256 before adding generated release assets.
3. Fail the release build when hybrid release DI is enabled and any artifact is absent or mismatched.
4. Copy the verified model atomically to `noBackupFilesDir/memory_models/bge-small-zh-v1.5/<model-sha256>/model.onnx`.
5. Re-verify the installed model before opening it. Deletion returns the provider to `UNAVAILABLE`; it never triggers a cloud or fake fallback.

## Gate Status

ObjectBox and ONNX build/native canaries may proceed. Production embedding and READY HNSW publication remain blocked because the pinned Xenova INT8 artifact does not record a complete reproducible exporter toolchain, 50 tokenizer golden fixtures and Recall@5 evidence are not yet present, and no API 31+ arm64 physical 16 KB device is connected. Until all gates pass, production recall remains current-Markdown lexical and ObjectBox remains shadow-only.

## Task 0 Verification

- `:app:testDebugUnitTest --tests "*Memory*"`, debug compilation, debug assembly, release R8 assembly, and the vector canary instrumentation package passed.
- The API 35 x86_64 emulator opened, wrote, queried, closed, and reopened the 512-dimension ObjectBox HNSW store below `noBackupFilesDir`.
- The same emulator verified the pinned model SHA-256, created an ONNX Runtime session, ran all three INT64 inputs, and produced a finite normalized 512-dimension CLS embedding.
- Post-ObjectBox debug APK: 94,975,986 bytes, SHA-256 `466ec3580f5de2dcc3cae150a2ada0a7cce7228bb2b7e2908154521745d1edf2`.
- Post-ObjectBox release unsigned APK: 20,734,390 bytes, SHA-256 `5fe5da2838881021a10b63e1632b6ada3233b1eedaf45fe71ca5f3379fd684ae`.
- Release delta from the recorded baseline: +8,096,397 bytes debug and +9,659,333 bytes release. ONNX Runtime remains AndroidTest-only and is not part of the release delta.
- `zipalign -c -P 16 -v 4` passed for the release APK.
- `llvm-objdump -p` reports ObjectBox LOAD alignment `2**14` for arm64-v8a and x86_64, and `2**12` for armeabi-v7a and x86.
- The connected emulator reports page size 4096. Real arm64 process-restart, performance, cancellation/concurrency, and 16 KB device checks remain unverified and keep production hybrid disabled.
