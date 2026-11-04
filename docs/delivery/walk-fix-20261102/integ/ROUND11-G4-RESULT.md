# Round 11, lane g4-play-walk: result

Branch `integ/launch-test-20261103`. My commits: `746e5efd` (item 4, F19 font), `a160ed49` (item 5, editor), `c02d461a` (walk fixes: 预测 after skip, F15 wording, j4 F8). This file is pushed after them.

**Every screenshot comes from the rig, not a phone.** The rig is `claude/cloud-walk-20261101-rig` with j4's `tools/rig-changes.diff` and j3's pointer-capture fix. It runs the real jar built at `31645fb8`, MySQL 8 and Redis 7, and the real mini-program in headless Chrome at 390×844, 2x. My front-end changes were applied to the rig's candidate checkout and the browser bundle was rebuilt. No Java changed in my commits, so the jar is the same code. The app was restarted with `APP_MEDIA_PUBLIC_BASE_URL` (m2's seam) for the roam places. Shots are under `integ/fix-shots/g4-*.jpg`. I looked at each one I quote.

## Rig data (labelled)

| What | How |
|---|---|
| Themes 990018 (选项问答 + 预测 closing at 01:00), 990020 (D20 三档 + 相册, 定位到达), 990021 (拍照审核, 找东西, 拍物成卡, 罗盘, 弹球, 安静, 喊一嗓子, 计步), 990022 (输入挑战 + 选项问答, both with 结果反馈) | Real `/api/template/draft` and `/api/topic/v2/create`. Game configs from the editor's own `defaultConfig` / `applyToConfig`, plus the picker's `outcomeRules` for check and album (g1's change), as j3's `gencfg.js` |
| Approval | Real admin `/business/audit/review` |
| **FIXTURE** on sale | `cms_topic` lifecycle 3 (j3's `fx_onsale.py`, because of J2-02) |
| **FIXTURE** fee rate | `fee_platform_rate = 0.0300`. The quote refused 「支付费率快照缺失或非法」 without the price lock that a real 确认终价 writes |
| Passes | Real consent, quote, create, then the rig pay seam (real callback). Players 9004, 9009, walk-player-1 |
| Roam places | j4's `poi.sql` (30 places around People's Square) |
| Predict clock | It was about 04:00 Shanghai time, so the station closes at 01:00 to reach the closed state |

## Items

### 1. Walk every g1 fix. Status: partly done; two fixes made; one blocker found.

| g1 fix | Result | Shots | Read-back |
|---|---|---|---|
| Closed 预测, no shop | PASS. 「今天这一轮已经截止」 and one 「不选，继续」. The route goes on. | g4-07, g4-10 | `SKIP_CLOSED_PREDICT` 200; `player_node_progress` node 84: `completion_mode=SKIPPED`, `puzzle_score=0`; no `play_predict_bet` row; `game_runtime_session.state.predict = {"skipped":true}` |
| After 「不选，继续」 | **Was wrong, fixed.** The card went back to 「转到想选的那张，点它」 with a dead 「就选这个」, because the server stops sending `closed` once skipped. Now the card takes `skipped`: no hint, no 就选这个, cards not tappable, 继续故事 only. | before g4-08, after g4-11 | — |
| Hint position (story-flow card) | **Was wrong, fixed.** 「今天这一轮已经截止」 was drawn over the card's lower edge (measured: card bottom 637 px, hint 615–639 px). Inline, the hint now sits between the card and the button. | before g4-07, after g4-10 | — |
| Closed 预测 with a shop | NOT VERIFIED. I set a merchant on the station after publish (fixture). The solo route then dropped that station, so it can't be reached this way. The copy difference is covered by g1's unit tests. | — | — |
| A finished theme reopens on its ending | PASS. 9004 reopened 990018 on the 尾声 and then the ending page. | g4-14, g4-16 | — |
| Distance updates while walking | PASS. 「146m」 → 「59m」 after one move. | g4-24, g4-25 | — |
| 「已结束」 with no giant 0 | PASS. Ending a run with nothing done shows 「已结束」 and no 点亮坐标 cell. | g4-26 | — |
| 确认到点 done state, dark top cards, 米 | PASS. 钢琴博物馆: 确认到点 → 「已到达」 in place. The shop card is dark and reads 「61 米」. | g4-30, g4-32, g4-33 | `/api/roam/poi/discover` 200 |
| D20 三档难度 and 相册 played | **BLOCKED, see below.** | — | `/api/play/nodes?topicId=990020` |
| Photo check down, retake then go on | NOT VERIFIED. The upload goes to `/api/common/uploadOSS`, which answers 503 in this rig. j3's local OSS stand-in is not in either rig diff. The screen shows 「上传失败，请重拍」 and stays retakeable (g4-22). | g4-21, g4-22 | — |
| 「等队长放行」 and 返回地图 under the director runtime | NOT DONE. I did not build j3's led-session fixture in time. | — | — |

**Blocker found (not fixed): a theme with 结果反馈 on any station can't open.**
- **What happens.** Theme 990020 (D20 三档 + 相册) and theme 990022 (输入挑战 + 选项问答, both with 结果反馈) publish and pass review. Their play page then stops at 「数值状态必须为已初始化的整数：sys.hp」 with 重新加载 / 返回活动详情. `/api/play/nodes` answers code 500.
- **Cause, as far as I traced it.**
  - Any station config with `outcomeRules` makes `TopicRoutePublicationCompiler.compileCurrentTemplateRules` compile a generic gameplay runtime for the whole theme: acts, one quest per station, and survival `BLOCK_HP_COST`.
  - `TopicRouteRuntimeServiceImpl.syncTheme` (`genericPlayer`) then reads `sys.hp` through `JourneyInitialState.numericValue`. A theme without 状态 rules never initialises `sys.hp`.
  - The editor's default `journeyRules` is empty, and its picker always adds `outcomeRules` for 输入挑战 (and now for D20 三档 and 相册, g1 F2). So the editor's own themes reach this.
  - The code is on master (`250c6c69`), so it is not new in this round.
- **Same compile, two more author-side refusals:**
  - In such a theme, a station without 结果反馈 whose game has no plain completion proof (预测, 翻卡, 找东西, 拍照审核, 计步 …) refuses publish with 「节点 79（01 站） 玩法 predict 工具 genericSettlement：尚无通用结算完成证明」 (rig: 试B, 试C).
  - A station on 直接参与 refuses with 「个人任务须使用真实商户到达或已验证扫码/GPS节点，工作人员点位请建队伍场次」. 定位到达 publishes.
  - So g1's F2 change moved the D20 dead end from the player to these refusals and the sys.hp stop.
- **Why I didn't fix it.** It is game-runtime logic shared by every generic theme, and the right rule (initialise HP, or don't compile generic runtime for plain 结果反馈) is a design call.
- **Recommended default:** compile the generic runtime only when the theme has 状态 or settlement rules, and treat a station's own 结果反馈 like before. Next best: initialise `sys.hp` from survival defaults when the theme has none.

