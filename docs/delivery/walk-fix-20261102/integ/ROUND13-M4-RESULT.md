# Round 13, lane m4-merchant-club-walk: result

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

STATUS: PARTIAL - six bugs fixed and walked; session-with-station, refund and settlement screens and money screens with real balances were left to k4/k5 and not re-walked, and no phone, camera or CI run.
