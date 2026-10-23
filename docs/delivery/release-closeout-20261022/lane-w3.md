# Lane W3: funds-path hardening (2026-10-22)

> **Funds path. Owner approved this on 2026-10-22. Review before merge.**
> Branch: `feature/lane-w3-funds-hardening-20261022`. Base: master `063a6c3c`. One draft PR.
> Evidence sources:
> - `disposition.md` on `feature/lane-g-triage-20261022`: owner-decision item 3, #11 R1, #334 F7.
> - `private-groups.md` on `feature/lane-g2-private-groups-20261022`: `PRIVATE-GROUP-backend-club-meeting-atomic`.

This lane does not touch amounts, the callback routing order in `WechatPayServiceImpl.handlePayCallback`, signature verification, refund logic, the WeChat Pay client, or `ClubMembershipServiceImpl`.
It adds no new DDL and no new dependencies.

## Item 1: payment callback dedupe identity + registration fence (#11 R1)

| Status | **DONE** |
|---|---|

What changed:

- **`WxPaymentCallbackInboxServiceImpl.register`**
  - Before: `insert ignore` returning 0 meant "duplicate" with nothing else checked.
  - Now: the existing row is read back by `notify_hash`. It must have the same `bizType`, `bizId` and `eventType`.
  - If it does not match, or the row cannot be read back, `register` raises an alarm (`payment.callback.inbox_identity_conflict`) and throws `IWxPaymentCallbackInboxService.IdentityConflictException`. The conflict is never treated as a duplicate success.
  - The alarm and log record only a 12-character hash prefix and the business ids. The raw notifyId is never logged.
  - `@Transactional(noRollbackFor = IdentityConflictException.class)` stops the conflict from marking a caller's transaction rollback-only.
- **Registration payment callback (`CmsRegistrationServiceImpl.handleWechatPayCallback`)**
  - Before: it called `register`, ignored the result, and ran the state machine inside its own `@Transactional`. There was no fence.
  - Now it runs in this order:
    1. Look up the order by `out_trade_no`.
    2. For a SUCCESS callback, check the amount against the frozen `wechat_payment_amount` *before* registering. A same-notify callback with a different amount never enters the inbox.
    3. `register`. On identity conflict, return FAIL and alarm.
    4. `process(notifyId, mutation)`. The state machine (`applyWechatPayCallback`, a verbatim move of the old switch) runs inside `WxPaymentCallbackMutationService`'s transaction, after the inbox row and the registration row are locked `FOR UPDATE`. The business effects and the inbox DONE commit together.
  - A DONE replay returns SUCCESS without touching the order. That is the idempotent path.
  - BUSY, MISSING, an exception, or a business FAIL rolls back, releases the inbox row and returns FAIL so WeChat retries. A business FAIL keeps its own single alarm reason, such as `payment_state_conflict`.
  - `isCurrent` throws if the claimed inbox row belongs to another business. It never marks such a row STALE, so it cannot produce a false ACK.
  - The claim uses `REQUIRES_NEW`, so the callback must not run inside an uncommitted outer transaction. If it is called that way it fails closed with reason `callback_inside_transaction`.
- **`TeamGroupServiceImpl` (activity cancel, reconcile a PAYING order that WeChat says is SUCCESS)**
  - This path ran inside the cancel transaction, so it cannot use the fence. It now calls `applyConfirmedWechatPaymentWithinTransaction`, which is `Propagation.MANDATORY`.
  - It reuses the same state machine and frozen-amount check, protected by the existing payment CAS and the row locks. Nothing about its behavior changes.
- **`RefundServiceImpl.handleRefundNotify`**: an identity conflict on register returns `false` (FAIL) and the payout status is not advanced. Refund logic is unchanged.
- **Other callers**: `WechatPayServiceImpl` (the verified webhook) and `OrderCloseTask` (expired-PAYING reconcile) call the callback without a transaction, so both now go through the fence.

On "amount" in the identity: `wx_payment_callback_inbox` has no amount column. Adding one would be new DDL on a funds table, and the code would depend on deploy order. Amount is bound before registration instead:

- Payment: `matchesFrozenWechatAmount`.
- Refund: `matchesRefundFacts`, which also checks `out_trade_no`, currency and total.

`out_trade_no` resolves to `bizId`, and that is compared.

Tests:

- `WxPaymentCallbackInboxServiceImplTest`:
  - A same-business replay returns `false` without an alarm.
  - Same hash with a different bizId, bizType or eventType, or a missing original row: rejected, alarmed, never claimed or marked done.
  - A pin on `noRollbackFor`.
- `WxPaymentCallbackFencingMySqlIT` (real unique key, +1 case, 6 total):
  - Same-business replay is idempotent.
  - Another bizId or bizType on the same hash throws, the original row is unchanged, and the alarm fires twice.
  - `ci/funds_it_gate.py` EXPECTED and the `ci/test_funds_it_gate.py` fixture were updated from 5 to 6.
- `PaymentCallbackFenceEntryTest` (new, 9 cases):
  - register → process → mutation order.
  - A DONE replay is SUCCESS with no order writes.
  - Identity conflict gives FAIL, never processed.
  - A foreign inbox row never mutates the order.
  - BUSY gives FAIL.
  - A business FAIL keeps its own reason and fires a single alarm.
  - A SUCCESS amount mismatch never enters the inbox.
  - A call inside a transaction fails closed.
  - The in-transaction variant is MANDATORY and bypasses the inbox.
