# j3-player-game: pre-launch check, second pass

- Lane: j3 (player's game), `ROUND8.md` rules. Branch `claude/check-1105-j3-player-game`, cut from the commit under test.
- Commit tested: `573c463b3b457b77544e63be4271a07d5d565e09` (head of `integ/launch-test-20261103` after Round 11 i1).
- Walked 2026-11-05, about 10:00 to 13:00 Asia/Shanghai (daytime). Fictional data only.

**This is the cloud rig, not a phone.** Real jar on real MySQL 8 and Redis 7, the real mini-program in headless Chrome at 390×844, 2x. Rig: `claude/cloud-walk-20261101-rig` with j3's first-pass `rig-changes.diff` (club harness patches, pointer-capture fix, `nav.js` seams), `up.sh` pinned to the commit above, `LANG=C.UTF-8`. Shots are in `shots/` (all under 200 KB). I looked at every shot I quote. Scripts are in `tools/`; `tools/notes.md` is my running log; `tools/rig-changes.diff` is the full rig diff.

## Summary

First-pass blockers: three of four are gone at this commit; one game (D20 检定) still ends on a black screen in the rig.

| First-pass finding | Now |
|---|---|
| F1 预测 after closing stops the route | **Open (daytime) round: gone.** Choosing and going on works. The closed round was not re-walked by me (daytime rig); g4 walked it. New: the 「选」 stamp lands on the wrong card (N1). |
| F2 D20 三档 has nothing to play | **Not gone in the rig.** In a theme with d20 检定 on, the station opens on a fully black screen with no controls, while the server offers `ROLL_CHECK` (N4, same as h5 saw). Authors also hit publish refusals around it (N5). |
| F3 photo check down, dead end | NOT VERIFIED by me: 拍照审核 can't be published in a 状态 theme (N5) and I ran out of time to add it to a plain theme. h5 walked it. |
| F4 no 「等队长放行」 under the director runtime | **Gone.** 「等待放行」 and 返回地图 show; the locked chapter stays locked at its station; after the lead's `UNLOCK_CHAPTER` the player goes on. |
| F5 finished theme reopens as new | **Gone.** Reopen goes to the 尾声 and the ending page. |
| F6 solved station lost on reopen | **Still there** (owner's call pending). Reopen 51 m away: `/api/play/arrive` 「还没到这一站附近」 with nothing on screen. Back at the station it completes. |
| F7 minutes shown as seconds | **Gone.** 21 stations × 10 min reads 「约 3小时30分钟」 and 「3.5+ 小时」. |
| F8 checkout 姓名 is the nickname | **Mostly gone.** Checkout now opens 参与人信息 with 姓名 + 手机号, and the order carries the 姓名 typed there. New: the phone typed there is not the one used (N12). |
| F9 solo-pass order says wrong things | **Partly there.** A just-started pass says 「已完成」 and 「首次扫码成功，订单已完成」 (N6). Dates still read 00:00, three different end dates, 「已开始,不可退款」 with an ASCII comma. |
| F10 levels and ranks disagree | **Mostly gone.** 我的 and 成长中心 both say Lv.93. Distance now counts (1.7 km). 「总积分排名 0」 shows at 0 积分 (N17). |
| F12 free-exploration order times | NOT VERIFIED (I did not run the free-exploration chain this pass). |
| F13 票夹 card unfinished | **Still there.** Five-point decorative route, grey skeleton bars, 「生成中…」 that never fills (97). |
| F14 captions | **Partly gone.** Play intro taglines, the generated 序章 line and the station-sheet lines are gone. Still there: instruction lines under 排序 / 连线 / 分类 / 预测, 「都放好了，看看下面对不对」, 精准停表's six lines, the legacy 「当前最高 100 解谜分 · 不影响探索值」, plus new ones listed under N16 and N3. |
| F15 ending wording and burst | Wording: 「这一生的结局」 now only shows with a 角色建档, as g4 decided. The full-screen particle burst is still there (43). The intro and finish card are the same in every look (59-t2-ending2). |
| F16 answers and feedback | **Still there.** Grey 「再想想」 block and a redundant 提交 (20-t1s1-c), 猜图 gives no right/wrong (41), 拍照打卡 says 「答对了」 (42), D20 加值 says 「继续」, stacked 分好了 + 继续故事 (24). |
| F17 author's 揭示答案 unused | **Still there**, both for advanced 文字问答 with `reveal` and legacy text questions in the led session. |
| F18 editor look sheet | NOT VERIFIED (editor UI not walked this pass). |
| F19 手写 font from fonts.gstatic.com | Code now points at our own OSS (`utils/config.js:66`). The rig browser can't reach OSS, so the load is NOT VERIFIED. |

New findings, worst first (details in section 3):
- **N4 HIGH (cause unverified):** D20 检定 station: black screen, no button, no back.
- **N1 HIGH:** 预测: the 「选」 stamp lands on the card the player did not choose.
- **N2 HIGH:** 猜数字 with 结果反馈: after 「重新开始本次玩法」 the 就这个数 button is dead until the player leaves and reopens.
- **N3 HIGH:** Theme with 状态: after a wrong answer the question stays answerable but does nothing. The only way on is 背包 → 任务 → 取消 → 开始任务 → 打开原任务, and the task says 「凭证未通过」 before anything was sent.
- **N5 HIGH (author):** In a theme with 状态 or d20 检定, 拍照审核, 拍物成卡, 找东西, 翻卡 and 罗盘 can't be published, and every 结果反馈 station on the editor's default 参与方式 is refused. The messages are internal words.
- **N6 HIGH:** An unfinished solo pass says 「已完成」 and 「首次扫码成功」.

Sensor games now let a solo player go on: 罗盘 and 弹球 after two failed reads, 计步 with WeRun denied (with a message). 安静挑战 and 喊一嗓子 played with the rig's mic stub.

## Setup (labelled)

| What | How | Ids |
|---|---|---|
| Solo themes, editor configs | API leg: `/api/template/draft` + `/api/topic/v2/create` (the editor's endpoints). Configs from the editor's own `defaultConfig` / `applyToConfig` / `validate` / `serialize`, plus what the picker adds (`outcomeRules` for 输入挑战 / D20 / 相册, `temp/index.js:2795–2801`). 「+fb」 stations carry author 结果反馈 (pass/fail reply) in the shape `outcome-after.js` writes. `journeyRules` for 状态 and d20 检定 come from the editor's `story-initial-state.js` and `theme-d20.js`. (`tools/gencfg2.js`, `tools/author2.py`) | see below |
| 990014 **星尘** (default look), no 反馈, no 状态 | 21 games, club 7002, lead 9008 | 选项问答, 文字问答, 排序, 连线, 分类, 猜数字, 预测, 翻卡, 抛硬币, 掷骰子, D20 加值, 角色建档, 留言, 今日城市签, 闭眼盲品, 输入挑战, 精准停表, 变色就点, 倒计时, 猜图, 拍照打卡 |
| 990015 **纸本** + 楷体 + 墨晕, **with 结果反馈** | 9 games, club 7001, lead 9003 | 选项问答, 文字问答, 排序, 翻卡, 抛硬币, 猜数字, 输入挑战 (all with 反馈), 相册, 角色建档 |
| 990017 **夜城** + 粗斜 + 解码, sensors | 6 games | 罗盘, 弹球, 安静挑战, 喊一嗓子, 计步, 预测 |
| 990049 **末世** (雨) + 宋体, **with 状态 (生命值 10) and d20 检定** | 4 games, 参与方式 GPS (the only way it publishes, N5) | 选项问答 (反馈), 输入挑战, D20, 相册 |
| 990050 d20 检定 only, 248/267-like | D20, 相册 | — |
| Led session | j3's `tools/l6/fixtures.py`, now publishing its own 4 legacy text templates: theme 990048, `open-session` activity 25, player `j3-led-1` (member 990005), team, PREPARE + START through `/api/game/session/command` | — |
| Approvals | Real admin `/business/audit/review` (`tools/approve.py`) | — |
| **FIXTURE** on sale | `cms_topic` lifecycle 3 and `fee_platform_rate = 0.0300`, as first pass, g4 and h5 (`tools/fx_onsale.py`) | all themes |
| Passes | 990014 bought in the UI by 9004 (consent, 参与人信息, pay seam). Others: API consent + quote + create, then the rig pay seam (`tools/buy_api2.py`) | regs 74–80 |
| Images | Generated PNGs in the rig's upload dir under `/profile/upload/2026/11/05/` | — |

**Rig seams (HARNESS, no product code changed):**
- Local stand-ins for `api.weixin.qq.com` (token, `img_sec_check`, `msg_sec_check` → pass) and OSS (`tools/rig-seams/`), with a private copy of cacerts given to the app's JVM through `app.py` (`RIG_TRUSTSTORE`).
- The OSS stand-in first ran on port 9443. The server refuses photo URLs with a port (`FileOssUploadUtils.java:537`), so 相册 said 「照片地址无效」. Then it said 「照片缺少上传归属信息」 because the stand-in dropped `x-oss-meta-*`. Both were fixed in the stand-in (443 on 127.0.0.2, metadata kept). These two messages are HARNESS.
- `picker-view` has no gesture in the rig: the 猜数字 wheel was set by firing its own `bindchange` (`drive.mjs` `pickview`), as j2 did for picker wheels.
- `<navigator url>` does nothing in the rig: 「新增参与人信息」 was opened with the same url by `navigateTo`.
- Mic level raised through the rig's own `MPWEB.mic.level` for 喊一嗓子. No compass or accelerometer reading in the rig; WeRun is denied.
- The browser can't load OSS or `/profile` images, so photo and image tiles are blank (HARNESS).

## 1. Steps

| # | Step | Result | Shots | Read-back |
|---|---|---|---|---|
| A1 | Theme 990014 → 查看详情与报名 → 随时开玩 sheet | PASS. 「6 章 · 约 3小时30分钟」 (F7 gone). The sheet keeps 「不用选场次」 and the safety line. | 01–03 | — |
| A2 | 去结算 → 补全姓名和手机号 → 参与人信息 (empty) → 新增 → 姓名 林夏, 手机号 13800000004 → 保存 → pick → back to checkout | PASS with N12. Checkout shows 林夏 · 139****0004. | 04–07, 10-participant-* , 10-checkout-filled | `ums_member_address` 11: 林夏 / 13800000004 |
| A3 | 立即支付 without consent → 「请先勾选同意」; tick → pay | PASS | 10-pay-noconsent, 10-consent, 10-paid | `/api/registration/create` realName 林夏, phone **13900000004**; reg 74 ¥9.90, status 2 |
| B1 | Play intro → 开始游戏 → 序章 → 出发 | PASS. Intro and 序章 have no invented taglines now. The first tap on 出发 during the text reveal only skips the reveal (N18). | 11, 12, 16, 19 | `journey/opening/complete` 200 |
| B2 | 星尘: all 21 stations, then 尾声 and finish | PASS, with findings per game in section 2 | 20–45 | `player_node_progress` 71–91 status 1; `topic_route_session` 1 COMPLETED; badge `TOPIC_FINISH_990014` |
| B3 | Reopen the finished theme | **PASS (F5 gone)**: 尾声 → 「这一生的结局」 page (this theme has a 角色建档) → 继续 | 45-t1-reopen-1..3 | `journey/ending` 200 |
| B4 | F6: answer, leave, reopen 51 m away | **FAIL (F6 still)**. Back at the station it completes. | 31 | arrive 500 「还没到这一站附近」 |
| C1 | 纸本 + 结果反馈: wrong answers then retries on 选项问答, 排序, 猜数字; 关闭 on the fail screen | PASS on the server, with N2, N7 | 50–55 | `RETRY` then pass; arrive 200 |
| C2 | 纸本: the rest to the finish card | PASS (相册 after the stand-in fix) | 56–59 | nodes 92–100 |
| D1 | 夜城 sensors: 罗盘 and 弹球 with no reading → two failed reads → 跳过这一站 | **PASS** | 60, 61 | `SKIP_SENSOR_STATION`; nodes 109, 110 `SKIPPED`, score 0 |
| D2 | 安静挑战 (rig mic 0.03), 喊一嗓子 (mic 0.9) | PASS | 62, 63 | `SUBMIT_QUIET_HOLD` 15000 ms, `SUBMIT_SHOUT` 5099 ms |
| D3 | 计步, WeRun denied twice → 「需要授权微信运动才能计步」 → 跳过这一站 | **PASS** | 64 | `REPORT_SENSOR_FAILURE denied:true` ×2; node 113 `SKIPPED` |
| D4 | 预测 (daytime, closes 20:00): pick A on 夜城, B on 星尘 | Route goes on. **FAIL (N1)** on the stamp. | 26, 65 | `SUBMIT_PREDICT` B / A |
| E1 | Led: intro header 「到场 0/1 人 · 第一章 迟到的电报 / 队长带队中」 → 同场路线 sheet → chapter 1 stations 1–2 | PASS | 70–73 | `play/answer` 「答对了！」 ×2 |
| E2 | Chapter 2 gate | **PASS (F4 gone)**: 「等待放行」 + 返回地图 | 73-led-s2-c, 74 | `game_session` view: current chapter 111 |
| E3 | 返回地图; walk to the locked station 3 | PASS: map opens; at station 3 still 「等待放行」. N13 on the map's 「下一站」. | 74-led-map, 75 | — |
| E4 | Lead `UNLOCK_CHAPTER 112` → reopen → stations 3, 4 → finish | PASS. Release seen on reopen; live release with the wait screen left open NOT VERIFIED. | 76, 77 | command APPLIED; answers 200 |
| F1 | 末世 with 状态 + d20: station 1 选项问答 with 反馈: start, wrong, recover, pass, settle | Route goes on only through the 背包 (N3) | 80–83 | `QUEST_CANCEL`, `QUEST_START`, `QUEST_RESOLVE` 200; `player_node_progress` 191 stays status 0 |
| F2 | 末世 station 2 (输入挑战) | NOT VERIFIED: I could not drive the 背包's second task row in the time left (84) | 84 | arrive 200 |
| G1 | d20 theme 990050 station 1, D20 | **FAIL / NOT VERIFIED cause (N4)**: black screen | 85, 86 | `journeyCheck.show: true`, `allowedActions: [ROLL_CHECK]`, paragraphs never `in` |
| H1 | 我的 → 成就 → 成长中心 | PASS with N17. Lv.93 in both. | 90, 92, 93 | — |
| H2 | 我的订单 → 查看详情 (pass for 990050) | **FAIL (N6, F9)** | 95, 96 | reg 80 |
| H3 | 票夹 | F13 still | 97 | — |
| L1 | Looks on the play page: 星尘 default; 纸本 + 楷体 + 墨晕; 夜城 + 粗斜 + 解码; 末世 + 雨 + 宋体 | PASS: `lookClass` `look--paper look-paper--kraft … story-font--kai` read back; 夜城 italic bold story text (60-t4s1-a); 末世 rain (81-t3-bag-0). 纸本 renders near-black with a warm tint; no kraft texture visible in the rig (56-t2s7-a). | as listed | `cms_topic.look_json` |

## 2. Game types

| Game | Where | Result | Notes |
|---|---|---|---|
| 选项问答 | 990014 s1; 990015 s1 (反馈); 990049 s1 (状态) | PASS ×3 | F16 grey block and extra 提交; N3 in the 状态 theme; N7 colours with 反馈 |
| 文字问答 | 990014 s2; 990015 s2 | PASS | 「不对。把它清掉，再试一次。」; F17 |
| 排序 | 990014 s3; 990015 s3 (wrong → fail → 关闭 → reopen → retry → pass) | PASS | Instruction line stays (F14). 关闭 never strands: reopen shows the fail screen with 重新开始. |
| 连线 | 990014 s4 | PASS | — |
| 分类 | 990014 s5 | PASS | 「都放好了…」, two stacked buttons |
| 猜数字 | 990014 s6; 990015 s6 (反馈) | PASS, **N2** | Without 反馈 a far guess completes; with 反馈 it fails and needs an exact-enough guess. 「米」 far left. |
| 预测 | 990014 s7; 990017 s6 | PASS (open round), **N1** | 「今天 20:00 截止。」 |
| 翻卡 | 990014 s8; 990015 s4 | PASS | Loud blue panel; 换一张/就这张 over the card; card face shows the label, not the content |
| 抛硬币 | 990014 s9; 990015 s5 | PASS | 「正面还是反面?」; first 继续故事 tap after the flip ignored twice; reopening at the station completes it |
| 掷骰子 | 990014 s10 | PASS | — |
| D20 加值 | 990014 s11 | PASS | 「继续」 not 「继续故事」 |
| 角色建档 | 990014 s12; 990015 s9 | PASS | Title twice; 「各项指标在范围内」 |
| 留言 | 990014 s13 | PASS | — |
| 今日城市签 | 990014 s14 | PASS, N10 | — |
| 闭眼盲品 | 990014 s15 | PASS, N14 | No verdict (no answer configured) |
| 输入挑战 | 990014 s16; 990015 s7 | PASS, N8 | — |
| 精准停表 | 990014 s17 | PASS (3 misses still go on), N9 | Six helper lines |
| 变色就点 | 990014 s18 | PASS | 292 ms best of 3 |
| 倒计时 | 990014 s19 | PASS | — |
| 猜图 | 990014 s20 | PASS | Images blank (HARNESS); no right/wrong |
| 拍照打卡 | 990014 s21 | PASS | Real upload through the stand-in; 「答对了」 for a photo |
| 相册 | 990015 s8 | PASS after the stand-in fix, N11 | — |
| 罗盘 | 990017 s1 | Skip path PASS | Only a phone proves a real reading |
| 弹球 | 990017 s2 | Skip path PASS | Stale 「动作读取已中断…」 line after skip |
| 安静挑战 | 990017 s3 | PASS (stub) | Copy N16 |
| 喊一嗓子 | 990017 s4 | PASS (stub) | Copy N16 |
| 计步 | 990017 s5 | Denied + skip PASS | 「少排 0.00 kg」 |
| D20 (三档 / 检定) | 990050 s1 | **FAIL in rig (N4)** | — |
| 拍照审核, 拍物成卡, 找东西 | — | NOT VERIFIED this pass | Refused in 状态 themes (N5); not placed in a plain theme in time. h5 walked them. |
| 九宫格 | — | NOT VERIFIED | Theme-level setting (i1); not authored |

**Templates 248 (罗盘与检定) and 267 (相册, 随机卡) as used by 990068 / 990069.** No fixture copy could be made: their live configs are not in the repository, and I have no production access. I built the closest shapes the editor's functions allow:
- One template with both 罗盘 and D20, or both 相册 and 翻卡: the editor's own `validate` accepts it without `outcomeRules`, but the picker can't make it (choosing a game replaces the main game). With `outcomeRules` the editor refuses it (「结果规则须且仅须一个主玩法」).
- In a d20 检定 theme: 罗盘 + D20 and 相册 + 翻卡 combined, and 罗盘 or 翻卡 alone, are all refused at publish with 「…工具 genericSettlement：尚无通用结算完成证明」. So a theme shaped like 990068/990069 with 检定 on can't be re-published from the editor today, if those templates are what their names say. Worth a read-only look at the live rows.
- In a plain theme, 相册 and 翻卡 each play (990015 s4, s8). The D20 in a d20 theme is N4.

## 3. Findings

### N4. HIGH (cause unverified; BLOCKER if real): D20 检定 station is a black screen
- Repro: 990050 (journeyRules `checkEnabled`, written by the editor's `theme-d20.js`), station 1 D20 on GPS. Arrive → 背包 → 任务 → 打开原任务.
- Screen: fully black. No question, no roll button, no back button, no 背包 (86-t5-d20-a).
- Page data: `journeyCheck.show = true`, `allowedActions = ["ROLL_CHECK"]`, `disabledReason = ""`. The inline paragraphs (`kind: journeyCheck`) never become `in`.
- h5 saw the same and rolled through the API. Other inline games reveal fine in this rig, so I can't call it HARNESS. One phone run settles it. If a phone shows the same, a 检定 theme strands every player at its first D20.

### N1. HIGH: 预测 marks the wrong card
- Repro: 990014 s7, centre 「B 不会」, tap it, 就选这个. Server gets `optionKey: "B"`. The carousel then moves on one card and the red 「选」 stamp sits on 「A 会」 (26-t1s7-e). The reverse on 990017 s6: A sent, stamp on 「B 不会」 (65-t4s6-d).
- The stamp also covers the end of the question 「明天会下雨吗？」.
- The player is told they chose the other answer, the night before the reveal.

### N2. HIGH: 猜数字 with 结果反馈 can't be retried in place
- Repro: 990015 s6 (猜数字 with 结果反馈). Far guess → 「失败」 → 继续 → 「重新开始本次玩法」. The wheel comes back; 「就这个数」 sends nothing (55-t2s6-g, -h, network shows only `RETRY`). Leaving the station and reopening it makes the button work again.
- Cause: `pages/play/components/playkit-estimate/index.js:102`: `locked: !!result || this.data.locked` never clears when the server resets the round.
- Also: without 结果反馈 a far guess completes the station with a low score (25-t1s6-d). With 结果反馈 the same guess fails, and the fail screen hides the real value that the plain version shows.

### N3. HIGH: theme with 状态: failure and settlement flow
On 990049 s1 (选项问答 with author 结果反馈, GPS):
- 开始任务 opens the 背包. The new task already reads 「待审核 / 凭证未通过，请打开原任务确认」 before anything was sent (80-t3s1-e). Same after a restart (81-t3-bag-2).
- Wrong answer → 「失败」. Reopening shows the question again with 提交. Tapping the right answer sends `SUBMIT_QA` (200) and nothing changes on screen (80-t3s1-j). This is i1's "dead 提交", still there.
- The way on: 背包 → 任务 tab → 取消 → 开始任务 → 打开原任务 → answer. Then 背包 → 任务 → 结算任务 → 「奖励预览」 (「本次动作的全部奖励；可明确放弃本人或本队的部分新奖励。」, 「领取后的装载 0 / 0 格」, 「本次没有物品奖励」) → 确认装载 (82-t3s1-settle). The author's pass reply shows only after that.
- After settling, the used-up task shows 「次数已用完 · 剩 0 次」 next to a 开始任务 button (83).
- 「背包装载 0 / 0 格」 is a fake zero on a theme with no bag.
- `player_node_progress` 191 stays status 0 after settlement (i1 saw this too).
- Nobody finds the 取消 path unaided. The player isn't stranded, but on the screen they see, they are.

### N5. HIGH (author): 状态 / 检定 themes refuse most of the editor's games
Probe: one-station themes through `/api/topic/v2/create` (`tools/probe_generic.py`) and the five full themes (`tools/notes.md`):
- With 状态 or d20 检定, these are refused whatever the 参与方式: 拍照审核, 拍物成卡, 找东西, 翻卡, 罗盘, 相册 + 翻卡. The message is 「节点 139（05 拍照审核） 玩法 photoCheck 工具 genericSettlement：尚无通用结算完成证明」: internal names, English keys.
- On the editor's default 参与方式 (无需验证, `validationMethod 0`), every station with `outcomeRules` is refused: 输入挑战 always has them, and so do D20, 相册 and any 结果反馈. The message is 「个人任务须使用真实商户到达或已验证扫码/GPS节点，工作人员点位请建队伍场次」, which names no station. With d20 检定 on, a plain 选项问答 on GPS was refused with the same line. Source: `TopicRoutePublicationCompiler.java:657`, `:679–687`.
- The editor's picker offers all of these games in such themes. An author who turns on 状态 finds out only at publish, one station at a time.

### N6. HIGH: an unfinished solo pass reads 「已完成」
- Repro: pass for 990050 bought 12:25. The D20 station was never done (N4).
- 我的订单: 「已完成」. Detail: 「已完成 · 首次扫码成功，订单已完成」, and the progress shows 「首次扫码成功 2026-11-05 12:40」 (95, 96). Nothing was scanned: 12:40 is when play started.
- The F9 leftovers are still on the same page:
  - list dates 「11.04 00:00 - 12.05 00:00」 (date-only values shown as 00:00; the pass was bought 11.05);
  - 「权益到期 2026-12-06 00:00」, while the sheet said 「可玩至 12 月 5 日」;
  - 「已开始,不可退款」 with an ASCII comma.

### N7. LOW: 结果反馈 screens use loud full-screen colours
- Fail: saturated red full screen 「失败 / 没过，再看看四周。 / 继续」, teal status bar (50-t2s1-d). Pass: saturated green ✓ with magenta status bar (50-t2s1-h).
- The owner's rules are player side dark and no loud colours. The theme's 纸本 look isn't applied.
- After 「失败 · 继续」 a second screen repeats it: 「本次玩法未通过 / 得分 0 / 已完成 0 项 / <option feedback> / 原结果已保留，重新进入不会重置进度。」 (50-t2s1-f). The last line is helper text. One screen with the author's reply and 「再来一次」 would do.

### N8. LOW: 输入挑战 wrong answer says nothing
- 990014 s16 and 990015 s7: a wrong submit returns straight to 「开始」 (37-t1s16-d). With 结果反馈 the author's fail reply 「没过，再看看四周。」 isn't shown either; other games show it.

### N9. LOW: 精准停表 numbers
- A 9.696 s stop shows 「9.70 · 早 0.30 秒」 and counts as a miss at 「容差 ±0.300 秒」 (38-t1s17-d). Round the shown error the way it's judged.
- After a reopen the tries left are off by one: after the third miss 「还有 1 次机会」 and 再来一局 show above 继续故事 (38-t1s17-e). The route does go on.

### N10. LOW: 今日城市签 after claiming
- 「你留给下一个人的」 card is empty: the player's own 「江风很大，带件外套」 isn't shown. The card covers the line just revealed 「今日宜出门」 (35-t1s14-f).
- Still from round 1: 「NO. 0000」 fake zero, light panel on the dark side, two back buttons, 出发 + 继续故事 stacked, ASCII 「撕不下来?点这里」.

### N11. LOW: 相册
- The main action 「拍照或选择照片完成相册」 is styled as plain text under a large blank panel; only 「收起相册」 looks like a button (57-t2s8-c).
- After submitting, 继续故事 is hidden behind the album panel until 收起相册 (57-t2s8-d).

### N12. LOW: 参与人信息 phone isn't the phone used
- 参与人信息 saved 林夏 / 13800000004 and lists it so. Checkout shows 林夏 · 139****0004, and `/api/registration/create` sends 13900000004 (the account's WeChat phone).
- The phone rule is WeChat-only by owner decision, so the form shouldn't offer a phone field that is ignored, or should say which phone is used without a caption (for example show the bound phone read-only).

### N13. LOW: led session map and title
- 返回地图 on the wait screen shows 「下一站 · 192m 乍浦路桥 导航过去」, the first station of the locked chapter (74-led-map). Walking there gets the wait screen again.
- After the finish the header says 「第一章 迟到的电报」 above chapter 2's text (77-led-end).

### N14. LOW: 闭眼盲品
- 「店员会把小样摆在你面前」 on a theme with no shop. 「尝好了，松手睁眼」 stays on after answering (36).

### N15. LOW: 抛硬币
- 「正面还是反面?」 has an ASCII question mark (29). The first 继续故事 tap right after the flip was ignored twice in the rig. Reopening at the station completes it, so it doesn't strand.

### N16. LOW: captions and technical words (owner's UI rule)
- 安静挑战: 「仅比较本机相对 PCM 振幅与校准阈值，不是分贝读数」 and 「已校准 · 本机阈值 21%（相对满幅振幅，不是分贝）」. 喊一嗓子: 「本机相对 PCM 峰值持续过线 5 秒」 (62, 63). English 「PCM」 and lab language; let the meter carry it.
- 「本机成绩：只记探索值与徽章，不发券或积分」 under 罗盘, 安静, 喊一嗓子 and 精准停表: say it once on the result, if at all.
- 计步: 「少排 0.00 kg」 (64). After a 弹球 skip, 「动作读取已中断，重新开始会重新校准。」 stays above 「这一站已跳过」 (61).
- The rest of F14 as listed in the summary.

### N17. LOW: 「总积分排名 0」 at 0 积分
- 我的 → 成就 shows 「总积分排名 0」 with 「G 0」 (92). No rank should show at 0 points (p1 F-4).

### N18. LOW: 出发 during the reveal
- On the 序章 card the first tap on 出发 only finishes the text reveal; nothing visible says so (16 vs 19). A second tap works.

### HARNESS (not product)
- `<navigator url>`, `picker-view` gestures, compass and accelerometer readings, WeRun, OSS and `/profile` images in the browser, the OSS stand-in's port and metadata (「照片地址无效」, 「照片缺少上传归属信息」) were all rig gaps, handled as described in Setup.

## 4. What I did not cover

- The closed 预测 round (night) and the shop's reveal next day. g4 walked the closed round.
- 拍照审核, 拍物成卡, 找东西 this pass (check down → retake → go on). h5 walked them.
- The rest of the 状态 theme (输入挑战, D20, 相册 at stations 2–4 of 990049), and its ending.
- The 「等待放行」 screen releasing live without a reopen.
- Free-exploration order (F12), 积分明细, invoices, medal screen during the finish.
- The editor UI itself (F18): look sheet, font and text-effect previews. Fonts on the play page: 宋体 / 楷体 / 黑体 can't differ in this rig (one CJK font); 手写 from OSS not reachable.
- 13 text effects × 4 looks were not each walked. I ran 墨晕 (纸本), 解码 (夜城), and the defaults.
- 九宫格 (theme-level), and the live 990068 / 990069 and templates 248 / 267 (no production access, no copy in the repo).
- Phone-only: real compass, accelerometer, mic levels, WeRun, camera, vision check, sound, haptics, native map, safe areas, share and save-to-album.

STATUS: PARTIAL - D20 检定 ends on a black screen in the rig (cause needs a phone); 状态 theme played through station 1 only; closed 预测, photo-check fallback and live release not re-walked; templates 248/267 not copyable
