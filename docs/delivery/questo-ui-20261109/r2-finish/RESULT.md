# r2-finish 结果：章节结束、本局结束、结算流程、排行榜、队伍名次、分享

- 分支 `claude/r2-finish-20261110`，基线 `origin/staging/questo-review-20261110`（等 qx-s1 写出 `STATUS: COMPLETE` 后合入了最新 staging，合入无冲突；qx-s1 没有改小程序代码，所以之前拍的 before 图仍然有效）。
- 只改小程序，没有改后端、支付、退款、结算状态，没有新迁移。
- 截图都在 `shots/`，390 宽、JPEG q70，全部来自走查 rig 的真实页面和真实接口（rig 搭法见文末）。每张图我都看过。

## 逐项

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机未验 |
|---|---|---|---|---|---|
| 章节结束（参考 `refs2/chapter-end-dissolve-frames.jpg` + Questo B1） | fixed | 原来上一章完成时是星点拼「01」方块数字。现在改成一张完成卡，结构照 Questo B1：金色勾 →「第 N 章完成」→ 章节名 → 用时 / 本章得分 / 坐标 →「下一站：南京东路站」。卡停 5.2 秒（或点「下一站」）后整张卡从左往右化成粒子飘散，接着升起下一章的三棱锥（原有的下一章过场不变）。用时是本局跑表，读不到整行不出；本章得分只在本章有计分题时出；下一站取本章第一个没完成的节点。减弱动态时卡照样停留，只去掉溶解动画。 | `pages/play/components/chapter-flip/index.{js,wxml,wxss}`、`engine.js`、`pages/play/utils/chapter-flip-data.js`、`pages/play/index.js`（只改 `showChapterGate` 传用时、兜底计时 7.8→10.8 秒） | rig 两章主题（`rig/two_chapter_theme.py`）真实跑完第 1 章：`chapter-end-before` / `chapter-end-after`（完成卡）/ `chapter-end-dissolve-after`（溶解中）。单测 `chapter-flip.test.js` 新增一例（行、下一站、不拿 0 凑） | 真机粒子帧率；headless 里粒子飘得偏慢 |
| s0-b1-run-ending | fixed | 「本局完成」结局页加庆祝：白色印章弹入、两圈光环外扩、彩旗落下（共用 `cy-celebrate`，这里放慢到 2.2 秒）。游玩页有单色契约（`play-monochrome-contract`），所以这里用黑白，不用金色；章节完成卡在独立组件里，沿用原型的金色。内容整屏居中。减弱动态时印章直接落位，不出光环和彩旗。 | `pages/play/index.{wxml,wxss}`（结局 `pv-ending` 段） | rig 真实通关：`s0-b1-run-ending-before` / `-after`（彩旗下落中截到） | 真机动效 |
| s0-b8-u08-01 | fixed | 完成页收拾：有路线卡时，日期 / 主题名 / 已通关提到最上面，卡在下面；卡上已经写了距离、用时、站数，下面不再重复大数字「2」和用时、距离两格；底部按钮放在实底托盘上（原来的黑色光晕盖不住，大数字从按钮下面露出来）；整局走完时多一颗「查看结算」。 | `pages/play/index.{js,wxml,wxss}`（完成面板段） | `s0-b8-u08-01-before` / `-after` / `-scrolled-after`，同一局真实通关 | — |
| 结算流程（总览 → 排行榜 → 评价 → 分享） | fixed | 新页 `subpackageJourney/pages/settle`，从完成页「查看结算」或战报页「结算」进入。顺序照 Questo 裁成 4 屏：<br>1. 总览：结果、主题名、本关名次、分数 / 节点 / 用时（都读战报；用时未知整格不出；通关时撒彩旗）。<br>2. 排行榜：和战报共用新榜单组件。<br>3. 评价：五星 + 一句话，可跳过；走既有 `/api/comment/add`（主题 owner_type=1，场次 2）；错误红字写在字段下面。<br>4. 分享卡：帖文 4:5 / 故事 9:16，分享（点了才签推广凭据，失败回落公开主题页）、保存图片、分享视频。<br>视频：基础库有 `wx.createMediaRecorder` + `wx.shareVideoMessage` 时，把分享卡入场动画录 3 秒再走微信视频分享；没有这两个接口就不出按钮。返回键在流程里是退一步。 | `subpackageJourney/pages/settle/*`（新）、`subpackageJourney/utils/share-card.js`（新）、`subpackageJourney/utils/journey-api.js`、`app.json`（分包加一页） | rig：`settle-overview-after`、`settle-rank-after`、`settle-rate-error-after`（没写字就点提交）、`settle-share-post-after`、`settle-share-story-after`。另外真实提交过一次评价（4 星 + 一句话），接口返回成功后进入分享屏。单测 `r2-finish-settlement.test.js` | 视频录制与微信视频分享（rig 不能录）；保存到相册；微信分享卡 |
| b3-23-4-3 | fixed | 榜单口径收少：本关只给「队内」（服务端给了队伍才出）/「全体」/「队伍」（有队伍之间榜才出），城市、国家、世界、俱乐部收进一个「更多」下拉。选了「更多」里的榜后，「全体」留在左边可以点回来。好友榜见待你决定 1。 | `subpackageJourney/components/rank-board/*`（新）、`subpackageJourney/utils/journey-view.js` | `b3-23-4-3-before` / `-after`（更多菜单）/ `-city-after` | — |
| b3-p9 | fixed | 原来两排横滑药丸并排、渐隐遮罩盖住「世界」，看起来像 bug。现在口径是一行分段（最多 3 个，不滑），累计 / 本周是名次下面一个小分段，不再横滑。 | 同上 + `subpackageJourney/pages/report/*` | `b3-p9-before` / `b3-p9-after` | — |
| P9 队伍之间榜（按你的默认接上） | fixed | 接 `GET /api/orient/team/ranking` 作为「队伍」页签：服务端有队伍才出。名次用月桂大数字（参考 `team-rank-laurel-frames.jpg`），切口径 / 周期时数字滚动；「前 N%」只在服务端给了上榜总数、且不少于 5 人时写（1 人上榜写「前 100%」没有意义）；队伍榜读满服务端上限 200 队时不算百分比。下面两张深色玻璃卡：全队完成、在岗队员。单人榜的「我的名次」也用同一个大数字。 | `rank-board/*`、`journey-view.js`（`boardTabs` / `topPercent` / `rankHero` / `teamBoardView`）、`images/laurel.svg`（自绘） | 单测覆盖（队伍名次、百分比、截断、不在队里不出大数字）。rig 截图里能看到「全体」的月桂名次 | **rig 没有组队主题，「队内」「队伍」两个页签没截到图** |
| b3-p7-1-2 | fixed | 「这一局不存在或无权查看」：在标题下面的剩余高度里居中，不再贴着上面；图标换成路线图标。 | `subpackageJourney/pages/report/*` | `b3-p7-1-2-before` / `-after` | — |
| b3-p7-1-3 | fixed | 「登录后查看战报」：同样居中，图标换成人像，「登录」按钮用按钮圆角 token。 | 同上 | `b3-p7-1-3-before` / `-after` | — |
| b3-p7 | fixed | 战报页：三格数据收进一张有分隔线的分组卡；底部「结算」+「分享」两颗；榜单面板换成新榜单组件。 | 同上 | `b3-p7-before` / `-after` | — |
| b3-p8 | fixed | 本局回忆改成时间线：换天写一次日期，每站一个绿点和竖线，时刻在右，照片跟在那一站下面（不再单独一排缩略图）。同行人数照旧只在大于 1 时写。 | `subpackageJourney/pages/report/*`、`journey-view.js`（`memoryView` 加 `day/time/showDay`） | `b3-p8-before` / `-after` | 带照片的回忆（rig 这一局没拍照） |

