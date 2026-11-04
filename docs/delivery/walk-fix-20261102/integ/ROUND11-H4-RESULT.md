# Round 11, lane h4-club-money: result

Branch `integ/launch-test-20261103`. Work started at `ba3c2207`. Other lanes pushed at the same time.

**This is not a phone.** Item 1 was walked on the cloud rig: the real jar, MySQL 8.0.45 and Redis 7.4. It was driven over real HTTP with real tokens. No browser UI was driven in that walk. No screenshots were taken this round; see "Not verified".

## Summary

| # | Item | Status |
|---|---|---|
| 1 | Walk 按核销付费 terms to settlement | **walked. The merchant is never paid. Needs an owner money rule; not changed** |
| 2 | L7: a refund after settlement is subtracted from the session row on 俱乐部结算 | fixed, and proved in the rig |
| 3 | L6: designer share computed but never paid | guard added. The rule is unchanged |
| 4 | L1: 开放商家承接申请 before sale; plain Chinese for reviewers | fixed |
| 5 | L5: no session while the theme isn't on sale | fixed |
| 6 | L3: unteamed players in 定向广播 and 队伍进度; counts agree | fixed. One 到场 rule is left for the owner |
| 7 | L4 real names, and L2 | fixed. Ordinary members still see nicknames, as the privacy rule says |
| 8 | LOW | fixed, except the pink error blocks (f3's) |
| — | `UmsMemberRankMySqlIT` after g3's `4751ec46` | not broken. The IT was extended to pin g3's rules |

## 1. 按核销付费 walk: the merchant gets nothing (needs owner)

Full read-backs are in `fix-shots/h4-walk-readbacks.txt` (`515d5e85`), at jar `31645fb8`.

**The setup.**
- Lead 990005 runs club 990004 and theme 990015, which has three stations.
- Merchant 9002 is on station 71.
- The merchant accepted an invitation with shareMode 2, ¥8 per verified person. That froze coop order 1 as FIXED_PER_FULFILLMENT.
- Pricing built the ¥8 into the price floor (costC 8.0, priceMin 8.89).
- The session was ¥39, and four players paid through the real callback.

**Money at each step, ¥:**

| Step | Collected | Refunded | Platform 9001 | Lead 990005 | Merchant 9002 |
|---|---|---|---|---|---|
| 4 paid | 156.00 | 0 | ledger 4 × 1.17 | ledger 4 × 37.83 | 0 |
| 50% refund of reg 77, within 24 h of the meeting time | 156.00 | 19.50 | – | – | 0 |
| Merchant scans the real group code | 156.00 | 19.50 | – | – | 0 |
| Session settlement (real job 145) | 156.00 | 19.50 | **4.10** | **132.40** | **0** |
| Organiser refund of reg 76 after settlement (¥39) | 156.00 | 58.50 | 4.10, plus a −1.17 line | 94.57 (−37.83) | 0 |

At every step, platform plus lead equals what was collected minus what was refunded:
- After settlement: 4.10 + 132.40 = 136.50 = 156.00 − 19.50.
- After the later refund, the clawback lines are −1.17 and −37.83, which add to −39.00.

**What breaks:**
- `settleActivity` has no merchant step. Everything after the platform's 3% goes to the lead.
- The merchant's scan writes only `coop_group_verify`. It writes no per-person fulfilment, so nothing counts the people the ¥8 is owed for.
- The theme-level co-op settlement refuses every run and raises an alarm each time:
  - With the 50% refund in place: `MANUAL_REPAIR:REFUND_NET_BASIS_UNPROVEN`.
  - Without a refund, it would still refuse on session tickets: `ACTIVITY_CASH_BASIS_UNSUPPORTED`.
  - No `coop_settlement` row is ever written, so nothing is paid twice.

**Money rule for the owner (not changed).** Who pays the merchant's per-person fee on a club session, from which share, and how heads are counted, is a money rule. Recommended default:
- At session settlement, pay the merchant ¥8 × the paid, checked-in players of each session that the merchant group-scanned.
- Take it from the lead's share: lead = sales − platform − merchant fee. This matches how pricing already puts the ¥8 into the price floor.
- Let the theme-level co-op settlement close those orders without counting them twice.

Stopgap until that's decided: refuse 按核销付费 and 分成 invitations on club themes whose sales are session tickets, with a plain reason. Today the product shows terms it cannot pay.

**Two smaller findings (also money, not changed):**
- The ¥19.50 kept from the half refund is paid out at settlement, but no `settlement_detail` line records it. The ledger therefore reads 113.49 for the lead and 3.51 for the platform, against balances of 132.40 and 4.10. The balances are right; the bookkeeping is short.
- A refund after settlement doesn't debit the platform's balance. Only a −1.17 adjustment line is written.

**Not walked:**
- Station answers over the API (f2 walked play in the UI).
- The lead released chapter 2 and finished over the API, not the UI.
- A player refund after settlement is refused by policy (「场次已开始,不可退款」), so the post-settlement check used the organiser path, `/api/registration/cancel-by-owner`.
- Three of the four players checked in.

## 2. L7: fixed

Commits `31645fb8` (code), `21a27c0a` and `14a4e674` (two tests that build their own tables needed the three new ones).

**Cause.** The session row summed only the original credit flows. A refund after settlement writes negative `settlement_detail` lines (`source_type=refund`, status 1, including any part recorded as debt), and those were never read.

**Change.** `CoopSettlementMapper.selectClubSessionSettlements` now subtracts the session's merchant and platform clawback lines from 到账 and 服务费. Sales is derived from those two.

**Proof:**
- `ClubSessionSettlementH2Test.refundAfterSettlementIsSubtractedFromTheSessionRow`: 136.50 / 4.10 / 132.40. Clawbacks for another session's refund and for unsettled lines are not counted. The class passes 3/3.
- `ClubSettlementSummaryMybatisIntegrationTest` passes 6/6 on real MySQL 8.
- In the rig, on the real jar, the lead's row read ¥136.50 / ¥4.10 / ¥132.40. After the ¥39 refund after settlement it read ¥97.50 / ¥2.93 / ¥94.57.

## 3. L6: guard added, rule unchanged

Commit `770c9dfd`.

**Can production have a non-zero `creator_rate` for activities?** Not through any app path:
- Writes are refused: `SettlementRuleServiceImpl.requireZeroCreatorRateForH8BizTypes` rejects a non-zero creator rate for `activity`, `topic` and `topic_club`.
- Stored rows are reset to 0 by the H8 migration and by `migration_20261103_settlement_rule_three_percent.sql`.
- The settlement batch already refuses the whole batch on a non-zero rate (existing test `activity_creatorRateNonZeroRefusesEvenWhenThreeRatesStillSumToOne`).

Only a direct database edit could produce one. Even then the batch check refuses, so today the share is refused rather than silently dropped.

**Added anyway.** `calculateActivityCommissions` now refuses settlement wherever a non-zero designer amount is computed, because nothing pays it. This covers `settleActivity` and the release of complaint-held registrations, without relying on the batch check.

Test `SettlementRateConvergenceTest.activity_nonZeroDesignerShareIsRefusedWhereItIsComputedNotSilentlyDropped` covers the refusal. Its control is ¥97.50 at 3%: 2.93 + 94.57. The class passes 24/24.

## 4. L1: fixed

Commit `ceee37bb`.

**Warning to the lead.** In 更多 › 开放商家承接, opening a chapter now asks for confirmation first: 「开放后，这一章要有商家承接成功才能开售。没有商家承接时，提交开售前请回到这里关闭。」. The request is sent only after 开放. Closing needs no confirmation.

**Reviewer text.** The sale review's refusal now reads, for example:
- City orienteering: 「「第一章 · 喷泉」还没有商家承接成功，不能开售。请主理人在这一章关闭商家承接，或等商家承接后再提交(章节 42)」.
- 自由探索: 「请等商家承接后再提交」.

**Tests:**
- `r11-h4-chapter-recruit-confirm.test.js` passes 2/2.
- `TopicSaleFinalizationServiceImplTest` passes 44/44. Two cases pinned the old internal text; they are updated.
- `ExploreAllianceMvpRealDbIT` assertion text is updated. No test was added or removed.

## 5. L5: fixed

Commit `6d221050`.

`ClubSessionServiceImpl.open` now refuses when the theme is in these stages:
- 立项待审, 招募 or 定价与开售审核 (7, 1, 2): 「主题还没开售，开售后才能开一场」.
- 结算中 or 已关闭 (5, 6): 「主题已结束售卖，不能再开一场」.

Themes with no lifecycle (older themes and plain UGC), 草稿, 售票中 and 进行中 can still be opened. That is the same rule the club page uses for its 开一场 entry. All three entry points go through `open()`: `ApiClubController`, event-ops series, and repeat.

Test `ClubSessionServiceImplTest.topicNotOnSale_refusesOpeningBeforeAnyWrite`. The class passes 26/26, and `ClubEventOps*` and `E01_12Test` are green.

## 6. L3: fixed

Commits `199d7bea` and `d3cb45f2`.

**Unteamed paid players:**
- They now appear in 定向广播 and in 队伍进度, one row each, with their real name and progress. They are not counted as a team.
- 「全部」 and 按人 broadcasts go to every paid, non-refunded player of the session. 队伍 and 角色 broadcasts still pick by team.
- The 「没人可送达」 check now counts paid players.

**Counts:**
- Play page 「到场 x/N 人」: N is now paid, non-refunded players only. The lead doesn't buy a ticket and is no longer counted, so 4 payers no longer read /5.
- Recap 持票, 有完成进度, and each station's arrived and completed counts no longer require being in a team.

**Left for the owner.** 到场 still counts a check-in, a merchant scan, or any station progress. That is decision C-10 and the roster's rule. This is why j2 saw 到场 4 with 3 check-ins. Counting lead check-ins only would split the recap from the roster. Routes that already finished keep their saved recap.

**Tests:**
- `GameSessionRuntimeServiceImplTest` 137/137, including the new `clubViewListsPaidPlayersWithoutTeamAndAllBroadcastReachesThem`.
- `GameSessionRuntimeMapperSqlTest` 25/25. It pinned the 2026-11-01 rule that 按人 broadcasts need a team; updated.
- `ClubLeadFlowTest` 7/7. It counted the lead in N; updated.
- `club-game-director-contract.test.js` 50/50.
- The new queries ran on the rig's MySQL with ONLY_FULL_GROUP_BY on.

## 7. L4 and L2: fixed

Commits `67d9a950` (L4) and `895ae4c8` (L2).

**Privacy rule applied** (j2 F-08, `OrganiserPersonName`): only people who can govern the club see sign-up names. Ordinary members see each other's nicknames. The rule was not widened.

**L4:**
- **贡献榜** now uses the same naming as the member list. Test `OrganiserPersonNameTest.contributionBoardFollowsTheSameNameRuleAsTheMemberList`.
- **Club home member list:** already correct for organisers (f2 `b83a5add`). An ordinary member still sees 城瘾用户 for accounts that never set a nickname. That is the rule; changing it is the owner's call.
- **「来自 城瘾用户」 on the merchant side:** when a club lead without a merchant profile invites, the merchant now sees the club's name. Contact details stay hidden until the invite is accepted. Test `ApiCoopControllerPartnerNodeTest.list_receivedFromClubLeadWithoutMerchantProfile_namesTheClubNotTheDefaultNickname`.

**L2.** A session's station card was being matched to the theme-level project card, which never shows the station. Now only an activity card for the same session counts as already shown. So the station card appears whenever a project card exists, one per session. New test in `r10-f2-merchant-hold.test.js`; it fails on the old code.

## 8. LOW: fixed

Commit `20150c6b`.

- 「主题审核中 · 审核通过后就能开一场」: the second half is cut.
- 帖子 → 帖文 in user-visible text:
  - the roam publish error;
  - 设置 › 帖文处置与申诉;
  - the merchant 合作 tab, empty states, error and loading text.

  Comments, backend logs, admin `@Excel` labels and the admin web UI are left alone.
- 核销据点 shows the station's name only. 「据点 #id」 is used only when the station has no name. `coop-supply-station-contract.test.js` pinned the id in the confirm text; it now asserts the id is not shown.
- 「查找原邀请请求」 appears only while an invite's result is unknown.
- 收益明细 badges follow the row's reason: 活动分润 → 活, 退款冲销 → 退, 创作 → 创, 品牌 → 品.
- 「客诉期结束后可提现」 shows only when withdrawable is ¥0 and money is actually waiting in the complaint period.
- Pink error blocks (j2 F-20): f3's, not touched here.

Test `r11-h4-low-wording.test.js` passes 4/4.

## Rank IT (`UmsMemberRankMySqlIT`)

Commit `5690cf2a`.

- Run on real MySQL 8 at the current head, it passed 1/1 with 0 skipped. g3's `4751ec46` did not break it, because the seeded rows all defaulted to `role='player'` and had points above 0.
- Neither was wrong, but the IT no longer covered g3's rules. It now seeds a high-point merchant account and a 0-point player. It asserts the points ranking includes only players with points, and that both of those get no rank.
- Against the pre-g3 mapper it fails, which is the negative control: 「point member 990011 expected 1 but was 2」.
- It's still one `@Test`, so the registry counts are unchanged.

## Gates

**Run locally:**
- `ui-integrity.js`: UI-GATE-0:PASS.
- `build-action-ledger.js --check`: PASS.
- Full mini unit suite (the L4/LOW fork ran it): 16,147 tests, 16,146 pass. The one failure was ledger drift, which was rebuilt; that test then passed on its own. The full suite was not rerun after the rebuild.
- Main package, by preflight's count: 3,115,140 → 3,115,292 bytes, under 3,145,728. L1 is in subpackage `pages/topic`, and L3 is in `pages/club`.
- No migrations, and no new public Service, Mapper or Controller.

**CI:** see the CI note at the end of this file.

## Not verified

- **No screenshots this round.** The rig was busy with the money walk, so every visible change is proved by unit tests only:
  - the 开放商家承接 confirm;
  - the director's sheets with an unteamed player;
  - the merchant home station card;
  - the 收益明细 badges;
  - the settlement hint;
  - the 帖文 wording;
  - the invite station name.
- **Not checked on a phone:**
  - real broadcast delivery;
  - WeChat payment and refund money movement;
  - the merchant's camera scan.

## Needs the owner

1. Merchant pay on club sessions under 按核销付费 or 分成 terms. Recommended default and stopgap are in item 1.
2. Whether 到场 should count lead check-ins only. Recommended: keep C-10 as it is (item 6).
3. Whether ordinary members should see something other than 城瘾用户 for members without a nickname (item 7).
4. The missing `settlement_detail` line for the kept half of a 50% refund, and that the platform isn't debited on a refund after settlement (item 1). Bookkeeping only; balances are correct.

## CI

No commit has all workflows green. The red jobs are listed below, with their owners.

**Funds MySQL IT: green** on `aacd393c`, which contains every code commit from this lane up to `14a4e674`.
- The first red here (`bc40b85e`) was mine. L7's new query needs three tables that the IT and an H2 test didn't build. Fixed in `21a27c0a` and `14a4e674`.

**Main CI** at `c3605a27`, which contains all of this lane's code: 27 jobs passed and 3 failed.
- **Backend tests:** `CityDirectorConsoleMySqlIT`, 3 cases. This is mine. The IT pinned the old rule that a paid player who left their team drops out of the session's roster and 全员 broadcast. L3 reverses that.
  - Fixed in the commit after this note. Only a refund now removes a player from 全员.
  - It passes 6/6 on real MySQL 8. No test was added or removed.
- **Mini program static gates:** not this lane. These are the same three reds f3 named:
  - g3's `growthcenter` `{{me.rank}}名`;
  - g3's `session-picker` `ticketsError`;
  - f2's `merchantinfo` `hostMerchants`.

  I reran the three sub-gates locally (without their self-tests); nothing from h4 is flagged.
- **Migration safety gate:** not this lane. 「SQL inventory is unsafe」 first appears at h2's `31222f8b`, which added a migration; the same check makes 「Isolated authenticated Redis recovery」 red from that commit on. That job was green at this lane's `20150c6b`, and this lane adds no migrations.

Main CI on later heads was cancelled by other lanes' pushes. I have not seen main CI on the commit with the IT update.

STATUS: PARTIAL - the merchant is never paid under 按核销付费 on club sessions, and that needs an owner money rule (item 1). No screenshots were taken. Main CI has not run green on a commit containing all of this lane's work: the red jobs left at `c3605a27` belong to other lanes, and mine is fixed after it.
