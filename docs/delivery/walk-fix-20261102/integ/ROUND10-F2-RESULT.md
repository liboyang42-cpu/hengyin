# Round 10, lane f2-club-merchant: result

Branch `integ/launch-test-20261103`. Work started at `ecef2f07` after `ROUND10-F1-RESULT.md` had its STATUS line. The last code commit from this lane is `ffc3a3ffc`. Lanes f3, f4 and round 11 pushed to the same branch at the same time.

The work was split four ways:
- **f2-A:** items 1 to 6, plus j2 F-05, F-07, F-10 and F-14. It also set up the rig.
- **f2-B1:** j2 F-04, F-06, F-08, F-09 and F-11.
- **f2-B2:** j2 F-12, F-13, F-15 and F-16.
- **f2-C:** walked the whole journey on a fresh rig through to settlement, and fixed what the walk found.

**This is not a phone.** All rig results come from the cloud rig: the real jar on MySQL 8 and Redis 7, and the real mini-program code in headless Chrome at 390×844.

Screenshots are in `fix-shots/`:
- `f2-<item>-*` come from f2-A, with `f2-a-rig-readbacks.txt`.
- `f2-walk-*` come from the full walk, with `f2-walk-readbacks.txt`.

Every shot was looked at. All names are fictional.

## Summary

| # | Item | Status |
|---|---|---|
| 1 | 招商 before pricing (also j2 F-05, F-14) | fixed. The price lock is kept; see below |
| 2 | Merchant sign-up dates set automatically | fixed |
| 3 | `RegistrationMerchantDTO` date format | fixed |
| 4 | `recruitDeadline` and the `effectiveSaleEndAt` refusal | fixed. **One money rule needs the owner** |
| 5 | Merchant can always reach the accept page; the lead sees who is holding up the start (also j2 F-07) | fixed |
| 6 | Live co-op order at the merchant's scan; no stranded players (also j2 F-10) | fixed |
| 7 | j2 F-04, F-06, F-08, F-09, F-11, F-12, F-13, F-15, F-16 | fixed. F-06 changes a public number; see below |
| — | Whole journey with a merchant, a lead and four players, through to settlement | walked. Correct to the fen |
| — | Bugs found by the walk | 4 fixed |
| — | CI on the final commit | **not green.** One f3 test fails, and the runs were superseded; see Gates |

Main package: this lane's net change is −67 bytes. Every other file this lane touched is in a subpackage or the backend.

No migrations. No new public Service, Mapper or Controller, so no architecture registration and no change to PR #723's ③ section.

## Whole journey (f2-C), fresh rig at `5539681f9`

### Fixtures and setup

These are environment fixtures, all set before any gate:
- `settlement_rule`: I inserted production's old rows (10%/90%), then applied the repo's own `migration_20261103_settlement_rule_three_percent.sql` to bring them to 3%.
- The two release gaps j2 called F-03: `cms_setting` id 1 (platform account 9001), and `migration_20260815_tandianri_h8_publisher_points_funds.sql`. **r1/release must still confirm production has both.**
- A rig seam for the local OSS stand-in.

Time shifts, labelled in the read-backs:
- `recruit_deadline` moved back, so the real deadline job ran.
- The session start moved forward.
- Activity 9 moved 9 days back for the settlement job: 8 days, plus 1 because the rig's MySQL runs in UTC.

No data was written past any gate.

### Steps

