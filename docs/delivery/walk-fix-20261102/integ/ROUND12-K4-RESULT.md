# Round 12, lane k4-club-flow: result

- **Branch:** `integ/launch-test-20261103`. Work was done on a private local branch and rebased before every push; nothing was force-pushed.
- **My commits:**
  - `90b6d42d` N-01 to N-05
  - `9e8d7c9c` N-07, N-09
  - `3ee591fc` ledger
  - `7d004e02` follow-ups from the rig walk and LOW items
  - `c33e843e` message order
- **Rig:**
  - Real jar, real MySQL 8 and Redis 7, and the real mini-program bundle in headless Chrome at 390×844.
  - **This is not a phone.**
  - Most walks ran on candidate `0aafbdf6`, which holds my N-01 to N-09 commits on top of `d36638a3`.
  - The N-02 and N-03 re-walks ran on `43d4cf29`, which holds all my code.
  - All data is fictional.
- **Shots:** `integ/fix-shots/k4-*.jpg`, with step logs in `k4-cs-LOG.md` (club session) and `k4-fe-LOG.md` (自由探索 sale). I looked at the shots I cite.
- **Money and settlement code belongs to k1.** I changed none of it. Where a finding needs a change there, it is listed under "For root / k1" below.

## For root / k1 (needs a decision or a change outside this lane)

1. **BLOCKER: a club-owned 自由探索 theme never settles.**
   - **What happened in the rig:** ¥58.00 was sold (2 × ¥29, regs 77 and 78), stamped at 2 shops and finished. The real jobs 145 and 146 then ran 15 days after the end; both logged SUCCESS and settled nothing (0 rows anywhere).
   - **Why:**
     - `CmsActivityMapper.xml` ~503-510 (activities for settlement) and `CmsTopicMapper.xml` ~1295 both exclude `product_type = 2`.
     - `CmsTopicServiceImpl.settleTopic` throws `FREE_EXPLORE_LEGACY_SETTLEMENT_FORBIDDEN`.
     - The only other path, `/business/edition-final`, returns `EDITION_TERMS_MISSING`, because those terms are frozen only for FOUR_STORE_REDEEM (`TopicSaleFinalizationServiceImpl` ~177-201).
   - **The same exclusion hides the money from the owner:** `ActivitySettlementHoldMapper.xml:93` also drops product_type 2, so 俱乐部结算 and the wallet show ¥0 pending after the sale (`k4-fe-42a`, `k4-fe-42b`).
   - **Counterfactual, as a labelled FIX:** product_type was set to 1 around one job 145 run, then restored. The session path then paid the platform ¥1.74 (= 58.00 × 3%) and the owner ¥56.26. Merchants got ¥0.00 (PERK terms carry no cash). The parts sum to ¥58.00. The withdrawable date was the end + 14 days: not available at +14 days − 1 h, available at +14 days + 1 min (`k4-fe-43*`, `k4-fe-44*`).
   - **Recommendation:** until club 自由探索 has a real settlement path, keep it from going on sale. Today three product gates (2 to 4 below) happen to stop it. **I left those gates alone on purpose:** opening them would let money be collected that never pays out.
2. **确认终价 makes a ticket with no times.**
   - It creates the 自由探索 「门票」 with no start, end or sign-up times (`PricingServiceImpl` ~498-518).
   - The platform's canonical15 save then fails with `SALE_SNAPSHOT_MISSING_SESSION_WINDOWS`, and the admin `PUT /business/ticket` answers 409.
   - In the rig: FIX, ticket times written in the DB.
3. **Club 自由探索 has no `sale_end_at`.**
   - `CmsTopicServiceImpl.defaultClubSaleEnd` fills it only for 城市定向; `ClubSaleEndDefaultTest` pins that 自由探索 is left alone.
   - Sale approval then fails with 「主题没有售票截止时间」.
   - In the rig: FIX, set in the DB.
4. **Session and sale block each other.**
   - Sale approval needs a session (`SALE_TICKET_PROJECTION_MISSING`).
   - `ClubSessionServiceImpl.requireTopicOpenForSessions` (~124-132) refuses to open a session before the sale unless the club holds a co-op order, and the owning club can't invite itself.
   - In the rig: FIX, lifecycle 2→0 around one real 开一场 call, then set back.
