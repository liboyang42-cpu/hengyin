# Acceptance r3: fixes for the confirmed defects

Source: `origin/qa/acceptance-r3:qa/acceptance/REPORT.md`, section 3 ("Confirmed defects").

- Backend branch: `fix/acceptance-r3`, cut from `origin/integration/backend-app-r3`.
- App branch: `ui/h-acceptance-fixes` in `chengyin-app` (native iOS), cut from `origin/roadmap/base-r6`. Commit `e83c99ca`.

The tester mapped A1-A4 to the old Flutter client (`*.dart`). The shipping client is the native iOS app, so each A item was checked again in the Swift code (`Core/`, `App/`).

Nothing was deployed. No migration was added. Ledger and gitleaks files were not touched. The only currency is 积分.

## Backend

| ID | Fix | Regression test |
|---|---|---|
| B1 | `TopicRouteRuntimeServiceImpl.toLinearView` now applies the stored `session.status`. A COMPLETED (1) or ENDED (2) linear session reports `COMPLETED`/`ENDED`, has no current or recommended node, and turns every PLAYABLE node into DISCOVERED_LOCKED. `PlayBasicIntentApplication.basicActionContext` no longer hands out a finished personal session: it returns 409 `INTENT_SESSION_ENDED` "本局已结束". | `TopicRouteRuntimeServiceImplTest.finishedLinearJourneySessionIsNeverReportedActive`, `PlayCommandIntentTest.actionContextRefusesAFinishedPersonalRouteSession` |
| B2 | When a bank withdrawal exceeds the available balance, the service throws `WithdrawalInsufficientBalanceException`, a `FundActionPreflightException` with a business code. `/api/withdrawal/create` returns 409 with `businessCode` `WITHDRAWAL_INSUFFICIENT_BALANCE`, or `PLAYER_WITHDRAWAL_COOLDOWN` when the cooldown is the cause, and a message that contains "余额不足". It no longer returns code 500 "请稍后重试". The machine code stays in `getMessage()` for monitoring, but the user never sees it. | `ApiWithdrawalInsufficientBalanceTest` (3), `BothWithdrawalLegsRejectPlayerCooldownTest.bankLegOverBalanceCarriesAStableBusinessCode` |
| B3 | `RoamSessionWriteService` now accepts a `collectedAt` that is up to the tolerance outside `[startedAt, min(finishedAt, now)]`, and clamps the stored value to the trip bounds. Times beyond the tolerance are still rejected. The tolerance is set by `feature.roam.collected-at-skew-seconds` (env `CHENGYIN_ROAM_COLLECTED_AT_SKEW_SECONDS`), defaults to 120 s and is capped at 600 s. | `RoamSessionArchiveTest.collectedAtToleratesClockSkewAndClampsIntoTheTrip`, `RoamSessionArchiveTest.defaultToleranceArchivesSlightlySkewedTilesAtTheTripBoundary` |
| B4 | TEST only. `ApiLoginController.getOpenID` sends `e2e-code-*` codes to `E2eTestWeChatMiniSessionService` when that bean exists. The bean is created only under profile `e2e-test` with `chengyin.e2e.test-providers=true`, which the `druid,e2e-test` TEST start sets. In the default and production profiles the bean does not exist and login behaves as before. | `ApiLoginE2eSessionStubTest` (3), `E2eTestProvidersProfileTest` (still green) |
| B5 | `BasicTextAnswer.expectedAnswer` reads `question_answer` first and falls back to `advanced_config_json.qa.answerText`. It is used in the live answer path, in the publish freeze (`nodeAnswers.questionAnswer`) and in the reveal text. If neither field holds an answer, `play/answer` returns `CONFIG_INVALID` "该节点答案未配置". It does not return "答案不正确"/`answerWrong`. | `BasicTextAnswerExpectedAnswerTest` (4, including the seed 992101 config and the freeze path) |
| B6 | Root cause: `ClubMemberServiceImpl.quitMemberAndLeaveGroup` and the ban path `ClubGovernanceServiceImpl.removeMemberForBan` deleted the `club_member` row whatever its status and always decremented `member_count`. Withdrawing a pending (0) or rejected (2) application, which was never counted, therefore lowered the count. The decrement now happens only when the removed row was an active member (status 1). As a guard, `ClubMapper.updateMemberCount` uses `greatest(0, …)`, and the IM counterparty, `PublicClubVO` and `ClubInviteCandidateVO` clamp the count at 0. Existing negative rows read as 0. To correct the stored value, recount `member_count` from `club_member` (status=1, del_flag=0) in a separate, reviewed data fix. | `ClubLifecycleServiceTest.removingAPendingOrRejectedApplicationNeverDecrementsMemberCount`, `ImServiceImplTest.groupConversationNeverExposesANegativeMemberCount`, `ClubGovernanceServiceImplTest` (fixture now states the member is active) |
| B7 | `AccountDeregistrationServiceImpl.status` returns `NORMAL` with `executeAfter = null` when the latest application is `CANCELLED`. `cancel()` still returns `CANCELLED` as the action's outcome. | `AccountDeregistrationServiceImplTest.statusAfterCancelIsNormalWithoutTheOldExecuteAfter`; `PrivacyVisibilityExportMySqlIT` was updated to expect the new contract (it needs MySQL) |
| B8 | `GlobalExceptionHandler`: a wrong method now returns code 405, `errorCode` `METHOD_NOT_ALLOWED` and "请求方式不正确，请更新到最新版本后重试". A wrong content type (`HttpMediaTypeNotSupportedException`) now returns code 415, `errorCode` `UNSUPPORTED_MEDIA_TYPE` and "请求格式不正确，请更新到最新版本后重试". Neither response includes Spring's own text. | `GlobalExceptionHandlerErrorMappingTest.methodNotSupported_returns405BusinessCodeWithFriendlyMessage`, `…mediaTypeNotSupported_returns415BusinessCodeWithFriendlyMessage` |