| # | Step | Result | Shots `f2-walk-` | Read-back |
|---|---|---|---|---|
| 1 | New player becomes 主理人; creates a club with a cover | PASS (UI) | 01–04 | club 990004 |
| 2 | 编辑资料: new logo and cover, then 保存修改 | PASS (UI) | 05–09 | 「已保存」, no conflict |
| 3 | Four players join | PASS (UI) | 10–11 | club/join ×4 |
| 4 | Lead publishes a city-orienteering theme with 商家承接 on | API in the editor's own body shape | 12–13 | The entry and station picks were UI. The rig can't drive the editor's 补内容 pop-ups or native pickers. Theme 990015 |
| 5 | Project approval (real admin endpoint) | PASS | 14–15 | lifecycle 7 → 1 |
| 6 | Merchant 9002 applies for station 1 | PASS (UI; date wheels HARNESS) | 19–22 | `cms_registration_merchant` 14 |
| 7 | Lead 确认, sends the terms invitation; merchant accepts | PASS (UI) | 23–31 | coop_order 1 |
| 8 | Recruit deadline job | PASS | — | lifecycle 2, fee frozen at 0.0300 |
| 9 | 确认终价 | PASS (UI) | 34–39 | no 支付资质 note |
| 10 | Real admin sale review | PASS after one refusal | 40–41 | See L1. lifecycle 3 |
| 11 | 开一场, ¥39 | PASS (UI; time wheel HARNESS) | 44–48 | activity 9 |
| 12 | Four players pay (checkout, then the real callback) | PASS | 49–55 | regs 74–77, payment_status 2. On the theme page, `onHeroCta` was called directly: taps there don't reach handlers in the rig, as j2 also saw |
| 13 | Team; second member joins | PASS (UI) | 60–62 | team 1 |
| 14 | Check-in ×3, then 开始准备 | PASS (UI) | 70–74 | 「还不能开始：人民广场音乐喷泉（待商家接受）」, with 联系商家 |
| 15 | Merchant accepts and readies the station | PASS (UI) | 75–79 | STATION_ACCEPT and STATION_READY; 3/3 stations ready |
| 16 | 开始活动 | PASS (UI) | 81–84 | RUNNING |
| 17 | Station 2 answered before reception | PASS (UI) | 85 | Reason in red, no 「结果待确认」 |
| 18 | Merchant scans: wrong code, tampered code, real 团码, real 团码 again | PASS (UI) | 80, 86–90 | 未核销 with reason ×2; 「已记录接待该团」; 「重复核销」 |
| 19 | All four players finish stations 1–3; lead releases chapter 2 | PASS (UI) | 91–99 | UNLOCK_CHAPTER |
| 20 | 结束活动 | PASS (UI) | 100–104 | FINISHED |
| 21 | Real settlement jobs | PASS | — | is_settlement 1 |
| 22 | 俱乐部结算; ownership transfer; new and former 主理人 | PASS (UI) | 105–115 | club owner 990005 → 990006 |

### Money, to the fen

| | Amount |
|---|---|
| Paid: 4 × ¥39.00 | **¥156.00** |
| Platform 9001 (balance event 7) | ¥4.68 (exactly 3%) |
| Lead 990005 (balance event 3, 活动分润) | ¥151.32 |
| Merchant 9002 | ¥0.00 |

- The merchant's terms were 引流合作, which pays no cash, so `coop_settlement` is empty.
- 4.68 + 151.32 + 0.00 = 156.00.
- Per ticket, `settlement_detail` has 37.83 + 1.17 = 39.00, four times, all settled.
- **Not walked:** 按核销付费 terms, where the merchant takes a cash share.

## 1. 招商 before pricing; j2 F-05, F-14: fixed

Commits `a1d57403`, `54c55060`, and `d4faffd4a` from the walk.

**Cause.** The editor never sent a 招商截止 date. Without it, the theme skipped 招募 after project approval and went straight to 定价. That is the price lock, and it refuses every co-op invitation. Item 4 makes the editor send the date. The order is now: 立项 → 招募 → deadline job → 定价 → 开售.

**What the club screens show now:**
- 「现在要做」 follows the theme's stage:
  - 立项待审: 主题审核中.
  - 招募中: 去招商, which opens that theme's 招商 layer.
  - 定价中: 确认终价, which opens the pricing page.
  - After pricing: 开售审核中. The walk found it still said 确认终价 and fixed it in `d4faffd4a`.
- 开一场 appears only on an on-sale theme, and only once.
- The project card says 还没有场次 instead of the theme's start date.
- The header now matches 更多, and 更多 has a 确认终价 row. The pricing page itself is unchanged.

