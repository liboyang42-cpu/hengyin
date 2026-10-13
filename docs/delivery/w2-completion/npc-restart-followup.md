# W2 F0231 — NPC original-request recovery after process restart

Base: accepted public candidate `efba970132d25c3285071e6e1ec909e013b83804`.
Scope: the shared NPC conversation component, play-page NPC methods/lifecycle, one metadata helper, and the separately authorized read-only NPC status endpoint with focused tests.

## Delivered behavior

- Before a text request or voice upload, persist only `requestId`, account ID, NPC scope and object ID, plus the play activity/topic context. No question text, ASR text, audio path/URL, token, or model output is stored by this helper. A write/read failure blocks the new billable request; invalid or duplicate pending records fail closed.
- Storage keys are isolated by account. Logout/account changes reset the visible conversation and invalidate late replies; they retain A's unresolved metadata so A -> logout/B -> A can recover the same original UUID. B neither restores nor removes A's request.
- A recreated component or reopened NPC panel restores only matching account/scope/object/play context and queries the original UUID. Manual “恢复原问题” also uses GET. Neither path resends text, uploads audio again, invokes a model, or re-synthesizes voice.
- Unknown, missing, unavailable, malformed, conflicting or access-denied readback keeps the original pending lock. A matching, valid persisted terminal response clears only that request. Cleanup failure leaves durable metadata, preventing a later send from silently making a second request.
- Closed/hidden pages, detached components, changed accounts/objects/play contexts and obsolete request generations cannot render late replies or delete pending records. NPC animation callbacks also respect page closure.

## Read-only server contract

`GET /api/ai/npc/request-status?requestId=<original UUID>&scope=<shop|merchant|roam>&bizId=<original node|merchant|roam session>`

Identity comes from `getAppUserId()` only. The endpoint uses the existing independent NPC flags; shop reads reuse `gateShopChat`, including current pass/registration expiry; roam reads require the existing owned active session. Existing POST/auth/RBAC policies are unchanged.

`NpcChatService.requestStatus(String requestId, Long userId, String scope, Long bizId)` reuses `NpcChatRequestMapper.selectByUserAndRequestId`. It compares the existing encoded session key: shop `-nodeId`, merchant `-(MAX_CHAT_ENTITY_ID + merchantId)`, roam `sessionId`. No schema, mapper SQL, dependency, permission or provider contract was added.

- Matching rows return existing `NpcChatResp` terminal text, or `PROCESSING`.
- Missing/cross-owner/cross-object rows return the same non-disclosing `PROCESSING / REQUEST_NOT_FOUND`; this never proves that a second generation is safe.
- Query errors/corrupt stored outcomes return `REQUEST_STATE_UNAVAILABLE`; invalid envelopes are rejected without querying or generating.
- Status performs no insert, lease expiry/renewal, quota mutation, model call, ASR or TTS. It returns no raw request row, hash, lease token or original question.
- Audio URLs are not persisted in the existing request row. Recovered terminal text is readable; audio remains unavailable. There is no automatic re-synthesis or new-charge entry.

## Validation

- Backend test tree: `a49d2017782f603051f93b62d50052ddb3a0e962`. Final Node source/test tree (before this report): `d2098f8e80ee3e635e716f6538a0025b4473524f`; the later difference is stricter JavaScript storage-shape validation/tests only, with identical backend source.
- Final backend archive SHA-256: `049eb12ce2fcdb629865cb10f7fc9cb84286eeecb8ed5dbcc39de17202c42418`; exact compressed public source transferred to the approved VPS, including SQL fixtures.
- Node: **137/137**, 8 files, no failures/cancellations/skips; includes helper corruption/quota/isolation checks, existing consumers, recreated play pages, real child Node process exit/restart, A/B/A, same UUID GET-only recovery, no second POST/upload, terminal cleanup, and delayed owner/object/page negative controls.
- Node command: `node --test --test-concurrency=4` with `tests/unit/{npc-pending-request,npc-chat-component,play-request-recovery,play-free-explore-detail-contract,merchant-node-npc-entry,npc-roam-gate,roam-npc-coordinator,remaining-scene-state-contract}.test.js`.
- JS-SCOPE: 559 production JS files, unresolved identifiers 0, missing exports 0; staged diff whitespace check and staged secret scan passed.
- VPS final: **65/65**, 10 test classes, Failures/Errors/Skipped all 0, exit 0, `BUILD SUCCESS`; backend compile plus testCompile of 1290 test sources passed. Original-request status was read from actual task MySQL via the existing mapper after a new service/session was created; the same UUID/safe text returned, other owner/node/scope returned unknown, and the row count stayed 1. This is an isolated SQL fixture, not a live user/model.
- Exact VPS suites: `NpcRequestStatusTest,ApiAiNpcRequestStatusTest,NpcRequestStatusMyBatisIT,NpcChatServiceTest,ApiAiNpcPassExpiryTest,NpcChatFlagGateTest,MerchantNpcChatFlagGateTest,ApiAiNpcVoiceChatFormatTest,NpcCurrentRegistrationMySqlIT,W2PlayerRecoveryMyBatisIT`. Command: `mvn -B -pl chengyinhub-admin -am test -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Xmx320m -Dchengyin.npc.expiry.mysql.it=true -Dtest=<above suites>`, with the coordinator's explicit test environment and loopback database target.
- First actual VPS run compiled all backend and 1290 test sources; 65 tests ran with 63 pass / 2 GET parameter-binding errors / 0 skips. Explicit `@RequestParam` names fix the endpoint binding; no fixture suppression or test bypass.
- Preserved coordinator-supplied hook: `/tmp/w2-followup-hooks-npc`, SHA-256 `b3c3f0f87b3ce393b3e67d6a788ee13e680ed58bf0d9c9c792f3eaeed36f3d0b`. Commit uses the per-command hook path, with existing staged syntax/migration/production/DS gates and VPS staged-backend compilation.

VPS uses only `docker --context chengyin-vps-sandbox`, task runner `cy-w2-followup-20261013-test`, work `/w2/npc`, serialized `flock /tmp/w2-java.lock`, `MAVEN_OPTS=-Xmx448m`, Surefire `-Xmx320m`. Test credentials are injected by Docker CLI `--env-file`; no values are printed or committed. A briefly copied task fixture file was mode 600 and removed exactly; no shared resource was removed.

## Residual acceptance limits

- This completes the bounded client/server original-request recovery implementation. Node lifecycle/network stubs and isolated MySQL fixtures are evidence for the code contract, not real WeChat identities, live HTTP business acceptance or vendor billing.
- Coordinator retains shared DevTools/offline visual checks, integrated gates and real-account/device testing. No shared IDE window was borrowed, no CI/push/merge/deploy/upload/production operation or paid generation was performed.
- An ASR task interrupted before `npc_chat_request` is created still has no vendor-status contract; the original UUID remains unknown and locked. Persisted `PROCESSING`/missing results do not trigger provider recovery or a second model request. Vendor recovery/manual resolution, active regeneration fees and audio re-synthesis remain outside this approved scope.
- Existing rows cannot recover audio or reconstruct original input; earlier versions that never persisted an original UUID cannot have that UUID invented. Deleted app storage/uninstallation cannot be repaired by this local metadata helper.
- The 22 deferred cloud/local features remain absent. Shared action-ledger, UI baseline, root README and unrelated code were not changed; no private history/refs/backups were imported.
