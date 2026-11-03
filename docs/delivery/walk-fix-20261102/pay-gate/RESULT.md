# pay-gate lane: result (walk-fix 2026-11-02, ROUND2 §pay-gate)

Branch `claude/walk-fix-1102-pay-gate` from `origin/master` (0f9c71bd), master merged back in before finishing. Draft PR #718. Not merged.

## needs owner (read first)

1. **Nothing records the club's share for a club-owned self-play pass (ownerType=1) on a theme without a coop order.**
   - After this change such a pass can be paid.
   - At payment, `SettlementEntryServiceImpl.recordOnPayment` writes nothing for ownerType=1.
   - Topic settlement then throws `NON_COOP_TOPIC_CASH_SETTLEMENT_UNPROVEN` for that theme every run (`CmsTopicServiceImpl.settleTopic`). The failure is isolated to that theme, but the job is marked FAIL.
   - So the platform holds the money and no row says what it owes the club.
   - Recommended default: until a payable ledger exists, keep refusing ownerType=1 sales on non-platform themes that have no effective coop order, or settle them by hand. I did **not** build or change this. The gate is open for them as ROUND2 asks.
2. **The 3% commission is not wired anywhere.**
   - The fee snapshot is `cms_topic.fee_platform_rate`, falling back to `settlement_rule(merchant_id=0, biz_type)` (`resolveFeeSnapshot`).
   - The rig theme carries 10%. The rig had no `settlement_rule` at all, so I added a 3% fixture row.
   - Activity cash settlement ignores the snapshot and uses the live global `activity` rule.
   - Platform-owned themes still record a platform share (the owner said they "pay no 3%").
   - Recommended default: set `settlement_rule` activity/topic to 0.03 and the per-topic rate for club and merchant themes in configuration. Not done here (it is data, not code).
3. **The refund rule differs from the owner's model.**
   - `RefundPolicy` is all-or-nothing: 100% before the deadline (snapshot, or 24 h before assembly) and 0 after.
   - There is no "50% within 24 h" tier. Not built.
4. **The refund dispatch did not reach the gateway in-request in the rig, for every theme kind, platform included.**
   - `RefundPayoutServiceImpl.doPayout` threw 「退款派发必须在原业务事务提交并退出后执行」 from `TeamGroupServiceImpl` afterCommit.
   - The refund row stays `payout_status=5` (DISPATCH_PENDING) for `RefundReconcileTask`, which is paused in the rig.
   - This is the same before and after this change. It is not part of this lane, and someone should check it.
5. **Who is credited for a club-owned theme session.**
   - Activity settlement credits `cms_activity.member_id`, the club that opened the session, not the CONTENT slot holder.
   - When a theme's CONTENT club and the executing club differ, the executing club gets the cash. That has been the existing rule for all activities, and I did not change it.
6. **Lane l3-sell (P-01):** ROUND2 told that lane to keep payment refused for club themes and show it plainly. If this PR merges, that refusal disappears. The two PRs need to be read together.

## What changed

| # | Rule | File | Before | After |
|---|---|---|---|---|
| 1 | `assertCollectible` (private 3-arg; used by row create, multi-ticket whole order, self-play pass, quote, payment retry; public overload test-only) | `CmsRegistrationServiceImpl` | refuse unless CONTENT slot = PLATFORM, or standalone activity by `platformMemberId` | allow when the payee is known: a resolved theme, or a standalone activity with a `member_id`. It still refuses (same 「暂不支持在线支付…」 message) when neither resolves, e.g. a hosted activity whose theme is gone |
| 2 | standalone publish gate `assertStandalonePaidTicketsCollectible` | `CmsActivityServiceImpl` | non-platform publisher: ticket price must be 0 | removed |
| 3 | standalone edit gate `assertStandaloneEditKeepsCollectible` | `CmsActivityServiceImpl` | non-platform: no new paid ticket / 0→paid / price rise | removed |
| 4 | merchant-hosted event price | `ApiActivityController.merchantHostViolation` | 「商家主办活动还没开通收款，票价只能是 0」 | removed (the club_id mix rule stays) |
| 5 | same, client side | `pages/publish/activity/index.js/.wxml` | toast + caption under 票面价格 | removed |

Unchanged on purpose (listed per ROUND2 step 1):

- **Paid per redemption (REVSHARE), third-party:** `MerchantOfferServiceImpl:853` (enroll), `CmsTopicChapterServiceImpl:528` (chapter terms) and `MerchantPayableServiceImpl:130` (accrual) still require `isPlatformOwned`. The existing club-direct-payer route (`directPayer`, club pays offline) is untouched.
- **`ProductRoleServiceImpl:118` `SALE_ORGANIZER_ONLY_FREE_EXPLORE`:** lane l3-sell.
- **Free→commercial conversion bans:** `CmsTopicChapterServiceImpl:378`, `OmsTicketServiceImpl:784` and `CmsActivityServiceImpl.assertNoPaidTicketOnFreeTopic` are product policy, not payment qualification.
- **`requirePaidPublisherIdentity`:** real-name check for paid tickets, kept.
- **Platform-admin commands:** `TopicAdminCommandServiceImpl` and `TopicContentCopyServiceImpl` are not payment rules.
- **Not touched:** WeChat credentials, merchant config, payouts, settlement and withdrawal code.

