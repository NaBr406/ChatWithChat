# Native tool retry duplicate output note

Date: 2026-09-10

Status: confirmed path, not fixed

## Symptom

Occasionally one user turn shows two different assistant answers in the same
assistant bubble. The first answer looks truncated, then the second answer is
appended immediately after it.

## Confirmed path

This matches the native tool-call recovery path:

1. A native tool round streams visible text through `ApiState.Success`.
2. `handleStates` appends that text to the same `StreamingMessageBuffer` for
   the current assistant message.
3. The round finishes without a parsable tool call, but
   `LostToolCallEvidence.isLostToolCall` is true because markup leaked, the
   provider claimed `tool_calls`, or the response was truncated.
4. `MAX_LOST_TOOL_CALL_RECOVERIES` still allows one recovery, so the loop emits
   `LOST_TOOL_CALL_RECOVERY_INSTRUCTION` and continues with another provider
   request.
5. The replacement round streams its answer into the same assistant slot.

Relevant code:

- `ChatRepositoryImpl.completeChatWithOpenAIResponsesNativeToolLoop`
- `ChatRepositoryImpl.completeChatWithOpenAIChatCompletionsNativeToolLoop`
- `ChatRepositoryImpl.completeChatWithAnthropicNativeToolLoop`
- `ChatRepositoryImpl.completeChatWithGoogleNativeToolLoop`
- `LostToolCallEvidence`
- `ApiStateFlowExtensions.handleStates`
- `StreamingMessageBuffer`

The recovery retry intentionally does not emit a tool boundary. Therefore the
second round is appended directly, without the paragraph separation used between
successful tool rounds.

## Rejected approach

Do not fix this by deleting or rewriting text that was already published to the
UI. That breaks the append-only streaming contract and makes the visible stream,
animation state, and persisted content harder to reason about.

## Direction for a future fix

Any future fix should establish the retry boundary before publishing, or model
the failed round as a provisional output that can be resolved without mutating
already-published text.

Possible directions to evaluate later:

- Buffer a native tool round's visible answer until the round outcome is known.
- Represent recovery as an explicit turn/revision boundary rather than appending
  to the current streaming buffer.
- Suppress only accidental pre-tool prose when the round is known to be a lost
  tool call, while preserving normal streamed output.

## Verification gap

There is currently no regression test asserting that a recovered lost-tool-call
round and its replacement do not concatenate in one assistant message.