### 2. j4 F8. Status: fixed (rig).
- 继续 no longer covers the chapter heading. The route card's on-screen preview is sized by height: `--rc-w: min(full width, (100vh − 900rpx) × 3/4)`, centred.
  - At 390×844, the card, 保存图片 / 分享, the date, 第一章 and 「已结束」 are all above the fixed 继续 (g4-28).
  - The exported image is unchanged: the canvas backing is still `routeCard.W × H × 2` and only the CSS size moved.
- A sticky dark band the height of the capsule area is now at the top. The card scrolls under it, not under the status bar (g4-29).
- Files: `pages/play/index.wxml`, `index.wxss`.
- Test: `r11-g4-play-walk.test.js`.
- Unverified: a phone's real safe area, and very short screens.

### 3. j3 F13–F18. Status: F15 wording fixed; the rest not done.
- **F15.** 「这一生的结局」 / 「这一生的档案」 now show only when the ending has a character file (角色建档 identity, the 预制人生 kind). Other themes read 「本局结局」 / 「本局档案」, words the page already uses.
  - The particle burst and `reducedMotion` were not checked.
  - The intro and 序章 card look the same in every 画面风格; not changed.
- **F13, F14, F16, F17, F18: not done.**
  - Seen again in the walk (F14): the tools menu says 「本局读数 · 章节 2」 while the player is in chapter 1, and still carries a sub-line under every row.

### 4. j3 F19 font. Status: done (code); upload is a deploy step.
- `utils/config.js` has a new `storyFontUrl`: `https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/static/fonts/longcang-v21.ttf`.
- Both `story-font.js` copies take the URL from their caller, and nothing is hardwired. Empty or non-https means no load, with the system font and no error. A failed load also falls back silently.
- `docs/发版checklist.md` 「故事手写体」 covers:
  - the file and its sha256;
  - `OFL.txt` alongside;
  - public read and `font/ttf`;
  - the CORS rule (origin `https://servicewechat.com` or `*`, GET/HEAD) with a curl check;
  - the downloadFile domain;
  - a phone check in China.
