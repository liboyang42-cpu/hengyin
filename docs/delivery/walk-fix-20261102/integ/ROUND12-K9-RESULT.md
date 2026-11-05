# Round 12, lane k9-pages: result

Branch `integ/launch-test-20261103`. I worked on a private local branch and ran `git pull --rebase` and `git show --stat` before each push. I did not force-push, touch master, merge a PR or touch production. There are no keys in the diff.

**Rig: yes, Docker ran this time.** `dockerd` started in the container. It ran the rig on `claude/cloud-walk-20261101-rig` with the club lane's harness patch, a real jar (MySQL 8, Redis 7) and the real mini-program in headless Chrome at 390×844.
- "Before" shots: taken on the base tip `3ee591fc`.
- "After" shots: taken on my commit `a00c2114e`, with the browser bundle rebuilt from it.
- The jar was **not** rebuilt for my two Java changes. Both are proven by unit tests only; see items J1 and J2.
- **This is not a phone.** The rig's MySQL and Chrome run in UTC, and covers and QR codes don't load (HARNESS).
- I looked at every shot named below. All shots are under `integ/fix-shots/k9-*`.

**A second k9 worker pushed to the same branch while I worked.** Commits `918c6380c`, `5a9a5975e`, `fe10e9cc5`, `8d05beed7`, `393e079f1`, `4f86c7503` and `9c1f301de` are labelled 第十二轮 k9. They are not mine. They cover p3 N6 tickets, N9, N13, N16 and N19. I rebased onto them; the only conflicts were the action ledger and its counts, which I rebuilt. Where my table below says "listed" for those items, read "done by the other k9 worker, see its commits". I didn't re-walk that work.

No migrations. No new endpoints. No write contract or money rule changed. No gated `@Test` added or removed, so `ci/funds_it_gate.py` is unchanged.

## k2's leftovers

