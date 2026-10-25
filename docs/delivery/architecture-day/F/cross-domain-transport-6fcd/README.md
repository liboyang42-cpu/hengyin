# CrossDomain controlled transport OOM — SOURCE ONLY

Parent: `6fcdf747e17cb5094121f609ae33f7734fda2ae7`.
Branch: `codex/architecture-day-f-cross-domain-transport`.
Reported failure: run `40232916855`, job `121288370648`, artifact `12613917154`.

The supplied stack locates the suppressed cleanup OOM in the owned provider request decoder.
The original source intentionally holds four 16 MiB image POSTs before reading them. On release,
each handler did `readAllBytes -> new String(UTF_8) -> fastjson object tree`, simultaneously
retaining full byte, character and decoded image copies. The controlled producer emits one image
per request; the source does not show a recursive/repeated request generator. The large image is
the existing physical-worker lifetime witness, so its size and four concurrent requests remain.
This is separate from the primary transaction assertion owned by C.

## Exact source/caller boundary

| Path / call | Disposition |
| --- | --- |
| `business/it/CrossDomainTransportStage.java` (admin test sources) | Owned handler, bounded decoder and local executor queues changed. |
| `business/it/CrossDomainTransportStageTest.java` (same source root) | Seven new direct control methods, SOURCE only. |
| `CrossDomainFaultIsolationMySqlIT` -> `open/client/image/beginFault/finishFault/close` | Read only; all signatures, original case and assertions unchanged. C owns this file. |
| `CrossDomainStorageFixture.photo` -> real `PhotoCheckVisionService` -> real `OpenAiCompatibleLlmClient.chatVision` | Read only. Existing fixture supplies the image; client sends original `{model,messages}` JSON. C owns fixture. |
| `CrossDomainSubscriptionProvider` / `CrossDomainSchedulerStage` | Read only; no call to the new decoder, no hunk needed. |
| common `HttpUtils`, `AiHttpAdmission`, map client | Unchanged real transport/admission/physical cleanup path. |

## Source change

Use the Jackson core already supplied by common's existing Jackson databind dependency.
The input stream is capped at 17 MiB (original 16 MiB image plus bounded envelope headroom).
A streaming parser still validates the exact model, two message objects and full JSON syntax,
rejects duplicate root keys, unknown root fields, trailing documents and depth over 16.
Image/content strings are skipped through the parser, not materialized with `getText` or a tree.
Only the bounded model token is materialized (1024-character parser limit); field names are
limited to 64 characters. This is a controlled fixture envelope validator, not a new RPC protocol.

Original 8 caller / 4 provider workers remain. Their waiting queues are explicitly 8 / 4,
with rejection rather than unbounded retention; the loopback listener backlog is 8.
The decoder closes its input on every outcome. Existing barrier release, exchange close,
server stop, owned executor shutdown/termination and suppressed cleanup failure aggregation
remain. Malformed/over-budget input has one fixed safe exception and is recorded even in the
fault phase; it cannot fall through the pre-existing allowed cancellation IOException branch.
Parser messages/causes that can quote body content are not exposed. No heap/deadline increase,
response shortcut, provider retry, UNKNOWN conversion, SQL or economic identity change.

## New control SOURCE

1. Four concurrent original-size image envelopes, generated as bounded streams; consume every
   byte, close the stream, and constrain decoder read chunks without materializing the images.
2. Exact 17 MiB request passes; the next byte fails and closes the stream.
3. Wrong model/count, duplicate root key, trailing document, truncation and excess nesting fail.
4. Malformed body canary is absent from the fixed exception and cause.
5. Original worker counts and finite queue/rejection policy.
6. Real loopback malformed POST in the fault phase still fails owned cleanup with its protocol
   exception; the error is not swallowed as cancellation.
7. Real `OpenAiCompatibleLlmClient.chatVision` loopback request/response still produces the
   controlled parsed result, with exactly one observed POST.

The existing process-global resource-lock key is reused for these controls. They require root's
approved Java execution environment. No new SQL suite, registry count or workflow is required;
the ordinary `*Test` is discoverable by the existing backend test naming convention.

## Delivery / verification boundary

Decode `review.patch.b64` and apply once on the parent (or cherry-pick the commit, not both).
`PATCH.sha256` binds the decoded two-Java-file patch; `POSTIMAGES.sha256` binds every authored
source/document except itself. Root can serialize this delta alongside C's independent fix.

Actual performed checks: Git diff/application checks, file SHA-256, finite added-line secret
pattern check. Tests, parsing, compile, HTTP, Java, DB, CI and resource execution: **NOT_RUN**.
No claim of measured heap bounds or repaired main case until same-source root execution.
The original failure and cleanup failure remain evidence; the supplied MySQL/Redis CLEAN does
not establish transport acceptance. Root must verify the new controls and the complete original
CrossDomain case, preserving C's transaction/rollback/UNKNOWN assertions.