- The font is not committed: about 5.15 MB, it would enter a package, and the test forbids font files.
- **Licence:** SIL Open Font License 1.1. Copyright 2018 The Long Cang Project Authors; no Reserved Font Name declared. Self-hosting and redistribution are allowed if the licence travels with it, and the font may not be sold on its own.
  - Note for the owner: the 关于 page credits 「设计：ZhongQi」, while Google Fonts metadata names the designer as Chen Xiaomin. Worth checking.
- Main package by preflight: 2.97 MB uncompressed, under the 3.00 MB ratchet.

### 5. Editor: no 结果反馈 on 预测 without a shop. Status: fixed.
- The rule follows the server, which lets only the station's own merchant settle a round: no shop on this station means no 结果反馈. That also covers "the theme has no shop".
- The theme editor now passes `shop` in the context (`gameplay-support-matrix.context`: `node.merchantMemberId > 0`). A merchant writing their own game (`scope=MERCHANT`) counts as a shop.
- With no shop, a 预测 game lists no 结果反馈 rows at all (成功 / 失败 / per option). A template that already has `outcomeRules` still lists them, so the author can switch them off.
- Test: `r11-g4-predict-feedback-shop.test.js`. 3 of its 5 cases fail on the old code.
- Interaction with the blocker above: in a theme where another station has 结果反馈, a 预测 station with no shop can't be published at all. That needs the decision above.

### 6. Game types j3 couldn't play. Status: not done.
- 990021 holds 拍照审核, 找东西, 拍物成卡, 罗盘, 弹球, 安静挑战, 喊一嗓子 and 计步, in that order. The first station needs a photo upload, which this rig can't do, so the rest were not reached.
- 相册 sits behind the sys.hp blocker.
- 九宫格 was not authored.
- **Only a phone proves:** the compass, accelerometer (弹球), microphone (安静, 喊一嗓子), WeRun steps, camera and the vision check.

### 7. j4's unwalked list. Status: not done.

### Not done by rule
- j3 F6 (presence proof) waits for the owner. The walk hit it once: answering station 1, then reopening 51 m away, gave `/api/play/arrive` 「还没到这一站附近」.

## Checks
- Full mini unit run with my first two commits plus the 预测 fix: 16,147 tests, 16,145 pass.
  - One failure was the action ledger, which I then rebuilt. Only lines moved, plus `visibleCondition {{!skipped}}` on 就选这个.
  - The other is `r11-g3-order-truth.test.js`, which is lane g3's and failed before my work too.
- After the rebuild:
  - `action-ledger.test.js` 46/46;
  - `build-action-ledger.js --check` PASS (5,712 controls) on the rebased tree;
  - `ui-integrity.js` `UI-GATE-0:PASS`;
  - my new tests 4/4 and 5/5, and `story-font-loader` 7/7.
- No backend change, no migration, no `@Test` added to a gated suite.
- Secrets: grep of my diff for keys and tokens found nothing.
- **CI.** The runs on `c02d461a` were cancelled by my docs push. The runs on `c3605a27` (contains all my work):
  - Mini followup focused regression: success.
  - Funds MySQL IT: success.
  - Isolated authenticated Redis recovery: failure. It failed the same way on the two commits before mine.
  - GitHub CI: failure, in four jobs, none from this lane's files:
    - Mini program static gates:
      - U6 bare error text in `pages/topic/components/cy/session-picker/index.wxml:26` (`ticketsError`);
      - the orphan-unit gate at `subpackageP3/pages/growthcenter/index/index.wxml:29` (`{{me.rank}}名`);
      - the dead-field gate at `pages/topic/merchantinfo/merchantinfo.js:459` (`hostMerchants`).
      - I re-ran the last two locally and they name only those files.
    - Backend tests: `CityDirectorConsoleMySqlIT` 3 failures (the roster includes member 10). I changed no Java.
    - Migration safety gate: 「无法验证共享 migration 台账执行序: SQL inventory is unsafe」. I added no migration.
    - Mini program static checks: aggregate of the above.
  - So there is no fully green CI on a commit with my work. The reds belong to the lanes that own those files.

STATUS: PARTIAL - blocker found and not fixed (any theme with 结果反馈 stops at sys.hp; needs a design decision); D20/相册, photo-check fallback and led-session wait screen not walked; items 3 (except F15), 6 and 7 not done; CI on c3605a27 red in other lanes' gates (session-picker, growthcenter, merchantinfo, CityDirectorConsoleMySqlIT, migration ledger), none from this lane