**Price lock: kept.** Locking freezes the fee rate, the price floor and the payout limits taken from the merchant line-up. A late invitation would change those after the price is set, which is a money change. Now that the deadline is sent, invitations happen during 招募, which is when they should.

**Tests:**
- `r10-f2-club-next-step.test.js` (8 cases).
- `c01-club-topic-not-on-sale-chip.test.js` is updated: it pinned the old header text.

**Shots:** `f2-1-01` to `06`; walk 14–15, 34–41.

## 2. Merchant sign-up dates: fixed

Commit `c41a4b3a`.

When a theme with 商家承接 on is created or rebuilt and has no merchant sign-up dates, they are filled in:
- start = now;
- end = 招商截止, or the theme's end date if there is none.

Themes that already have dates are not touched. There is no data migration.

**Proof:**
- Test: `MerchantSignUpWindowDefaultTest` (6).
- Rig, old jar `ecef2f07`: both dates null, and the app showed 「报名时间信息不完整」.
- Rig, new jar: 20:58:50 to 23:59:59.
- Shots `f2-2-01`, `02`; walk 19–22.

## 3. `RegistrationMerchantDTO`: fixed

Commit `5f909255`.

`startDate` and `endDate` now take `@JsonFormat("yyyy-MM-dd HH:mm:ss", GMT+8)`, which is what the app sends. The other request bodies on this path carry no dates: `coop/invite`, `candidates/confirm` and `coop/handle`.

**Proof:**
- Rig, old jar, the app's exact body: 500 「系统繁忙」, `HttpMessageNotReadableException`.
- Rig, new jar: 200, row 14.
- Test: `RegistrationMerchantDtoDateFormatTest`.
- Shots `f2-3-01`, `02`.

## 4. `recruitDeadline` and `effectiveSaleEndAt`: fixed. Money rule for the owner

Commit `3dff3e21`.

**The editor.** For city orienteering with 商家承接 on, the editor now shows the 招商截止日期 field and sends it. If it is empty, red text appears under the field. Free explore still always requires it. Clause ③ of `publish-schedule-recruit-sections-contract.test.js` is updated to this rule.

**Is the `effectiveSaleEndAt` refusal real? Yes, and it is worse than j2 found.** Using the editor's own body on the old jar:
- The sale review first refuses 「saleEndAt 必填」, because the editor never sends a theme sale end date.
- Supply one, and the guided-ticket rule (sales must end at or before the ticket starts) refuses every club theme sold through sessions, because the template ticket starts when the theme starts.

**Change, for owner confirmation:**
- For club-owned city orienteering, the sale end defaults to the theme's end date.
- The guided-ticket rule is skipped when `topic.clubId != null`. Each session carries its own start, sign-up close and refund clock.
- Non-club themes are unchanged.
- Recommended default: keep it.

**Reviewer messages are now plain Chinese.** For example: 「票种「领队场」的售票截止时间必须早于或等于票种开始时间」.

**Proof:**
- Tests: `TopicSaleTimeValidatorTest` (6), `ClubSaleEndDefaultTest` (3), `r10-f2-recruit-deadline.test.js`.
- Rig, with no fixture: approval → 招募 → deadline job → 定价 → 确认终价 → admin sale review → lifecycle 3.

## 5. Merchant reach and the lead's view of the start; j2 F-07: fixed

Commits `78e4bf4d`, `5aa57498`.

**Merchant home.** A session where the merchant holds a station but that belongs to no project card now gets its own card. It opens the station's accept page directly.
- Rig: after the sign-up window closed at 23:59:59, merchant 9002's card led to 待接受邀请. 接受邀请 returned STATION_ACCEPT APPLIED.
- Shots `f2-5-03` to `05`; walk 75–79.

**Director page.** When the blockers include 待商家接受 or 没有生效的合作单, a 联系商家 row appears. It opens the theme's co-op list, where each entry has 联系合作方. Shots `f2-5-01`, `02`; walk 70–74.

