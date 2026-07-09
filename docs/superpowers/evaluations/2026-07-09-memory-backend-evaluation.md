# OpenClaw-Style Memory Backend Evaluation

Date: 2026-07-09

## Recommendation

Keep ChatWithChat local-only by default for the Android MVP:

- Markdown remains the source of truth under app-private storage.
- Room remains the rebuildable local index and maintenance queue.
- No sidecar, cloud memory account, Python daemon, Milvus, Node process, or external provider is required for normal mobile use.
- Revisit external backends only after local Markdown recall/write/maintenance has enough real usage data to expose a concrete quality or scale gap.

## Comparison

| Option | What It Solves Beyond Local Markdown + Room | Markdown Source Of Truth | Runtime Requirements | Privacy / Offline / Recovery Fit | Android Packaging Impact | Recommendation |
| --- | --- | --- | --- | --- | --- | --- |
| Local-only Markdown + Room | Baseline durable memory, inspectable files, rebuildable index, app-private privacy | Yes | None beyond APK | Best offline behavior; deletion/export are local file operations; index can be rebuilt | Lowest battery/network/security risk | Default |
| QMD | Better retrieval quality through BM25, vector search, and LLM reranking over Markdown/docs | Yes, as an indexed corpus | Node / local model / companion service style runtime | Local-first, but recovery and model/runtime health become sidecar concerns | Too heavy to bundle in Android MVP | Not now; consider desktop/server sidecar later |
| memsearch | Cross-agent memory with Markdown plus hybrid/vector retrieval | Yes, Markdown-backed | Python library/service path and Milvus/vector infrastructure in common setups | Strong transparency story, but sync, partial failure, and service availability need product design | Too heavy for pure APK-native memory | Not now; useful reference for future self-host backend |
| Mem0 / provider-style service | Managed semantic memory, API abstractions, external retrieval | Not inherently; depends on provider | Cloud or self-host service, API key/account, network | Offline behavior and deletion/export depend on provider; privacy policy and account model matter | Adds network, security, account, and rollback burden | Not now for MVP |
| Hermes-style external provider | Clean provider abstraction while built-in local memory remains active | Built-in memory can stay local; external provider varies | Plugin/provider runtime | Good architectural pattern: additive provider, not replacement | Plugin/provider discovery and mobile runtime need separate design | Reference only for a future `MemoryBackend` interface |

## Questions Answered

- Exact unsolved problem: none yet proven in ChatWithChat. Local Markdown + Room search is enough to validate product behavior, privacy expectations, repair flow, and UI before optimizing retrieval quality.
- Source of truth: QMD and memsearch are useful because they can preserve Markdown as primary storage. Mem0/provider-style services should not become the source of truth unless export/deletion parity is proven.
- Server/account/process: QMD and memsearch imply companion/runtime complexity; Mem0/provider services imply account/API/network complexity.
- Deletion/privacy/audit/export: local-only is strongest because the user-visible file is the durable artifact. External options need explicit delete propagation, audit logs, export, and failed-sync repair.
- Offline behavior: local-only works offline. External providers must degrade to local memory without blocking chat.
- Partial failure: local-only can retry from `memory_maintenance_job` and rebuild indexes. External backends would need idempotent sync jobs and conflict handling.
- Disable behavior: any future backend must be additive; disabling it must leave local Markdown recall and repair intact.
- Android impact: external backends add battery, background networking, credentials, sandboxing, and dependency size risks that are not justified for the MVP.

## Not Now

- Do not bundle QMD, node-llama-cpp, GGUF models, or a Node sidecar in the Android app.
- Do not add memsearch, Milvus, Python services, or vector database runtimes to the APK.
- Do not add Mem0 or cloud memory as the default memory system.
- Do not replace local Markdown with a provider-owned memory store.
- Do not add provider sync until local deletion/export/retry semantics are stable.

## Future Backend Plan Shape

If local search quality becomes the limiting factor, add a separate `MemoryBackend` interface with:

- Local Markdown as required primary storage.
- Optional backend sync as a retryable maintenance job family.
- Per-backend enable/disable setting.
- Credential storage and clear network/privacy copy.
- Idempotent push/pull with conflict handling and delete propagation.
- Full rollback to local-only mode.
- Tests for offline fallback, failed sync retry, duplicate prevention, deletion, export, and provider disable.

## Sources

- QMD: https://github.com/tobi/qmd
- memsearch: https://github.com/zilliztech/memsearch and https://zilliztech.github.io/memsearch/
- Mem0: https://github.com/mem0ai/mem0 and https://mem0.ai/
- Hermes Agent memory providers: https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/features/memory-providers.md
