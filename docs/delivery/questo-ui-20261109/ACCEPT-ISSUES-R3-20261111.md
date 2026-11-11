# 城瘾 mini-program acceptance walk, round 3: launch/questo-20261110

- **Tested SHA:** hengyin PR #779 head `833027d04ae122a2ffcf90a627287ef30f158093` (8 commits after round 2's 9617cbba). Tarball of `chengyinhub-xcx` in `acc3/mp`.
- **CI:** see `CI.md`. Red only on the two expected gates (Backend tests / "Check private Mapper compiled references", Fast gates / "Check architecture changes against trusted CI base"). Nothing else failed.
- **Backend:** TEST only `http://127.0.0.1:8099` (cy-dw29-master-app). No backend source changed between 9617cbba and 833027d0 (0 files under chengyinhub-*/src/main), so the round-2 jar (CI run 43089942872, git 4b61b364) stayed. No redeploy, no restart. `siteBaseUrl` asserted before every shot (lib.js).
- **Local edit to the copy:** only `utils/config.js` (API base 8099 in both places, byte-identical to round 2's edit). The app compiled and booted clean in DevTools (no WXML/WXSS error, no gesture.wxs patch).
- **Shots:** 273 in `shots/`, log `log.json`, contact sheets `sheet-{player,player-play,merchant,club,author}.jpg`, DB changes `DBCHANGES.md` (all restored, md5 verified). Every shot was looked at (Read) at readable size.
- **Accounts:** player 990050 (swap 990047<->990050), merchant 990047 (original), club leader + author 990044 (swap 990047<->990044). Swaps undone at the end.
- **Env limits unchanged:** no OSS (covers are placeholders), no AI config, no WeChat Pay cert, DevTools has no WebGL/sensors, automation cannot scroll an inner `scroll-view`.

## Round-1 + round-2 status (every row)
| id | status now | evidence |
|---|---|---|
| B-1 | fixed | app boots, compile clean, gesture.wxs pages render |
| M-1 | fixed | merchant workbench renders projects (merchant-05) |
| M-2 | fixed | 勋章墙 dark with 「还没点亮勋章 / 去探索」 (player-16) |
| P-1 | fixed | topic 990053 `heroCta={kind:'closed',text:'已截止'}` (player-180 flags); no 立即报名 |
| P-2 | fixed | checkout title 「自玩门票」 (player-183) |
| C-1 | fixed | club manage 「现在要做 去招商 / 开一场」 (club-03); coop/list 403 is still requested (see N-18) |
| P-3 | fixed | tools menu 「我的权益 1 家 · 已核销 0 家」 matches header 1 家商家 (player-172) |
| P-4 | fixed | 「各站奖励」 has icon + chevron (player-172) |
| P-5 | still broken | station detail: 「到店三步」 heading with nothing under it, fixed 到店打卡 bar covers it (player-175). Stray bullet is gone |
| P-6 | partly fixed | back button now on the pack screen; the frayed white artifact at the pack's bottom-left and the missing title remain (player-170) |
| P-7 | fixed | chapter label sits on its own row, no longer under the 未到店 pill (player-171) |
| P-8 | fixed | place card shows 距离 / 分钟步行 stats; the opening-hours sentence is body text now (player-110, player-130) |
| P-9 | partly fixed | station names no longer wrap to 4 lines; caption 「这一页尚未书写。你的脚步还没抵达这里。」 still shows (player-173) |
| P-10 | fixed | finish copy 「你已完成第 1 个主题 · 再完成 4 个到 5 个」 (player-190, mock) |
| P-11 | fixed (copy) | location modal copy shortened to 「精确位置会发送给城瘾以确定首页城市，之后自动定位，不用于附近排序。」; 编辑精选 empty = env. Placement left in L2-1 |
| P-12 | fixed | detail stats row is 预计游玩 / 总里程 / 参与商家 / 已参加, no wrapped date (player-70 s1300). Cover placeholder = env |
| P-13 | fixed | library chips fine with pass on (player-12) |
| P-14 / L-1 | fixed | stamp album dark (player-17) |
| P-15 | fixed | distinct icons: AI 对话记录 chat, 投诉建议 megaphone, 帖文处置与申诉 shield-alert, 我的申诉 shield-check (player-13) |
| P-16 | fixed | roam nearby 全部 chip has a pill, real map renders (player-20) |
| P-17 | fixed | roam tab map renders in this DevTools run (player-07) |
| P-18 | fixed | 票夹 shows the seeded ticket card (player-09) |
| A-1 | fixed | mytemplate 「草稿站点：到点即完成。」 (author-05) |
| A-2 | env, not re-checked | AI precheck needs AI config + full publish check; not reachable |
| A-3 | still broken | fabu title clipped 「自由探索草稿 · 付费自玩…」 at the right edge; on 票务设置 the fixed bar (返回 / 预览) still covers 「添加票种 收起」 (author-01, author-03) |
| A-4 | fixed | 模板介绍 now a clean intro with 开始创建, no 「暂无精选示例」 (author-04) |
| ME-1 | fixed | store info uses 未设置 consistently (merchant-28) |
| ME-2 | fixed | 更多 sheet icons all distinct (merchant-27) |
| ME-3 | fixed | earnings status bar readable (merchant-15) |
| ME-4 | partly fixed | player tab bar now has a dark gradient backdrop (fixed there). Merchant light pages still show card text through the tab icons (merchant-02, -05, -18) |
| C-2 | fixed | 本场名册 · 0 人 with chips all 0 and 「这一段暂时没有成员」 (club-06) |
| C-3 | changed, leader start still blocked | leader now reaches the 导演台 (`pages/club/topic-detail/index?topicId=9912625&clubId=990008&activityId=9912701`) with a 开始准备 button, and `/api/game/session/view` no longer 500s (status NOT_PREPARED, availableActions [PREPARE]). PREPARE then returns outcome FAILED / GAME_TEAM_REQUIRED 「共享玩法需要本场有效队伍」; see R3-1. The old route `pages/play/index?topicId=9912625&activityId=9912701` still shows 开始游戏 with session/view 500 「本场游戏尚未准备」 |
| L-2 | fixed (transition) | first play frame is a black transition; content renders in the next frame (player-160-1 then -2) |
| L-3 | fixed | chapter gate shows a dot-matrix 02 in the look colour (player-110 / k1) |
| L-4 | still broken | 商家主页 from brand center 「链接已失效」 (merchant-12) |
| L-5 | fixed | 口碑 shows only 「还没有真实到店评价」, no reload button (merchant-21) |
| L-6 | still open | see L2-1 |

Totals for these 37 rows: fixed 28, partly fixed / changed 4 (P-6, P-9, ME-4, C-3), still broken 4 (P-5, A-3, L-4, L-6), env 1 (A-2).

## Round-2 new rows (N-1 .. N-19, L2-1, L2-2)
| id | status now | evidence |
|---|---|---|
| N-1 | partly fixed | 继续探索 clears the home indicator in STARDUST (player-122, button bottom ~805/866). In CYBER the button's bottom edge is flush with the screen and the indicator is drawn over it (player-161 crop). Survival not re-captured after animation |
| N-2 | still open | 选择票种 opens with the single ticket unselected, 下一步 greyed (player-181) |
| N-3 | env | pay button 「重新检查支付服务」 (player-182); no WeChat Pay cert |
| N-4 | env | 通行证 plans ¥299/¥39, button 「当前设备暂不可开通」 (player-11) |
| N-5 | still open | free self-play needs 补全姓名和手机号 before 免费领取 (player-183 go) |
| N-6 | fixed (survival) | dice is a dark card on the rain look, only the die itself is white (player-133). Paper dice not replayed |
| N-7 | still broken | STARDUST and CYBER album: viewer is a white screen (player-124-2, player-163-7), draw screen is saturated blue with a light-grey card (player-125, player-164), card detail is a light-grey page (player-126). Root cause in R3-2 |
| N-8 | still open | ballShake kit is plain black, look gone (player-132-2). No blue/white block, but no rain either. Sensors not available |
| N-9 | fixed | settlement title is the topic name 「纸本旧书 · 人民广场寻字」 and the page is plain black (player-184) |
| N-10 | still open | library still lists only the two 南京路 themes, not 9912702..4 (player-12) |
| N-11 | still open (partly env) | 通关卡: big title text is covered by the card, only stray glyph fragments show at both card edges at mid height; the card's 里程/用时 labels are cut by the card edge; dot-matrix logo overlaps the card top (player-141). Background is now the PAPER warm brown with orange pill. The 3D card is a DevTools approximation (no WebGL), so real-device state unverified |
| N-12 | still open (env) | after 继续 the map state is black with a white 「输入地址」 search bar (player-142). The same DevTools renders the roam map fine |
| N-13 | still broken | timeline: 「就标这些」 button is pushed 6pt below the bottom edge and sits under the home indicator (player-153-4 crop); prompt still says 「右侧」 in a one-column list |
| N-14 | fixed | CYBER shows a cyan 开始任务 / 开始 button, cyan titles and a pink edge/streak (player-160, player-163) |
| N-15 | = L2-1 | |
| N-16 | fixed | 我的 → 通行证 tab shows the 通行证 card ¥299 · ¥39 (player-10) |
| N-17 | still open | approve confirm title 「通过「城瘾用户 · 2026-11-12 02:46 申请」的入会申请」 (club-25) |
| N-18 | still open (partly) | `/api/coop/list` 403 「商家身份无效或权限不足」 still requested by the club owner on club home/manage. The routeDone console error was not reproduced |
| N-19 | still open | = A-3 title clip |
| L2-1 | still open | home 首页城市定位 is a centred modal that stays fixed over the feed while the page scrolls, no scrim (player-01 s0 / s700). Shorter copy only |
| L2-2 | fixed | 我的 → 通行证 shows the plans and a disabled 当前设备暂不可开通 button (player-10, player-11) |

Totals: fixed 5 (N-6, N-9, N-14, N-16, L2-2), env 2 (N-3, N-4), partly fixed 1 (N-1), still open 12 (N-2, N-5, N-7, N-8, N-10, N-11, N-12, N-13, L2-1 which is also N-15, N-17, N-18, N-19). N-15 is a duplicate of L2-1.

## Four looks, every kit
| kit | look | in-story (inline) | full-screen | verdict |
|---|---|---|---|---|
| qa PICK | STARDUST | dark, answer 「答对了」 in green on dark, 继续故事 clear of indicator (player-121) | n/a | OK |
| coinFlip | STARDUST | n/a | dark, silver coin, result 绿荫线, 继续故事 clear (player-151/152) | OK |
| timeline/compare | STARDUST | n/a | dark rows, but button under indicator (N-13) | button broken |
| album viewer | STARDUST, CYBER | n/a | WHITE screen with grey blur (N-7) | broken |
| album random draw | STARDUST, CYBER | n/a | SATURATED BLUE screen, light-grey card (N-7) | broken |
| card detail | STARDUST | n/a | LIGHT GREY page (N-7) | broken |
| diceRoll | SURVIVAL | dark card, white die (player-133) | same | OK |
| ballShake | SURVIVAL | n/a | plain black, 开始 button clear (player-132) | OK (look missing, N-8) |
| qa TYPE, coin, dice, album | PAPER | not replayed (seeded run is completed; protected) | | not checked |
| timeline, qa TYPE, ballShake | CYBER | not replayed | | not checked |
CYBER chrome shows cyan/pink accents (N-14 fixed). Chapter-gate numbers are dot-matrix in the look colour in all looks seen.

## New rows (round 3)
| id | role | page | screenshot | what's wrong | severity | suspected file:line | env? |
|---|---|---|---|---|---|---|---|
| R3-1 | club | 导演台 开始准备 | club-22/23/24 | After the confirm 「进入准备」 the backend answers `/api/game/session/command` PREPARE outcome FAILED, reasonCode GAME_TEAM_REQUIRED, result.reason 「共享玩法需要本场有效队伍」. The leader only sees a generic toast 「操作未生效，请刷新后重试」, never the reason. A leader cannot tell they need a signed-up team | major | pages/club/topic-detail/index.js onPrimary / command result handler (reads receipt but not `result.reason`) | test data: session has 0 signed-up teams, but the missing message is real |
| R3-2 | player | full-screen kits (album viewer / draw / card detail / ballShake) | player-124/125/126/132/163/164 | The x-fix4 N-6/N-7 token fix has no effect for full-screen kits: `--pk-embed-*` are defined on `.play[class*="look--"]` (pages/play/look-tokens.wxss:11) but `<cy-playkit id="playKit">` (pages/play/index.wxml:1768) and `<cy-playkit-album>` (index.wxml:1991) are siblings outside the root `.play` (DOM check: the kit is not a descendant of `.play`), so they fall back to the skin defaults (#0b6adc blue, #e6e3e4 grey, white viewer). Fix: also define the tokens on the page root / `:host` of these layers, or render them inside `.play` | major | pages/play/index.wxml:1768,1991; pages/play/look-tokens.wxss:11; components/playkit-random/index.wxss:15,46,154 | no |
| R3-3 | club | 导演台 header | club-20 | Date line reads 「10月31日 — 10月31日」 (topic dates) while 集合时间 is 11月12日 06:56 | minor | pages/club/topic-detail/index.js header date | partly test data |
| R3-4 | club | 导演台 | club-20 | `/api/game/session/shared/control` 500 「本局没有共享运行控制」 requested for a non-shared session | minor | pages/club/topic-detail/plan-fallback.js:29 | no |
| R3-5 | player | 旅程手记 (finished city run) | player-143 | All four stations (第 101/102/201/202 站) are listed under 「第一章 · 缺字的扉页」; the chapter picker says 2 chapters × 2 places | minor | pages/play journey sheet grouping | maybe seed shape |
| R3-6 | player | 游玩工具 (finished run) | player-144 | Still offers 「开始这一局」; 本局读数 / 开始这一局 / 切换章节 share the same route icon | minor | pages/play/index.wxml tools list (~l.300-360) | no |
| R3-7 | player | topic detail 990053 and play pages | player-180 flags | `/api/play/journey/casebook` and `/api/play/encounter` are called repeatedly and 500 「这一局还没开始」; `/api/topic/share-code` 500 「个人码存储失败」 | minor | topic/play pages polling | partly env (OSS) |
| R3-8 | player | 主题记录 | player-146 | 用时 「未知」, 解谜分 「本局不计分」 for a completed run | minor | subpackageJourney/pages/report | seeded run |
| R3-9 | player | 通关卡 | player-141 | see N-11 (finale text covered by card, labels cut) | major if real on device | pages/play/components/chapter-flip/index.wxss | DevTools has no WebGL |

## Verified OK this round
- Compile clean at 833027d0; all pages in the round-2 sweep render with no blank/white-on-dark errors except those listed.
- Player pages dark, merchant pages light, settlement and club settlement plain black, 通行证 plans, 票夹, 收集册 dark, roam map renders, 发帖 entry present on own 帖文 tab (compose row 「有什么新鲜好玩的分享吗?」 with image/location icons, player-191).
- Chapter picker (切换章节) on the finished paper run lists 第一章 已完成 ✓ and 第二章; 回看旅程手记 sheet and 主题记录 report open.
- Chapter editor: a text block is NOT draggable (long-press `onStoryDragArm` leaves `storyDrag.key` empty, no `.story-grip` on text blocks), a node block is (pages/publish/fabu/index.js:2195-2203 `armed.type === 'text'` returns; index.wxml `story-grip` has `block.type !== 'text'`).

## Not reached
- Paid self-play price red text: the publisher ticket page has no self-play section any more (owner decision a5-v3-29, step3.wxml:319-320; selfPlayPrice is only written for productType 1 in index.js:8361-8363, never entered in the UI). The ticket editor's price field sits under the fixed bottom bar and DevTools automation cannot scroll the inner scroll-view (touch drag, scrollTo and pageScrollTo all had no effect), so the price red text was not seen. With `onEditTicketChange` price 0.5 on the free-explore draft `canSaveTicket` stays true (price may be empty/paid for productType 2).
- Leader start of the club session: reached 导演台 and 开始准备, blocked by GAME_TEAM_REQUIRED (needs a signed-up team; the player could not sign up because the free ticket needs participant name/phone, N-5).
- WeChat Pay, pass key-opening animation (jsapi unavailable), ballShake/diceRoll sensor interactions (DevTools has no sensors), PAPER and CYBER kit-by-kit replay (the seeded paper run is completed and was protected; cyber ballShake/timeline/qa not replayed), 核销 scan, A-2 AI precheck.
