# Round 13, lane m4-merchant-club-walk: result

## Money findings for the lead (second job, 2026-11-05, head `1a9b55ab5`)

I changed no money rule and no settlement code. Two things are worth your attention; both are on or next to your open list.

**M1. A 自由探索 sale of four approved shops is never settled by any job.**
- Inputs: the rig chain's theme 990014 (自由探索, `product_type = 2`, theme owner club 7001, session 13 run by club 7002, one player paid ¥69, scanned at the shops, shops on 引流/PERK terms). I moved the session and the theme to end 15 days ago on my own rig rows and ran the real 分润结算 job (job 145).
- Expected: ¥69 paid out as 3% platform and the rest to the organiser at end + 14 days, or an alarm saying why not.
- Actual: the job logs 「没有需要结算的活动 / 没有需要结算的主题」 and ends with status 0. `cms_activity.is_settlement` stays 0, no `settlement_detail` line exists for the registration, no balance moved, no alarm. SQL reason: `selectActivitiesForSettlement` leaves out `product_type = 2` unless the theme's club equals the session's club and the evidence rule is not `FOUR_STORE_REDEEM`; `topicSettlementEligibility` leaves out `product_type = 2` entirely. This chain's four-store sale fails both, so nothing ever picks it up.
- In your open list? Partly: the 「俱乐部自有自由探索结算」 row and the R2 「开售到结算」 row. The four-store case (`FOUR_STORE_REDEEM`) is not named there. If it is meant to settle by another path, that path did not run in the rig. If it is meant to be held, the player's ¥69 sits with no screen showing it and no alarm.

**M2. A 自由探索 theme with no nodes still cannot be published at head, so 480c9a0ab does not unblock R2.**
- Inputs: real `POST /api/topic/v2/create` as a club lead (club 990004), `productType 2`, two chapters with `recruitEnabled 1` and `termsMode PERK`, `nodes: []`, one ¥29 ticket (script `proof/k5-walk/t1_fe_create.py`). The rig jar is built from `1a9b55ab5`, which contains 480c9a0ab.
- Expected: the theme publishes into 招商 and merchants then build the nodes.
- Actual: `code 500`, 「主题没有可运行节点」, no theme row created. The review step now accepts zero nodes (`TopicBundleReviewService.awaitsMerchantGameplay`), but `TopicRoutePublicationCompiler.freezeCurrentPublication` (line 570) throws on an empty node list. That is a different check from the one 480c9a0ab touched.
- In your open list? Yes, the R2 row 「新 JAR／运行态的针对性复验尚未完成」. This is that re-check, and it fails.