## Money afterwards (from code; rig rows where shown)

- **Collection:** every order is prepaid on the platform's single merchant ID (no `sub_mchid` exists). The callback `/api/registration/callback` → `completePaymentSuccess` sets `payment_status=2`.
- **Recorded as owed, by order type:**
  - **Standalone event (ownerType=2, no theme):** `settlement_detail` ORIGIN rows, status 0. One goes to `receiver_type=merchant, receiver_id=cms_activity.member_id` for gross×(1−rate), the other to platform. Rig, registration 77: 37.83 to member 9008 (club 7002) and 1.17 to the platform at 3%.
  - **Activity under a theme with an effective coop order (the rig theme 990014):** no `settlement_detail` at payment. Cash is split at topic settlement into `coop_settlement` lines (T+7) and later paid to balance.
  - **Activity under a theme without a coop order:** `settlement_detail` as for a standalone event.
  - **Self-play pass:** nothing, see needs owner 1.
- **Settlement:** the hourly `settlementTask` (seeded paused) runs `executeActivitySettlement` for activities 7 days after they end. It credits `ums_member.balance` of `cms_activity.member_id` and writes `ums_member_balance_detail` eventType 3. The platform share goes to `cms_setting.platformMemberId`. Nothing filters on platform ownership.
- **What the club sees:**
  - `/api/wallet/balance` and `/api/wallet/stages`: unsettled or held activity funds and balance.
  - Club `/api/club/settlement/summary`: `coop_settlement` rows only.
  - Merchant finance does not list eventType 3 credits.
- **Refund:** the player calls `/api/registration/cancel-refund`. That writes `refund_application` (status 1 approved, payout DISPATCH_PENDING), sets registration `payment_status=4, registration_status=3`, and voids PENDING `settlement_detail` (status 2, as in rig registration 77). The cash goes back from the platform merchant via `RefundPayoutServiceImpl` → `submitRefund`. If settlement had already paid, `RefundServiceImpl` debits the receiver's balance; a shortfall goes to `SettlementDebtLedger`.
- **Withdrawal today:**
  - Bank card: `/api/withdrawal/create` debits the balance; an admin reviews and pays out manually (`confirmPayout`).
  - WeChat change (`/api/wallet/withdraw`, club `/api/club/settlement/withdraw`) is off unless `withdrawal.fast-withdraw.enabled=true` (default false).
  - No co-organiser receipt step exists.

## Proof over real HTTP (rig, DB read-back)

Rig: `claude/cloud-walk-20261101-rig` `up.sh` pinned to this branch's commit 7c5da8a3 with the club lane's `harness-patches.diff`.

Rig notes:
- Docker Hub answered 429, so dockerd used the `mirror.gcr.io` registry mirror with the same pinned digests.
- The chains `platform → merchant_club → sale_close` PASSed (theme 990014).
- Scripts and raw logs are in `evidence/`.

| Case | Steps (real endpoints) | Read-back |
|---|---|---|
| **A: club-owned theme** (CONTENT slot set to CLUB 7002 via admin `PUT /business/topic/990014/content-slot`) | club opens session (activity 16) → quote 69.00 → create → verified fact + real callback 204 → cancel-refund | reg 75: payable 69.00, rate 0.1000, `payment_status=2`, txn PAYGATE-TXN-75; after refund 4/3; `RF75` 69.00 status 1, payout 5. First run (reg 74, activity 13) paid the same way and was refunded by `evidence/refund74.py` |
| **B: platform-owned theme** (slot back to PLATFORM) | same, activity 17 | reg 76 identical to before: paid 2, refund 4/3, `RF76` |
| **C: club's own paid standalone event** | 9008 `POST /api/activity/publish` with a 39.00 ticket → 「活动发布成功」 id 19 (old code: 「独立活动暂不支持在线收款…」) → player quote 39.00 → create → callback 204 → cancel-refund | reg 77 paid, rate 0.0300; `settlement_detail` merchant 9008 37.83 + platform 1.17 status 0 → status 2 after refund; `RF77` 39.00 |

Fixture writes (test DB only, not product):
- `ums_member.role='club'` for 9008, so the fixture club owner counts as a club, as real owners do.
- `user_permission(role=club).max_events=20`, because the sessions opened earlier used up the cap of 3.
- One `settlement_rule(0,'activity',0.03)`, because the rig had none and paid standalone events need a fee snapshot.

`evidence/C-club-standalone-publish.log` is a rerun of the publish (activity 21), with the same response as id 19.

Not proved over HTTP: a merchant-hosted paid event. The fixture's `user_permission(role=merchant).can_create_event=0` stops it at the quota check before my change matters. It is covered by `ActivityPublishRoleGateTest.merchantScopePublishAllowsPaidTicketThroughPlatformMerchant` and the xcx unit tests.

