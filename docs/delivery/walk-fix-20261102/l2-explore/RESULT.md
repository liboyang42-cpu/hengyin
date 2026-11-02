# l2-explore result: free exploration, ticket wallet, order page

Branch `claude/walk-fix-1102-l2-explore`, from `origin/master` 014fc0d8.

**Where the evidence comes from.** Screens and HTTP proofs are from the walkthrough rig (`claude/cloud-walk-20261101-rig`), not a phone.
- I ran the rig twice: "before" on master 014fc0d8, "after" on this branch at e166ea23.
- Rig changes stayed out of product code:
  - `up.sh` takes `SHA` from the environment.
  - The club lane's `harness-patches.diff` is applied to the rig checkout only.
- Shots are in `shots/` (390×844, JPEG q70). HTTP transcripts and the proof script are in `proof/`.
- The rig's map is a stand-in. Fonts, safe areas, haptics and real scanning are not verified on a phone.

## #707 proof (most important check): PASS

I walked one non-pure free-exploration theme to completion over real HTTP: topic 990014, 4 chapters, 4 merchants, ¥69.

**Rule.** `cms_topic.complete_rule_json` = `{"nodeCompletion":{"mode":"ALL"}}`, the format the editor writes.
- This is a fixture write to the throwaway rig database. The editor cannot rewrite a theme that is on sale.

**Steps** (`proof/explore_707_proof.py`):
1. The execution club opens a session through `/api/club/open-session` (activity 14).
2. Player 9004 joins through `/api/club/join`.
3. Quote, then create the order (registration 75).
4. Trusted payment fact plus the real `/api/registration/callback`, which answered 204. `payment_status` was never written directly.
5. The four merchants (9002, 9005, 9006, 9007) each redeem through `/api/registration/scan_qr_code` using the player's dynamic code.

**Read-back** (`proof/run-*.txt`):
- `registration_entitlement`: all four chapters went 0 → 1.
- `redemption`: four rows, all `ACTIVE`, one merchant per chapter.
- The 4th redemption returned `completionEarnedXp: 1200`.
- `/api/registration/explore-completion` returned `completed: true, 4/4`.
- `player_badge`: `TOPIC_FINISH_990014` granted once.
- `topic_completion_xp_award`: 1200 XP.
- No completion points row (`ums_member_points_detail` event 13). That matches the fixture chain for player 9009: this topic has no finish points or coupon configured.

**Not exercised.** The player's own shop scan (`/api/play/checkin`) through raw HTTP needs the runtime intent fields, so I left it out. Merchant redemption does not depend on it; the fixture chain does the same.

The repo's own chains (platform → merchant_club → sale_close → player) also PASS on this branch.

## Items