- Adapted to the fenced design. Each test keeps its original intent:
  - `RegistrationPaymentAtomicityContractTest`: now asserts register → process → state machine, and that the mutation is `@Transactional`. The CAS-before-side-effects assertions stay.
  - `WechatPayCallbackForgedSignatureTest` and `RegistrationFundsMybatisIntegrationTest`: use `PaymentCallbackInboxStubs.passThrough`, which runs the mutation every time in its own transaction and rolls back on failure. Two downstream-failure cases now assert `FAIL` plus a full rollback instead of an exception escaping.
  - `SignupNoticeAfterCommitPersistenceMySqlIT`: the "notify only after the payment transaction commits" checks drive the same state machine through `applyConfirmedWechatPaymentWithinTransaction` inside the caller transaction. The replay still goes through the fenced callback.

Local evidence (MySQL 8.0.45 in Docker, `CHENGYIN_FUNDS_MYSQL_IT=1`):

- `RegistrationFundsMybatisIntegrationTest` 92/92
- `WxPaymentCallbackFencingMySqlIT` 6/6
- `SignupNoticeAfterCommitPersistenceMySqlIT` 5/5
- `RefundPayoutMybatisIntegrationTest` 30/30
- `RefundNotificationMybatisIntegrationTest` 9/9
- `RegistrationEquityMybatisIntegrationTest` 5/5
- `ActivityCancelFreeOrderMybatisIT` 8/8

## Item 2: club lead edit-ops atomic sold lock (#334 F7, club-meeting-atomic)

| Status | **DONE (backend)**; the front-end copy goes to its owner (see the handoff below) |
|---|---|

- `ApiClubLeadController.editOps` stays thin.
  - Before: `countOpenSaleRegistrations` followed by a separate `updateCmsActivity`, with the row count unchecked. That was check-then-write.
  - Now: one call to `ICmsActivityService.updateLeadOpsUnlessSold(upd, touchesTimeOrPlace)`.
- For time or place edits, the service calls `CmsActivityMapper.updateOpsFieldsIfUnsold`. That is a single `UPDATE cms_activity … WHERE id=? AND NOT EXISTS (open-sale registration: owner_type=2, payment_status ∈ {0,1,2})`, the same rule as `countOpenSaleRegistrations`.
  - The method pins `REPEATABLE READ`. Under RR the subquery inside the UPDATE is a locking read: an in-flight sale makes the edit wait, and a sale arriving after the edit waits for the edit to commit.
- When zero rows change, the endpoint returns the existing message "该场次已售出，集合时间与地点不能修改；当日备注仍可改" plus `errorCode=CLUB_OPS_SOLD_LOCKED`. The message is unchanged so the mini program's failure sheet keeps showing it.
- Day-note-only edits stay unlocked, as previously ruled. They use the plain update.
- The response no longer claims "已报备发起人". Only a log line is written, so success now returns `msg="已更新；已留痕，未推送通知发起人"` and `data={organizerNotified:false, noticeReceipt:"LOGGED_ONLY"}`.
- The leader/owner gate is unchanged.

Tests:

- `ClubLeadLeaderResolutionTest`, moved to the new API:
  - Sold → `CLUB_OPS_SOLD_LOCKED`, and neither `updateCmsActivity` nor `countOpenSaleRegistrations` is called.
  - Note-only → `touchesTimeOrPlace=false`.
  - The success receipt is asserted.
- `ClubLeadOpsSoldLockMySqlIT` (new, registered in `ci/backend-test.sh` MYBATIS_ITS), using real MySQL and two interleaved transactions:
  1. Paid/pending/paying registrations lock the edit; failed/refunded ones and topic-level rows do not.
  2. An uncommitted sale makes the edit block; after the sale commits, the edit gets 0 rows and nothing changes.
  3. If the sale rolls back, the waiting edit applies.
  4. If the edit goes first, a concurrent sale blocks until the edit commits; any later edit then gets 0 rows.
  - Negative control: with the `NOT EXISTS` removed, 4/4 fail (run locally, not committed).
- `chengyinhub-xcx/tests/unit/club-director-ops-time-contract.test.js`: the backend sold-lock regex now matches the atomic form. The negative control still mutates it. 9/9 pass.

## CY-HANDOFF (front end, not changed in this lane)

- `pages/club/topic-detail/index.js` `confirmOpsTime` success sheet says "已报备发起人；…". The backend now reports `data.organizerNotified=false` and `noticeReceipt=LOGGED_ONLY`. The owner should show copy based on the receipt, for example "已更新；发起人通知未推送", and can map `errorCode=CLUB_OPS_SOLD_LOCKED` to the sold-lock state. Owner: club topic-detail owner, per private-groups #21.

## Not done / residual

- The amount is not persisted in the inbox row (that would need DDL). It is enforced before `register` (see item 1).
- `TeamGroupServiceImpl`'s in-transaction reconcile is deliberately not fenced, because a REQUIRES_NEW claim cannot see an uncommitted outer transaction. It still has CAS and row-lock protection.
- The atomic sold lock relies on InnoDB RR locking reads. The service method pins RR explicitly.
- Ledgers and baselines were not regenerated.