5. **Smaller findings from the same walk** (not fixed, not mine to judge):
   - A merchant could redeem reg 78 at 14:23 for a 14:28 session while the play page said 「活动未开始」.
   - `verification_status` becomes 1 after the first of two shops.
   - The ticket freeze refusal says 「已进入 SELLING」 while the theme is still in pricing.
   - The quote's refund deadline and the stored `refund_deadline` differ by 24 h.
   - `game/session/view` answers 500 on every play-page open of that theme.
   - The 自由探索 pricing page still offers 有人带/无人带 and 带队成本.
   - The session ticket keeps a sign-up deadline (11-12) after the session ends (11-05).
6. **No shared-branch problem remains from my side.** k2's `.sql` under `docs/` briefly broke the migration ledger (and with it the rig schema build); k2 fixed it themselves in `94f75325`.

## Items

### 1. j2 findings

| ID | Status | What changed | Files | Proof | Unverified |
|---|---|---|---|---|---|
| N-01 terms invitation can't be reopened | **fixed** | Project home sends each 已中标 merchant's `inviteState` (none/sent/accepted). The 招商 layer's 已确认 row reads 未发邀约 or 邀约待接受 and carries a 「发邀约」 pill until an invitation exists. 我的合作 › 收到的 has the same button on 已中标 cards. Club 管理 「现在要做」 shows **发邀约** with the station names that have no co-op order (「X 还没有合作单，不能开售」); that list is sent only to the theme's publisher. | `ProjectHomeReadServiceImpl`, `IProjectHomeReadService`, `ApiClubController` (topics), `ApiCoopController` (candidates/received), `CmsTopic`; `project-host` wxml, `merchantinfo.js`, `coop/list`, `club/detail` | `ProjectHomeTermsInviteStateTest` (4), `r12-k4-club-flow.test.js`. Rig `k4-cs-01`…`17`: unsent → 未发邀约 + pill (`k4-cs-04`), 收到的 button (`k4-cs-05`), 现在要做 发邀约 (`k4-cs-07`), sent → 邀约待接受 (`k4-cs-12`), accepted → 已中标 (`k4-cs-16`). DB: coop_invite 990024 status 1, coop_order 1 | The merchant name is shortened to 「探店日样…」 next to the tag (`k4-cs-04`) |
| N-02 merchant can't tell they must accept | **fixed** | Merchant entries carry `invitedCount`; the client now keeps it (the first rig walk caught that it was dropped). The station card shows 「待接受邀请」, sorts first, and gives the time to the minute. 我的消息 gets a 「站点邀请 · N 个站点待接受邀请 · 「场次」」 line built from real `game_station_run` rows, and tapping it opens the station page. | `GameSessionRuntimeMapper.xml`, `GameMerchantEntryView`, `ApiMerchantController` (events), `game-session-client.js`, `game-session-merchant.js`, `inbox-preview.js`, `merchant/index` | `game-session-client.test.js`, `r12-k4-club-flow.test.js`. Rig: the card comes first with 待接受邀请 (`k4-re-02`; FIX: station 1 set back to INVITED for this shot, then restored to READY); message and tap to station page (`k4-cs-26`, `k4-cs-27`); after 接受邀请 the message is gone (`k4-cs-30b`) | 「全部 1」 still counts projects, not station cards. The tab bar sits over the carousel on merchant home (rig or product, not judged) |
| N-03 refund roster | **fixed** | Names: the roster read from the public summary query, which has no sign-up name or time, so k3's name rule always fell back to 「城瘾用户」. It now reads a roster-only summary (`selectRosterSummaries`, with encrypted sign-up name and time); the public summary is unchanged. Tapping a row opens 报名与退款 detail. The retired 「募集中」 is gone. An empty template ticket block is hidden when other tickets have sign-ups. | `ViewRegistrationMapper.xml/.java`, `IViewRegistrationService`, impl, `ApiClubController`; `club/enroll` | Roster privacy and session-label tests updated. Rig: 周晚晴 / 陆知行 / 顾南星 (`k4-re-01`), row opens detail (`k4-cs-24`), no 募集中 and no empty template block (`k4-cs-23b`) | Every row is still 「待核销」 after the game, because club-session tickets aren't scanned per player (owner rule: scans record taking part). Not changed |
| N-04 开始活动 refusal out of sight | **fixed** | The refusal sits in red right above the bottom 开始活动 button. The server message gives the session window (「（本场 11-05 20:00 至 11-05 23:59）」), and station windows are to the minute. | `GameSessionRuntimeServiceImpl`, `club/topic-detail` wxml/wxss | `GameSessionRuntimeServiceImplTest` (2 assertions), `r10-f2-merchant-hold.test.js`. Rig `k4-cs-31`, `k4-cs-32` | The line wraps inside 「11-05」 (`k4-cs-31`) |
| N-05 自由探索 after 确认终价 | **fixed (owner screen and ticket name)** | 「现在要做」 reads 开售审核中 once a priced 自由探索 has no review task yet. The pricing ticket for 自由探索 is named 「门票」, not 「有人带」. | `ApiClubController`, `PricingServiceImpl` (name only) | Rig `k4-fe-21` (开售审核中), DB oms_ticket 76/78 「门票」, `k4-fe-25c` | Players still see 「定价中 · 未开售」 until sale approval, which is true. The 本期可售 / 余席 and 参与品牌 lines on the player ticket block were not changed |
| N-06 theme-page taps | **fixed (rig)** | See item 3 | — | — | — |
| N-07 自由探索 招商 list doesn't refresh | **fixed** | After 确认通过 the page now reloads project home first and then force-reloads both review lists. Before, the project-home read invalidated the in-flight list reads, so the list stayed stale. | `merchantinfo.js` | `r12-k4-club-flow.test.js` | The counts were not re-walked after the fix. The count logic follows the lists, so a stale list explains the wrong counts j2 saw |
| N-08 names after transfer | **already fixed** by k3 (`b9df1ecd`, owner nickname everywhere) | — | — | — | Not re-walked |
| N-09 on-sale theme says 管理项目 | **fixed** | With the theme on sale (lifecycle 3/4) and the merchant accepted, 「现在要做」 is 开一场. | `club/detail/index.js` | Rig `k4-cs-18` | After a session with paid players it still says 开一场 (noted) |
| N-10 merchant theme page | **not changed** | The cover-only first screen is by design (j2). 「封面暂不可用」 and the cut-off 「已选」 line were not touched | — | — | — |
| N-11 hold-screen map | **not changed** | `pages/play` belongs to k2 | — | — | — |
| N-12 club/edit denial | **by design** | The sheet has no buttons and auto-returns after 2 s (`access-gate` component, design frame 356-5220). In the rig it looks stuck because rig timers run slow. 9003/7001 is seed data | — | — | Phone |
| N-13 settlement money formats | **fixed** | Backend money text matches the app: 「¥151.32」 and 「−¥2.00」. 商家计酬 with no records is hidden on 俱乐部结算. | `ApiClubSettlementController` and 3 tests; `club-direct-ledger` | Java tests updated | 「联系平台客服提现」 next to the in-app balance is an owner withdrawal matter; not changed |
| N-14 captions | **partly fixed** | Removed: the merchant station page's 「仅显示本站安全聚合…」, the station apply 「决定这一站能接多少团…」, and the invite card 「对方接受并成单后锁定」. k3 already cut 开一场, apply and pricing. Left: the order/team line 「组队与否不影响…」 (a consequence statement, test-pinned), the director's 「玩家进度与发放状态保持不变」 (a consequence of 放行), and the theme page points rule (player page) | merchant `game-node`, `merchantapply`, `coop/invite` | `ui-integrity` PASS | — |
| N-15 smaller | **partly fixed** | 管理 no longer shows a primary button named like the heading in 主题审核中 / 开售审核中. The club owner's 确认终价 is dark (`&club=1`). | `club/detail`, `topic/pricing`, `route-matrix` role override | `r10-f2-club-next-step.test.js`, route-matrix | Not re-walked after the change. Counts in 报名参与者, 「1 队在等」, team name 「队伍」, gather overlay (`pages/play`, k2) and the inbox wording are unchanged |
| F-19 team join mode | **explained** | 「仅邀请可加入」 on the order page is an off switch for the *next* team, so the data doesn't contradict itself | — | — | — |
| F-22 票夹 after the end | **fixed** | A completed ticket shows no QR and no 「今天过期」 | `subpackageMember/signup` | — | Not re-walked |
| F-23 「欢迎加入」 for the former owner | **already fixed** (f3). j2 saw it because rig storage resets on every walk | — | — | — | Phone |