| ID | Status | What changed | Files | How verified |
|---|---|---|---|---|
| P-03 | fixed | **Cause:** the first chapter redemption sets `verification_status=1` on purpose. That flag is the refund gate: once any shop has redeemed, there is no self-service refund. The wallet, 我的 and the order page read that flag as "used up".<br><br>**Change:** a read-only progress view, `exploreProgress` {redeemed, required, completed, ended, active}, is added to explore-pass rows in `/api/registration/list`, `/my-joined` and `/info`. A ticket is "done" only when every required chapter is redeemed or the session has ended.<br>- Wallet keeps the ticket and shows 「已核销 1/4 家」.<br>- The order page shows 「进行中 · 已核销 1/4 家」.<br>- The 已完成 tab moves only on completion.<br><br>**Unchanged:** `verification_status`, the refund rule, and every reader of the flag (finance, merchant counts, refund, settlement). This is read-model only. | `ExploreTicketProgress.java` (new), `CmsRegistration.java`, `RegistrationQueryApplication.java`, `utils/order-status.js`, `signup/index.js`, `profile/order-preview.js`, `scene-member-order-detail/index.js` | `ExploreTicketProgressTest` (5)<br>`order-status.test.js` and `signup-state-contract.test.js`: new cases that fail on master<br>Rig HTTP: after shop 1, master listed the order under 已完成 (`proof/before-lists.txt`); this branch lists it under 待使用 until 4/4 (`proof/run-redeem*.txt`)<br>Shots `P-03-wallet-*`, `P-03-order-*` |
| #707 | proof PASS | See above. | — | — |
| P-13 (free exploration) | fixed | No waiting for a leader.<br>- **Server:** `PlayNodeAccessService.leadGateError` returns early for FREE_COLLECT. The start time is still enforced by `activityTimeError`. A FINISHED/CANCELLED director session still refuses with 「本场已结束」.<br>- **Client:** `/api/club/lead/team-progress` returns `freeEntry`. The play page then hides the gather screen and the 「带领中 · 队长带队中」 bar; that bar also overlapped the title.<br>- **Unchanged:** the lead state row, the leader's settle path, and the city lead gate. | `PlayNodeAccessService.java`, `ApiClubLeadController.java`, `pages/play/index.{js,wxml}` | `ApiPlaySensorResultTest`, `PlayLeadAdmissionFailureTest`: the old "free-collect is held by gather" assertions now assert the gate is skipped<br>`play-free-explore-no-gather.test.js`<br>Rig: after start with no leader action, master shows 「队伍集合中 0/2 · 等队长开始行程」 and this branch shows the pack (shots `P-13-*`) |
| P-14 | fixed | 本店权益 moved up, right after 店铺, so the perk (e.g. 「到店赠精选书签一枚」) is on the card above the steps.<br>A 「出示入场码」 secondary button under 到店三步 opens the existing entry-code sheet (`openEntryQr`). It shows only for a merchant stop that has a ticket and is not yet redeemed. | `pages/play/index.wxml` | `play-free-explore-shop-card-g21.test.js`<br>Rig shots `P-14-before/after`, `P-14-code-after` (code sheet opens) |
| g21 | fixed | 「轻点拆开」 under the unopened pack. It leaves when tearing starts and does not take taps. The CTA is now 「到店打卡」 in both places. | `pages/play/index.{wxml,wxss}`, contract test updated | Shots `g21-before/after` |
| P-12 | fixed | A wrong-shop code now gets one plain sentence: 「这是「<scanned shop>」的码，不是这一家的」.<br>A code from another theme gets 「这不是这一家的码」, with no shop named. | `PlayBasicCommandApplication.java` | `ApiPlayCheckinLiveCodeTest` (2 new).<br>**Not verified in the rig:** every shop was already redeemed by the time I tried. |
| P-22 | partly fixed | - Missing rank data (the TOP0% or no-rank sentinel) now shows 「0」 instead of 「—」, as the owner asked.<br>- 收集册 restyled to the dark player kit: same surface, border and radius as the 总积分排名 card, sans-serif text, dark pile cards. Layout and the three piles are unchanged.<br>- City orders are marked complete on 通关: the server adds `topicCompleted` when the member's `TOPIC_FINISH_<topic>` badge was obtained after this order. The order then reads 已完成 and leaves 待核销.<br>- **Not fixed:** 「累计探索 N家店 · km」. `totalCheckins` counts completed nodes, not distinct shops, and merchant redemptions never add to it (rig: 0 家店 after 4 redemptions). Mileage is added only from chapter mileage on topic completion. That needs a metric decision (see needs owner). | `profile/index.{js,wxss}`, `RegistrationQueryApplication.java`, `ExploreTicketProgress.java`, `utils/order-status.js` | `profile-first-run-state-contract.test.js` (updated)<br>`profile-album-dark-p22.test.js`<br>`order-status.test.js` (city 通关)<br>Shots `P-22-before/after`<br>**Not walked:** the city-order completion, because the rig has no city theme. Unit-tested only. |
| P-19 | fixed | Theme detail sessions carry the executing club (`cms_activity.club_id`) as `clubName`. The session picker shows it (「14:05 · 场次名 · 执行俱乐部」).<br>The membership gate names it: 「这一场由「执行俱乐部」执行，加入后查看」. | `PublicActivitySummaryVO.java`, `ApiTopicController.java`, `ApiActivityController.java`, `session-picker/index.js` | `topic-session-picker-ticket-states-contract.test.js` (new case)<br>Rig HTTP: `proof/api-before.txt` (`clubName: null`) vs `proof/api-after.txt` (named, gate message named) |
| P-20 | fixed | - Organiser of a club session is the club name, not the leader's nickname (rig: 「执行俱乐部主理人」 → 「执行俱乐部」).<br>- City order timeline says 「到集合点开场」, not 「等待到店核销」.<br>- **Not changed:** the 商家消息 consent card. It is a consent flow, so I left it alone. | `RegistrationQueryApplication.attachOrganizer`, `scene-member-order-detail/index.js` | Rig HTTP before/after (`proof/api-*.txt`). The timeline wording is unit-level only: there is no city order in the rig. |
| P-21 | fixed (count) | 「参与商家 N 家」 for free exploration counts distinct approved merchant nodes. These shops join as chapter merchant nodes and never write `cms_registration_merchant`. | `ApiTopicController.java` | Rig HTTP: `registrationMerchantCount` 0 → 4.<br>**Not reproduced:** 「节点介绍 shows one card」. In the rig all 4 nodes come back with their template, so all 4 cards have data.<br>**HARNESS:** the rig could not open the second section of the topic page (shots not possible). |
| P-27 | fixed | - The 票夹 session card shows 「11月2日 16:31 · <place>」 instead of 「场次 · 今天过期」. The expiry stays on the back of the card.<br>- After a city 通关 (`topicCompleted`), the card reads 「已通关」 and the button reads 「查看记录」. | `signup/index.{js,wxml}` | `signup-state-contract.test.js` (2 new)<br>Shot `P-03-wallet-after` shows the time · place line |
| P-28 (free-exploration menu) | fixed | The play-tools menu (我的权益 / 入场码 / 旅程手记 / 足迹) was nested inside the city story stream, which free exploration never renders. It is moved to page level, with the rows unchanged. A 「···」 control (88rpx) next to 探索节点 opens it. | `pages/play/index.{wxml,wxss}` | `play-free-explore-no-gather.test.js`<br>Shots `P-28-before/after` |
| P-37 | fixed | The next stop in free exploration skips shops whose game is done and that are only waiting for counter redemption. If only such shops remain, no stop is suggested (before, it pointed at the current shop at 「0 米」). | `pages/play/index.js` `computeNext` | `play-route-page-behavior.test.js` (new case, fails on master) |
| P-39 | fixed | 探店图鉴 now shows the shop's node name, falling back to the merchant name: 「第1章 咖啡 · 样板咖啡第1站」. Time is sent as Beijing 「yyyy-MM-dd HH:mm:ss」 and shown as 「11-02 16:31」. Existing stamp rows keep their old text, because the row does not store the merchant. | `ExploreCollectibleServiceImpl.java`, `ExploreCompletionVO.java`, `scene-member-order-detail/index.js` | `ExploreCollectibleShopNameTest` (2)<br>Rig HTTP before (「…· 探店日样板咖啡」, `2026-11-02T15:34:01.000+08:00`) vs after (`proof/api-after.txt`) |