**Everything else matched to the fen** (club session with a ¥8/人 merchant, k5's scenario S1/S2 re-run on this head; DB read-backs and screens agree with ROUND12-K5 and K11):

| Step | DB | On screen |
|---|---|---|
| Before settlement (5 heads scanned, one 50% refund) | balances all 0.00 | 俱乐部结算 lead 168.06 「待结算」; merchant 9002 核销与退款 待结算 ¥40.00; 9002 提现 shows 「客诉期中 · 11月6日可提现 ¥40.00」, lead 「¥168.06」 |
| Ended 13 days ago, real job | nothing paid | same |
| Ended 15 days ago, real job | platform 6.44, merchant 9002 40.00, lead 168.06 (sum 214.50 = 234.00 − 19.50) | 俱乐部结算 rows 59.66 and 108.40 with 销售额 78.00 / 136.50, 平台服务费 3% 2.34 / 4.10, 商家核销费 16.00 / 24.00; 9002 核销与退款 已入账 ¥40.00, rows +¥16.00 +¥24.00; 收益明细 and 账户收益 +16.00 +24.00; 提现 可提现 ¥40.00 / ¥168.06 |
| Job re-run | unchanged | |
| Refund of one ¥39 ticket after settlement (organiser path) | platform 5.27 (−1.17), lead 138.23 (−29.83), 9002 32.00 (−8.00); no debt row | 俱乐部结算 S1 sales 97.50, fee 2.93, net 78.57, 商家核销费 16.00; lead 可提现 ¥138.23; 9002 已入账 ¥32.00, rows +16.00 +16.00; 收益明细 「退款冲销 −¥8.00」 (9002), 「−¥29.83」 (lead), 「−¥1.17」 (platform 9001) |

A player's own refund after the start is refused with 「场次已开始，不可退款」, as before. Screens: `fix-shots/m4-money-*.jpg`, all viewed. Not money, not changed: the merchant 核销与退款 header shows 待结算 ¥40.00 before settlement but lists no row for it (it is the estimate, rows appear at settlement); a player's 我的参与 hides sessions that ended more than 7 days ago while 我的订单 still lists them (by design, code comment in `RegistrationQueryApplication`).

Rig notes for this job: the container had restarted, so I rebuilt the rig from `1a9b55ab5`. The empty rig DB has neither the activity 3% rule nor `cms_setting`, which production has; the k5 scripts create both, so I ran them (copies in `proof/k5-walk/`, the new `t1`..`t3` scripts are mine). Time shifts are on my own rig rows only (sessions 14, 15, 13, theme 990014); the job did all settling.

## Not walked in the second job
- Withdrawal beyond the screens: the in-app withdraw is the 联系客服提现 page by owner decision, so there was nothing to submit.
- 自由探索 sale paid by screen: bought through the chain's real HTTP, not by tapping through the checkout.
- Club session invite → scan by tapping the merchant's camera: invite, accept and group scan were done through the real endpoints (k5 scripts), the screens read afterwards.
- Phone, CI.


Branch `integ/launch-test-20261103`, private local branch, `pull --rebase` before every push, no force-push.
Rig: the real jar built from `35b3dc6df` (the branch head when I started), real MySQL 8.0.45 and Redis 7.4 in Docker,
the real mini-program in the rig's headless browser at 390×844. **This is not a phone.** Screenshots I looked at are
under `integ/fix-shots/m4-*`.

Rig fixes that were mine (rig worktree only, not product code): the rig's dummy OSS settings
(`CHENGYIN_OSS_BUCKET_NAME` and three more) so topic pages can render at all. Without them a topic read answers
「Could not write JSON: 对象存储配置缺失…」 and that text shows on screen as a toast. That is **HARNESS**: production has the
settings. See "For the lead" for the one thing about it that is not HARNESS.

## Journeys

| Journey | Status | What I did and saw |
|---|---|---|
| Merchant: apply → approval → store | **walked** | New player 9009 → 成为商家 → 4-step form → submit → 审核中 page → platform approves (admin API) → merchant home 墨香书屋 营业中. Found and fixed 2 bugs (below). **Not walked:** licence photo upload (HARNESS: image safety check calls WeChat, unreachable, answers 「安全检查服务异常」; I set the licence by script for that one step). Map pick and hours picker worked. |
| Merchant: stations, invitations, accepting | **partly walked** | Merchant 9002 as a chapter supplier (offers enrolled by the real chain), 合作中心, 协作邀请 (empty), game-node (blocked by the OSS HARNESS gap before my fix, not re-walked). Invitation resend and 「待接受邀请」 were fixed and walked by k4; I did not redo them. |
| Merchant: verification scan | **walked through the chain** | The player chain (real HTTP) paid a ticket and scanned at all four shops: all four chains PASS on the rig. 核销与退款 shows 「权益已履约 · 无现金结算」, 核销记录 shows the scan, 客户 shows the player. I did not tap the camera (rig has no camera). |
| Merchant: earnings, withdrawal screens | **walked, empty states only** | 账户收益, 明细, 分析, 提现, 提现记录, 协作提现 and 分润明细 as merchant and as club owner. They open, show plain Chinese and correct zeros. No money had settled in this rig run, so no number could be wrong yet. k5 and k6 own the money walk (to the fen). |
| Club owner: apply, create, manage | **walked** | New player 9004 → 成为主理人 (4 steps) → 创建我的俱乐部 (4 steps) → club page → 管理. Found and fixed 3 bugs (below). |
| Club members, roles, posts | **walked, thin** | A second player joined (成员 2), role page lists them in the picker, 群发通知 (audience, send), post composer opens. I did not post or assign a role end to end. |
| Club session with merchant station, enrolment, refunds, settlement report | **not walked by me** | Covered by k4 and k5. In this run the chain opened sessions and sold a ticket (all PASS). 俱乐部结算 for my new club shows 0, correct. |
| 自由探索 sale with approved shops | **partly walked** | Chain sold one ticket across four approved shops (PASS, real HTTP). Topic page opens on the cover; the checkout shows 自玩通行证 and 合计. I did not re-pay it by screen. |

## Bugs fixed (all pushed, every commit has `[skip ci]`)

1. **Pink error blocks on field errors** (commit `f72f8754f`). The owner's rule is red text under the field. These
   pages used the pink block with an icon (`kind="error"`): 商家入驻 (name, industry, phone, address, hours, licence, real
   name, ID, 营业日, 营业时段), 常备权益, 群发通知, 创建俱乐部 name, 收货地址, 报名参与人信息. Now `kind="field"`.
   Files: the six `.wxml` pages plus tests that pinned the old kind (`r5d-first-pages-input-errors`,
   `c14-club-name-check-on-blur`, `baoming-submit-inline-error`, and later `r4-input-error-under-field-sweep`).
   Proof: `m4-apply-field-error-before.jpg` / `-after.jpg`.
2. **商家入驻 accepted a 7-digit phone** (`m4-apply-phone-error.jpg`). Step 1 only checked that something was typed;
   the server does not check either. Now it needs an 11-digit mobile number (existing `isValidMobile`) and says
   「请填写11位手机号」 under the field. Files: `pages/merchant/apply/index.js`, test `m4-merchant-apply-phone-contract`.
3. **成为主理人 form: the 下一步 button did nothing when disabled** (steps 1 and 2). Now it writes the missing fields
   in red under them (`m4-club-apply-field-error.jpg`, `-dead-button-before.jpg`). Files: `pages/club/apply/*`.
4. **创建俱乐部 form: same dead 下一步** on type, name and city (`m4-club-create-type-error.jpg`,
   `m4-club-create-name-error.jpg`). Files: `pages/club/create/*`.
5. **群发通知: 发送 was greyed until you had tapped 预览受众, with no hint.** Tapping 发送 now shows red errors for an empty
   title or body, or runs the audience preview for you and shows the head count; one more tap sends
   (`m4-club-notify-send-feedback.jpg`). Files: `pages/club/notify/*`.
6. **Chain acceptance failed at `merchant_club` step 12** on this branch head: the recruit-deadline job could not move
   a theme to 定价 because the empty test DB has no platform topic-rate rule, and k1 made a missing rule refuse (right for
   money). CI's chain acceptance would have been red. The gate now seeds the 3% rule after building the schema (same
   place it already seeds the openid fixture; `seed_fixtures.py` is pinned by a gate, so I did not touch it). File:
   `ci/chain_acceptance.py`; `ci.test_chain_acceptance` 92 tests OK. Proof: all four chains PASS on the rig after it.

