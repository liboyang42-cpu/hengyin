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

STATUS: COMPLETE