## Needs owner

Round 1 asked three questions. The owner answered all three on 2026-11-02 (see Round 2 below). Nothing new needs the owner.

## Round 2 (owner decisions, 2026-11-02)

Waited for the branch to be quiet before starting, as ROUND2.md asks. Another session merged master at 09:34 (`80022816`); I kept that merge and dropped my identical local one. Nothing was force-pushed.

The evidence comes from the rig, not a phone. The backend ran this branch. The "before" shots use the browser bundle built from `80022816`, the branch tip before round 2. The "after" shots use this branch. Both runs used the same database and state.

| ID | Status | What changed | Files | How verified |
|---|---|---|---|---|
| P-22 | fixed | **Label:** the achievements card now reads 「累计打卡」 「N 处」. The km figure is hidden until mileage has a real source.<br><br>**What N counts:** shops with an active redemption on the player's tickets (counted once per chapter), plus completed city stations. A city station is a node with no merchant, in a theme that is not free exploration (counted once per node).<br><br>**No double counting:** a shop is counted only through its redemption. Finishing the game at a shop does not add to N.<br><br>**Where it comes from:** `/api/play/growth` returns the new read-only field `checkinPlaces`. The page reads it as is. If the field is missing, the page shows 0 and the rest of the card still loads.<br><br>**Unchanged:** `totalCheckins`, levels, the 首访 check and the other readers. | `PlayerGrowthMapper.{java,xml}`, `IPlayerProgressionService.java`, `PlayerProgressionServiceImpl.java`, `PlayPlayerQueryApplication.java`, `cy/profile/index.{js,wxml}`, screenshot fixtures | `PlayGrowthCheckinPlacesTest` (3), `PlayerProgressionServiceImplTest` (14): all pass.<br>`profile-first-run-state-contract.test.js`: the new cases fail on the old code.<br>**Rig HTTP and database read-back** (`proof/p22_checkin_places_proof.py`, `proof/p22-run.txt`), player 9009, P22-PROOF: PASS:<br>- 4 redeemed shops gives 4.<br>- Fixture: a shop game also done. Still 4.<br>- Fixture: a city theme station done. 5.<br>- A player with no data gets 0.<br>The SQL also passed the app's Druid wall.<br>Shots `P-22r2-before/after`: 「累计探索 0家店 · 0.0km」 becomes 「累计打卡 5 处」. The 5 includes the city-station fixture. |
| P-28 | fixed | In free exploration (`lead.freeEntry`), the play-tools menu no longer shows 「队伍位置」. The leader-only rows and city sessions are unchanged. | `pages/play/index.wxml` | `play-free-explore-no-gather.test.js`: the new case fails on the old code.<br>Rig session 14, player 9004: shots `P-28r2-before/after`. |
| P-28 「切换章节」 | reported, not changed | In the rig it opens 「选择章节」 with all 4 chapters (shot `P-28r2-switch-chapter`). Three things look off:<br>1. The pack page already shows every shop card, so the picker repeats what is on screen.<br>2. Each row says the chapter number twice (「第 1 章」 above 「第1章 咖啡」).<br>3. From code (`openStackOrChapters`): with one chapter or none, the row labelled 「切换章节」 opens the card pack instead. | — | Rig shot and code reading |
| Wallet subtitle | no change, owner confirmed | Keeps both lines. | — | — |

## Notes

- **Other lanes.** I touched `pages/play` (same file as l1-play), but only free-exploration paths:
  - the gather condition;
  - the pack hint;
  - the shop card;
  - `computeNext` mode 2;
  - the tools menu moved to page level. l1 should re-check that the city menu still opens from the story stream; it rendered fine in tests.
- **Gates:** `UI-GATE-0:PASS`. The action ledger was rebuilt (line shifts plus the new controls). No baseline entries were added.
- **Tests run:**
  - node: play-*, free-explore*, order-status*, signup-state-contract, profile-first-run-state-contract, order-detail*, topic-session-picker.
  - Java (`-Dtest`): 358 tests, all pass except `PublicActivityScopeGateMyBatisTest` (2 errors). That IT needs its own MySQL database, which was not available in this container ("Communications link failure"). It is not related to this diff.

STATUS: PARTIAL - P-12 and the city-order paths of P-20/P-22/P-27 are unit-tested but not walked in the rig (no city theme, shops already redeemed); the P-22 city-station count is proven only with a fixture city theme
