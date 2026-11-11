# 城瘾 mini-program acceptance walk, round 2: launch/questo-20261110

- **Tested SHA:** hengyin PR #779 head `9617cbba249a6496e0d568fb1bf18aaebdb22c50`. Tarball of `chengyinhub-xcx` in `acc2/mp`.
- **Backend:** TEST only `http://127.0.0.1:8099` (cy-dw29-master-app), jar from CI run 43089942872 (git.commit.id 4b61b364, sha256 36dab5b3...), DB cy_dw29_dev, platform pass switch on (test container only). See `dw29be/DEPLOY-R2.md`. `siteBaseUrl` asserted before every shot.
- **Local edits to the copy:** only `utils/config.js` (API base 8099 in every env branch). **No gesture.wxs patch.** The app compiled and booted clean and gesture.wxs-based pages (publish/fabu etc.) rendered. B-1 is FIXED.
- **Shots:** 273 in `shots/`, log `log.json`, contact sheets `sheet-<role>.jpg`, DB changes `DBCHANGES.md` (all swaps restored, md5 verified).
- **Env limits:** no OSS (theme covers are placeholders except the seeded Wikimedia covers), no AI config, no WeChat Pay cert, DevTools has no WebGL/sensors/real map.

## Round-1 issue status
| round1-id | status | evidence |
|---|---|---|
| B-1 | fixed | app boots with no local gesture.wxs edit |
| M-1 | fixed (not reproduced) | merchant workbench renders with projects, no 500 on access/me or project/my (logo/img media-asset restored) |
| M-2 | fixed (renders, dark) | player-16-badges now dark with 「还没点亮勋章」; wall shows no medals although the seeded user holds 4 medal claims (test data: wall reads another source) |
| P-1 | not re-checked | 990053 detail only re-shot (player-30); CTA text not verified |
| P-2 | fixed | checkout shows 「自玩门票」 (player-76/77) |
| C-1 | fixed | club manage shows 「现在要做 去招商 / 开一场」; coop/list still 403 in the log but the page no longer errors (club-03) |
| P-3..P-9 | not re-checked | free-explore play pages not walked in round 2 (no registration swap pass) |
| P-10 | not re-checked | |
| P-11 | still broken | home location modal copy still long; 编辑精选 empty (env), modal covers the page |
| P-12 | not re-checked (cover missing = env) | |
| P-13 | changed | library now lists 2 themes with chips; pass is on. The 3 official free themes 9912702..4 are NOT listed (see N-10) |
| P-14 / L-1 | fixed | stamp album is dark (player-17) |
| P-15 | partly fixed | AI 对话记录 and 投诉建议 differ; 帖文处置与申诉 reuses the AI chat-bubble icon |
| P-16 | fixed | roam nearby chip 全部 has a pill |
| P-17 | env | map black/blank in DevTools |
| P-18 | fixed | 票夹 shows the seeded 纸本旧书 ticket card |
| A-1 | fixed | mytemplate shows 「草稿站点: 到点即完成」, no literal null |
| A-2 | not re-checked | AI precheck is env |
| A-3 | still broken | fabu title clipped 「自由探索草稿 · 付…」 ; 票务设置 bottom bar overlaps 「添加票种 收起」 (author-03) |
| A-4 | still broken | 模板介绍 「暂无精选示例」 over a large empty black area (author-04) |
| ME-1 | fixed | store info uses 未设置 consistently (merchant-28) |
| ME-2 | fixed | 更多 sheet has distinct icons (merchant-27) |
| ME-3 | fixed | earnings status bar is readable (merchant-15) |
| ME-4 | still broken | tab bar still has no backdrop; card text shows through (merchant-05, player-08) |
| C-2 | fixed | 本场名册 0 人 with chips 0 (club-06) |
| C-3 | still open | leader session view 500 「本场游戏尚未准备」 before start; no 开场 control found on activity/event-ops pages in the session window |
| L-2 | not re-checked | |
| L-3 | fixed | chapter gate shows a dot-matrix 01 in the look colour (player-100/103) |
| L-4 | still broken | 商家主页 from brand center shows 「链接已失效」 (merchant-12) |
| L-5 | changed | reviews now 「还没有真实到店评价 / 重新加载」 (merchant-21); dead end gone, reload button odd for an empty list |
| L-6 | not re-checked | |

Counts: fixed 16 (B-1, M-1, M-2, P-2, C-1, P-14/L-1, P-16, P-18, A-1, ME-1, ME-2, ME-3, C-2, L-3, plus P-15 partial), still broken/open 7 (P-11, A-3, A-4, ME-4, C-3, L-4, P-15 partial), changed 2, env 1, not re-checked 9.