## Tests updated because this decision changes them

| Test | Old pin | Now |
|---|---|---|
| `ErqingGateTest` | third-party theme / activity / self-play / multi-ticket / replay → 「暂不支持在线支付」 | `thirdParty_paid_collectsThroughPlatformMerchant`, `activityCarryingThirdPartyTopic_passesCollectGate` (order inserted, payable 100), `thirdPartyStandaloneActivityPassesCollectGate`, `selfPlayPass_thirdParty_passesCollectGate`; multi-ticket and replay tests now pin the remaining refusal (theme not found); new `activityWhoseTopicVanished_stillRejected`; `replayExistingStandaloneOrderRequiresKnownPublisher` |
| `StandaloneActivityLegacyPaidEditTest` | 3 × 「票价只能是 0」 on edit | `…_saves` |
| `FreeClubPublisherIdentityTest` | `standalonePaidActivityRequiresPlatformPublisher` | `standalonePaidActivityPublishesForAnyRegisteredPublisher` |
| `StandaloneCheckoutSignupNoticeAcceptanceTest` | user-published paid standalone refused on retry | `userPublishedStandalonePaidActivityQuotesAndRetriesThroughPlatformMerchant` |
| `ActivityPublishRoleGateTest` | `merchantScopePublishRejectsPaidTicketWithExplicitMessage` | `merchantScopePublishAllowsPaidTicketThroughPlatformMerchant` |
| xcx `publish-activity-merchant-host.test.js` | paid merchant publish blocked, caption present | publish sent with scope=MERCHANT; caption gone |
| xcx `activity-publisher-identity-contract.test.js` | 「商家收费禁令仍在」 | no client block, no old wording |

Runs:
- Targeted Maven set (18 classes, 164 tests, then widened to 22 classes): 0 failures.
- Full `mvn -pl chengyinhub-admin -am test`: 14041 tests, 0 failures. The 32 errors are environment only: the 13 `*MyBatisTest` ITs had no MySQL at that moment, and `WxImageSecurityCopyTest` hit a PNG decode error.
- After merging master: the targeted set and both xcx files pass again (33/33).
- `ui-integrity.js`: `UI-GATE-0:PASS`.
- Action ledger: rebuilt with `--write` after the merge; the diff is only `staticSource` line shifts in `pages/publish/activity/index.js` (124 lines), then `ACTION-LEDGER PASS: 5599 controls`.

## Items

| ID | Status | What changed | File | Verified |
|---|---|---|---|---|
| PG-1 list gates | fixed | list above | — | code read |
| PG-2 open gate | fixed | rows 1–5 above | `CmsRegistrationServiceImpl`, `CmsActivityServiceImpl`, `ApiActivityController`, `pages/publish/activity` | unit tests + rig A/B/C |
| PG-3 keep REVSHARE refused | fixed (unchanged) | none | — | `MerchantOfferServiceImplTest`, `ChapterSupplyTermsGateTest`, `MerchantPayableServiceTest` green |
| PG-4 money report | fixed | this file | — | code + rig rows |
| PG-5 HTTP proof | fixed (merchant-hosted case only unit-tested) | — | `evidence/` | rig |
| M-paygate UI | fixed | caption 「商家主办活动还没开通收款，票价只能是 0」 removed from the ticket step | `pages/publish/activity/index.wxml` | `shots/M-paygate-before.jpg` / `-after.jpg` |

About the screenshots:
- Both are from the rig browser runtime, not a phone. They show the merchant scope, identity 9002, ticket step reached by `setData({step:2})`. The step dots still read 基本信息 because of that shortcut, the same in both shots.
- Not verified on a real phone: the WeChat pay sheet, real refund to WeChat, and settlement or withdrawal jobs running on schedule.

## Round 3

Round 3 (ROUND3.md §pay-gate, restarted per ROUND3C.md). Every item below has been redone and verified in this run. Nothing from the lost run was pushed before.

### Needs owner (round 3)

1. **The 3% rate is data, and two code fallbacks still say 10%.** Nothing is hard-coded to 3%. Before launch, set these values:
   - `settlement_rule (merchant_id=0, biz_type='activity')`: `platform_commission_rate=0.03`, `merchant_rate=0.97`, `creator_rate=0`. Activity cash settlement reads this live (`CmsActivityServiceImpl.executeActivitySettlement`).
   - `settlement_rule (merchant_id=0, biz_type='topic')`: `platform_commission_rate=0.03`, `merchant_rate=0.97`, `creator_rate=0` (H8 requires 0). New themes freeze this into `cms_topic.fee_platform_rate` when the price is locked (`PricingServiceImpl.snapshot…`). Self-play and coop topic settlement read that snapshot.
   - If you use one: `settlement_rule (0,'topic_club')` with the same values. Club themes fall back to `topic` when it is missing.
   - **Existing themes** keep the `fee_platform_rate` they froze at price lock (the rig theme has 0.1000). Changing that is a per-theme data decision and is not done here.
   - **Flag:** `PricingServiceImpl.FALLBACK_PLATFORM_RATE = 0.10`. When the `topic` row is missing, pricing and the snapshot fall back to 10% (it logs an error). Recommended: add the rows above. Fallback default: leave the constant until the owner says to fail closed instead.
   - The owner said platform-owned themes pay no 3%. Topic settlement still applies the same rate to them. Not changed.
