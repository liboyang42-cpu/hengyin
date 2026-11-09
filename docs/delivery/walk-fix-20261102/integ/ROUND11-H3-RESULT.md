# Round 11 lane h3-points-switch: result

Owner rule (2026-11-04): the admin 停用 switch on `cms_points_setting` must really stop payouts. A disabled (`status` ≠ 1) or deleted (`del_flag` ≠ 0) rule pays nothing; enabled rules pay exactly as before. No migration, no stored values changed. `migration_20261104_invitee_signup_points_off.sql` is untouched.

- Commit: `ccb99b3f` (code + tests + gate count). Branch `integ/launch-test-20261103`, rebased before push, no force-push.

## Every reader of cms_points_setting

| Reader | Rule | Reads status? | This lane |
|---|---|---|---|
| `CmsRegistrationServiceImpl.pointsSettingValue` (payment callback `completePaymentSuccess`, used by WeChat callback, zero-amount completion and the duplicate-success repair path) | ids 2, 3, 5, 6 | **was no** | **Fixed**: returns 0 unless `status = 1` and `del_flag` is null/0. The order still completes; `awardPointsOnce` skips 0. |
| `PointsAwardServiceImpl.awardPublisherSettlement` → `selectPublisherSettlementRules` | event 15 | yes (`status=1 and del_flag=0`, unique) | Unchanged, as instructed. |
| `PointsAwardServiceImpl.award` (by `event_type`: 11 核销, 12 节点打卡, 13 通关) | 11/12/13 | **no** | Not changed: not a 2/3/5/6 path. **Owner question below.** |
| `PuzzlePlayServiceImpl.hintUnlockCost` | event 14 (a cost) | yes (status) | Unchanged. |
| `CommentPublicationService` first review | id 4 | **no** | Not changed. Owner question. |
| `ApiBetaGameController` vote | id 4 | **no** | Not changed. Owner question. |
| `MmsMerchantServiceImpl` merchant referral (推荐商户注册) | id 7 | **no** | Not changed (the f4 brief says the ¥30/merchant referral stays as it is). Owner question. |
| `UmsMemberServiceImpl` register, `ApiLoginController` (two places) | id 1 | **no** | Not changed. Owner question. |
| `ApiPointsController /api/points/result_list`, admin `CmsPointsSettingController` | display/CRUD | n/a | Display: see the screens section. |

No other path awards from rule ids 2/3/5/6. Refund clawbacks read the points detail rows, not the settings.

## Tests (real MySQL, `RegistrationFundsMybatisIntegrationTest`, +3)

- `enabledPointsRulesPayAsBeforeAndCallbackReplayDoesNotDuplicate`: rules 2/3/5/6 enabled; the callback runs twice. Player 100 → 115 (10 + 5), inviter 0 → 600 (500 + 100). One detail row each for events 2, 3, 5 and 6.
- `disabledPointsRulesPayNothingAndOrderStillCompletes`: all four have `status=0` with their values still stored. The callback runs twice and returns SUCCESS. `payment_status=2`, `registration_status=2`, player stays 100, inviter stays 0, no detail rows for 2/3/5/6. Stored values still add up to 615.
- `deletedPointsRulesPayNothingAndOrderStillCompletes`: `status=1` with `del_flag=1` (ids 2, 5, 6) or `del_flag=2`, the RuoYi convention (id 3). The callback runs twice; no points and the order completes.
- The existing tests still pass unchanged: `inviteeSignupRuleOffStillPaysAndOnlyAwardsFirstPurchase`, `inviteeSignupRuleStillOnBeforeMigrationAwardsPerSignup`, `duplicateSuccessRepairsHistoricalPaidOrderMissingRewards` (base seed rows 2/3 are `status=1`).

**Gate (count raised, not weakened):** `ci/funds_it_gate.py` RegistrationFunds 110 → 113, with a comment naming the three tests. The self-test fixture in `ci/test_funds_it_gate.py` is now 113. `python3 -m unittest ci/test_funds_it_gate.py` passes all 45 tests. I read h1's `ROUND11-H1-RESULT.md` first: h1 had finished at `a664bd4e` (728 tests).

**Mini:** `points-rules.js` already hid `status ≠ 1` rules; it now also hides rules with `delFlag ≠ 0`. `tests/unit/profile-points-rules.test.js` gained two deleted rows in its existing case and passes 4/4. Full mini unit run: 16,120 pass, 1 fail. That failure is `r11-g3-order-truth.test.js` "自玩通行证只说一次不可退款…", and it also fails without my change: it belongs to g3's in-flight work.

## CI

- My own commit's run, 41898492918 @ `ccb99b3f`, was cancelled by later pushes from other lanes.
- Funds MySQL IT run **41900809795** @ `6d221050` contains my commit:
  - Self-test job: success.
  - MySQL job: `Tests run: 731, Failures: 0, Errors: 5, Skipped: 0`. 731 = h1's 728 + my 3, and no error is in RegistrationFunds.
  - All 5 errors are in `ClubSettlementSummaryMybatisIntegrationTest`: `Table '…settlement_detail' doesn't exist`. h4 L7 (`31645fb8`) added a `settlement_detail` subquery to `CoopSettlementMapper.xml`, but the test's schema has no such table. **h4: please add the table to that test's DDL.** This is not h3's code.
- `ci/test_real_storage_it.py` / `ci/real-storage-it-registry.json` still register RegistrationFunds as 108. It was already stale at 110 after f4's +2, and is 113 now. The precheck says the registry must be updated after review, so I left it for the owner of that registry instead of editing it myself.

## Screens: reward promises for disabled rules

- 「如何获取积分」 (profile points sheet) lists only `status = 1`, and now also only undeleted, rules. It promises nothing for a disabled rule.
- **Invite history** (`subpackageA/components/scene-member-invite-history`) shows 「首购奖励积分」, 「已加入 · 首购待完成」 and 「待解锁」 for rule 5 **without reading whether rule 5 is enabled**. Rule 5 is enabled on production today, so the screen is correct right now. If an admin disables rule 5, it would keep promising a first-purchase reward that no longer pays. I didn't change it: the page would need a rule-status read. Owner call.
- After f4 removed the copy, no screen promises rule 6 (per-signup).

## Owner questions (money rules, not changed)

Rules 1 (注册赠送), 4 (评价/投票), 7 (推荐商户注册) and event-type rules 11/12/13 (`PointsAwardServiceImpl.award`) still pay when disabled. `award()` also takes the first row for an `event_type` without filtering on status. Should the same switch rule apply to them? Before deciding, check production's `status` for ids 1, 4, 7, 11, 12 and 13: turning this on for a row that is disabled today would stop a payout that is live today.

STATUS: PARTIAL - code and tests done. The Funds MySQL run with my commit had 0 failures in my class (731 run), but it is red from h4's ClubSettlementSummary schema gap, so I have no fully green run yet. The real-storage registry count (108) also needs its owner.
