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

## Step 2

(Filled in as the work lands.)