2. **Points under the 50% tier.** A late cancel refunds 50% of the cash. Points used on the order are still returned in full, because `returnPoints` is all-or-nothing. Recommended default: keep it (points are platform value, not the organiser's money).
3. **Tiers on theme passes.** Session tickets (owner_type=2) now have three tiers. Theme self-play passes (`evaluateTopicFulfillmentWindow`, refundable until fulfillment starts) and ③ explore passes (purchase snapshot) still use the all-or-nothing rule. Applying 50% to them needs a "start" defined for a pass. Recommended default: the fulfillment-window start, with the same 24 h tiers. Not built.
4. **When a 主理人 change takes effect.** Activity cash goes to whoever is 主理人 when settlement runs (end + 7 days). A self-play theme's 97% line is fixed to the 主理人 at topic settlement and paid T+7 to that person, even if the 主理人 changes during those 7 days. Recommended: keep it. The line is a record of what was due at that moment.
5. **Lane overlap:** `pages/activity/baoming` (checkout refund table and the within-24 h confirm) and the player order status words are also near l3-sell's files. Read the two PRs together.

### Items

| ID | Status | What changed | Files | Verified |
|---|---|---|---|---|
| R3-payee | done | New `ClubPrincipalPayee` (service/support). A club session (`cms_activity.club_id>0`) pays the club's **current** 主理人 (`club.member_id`). Other sessions still pay `cms_activity.member_id`. Both pay sites in `CmsActivityServiceImpl` (normal settlement and the complaint-hold release) and the debt offset use it. A club with no 主理人 fails closed: the activity rolls back and retries next run. | `ClubPrincipalPayee.java`, `CmsActivityServiceImpl.java` | `ClubPrincipalPayeeSettlementTest` (3 cases; red on old code, which credits the creator) |
| R3-refund-debit | done | When settlement has already paid, the refund clawback now debits whoever actually received the money. New mapper query `selectIncomeMemberIdsByEventTypeAndEventId` lists the people with a positive eventType=3 income for the activity, most recent first. If the ledger receiver is among them, they are debited. If not, the latest receiver is debited. With no income rows (older data), the ledger receiver is debited as before. The `settlement_detail` adjustment row keeps the original receiver so it still pairs with its ORIGIN row. | `RefundServiceImpl.java`, `UmsMemberBalanceDetailMapper.java/.xml` | `RefundSingleSidedDebitGuardTest` +2 (red on old code) |
| R3-self-play | done | `CmsTopicServiceImpl.settleTopic`: a club's own theme (`cms_topic.club_id>0`) with no effective coop order no longer throws `NON_COOP_TOPIC_CASH_SETTLEMENT_UNPROVEN`. `settleClubSelfPlayTopic` locks the owner_type=1 cash rows only (club session tickets settle per activity), refuses if refunds block the basis, and writes two `coop_settlement` lines in the existing shape: a `PLATFORM` line (ACCOUNTING, status 1, rate from `platformRateOf(topic)`) and an `INITIATOR_MARGIN` line (status 0, payable end+7 days) for the remainder, owed to the club's current 主理人. The existing `payoutDueCoopSettlements` pays it into the balance (eventType 5). Non-club themes still throw. | `CmsTopicServiceImpl.java` | `SettlementRateConvergenceTest.topic_clubSelfPlayWithoutCoopRecordsPrincipalDue` (138.00 → 4.14 platform + 133.86 to 主理人 9008) |
| R3-3% | done (data, needs owner 1) | No code. The exact settings are listed above. | — | code read |
| R3-payout5 | done: **defect confirmed and fixed** | Inside Spring's `afterCommit`, `TransactionSynchronizationManager.isActualTransactionActive()` is still `true`, because resources are unbound only in `cleanupAfterCompletion`. So `RefundPayoutServiceImpl.doPayout` threw 「退款派发必须在原业务事务提交并退出后执行」 on **every** player self-refund. `payoutAfterReview` swallowed it as a log line, and the row stayed at `payout_status=5` until `RefundReconcileTask` (paused in the rig). Fix: `TeamGroupServiceImpl.dispatchPayoutAfterCommit` now dispatches in `afterCompletion(STATUS_COMMITTED)` under `PROPAGATION_NOT_SUPPORTED`, the same pattern `CoopDepositRefundService` already uses. The test helper `memoryTransactions` now allows suspending only once synchronization has been cleared, which happens only after completion. | `TeamGroupServiceImpl.java`, test `CancellationPhaseTestSupport` | `RefundDispatchAfterCommitTest`: (a) shows Spring reports an active transaction inside afterCommit; (b) payout runs with no transaction (red on old code); (c) rollback never dispatches |
| R3-tiers | done for session tickets (needs owner 2, 3) | `RefundPolicy`: three tiers for session tickets. More than 24 h before start: 100%. Within 24 h: 50% (`SESSION_LATE_REFUND_RATIO`; `Verdict.ratio`/`partial()`). After start: none, reason 「场次已开始,不可退款」. `sessionRefundRatioAt` serves the quote. The rule text now reads 「集合前 24 小时以上全额退;24 小时内退 50%;开始后不退」. Player self-cancel passes the ratio to `refundOne`/`refundWholeOrder`: refund = cash × 0.5, rounded **down** to the cent. Owner-initiated and organiser cancels stay 100%. **Settlement nets out the refund:** a 50% refund sets the registration to 4/3, so `selectTotalSalesByActivityId` no longer counts it, and the new `selectRetainedSalesAfterPartialRefundByActivityId` adds back `payable − approved refunds` for partly refunded registrations. Checkout: the quote returns `refundPercentNow`. A 「退款规则」 block with three rows uses the existing `sec-title`/`setly_li` rows. Ordering within 24 h asks 「取消只退 50%」 first. The after-start confirm reads 「该场次已开始，支付后不可退款」. The order page shows the 50% reason instead of 「可免费取消至…」. **Status words:** player order 付款成功 → 正在退款 → 退款成功 (`utils/order-status.js`, order timeline, history filter). 退款审核中 stays for refunds waiting for review. No migration is needed (no schema change). | `RefundPolicy`, `TeamGroupServiceImpl`, `CmsActivityServiceImpl`, `CmsRegistrationMapper.java/.xml`, `RegistrationQuoteResult`, `CmsRegistrationServiceImpl` (quote only; `assertCollectible` untouched), `RegistrationDetailDisplay`, xcx `baoming`, `order-status.js`, `scene-member-order-detail`, `scene-member-order-history` | `RefundPolicyTest` +6, `FreeSessionCancelReleaseV2Test.paidSessionOrderWithin24hRefundsHalf` (9.90 → 4.95 refund), `ClubPrincipalPayeeSettlementTest.retainedHalfOfLateRefundIsSettledToOrganiser`, xcx `item6-nonrefundable-confirm` (+1), `order-status`, `cross-end-copy-contract` |

Tests that pinned the old refusal and were changed because the tier decision changes them:
- `RefundPolicyTest`: `activity_afterStart_notRefundable`. `session_1minAfterCutoff_*`, `paidOrPointSessionOrder_within24h_*` and `session_legacyMidnightAssembly_keepsTheOldCutoff` now expect 50%.
- `SessionRefundCopyTest.missedSessionRefusalReasonSaysStarted`.
- `FreeSessionCancelReleaseV2Test`: "within 24 h rejected" became `paidSessionOrderWithin24hRefundsHalf` plus `paidSessionOrderAfterStartRejectedWithoutTouchingInventory`.
- xcx: `item6-nonrefundable-confirm` (quote contract and modal text), `order-status` and `cross-end-copy-contract` (refund words).

What changed for the money, by path:
- Activity settlement: the payee is the club's current 主理人 for club sessions; retained 50% is now counted.
- Refund after settlement: the debit goes to the actual receiver.
- Self-play theme: a new settlement branch.
- Player self-cancel: 50% within 24 h.
- Refund dispatch: it now actually reaches the gateway after commit.

Unchanged: `assertCollectible`, coop settlement and its payees (club coop lines already pay `club.member_id` via `resolveOrderPayee`), owner/organiser cancels (still 100%), `settlement_detail` ORIGIN receivers.

Runs:
- Wide Java set before the tiers: 2376 tests, 0 failures; 3 errors were MyBatis ITs with no MySQL in this container.
- After the tiers: a targeted Java set (`Refund*`, `Session*`, `*Cancel*`, `TeamGroup*`, `*Settlement*`, `Activity*`, `Erqing*`, `Standalone*` and others) ran 1144 tests with 0 failures and 0 errors. The xcx files touched pass.
- `ProductSourceWiringTest.settlementReadsFeeSnapshotNotLive` was updated to count 2 snapshot reads; that commit came from a parallel session on this branch and was merged.
- Action ledger: rebuilt with `--write` (only `pages/activity/baoming` line shifts, 5599 controls).
- `ui-integrity.js`: `UI-GATE-0:PASS`.

Not done:
- **HTTP proof in the rig for the self-play pass and the tiers.** The rig (Docker, pinned images) was not brought up in this restarted run. The proof is unit and service level only. The round-2 rig evidence above predates these changes.
- **Screenshots** of the checkout refund table and the new status words were not taken, for the same reason.
- **Real phone:** unverified, as before.

## Round 3D

Round 3D (ROUND3D.md, 2026-11-03). Master merged first: already up to date.

### Needs owner (round 3D)

1. **Old refund text stored on tickets.** The theme page session rows show 「退改 · {ticket.refund_rule}」 (`pages/topic/index/index.wxml:271`), and the session picker shows the same field (`pages/topic/components/cy/session-picker/index.js:171`). That text is `oms_ticket.refund_rule`, free text stored on the theme ticket and copied into every session ticket (`copyTopicTicketsToActivity`). In the rig it reads 「开场前24小时可退」 on theme ticket 81/96 and on session ticket 83. That no longer matches the three tiers. It is stored data, so I left it.
   - Recommended default: hide `refundRule` on rows for paid session tickets (mode 1), because the checkout table and order page already state the platform rule. Alternatively, clear the column for those tickets with a data migration.
   - Not captured as a screenshot: the rig driver did not open the theme page's session list. The evidence is the code line plus the DB rows in `proof/setup_club_theme.log`.
2. **Platform-owned themes: who the 3% came from.** Built per the approved decision (below). On a platform-owned theme the initiator lines go to `cms_topic.member_id`, which is the platform's own account (`chengyin.platform.member-id`, set by `insertCmsTopic` / `resolvePayeeMemberId`). So in topic settlement the 3% was always the platform paying itself. The change moves it from the PLATFORM line into INITIATOR_MARGIN with the same receiver. No outside party gains or loses money there.
   - Where an organiser really does pay 3% on a platform theme: **activity settlement** of a club session opened on a platform-owned classic theme. The session's club (its current 主理人) gets `merchant_rate` (97% after the migration) and the platform account gets 3% (`CmsActivityServiceImpl.settleActivity`). I did **not** change that. The brief scoped the decision to settlement of the theme.
   - Recommended default: keep 3% on club sessions. The club does the work and the platform takes its fee. If "pay no 3%" was meant for the organiser here, the same `isPlatformOwned` check fits in `calculateActivityCommissions`.
3. **Self-play quote shows the ticket price, not the pass price.** In the rig, `/api/registration/quote` for ownerType=1 on theme 990016 returned `payAmount 39.99` (the theme ticket), while `/api/registration/create` charged `selfPlayPrice` 29.99 (`createSelfPlayPassInternal`). The order and the money are right; the quote is wrong. Not in this lane and not changed. Lane l3-sell owns the sale flow.
4. **Classic club themes cannot reach on-sale in master.** `PUT /business/topic/{id}/organizer-actor` returns `SALE_ORGANIZER_ONLY_FREE_EXPLORE` (`ProductRoleServiceImpl:118`), so `sale-submit` has no canonical draft. This is lane l3-sell's P-01 gate. For the proof I set lifecycle 2→3 on the two rig test themes as a fixture.
5. Round 3 items 2–4 (points under 50%, tiers for theme passes, 主理人 change timing) are unchanged and still open.

### Items

| ID | Status | What changed | Files | Verified |
|---|---|---|---|---|
| D1 rig | done | Rig `claude/cloud-walk-20261101-rig` with the club lane's `harness-patches.diff` applied, `up.sh` pinned to this branch (2cf45074). dockerd ran with the `mirror.gcr.io` registry mirror and the same pinned digests. Chains platform → merchant_club → sale_close PASS (theme 990014). | rig only | `proof/` |
| D2 HTTP proof | done | Six cases, real endpoints plus DB read-back. Details below. | `proof/proof_round3d.py`, `proof/round3d-*.log/json` | rig |
| D3 shots | done (after) / see below (before) | Checkout refund table, the within-24 h confirm, and order page 付款成功 → 正在退款 → 退款成功. Taking the shots exposed a real defect, fixed in D3a. | `shots/R3D-*` | rig browser runtime, not a phone |
| D3a timeline | **fixed (found in rig)** | The order page timeline counted only `payment_status=2` as paid. A refunded order carries `payment_status=4`, so the refund rows never showed and a refunded order read 「订单已创建 → 等待支付」. Now a refunding or refunded order shows 付款成功 → 正在退款 / 退款成功. | `scene-member-order-detail/index.js` | new `order-timeline-refund.test.js` (2 of 4 red on old code); shots `R3D-order-*` |
| D4 platform-owned | done | `CmsTopicServiceImpl.settlementPlatformRate`: settlement of a theme whose CONTENT slot is PLATFORM uses platform rate 0. The PLATFORM line is still written, with amount 0, because it is the "already settled" marker the idempotency check reads. Its share stays in INITIATOR_MARGIN. Both topic settlement paths (coop and club self-play) use it; other themes still read the price-lock snapshot. Who receives the money: see Needs owner 2. | `CmsTopicServiceImpl.java` | `CoopSettlementTest.settleCoopTopic_platformOwnedTopicRecordsNoPlatformCommission` (red on old code: PLATFORM 10.00, expected 0) and `…nonPlatformTopicStillPaysPlatformRate`; `ProductSourceWiringTest` updated (1 snapshot read, 2 uses of the helper). Not proven in the rig: the rig jar was built before this commit. |
| D5 rate rows | done | `sql/migration_20261103_settlement_rule_three_percent.sql` sets generic `activity`, `topic` and, only if present, `topic_club` to platform 0.0300 / merchant 0.9700 / creator 0. A pre-assert (one active generic row each for activity/topic, at most one topic_club) runs before the UPDATE; a post-assert checks the values. It writes absolute values, so a rerun is a no-op. It does not touch `cms_topic.fee_platform_rate`. Registered in `ci/migration-dependencies.json` after `p3_phase_c_web_rules_refund` and `20260815_tandianri_h8_rate_convergence`. | migration, `ci/migration-dependencies.json` | Run with `ci/run_migration.py` on the rig DB three times (`proof/migration-rerun.txt`): runs 1–2 activity/topic, run 3 with a topic_club row. A duplicate-row negative case is impossible: `uk_rule_scope` already forbids it. `migration-layout-check.sh` and `migration-safety-check.sh` pass. Theme 990016 then froze `fee_platform_rate=0.0300` at price lock. |
| D6 wording | done (code) / needs owner (data) | Code fixed: the order list cancel confirm said 「可免费取消至 <开场时刻>」 for an order within 24 h (the list API sends `ratio 0.5` and deadline = start). Now it uses the backend reason 「集合前 24 小时内取消,退实付的 50%…」. Data: see Needs owner 1. | `scene-member-order-history/index.js` | `order-list-refund-deadline-truth.test.js` +1 (red on old code); rig list API read for order 81 |

Every place a player can see refund wording, checked:

| Where | Text | Kind | Action |
|---|---|---|---|
| Theme page session row, session picker | `退改 · {refund_rule}`, e.g. 「开场前24小时可退」 | stored ticket data | Needs owner 1 |
| Checkout `baoming` | three-row table, 50% confirm, 「本单不可退」 after start | code (round 3) | matches |
| Order detail refund card | backend `RegistrationDetailDisplay`: full rule plus 「可免费取消至…」, or the 50% reason within 24 h | code | matches |
| Order detail timeline | was 「等待支付」 after a refund | code | fixed (D3a) |
| Order list cancel confirm | was 「可免费取消至 <start>」 within 24 h | code | fixed (D6) |
| Order detail / participation cancel confirm fallback | 「已核销或已过开始时间不可退」, only when the backend sends no display text | code | true as stated; not reached within 24 h (the display text is the 50% reason). Left as is. |
| Backend refusal texts | 「场次已开始,不可退款」, 50% reason, 「可全额退款,原路退回…」 | code (round 3) | match |
| Organiser publish step 3 (not player) | 「集合时间前可全额退款；错过集合或已核销不退」 | code | Not changed: organiser view, and still true for theme passes. Listed for the owner. |

### HTTP proof (rig, DB read-back)

Theme 990016 「R3D 俱乐部自玩主题」: club 7002's own classic theme (CONTENT slot CLUB 7002, no coop order). It was built through the real template, create, audit and pricing endpoints (`proof/setup_club_theme.py`, log in `proof/setup_club_theme.log`). `payment_status` is written only by the real `/api/registration/callback` after the chains' trusted-fact fixture (`chain_player.store_trusted_payment_fact`). Session ticket 39.99.

| Case | Real endpoints | Read-back |
|---|---|---|
| Self-cancel > 24 h | open-session (11-06 10:00) → quote (`refundPercentNow 100`) → create → callback 204 → `cancel-refund` | reg 75: paid 2/2, rate 0.0300 → 4/3; `RF75` refund **39.99** of 39.99 |
| Self-cancel < 24 h | open-session (today 17:30) → quote (`refundPercentNow 50`) → … → `cancel-refund` | reg 76: `RF76` refund **19.99** of 39.99 (39.99 × 0.5 = 19.995, rounded down), reason 「用户申请退款(集合前24小时内,退50%)」 |
| Self-cancel after start | paid reg 77; fixture moves the session start 1 h into the past | 「场次已开始,不可退款」 (code 500); reg stays 2/2; no `refund_application` |
| Dispatch leaves 5 | same as the first two; `refundReconcileTask` job 143 status 1 (paused) | `payout_status=6` (DISPATCHING), `payout_submission_state=LEGACY_RECONCILE`. The in-request dispatcher claimed the row and called the gateway. The rig's payment stub answers `submitRefund` with UNKNOWN by design, so the row waits in 6 for a same-identity query. It never sits in 5. Round 2's rig read 5 on the old code. |
| Club session pays the current 主理人 | 9004 joins club 7002; `POST /api/club/governance/owner/transfer` 9008 → 9004 (`club.member_id` 9008 → 9004); activity 18 (creator `member_id` 9008, the 50% case) moved 8 days back; `PUT /monitor/job/run` settlementTask | activity 18 `is_settlement` 1; `ums_member_balance_detail`: **9004 eventType 3 +19.40**, platform 9001 eventType 7 +0.60. 20.00 is the retained half (39.99 − 19.99) at 3%. The creator 9008 gets nothing. |
| Club self-play theme | 9009 buys a self-play pass (`create` ownerType 1, 29.99, callback 204); theme end moved 8 days back; settlementTask; then coopSettlementPayoutTask | `coop_settlement`: **PLATFORM 0.90** (29.99 × 3%, status 1, receiver none) and **INITIATOR_MARGIN 29.09 to 9004** (status 0). After payout: INITIATOR_MARGIN status 1, `ums_member_balance_detail` 9004 eventType 5 +29.09. Balances: 9004 48.49 (19.40 + 29.09), 9008 0.00. |

Fixture writes (test DB only, each printed in the logs):
- `settlement_rule` activity/topic rows in the pre-migration production shape (the rig had none). The new migration then set them to 3%.
- `cms_setting(id=1, platform_member_id=9001)`. The fresh rig had no row, and settlement failed closed with 「活动结算配置不存在」 (first attempt kept in `round3d-settle_activity-attempt1.log`).
- `ums_member.role='club'` for 9008 (as in round 2).
- Lifecycle 2→3 for themes 990016/990017 (Needs owner 4).
- Date moves (session start/end, theme end).
- `refund_application.payout_status=1` on two orders (80, 82), only for the 退款成功 screenshot. The rig's stub refuses refund callbacks.
- Applied the existing, unchanged `migration_20260815_tandianri_h8_publisher_points_funds.sql` to the rig DB with `ci/run_migration.py`. The rig's schema builder had skipped it, and the post-cash points step failed with 「Unknown column rule_version」.

Rig notes:
- In the rig, the refund row time on the order timeline shows UTC (03:56) next to CST times (11:56). `refund_application.update_time` is filled by MySQL, and the rig's MySQL container runs in UTC while the app writes CST. This is a rig artifact; check the production server time zone if in doubt.
- The callback for a new order on theme 990016 after its settlement was refused, correctly, by `CoopClosedPaymentRecovery` (no cash into a settled theme). The screenshot orders use a second test theme, 990017.

### Screenshots

The "after" shots come from this branch in the rig browser runtime (390 × 844, JPEG q70), not from a phone:
- `R3D-refund-table-after`: checkout, theme 990017 session in 3 days.
- `R3D-confirm-within24h-after`: 「取消只退 50%」 confirm, session starting in 5 h.
- `R3D-order-{1-paid,2-refunding,3-refunded}[-top]-after`: the same order 82 paid → cancelled → refund done (fixture).

The "before" shots come from master c6eac3b8, run in a second rig (`up.sh` pinned to master, fresh DB, same chains). The same theme was built with the same script (`proof/setup_club_theme-master.log`, theme 990015). Master refuses payment for club-owned themes (`assertCollectible`), so for a comparable state its CONTENT slot was set to PLATFORM through the real admin endpoint (`proof/shots_master_slot.log`).

| Shot | master (before) | this branch (after) |
|---|---|---|
| `R3D-refund-table-*` | no refund rule on the checkout | 退款规则: 集合前 24 小时以上 全额退 / 24 小时内 退 50% / 开始后 不退 |
| `R3D-confirm-within24h-*` | 「本单不可退 — 该场次已过「集合前 24 小时」退款截止时间，支付后不可退款」 | 「取消只退 50% — 该场次已进入集合前 24 小时，支付后取消只退实付的 50%」 |
| `R3D-order-1-paid[-top]-*` | timeline 「支付已完成」 | timeline 「付款成功」 |
| `R3D-order-2-refunding[-top]-*` | status 「退款处理中」; timeline falls back to 「订单已创建 → 等待支付」 | status 「正在退款」; timeline 订单已创建 → 付款成功 → 正在退款 |
| `R3D-order-3-refunded[-top]-*` | status 「已退款」; timeline 「等待支付」 | status 「退款成功」; timeline … → 付款成功 → 退款成功 |

The same before/after run also shows the dispatch fix directly. On master, `cancel-refund` answered 「现金退款已受理，尚未派发」 and RF74 stayed at `payout_status=5`. On this branch it answers 「现金退款正在派发」 and the row moves to 6 (`proof/shots-before-74.log`, `proof/shots-after-82.log`).

The before shots' refund time is UTC for the same rig reason given above. The before checkout carries a 「你已报名这场活动」 banner because the order for the order shots was placed first; the after shot was taken further down the page.

### Runs

- Java: `CoopSettlementTest`, `ProductSourceWiringTest`, `SettlementRateConvergenceTest`, `CoopSettlementPerMerchantTest`, `TopicSettlementIdempotencyTest`, `Wp04TopicSettlementEntryGuardsTest`, `CoopSettlementTopicFenceTest`: 105 tests, 0 failures. The new test fails on the old `CmsTopicServiceImpl` (checked by swapping the file).
- xcx: `order-timeline-refund`, `order-list-refund-deadline-truth`, `dw29-v20-order-detail`, `order-status` and `cross-end-copy-contract` pass.
- `ui-integrity.js`: `UI-GATE-0:PASS`. Action ledger rebuilt with `--write`: only line shifts, 5599 controls.

Not verified on a real phone: the WeChat pay sheet, a real WeChat refund reaching 退款成功, and jobs running on their schedule.

STATUS: COMPLETE