**F-07.** A refused START now shows its reason in the caption. The reason stays after a refresh and clears once the session runs.
- Rig: 「没能开始：当前不在活动服务时段」. Shot `f2-F07-01`.
- The CU-C-76 test assertion was rewritten for the new code shape. It still requires the reason to be shown.
- Test: `r10-f2-merchant-hold.test.js`.

**Limit (L2).** When the merchant also has a project card for the theme, the direct card is not shown. The page is then reached through 承接页 › 更多 › 服务时段, so it is still reachable, but in two steps.

## 6. Live co-op order at the scan; no stranded players; j2 F-10: fixed

Commits `2314dfbc`, `53d48fff`; F-10 in `d963148d`.

Now that 招募 comes first, the invitation and its acceptance happen before the lock. The scan finds a live co-op order.

**Walk:**
- At station 2 before the scan, the player is refused, with the reason in red.
- The merchant scans the real 团码: 「已记录接待该团」 (`coop_group_verify`). All four players then finished stations 1 to 3.

**No stranded players.** A theme cannot go on sale while a station's winning merchant has no co-op order. The refusal names the station: 「「人民广场音乐喷泉」的商家还没有生效的合作单，不能开售」 (rig, theme 990016).
- The same case is also a director start blocker. This is unit-tested only: after the sale gate, the rig cannot create it.
- A theme where no merchant has been accepted by the deadline goes back to draft. This is existing behaviour.

**F-10.** After 确认 in the 招商 layer, the app goes straight to that merchant's terms invitation page. Shots `f2-F10-01` to `03`; walk 26.

**Test:** `StationMerchantContractGateTest` (4).

## 7. j2 findings

Each finding was confirmed at the head before it was fixed. The after-shots come from the walk.

### F-04: fixed (`da813d93`)

**Cause.** Three organiser surfaces decided "players can pay" by whether the theme is platform-owned:
- `ClubCrmAppServiceImpl.topicManageStats`;
- `ClubSessionServiceImpl.onlinePaymentAvailable`;
- `ApiPricingController.afterConfirmMessage`.

The real gate, `assertCollectible` (unchanged), has passed every topic-backed sale since 11-02. All three surfaces now follow the real gate.

**Tests:**
- Updated to the owner's 11-02 decision: `ClubSessionServiceImplTest`, `ApiPricingControllerPaymentNoteTest`, `FreeExplorePriceLockAcceptanceTest`.
- New: `ClubTopicManageStatsServiceTest.clubOwnedPaidThemeIsReportedCollectible`.

**Shots:** walk 42, 45, 70, 37.

### F-06: fixed (`366b404d`). Public-number change for the owner

- The club's session card opens the theme page with `via=session`. The main button is then 「立即报名」 (session picker), not self-play.
- After paying for a session, the main button is 「去票夹」.
- A paid session order now has a bottom 「开始游戏」 that opens play.
- **已参加** on the theme page now counts paid sign-ups, not only people who checked in at a station. This changes a public number. The default is to keep it; it can be reverted.

**Tests:** `r10-j2-f06-session-entry.test.js`, `ApiTopicSessionSignUpTest`. Three contract tests were updated for the new URL and button.

**Shots:** walk 50b, 56–59.

### F-08: fixed (`b83a5add`, plus `5539681f9` from the walk)

**Name source.** The name the person gave when signing up for this club's sessions or themes, newest first. This is the C-05 roster's source.
- The lead's own row uses `club_leader.leader_name`. `club.leader_name` is not used, because it isn't updated after a transfer.

**Who sees real names.** Only people who can govern the club (`canGovernClub`, the customer-list permission).

**Where they appear:**
- the member list;
- the role picker;
- the 成员治理 picker and the 「转让主理人给 林一帆」 confirm;
- 开一场;
- on the director page: team members, 定向广播 and the leaderboard.

**Fallbacks.** Without a sign-up name, the nickname is shown, and the default 「城瘾用户」 becomes 成员#id. The walk found players without a team showing as 成员#id on the leaderboard; `5539681f9` fixed that.

