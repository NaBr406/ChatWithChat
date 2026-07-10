# On-Device Vector Memory Readiness

Markdown remains the source of truth. `MemoryRetriever` is the replaceable read boundary, and Room remains a rebuildable operational index. The production strategy is lexical only; this change adds no embedding model, vector dependency, or cloud embedding request.

## Candidate Backends

| Option | Best fit | ABI and app size | Model lifecycle | Rebuild and update behavior |
|---|---|---|---|---|
| Small-corpus brute force | Hundreds or a few thousand memory chunks | Kotlin storage/math can avoid native ABIs; app-size cost is mainly the embedding model and runtime | Bundle a small quantized model or download it explicitly, version it, and invalidate vectors when the model changes | Re-embed only `changedChunkIds`; full scans are acceptable at small scale and are the simplest correctness baseline |
| `sqlite-vec` | Moderate corpora where SQL filtering and vector search should share one store | Requires Android native builds for supported ABIs and increases packaging/test surface | The app still owns model download, version, warm-up, and deletion; the extension stores vectors but does not create them | Keep vectors keyed by stable `chunkId` and `contentHash`; update changed rows transactionally and rebuild the table when dimensions/model version change |
| Embedded ANN library | Larger corpora where brute force is measurably too slow | Usually the highest ABI, NDK, and binary-size cost; library support and persistence format must be validated on Android | Requires the same model lifecycle plus ANN index-version compatibility | Incremental updates may be limited or tombstone-based; corruption recovery must rebuild the ANN index from Markdown-derived Room chunks |

## Integration Contract

- `MemoryRetrievalRequest.strategy` selects lexical, vector, or hybrid behavior without changing `ChatViewModel`, `ChatRepositoryImpl`, `MemoryPromptBuilder`, or Markdown storage.
- `MemoryRetrievalResult` carries stable chunk/entry identity, `contentHash`, lexical/vector score provenance, and one fused score.
- Index rebuilds report `changedChunkIds`; a future embedding worker can compare content hashes and update only changed vectors.
- Vector data is disposable. Deleting it or changing models must be recoverable by parsing Markdown and rebuilding Room chunks first.
- Any future model download requires an explicit product decision for size, network conditions, storage, versioning, and removal. It must not silently fall back to a cloud embedding API.
