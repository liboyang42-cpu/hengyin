# Round 12, lane k6-merchant-screens: result

Branch `integ/launch-test-20261103`, private local branch, `git pull --rebase` before every push, no force-push.
Product commits: `af881f264` (j1 N3), `b8f7d57a0` (j1 N4, p2 N-3, p2 N-1), `667cd3898` (p2 N-2), `8605ffb84`
(p1 N-1, p2 N-4, p2 N-5). k5 owns settlement and ledger writes and the co-op fee rows; this lane wrote no settlement code.

**This is the cloud rig, not a phone.** Rig: `claude/cloud-walk-20261101-rig` with the club lane's `harness-patches.diff`,
real jar, MySQL 8.0.45 and Redis 7.4 in Docker (images pulled through `mirror.gcr.io`), the real mini-program in headless
Chrome at 390×844, shots at 2×. "Before" shots ran on `0d95f3448` (k1's last commit). "After" shots ran on `8605ffb84`
(my last product commit). Same database, so the data is the same in both. I looked at every shot cited here. All shots are
in `integ/fix-shots/k6/`. The walk scripts are in `integ/fix-shots/k6/tools/`.

## Rig data and harness notes

| What | How |
|---|---|
| Base chains | `chains.py finance`: platform → merchant_club → sale_close → player → finance all PASS on topic 990014 (自由探索). |
| **HARNESS: fee rules** | The rig seed has no `settlement_rule` rows. Since k1 item 7, a missing rule refuses instead of silently using 10%. Because of that, the chain stopped at merchant_club (`平台费率规则缺失 … biz_type=topic`). I added the three generic 3% rows that `migration_20261103_settlement_rule_three_percent` asserts, plus `cms_setting` row 1 (`tools/harness_rules.sh`). Production has these rows. **`chain-acceptance.yml` (manual dispatch only, not run by this lane) builds its database from the same `seed_fixtures.py`, so a dispatched run will likely stop at merchant_club the same way until the seed gets these rows. This comes from k1's item 7 change; it is not a product bug.** |
| HARNESS: points rule | The rig seed has no `cms_points_setting`. I inserted rule 1 at production's current value (100, enabled), then ran k1's real `migration_20261105_welcome_points_fifty.sql` twice. The assertion was true both times, and the value became 50. |
| HARNESS: image URLs | The rig app got dummy `CHENGYIN_OSS_*` values so URL signing works offline (p2 had to do the same). Images themselves still don't load. |
| FIXTURE: role | Club lead 9008 has `role = player` in the rig seed, so the publish gate refused 「仅俱乐部主理人或商家可发布活动」. I set it to `club`. |
| Merchant's paid activity | `fx_activity.py`: 9002 publishes activity 14 (¥39 tickets) through real `/api/activity/publish`. Players 9004, 9009, 9001 and 9007 buy through real quote/create and the rig payment seam with the real callback (regs 75–78). |
| Refunds | Real `/api/registration/cancel-refund`. Reg 77 cancelled more than 24 h ahead, refund ¥39.00. Activity 14 was then time-shifted to start in about 4 h, and reg 78 was cancelled, refund ¥19.50 (50%). |
| Scans | Merchant 9002's real scan sheet, with the codes 票夹 issues (`/api/verify/dyncode/issue`), for regs 75 and 76: 「验票成功 · 核销成功」. |
| Deleted activity | `fx_delete.py`: activity 15 taken down and deleted through `/api/activity/update_publish_status` and `/api/activity/delete`, the calls the 我的项目 long-press menu makes. Result `del_flag 2`. |
| Settlement | Time shifts plus the real job 145 `settlementTask` through admin `/monitor/job/run`. |

## Item 1: ROUND12 k1 item 8 and p1 N-1

### j1 N3: a deleted activity left the merchant home and 我的项目 — fixed

- **Cause:** `/api/project/my` read activities without `del_flag = 0`. 营销 「活动」 already filtered them.
- **Fix:** `ApiProjectController.my` sets `delFlag(0)` on the activity query. The merchant home 「全部 N」 uses the same endpoint (`activeTotal`), so it is fixed too.
- **Proof (rig):**
  - Before: 我的项目 → 活动 listed 「已下架 · 周日拉花小课」, and the home showed 「全部 3」 with that card (`n3-myproject-before`, `n3-home-before`).
  - After: only 周六手冲咖啡体验 is listed, and the home shows 「全部 2」 (`n3-myproject-after`, `n3-home-after`).
  - DB: activity 15 has `del_flag 2`.
- **Unverified:** a phone.

### j1 N4 and p2 N-3: 核销与退款 rows add up to the header; refund rows show the merchant's effect; no 「平台已打款」 for in-app balance — fixed

- **Cause:**
  - A refund row drew the player's whole refund as −¥ in the merchant's money column, though that money never left the merchant.
  - The ¥ the merchant keeps from a 50% order had no row.
  - Settled activity tickets read 「平台已打款」 because `destination` was never set.
- **Fix:**
  - Backend: `/api/merchant/aftercare/list` gives each **approved** refund on the merchant's own activity a `merchantNetAmount`. Without finance permission this is null.
    - For an order not scanned, it is what the merchant keeps from that order.
    - For a scanned order, it is how much less the merchant gets.
    - It uses `ICmsActivityService.estimateMerchantShare`. That is the same rate and rounding 「待结算」 and `settleActivity` use.
  - New read-only mapper `selectOwnedActivityRefundShareFacts`, scoped by the existing `exactMerchantRefundScope` and the activity's `member_id`.
  - Activity-ticket rows get `destination = PERSONAL_ACCOUNT` (redacted without finance permission).
  - Ledger page:
    - The money column shows only that merchant amount: +¥ when kept, ¥0.00 when fully refunded, −¥ for a scanned order.
    - The player's refund moves into the existing status chip: 「退款 ¥19.50 · 处理中」.
    - If the server gives no merchant amount (e.g. a refund on a theme), the row has no amount, never the player's refund as −¥.
  - Files: `MerchantAftercareServiceImpl`, `MerchantAftercareMapper.java/.xml`, `MerchantAftercareListItemVO`, `MerchantRefundShareFacts` (new), `MerchantRedemptionView`, `MerchantFinanceQueryService`, `ApiMerchantController.redactRedemptionFinance`, `pages/merchant/ledger/index.js`.
- **Proof (rig, j1's own case: one 50% refund, one full refund, two scans):**

| Step | Header | Rows | Rows sum |
|---|---|---|---|
| Before, pending (`n4-ledger-before`) | 待结算 ¥94.57 | +37.83, +37.83, **−19.50, −39.00** | 17.17 ✗ |
| After, pending (`n4-ledger-after`) | 待结算 ¥94.57 | +37.83, +37.83, **+18.91** (「退款 ¥19.50 · 处理中」), **¥0.00** (「退款 ¥39.00 · 处理中」) | 94.57 ✓ |
| Ended 13 days ago, job run (`d14-ledger-d13`) | 待结算 ¥94.57 | same | 94.57 ✓ (job settled 0 activities) |
| Ended 15 days ago, job run (`n4-ledger-settled`) | 已入账 ¥94.57, 待结算 ¥0.00 | +37.83 「已入个人账户」, +37.83 「已入个人账户」, +18.91, ¥0.00 | 94.57 ✓ |

  - DB after the job: `ums_member_balance_detail` 9002 +94.57 「活动分润 - 周六手冲咖啡体验」 and 9001 +2.93. Their sum, 97.50, equals 39 + 39 + 19.50 retained.
  - 18.91 is the share of 19.50 retained (19.50 − 3% = 18.915 → 18.91, the activity rule's rounding). It matches the 待结算 total exactly.
  - Unit: `merchant-ledger-filter-motion-contract.test.js` (refund row: kept, full refund, scanned, no finance permission).
  - Real MySQL: the 136 targeted backend tests below include the aftercare and finance sets.
- **Known limits (not fixed, reported):**
  1. The list is scans and refunds only. A paid ticket that is neither scanned nor refunded is in 待结算 but has no row. In j1's and this walk every paid ticket was one or the other. Before the event, the rows can be less than the header by those tickets.
  2. Each row is rounded on its own while settlement rounds the activity's total. When several kept shares each round down, the rows can differ from the header by a fen or two. Not seen in this walk.
  3. A platform-ordered refund on an already-scanned ticket, before settlement, voids its `settlement_detail`, so that scan row shows 「待定」. Normal refunds after a scan are refused (「已核销报名不可退款」).
  4. Refund rows stay 「处理中」 in the rig because the WeChat refund seam never confirms the payout (HARNESS).

### p2 N-1: a paid activity scan in 核销与退款 opened a dead end — fixed

- **Cause:** `ledger/order-detail` accepted only `recordType=redemption`. The backend detail already handled `activity_ticket`.
- **Fix:** the page accepts `redemption` and `activity_ticket`. A pending activity ticket reads 「待结算」, not 「随主题结算发放至个人账户」.
- **Proof (rig):**
  - Before: 「缺少核销记录信息 · 请返回重新进入」 (`pn1-ticket-detail-before`).
  - After: 「周六手冲咖啡体验 · 单人体验 · 我的收入 ¥37.83 · 待结算」, store, time (`pn1-ticket-detail-after`).
  - After settlement: 「¥37.83 · 已入个人账户」 (`pn1-ticket-detail-settled`).
  - Unit: `k6-merchant-screens.test.js` case 1. It fails on the old code.

### p2 N-2: a shop that didn't host the ticket showed the buyer's whole ticket as 累计实付 — fixed

- **Fix:** `MerchantCrmAppMapper.xml` has a new fragment `HostPaidRegistration`. 累计实付 (list and detail) counts only orders whose ticket money this merchant received:
  - its own activity, or the 自由探索 shell host;
  - its own theme.
- A 自由探索 participating shop, or a co-op merchant on someone else's theme, no longer adds the buyer's ticket.
- **Proof (rig, shop 8001 / owner 9002, customer 9009):**
  - 9009 paid ¥69 for a 自由探索 ticket hosted by 9008 and ¥39 for 9002's own activity.
  - Before: 「累计实付 ¥108.00」 (`pn2-customer-detail-before`). After: 「¥39.00」 (`pn2-customer-detail-after`).
  - Real MySQL: `MerchantCrmFreeExploreRosterMySqlIT`. The host shows ¥39, the participating shop ¥0 in list and detail, the co-op merchant on a normal theme ¥0, and the host of the normal theme ¥20.
  - That IT was already failing at head with `Unknown column 'r.real_name'`: k3's name change added the column to the SQL but not to the IT's fixture table. I added the column to the fixture.

### p2 N-4: 分润明细 contradicted itself; a player or 店长 saw fake zeros — fixed

- **Fix:** `scene-merchant-profit` shows the stat card, balance stages, withdraw button and caption only when there is at least one co-op settlement. Otherwise it shows only 「还没有合作结算」. The zero-state caption 「还没有已入账分润」 is gone, because the grey button and ¥0.00 already say it.
- The balance stages (待结算, X月X日可提现) are still on 提现 (`subpackageMember/tixian`) and 俱乐部结算.
- **Proof (rig):**
  - Before, merchant: 已入账分润 ¥0.00, 待结算 ¥94.57, 可提现 ¥0.00, grey button, 「还没有已入账分润」 and 「还没有合作结算」 on one screen (`pn4-coop-finance-merchant-before`).
  - Before, player 9004: the same zeros (`pn4-coop-finance-player-before`).
  - After: both show only 「还没有合作结算」 (`pn4-coop-finance-*-after`).
  - Unit: `k6-merchant-screens.test.js` case 3, `ui-token-funds-contract.test.js`.
- **Not changed:** `/api/coop/finance` still answers 200 for a 店长 under the 店长's own id; the empty state is what they see. I did not add a 403: a person who is both a club lead and someone's 店长 would lose their own club finance.

### p2 N-5: co-op texts promised money on a no-cash contract — fixed

- **Fix:** deleted the fallback sentence 「这一屏没有节点可列:你接的是整条路线的合作（到店核销与分成）,不针对某一站。」 (`project-join`). The 「申报本次供给」 sheet no longer says 「勾选本次提供的权益，按核销人数结算」 (`coop/invite-detail`). Both were helper lines under the owner's rule; their facts were wrong for 引流型.
- **Proof:** unit `k6-merchant-screens.test.js` case 4. p2's shots g27 and g29 are the before.
- **Not walked:** I built no accepted 引流型 invite in this rig, so there is no after shot.

### p1 N-1: 结算 offered 积分抵扣 on a 自由探索 session, then refused the purchase — fixed

- **Fix:**
  - `/api/registration/quote` now returns `pointsUsable`. It is false for 自由探索, for a ¥0 base, for no points, or when the rule gives 0.
  - It is computed with the same `computePrice` as the order, and is not part of `quoteSign`.
  - 结算 hides the row when it is false. An old jar sends no field, and the page behaves as before.
  - Files: `CmsRegistrationServiceImpl`, `RegistrationQuoteResult`, `baoming.js`.
- **Proof (rig, new player with 50 welcome points, club session 10 of 990014):**
  - Before: the row 「积分抵扣 -¥0」 was shown. Ticking it gave 「暂不可报名 · 自由探索不支持积分抵扣，请关闭积分后按全价购买」 (`p1n1-checkout-before`, `p1n1-checkout-tick-before`).
  - After: no points row; 订单金额 ¥69 → 总计 ¥69 (`p1n1-checkout-after`).
- **Unverified:** a non-自由探索 ticket where points do apply, in the rig. The row should stay there because `pointsUsable` is true. Covered only by reading the code.

## Item 2: rig walk of what k1 only unit-tested

| k1 item | Status | Proof |
|---|---|---|
| Double order and closing the pay sheet (k1 5) | **PASS** | See the steps under this table. |
| 另存为模板 keeps the theme on sale (k1 6) | **PASS, with a new finding** | See the steps under this table. |
| 39.99 on 发布活动 (k1 9) | **PASS (typing)** | Typed 39.99 key by key into 票面价格 as merchant 9002: field and `formData.tickets[0].price` = "39.99"; a third decimal is dropped (`k1-price-3999-typed`). **Not walked:** submitting the form; the rig can't drive the map and date wheels (j1 B1). k1's unit tests cover the conversion on submit. |
| The limit of 3 not counting club sessions (k1 4) | **PASS** | Club lead 9008 has 5 live club sessions of 990014; the old rule counts 5. `/api/activity/publish_precheck` → 200 「操作成功」, and 发布活动 opens the form, not 「当前身份不能发布活动」 (`k1-quota-club-lead-publish`). **Not walked:** published activities still counting to 3; covered by k1's real-MySQL test. |
| Welcome points 50 in balance and ledger (k1 3) | **PASS** | New player 990005 logs in through the app. 成长中心 shows 50 积分 (`pts-03-growth-balance`). 积分明细 shows 「注册赠送 +50 · 余额 50」 (`pts-02-points-history`). DB: `ums_member.point` 50; `ums_member_points_detail` change 50, after 50, event_type 1. |
| The 14-day wait on the screens (k1 2) | **PASS** | See the steps under this table. |

Double order and closing the pay sheet:
1. New player 990005, session 9 (¥69): 付款, then the rig pay sheet is closed (`cancel`).
   - The page's cancel got 「支付结果确认中」, because the rig's WeChat query seam doesn't confirm.
   - The player lands on 订单详情 「待支付 · 请在 14:14 前完成支付 · 去支付」 (`k1-dbl-02`).
   - Order 79 has `payment_status 1`.
2. 结算 again, then 付款: the backend answers 409 `PENDING_ORDER_EXISTS` with `registrationId 79`. The page opens order 79 (`k1-dbl-03`, `k1-dbl-04`). No second row was created.
3. 去支付 on that order: paid, `payment_status 2`, `registration_status 2`. Still one order (`k1-dbl-05`).
4. Note: the redirect has no toast; the order page itself says 待支付.

另存为模板, as owner 9003 on live theme 990014 (5 sessions, paid tickets) from 我的项目:
1. Confirm 「另存为模板」 (`k1-tpl-02`).
2. DB: new row 990015 has `is_template 1`. 990014 keeps `status 1, user_status 1, is_template 0`, and its 5 sessions.
3. The player's theme page still sells it: 「立即支付 · 自玩通行证」 (`k1-tpl-05`).
4. **New finding (LOW, not fixed, owner):** right after saving, 我的项目 → 主题 shows the copy as a second card, 「E2E 探店日一期 · 未开始 · 0 报名」 (`k1-tpl-03`).
   - Public lists filter `is_template = 0`, so players don't see it.
   - The owner sees two identical themes, and the 模板 tab doesn't list theme templates.
   - I did not hide `is_template = 1` rows from 主题: themes taken off sale by the old behaviour still have `is_template = 1` (k1's owner note), and their owners would lose them along with their rosters.
   - Recommended default: show theme templates in 模板 and leave 主题 to non-template rows once those old rows are cleaned up.
   - Also, the code comment above `saveAsTemplate` in `myproject/index.js` still says the backend 「不复制」.

The 14-day wait on the screens:
1. Activity 14 time-shifted to end 13 days ago, then the real job ran: 「共结算 0 个活动」.
2. 提现 shows 「客诉期中 · 11月6日可提现 ¥94.57」 (`d14-tixian-d13`). 11-06 is the end date 10-23 + 14 days.
3. `/api/wallet/stages`: `complaintPeriod[0].availableDate = 2026-11-06`.
4. Shifted to end 15 days ago, the job ran: 「共结算 1 个活动」. 提现 then shows 可提现余额 ¥94.57 (`d14-tixian-settled`), and 账户收益 ¥94.57 (`d14-earnings-settled`).
5. No screen in the app writes a fixed 7 or 14. The date comes from the server.

## Verification

- **Java (real MySQL 8.0.45, rig container on 3316):**
  - Targeted run of 136 tests: `MerchantCrmFreeExploreRosterMySqlIT`, `MerchantCrmAppMapperContractTest`, `MerchantCrmAppServiceImplTest`, `PublicVisibilityMyBatisTest`, `ProjectPlayerContactVisibilityTest`, `*Aftercare*Test`, `MerchantFinance*Test`, `*Quote*Test`. Green after the fixture fix.
  - No `@Test` added or removed, so `ci/funds_it_gate.py` is unchanged.
- **Mini-program:**
  - `ui-integrity.js` → `UI-GATE-0:PASS`.
  - `build-action-ledger.js --write`: line shifts in `baoming.js` only, then no diff after rebasing onto k3.
  - New `k6-merchant-screens.test.js` (4). `merchant-ledger-filter-motion-contract`, `ui-token-funds-contract`, `merchant-ledger-detail-state-contract`, `cu-m-copy-merchant-empty-states-contract`, `scene-merchant-profit-truth-contract`, `funds-stages-owner-only-contract` and every unit file that names `baoming`, `project-join` or `coop/invite-detail` pass.
- **Rig:** all of the above, at 390×844 2×, every shot under 200 KB.
- **CI:** see the CI section below.

## CI

Other lanes kept pushing, so the main `ci.yml` runs on my own commits `a284764b0` and `d36638a3c` were cancelled by newer pushes.

**On `3ee591fc6` (contains all my product commits), all green:**
- Funds MySQL IT, run 41977440026
- Mini followup focused regression, run 41977440061
- Isolated authenticated Redis recovery, run 41977440029

**On `2f9daae1e` (contains all my work), `ci.yml` run 41979418953 is red. 7 jobs failed; none is in a file k6 touched:**
- Backend:
  - `P0DeregistrationMySqlIT:199` and `WechatMemberRegistrationMySqlIT:116`: no welcome-points ledger line in their fixtures. This follows from k1 items 3 and 11, where a disabled or missing rule 1 grants nothing.
  - `ClubAttendanceTimeMappingMyBatisIT:98`: `Unknown column 'create_time'` in `ClubEventOpsMapper.xml` (club lanes).
- Mini program:
  - danger-confirm selftest: `club.joinRequest.reject` and `club.enroll.refund` have no consequences written (club pages).
- Unit tests failing locally at `2f9daae1e`:
  - `action-ledger` #37 (ledger drift from later lanes; the ledger was clean after my rebase)
  - `mycanyu-refresh-state`
  - `play-chapter-story-blocks-contract`
  - `r10-j2-f06-session-entry`
  - `verification-unknown-adapters` (club detail)
- Every unit file I touched or added passes at that commit.

I did not fix other lanes' failures.

## Not done / not verified

- Real phone: nothing.
- p2 N-5 has no after shot (no accepted 引流型 invite in this rig).
- Submitting 发布活动 with 39.99, and the published-activity side of the quota.
- The four ledger limits listed under j1 N4.
- The 另存为模板 duplicate card in 主题 (finding above, for the owner).

STATUS: COMPLETE