## New issues
| id | role | page | screenshot | what's wrong | severity | env/test-data | round1-id |
|---|---|---|---|---|---|---|---|
| N-1 | player | 故事流 (all looks) | player-82-stardust-1-mc.png, player-100-survival-5-confirmKitStar.png | The bottom 「继续探索」 button sits on the home indicator and is cut off (only its top shows). Missing safe-area padding. | major | no | |
| N-2 | player | 主题详情 9912701 | player-74-checkout-9912701-picker.png | The seeded city-orient topic had no session, so 立即报名 said 「暂无可参加的场次」. I added activity 9912702 + ticket 9912704 (¥0.01). Also the 选择票种 sheet opens with no ticket selected and 下一步 greyed; the single ticket should be pre-selected. | minor | partly | |
| N-3 | player | 确认订单 ¥0.01 | player-75-baoming-9912701-pay.png | Stop point reached: pay button reads 「重新检查支付服务」 (payment readiness fails, no WeChat Pay cert). 参与人信息 is required first. Env. | info | env | |
| N-4 | player | 通行证 | player-11-membership.png | Plans ¥299/¥39 show, button 「当前设备暂不可开通」. /api/platform-pass: jsapi PAYMENT_UNAVAILABLE, xpay XPAY_DISABLED. Key opening animation not reachable. | info | env | |
| N-5 | player | 自玩门票确认 | player-77-selfplay-buy-9912703-go.png | Free self-play needs 参与人 name+phone (orange 「补全姓名和手机号」) before 免费领取. Registrations for 9912703/4 were inserted by SQL. | minor | partly | |
| N-6 | player | 骰子玩法 (survival) | player-102-survival-dice-4-confirmKitStar.png | The dice card is a bright white rectangle on the dark rain look, with square corners. It ignores the look. 3D dice absent in DevTools (2D fallback). | major | partly | |
| N-7 | player | 相册随机抽卡 (stardust) | player-87-stardust-album5-7-tapl.png | Draw screen is saturated blue and the card detail is light grey/white inside the dark stardust look. Album viewer is a white screen with a blurred placeholder. | major | partly (images off) | |
| N-8 | player | 弹球 (survival) | player-101-survival-ball-6-confirmKitStar.png | Kit screen is plain black: the rain/orange look is gone. Needs motion sensors, so the interaction was not reached. | minor | partly | |
| N-9 | player | 结算 9912702 | player-97-settle-9912702.png | Title reads 「本局」 instead of the topic name. casebook returns 500 「这一局还没开始」 (completed run written by SQL has no session). Settlement is plain black as required. | minor | partly | |
| N-10 | player | 游戏主题库 | player-99-pass-library-after.png | Pass is on but the library lists only the two 南京路 themes, not the seeded free official self-play themes 9912702..4. | minor | probably flag/test data | |
| N-11 | player | 通关卡 (9912702) | player-94-paper-done-1-mc.png | The 保存通关卡 screen shows stray text fragments at both edges of the card (mid height), and the dot-matrix logo overlaps the card top. The screen is grey, not the PAPER look. | minor | no | |
| N-12 | player | 地图态 after 继续 | player-96-paper-foot-4-tapl.png | White search bar 「输入地址」 on the black screen, empty map (DevTools). Light element in the dark UI. | minor | map env | |
| N-13 | player | 时间轴玩法 | player-90-stardust-timeline2-4-confirmKitStar.png | 「就标这些」 button is under the home indicator; the prompt says 「右侧」 though the list is one column. After 标对了 the node did not count (3/4 on relaunch). | minor | partly | |
| N-14 | player | CYBER look 9912704 | player-103-cyber-4-taplkbtnprimar.png | look_json neon=CYAN_PINK but accents are yellow; no cyan/pink anywhere. | minor | verify | |
| N-15 | player | 首页 | player-01-home.png | The 首页城市定位 modal sits over the page in all scroll shots and blocks the feed. | minor | no | P-11 |
| N-16 | player | 兑换 pass vs passes page | player-10-passes.png | 通行证 page 「暂无在售通行证」 while 我的 → 通行证 shows plans; two pages disagree. | minor | maybe different feature | |
| N-17 | club | 入会申请 | club-12-join-approve-after.png | The confirm pop-up title uses the request timestamp as the name: 「通过「城瘾用户 · 2026-11-12 02:46 申请」…」. | minor | no | |
| N-18 | club | 俱乐部首页 | club-02-club-home.png | Console error 「routeDone with a webviewId … not found」 on entry; coop/list 403 still requested by a club owner. | minor | partly | C-1 |
| N-19 | author | 编辑器 | author-01-draft-9912705.png | Seeded free-explore draft 9912705 title and 已存云端 chip clip at the right edge. | minor | no | A-3 |

## Verified OK this round
- Four looks apply (lookClass look--stardust / paper / survival / cyber): cover, chapter gate with dot-matrix number in the look colour, station card glass (stardust), rough-edge buttons and rain (survival), yellow angular (cyber), kit start pop-up per look.
- qa PICK answer judged by server (「答对了」), coinFlip, timeline, album + random draw run; chapter 2 reached.
- Completed run 9912702: finish card (保存通关卡 / 继续), settlement plain black. Platform pass plans visible. Club join request pending with 拒绝/通过. Club settlement plain black. Merchant pages light, player pages dark.

## Not reached
- WeChat Pay stop (pay readiness fails, cert missing), pass key-opening animation (jsapi unavailable).
- Paid self-play price red text and chapter text-block drag: the publisher ticket page no longer has a 自玩票 section (a5-v3-29), the ticket editor was cut off by the bottom bar, no drag test. Not verified.
- Leader start of club session 9912701 (no start control found; session view 500 before start), ballShake and diceRoll interactions (sensors / 3D), per-station walk of chapters 2 for paper/survival/cyber, scroll-back review and chapter picker on 9912702 (not captured), free-explore play pages (round-1 P-3..P-9), 核销 scan, posts entry.

## Lead review additions (round 2)
| id | page | what's wrong | severity |
|---|---|---|---|
| L2-1 | home | 「首页城市定位」 card still sits over the middle of the feed (round-1 L-6) — should be a proper sheet/top banner that doesn't cover content, or dismiss cleanly; check against master's original prompt | major |
| L2-2 | 我的 → 通行证 tab (pass library) | shows 「暂无在售通行证」 while ¥299/¥39 plans are on sale elsewhere — reconcile (same source of truth), and when pay is unavailable show plans with the disabled button, not "none on sale" | major |