### 2. 自由探索 sale all the way: **PARTIAL**

Walked on screen (club 外滩慢逛社 990005, theme 990016, merchants 9002 and 9005):
- 主理人 and club setup.
- Merchants apply, the owner approves, merchants fill supply.
- Deadline job: TIME SHIFT, then the real job 149.
- 确认终价 ¥29.
- Admin sale review through the real admin API with a real admin token.
- Two players pay ¥29.
- Stamps at 2 shops; the scan uses the HARNESS camera seam.
- 票夹 keeps the ticket after the first stamp: 「已核销 1/2 家」 (`k4-fe-37a`).
- Ending: 已通关 · 2/2 家 (`k4-fe-41*`).
- A scan moved no money: 0 balance rows after all payments and scans.

What failed or needed fixtures:
- Settlement is the blocker above.
- Going on sale needed 3 labelled DB fixtures past gates 2 to 4 above.
- The theme was created through the editor API, because the editor's location picker loops in the rig (HARNESS).

Full table and SQL: `k4-fe-LOG.md`.

### 3. Theme-page taps: **fixed in the rig; not a product overlay**

- **Cause:** the rig runtime called `setPointerCapture` on `touchstart` for any element with touch handlers, so the click was retargeted to `.topic`. No product element covers the button (j2's probe; `elementFromPoint` hits `.dock-cta`).
- **Fix:** rig only. Capture now starts on the first drag move (`mpweb/src/runtime.js`, rig worktree; not product code).
- **Proof:** plain `tap` steps on 立即报名, the ticket row and 下一步 · 去结算 now fire. 3 of 3 members reached checkout and paid (`k4-cs-19`…`22`), and so did `k4-fe-24`.
- **Unverified:** a phone should still confirm with a real tap.

## Gates

- `node tests/lint/ui-integrity.js`: `UI-GATE-0:PASS`.
- `build-action-ledger.js --check`: PASS at my last ledger rebuild (5718 controls).
- `action-ledger.test.js`: shared-branch HEAD pins 5719 controls while the tree has 5718. That pin came from another lane's merge (`848878cc`) and was already off at upstream before my rebase. I did not change it.
- **Java tests run on these changes (all green):** ProjectHome*, ApiClubLifecycleTest, GameSessionRuntimeServiceImplTest, ApiCoopCandidatesReceivedTest, StationMerchantContractGateTest, Pricing*, FreeExplorePriceLockAcceptanceTest, the ClubRegistrationRoster* tests, ClubSessionSettlementH2Test, ClubSettlementTopicNameH2Test, ApiClubSettlementControllerTest, OrganiserPersonNameTest. That is 221 + 68 tests, 0 failures.
- `*RealDbIT` / `*MySqlIT` need a database container and were not run locally.
- **JS tests:**
  - Unit tests touching the changed pages pass.
  - Failing before my changes and still failing: `sc-ui-date-count-probe`, `r10-j2-f06-session-entry`, `verification-unknown-adapters`, `mycanyu-refresh-state`.
- **Migrations:** none added.
- **Main package size:** not re-measured (no new files in the main package beyond edits).
- **CI:**
  - Runs on `7d004e02` started (ci.yml run 41981597354, plus the Funds MySQL IT, Mini followup and Redis recovery workflows).
  - Later pushes by other lanes cancel the ci.yml runs on this branch within minutes, so I have no completed ci.yml conclusion for a commit holding all my work.
  - I didn't record whether the other three workflows finished on `7d004e02`.

STATUS: PARTIAL - j2's findings are fixed and shown in the rig, but a club-owned 自由探索 sale cannot settle (blocker in k1's settlement code, reported above), it reached sale only with three labelled DB fixtures, and there is no completed CI run on a commit with all my work.
