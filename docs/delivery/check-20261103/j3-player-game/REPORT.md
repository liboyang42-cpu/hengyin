# j3-player-game: pre-launch check, first pass

- Lane: j3 (player's game) from `ROUND8.md`. Branch `claude/check-1104-j3-player-game`, cut from the commit under test.
- Commit tested: `ecef2f078ae3cb53fa41621c71d5fea3cf9124e4` (on `integ/launch-test-20261103`).
- Walked 2026-11-04, about 23:10 to 01:00 Asia/Shanghai. Fictional data only.

**This is the cloud rig, not a phone.** It runs:
- the real jar on real MySQL 8.0.45 and Redis 7.4 (images from `mirror.gcr.io`, same digests);
- the real mini-program code in headless Chrome at 390×844, 2x;
- `claude/cloud-walk-20261101-rig` with the club lane's `harness-patches.diff`, `up.sh` pinned to the commit above, and `LANG=C.UTF-8`.

Shots are in `shots/`: 780×1688 JPEG, all under 200 KB. I looked at every shot I quote. Scripts are in `tools/`. `tools/rig-changes.diff` is the full diff on the rig worktree.

## Summary

Four things stop a player for good:

1. **竞猜 station after 20:00 (BLOCKER, F1).** It answers 「今天这一轮已经截止,明天再来」 and leaves only a disabled 「先完成上面的玩法」. A linear solo route cannot go on that night.
2. **D20 三档难度 station (BLOCKER, F2).** The station shows 「该模板未配置高级玩法」 and has nothing to play. The backend counts `check` as a game only when `outcomeRules` exists. The editor's game picker does not add `outcomeRules`.
3. **拍照审核 when the photo check can't run (BLOCKER, F3).** The server says retake. The screen says 「这次没能审，先往下走」 and offers only a disabled 「这一张已定」.
4. **Led session (HIGH, F4).** The 「等队长放行」 wait screen never appears, so 返回地图 can't be reached. The player reads the locked chapter, walks to its first station and answers. Only then does the server say 「本章还没开启,等队长放行」.

Other HIGH findings:
- **F5.** A finished theme reopens as new: 「开始游戏」, 序章, the location prompt. There is no way back to the ending or the finish card.
- **F6.** A station answered but not closed with 「继续故事」 is lost on reopen. 「开始任务」 then fails silently anywhere but at that station.
- **F7.** The theme page shows 「约 4分钟」 for 270 minutes of stations.
- **F8.** Checkout's 「姓名」 is the profile 昵称, so a nickname reaches the organiser as a real name.
- **F9.** The solo-pass order says 「可玩至 12/04」 at purchase but 「权益到期 2027-02-03」 in detail. It also shows 「不可退款」 four times, 票价 「— × 1」, and a 「到集合点开场」 step.
- **F10.** Profile 「Lv.5」 vs 成长中心 「Lv.57」. 0 积分 is shown as 「TOP60%」 and 「第 4 名」.

What worked:
- Buying the solo pass in the UI, through the real payment callback.
- 20 of 31 game types played to a correct server result.
- Hints with score steps (100 → 70 → 40).
- The 尾声 ending, its ending page and the finish card. Badge `TOPIC_FINISH_990019` was written.
- Free-exploration recap.
- All four 画面风格 looks, as the author sets them and on the play page.
- Fonts and text effects in the editor preview.

## Setup (labelled)

| What | How | Ids |
|---|---|---|
| Solo themes like the live 990069 (club theme, self-play pass, opening chapter, story chapters, 尾声 ending) | API leg: `POST /api/template/draft` + `/api/topic/v2/create`, the endpoints the pro editor posts to. Game configs come from the editor's own `defaultConfig` / `applyToConfig` / `validate` / `serialize` (`tools/gencfg.js`), with fictional content. | 990015 (27 games, lead 9008, club 7002), 990016 (24 games, lead 9003, club 7001), 990017 (10 games), 990018 (7 photo and sensor games), 990019 (2 legacy hint stations + 尾声) |
| Free-exploration theme like 990068 | The rig's own `chains.py player` (all four chains PASS) | topic 990014, activity 13, player 9009 |
| Led club session | Club lane's `rig-replay/fixtures.py`, adapted (`tools/l6/`): theme, `open-session`, one player, a team, PREPARE + START through `/api/game/session/command` | topic 990021, activity 20, player `j3-led-1` (member 990005) |
| Approvals | Real admin endpoint `/business/audit/review` (`tools/approve.py`), only my own topics | — |
| **FIXTURE: theme on sale** | The sale review refused every theme with 「effectiveSaleEndAt 必须早于或等于 ticket.startTime」 (j2's J2-02). `cms_topic` set to lifecycle 3 as in j2 (`tools/fx_onsale.py`) | all solo themes |
| **FIXTURE: look** | The editor's look sheet produced the look string (read back from `formData.look`, shots 94–98). Saving through the editor first needs the 主题详情 gaps filled (category, cover), which is p3's ground. The same string was written to `cms_topic.look_json` (`tools/fx_look.py`). | 990016 末世, 990017 纸本, 990018 夜城. 990019 got its look at create. |
| **FIXTURE: images** | Three generated PNGs placed in the rig's upload dir under `/profile/upload/2026/11/04/` (the product's system-image path) | — |
| Player 9009's pass for 990019 | API quote + create, then the rig's payment seam (`tools/buy_api.py`). 9004's passes were all bought in the UI. | reg 80 |

**Rig seams added** (all from j2's report and diff, no product code changed):
- local OSS stand-in and `api.weixin.qq.com` stand-in (`img_sec_check` → ok), trusted in the JVM, with dummy `CHENGYIN_OSS_*`;
- `wx.chooseLocation` queue and `wx.getRandomValues` in `nav.js`.

**New rig fix (HARNESS).** On every page whose root has `bindtouchstart` (here `pages/topic/index`), taps did nothing. The rig's `runtime.js` called `setPointerCapture` on the ancestor at `touchstart`, so the click went to the root, not the button. I limited capture to the element actually touched. **This is the dead-tap j2 reported on `pages/topic/index`. It is a rig bug, not the page.** With the fix, 查看详情与报名, 随时开玩, 去结算 and 立即支付 all work.

**Rig clock.** The walk ran at night Shanghai time. That matters for F1.

## 1. Steps

| # | Step | Result | Shots | Read-back |
|---|---|---|---|---|
| A1 | Home → theme page 990015 → 查看详情与报名 → 「¥9.9 随时开玩」 | PASS. The duration chip is wrong (F7). | 01–05 | — |
| A2 | 去结算 → 确认订单 | PASS | 06 | — |
| A3 | 补全姓名和手机号 → profile → 昵称 「林夏」 → 保存 | PASS, with F8. Back on checkout it still says 补全姓名和手机号 until reopened. | 07–10, 12 | `/api/user/update {"name":"林夏"}` |
| A4 | 立即支付 without consent → 「请先勾选同意」. Tick → pay. | PASS | 13–15 | reg 75: owner_type 1, ¥9.90, payment_status 2, real_name encrypted |
| A5 | Same purchase in the UI for 990016, 990017, 990018 and 990019 | PASS | 61–64, 100–103, 160–163 | regs 76–79, all ¥9.90/¥6.60, status 2 |
| B1 | Play page intro → 开始游戏 → 序章 card → story → 出发 | PASS (taglines, F14) | 20–24 | `journey/opening/complete` 200 |
| B2 | Chapter 1 text → 继续探索 → location consent → station sheet | PASS (helper lines, F14) | 26–29 | — |
| B3 | Every game type, see section 2 | 20 PASS, 3 FAIL, 8 NOT VERIFIED (of 31) | 30–141 | per row |
| B4 | Hints on a legacy 文字作答: 看提示 1 → 2, wrong answer, right answer | PASS. 100 → 70 → 40 解谜分, then 「实在解不出，揭示答案」. The author's answer reveal is not shown after a right answer (F17). | 166–171 | `/api/play/puzzle/hint` 200 ×2, `/api/play/answer` 「答案不正确」 then 「答对了！」 |
| B5 | Last station → 尾声 text → 继续 → 「这一生的结局」 page → particles → 全部完成 card (2 坐标 · 01:00 · 200 解谜分) → 继续 / 保存通关卡 | PASS, with F15 | 182–187 | `topic_route_session` 5: status 1, completed_at set. `player_badge` 3: `TOPIC_FINISH_990019`. |
| B6 | Reopen a finished theme | **FAIL (F5)** | 177, 181, 188, 189 | Server still has the session COMPLETED |
| B7 | Medal screen after finishing | NOT SEEN. No medal screen appeared between the ending and 全部完成 (as l1 found in Round 6). The badge shows in 成长中心 (212). | 186, 187, 209, 212 | `player_badge` row exists |
| C1 | Led session: start, chapter 1 stations 1–2 | PASS | 230–233 | `play/answer` 200 ×2 |
| C2 | Wait-for-release screen at the chapter 2 gate | **FAIL (F4).** No wait screen. Chapter 2's card and text play. | 234–237 | `game_session` currentChapterId 73 (chapter 1) |
| C3 | 返回地图 on the wait screen | NOT VERIFIED: the screen never shows (F4) | — | — |
| C4 | Lead `UNLOCK_CHAPTER 74` → answer station 3 again | PASS | 238, 239 | before 「本章还没开启,等队长放行」, after 「答对了！」 |
| D1 | 我的 → 我的订单 → 查看详情 (solo pass) | FAIL in content (F9) | 200–206 | reg 78 |
| D2 | Free-exploration order detail (9009, chain data) | PASS, with F12 | 221, 222 | reg 74 |
| D3 | Free-exploration play page after the day ended → 看看这一天的收获 | PASS | 223–226 | — |
| D4 | 我的 → 成就 → 查看详情 (成长中心) → badge → 查看 | PASS, with F10 | 207–212 | `player_badge` 3 |
| D5 | 成就 → G 0 → 积分明细 | PASS: empty state 「还没有积分记录」. Finishing a solo theme gave EXP and a badge, no 积分. | 211 | `ums_member_points_detail` empty for 9004 |
| D6 | 设置 → 发票 | NOT VERIFIED: 「发票功能暂未开放」. The server answers `INVOICE_FEATURE_OFF` in the rig. Whether production has the flag on is unknown. | 213 | — |
| D7 | 票夹 (9009) | Partly FAIL (F13) | 220 | — |
| E1 | 画面风格 as the author sets it: editor → 主题详情 → 画面风格; each style; each font; each text effect | PASS. Preview updates for every choice. | 90–99 | `formData.look` read back each time |
| E2 | 星尘 (default and 暖/密/快 + 蓝 + 黑体 + 呼吸光) on the play page | PASS | 20–36, 164–176 | `lookClass` `look--stardust …` |
| E3 | 纸本 + 楷体 + 墨晕 on the play page | PASS | 104–129 | `look--paper look-paper--kraft … story-font--kai` |
| E4 | 夜城 + 粗斜 + 解码 on the play page | PASS. The decode is caught mid-glitch in 133. | 130–141 | `look--cyber … story-font--bold` |
| E5 | 末世 (雨, chapter times 夜/黄昏) + 宋体 on the play page | PASS. Rain and a warm dusk tint on chapter 2. | 150–153 | `look--survival look-climate--rain … story-font--song` |

## 2. Game types

| Game | Theme / station | Result | Shots | Notes |
|---|---|---|---|---|
| 选项问答 | 990015 s1 | PASS | 30–32 | Wrong answer: grey 「再想想」 bar, not red. 提交 is redundant because tapping an option submits (F16). |
| 文字问答 | 990015 s2 | PASS | 34–36 | Wrong is red now (P-10 fixed). The input is cleared on a right answer. 「不对。把它清掉，再试一次。」 shows when it is already cleared. |
| 排序 | 990015 s3 | PASS | 40–43 | Order reshuffles on each entry |
| 连线 | 990015 s4 | PASS | 44–47 | — |
| 分类 | 990015 s5 | PASS | 48–51 | On re-entry 「还有 2 条没分」 and the old 「还有分错的地方」 show together |
| 猜数字 | 990015 s6 | PASS | 52–54 | 「米」 is still far left of the number (l1's P-09) |
| 竞猜 | 990015 s7 | **FAIL (F1)** | 56–60 | — |
| 抽卡 | 990016 s1 | PASS | 65–68 | The card face shows only 「线索卡」, not the task text. It is in the card's detail view, which I did not open before the station closed. 换一张 and 就这张 overlap the card. A loud blue panel sits on the dark side. |
| 角色建档 | 990016 s2 | PASS | 69–72 | Title shown twice. 「各项指标在范围内」 is an odd stamp for a character file. |
| 留言 | 990016 s3 | PASS | 73, 74 | — |
| 今日城市签 | 990016 s4 | PASS | 75–78 | Light panel on the dark side, two back buttons. 「NO. 0000」 stays after the claim (fake zero). 「撕不下来?点这里」 uses an ASCII 「?」. |
| 抛硬币 | 990016 s5 | PASS | 79, 80 | A tap stands in for shaking |
| 掷骰子 | 990016 s6 | PASS | 81, 82 | — |
| D20 三档难度 | 990016 s7 | **FAIL (F2)** | 84, 153 | — |
| D20 加值 | 990017 s1 | PASS | 111, 112 | Button says 「继续」 where every other game says 「继续故事」 |
| 闭眼盲品 | 990017 s2 | PASS | 113–117 | 「店员会把小样摆在你面前」 in a theme with no shop. No right/wrong verdict after answering. |
| 输入挑战 | 990017 s3 | PASS | 118, 119 | — |
| 精准停表 | 990017 s4 | PASS | 120 | Light panel on the dark side and six lines of helper text. 「还能错 ●●●」 collides with the tagline chip (F14). |
| 变色就点 | 990017 s5 | PASS | 121, 122 | Best 284 ms over 3 rounds |
| 倒计时 | 990017 s6 | PASS | 123, 124 | — |
| 猜图 | 990017 s7 | PASS | 126–128 | A wrong pick shows nothing at all. The images fail to load (HARNESS), so the fallback tiles are bright red and blue. |
| 找东西 | 990017 s8 | NOT VERIFIED (HARNESS) | 129 | The server sends `imageUrl: ""` (rig image path). The station has nothing to tap and no message. Later stations on this linear route are blocked. |
| 相册, 拍物成卡 | 990017 s9, s10 | NOT VERIFIED | — | Behind 找东西 |
| 拍照打卡 | 990018 s1 | PASS | 135–137 | Real upload through the OSS stand-in. Says 「答对了」 for a photo. |
| 拍照审核 | 990018 s2 | **FAIL (F3)** | 138–141 | — |
| 罗盘指向, 弹球, 安静挑战, 喊一嗓子, 计步挑战 | 990018 s3–s7 | NOT VERIFIED | — | Behind 拍照审核. They also need a phone's sensors, microphone and WeRun. |
| 九宫格 | — | NOT VERIFIED | — | Configured at theme level. Not authored. |

## 3. Findings

### F1. BLOCKER: 竞猜 stops a solo route after 20:00

- Repro: 990015 station 7 (竞猜, default close hour 20). At 23:2x tap 「不会」 → 就押这个.
- Server: `SUBMIT_PREDICT` → 400 「今天这一轮已经截止,明天再来」.
- Screen: a sheet with the red error, an empty 「进行中」 card and a disabled 「先完成上面的玩法」. Re-entering offers the same cards again. The 游玩工具 menu has no skip.
- Effect: in a linear route every later station waits until the next day. The copy then says 「明天 20:00 由商家给出答案」, though this club theme has no shop. Daytime completion is NOT VERIFIED; the rig runs on real time.
- The error also uses an ASCII comma.
- Shots 56–60.

### F2. BLOCKER: D20 三档难度 has nothing to play

- Repro: a template whose only game is `check`, made by the editor's own `applyToConfig(defaultConfig(), 'check')` with success and fail text. Template draft and theme publish both accept it.
- At the station, `POST /api/play/advanced/start` → 500 「该模板未配置高级玩法」. The screen shows that message with 「点击重新读取」 and nothing else.
- Cause: `AdvancedGameConfigValidator.java:474` counts `check` only together with `root.has("outcomeRules")`. The editor's picker (`pages/publish/temp/index.js:2786–2797`) adds `outcomeRules` only for `typeIn`. Otherwise it is added only when the author edits 结果反馈 (`temp/index.js:743`, `temp/r5-editor.js:208`).
- I did not walk the template editor UI. Whether its save path refuses a D20 with no 结果反馈 is NOT VERIFIED; one tap-through would settle it.
- In a linear route every later station is stuck. Here that was 17 stations of 990016.
- Shots 84, 153.

### F3. BLOCKER (when the photo check can't run): 拍照审核 dead end

- Repro: 990018 station 2, 拍一张 → 单独同意 → the upload succeeds and `SUBMIT_PHOTO_CHECK` returns 200.
- Server state: `verdict: DEGRADED`, `lastReason: 「照片读取失败,请重拍」`, `tries 1 of maxTries 3`, `fallback: retake`, `passed: false`.
- Screen: 「这次没能审，先往下走」 with only a disabled 「这一张已定」. No retake, no continue, and the same on reopen.
- The rig cause is HARNESS (no model service). The dead end is the product's own failure path, and the client contradicts the server.
- The photo preview frame is empty (stand-in host, HARNESS).
- Shots 138–141.

### F4. HIGH: led session, no wait-for-release screen

- Repro: activity 20 (lead 9008, topic 990021), PREPARE + START. The player finishes chapter 1.
- What happens: chapter 2's card and text play as if open. The player walks to station 3 and answers.
- Server: 「本章还没开启,等队长放行」 (500), shown as a line inside the question.
- After the lead's `UNLOCK_CHAPTER 74` the same answer passes.
- 「等队长放行」 and 返回地图 (`pages/play/index.wxml:499–501`) never appear for a session run by the director runtime (`/api/game/session/command`).
- The header 「带领中 · 0/2 人」 → 「1/2 人」 changed when one player finished a station. The count's unit or meaning looks wrong.
- Shots 230–239.

### F5. HIGH: a finished theme reopens as new

- After the 尾声 (9004) or the whole finish flow (9009), reopening the play page shows the intro with 「开始游戏」. That leads to 序章 and the location prompt again.
- There is no 已通关 state and no way back to the ending, the finish card or the 足迹卡.
- The server has the run COMPLETED and the badge awarded. The client calls `run-session/clear` on open.
- 票夹 does say 已通关 (220).
- Shots 177, 181, 188, 189.

### F6. HIGH: answered but not closed, the station is lost on reopen

- Repro: answer 990015 station 2 correctly, leave before 「继续故事」, walk 50 m on, reopen.
- 「开始任务」 → `advanced/start` (already solved) → `POST /api/play/arrive` 500 「还没到这一站附近」. The sheet returns with no visible message; the player must walk back.
- The server already holds the solved state, which could complete the station.
- Shots 37, 38.

### F7. HIGH: theme page shows minutes as seconds

- 990015 has 27 stations × 10 min: `cms_topic.total_time` = 270, set as the sum of the editor's `nodeTime` minutes in `CmsTopicServiceImpl.calculateTotalTime`.
- The page formats it with `formatTimeToChinese(seconds)` (`pages/topic/index/index.js:1998`, `:2098`) and shows 「8 章 · 约 4分钟」.
- Any theme shows a duration about 60 times too short.
- Shot 05.

### F8. HIGH: checkout's 姓名 is the nickname

- 「补全姓名和手机号」 opens 个人资料, which has 昵称 and no 姓名.
- The checkout reads `user/info` `name` as `realName` (`pages/topic/index/index.js:1581`), and the profile saves 昵称 into the same `name`.
- So the nickname reaches the organiser as the real name. Nicknames are also unique-checked, so two people can't share a real name.
- After saving, checkout still shows 「补全姓名和手机号」 until it is reopened.
- Shots 06–12.

### F9. HIGH: solo-pass order detail says the wrong things

On the order detail for reg 78:
- 「可玩至 12/04（主题结束）」 at purchase (05) but 「权益到期 2027-02-03 00:05」 here.
- 「不可退款」 four times: hero chip, card title, right label, and 「已开始,不可退款」 with an ASCII comma.
- 票价 「— × 1」.
- An 「到集合点开场 · 完成后更新」 step a solo pass never reaches.
- The order list also shows active passes as 「不可退款」 and the 我的 cards as 「待核销」. There is nothing to redeem at a counter.
- Times read 「11.03 00:00 – 12.04 00:00」; the theme starts and ends at 09:00.

Shots 200–206.

### F10. HIGH: profile numbers disagree

- 我的 header 「Lv.5 城市探索者」; 成长中心 「Lv.57」.
- With 0 积分: 「总积分排名 TOP60%」 on 我的 and 「总积分榜 · 第 4 名」 in 成长中心.
- 「累计距离 0 km」 after about 1 km of stations.
- Shots 207–212.

### F12. HIGH: free-exploration order times out of order

- 「权益已核销 2026-11-04 00:00」 and 「核销时间 2026-11-04 00:00」 come before 「付款成功 23:10」. A date-only value is shown as 00:00.
- 「已完成 / 已经完成 / 已完成」 is shown three times.
- The order was written by the rig's chain; check whether a real counter scan stores a time.
- Shots 221, 222.

### F13. LOW: 票夹 card on a finished pass looks unfinished

- A decorative orange route with 5 points on a 2-station theme.
- Grey skeleton bars and 「生成中…」 in the DOM that never fill.
- Shot 220.

### F14. LOW: taglines, helper text and secondary labels (owner's UI rule)

Each line below needs the layout change named after it.
- Play intro: 「读 跟着故事走 / 玩 到站解开玩法」, 「建议戴耳机，打开声音」 (21). Let the 开始游戏 button and the title carry it.
- 序章 card: a generated 「跟着脚步走完这几处坐标，把散落的线索串成一段属于你的城市故事。」 the author never wrote (22). Show the author's own chapter text or nothing.
- Station sheet: 「走到这里，留意四周——故事就藏在细节里。」, 「本站任务」, 「到达后开启本站任务」 (29). Name, address and the button are enough.
- Tools menu: a sub-line under every row (60), and 「本局读数 · 章节 7」 while in chapter 2.
- Solo-pass sheet: 「不用选场次」 and 「期间可随时开玩本路线。请遵守安全须知…」 (05). The safety line may count as legal text; owner's call.
- 排序 (40), 连线 (44), 分类 (48) and 竞猜 (56): an instruction line under each question.
  - The 竞猜 line is drawn over the cards.
  - 分类 also shows 「都放好了，看看下面对不对」 in a large empty space.
- 精准停表: six lines (120).
- Legacy questions: 「当前最高 100 解谜分 · 不影响探索值」 (166). Show the score once, at the result.
- 成长中心: 「查看获得时刻」 (209).

### F15. LOW: ending copy and effect

- The ending page says 「这一生的结局」 and 「这一生的档案」. That wording belongs to the 预制人生 story product and reads oddly on any other theme.
- The finish runs a full-screen particle burst (184, 185). The owner's rule says no explosions. It should respect `reducedMotion`; not checked.
- The intro screen and the 序章 card are the same gold pyramid in every look (22, 106, 132).

### F16. LOW: answers and feedback

- 选项问答: tapping an option submits, yet a 提交 button stays (30).
- Wrong answers in 选项问答 and 分类 use a pink or grey block. The owner's rule is red text under the field.
- 猜图 wrong pick: no feedback (127).
- 闭眼盲品: no verdict after answering (117).
- 拍照打卡 says 「答对了」 (137).
- Buttons vary between 「继续故事」, 「继续」 and two stacked buttons (分类 「分好了」 + 「继续故事」; 城市签 「出发」 + 「继续故事」).

### F17. LOW: author's 揭示答案 text unused after a right answer

On 990019 station 1 the right answer shows the feedback line only, not 「1873，这座楼重建落成的那一年。」. The same holds for the advanced 文字问答 with `reveal: true` (36).

### F18. LOW: editor look sheet layout

- The pinned preview covers the 「主题配色」 heading when the sheet scrolls (95-font-bold).
- The editor lists a published, on-sale theme as 「主题详情 还有 2 项未填」 and 「票务设置 还有 1 项未填」 (90). That may come from my API-authored data (category id 1 and cover path), so treat it as a lead for p3.
- Chapters read 「第1章 序章」 then 「第一章 江边1」 (90).

### F19. LOW: the 手写 story font loads from fonts.gstatic.com

`pages/play/utils/story-font.js:15` loads the full Long Cang TTF from `fonts.gstatic.com`. Google's font CDN is generally not reachable from mainland China, so players there will likely fall back to the system font. Only a phone in China can confirm.

### Leads, not mine

- `fee_platform_rate` reads 0.1000 on every club theme after 确认终价 (`cms_topic`). The owner's rate is 3%. j2 saw 3% at settlement through `settlement_rule`. Worth a look by the money lane.
- The sale review refusal (J2-02) blocked all five solo themes.

## 4. Fonts and text effects (专业主题 画面风格)

- **Author side.** Editor → 主题详情 → 画面风格.
  - Each of the 4 styles (94-*), each of the 5 fonts (95-*) and each of the 13 text effects (96-*, 97-*) was tapped, and the preview changed each time. `formData.look` read back the chosen value every time.
  - 粗斜 shows bold italic in the preview.
  - The rig has one CJK font (WenQuanYi), so 宋体, 楷体 and 黑体 can't differ from each other here. NOT VERIFIED on a phone.
  - My captures of text effects landed after each effect finished, because of the driver's wait after a tap. The motion itself is therefore NOT VERIFIED from shots; the code replays it on each change (`look-preview/index.js:68–78`).
- **Player side.**
  - 星尘 with 呼吸光: glow visible (175).
  - 纸本 with 楷体 and 墨晕: serif, characters split for the effect (107, 109).
  - 夜城 with 粗斜 and 解码: mid-decode glyphs captured (133).
  - 末世 with 宋体 and rain (151).
  - 13 effects × 4 styles on the play page were not each walked.

## 5. What I did not cover

- 相册, 拍物成卡, 找东西 (rig image path), and 罗盘, 弹球, 安静挑战, 喊一嗓子, 计步. These are blocked behind F3, and they need a phone's sensors, microphone and WeRun. 九宫格 (theme level).
- 返回地图 (F4 keeps it from showing).
- The medal screen during the finish. The badge exists; the screen did not show.
- Invoices: feature off in the rig.
- 竞猜 in daytime and the shop's reveal the next day.
- Saving the look through the editor (needs the p3 fixes for category and cover).
- Real fonts, sound, haptics, native map, share cards, saving images to the album. Phone only.
- Production 990068 and 990069 themselves. I have no production access; I built themes of the same shape in the rig.

STATUS: PARTIAL - 8 of 31 game types not played (blocked behind F1/F2/F3, rig images, or phone sensors); wait-for-release and 返回地图 unreachable (F4); invoices off; medal screen not seen.