I broke two things on the way and fixed them: my first push left `r4-input-error-under-field-sweep` red (fixed in the
notify commit), and the club apply change moved two lines that `ui-integrity-baseline.js` anchors by line (re-anchored,
no allowance added).

## Gates

- `build-action-ledger.js --check`: PASS at 5743 controls after `--write`. My changes add 2 button-feedback events:
  pins updated with notes in `action-ledger.test.js` (5741 → 5743 controls, taps 2519 → 2521, event contracts
  2774 → 2776).
- `tests/lint/ui-integrity.js`: UI-GATE-0:PASS after re-anchoring two club apply baseline lines and the u4Dynamic digest (no allowance added).
- Unit tests I ran after each change: the files that name the pages I touched, all pass. New file
  `m4-merchant-apply-phone-contract.test.js` (3 tests). `action-ledger.test.js` runs about 4 minutes; last run: 46 of 46 pass.
  the line at the end.
- No Java changed, so no Java tests. One CI file changed (`ci/chain_acceptance.py`).

## For the lead (not fixed, not mine)

- **Money:** nothing found. I did not change settlement. No settled amount was on any screen I walked.
- **Raw server error reaches the screen.** When the server fails to write a response (here the OSS setting was missing),
  the toast shows the exception text 「Could not write JSON: 对象存储配置缺失: CHENGYIN_OSS_BUCKET_NAME」. In production the
  setting exists so I expect it not to happen, but any such 500 shows its internal text. A global handler that hides the
  text of `HttpMessageNotWritableException` would fix it; that is framework code, so I left it.
- **Member merchant status:** an applicant whose application is approved becomes role merchant, and the order gate then
  refuses every club ticket (「商家不能报名俱乐部活动」, owner decision 2026-10-22). Working as decided; I only note it
  because it blocked my own player chain until I reset the role in the rig database.
- **Club-owner 9003 on seed club 7001** still shows 「当前账号无权进入该俱乐部」 on 结算, 入会申请, 客户 (k4 N-12 says seed data).
  I used my own new club for those pages and they work.
- Large pinned title overlaps the status bar when the topic page is scrolled (`m4-r04-detail2.jpg`). Player topic page:
  not my lane.

## Not walked / unverified

- A phone: nothing. Camera scan, licence upload, WeChat share, real payment.
- Club session with a merchant station through enrolment, refund and settlement report in this run (k4 and k5 own it).
- Posting and role assignment end to end; 治理 (remove a member) with a second member.
- Merchant 提现 with a balance (nothing settled in this run).
- The 自由探索 sale paid by screen (only through the chain).
- CI: not run; the owner wants one unified run at the end.

STATUS: PARTIAL - money screens match the database to the fen, but two money-side findings are open for the lead (M1 four-store 自由探索 sale never settles; M2 zero-node 自由探索 still unpublishable), and nothing was tried on a phone, camera or CI.
