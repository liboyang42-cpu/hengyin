# Round 13, lane m6-free-explore-money: result

Branch `integ/launch-test-20261103`, private local branch `m6-free-explore-money`, `pull --rebase` before every push, no force-push, `[skip ci]` on every commit. Nothing touches master or production.

## Step 1: what the code means to do with paid 自由探索 money today (read at `ce5812e13`)

This is what the code says, not what I think it should say. Line numbers are at `ce5812e13`. Owner decisions in force: a scan moves no money; platform 3%; withdrawable 14 days after the end; merchant fee at settlement from the organiser's share; platform pays back its share on a refund after settlement; approved shops see all buyers.

There are not one but three money designs in the code for a 自由探索 (`product_type = 2`) sale. Which one applies depends on two things frozen when the theme goes on sale (`topic_sale_snapshot`): whether the completion rule is `FOUR_STORE_REDEEM` (the old 探店日 four-shop product), and whether the club that opens the session is the club that owns the theme.

### Case A. Club-owned 自由探索 (theme club = session club, completion rule is not `FOUR_STORE_REDEEM`)

- **Path:** the normal session settlement, job 145 (`CmsActivityServiceImpl.executeActivitySettlement` → `settleActivity`). Opened by the lead in `528c130c2` (2026-11-04, 「打通俱乐部自有自由探索场次结算」): the selecting SQL (`CmsActivityMapper.xml` `selectActivitiesForSettlement`, ~483-519) and the pending-money SQL (`ActivitySettlementHoldMapper.xml` ~90-106) now let this case through; `requireSessionSettlementContract` (`CmsActivityServiceImpl.java` ~448) checks a frozen sale contract exists and is not four-store.
- **Who gets what on ¥69:** platform 3% = ¥2.07 (event 7 to `cms_setting.platform_member_id`); the club's current 主理人 ¥66.93 (`activityPayee` → `ClubPrincipalPayee`). Shops on PERK (权益) or TRAFFIC (引流) terms get no cash. On ¥29: platform ¥0.87, 主理人 ¥28.13.
- **When:** the job pays once the session ended 14 days ago (`FundsHoldPolicy.WITHDRAWABLE_AFTER_END_DAYS`) and no paid ticket is still inside its refund window. Money is withdrawable as soon as it is paid in. Before that, 俱乐部结算 and the wallet show it as 待结算 (the hold SQL above).
- **Refund before settlement:** the ticket leaves the sale; nobody is paid for it. **Refund after settlement:** `RefundServiceImpl.allocateClawback` takes it back in the same proportion from the 主理人 and (since k11, `66256f2d`) from the platform member that got event 7; anything a balance can't cover becomes a debt (`ums_member_settlement_debt`) with an alarm, offset from the next settlement. A re-run settles nothing twice (`claimActivityForSettlement`).
- **Edition-final:** not involved.
- **Matches the owner's rules?** Yes. Proven by the lead on Funds MySQL at an earlier candidate (`76d1a3b5c`, 2 × ¥29 → 1.74 / 56.26) and by k4's counterfactual run. Not yet walked on the rig at head.

### Case B. Four-store 探店日 (completion rule `FOUR_STORE_REDEEM`), e.g. m4's M1: ¥69, theme club 7001, session club 7002