| Item | Status | What changed | Files | Proof |
|---|---|---|---|---|
| p1 N-4 参与详情 for a non-owner | fixed | `无权查看该报名信息` / `报名记录不存在` now show 「链接已失效 · 返回」. Before, the load-failure state was blank: only the title was left. | `subpackageMember/components/scene-member-participation-detail/index.{js,wxml}` | `k9-p1N4-mycanyuinfo-not-owner-before/after` (9004 opening 9009's reg 74); `r12-k9-pages.test.js` |
| 排行榜 guest double line | fixed | The guest sees 「登录后查看排行榜 · 登录」 once. | `subpackageP3/pages/growthcenter/leaderboard/index.wxml` | `k9-p1N5-leaderboard-guest-*` |
| 队伍详情 guest wording | fixed | 「请重新登录后再打开这支队伍」 is removed. The page shows 「登录后查看队伍 · 登录」. | `pages/team/detail/index.js` | `k9-p1N5-team-detail-guest-*` |

## p1's LOW list (k2 item 6)

| ID | Status | What changed | Files | Proof |
|---|---|---|---|---|
| N-6 「请返回重新进入」 | fixed | Removed from all 27 places, including the error subs set in JS. Each page keeps its title and a back button. The chat's missing-link state gets 「返回消息列表」, and its footer no longer says it a third time. The club "gone" state gets a 「返回」 button. A test scans product code for the phrase. | 14 wxml + 8 js (see commit) | `k9-p1N6-userinfo-no-id-*`, `k9-p1N6-chat-no-id-*`, `k9-p1N6-mycanyuinfo-no-id-*`; `r12-k9-pages.test.js`; contract tests `c06-*`, `v4-club-share`, `ui-review-0831` updated |
| N-7 zero rows | fixed (part) | 「我的足迹」 is not drawn while all four rows are 0. **Not done:** 月度回顾 「路线」 empty for a finished theme. That is the counter, and I didn't trace it. | `subpackageP3/pages/growthcenter/index/index.{js,wxml}` | `k9-p1N7-growth-new-player-*` (walk-player-1) |
| N-8 角色卡 by link | fixed | If the theme has no visible attributes, the page shows 「链接已失效 · 返回」. The four stacked lines are gone. | `subpackageMember/character/index.wxml` | `k9-p1N8-character-link-*` |
| N-9 票夹 pill | fixed | The pill now sizes to its content, so 「我的队伍」 doesn't wrap. The team name gets the room up to the page margins. | `subpackageMember/signup/index.wxss` | test only. The rig player has no team on a ticket: **not walked**. |
| N-9 个人介绍 | fixed | An empty intro is one row: 「个人介绍 · 未填写」. 「介绍一下你自己」 stays only as the edit box placeholder. | `pages/gerenziliao/gerenziliao.wxml` | `k9-p1N9-profile-no-intro-*` |
| N-9 home role chip 「✓ 玩家 · 上海市」 | **needs owner** | Not changed. This chip is the owner's P-01 real-phone layout from 2026-10-27, pinned by `devfix-l04-home-template-20261027`. Default: keep it. | — | — |
| N-10 chat header | fixed | A link without `name` now reads the other person's nickname from `/api/im/conversations` (read only). | `subpackageB/pages/im/chat/index.js` | `k9-p1N10-chat-no-name-*`: 「对话」 → 「俱乐部主理人」 (9008 on conversation 6) |
| N-10 3D badge | fixed (part) | With no name in the link, 「徽章」 is no longer repeated under the coin. The badge wall already passes the real name; only a bare link lacks it. | `subpackageP3/pages/badge-3d/index.wxml` | `k9-p1N10-badge-3d-no-name-*` |
| N-11 结算 ¥69 three times | fixed | 订单金额 only shows when a club discount or points row is below it. | `pages/activity/baoming/baoming.wxml` | `k9-p1N11-checkout-after` (9009, activity 10: 标准场次票 ¥69 / 总计 ¥69). The before state is p1's W01. |
| N-12 申请开票 title | **not done** | I made it and then reverted it. The page is listed as a small-title page that copies 结算, and two title inventories pin that (`nav-page-title-spacing-contract`, `title-system-owner-contract`). Moving it means re-classifying it in those contracts, and the invoice feature is off. | — | — |
| N-13 another player's coupon QR | fixed | `无权查看该券` now shows 「这张券不在你的券包里 · 返回」. Before it said 「稍后重试」. Timers stop. | `subpackageMember/components/cy/scene-qr-coupon/index.{js,wxml}` | test only. The rig has no coupon rows: **not walked**. |
| N-14 invite refused for lack of a ticket | fixed | When the refusal is `TICKET_REQUIRED`, the button becomes 「去买这一场的票」 and opens 结算 for that session. | `pages/team/join/index.{js,wxml}` | test only. Team commands hang in the rig without `wx.getRandomValues` (HARNESS, p1 H-8): **not walked**. |
| N-15 play page banner | not mine | This is play page layout and goes to k8 (play page). | — | — |
| N-16 开始玩 / 开始游戏 | fixed | 参与详情 now says 「开始游戏」. | participation detail wxml | test |

## k3's leftovers

| ID | Status | Notes |
|---|---|---|
| p3 N6 lost category | **not a copy bug** | Rig read-back: `/use` of template 990015 made draft 990016 with `category_ids=1`, the same as its source (`k9` api run). The editor only shows ids that exist in the live category list; the rig's seed id `1` isn't one (live ids start at 21). So 主题详情 「还有 1 项未填」 comes from category data, not from the copy. |
| p3 N6 lost tickets | done by the other k9 worker | `918c6380c`: 应用 now copies the theme's own tickets. I would have listed it for you, since it creates priced rows; please check it. |
| p3 N6 each 应用 makes a draft | fixed | If this page already made a draft, tapping 应用 again reopens that draft. The in-flight lock was already there. File `pages/templatedetail/topic-detail/index.js`; test. |
| p2 N-9 wrong role / guest leftovers | fixed | (1) The shared login gate (`cy-access-gate`) now starts below the nav bar, so the back arrow shows on every guest gate. It was hidden on all of them, not only decor: `k9-p2N9-decor-guest-*`. (2) 客户 and 核销与退款 get the login gate for guests: `k9-p2N9-customer-guest-*`, `k9-p2N9-ledger-guest-*`. (3) A player opening a customer page reads 「当前账号不能查看客户 · 返回」, not 「联系店主开通」: `k9-p2N9-customer-detail-player-after`. (4) `merchantinfo?topicId=` for an unrelated account shows 「链接已失效 · 返回上一页」 instead of a blank page: `k9-p2N9-merchantinfo-wrong-param-*` (9002 on 990013). |
| p2 N-13 coupon form | fixed (part) | 投放数 no longer pre-fills a fake 「0」. **Listed for owner:** no value field. Adding an amount to coupons is a write contract. The default validity window is the picker's default and was not changed. |
| p2 N-15 AI 形象 status | fixed | After saving, the status chip follows the receipt. A machine pass no longer reads 「审核中 · 玩家现在能看到这个形象」. File `pages/merchant/decor/ai-npc/index.js`. |
| j1 N7 limit heading | fixed | 「当前身份不能发布活动」 → 「暂时不能发布活动」. The reason line stays the backend's. |
| j1 N11 apply intro | fixed | Same title-to-button gap as the club intro: `k9-j1N11-apply-intro-*`. The empty upper half is the bottom-anchored intro layout, the same on the club side. |
| j1 「授权状态待确认，可点击申请」 | fixed | When nothing has been asked yet, there is no sub-line. The switch is the action. |
| j1 「长按卡片管理」 | **needs owner** | The owner's design 260:369 says "no buttons on cards". A ··· on the card contradicts it. Default: keep. |
| j1 N8 verifier id | **listed for owner** | Recording the clerk instead of the shop is a data model / write change. |
| p3 N9 transfer | done by the other k9 worker (`fe10e9cc5`) | Still open: the former owner losing 俱乐部结算 (their 可提现) is a product question, k1's area. |
| p3 N13, N16 | done by the other k9 worker (`8d05beed7`, `5a9a5975e`) | |
| p3 N18, N19 | N19 done by the other k9 worker (`393e079f1`); N18 not done | N18 may be the rig. |
| p2 N-11 early redeem, N-12 invite without `shareMode` | **listed** | Money rule and write contract, as k3 said. |
| p2 N-14 rest, N-16, N-17 rest | not done | |
| Item 5: "not covered" by fixture | partly | I walked **all 32 p3 pages as a guest** at `a00c2114e` (bundle). The visible text of each is in `fix-shots/k9-p3-guest-sweep.txt`. Every page shows a login gate, 链接已失效 or real public content, with two exceptions. (1) 剧情与玩法 with no theme was blank under its two tabs: **fixed** (`pages/club/topic-story`, test). (2) 俱乐部勋章 with no id uses the auto-back missing-param shell: by design. Still showing helper lines in missing-param states: club/edit 「没有指定要编辑的俱乐部，请返回俱乐部页重新进入。」, join-requests 「缺少俱乐部信息，请返回俱乐部列表后重新进入」, group-code 「请从俱乐部管理进入具体场次」. **Not done.** game-node, coop settlement-detail, batch-detail and predict with data still need the j2 journey fixtures: **not done**. |

## j5 LOW list (second pass)

| ID | Status | Notes |
|---|---|---|
| N3 draft held on save while WeChat busy | **listed** | The update path holds a draft with no `saveDraft` guard; the create path has one. This changes moderation hold behaviour, so I left it to you. My default: add the same guard, and let publish run the check. |
| N4 hit-log rows rolled back on refusal | **listed** | Fixing it needs `recordHit` in its own transaction (a write-path transaction change). |
| N5 raw field key | fixed | 「节点玩法「桥边问答」.title」 → 「节点玩法「桥边问答」的标题」. A field with no readable name keeps its path, so the author can still find it. `JourneyReviewTexts.plainField`, used by `TopicTextSafety` too. Test `JourneyReviewTextsPlainFieldTest` (2). **Not done:** the link over the status bar, and 重试 resending blocked text. |
| N6 「活动尚未发布」 on a live edit | fixed | The title is 「修改没有保存」 when editing. **Not done:** blue block → field-level red text; summary panel over 「添加票单」. |
| N7 「玩法没有发布成功」 on 保存并绑定 | fixed | Now 「玩法没有保存成功」. |
| N8 「刚刚前」 | fixed | `pages/square/detail/index.js`; test runs the function. |
| N9 lands on 主题 tab | already fixed | k3 (j1 N6). |
| N10 「审核中」 wraps | fixed | `nowrap` on the chip. |
| N11 merchant without `scope` | not done | |
| N12 activity bound game not in safety text | **listed** | Write path. |
| N13 taglines | **needs owner** | The activity edit line and the chat footer 「系统通知不支持回复」 are pinned by owner-approved contracts (CU-C-103, CU-C-120). The 评价 points line may be a disclosure. Default: keep. |
| Carried L2, L5, L6, L7, L8, L10 | not done | |

## j6 LOW list (second pass)

| ID | Status | Notes |
|---|---|---|
| N4 `club/roles?id=` blank | fixed | Accepts `id` like the other club pages. A missing club shows 「链接已失效 · 返回」. |
| N4 `club/settlement` member sent home | not changed | The gate shows the 「暂时不能查看」 sheet, then leaves. Not re-walked. |
| N8 「剩余 20 张」 after an order | likely HARNESS | The detail re-reads on component `pageLifetimes.show`, which the rig doesn't fire (j6 F4). |
| N8 / F9 unpaid 「微信实付」 | fixed | An unpaid order reads 「微信待付」. |
| N8 / F10 `cy_usage_pending_v1:<id>` after logout | by design | It is the consent-sync ledger. It is kept on purpose so that logging back in reuses the request id (`utils/roam-player-memory.js:233`). It holds no account content. |
| N8 captions on cancel / refund / logout dialogs | **needs owner** | j1 counts these as consequence text, j6 as captions, and p3 left them for you. I only fixed the ASCII punctuation in 取消订单. 投诉与建议's line is pinned by `c04-r3` ("the limit must not be lost"). |
| N8 `stamp-camera` with no image | not done | |
| N8 `topic/list is_my=1` 「请先登录」 as code 500 | fixed | It now answers business code 401, so the request layer silently logs in again and retries. `ApiTopicController.topicList`; test `ApiTopicListLoginCodeTest`. |

## Java (not walked)

- **J1:** `ApiTopicController` (the 401 change above).
- **J2:** `JourneyReviewTexts` / `TopicTextSafety` (the field label change).
- Tests, run with `mvn -pl chengyinhub-admin -am`: `ApiTopicListLoginCodeTest` 1, `JourneyReviewTextsPlainFieldTest` 2, `JourneyReviewTextsTest` 23, `TopicTextSafetyTest` 7, `ApiTopicListToMerchantPublisherTest` 2, `ApiTopicMerchantOperatorScopeTest` 9. All green.
- The rig jar was not rebuilt, so neither change is proven over HTTP.

## Gates

- `node tests/lint/ui-integrity.js`: `UI-GATE-0:PASS`. No baseline entries were added or moved. Where my edit shifted an anchored line, I kept line counts equal instead.
- `build-action-ledger.js --check`: PASS. 5718 → 5726 controls (event contracts 2768 → 2774: six 「返回」 exits; tap 2507 → 2509: 去买这一场的票, and the 个人介绍 row split in two). `action-ledger.test.js` counts updated, 46/46.
- `tests/unit/r12-k9-pages.test.js`: 25 tests. The first 20 were checked against the base tree, where all 20 fail.
- Full mini-program unit suite: see "Full suite" below.

## Full suite

FULL_SUITE_PLACEHOLDER

## CI

CI_PLACEHOLDER

STATUS_PLACEHOLDER