### 全局规则落在本 lane 的地方

1. 弹层看得清：章节完成卡用比底色抬高一档的卡面 + 卡沿 + 投影，次要字对比度 ≥ 4.5:1；榜单、回忆面板沿用 `cy-sheet` 和 token，没有写死灰色（r2-visual 改 token 后会一起生效）。
2. 关闭在右上：面板沿用 `cy-sheet` 右上关闭；结算页只有一个返回。
3. 居中：战报状态屏、结局页、结算总览和评价屏都居中。
4. 不给玩家看系统词：新页面没有 id、没有提示语。
5. 底部按钮固定在底部：完成页、结算页底部按钮都在实底托盘上；圆角用 `--cy-comp-btn-radius`（r2-visual 统一后跟着变）。
6. 动效：章节完成卡溶解（canvas 粒子）、结局印章 + 光环 + 彩旗、月桂名次数字滚动，都只用 transform / opacity / canvas，全部尊重减弱动态。
7. Questo 只借结构（B1 章节完成、B4 总览、B5 榜单、B8 评分、B11 分享卡），样式、文案、图都是我们自己的。
8. 动图素材：本 lane 的动效都是代码画的（CSS / canvas），没有引入外部 Lottie / GIF，所以没有素材许可要登记；月桂是自己画的 SVG（1.6 KB，放在分包）。

## 门禁

