# 城瘾 mini-program acceptance walk, round 5 (final): launch/questo-20261110

- **Tested SHA:** hengyin PR #779 head `0c02e1e68868f00f0df2a6fc43488c51d0cabba9` (merge ref 1f040f4a). Tarball of `chengyinhub-xcx` in `acc5/mp`; only edit: `utils/config.js` (API base lines 2 and 32 to `http://127.0.0.1:8099`, identical to round 4).
- **CI:** `CI.md`. 5 workflows, 31 GitHub CI jobs: 29 green, red ONLY Backend tests / "Check private Mapper compiled references" and Fast gates / "Check architecture changes against trusted CI base". All 4 other workflows green (incl. Release migration rehearsal). No blocker.
- **Backend:** test only `http://127.0.0.1:8099` (`siteBaseUrl` asserted before every shot). `PlayJourneyStoryServiceImpl` changed since f63ccf10, so the jar was redeployed from CI run 43142975164 (sha256 9d9b9a7e...), `DEPLOY.md`. Startup 50 s, no 1054/1146/NoSuchBean; only env errors (ALARM_WEBHOOK, WeChat Pay cert).
- **Compile:** every one of the 171 `app.json` page paths opened via reLaunch in DevTools: all 171 loaded, zero Compile/wxss/wxml errors (`allpages.json`; the 13 that landed elsewhere are role/param guards, re-opened under the club role, `allpages-club.json`). wcsc over all 481 .wxss (each as root with every other .wxss as import set, `-lc -js`): **0 failures**. wcc over all 465 .wxml (+14 .wxs): **0 failures** (single run with the full file set, then per file). Negative controls: the R4-1 `;`-in-comment construct fails in wcsc ("unexpected"), an unclosed tag fails in wcc. No blockers. R4-1 is fixed.
- **Accounts:** player 990050, merchant 990047, club leader + author 990044. Swaps undone, md5 verified (`DBCHANGES.md`). No walk step wrote rows (game rows dump identical before/after); club 开始准备 receipt 54 deleted; session times restored.
- **Shots:** 246 in `shots/`, all in `ACCEPT.json` (196 pass, 35 fail, 15 env). Contact sheets `sheet-{player,merchant,club,author}.jpg`; every sheet and the key full-size shots were looked at (Read).
- **Method notes:** four-looks x kits injected into the real play page (6 kits x 4 looks); the `openKitStart`/`confirmKitStart` flow did not open a kit in the seeded progress state (shots 110-113 show the station card). One run of the DevTools screenshot call stopped working mid-walk; re-running `cli auto` on the same port fixed it (not an app issue).

## Open items (by severity)
| id | sev | role | page | what | evidence | env? |
|---|---|---|---|---|---|---|
| R4-5 / N-11 | minor | player | 通关卡 (finale) | ghost glyph slivers of the big title at both card edges mid-height still show (「纸…」 left, 「…字」 right). The 里程/用时 labels are no longer cut (improved) | player-140-pp-fin-2, player-150, player-147-pp-journal-2 | DevTools has no WebGL; unknown on device |
| R4-6 / N-12 | minor | player | map behind chapter picker / tools | no map tiles in DevTools; bg is now the look colour (dark brown) instead of pure black, white 输入地址 bar remains | player-147-pp-journal-4, -148-pp-tools-4 | DevTools (no tiles) |
| P-5 | minor | player | free-explore station detail | 「到店三步」 heading with nothing under it at first viewport, 到店打卡 bar over the steps | player-174-fx-station-3 | inner scroll not driven for this page, may be fine when scrolled |
| P-6 | minor | player | free-explore pack screen | back button present now; the foil pack still has a frayed white edge bottom-left and no title | player-170-fx-1-mc | no |
| N-5 | minor (owner decision) | player | self-play checkout | free ticket still requires 补全姓名和手机号 (backend `RegistrationOrderCreateGateImpl` rule; x-fix6 left it) | player-183-n5-selfplay-9912703-go, player-77/78 | no |
| N-18 | minor (decision: keep) | club | home / manage | `/api/coop/list` 403 「商家身份无效或权限不足」 in the console, no UI effect | club-02, club-03, club-13 | no |
| R5-1 | minor | club | leader play before session start | 500 on `/api/play/journey/casebook` (这一局还没开始) and `/api/game/session/view` (本场游戏尚未准备): expected states answered with 500 (same class as R4-3; present in R4 too, not flagged then) | club-11-club-leader-play | no |
| N-10 | info | player | pass library | lists only the two 南京路 themes; rule (official published self-play topics), seed topics do not qualify | player-12-pass-library, -99 | seed |

No blocker, no major.

## Env-only
- No OSS (placeholder images, `/api/topic/share-code` 500 「个人码存储失败」 on paper play load), no AI config, no WeChat Pay certificate (pay button 「重新检查支付服务」, 通行证 「当前设备暂不可开通」), no WebGL (3D 通关卡 approximation), no map tiles, no sensors, topic 9912627 「主题活动不存在」 (seed), empty 编辑精选 on home (no featured seed topic).

## Rows re-checked this round
| id | status | evidence |
|---|---|---|
| R4-1 play page compile | **fixed** | pages/play/index loads; wcsc 481/481 clean |
| R4-2 完成面板 row | **fixed** | 保存图片/分享 fully above 查看结算 and 继续 (player-147-pp-journal-3) |
| R4-3 ending 500 | **fixed** | `/api/play/journey/ending` now 200 on play load (player-150 requests); jar redeployed |
| R4-4 publish bar ghost | **fixed** | solid bar (author-02-draft-p1) |
| R4-5 finale | still open (see above) | |
| R4-6 map black | partly (look colour now), env | |
| L2-1 / N-15 / P-11 / L-6 home first-city prompt | **fixed** | non-blocking card in the feed (player-01-home-first), 暂不定位 dismisses it and falls back to 上海市. Position: below the carousel, above 编辑精选 (the documented default), not above the carousel |
| N-1 | **fixed** | continue/start buttons clear the indicator in STARDUST, SURVIVAL, CYBER (player-160/161/162) |
| N-5 | still open (owner decision) | |
| N-10 | rule, info | |
| N-11, N-12 | = R4-5, R4-6 | |
| N-18 | still (kept) | |
| A-3 | **fixed** | scrolled to bottom, 添加票种 clear of the bar (author-03-ticket-scrolled-bottom) |
| ME-4 | **fixed** | merchant tab icon row opaque (merchant-05, -18) |
| P-5 | still open | |
| P-6 | partly | |
| P-9 | **fixed** | journal sheet rows without captions (player-173) |
| R3-8 | partly | 主题记录 opens; 用时 「—」, 解谜分 「本局不计分」 on a seeded run with no timing/score |
| R3-2 / N-6..N-8, N-13, N-14 (kits) | **fixed** | 6 kits x STARDUST/SURVIVAL/CYBER/PAPER all dark, accent per look, buttons clear of indicator (player-130..133) |

## Regression sweep
Merchant 31 shots (20 pages + sheets), player 175 (home, templates, talent, roam, member, wallet, passes, membership, pass library, settings, favourites, badges, stamp album, messages, official routes, coupons, 9 topics, 4 looks, checkout, baoming, self-play, settlement, finish card, journal, tools, chapter picker, free-explore pack/tools/journal/station, 24 kit shots), club 24, author 16. No new visual regression against round 4.