**Tests:** `OrganiserPersonNameTest` (5), `GameSessionOrganiserNamesTest` (2).

**Shots:** walk 63, 65, 93b, 97, 104, 108, 110.

### F-09: fixed (`dd062b82`)

**Cause.** The counts used chapter rows, while a "station" in city orienteering is a node. Each count is now computed from the list it sits above (`pages/topic/utils/recruit-summary.js`), and 已确认 lists only accepted shops.

**Walk:** 0/3 · 0 · 3 → 0/3 · 1 · 3 → 1/3 · 0 · 2 (shots 16, 23, 32).

**Test:** `r10-j2-f09-recruit-counts.test.js`.

### F-11: fixed (`a0376f1e`)

A successful join no longer shows the request-number card. The card still shows when the result is unknown or failed, or when reopening finds an unfinished request.
- `team-recovery-card-gate` is updated: it pinned the old decision.
- Shot: walk 62.

### F-12: fixed (`ed15bce3`)

The logo and cover are now compared by the stored object, not by the freshly signed web address.
- Test: `club-edit-draft.test.js`; the new test fails on the old code.
- Shots: walk 05–09.

### F-13: fixed (`fd8aa3e2`, `30b86389`)

**Merchant.** Group-code refusals made before any write now carry `notRedeemed`, and the merchant sees 未核销 with the reason. Network and unmarked server failures still show 待确认.

**Player.** A new read-only precheck (`routeReceptionError`, a default method on the existing interface) runs before the write transaction. The refusal now becomes a normal failed receipt with its reason. The locked `assertRouteReception` still decides.

**Tests:** `ApiVerifyGroupCodeTest` (22), `PlayNodeAccessReceptionPrecheckTest` (2), `GameSessionRuntimeServiceImplTest` (136).

**Shots:** walk 80, 85, 87.

### F-15: fixed (`40e85b16`)

The 放行 row now sits before the leaderboard. The footer spacer includes the safe area.
- Walk 92: the row is at y 517–550, the footer at 758–780.

### F-16: fixed (`10a8ce6e`, plus `ffc3a3ffc` from the walk)

`/api/club/settlement/summary` now returns a `sessions` list built from the real ledger rows. The page has a new 「各场次结算」 section. Each row shows:
- sales;
- the fee, with its rate shown only when it reproduces the recorded fee exactly;
- the net amount;
- where the money went: 已入你的账户 or 已入原主理人账户.

**After a transfer.** The former 主理人's money stays in their own balance. They see it under 我的 › 我的资产 › 收益明细 and can withdraw it there. No new path was needed.

**Walk:** 105 shows ¥156.00 · 3% ¥4.68 · ¥151.32 · 已入你的账户; 113 shows 已入原主理人账户; 115 shows +¥151.32 活动分润.

**Tests:**
- `ClubSessionSettlementH2Test` (2) and `ApiClubSettlementControllerTest` (11).
- `ClubSettlementSummaryMybatisIntegrationTest`: 6/6 on MySQL. The walk fixed a schema-split bug in this test in `ffc3a3ffc`; Funds MySQL IT passed in CI after that.

## Bugs the walk found and fixed

1. **`4bdc110f9`: answers exposed.** `/api/club/topics` returned `publishedRouteGraphJson`, which holds every station's answers, to any logged-in user. It now clears that field and the other play-config fields. Test: `ApiClubTopicVisibilityTest`.
2. **`d4faffd4a`:** the next step after 确认终价 (item 1).
3. **`5539681f9`:** players without a team showed as 成员#id on the leaderboard (F-08).
4. **`ffc3a3ffc`:** the real-MySQL settlement test was broken by this lane's schema change (F-16).

## Owner decisions needed (with the default I recommend)

1. **Item 4: club-owned city-orienteering sale end defaults to the theme end, and the guided-ticket sale-end rule is skipped for club themes.** Default: keep. Each session has its own clocks.
2. **F-06: 已参加 now counts paid sign-ups.** Default: keep.
3. **Price lock after pricing stays closed to late invitations (item 1).** Default: keep, as above.