- **Path the code intends:** **not** the settlement job. It is a separate, platform-run product line built in August (`migration_20260815_tandianri_batch3_funds_core.sql`, `migration_20260816_tandianri_batch3_funds_club_compensation.sql`, `…club_payee.sql`, `migration_20260817_tandianri_r6_funds_final_closure.sql`; merged in PR #541, `f099c5be9`):
  - At sale (`TopicSaleFinalizationServiceImpl.finalizeForSale` ~177-199) the code forces every shop onto PERK terms (`assertShopDayTermsArePerk`), resolves one *executing club* (the club holding the ORGANIZER co-op order, here 7002), freezes that club's company payee (`clubPayeeService.snapshotForSale`) and freezes **labour compensation terms** (`compensationService.freezeTermsForSale`).
  - The compensation (`ClubEditionCompensationServiceImpl` ~44-200, 515-560): a fixed fee F of at least ¥2,000 per edition read from `topic_edition_cost`, quality Q of ¥200 for each of three dimensions the platform confirms, extra hours at ¥40/h, and an optional head-count fee (frozen rate × earned tickets, capped).
  - After the edition ends, an admin with `business:editionfinal:execute` (granted to no non-admin role today, says the controller) calls `POST /business/edition-final/{topicId}` (`TopicEditionFinalizationController`, `TopicEditionFinalizationServiceImpl.finalizeEdition`). It refuses while the edition is running, while any refund is unsettled, or while a complaint is open (`EditionFinalFactGate`). It writes a FINAL funds snapshot (`topic_edition_funds`: cash in, refunds, earned) and four `club_payable` rows (F, Q, EXTRA, HEADCOUNT).
  - Payment is **off-app**: finance creates a batch, one person confirms, a different person records the bank transfer, and invoices are tracked (`club_payable_batch`). The CI chain `scripts/e2e/chain_finance.py` runs exactly this.
- **Who gets what on ¥69:** the code never splits the ticket. The whole ¥69 stays with the platform as its own revenue. The executing club is paid its compensation (≥ ¥2,000 + up to ¥600 + hours + head count) by bank transfer, whatever the ticket sales were. The theme's owning club (7001) gets nothing from this path. Shops get their perks' customers, no cash. No one's in-app balance moves.
- **When:** whenever an admin runs FINAL after the edition's frozen end. No job runs it, no alarm fires if nobody does, and the club's 俱乐部结算 and wallet never show it (the session SQL excludes it on purpose; only the admin 期级财务 page reads `topic_edition_funds`).
- **Refund before FINAL:** the player is refunded from the platform's money; FINAL waits until no refund is unsettled; the head-count part counts only earned tickets. **Refund after FINAL:** nothing is blocked and nothing is clawed back automatically; the platform refunds out of its revenue. Correcting the club's payable needs an admin 红冲 + 重计提, and is refused once the payable is in a batch (`CLUB_PAYABLE_ALREADY_BATCHED`).
- **Is edition-final meant to do it?** Yes: the session path refuses it on purpose (`requireSessionSettlementContract`: 「四店专用合同仍走原 FINAL」), and `settleTopic` refuses every 自由探索 (`CmsTopicServiceImpl.java` ~690).
- **Matches the owner's rules?** **No.** The code's rule is "platform keeps 100% of tickets and pays the club a fixed labour fee off-app"; the owner's rules are "platform 3%, the organiser gets the rest, merchant fee from the organiser's share, withdrawable in-app 14 days after the end, platform pays back its 3% on a later refund". This is a money-rule conflict, so I stop on it (Step 2 lists the options and my recommendation) and do not change who gets the ¥69.
- **Today, in practice:** the ¥69 sits in the platform's WeChat account, no job picks it up, no alarm fires, no organiser or merchant screen shows it, and nothing forces anyone to run FINAL. That part I can fix without choosing a money rule: see Step 2.

### Case C. 自由探索 with shop cash terms ("merchant terms")

- `TermsMode` (`chengyinhub-common/src/main/java/com/chengyinhub/common/constant/TermsMode.java`) has three shop terms: PERK (perk, no cash), TRAFFIC (引流, no cash) and REVSHARE (计酬, a fixed fee per scanned customer). 招商 V1 allows only PERK and TRAFFIC (`ChapterRecruitmentRules.assertV1RecruitmentTermsMode`: 「平台计酬待P2开放」), except REVSHARE when the theme has a club as its content owner (`ClubDirectMerchantPolicy`).
- **Club-direct REVSHARE, what the code means:** at each scan, `ClubDirectMerchantReceivableService.accrue` records that the **club owes the shop** `per_head_fee` (`club_direct_merchant_receivable`). The app never pays it: the club pays the shop directly, outside the app, and the row is marked settled (the class says 「没有钱包、提现或转账依赖」). The ticket money itself goes the Case A way: 3% platform, 97% 主理人, so the 主理人 is expected to pay the shop out of that.
- **Platform-owned REVSHARE (③-自营):** `MerchantPayableServiceImpl` records a platform payable at each scan, paid off-app by bank transfer.
- **Club session co-op orders on 按核销付费 (k5's ¥8/人 rule)** are paid in-app at settlement only for heads in a session the shop **group-scanned** (`ClubSessionMerchantFee.plan`). A 自由探索 ticket is scanned per player at each shop (`redemption`), never group-scanned, so in Case A a shop on an 按核销付费 co-op order gets ¥0 in-app and the 主理人 keeps the fee.
- **Matches the owner's rules?** Partly. The owner said the merchant fee is paid **at settlement, from the organiser's share**; the code records a club-to-shop debt at scan time and leaves the payment off-app. Who owes whom and how much are the same; where and when the money moves are not. This is a money-rule question too, so I do not change it; options below.

### Case D. 自由探索 sessions run by a club that does not own the theme, not four-store

- Allowed: a club holding a co-op order on the theme can open sessions (`ClubSessionServiceImpl.requireTopicOpenForSessions`).
- The settlement SQL excludes it (theme club ≠ session club), `requireSessionSettlementContract` would refuse it, and `settleTopic` refuses every 自由探索. **No path pays it, no alarm.** The code has no stated rule for who gets the money (the session's 主理人 or the theme's), so I stop on the rule here too.

### Platform-owned 自由探索 (no club), not four-store

- The ticket is the platform's own revenue (③-自营). There is no one else to pay except REVSHARE shops (off-app payable above). Excluded from the job; nothing is owed in-app. Not a launch issue under the owner's rules; noted only.

## Step 2: what I changed, what the rig showed, what is left for the owner

Commits (all `[skip ci]`, pushed to `integ/launch-test-20261103` after `pull --rebase`):

| Commit | What |
|---|---|
| `c66737ee1` | Step 1 of this file. |
| `e75d76292` | **M2.** A 自由探索 theme with no nodes publishes into 招商. `TopicRoutePublicationCompiler.freezeCurrentPublication` now uses the same rule as the review step (`TopicBundleReviewService.awaitsMerchantGameplay`: 自由探索 with at least one chapter open to merchants). Other themes, themes with no open chapter and themes with no chapters are still refused with 「主题没有可运行节点」. When a merchant's node is approved, `ChapterMerchantNodeServiceImpl` refreshes the published snapshot as before. Test: `TopicPublicationContentSnapshotTest` +1. |
| `6c369f627` | **M1, no more silent money.** When the settlement job runs, it now also finds 自由探索 sessions that have reached the payout date (ended 14 days ago, no ticket still refundable) but have no settlement path: four-store with no FINAL yet, a session run by a club that is not the theme's club, or a missing sale contract. For each one with money still held (paid and not refunded, plus what a 50% refund left behind) it names the session, the amount and the reason, and the job ends as FAIL. That fires the existing 「活动结算失败」 and 「定时任务失败」 alarms, and the job log screen shows the failure. No money moves and nothing is marked settled, so the alarm repeats on every run until someone acts. A four-store edition stops alarming once its edition-final has run; a fully refunded session never alarms. Other sessions in the same run still settle. Test: `ClubSessionMerchantFeeMySqlIT` +1 (`EXPECTED` 7 → 8 in `ci/funds_it_gate.py` and its fixture). |
| `2d4541cab` | **Funds gate fixture.** `ExploreItDatabase` now also applies `migration_20261105_chapter_video.sql`. Without it, three gated suites failed on real MySQL with `Unknown column 'video_url'` (2 failures, 11 errors) since the video lane's `9307c39df`. Assertions unchanged. |
| `80c7a6810` | **Refund after settlement now gives back both shares (found on the rig).** A 自由探索 ticket gets no ledger lines at payment (by design: `SettlementEntryServiceImpl` skips 探索票). Since `528c130c2` the session job pays the whole sale to the 主理人 and the platform, but a later refund found no lines to claw back. On the rig the player got ¥69 back while the 主理人 kept ¥167.32 and the platform ¥5.18 (`proof/m6-walk/m6-walk-readbacks-before-fixes-97ca46f8.txt`, run `fe69b`). Now, inside the session's settlement and only after `requireSessionSettlementContract` passes, each paid and unrefunded ticket gets the same two lines a club session ticket gets at payment: 主理人 and platform (3% of the frozen rate). They are marked settled with the session, and the existing clawback (`clawBackSettledShares`, k11) then takes both shares back in proportion, with a debt if a balance is short. The payment-time refusal for 探索票 is unchanged (`ExploreNeverSettledWholeOrderTest`). Test: the club-owned 自由探索 case in `ClubSessionMerchantFeeMySqlIT` used to seed lines that production never has; it now matches production and checks that the job writes four lines, a re-run writes none, and the refund takes back ¥0.87 and ¥28.13. Same number of tests. |
| `1972e6130` | **自由探索 could not go on sale (found on the rig).** The 「门票」 that 确认终价 creates had no refund rule. The platform's sale contract needs one for every ticket, so saving the sale draft always failed with `SALE_SNAPSHOT_MISSING_REFUND_RESCHEDULE_POLICY`. After pricing the ticket is frozen, and the back-office ticket edit answers 409, so no screen could fix it (k4 patched it in the DB). On 2026-11-03 the owner gave 探索票 the same three tiers as session tickets, and the code already refunds them that way (`START_24H_100_50_0`). The ticket now carries the existing rule sentence `RefundPolicy.SESSION_REFUND_RULE_TEXT`, only when the ticket has none. No new wording. Test: `FreeExplorePriceLockAcceptanceTest` +1 assertion. |

### Rig rehearsal (real jar `1972e613`, real MySQL 8.0.45 and Redis 7.4, real jobs; not a phone)

Everything was done through real HTTP and the real jobs 145 (分润结算), 149 (招募到期) and 143 (退款对账). The only DB writes were time shifts on my own rows: each theme's recruit deadline, and each session's start/end together with its tickets' frozen refund deadline. After the last two fixes, no FIX was needed: the lead created a zero-node theme, merchants 9002 and 9005 applied, enrolled and built nodes, the lead approved them, the deadline job moved the theme to pricing, the lead confirmed the price, the lead opened a session, the platform saved the sale draft and approved the sale, players joined, paid (rig pay seam into the real callback) and were scanned at the shops. Log: `proof/m6-walk/m6-walk-readbacks.txt`. Scripts: `proof/m6-walk/fe_walk.py`, `m1_four_store.py`, `shots.sh`.

| Case | Inputs | DB read-back | Screens (`fix-shots/m6-*`, all viewed) |
|---|---|---|---|
| M2 | Zero-node 自由探索 `v2/create` | `主题发布成功`, lifecycle 1 (招商). After two merchant nodes are approved, the published snapshot has 2 stations | n/a |
| ¥29, club-owned (`fe29`, session 9) | 3 × ¥29. One player cancels inside 24 h before the start: ¥14.50 back, ¥14.50 kept. Two players scanned | 13 days after the end: job runs, nothing paid, wallet shows 「¥70.32 · 11-06 可提现」. 15 days: platform **2.18** + 主理人 **70.32** = **72.50** (29 + 29 + 14.50). Re-run: unchanged. Lines 28.13 / 0.87 × 2, status 1 | 俱乐部结算: 销售额 ¥72.50 · 平台服务费 3% ¥2.18 · ¥70.32 · 可提现 ¥70.32. 账户收益: ¥70.32. Merchants: 权益已履约 · 无现金结算 (PERK, no cash) |
| ¥69, club-owned (`fe69`, session 10) | 3 × ¥69. One cancels inside 24 h (¥34.50 back). One scanned, one never went to a shop | 15 days: platform **5.18** + 主理人 **167.32** = **172.50**. **Refund after settlement** (organiser cancels the unscanned ticket, ¥69 back to the player): platform **−2.07**, 主理人 **−66.93**, now 3.11 + 100.39 = **103.50** = what is kept. No debt (balances covered it). Re-run: unchanged | 俱乐部结算 ¥172.50 / ¥5.18 / ¥167.32, then ¥103.50 / ¥3.11 / ¥100.39 and 可提现 ¥100.39. 收益明细 (主理人): +167.32, 「退款冲销 −¥66.93」. 收益明细 (platform 9001): +5.18, +2.18, 「退款冲销 −¥2.07」 |
| M1, four-store ¥69 (CI chain, topic 990016, session 15, club 7002) | Player paid ¥69 and was scanned at four shops. Session moved to 15 days ago | Job 145 **FAIL**, alarm: 「【结算告警】自由探索实收没有结算路径,未入账 activityId=15, topicId=990016, 实收=69.00, 原因=四店探店日(FOUR_STORE_REDEEM)待期级 FINAL,分钱规则待业主拍板」, then `[ALARM] 活动结算失败` and `[ALARM] 定时任务失败` (`proof/m6-walk/m1-alarm-application-log.txt`). No balance moved. After the CI finance chain ran edition-final on the same rig: club 7002 payable **¥2,600** (F 2,000 + Q 600) batched and marked PAID off-app; FINAL funds ¥69 earned by the platform; the next job run is SUCCESS, with no alarm | none: under the code's model this money is the platform's |
| Before the refund fix (jar `97ca46f8`, run `fe69b`) | Same as ¥69 | Refund after settlement: player got ¥69 back, 主理人 kept 167.32, platform kept 5.18, no lines, no debt | 俱乐部结算 still showed ¥172.50 |

### Stopped: money rules the code does not settle under the owner's rules

**S1. Four-store 探店日 (M1).** The code's rule is: platform keeps 100% of the ticket and pays the executing club frozen labour terms (at least ¥2,000 + up to ¥600 + hours + head count) off-app through edition-final. The owner's rules are: 3% to the platform and the rest to the organiser, in-app, withdrawable 14 days after the end. These conflict. On the rig, one ¥69 ticket led to a ¥2,600 payable.
- Option A: keep the code's contract. The chain's theme is platform content (`CONTENT:PLATFORM`, `ORGANIZER:PLATFORM`), so the platform *is* the organiser and the owner's split gives it 100% anyway; the club is a paid contractor. Someone must hold `business:editionfinal:execute` (today only the super admin does) and run FINAL after each edition; the new alarm repeats until they do.
- Option B: settle four-store sessions like club-owned ones (3% platform, rest to the session club's 主理人, in-app, end + 14), and stop freezing compensation terms for new editions.
- Option C: stop selling new four-store editions until decided; paid ones keep alarming.
- **Recommendation: A** for editions already on sale (the frozen terms are the club's contract), with FINAL given to a finance role. Ask the owner whether to keep selling it at ¥69 with a ¥2,000 floor.

**S2. A club running sessions of another club's ordinary 自由探索.** No rule anywhere in the code says who gets the money. It now alarms instead of sitting silent.
- Options: (a) the session club's 主理人 gets 97%; (b) split with the theme's club, which needs a rate; (c) refuse such sessions on ordinary 自由探索.
- **Recommendation: (c)** for launch.

**S3. Shops on cash terms in 自由探索.** Club-direct REVSHARE is recorded at each scan as the club owing the shop, and paid off-app (`ClubDirectMerchantReceivableService`). A 按核销付费 co-op order on 自由探索 pays nothing in-app, because only group scans count (`ClubSessionMerchantFee`). The owner's rule is: merchant fee at settlement from the organiser's share.
- Options: (a) keep off-app; (b) pay per player scanned at that shop, at session settlement, from the 主理人's share, refusing settlement if the fee is larger than that share (k5's rule), by giving `ClubSessionMerchantFee` redemption heads; (c) allow only PERK/TRAFFIC on 自由探索 at launch.
- **Recommendation: (b)**, matching the owner's rule. Not built or walked: no cash-terms 自由探索 was sold in the rig.

**S4. Refunding a scanned 探索票 after settlement.** Both the player's and the organiser's request go to the platform's manual case (`MANUAL_REVIEW`; rig `fe29`, case 1, PENDING_ASSESSMENT 2900 fen). The manual case records an off-app refund with evidence and **never claws back** the 主理人's or the platform's share (`ActivityCancelManualRefundCaseServiceImpl`). So after such a refund the platform carries the 主理人's 97%.
- Options: (a) when the case is verified, claw back the assessed amount in proportion through the same ledger lines (now present) and `clawBackSettledShares`, recording a debt if a balance is short; (b) the platform absorbs it.
- **Recommendation: (a).** Not built: it changes the manual refund flow, which I did not want to do without a word.

### Tests

- Funds MySQL gate (`ci/funds-mysql-it-gate.sh`, scratch MySQL 8.0.45 in this container):
  - First run on `6c369f627`: 740 tests, 13 red, all `video_url` (fixed in `2d4541cab`, then those three suites 13/13 green).
  - Final run, started on `1972e6130` (all my code): **740 tests, 0 failures, 0 errors, 0 skipped**. The gate's own read-back passed: 「资金 MySQL IT 门禁通过」.
  - This was run in this container, not in GitHub CI.
- Targeted unit and real-MySQL runs after each change, all zero failures, errors and skips: `ClubSessionMerchantFeeMySqlIT` 8, `ComplaintPeriodWithdrawalGateMybatisIntegrationTest` 7, `TandianriBatch0` 17, `RegistrationFunds` 114, `RefundRetryAndRemainder` 10, `ActivitySettlementIdempotencyTest` 8, `ExploreNeverSettledWholeOrderTest` 5, `SettlementEntryServiceImplTest` 16, `CriticalPathContractTest` 3, `TopicPublicationContentSnapshotTest` 7, `TopicBundleReviewServiceTest` 25, `FreeExplorePriceLockAcceptanceTest` 19, and the pricing suites.
- `ci/test_funds_it_gate.py`: 45 OK.

### Not done or not proven

- S1 to S4 above (owner decisions; S4 also needs code).
- No phone and no CI run; the rig browser is not a phone. Withdrawal stays 联系客服 by owner decision.
- The debt branch of the clawback (balance too small) was not hit on the rig; k11's real-MySQL tests cover it.
- 俱乐部结算 lists a session only after settlement; before that, the pending amount shows in 账户收益 and 提现 (same as m4 noted).

STATUS: PARTIAL - M2 is fixed, and club-owned 自由探索 sales now go on sale, settle and refund to the fen (rig: ¥29 and ¥69; Funds gate 740/740). Four-store and cross-club sales no longer sit silent: the job alarms. Four things wait on the owner: S1 four-store money rule (recommend A), S2 cross-club sessions (recommend blocking them), S3 shop cash terms in 自由探索 (recommend paying at settlement), S4 claw-back on manual refunds of scanned tickets (recommend clawing back; needs code).