Notes:

- B1: a finished run is now reported honestly. Whether a player can start a new run of the same linear route after finishing it is a product decision and is out of scope here.
- B5: the seed file (`docs/delivery/e2e-seed/seed.sql`) was not changed, because the backend now reads `qa.answerText`. Live sessions that were frozen before this fix with an empty answer get `CONFIG_INVALID`, not "答案不正确". New sessions freeze the fallback answer.

### Maven

- Targeted: `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -pl chengyinhub-admin test -Dtest=…` on every test class listed above and on the neighbouring suites (`ApiLoginController*Test`, `PuzzlePlayServiceImplTest`, `ClubMemberImCleanupTransactionTest`, `ImConversationBatchReadTest`). All green.
- Full: `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -fae test`. 12,694 tests, 0 failures, 34 errors, 563 skipped. All 34 errors are in 14 `com.chengyinhub.business.it.*MyBatisTest` classes. Each one needs a live MySQL and fails with `CommunicationsException: Communications link failure`, because this container has no database. They are environmental, not caused by this change, and need to run on the TEST VPS.

## App (native iOS, `chengyin-app`)

| ID | Swift check on `origin/roadmap/base-r6` | Result |
|---|---|---|
| A1 | Main play path: `PlayExperienceCoordinator` reads `GET api/play/action-context` and sends `routeActionId`/`expectedRouteVersion`/`routeSessionId`. However, the scan (`play/checkin`) request dropped `nodeId`, and the backend refuses a checkin without one (`basicIntentScope`). `PlayService.answer` (the older linear reader) and `PlayStoreDayService` checkin/answer/photo sent none of the three fields. | **Fixed.** A checkin bound to the action context now also sends `nodeId`; older unbound journals replay byte-identical. `PlayService.answer` and `PlayStoreDayService` proof writes read the action context first and send the run identity (plus `runtimeInstance`/`authorityEpoch` for shared runtimes). Nothing is written if that read fails. Tests: `PlayExperienceTests.testContextBoundScanCarriesNodeAndRunIdentity`, `PlayServiceTests.testAnswerReadsActionContextAndSendsRunIdentity`, `…testSharedRuntimeContextAddsInstanceAndEpoch`, `…testFailedActionContextSendsNoAnswer`, `PlayStoreDayTests.testRequestsMatchMiniProgram` (updated), `…testProofWriteWithoutRunIdentitySendsNothing`, contract `test_w2_19_playMerchant` (updated). |
| A2 | `PlayExperienceService.ending` calls `api/play/ending` with query fields only, so the request is `GET`. | **iOS already OK.** |
| A3 | `ClubService` (list/detail/members), `ClubActionService` (join/quit), `RePoint06ClubJoin`, `Adapt3ClubBackendAdapters`, `ChannelAdmissionGovernanceClient`, `ClubPublicRosterReading` and `SearchMapService` all send JSON. However, `ClubOperationsService.snapshot(.club)` read `api/club/detail` and `api/club/members` as multipart, and both are `@RequestBody`. | **Fixed.** Both reads are now JSON (`{"id":…}`, `{"clubId":…}`). Test: `ClubOperationsProfileScopeTests.testClubSnapshotReadsDetailAndMembersAsJSON`. |
| A4 | `TicketWalletService` decodes `data` as either an array or `{rows}`. `ProfileService.orders` decodes `{total, rows}` and checks `total == rows.count`. | **iOS already OK.** |

iOS verification:

- Swift is not installed in this environment, so the Swift tests were written but not run.
- All 12 changed Swift files parse cleanly with `tools/check_swift_syntax.py` (Tree-sitter, not a compile).
- `python3 -B tools/check_scaffold.py`: same result as the base. One pre-existing a11y-lint failure in `App/ExternalGameReviewScreens.swift`, a file this change does not touch.
- `python3 -B tools/contract_ratchet.py --check`: 190 failing against a baseline of 184, with the same 6 "new" failures that the untouched base already shows. This change adds no new failures.