## Leads not fixed

Out of this lane, or found late:
- **L1.** Opening 更多 › 开放商家承接申请 (chapter perk recruiting) before sale makes the sale review refuse with internal text: 「必选章节 chapterId=42 缺有效 SUPPLY 供给」. The lead gets no warning.
- **L2.** On the merchant home, the direct station card shows only when there is no project card for the theme (item 5).
- **L3.** Players not in a team are missing from the 定向广播 audience and from 队伍进度. The recap counts contradict each other: 持票 2 / 到场 4 / 完成 2, while 4 paid, 3 checked in and 4 finished. The play page says x/5 with 4 paid (j2 F-18).
- **L4.** Member-facing lists still show 城瘾用户: club home, 贡献榜, and 「来自 城瘾用户」 on the merchant side. F-08 covered organisers only.
- **L5.** `ClubSessionServiceImpl.open` doesn't check the theme stage, so a session can be opened while the theme is 定价中, before players can buy.
- **L6 (money).** `settleActivity` computes a designer share but never pays it. This is safe only while the creator rate is 0.
- **L7.** A refund made after settlement is not subtracted from the session's row on 俱乐部结算.
- **LOW, for f3:**
  - 「主题审核中 · 审核通过后就能开一场」;
  - 帖子 vs 帖文;
  - raw 「#14」 in 核销据点;
  - the 「查找原邀请请求」 row;
  - pink error blocks on 确认终价 and 交接原因 (j2 F-20);
  - 「品」 icon on an 活动分润 row;
  - 「客诉期结束后可提现」 at ¥0.00.

## Gates

At this lane's last code commit `ffc3a3ffc`:
- Full mini unit tests: 16,083 tests, 16,082 pass. The 1 failure is f3's `wt2-player-scene-fixes-contract.test.js` 「帖文空态…」, which has failed since f3's `26bcfb13c` and passes at `26bcfb13c^`. Not this lane. f3 has since updated it (`764cb4cd0`, `349f43c92`); it passes at `0740d1b8c` (5/5).
- `ui-integrity.js`: UI-GATE-0:PASS.
- `build-action-ledger.js --check`: PASS (5697 controls).

Backend, all green: MerchantSignUpWindowDefault 6, RegistrationMerchantDtoDateFormat 1, TopicSaleTimeValidator 6, ClubSaleEndDefault 3, StationMerchantContractGate 4, OrganiserPersonName 5, GameSessionOrganiserNames 2, ApiTopicSessionSignUp 3, ClubTopicManageStatsService 8, ClubSessionSettlementH2 2, ApiClubSettlementController 11, ApiVerifyGroupCode 22, PlayNodeAccessReceptionPrecheck 2, GameSessionRuntimeServiceImpl 136, ApiClubTopicVisibility 8, ClubSettlementSummaryMybatisIntegration 6 (rig MySQL). f2-A also ran 35 related classes (468 tests), green.

CI at `ffc3a3ffc`:
- Funds MySQL IT, Mini followup and Redis recovery passed.
- Main CI was cancelled by a newer push from another lane. Before that, its mini shard failed on the f3 test above.
- Later heads from round 11 fail the Funds IT gate self-test. That is not this lane.

## Unverified on a phone

- Taps on the theme page (立即报名, 去票夹, 下一步 · 去结算). In the rig the handler had to be called directly; j2 saw the same.
- The native date, time and 据点 wheels.
- The editor's full input path.
- The WeChat payment sheet.
- GPS check-in.
- The merchant's camera scan.
- Real decryption of sign-up names.
- The OSS signed images for covers and logos.
- The home-indicator safe area.
- A real withdrawal.

STATUS: PARTIAL - all f2 items and j2 F-04 to F-16 are fixed and the whole journey settles correctly to the fen in the rig. Three things are missing: CI on the final commit is not green, failing one f3 unit test, and the runs were superseded by other lanes; 按核销付费 co-op terms were not walked; owner decisions 1–2 are pending.
