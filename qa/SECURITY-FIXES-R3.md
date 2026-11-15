# r3 security fixes: finding → fix → regression test

Source report: `origin/qa/admin-security-r3:qa/ADMIN-SECURITY-REPORT.md`. Branch: `fix/security-r3`.

| ID | Fix | Regression test |
|---|---|---|
| CRIT-S1 | `ExternalGameMonetizationService`: no longer accepts the nonce-keyed HMAC. A result counts as attested only when a server-side game authority signs it (per-game secret or platform Ed25519). Duration is measured on the server, and sessions expire after 2h. | `ExternalGameSecurityR3Test` |
| CRIT-S2 | `SpringExternalGameNodeBridge`: runs resolveCtx/leadGate/routeAccess/unlockGate, needs an in-progress run, takes the topic from the server-side binding, and grants `XP_BADGE_ONLY`. | `SpringExternalGameNodeBridgeSecurityTest` |
| HIGH-S1 | Standalone pass XP is idempotent per session, granted only on the first pass per game per day, with a daily cap of 30. | `SpringExternalGameNodeBridgeSecurityTest` |
| HIGH-S2 | TEST_WALLET and confirm-test need the TEST-only `test-wallet-enabled` flag, and debit the buyer as a pair with the seller credit. | `ExternalGameSecurityR3Test`, `ExternalGameMonetizationMysqlIntegrationTest` |
| HIGH-S3 | `InGameChannelService.resolveTarget`: the topic/session channel requires the topic owner or a paid registration, otherwise 403. | `InGameChannelSecurityR3Test#highS3_*` |
| HIGH-S4 | `ApiRoamController.checkin`: the participating branch posts the server-side node title, never the client `name`. One event per member, node and day (Redis, fails closed). `@RateLimiter(MEMBER, 20/min)`. | `RoamCheckinChannelEventSecurityTest` |
| HIGH-S5 | City dice receipt is `city:{memberId}:{sha256(boardId:requestId)}:{tag}`. `GameRandom` rejects receipts over 64 chars and checks member and ref on replay. | `CityDiceReceiptTest`, `GameRandomTest` |
| HIGH-S6 | `executeOne` erases or tombstones the r3 per-user tables. | `AccountDeregistrationR3ErasureTest`, `AccountDeregistrationR3ErasureMyBatisTest` |
| HIGH-S7 | `cleanRoamLocations` coarsens or nulls territory check-in, audit and claim-evidence GPS after 30 days. | `CityLocationRetentionServiceTest`, `DataRetentionServiceImplTest` |
| HIGH-S8 | Territory and Monopoly lat/lng commands call `requireCurrentAgreement`. | `CityLocationConsentGateTest` |
| HIGH-S9 | `postRoamEvent` posts only for members with presence opt-in (`roam_session.presence_visible=1`), signs with the masked id, and doesn't auto-join the actor. `actorLabel` never falls back to `ums_member.name`. | `InGameChannelSecurityR3Test#highS9_*` |
| MED-S6 | `getSummary` for non-members returns only `exists`, title and `unread=0`, with no conversation id or last message. | `InGameChannelSecurityR3Test#medS6_*` |
