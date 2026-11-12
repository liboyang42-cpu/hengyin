# 城瘾 mini-program acceptance walk, round 4: launch/questo-20261110

- **Tested SHA:** hengyin PR #779 head `f63ccf10a4146a0720cd7ae6be612b6bd7f98cf8` (10 commits after round 3's 833027d0; merge ref 9c095ee0). Tarball of `chengyinhub-xcx` in `acc4/mp`.
- **CI:** see `CI.md`. 31 jobs in GitHub CI: 29 green, red ONLY Backend tests / "Check private Mapper compiled references" and Fast gates / "Check architecture changes against trusted CI base". Other 4 workflows green. No blocker in CI.
- **Backend:** TEST only `http://127.0.0.1:8099` (`siteBaseUrl` asserted before every shot). Java changed (ApiGameSessionController), so the jar was redeployed from CI run 43127423897 (sha256 ab2a4147...), see `../dw29be/DEPLOY-R4.md`. Health clean (no 1054/1146/Exception), datasource cy_dw29_dev.
- **Local edits to the copy:** `utils/config.js` (API base 8099, identical to round 3). **Compile was NOT clean without one more edit, see R4-1.** For the walk only, one comment character in `pages/play/components/playkit-album/index.wxss:5` was changed (`;` to `,` inside a comment); original saved in `tmp/playkit-album.wxss.orig`.
- **Accounts:** player 990050, merchant 990047 (original), club leader + author 990044. Swaps undone, md5 verified (`DBCHANGES.md`).
- **Shots:** 224 in `shots/`, log `log.json`, sheets `sheet-{player,merchant,club,author}.jpg`. Every contact sheet and the key full-size shots were looked at (Read).
- **Method note:** inside the play page the four looks x kits were checked by injecting the kit state (`setData playKit/album`) into the real page of each look, because the seeded progress state makes a full replay slow and mutates data. This exercises the real components and tokens, not the server-driven flow. The card-detail screen and a real album/draw run were NOT replayed.
- **Env limits unchanged:** no OSS (placeholders), no AI config, no WeChat Pay cert, no WebGL (3D 通关卡 is an approximation), no sensors, automation cannot scroll inner scroll-views (so scroll shots equal the first shot on several pages).

## New this round
| id | role | page | screenshot | what's wrong | severity | file:line | env? |
|---|---|---|---|---|---|---|---|
| R4-1 | player | whole 玩法 page `pages/play/index` | (page white: "Compile .wxss error", `reLaunch` timeout; wcsc output) | x-fix5 R3-2 added a `;` inside a comment between two gradient layers: `/* ds-ok 筒壁漫反射;主题内取外观色 */`. The WXSS compiler (`wcc-exec/wcsc`, same one DevTools uses) treats it as a declaration end: `ERR playkit-album/index.wxss(5:200): unexpected` . Result: every page that imports the album component (pages/play) fails to compile; in DevTools the play page never loads (reLaunch times out after 10 s). Likely also fails upload/preview compile. CI's 17352 gates do not run wcsc. Replace `;` with `,` or remove the comment. All other wxss files compile (scanned all with wcsc) | **blocker** | `chengyinhub-xcx/pages/play/components/playkit-album/index.wxss:5` | no |
| R4-2 | player | 完成面板 (after 通关卡 → 继续) | player-147 era, player-145-pp-journal-3 | the 保存图片 / 分享 row is half hidden under the 查看结算 button (label tops cut) | minor | `pages/play/index.wxss` finsheet actions row | no |
| R4-3 | player | play load, STARDUST topic | log player-151 | `/api/play/journey/ending` 500 「本主题未配置剧情」 on each load (seed has no ending). Page keeps working, but it is an expected state answered with a 500 | minor | `pages/play/index.js` ending request / backend | partly seed |
| R4-4 | author | 发布 page bottom bar | author-01 crop | bar is only partly opaque: the 编辑章节 button underneath shows through behind the map icon ("辑章节" ghost). Same class as ME-4 | minor | `pages/publish/fabu/index.wxss` bar background | no |
| R4-5 | player | 通关卡 (finale) | player-112-cyber-n9912716-kit, player-140 | see N-11 / R3-9: ghost glyph slivers at both card edges at mid height and the 里程 / 用时 labels cut at the card bottom still there after the x-fix5 wrap fix | minor (DevTools has no WebGL, real device unknown) | `pages/play/components/chapter-flip/index.wxss` `.cf--finale` | DevTools |
| R4-6 | player | 游玩中的地图底 | player-149 | map behind the chapter picker / tools menu is solid black with a white 输入地址 bar (same as N-12) | minor | `pages/play/index.wxml` map layer | maybe DevTools |

## Round 3 rows (R3-1 .. R3-9)
| id | status now | evidence |
|---|---|---|
| R3-1 | fixed | 开始准备 confirm now toasts 「共享玩法需要本场有效队伍」 (club-26). Backend answered 200/200 for the command; receipt row deleted again |
| R3-2 | fixed (by injection) | album viewer, random draw, compare, coinFlip, diceRoll, ballShake are dark in STARDUST, SURVIVAL, CYBER, PAPER with the look's accent (cyan, orange, rust red) and no blue/white/light-grey block (player-130..133). Needs R4-1 fixed first. Card-detail screen not replayed |
| R3-3 | fixed | director header shows 「11月12日」 (club-20) |
| R3-4 | fixed | no `/api/game/session/shared/control` 500 on the director page (club-20, club-26 logs: 200) |
| R3-5 | fixed | 旅程手记 groups 第 1 章 · 缺字的扉页 (101, 102) and 第 2 章 · 页脚的批注 (201, 202) (player-147) |
| R3-6 | fixed | tools menu on the finished run has no 「开始这一局」; 本局读数 uses an info icon, 切换章节 a route icon (player-148) |
| R3-7 | fixed / env | casebook and encounter 500s gone on topic 990053 and on play load; `/api/topic/share-code` 500 「个人码存储失败」 remains (OSS, env) |
| R3-8 | not re-checked | 主题记录 report not opened this round (x-fix5 changed journey-view/report wxml) |
| R3-9 | still open (see R4-5) | |