- 单测：`node --test` 跑了 `chapter-flip*`、`play-chapter-*`、`r2-finish-settlement`、`qx-b3-player-journey`、`finish-page-redesign`、`play-finish-panel-not-nested-in-ending`、`play-ui-contract`、`r11-g1-finish-sheet-label`、`k7-map`、`ui-review-0831-marks-contract`、`privacy-gate-coverage-contract`，全部通过。改了 4 条钉住旧写法的断言（章节眉标「第 1 章完成」、兜底计时 10.8 秒、翻转门「轻点跳过」在完成卡在场时不出、完成页用时格多了 `fst--dup` 类、分包页面列表多了 settle、战报页禁写口的正则不再把页面路由 `pages/settle` 当写口），每处都写了注释。
- DS 硬编码门禁：把本分支改动放进 staging 的临时工作树暂存后跑 `scripts/ds-hardcode-gate.sh`，无报错（分享图画面色、月桂大名次字号这类刻意值行尾标了 `ds-ok`）。
- 动作台账与 UI 门禁：先 `build-route-matrix.js --json` 重建路由矩阵（新增结算页），再 `build-action-ledger.js --write` 重建台账（5893 条），`node tests/lint/ui-integrity.js` 输出 `UI-GATE-0:PASS`，没有新增任何基线条目（中途门禁报过 2 个零消费字段和 1 处基线行号漂移，已改代码收掉：把新增的辅助方法挪到基线行之后）。
- 游玩页单色契约（`play-monochrome-contract`）：第一版结局印章用了金色被判红，已改成黑白；同批 play/finish/journey/chapter 相关单测 2430 例全部通过（含这次修正后重跑的 5 个）。
- 主包体积：`scripts/mainpkg-size-report.js` 本分支与基线都是 3,233,159 字节 ≈ 1998.6 KiB（< 2048）。新增文件都在 `subpackageJourney` 分包；`pages/play` 的改动不计入主包估算。
- 没有跑 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`（brief 禁止）。

## rig

- 走查 rig：`claude/cloud-walk-20261101-rig` + staging 的 `rig-up-sha-param.diff`、`rig-app-oss-seam.diff`（占位 OSS 值，不是凭据）+ qx-c1 的 media seam（已包含在 oss diff 里）+ club 分支的 `harness-patches.diff`；docker 镜像源换成 mirror.gcr.io。
- 后端 jar 用 staging 构建（本 lane 不改后端）。before 图用 staging 的小程序包，after 图把本分支的 `chengyinhub-xcx` 打进同一个 rig。
- 夹具：
  - staging 的 `free_orient_theme.py` 建一个单章两站主题（990014），玩家 9004 真实走完（到站、答题「3」），用于本局完成、完成页、结算、战报、榜单。
  - `rig/two_chapter_theme.py`（由上面那个改成两章各一站）建 990015，玩家 9004 走完第 1 章，用于章节完成卡。日志在 `rig/fixture-two-chapter.txt`。
  - 两个夹具里唯一的 SQL 仍是 staging 原来那一处（零价自玩通行证开关）；没有往页面里注入假进度。

## 待你决定

1. **好友榜**：服务端 `/api/rank/list` 没有好友作用域（只有 world / country / city / quest / club）。库里有关注关系 `UmsMemberFollow`，可以按「互相关注 = 好友」加一个 `scope=friends`。**默认做法：不出「好友」页签**（只放服务端能答的口径），后端加了作用域后在 `boardTabs` 里加一行即可。
2. **只打星不写字**：既有评论写口 `/api/comment/add` 要求必须有文字。**默认做法：选星后要写一句才能提交，不想写就「跳过」**。如果要允许只打星，需要后端放开 contents 必填（或新开评分写口）。
3. **视频分享**：小程序没有现成的「分享视频」路径，视频悬赏是投到外部平台的稿件，和这里无关。**默认做法：用 `wx.createMediaRecorder` 把分享卡动画录 3 秒，再调 `wx.shareVideoMessage` 发到聊天；基础库不支持就不出按钮**。rig 录不了视频，需要真机验一次；如果你想要的是「把旅途照片拼成视频」，那需要照片下载和更长的录制，另起一项。
4. **章节完成卡停多久**：Questo 是等玩家点「下一步」；我们 9-24 定过「章节门不要每章都要点」。**默认做法：停 5.2 秒自己溶解，点「下一站」可以提前**。
5. **结算里的奖励屏**：Questo 第一屏是「+2000 XP」。战报接口没有本局奖励事实，**默认做法：不放奖励屏**，背包件数仍在战报页。
6. **战报「用时」**：rig 这一局的战报 `elapsedSeconds` 为空，所以战报和结算显示「未知」/ 不出用时格（完成页用的是本机跑表 02:04）。是不是后端该在完局时写这个字段，需要后端确认。

## Cross-lane

- `pages/play/index.{js,wxml,wxss}` 是 r2-play 的地盘：我只动了结局 `pv-ending` 段、完成面板 `finsheet` 段、`showChapterGate` 的两处（传用时、兜底计时）和新增的 `openSettlement` / `_gateElapsed`，新样式都追加在 `index.wxss` 末尾，合并时冲突应该很少。
- `pages/play/components/chapter-flip/*` 只改了「上一章完成」那一段和溶解；三棱锥过场、通关过场没动。
- 共享文件：`app.json` 只在 `subpackageJourney` 里加了 `pages/settle/index`。
- r2-visual 改玩家弹层 token / 底部按钮圆角 token 后，本 lane 的面板和按钮会跟着变，不需要再改。

STATUS: PARTIAL - 所有条目已改完并在 rig 截图；只差：「队内」「队伍」两个榜单页签 rig 没有组队主题、未截到图（仅单测）；视频分享与保存相册需要真机验证
