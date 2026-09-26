# Memory generation optimization verification

Branch: `codex/memory-generation-optimization-20260926`

## Production and migration checks

- Real export SHA-256: `ba688e4b37429f7aa3ffdff6e3c03214d945a13c56a044ab299ff37a2ca443a1`. The source file was read without modification.
- A reviewed addressing split and project text review were applied to a temporary app-managed copy. The migrated corpus SHA-256 was `3fcb79a202a2a0b586773071c535743a3110659440c4f9797f76a0fa56f0fca5`.
- The post-migration canonical audit is healthy: no active duplicate identity, unresolved alias, missing current key, obsolete active projection, or unreviewed addressing bundle.
- CAS commit, reread, replay with zero mutations, backup restoration, repeat rollback, and conflicting-source rejection passed in `MemoryMutationCoordinatorTest`. The original export was byte-identical afterward. This is a copy migration test; the emulator's own user memory was not replaced.
- The migrated project description is a reviewed stable project fact. The original mixed description remains recoverable as obsolete history.

## Generation and answer evaluation

- Four provider generation recordings use current production request payloads; request SHA-256 values match their recording manifest. The production parser, validation, commit, and replay tests pass for addressing, transient observations, project context, and correction. The transient case creates no long-term entry; the correction converges to one current entry.
- The four generation scenarios accept four long-term facts, all supported by the supplied user turns (4/4 precision on this small fixed set); unnecessary long-term creation, active duplicates, inferred Core entries, and private exposure are zero in these checks. This is not a direct percentage comparison with the broader P0 fixture.
- Eight answer replay cases used the same questions for baseline and optimized contexts. DeepSeek `deepseek-flash` passed 4/8 baseline and 8/8 optimized. Five cases use the real export; stale version, completed project, and editor correction are controlled fixtures. The answer check covers recalled facts, obsolete and stale status, private context, and unsupported extensions, but is a bounded scenario evaluation, not a claim about every future answer.
- The old export was intentionally recalled without the new query freshness filter for the baseline. This comparison measures the migration and recall policy together.
- Fixed P0 fixture baseline: durable precision 0.8571, unnecessary creation 0.4, active duplicate rate 0.1, stale memory rate 0.1, evidence coverage 1.0, inferred Core rate 0.2, private leakage 0, recall usefulness 1.0. These figures describe the old fixture; the provider replay and migration checks above are the post-change evidence.

## Build and device gates

- `:app:compileDebugKotlin` and `test`: passed with the real export path set; 192 Debug unit test suites, 1854 tests, zero failures, errors, or skips.
- Targeted `connectedDebugAndroidTest` on API 35 16 KB emulator: seven tests passed, zero failures or skips. Covers Core projection, backup restore, and schema-17 startup recovery.
- `git diff --check`: passed. The workspace also contains unrelated UI and export edits, which are outside this verification and its commits.
- Standalone ktlint 1.3.1 was not available in this environment; formatting was checked against the repository conventions and `git diff --check`, but the CI ktlint gate remains unverified locally.