## Round 1-3 rows
| id | status | evidence |
|---|---|---|
| B-1 | fixed | app boots; but see R4-1 for play page |
| M-1, M-2, P-1..P-4, P-7, P-8, P-10, P-12..P-18, A-1, A-4, ME-1..ME-3, C-1, C-2, L-2, L-3, L-5 | fixed (unchanged) | sampled again: merchant workbench projects, 勋章墙, checkout title, tools menu, library chips, stamp album, icons, roam map, 票夹, store info, 更多 sheet icons, earnings, 本场名册, 口碑 (merchant-*, player-*) |
| P-5 | not re-checked | station detail 「到店三步」 not captured this round |
| P-6 | not re-checked | pass pack screen not captured |
| P-9 | not re-checked | x-fix5 says fixed (手记空站说明句); station caption not captured. The 旅程手记 now lists 已抵达 rows |
| P-11 / L2-1 / N-15 | still open | home 首页城市定位 is still a centred modal over 编辑精选 / 附近活动 with no scrim; scrolled shots do not move (player-01*) |
| A-2 | env | AI precheck needs AI config |
| A-3 | partly fixed | title now wraps to two lines (author-01). 票务设置: fixed bar still sits over 「添加票种 收起」 at the first viewport; could not scroll to test (author-03) |
| ME-4 | partly fixed | bar is faded but the card text ("estify E2E 2026029 X0", 进入合作中心) still shows through the tab icons on merchant light pages (merchant-05, -18) |
| C-3 | fixed (leader reaches 导演台 and gets the reason) | R3-1 |
| L-1 | fixed | |
| L-4 | fixed | 看公开主页 opens `pages/userinfo/userinfo?userId=990047&preview=1` with story, category, hours (merchant-30). `pages/merchant/profile/index` without params says 链接已失效 but nothing links to it |
| L-6 | still open | = L2-1 |

## Round 2 new rows (N-*)
| id | status | evidence |
|---|---|---|
| N-1 | not re-checked | continue-button clearance in survival/cyber not captured (x-fix5 added 32rpx; ballShake 开始 and compare 就标这些 buttons clear the indicator in all four looks, player-130..133) |
| N-2 | fixed | 选择票种 opens with the single ticket selected and 「下一步 · 去结算」 enabled (player-181) |
| N-3 | env | pay button 「重新检查支付服务」 (player-182) |
| N-4 | env | 通行证 ¥299 / ¥39, 「当前设备暂不可开通」 (player-10, -11) |
| N-5 | still open | free self-play checkout still shows 补全姓名和手机号 before 免费领取 (player-183) |
| N-6 | fixed | dice card dark in all looks (player-130..133) |
| N-7 | fixed (by injection) | = R3-2 |
| N-8 | fixed (by injection) | ballShake in SURVIVAL / CYBER / STARDUST / PAPER takes the look background and accent; no rain background (not a bug if intended) |
| N-9 | fixed | settlement title is the topic name on plain black (player-184, -97) |
| N-10 | still open | pass library lists only the two 南京路 themes (player-12, -99) |
| N-11 | still open | = R4-5 |
| N-12 | still open | = R4-6 |
| N-13 | fixed | compare 「就标这些」 sits above the home indicator (player-130..133) |
| N-14 | fixed | CYBER cyan 开始 button (player-132) |
| N-16 | fixed | |
| N-17 | fixed | approve confirm title 「通过「城瘾用户」的入会申请」 (club-25) |
| N-18 | still open | `/api/coop/list` 403 on club home / manage (club-02, -03) |
| N-19 | partly fixed | = A-3 |
| L2-2 | fixed | |

## Four looks, every kit (injected into the real play page of each look)
| kit | STARDUST | SURVIVAL | CYBER | PAPER |
|---|---|---|---|---|
| album viewer | dark | dark | dark | dark |
| random draw | dark card, white badge | dark, orange badge | dark, cyan badge | dark, rust badge |
| compare/timeline | dark rows, button clear | same | same | same |
| coinFlip | dark | dark | dark | dark |
| diceRoll | dark, white die | same | same | same |
| ballShake | black, beige 开始 | orange 开始 | cyan 开始 | rust 开始 |
All 20 shots dark, no light/blue blocks, buttons clear of the home indicator. Not covered: card-detail, qa TYPE, real album/draw flow.

## Other pages swept this round
Player: home, templates, talent, roam tab, roam nearby, member, wallet, passes, membership, pass library, settings, favourites, badges, stamp album, messages, official routes, coupons, 9 topic pages, 4 look topics, checkout, baoming, self-play, settlement x3, finish card, chapter picker, journal, tools menu. Merchant: 20 pages. Club: member, home, manage, join requests, session ops, activity detail, settlement, customers, leader play, director, approve. Author: draft, ticket page, editor intro, 我的节点玩法, templates, new city draft.
