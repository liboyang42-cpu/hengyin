# l1-play 结果：城市定向游玩页、剧情、结局与游戏手感

分支 `claude/walk-fix-1102-l1-play`（从 `origin/master` 014fc0d8 切出），草稿 PR #713。截图在 `shots/`，rig 记录在 `RIG-NOTES.md`。

第二轮（ROUND2.md，2026-11-02）：P-23 按业主批准做完，P-26 按业主确认不改，P-29 在 rig 里走过换章后做完。第二轮截图 P-23-* / P-29-* 的 before 与 after 都是本轮在 rig 里重拍的同一页同一状态（before 用本分支第二轮之前的提交 f2f04d56 打的包，after 用本轮提交），不是真机。

截图说明（第一轮）：`*-after*.jpg` 来自走查 rig（浏览器里跑真实小程序代码 + 真后端，候选为本分支），不是真机。`*-before*.jpg` 取自 11-01 玩家走查（同一页面同一状态，候选 a1d30fb），不是重新拍的。

## P-04 真实原因（业主：「你需要核对清楚」）

结局读口 `/api/play/journey/ending`（`PlayJourneyStoryServiceImpl.ending`）要求玩家有一局**服务端路线局**（`topic_route_session`），并且这一局的状态是已完成；没有局就返回「本局尚未开始」，`pages/play/index.js _loadLifeEnding` 拿不到结局，静默跳到完成页。

线性主题只有在 `TopicRouteRuntimeServiceImpl.journeyConfigured` 返回 true 时才建这一局。原来它认的条件是：开局章、主题数值规则、主题运行配置，或某个节点玩法块配了 requires / effects / check。**「配了结局（尾声章）」不在里面。** 走查的主题「外滩钟声」只有 3 章 + 尾声，没有开局章、没有规则，所以整局都没有路线局，背包、案件册（「这一局还没开始」）和结局都失败。

走查报告里「只有菜单里按住『开始这一局』才会开始」这条不完整：那个按键（`onRunToggle` / `onRunHoldStart`）只启动本地计时和定位，**根本不调用服务端**，按不按都不会建局。

修复：`journeyConfigured` 的两条分支都加上「配了结局」：冻结快照分支（`runtimeSnapshotVersion==1`，读冻结的 `journeyStory`）和实时分支（读 `cms_topic.journey_story_json`）。判定用新的 `JourneyStory.hasEnding`（`ending.endings` 至少一项）。

对现有线性主题的影响：
- 没配结局的老线性主题仍然不建局，行为不变（单测负控：同一份冻结快照不带结局时仍返回 false）。
- 配了结局、已经在玩的玩家：下一次读状态或作答时建局，`ensureJourneySession` 用已有完成事实（`completed(scope)`）铺初始状态，已走完的站保持已完成；全部走完时局直接是已完成，结局可读。
- 线性主题建局后，节点推进仍以完成事实为准（`toLinearView` 对非 V1 快照仍走 `linearState`）。
- 未覆盖：导演运行时（director runtime）共享局走的是 `GameSessionRuntimeService.sharedRouteState`，不经过这里；那类场次的结局不在本次范围（C-04 归 l6）。

## 逐项

| ID | 状态 | 改了什么 | 文件 | 怎么验证 |
|---|---|---|---|---|
| P-04 | fixed | 只配了结局的线性主题也建局（冻结与实时两支）；原因见上 | `TopicRouteRuntimeServiceImpl.java`、`JourneyStory.java` | `PublishedNodeContentConsumptionTest.endingOnlyLinearThemeNeedsTheRunOnBothFrozenAndLiveBranches`（旧代码红、新代码绿）；相关 308 个后端用例通过。**rig 里走真 HTTP**：最后一站作答回执 `completed:true`，随后 `GET /api/play/journey/ending` 返回 `ending1` 和尾声正文，不再是「本局尚未开始」；库里该玩家的 `topic_route_session` 一行，状态已完成。对照：没配结局的线性主题照玩，不建局。详见 `RIG-NOTES.md`；截图 P-04-after（尾声正文）、P-04-after-2（结局页） |
| P-02 | fixed | 老文字问答标准答案按、/ , / ，拆开，命中任一即过；整串比对保留。`/api/play/answer` 与题组判定 `verifyGuidedRouteAnswer` 共用同一个 `BasicTextAnswer` | `BasicTextAnswer.java`、`PlayBasicCommandApplication.java`、`TopicRouteRuntimeServiceImpl.java` | `BasicTextAnswerTest`；`ApiPlayProgressCompletionTruthTest.alternativeAnswerPassesAndOnlyTheCorrectReceiptCarriesTheReveal`（答「第七根」过、答「6」不过） |
| P-11 | fixed | 老问答答对：题卡停一拍，写「对了」+ 作者揭示 + 本站碎片，点「继续」回故事流；只有「对了」时 1.1 秒后自动继续。揭示只在答对回执里下发（`answerReveal`），不进节点列表。离页时完成照常落下。答错不抖动（游玩台面 `nudge()` 早已是空操作，本次未新增任何抖动） | `pages/play/index.js`、`index.wxml`、`index.wxss`、`utils/answer-right-moment.js`、`PlayBasicCommandApplication.java` | `play-answer-right-moment.test.js`；同上后端用例（答错回执不带揭示） |
| P-09 | fixed | 原因：猜数字运行时视图读配置里不存在的 `question` 键，编辑器和校验器把题干存在 `title`。改为读 `title`；交过之后才下发估数、真值与揭示，屏上一行「差一点 · 你猜 6 米 · 实际 5.4 米 · 差 0.6 米」+ 揭示。整屏玩法在故事流里时，左上地图/罗盘、主题标题、属性条收起，换成返回 | `AdvancedGameRuntimeServiceImpl.java`、`playkit-estimate/*`、`playkit/index.wxml`、`utils/playkit-view.js`、`pages/play/index.wxml/wxss` | `AdvancedGameRuntimeServiceImplTest.estimateShowsTheQuestionAndRevealsOnlyAfterSubmit`（交前无真值/揭示）；`play-estimate-result.test.js` |
| P-09 其他整屏玩法 | 已核对 | 运行时视图逐个对照校验器的必填键：猜图、找东西、问答、盲品等都用 `title`，排序/连线/分类/对照/留言用 `prompt`，两边一致。只有猜数字对不上。顶部收起与返回对所有故事流内嵌整屏玩法都生效 | — | 代码核对 |
| P-10 | fixed | 老题卡答错：输入框描红 + 一行原因，下一次输入即清。进阶打字题：两处原因 —— 服务端答错常不带文案，整块反馈不渲染；而且故事流里页面不调 `settle()`、组件也不看 `attempts`，所以答错时屏上什么都不变（rig 首轮复核时发现，已补 `attempts` 观察）。现在写「再想想 / 不对。把它清掉，再试一次。」红条，输入框清空、下划线变红，下一次输入即清。猜数字没中：结果行标题用红。没有抖动、没有新动画，只有颜色过渡 | `pages/play/index.*`、`playkit-qa/*`、`playkit-estimate/*` | `play-answer-right-moment.test.js`（描红接线）、`play-estimate-result.test.js`；游玩页单色契约测试通过（只用了契约里已批的状态红 #EF4444、状态绿 #4ADE80） |
| g20 | fixed | 最后一站：不再弹「解锁徽章」卡、名次勋章屏和勋章页，不放勋章庆祝；`openFinish` 先读结局（P-04 后尾声真的能播），再出一张「全部完成」通关卡。点「继续」露出原有完成页（勋章仍在完成页勋章堆里，徽章仍在收藏）。完成横幅不再对整个主题说「本章」 | `pages/play/index.js`、`index.wxml` | `play-finale-single-card.test.js`；`play-ui-contract.test.js` 同步改了那句文案的断言 |
| P-08 | fixed | 带领场：队长没放行到的章，章节门停住并显示「等队长放行」，不再自动进正文；开玩之前同样拦下并说一句。队长放行后，轮询读到就自动接着进。放行规则本身没动（服务端 `PlayNodeAccessService.leadGateError` 照旧） | `pages/play/index.js`、`index.wxml/wxss`、`utils/lead-chapter-hold.js` | `play-lead-chapter-hold.test.js`（含负控：集合中、已结束、非带领、未知章节都不拦） |
| P-13（带领场） | fixed | 集合屏三颗点依次缓慢明暗（1.6 秒一轮，只动透明度）；减弱动态时静止 | `pages/play/index.wxml/wxss` | `play-lead-chapter-hold.test.js` 接线断言；截图 |
| P-35 | fixed | 队长放行新的一章时，玩家看到一句「队长放行了「章名」」（队长本人不提示）。商家核销的提示属于自由探索，归 l2 | `pages/play/index.js`、`utils/lead-chapter-hold.js` | `play-lead-chapter-hold.test.js` |
| P-23 | fixed（第二轮，业主已批） | 作者在罗盘玩法上可勾「允许跳过」（默认关）。玩家这台手机读不出方向时，每次失败报给服务端记一次（按人记）；本人失败满两次、且作者允许或队长放行了本人，服务端才给出「跳过这一站」。跳过由服务端完成：这一段不发 xp，进度记 `completion_mode=SKIPPED`、解谜分 0，会话里记下谁、凭什么跳的（`skipVia` AUTHOR / LEADER）。带领场里，有成员卡住且作者没开允许跳过时，队长的「更多」里出现「放行这一站 · 昵称 · 站名」，确认后放行；放行只放开本人的跳过，仍由成员自己点。只对罗盘（传感器段）开放；恢复挑战与按结果分支的玩法拒绝跳过。跳过后的罗盘不亮绿，写「这一站已跳过」 | `AdvancedActionJudgement.java`、`AdvancedGameRuntimeServiceImpl.java`、`IAdvancedGameRuntimeService.java`、`AdvancedGameConfigValidator.java`、`AdvancedGamePublicProjection.java`、`PlayNodeCompletionApplication.java`、`ApiClubLeadController.java`（`/api/club/lead/release-station`，team-progress 只给队长 `skipRequests`）；小程序 `playkit-compass/*`、`playkit/index.*`、`utils/playkit-view.js`、`pages/play/index.js/wxml`、编辑页 `publish/temp/index.wxml`、`advanced-game-config.js` | `AdvancedGameRuntimeServiceImplTest` 新增 6 条（两次失败门槛、作者关时拒绝、队友失败不算本人、队长放行后可跳、非传感器站不能放行、非传感器玩法不能跳）；`compass-skip-p23.test.js`。**rig 真 HTTP**（见 `RIG-NOTES.md` 第二轮）：他人跳过 / 报失败 → 「玩法会话不存在或无权访问」；问答站跳过 → 「本站没有这个玩法」/「这一项不支持跳过」；一次失败就跳 → 拒；作者关时跳 → 「这一站不允许跳过」；非队长放行 → 「只有本场队长可以放行」；队长放行问答站 → 「这一站不是传感器玩法，不能放行」；库里两站 `SKIPPED / 0`，会话 `score=0`。截图 P-23-before / after / after-2…5 |
| P-24 | fixed | 拍照审核上传失败：屏上写「上传失败，请重拍」（原来只有一闪而过的 toast，拍物成卡以外的模式收不到失败通知），再拍时清掉 | `pages/play/index.js`、`playkit-photocheck/*`、`playkit/index.wxml` | `play-small-items-1102.test.js`；`object-card-reveal-contract.test.js` 同步了失败通知的断言 |
| P-25 | fixed | 「看提示（1/2 · −30 分）」，看过一条变「（2/2 · −30 分）」。代价按服务端 `PuzzleScorePolicy` 100→70→40 展示，真扣多少仍以回执为准 | `pages/play/index.js`、`utils/puzzle-hint-label.js` | `play-small-items-1102.test.js` |
| P-26 | no change, owner confirmed | 业主第二轮确认：每次游玩都问定位，不改。第一轮已做的「在开始游戏时问」保留；重进仍会再问一次，符合定位合规契约 | `pages/play/index.js`（第一轮） | 第一轮 `play-small-items-1102.test.js`；定位合规契约全绿 |
| P-28（城市菜单） | fixed，有偏差 | 没有删掉「扫码到达」：城市定向定位没开时，它是到站的唯一入口（站卡上也写着「可扫码到达」）。只把副标题从「到店后扫商家的码」改成「到站后扫现场的码」 | `pages/play/index.wxml` | `play-small-items-1102.test.js` |
| P-29 | fixed（第二轮，rig 里走过换章） | 原因有两层（rig 里逐帧追出来的）：① 本章最后一站一完成，渲染那一步立刻把 `chapter` 换成下一站所在的章，最后一个节点块之后的文字从没铺出来；② 服务端只把块流下发到第一个未完成节点，作答回执触发的那次重算里，本章的收尾还不在手上，紧接着那次重拉节点才带来完整块流。修复：按「上一次所在的章」判换章（重拉会把 `data.chapter` 重置成第一章，不能拿它判）；收尾到手就先停在本章把收尾铺进故事流，点本章末尾原有的「继续探索」才换章、出章节卡；章节卡是在最后一站完成前拉的（收尾可能还没到手）就先安静停着，等重拉再判。没有收尾的章照旧直接换章；读过的收尾记进本局存档，重进不重放；试玩不走这条 | `pages/play/index.js`、`utils/chapter-trail.js`（新）、`utils/story-resume.js`（注释） | `play-chapter-trail-p29.test.js`（含「收尾未到手不能当没有收尾」）。rig：旧包作答「继续」后直接是「第 1 章 · 完成」卡（P-29-before）；新包先出本章收尾「你把年份念出来的时候……第一章的门在身后合上了。」+「继续探索」（P-29-after），点了才出第 1 章完成卡与第 2 章卡（P-29-after-2） |

## 门禁与测试

- 后端（`mvn -q -pl chengyinhub-admin -am -Dtest=...`）：本次涉及的套件全部通过，包括 `TopicRouteRuntimeServiceImplTest`、`JourneyRuntimeActionsTest`、`PlayJourneyStoryServiceImplTest`、`AdvancedGameRuntimeServiceImplTest`（201）、`ApiPlayProgressCompletionTruthTest`（36）等。
- 新测试在旧代码上的结果：见下方「旧代码对照」。
- 小程序：CI 同款分片 11 / 12 / 20 本地全过；`play*`、`playkit*`、`object-card*`、`*photo*`、定位合规相关用例全过。动作台账测试的两个计数（本地 tap 2445→2446、控件 5595→5597）按重建实测更新。
- 游玩页「keyframes 只减不增」：等待动画复用已有 `fade` 关键帧来回播，没有新增 keyframes。
- `node chengyinhub-xcx/tests/lint/ui-integrity.js` → `UI-GATE-0:PASS`。两条既有登记只是行号位移（1791→1797、8072→8139），按惯例换 key 并重算 digest，没有新增登记。
- 动作台账重建，只多了两个控件：答对后的「继续」、整屏玩法的「返回故事」。

## needs owner

第一轮列的两条业主已在第二轮定了（P-23 按建议做、P-26 不改），本轮没有新的需要业主定的事。以下是做 P-23 时的两处取舍，默认已按括号里的做，业主不同意再改：

1. **跳过的站还给不给节点探索值。** 现在：罗盘段的 xp 不发、解谜分 0、进度记 SKIPPED，但节点完成本身照旧（完成链对自报传感器段本来就只发探索值与徽章，不发积分 / 券）。建议保持（这一站人确实到了现场）。
2. **队长放行时机。** 现在：只有成员本人读取失败满两次、且作者没开允许跳过时，才出现在队长的放行列表里；放行后仍由成员本人点「跳过这一站」。放行列表靠队伍进度轮询（4–10 秒），成员那边要再读一次失败或重进这一站才看到按钮。建议保持（放行不替成员做决定）。

- **第二轮**：后端相关套件 23 个类 632 条全过（含 `AdvancedGameRuntimeServiceImplTest` 207 条）；小程序 `compass-skip-p23.test.js`、`play-chapter-trail-p29.test.js` 新增，`playkit-compass-contract` / `compass-sensor-recovery` 照过；全量 `node --test tests/unit/*.test.js` 15569 条全过。动作台账重建 5597 → 5602（罗盘「跳过这一站」、分发器 readfail / skip 两条事件、编辑页「允许跳过」开关、队长「放行这一站」），测试里的三个计数按重建实测更新；`UI-GATE-0:PASS`，唯一一条 U5 登记（loadMilestone）只是行号位移 8134 → 8208，按惯例换 key 重算 digest，没有新增登记。

## 旧代码对照

在 `origin/master` 的独立工作树里只放入新测试（外加测试要用的 `JourneyStory.hasEnding`），跑 `PublishedNodeContentConsumptionTest`、`AdvancedGameRuntimeServiceImplTest`、`ApiPlayProgressCompletionTruthTest`：恰好三条新用例失败，其余全过 ——
`endingOnlyLinearThemeNeedsTheRunOnBothFrozenAndLiveBranches`、`estimateShowsTheQuestionAndRevealsOnlyAfterSubmit`、`alternativeAnswerPassesAndOnlyTheCorrectReceiptCarriesTheReveal`。本分支上三条都过。

## rig 里另外看到的（未改，记下）

- **猜数字交完后**，844 高的屏上揭示贴着下沿，「继续故事」要往下滚一点，且压在一枚灰掉的「这一关结束了」下面（截图 P-09-after-3）。只改了内容，没动这一屏的按钮布局；建议下一轮把交完后的灰按钮收起。
- **最后一站在第二章时**，通关前出现的章节卡写「第 1 章 / 共 2 章 · 第一章」——和 P-29（章节收尾与换章时机）是同一条链路，未改。
- 进阶打字题比对 `AdvancedActionJudgement.matchesAnswer` 的分隔符写成了 `[、,,]`（两个半角逗号），全角逗号「，」分不开。不在本 lane 条目里，未改；建议并入 P-02 同一规则。

## 真机上仍未验证

- P-23：rig 的罗盘缝每 500 毫秒必发一帧，读不出方向是在 rig 页面里把 `wx.startCompass` 改成直接 fail 模拟的（只在 rig 页面内注入，没进产品代码）。真机上「读不出方向」的几种样子（无罗盘、首帧超时、帧中断）都走同一个 `_failed`，但没在手机上跑过。
- P-23：队长放行后，成员那边要再失败一次或重进这一站才看到「跳过这一站」（玩法视图不轮询）；队长那句「某某在某站读不出方向」的提示在 rig 截图里没拍到（提示是一闪的 diegetic 行）。
- P-29：只在 rig 里走了「第一章 → 第二章」一次换章；带领场「等队长放行」与收尾同时出现的组合、以及多章连续换章没走。

- 定位弹窗时机、罗盘、拍照上传都只在浏览器 rig 里跑过，没在手机上跑。
- 「对了」那一拍和三点等待动画的观感、减弱动态下的表现。
- 带领场放行的轮询间隔（4–10 秒）决定了「等队长放行」解开的延迟。

## Round 3

按 ROUND3.md（业主审阅与 Figma 核对）和 ROUND3C.md（限额中断后重做）。开工时本分支已含 origin/master（c6eac3b8，#702 已合），本轮无需再合；收尾时再取一次 master，仍无新提交。本轮全部改动只复用既有组件：章节门就是 `cy-chapter-flip mode="gate"`、作者结果反馈走既有的结算判定屏 `cy-play-verdict`（`r5Verdict`）、彩纸是通关过场自己的彩纸，没有新画任何 UI。

| ID | 状态 | 改了什么 | 文件 | 怎么验证 |
|---|---|---|---|---|
| P-08 | done | 被挡住的章停在章节翻转门本身：底部「轻点跳过」换成「等队长放行」，门不再自己淡出、轻点也不进；地图上的 `chgate-hold` 一行和样式删除（所以门后的「继续探索」也露不出来）；队长放行后门自己淡出进正文（4.2 秒兜底）。门上的读屏文案同步为「…，等队长放行」 | `pages/play/components/chapter-flip/index.js/wxml`、`pages/play/index.js/wxml/wxss` | `chapter-flip-round3.test.js`（held 时播完不进、轻点不进、放行后自己进、未播完放行按原计时进、不带 held 照旧，旧代码 4 红）；`play-lead-chapter-hold.test.js`。**rig**：同一场（活动 14，队长停在第一章）旧包 P-08-r3-before＝门已淡出、黑屏上「继续探索」+「等队长放行」胶囊；新包 P-08-r3-after＝停在第二章翻转门上、底部「等队长放行」（开页后 18 秒仍停着）；队长 `lead/unlock-chapter` 52→53 后，P-08-r3-after-2＝门自己进了第二章正文，并有「队长放行了「第二章 · 钟楼」」 |
| P-10 / P-11 | done | ① 老题卡：模板「文字反馈」`feedbackText` 是完成后的话（编辑器占位「恭喜你解开了老城的第一个秘密!」，后端 `CmsMemberTemplate` 也注明「完成即发」）。**答错能显示成功文案：是**——原来答错兜底是 `r.msg \|\| n.feedbackText`，服务端没带原因时就会把祝贺语当答错提示。现在答错兜底永不取它；答对时有它就写作者的话（不再叠「对了」+ 揭示），没有才用「对了」+ 揭示兜底。② 进阶题（模板「结果反馈」`outcomeRules.pass/fail` 的 `reply`/`images`）：作者的话本来就由结算回执（`lastOutcome.reply/images`）驱动既有判定屏 `cy-play-verdict` 摆出来；问题是题屏同时叠我第一轮写的「再想想 / 不对。把它清掉，再试一次。」。现在玩法视图在判完之后带 `outcomeFeedback`（只在作者给这个结果写了文字或配图时才有），题屏有它就只描红清空、不叠兜底句；作者没写时兜底照旧 | `AdvancedGameRuntimeServiceImpl.java`（`authoredOutcomeFeedback`）、`pages/play/index.js/wxml`、`utils/answer-right-moment.js`、`utils/playkit-view.js`、`components/playkit-qa/index.js`、`components/playkit/index.wxml` | `AuthoredOutcomeFeedbackTest`（判前不给、没通过给 fail、通过给 pass 与配图、作者没写不给）4 条 + `AdvancedGameRuntimeServiceImplTest` 207 条全过；`play-answer-right-moment.test.js`、`playkit-qa-authored-outcome-p10.test.js`（旧代码 7 红）。**rig**：同一道老题（模板配了文字反馈）答 1873：旧包 P-11-r3-before＝「对了」+ 揭示；新包 P-11-r3-after＝作者的「门楣认出来了：这座楼在 1873 年重建落成。」+ 碎片。**进阶题的作者结果反馈没在 rig 里拍到**：配了 `outcomeRules` 的主题能发布（参与方式须为 GPS / 扫码），但开局即报「数值状态必须为已初始化的整数：sys.hp」——R5 结算要求整套主题数值规则，走查夹具没有；这一条只有单测证据 |
| P-09 | done | 题干看不清的原因：故事流 `.chfull__scroll` 上沿有 0–220rpx 透明、到 340rpx 才实的渐隐遮罩（防顶栏叠字），内嵌玩法块的题干落在约 310rpx，被遮成灰字。改为内嵌块上内边距至少到遮罩终点（`--chfull-fade-end:340rpx`，与遮罩同值）；为不把「就这个数」挤出一屏，内嵌时猜数字滚筒 980→860rpx。结果行被截：交过之后滚筒收成只留中间那个数（400rpx），结果行、作者揭示、按钮都在一屏内 | `pages/play/index.wxml/wxss`、`components/playkit-estimate/index.wxml/wxss` | `play-estimate-result.test.js` 新增 2 条。**rig**（活动 12，同一题同一初值 11）：P-09-r3-before＝灰色题干；P-09-r3-after＝白色题干、按钮完整；P-09-r3-before-2＝交后「约 5.4 米。」压在下沿被截；P-09-r3-after-2＝结果、揭示、「这一关结束了」「继续故事」都在屏内 |
| g20 | done | 通关过场的彩纸代码自走查以来没变；原因是它 2.6 秒就停撒，通关卡 3 秒才立起，卡一出来屏上只剩零星几片（第一轮 g20-after 就是这样）。改成撒到卡立起之后再 2.4 秒；片子、颜色、下落不变；减弱动态时仍不撒。通关卡不变 | `pages/play/components/chapter-flip/index.js` | `chapter-flip-round3.test.js` g20 条（旧代码红）。**rig**：同一主题两个玩家，点尾声「继续」后同一时刻截图：g20-r3-before（旧包）卡上只剩几片，g20-r3-after（新包）卡上满屏彩纸；-2 是早 2 秒的一帧 |

### 门禁与测试（第三轮）

- 后端：`mvn -q -pl chengyinhub-admin -am -Dtest='AuthoredOutcomeFeedbackTest,AdvancedGameRuntimeServiceImplTest'` → 211 条全过（`LANG=C.UTF-8`）。
- 小程序：全量 `node --test tests/unit/*.test.js` 15603 条全过（P-09 末次调整前跑的；之后 `play*`、`playkit*`、`chapter*` 相关 368+ 条重跑全过）。
- `node chengyinhub-xcx/tests/lint/ui-integrity.js` → `UI-GATE-0:PASS`。唯一一条 U5 登记（loadMilestone）只是行号位移 8218→8230，按惯例换 key 重算 digest，没有新增登记。动作台账重建：只有章节门读屏文案与行号变化，控件数不变。

### rig 说明（第三轮）

- 新容器。rig＝`origin/claude/cloud-walk-20261101-rig` + 俱乐部 lane 的 `harness-patches.diff`（只在 rig 里）；`up.sh` 的 `SHA` 指向本分支 211fca4b（后端 jar 由它构建；P-09 末次调整只动小程序，新包按 9eb721cb 重建）。Docker Hub 仍可能限流，MySQL / Redis 从 `mirror.gcr.io` 按同一摘要拉取后打本地标签。
- before 截图用本轮之前的提交 c353c611 打的小程序包、after 用本轮提交打的包；后端同一个。都是浏览器 rig，不是真机。
- 夹具（全部真接口）：主理人 9003 草稿模板 6005（老文字题，带文字反馈与揭示）、6006（进阶打字题）、6007（猜数字）；主题 990018（两章 + 尾声，LINEAR，俱乐部 7001，免费票）与 990017（猜数字一站）；带领场活动 14、12；玩家 walk-player-1…6。另有 990016（带 `outcomeRules` 的版本，开局报 sys.hp，已下架）。直接写库只有 `seed-walk.py shift-session`（活动 11/12/14 改开始时间）；审核用 `/business/audit/review` + `expectedVersion`。
- 「开启定位」弹窗在 rig 里会盖在章节门上，截图前先点掉。

### 真机上仍未验证（第三轮）

- 进阶题「作者在结果反馈里写了话」时题屏不叠兜底、由判定屏摆作者的话：只有单测，rig 夹具跑不起 R5 主题（见上）。
- P-08 放行后门的淡出与自动进正文的节奏（依赖带领轮询 4–10 秒）；rig 里只走了一名成员。
- g20 彩纸在真机帧率下的密度；`prefers-reduced-motion` 下不撒（代码路径未改）。
- P-09 在不同机型（状态栏高度不同）上题干与遮罩的相对位置；`max()` 在基础库 WebView 上的支持按 iOS 11.3+ / Chromium 计，未在真机确认。

## Round 4

按 ROUND4.md（2026-11-03 业主审阅第三轮）。开工时本分支已含 origin/master（c6eac3b8），收尾时再取一次仍无新提交，本轮无需合并。全部只复用既有件：等待动画就是集合屏的 `lg-wait` 三点、放行后的过场仍是 `cy-chapter-flip`、作者结果反馈仍由既有判定屏 `cy-play-verdict` 摆。没有加说明行。

| ID | 状态 | 改了什么 | 文件 | 证明 |
|---|---|---|---|---|
| P-08 | fixed | 被挡住的章不再停在翻转门上：换成一屏「等待放行」——标题在上（胶囊下沿）、集合屏同款三点在屏幕正中（减弱动态时静止）。这一屏没有真实动作，所以不放按钮（故事流本身也没有退出钮，微信胶囊照常可用）。队长放行后收起这一屏、挂上正常的章节翻转门，播完照常进正文（兜底计时与平常的门同值 4.2 / 7.8 秒）。翻转门组件退回第三轮之前、不再认识 `held` | `pages/play/index.wxml/wxss/js`、`components/chapter-flip/index.js/wxml` | `play-chapter-hold-screen-p08-r4.test.js`（布局、减弱动态、组件不认 held、放行换门与兜底、仍被挡时不进）；`chapter-flip-round3.test.js` 去掉 held 三条、`play-lead-chapter-hold.test.js` 改认新屏。**rig**：带领场 12（主题 990018 两章），同一时刻同一状态——P-08-r4-before（第三轮包：翻转门 +「等队长放行」）、P-08-r4-after（新包：「等待放行」+ 三点）；队长 `lead/unlock-chapter` 47→48 后 P-08-r4-after-2（正常翻转门在播，玩家侧弹「队长放行了「第二章 · 钟楼」」），随后进第二章正文 |
| P-09 | fixed | 故事流里的猜数字，题目移进顶栏：和返回钮、微信胶囊同一行（顶栏按胶囊竖向居中对齐，右边距让开胶囊 `chrome.actionRight`），最多两行、超出省略；这时右上的圆钮（案件册 / 游玩工具）收起，组件里原来那行题目由页面用 CSS 变量收起（整屏单独打开时题目照旧）。交过之后只写「这一关结束了」的灰钮点了什么都不做，去掉；「继续故事」不变 | `pages/play/index.wxml/wxss/js`、`pages/play/utils/kit-head.js`（新，18 行）、`components/playkit-estimate/index.wxml/wxss` | `play-kit-head-p09-r4.test.js`（对齐几何、胶囊右边距、两行截断、组件题目收起、灰钮没了且「继续故事」还在）；`play-estimate-result.test.js` 随内嵌变量更新。**rig**：同一站同一猜数 11——P-09-r4-before / -before-2（第三轮包：题目单独一行在三颗圆钮下、交后灰钮「这一关结束了」）、P-09-r4-after / -after-2（新包：题目在返回钮右边、胶囊左边两行，交后只剩结果与「继续故事」） |
| P-10 / P-11 | fixed（真走通）+ 1 条 needs owner | **夹具**（全部真接口，见下）：带 `journeyRules`（`stateEnabled`、`hp` 初始 10）与 `themeRuntimeConfig.gameplayRuntime`（PLAYER，一幕一任务绑在这一站，`survival.zeroHpPolicy=BLOCK_HP_COST`、`actionLimitPolicy=BLOCK_OVERFLOW`）的主题 990021，站点是进阶打字题（GPS 参与），模板「结果反馈」`outcomeRules.pass/fail` 各配 `reply` + 一张图。**真走暴露并修了三处**：① 开局从不读个人局主题运行时（`/api/play/theme/state` 只在第一次到达的回包里带回来），任务不可见——已建局就读一次；② 带玩法的 GPS 站要「先过玩法再到达」，任务要「先到达再开始任务再玩」，两边互等，首站只出「请先开始当前任务 · 点击重新读取」——任务只差到达（`disabledReason=NOT_ARRIVED`）时也靠近即到达，服务端本来就收（「已记录本局到达，请继续当前任务或操作」）；③ 第三轮的 `outcomeFeedback` 在 `cy-advanced-game.emitSession` 这一层被丢掉，页面永远拿不到，作者判定屏下面仍叠「再想想 / 不对。把它清掉，再试一次。」——补上抛字段；题卡在作者反馈晚到一拍时补清兜底句 | `pages/play/index.js`、`components/advanced-game/index.js`、`components/playkit-qa/index.js` | `play-generic-quest-arrival-p10-r4.test.js`、`advanced-game-outcome-feedback-relay-p10.test.js`（走真 action → hydrate → emitSession，旧代码 2 红）、`playkit-qa-authored-outcome-p10.test.js` 加晚到一条。**rig + HTTP + 库回读**：见下 |

### P-10 / P-11 真走记录（rig，浏览器 + 真后端）

- 走法（全部是产品自己的按钮）：开始游戏 → 开启定位 → 靠近即到达 → 背包「任务」页自动打开 → 「开始任务」（`QUEST_START`）→ 站点「开始任务」打开题目 → 作答。
- **答错**（walk-player-8 = 会员 990012，答「黄浦江」）：`POST /api/play/advanced/action` → `status:FAILED`，`outcomeFeedback:{outcome:FAIL, reply:「不是这个名字。去桥头看看那块石碑，上面刻着它的旧称。」, images:[…/rigfbfail.jpg]}`；屏上 P-10-r4-after＝判定屏「失败」+ 作者的话 + 作者配图 +「继续」；点继续后 P-10-r4-after-2＝题屏只描红清空，不再叠兜底句。
- **答对**（walk-player-10 = 会员 990014，答「吴淞江」）：题屏「答对了」+「继续故事」（P-11-r4-after-2）→ 背包「待确认结算 · 凭证已通过，可结算」→「结算任务」→ 奖励预览「确认装载」→ P-11-r4-after＝判定屏对勾 + 作者的话「对，就是吴淞江。老周的第三张字条压在桥头石碑下面。」+ 作者配图 +「继续」。
- **before**（P-10-r4-before / P-11-r4-before，同一主题同一站，第三轮包）：首站只出「请先开始当前任务 · 点击重新读取」，答对答错都走不到。
- **库回读**（`topic_route_session.state_json` 的 `themeRuntime.lastOutcome`）：会话 6 / 会员 990012 → `FAIL`、作者 fail reply、`["/profile/upload/2026/11/03/rigfbfail.jpg"]`；会话 9 / 会员 990014 → `PASS`、作者 pass reply、`["/profile/upload/2026/11/03/rigfbpass.jpg"]`。`/api/play/theme/state` 回包的 `lastOutcome` 与之一致（图片按媒体出口换成可见地址）。

### needs owner

1. **作者判了「没通过」之后，这一站卡住。** 实测：答错一次，进阶会话 `FAILED`、`canRetry:false`；但任务仍挂着（`activeQuest=q1`，结算 `QUEST_RESOLVE` 置灰为 `PROOF_PENDING`，剩 1 次），路线该站仍 `PLAYABLE`。题屏还摆着可点的「提交」，再交（哪怕交对）服务端照回 `FAILED`，屏上什么都不变（rig 里会员 990013 复现）。这是 R5 结算规则（没通过后任务该结束、可重开还是可重试），不是展示问题，本 lane 没改。**建议默认**：作者配了 `fail` 且玩法不可重试时，没通过即按 FAIL 结算这一任务（扣作者配的代价），题屏收起「提交」只留「继续故事」；需要重试的由作者开「可重试」。
2. **P-09 只改了猜数字。** 第三轮截图里「题目单独一行在圆钮下面」的只有猜数字这一屏；打字题 / 选项题的题目是在屏幕中间排的（P-11-r4-after-2），没改。若业主要所有题型都把题目提到胶囊那一行，需要逐个玩法组件收起自己的题目，建议另开一项。

### rig 说明（第四轮）

- 新容器。rig＝`origin/claude/cloud-walk-20261101-rig` + 俱乐部 lane 的 `harness-patches.diff`；`up.sh` 副本的 `SHA` 指向本分支 4228efcd（后端 jar 由它构建；本轮没有改 Java）。MySQL / Redis 从 `mirror.gcr.io` 按同一摘要拉取后打本地标签。
- before 包＝第三轮末 b1c40e9b，after 包＝9288f8cf；后端同一个。都是浏览器 rig，不是真机。
- **只在 rig 里**的两处：① rig 的 `app.py` 加了一个开关，把 `--app.media.public-base-url=http://127.0.0.1:8097/__api` 传给应用（新库没配媒体公网前缀时，作者配图在回包里是空串——这是部署配置，生产有）；② 作者配图是放进 rig 上传目录的两张仓内图（`pages/templatedetail/art/covers/game/` 的 city-camera / branch-story），因为 rig 的上传要过微信内容安全、连不到。另在 rig 的 Redis 种过一个假的 `wx:mini:access_token`（没起作用，库是一次性的）。
- 夹具（全部真接口）：作者 9003 实名登记（`/api/publisher/identity`，假名 + 校验位合法的假证件号）；模板 6005 / 6007 / 6010 / 6011（打字题 + 结果反馈，GPS 参与）、6006 / 6009（猜数字）、6008（打字题）；主题 990017（只配 journeyRules，没有任务，开局「请先开始当前任务」且无处开始，已下架）、990018（两章，P-08 / P-09）、990020（已下架）、990021（草稿后补任务再发布，P-10 / P-11）；审核 `/business/audit/review` + `expectedVersion`；带领场 11 / 12 / 15（`/api/club/open-session`、`lead/start`）；玩家 walk-player-1…11（真登录、入会、报名同意、¥0 报名）。直接写库只有 `seed-walk.py shift-session`（活动 11 / 12 / 15 改开始时间）。玩家 3 的第一次到达是直接调 `/api/play/arrive`（为确认服务端收不收「先到达」），之后的玩家都由页面自己到达。

### 门禁与测试（第四轮）

- 小程序：P-08 / P-09 提交前 `action-ledger*`、`play*`、`chapter*`、`playkit*`、`ui-integrity*` 1366 条全过；P-10 / P-11 提交前 `advanced-game*`、`playkit-qa*`、`play-generic*`、`play-kit-head*`、`play-chapter-hold*`、`action-ledger*`、`r5*`、`theme-backpack*` 343 条全过。新增的四个测试文件在旧代码上红。
- `node chengyinhub-xcx/tests/lint/ui-integrity.js` → `UI-GATE-0:PASS`。U4-DYNAMIC 1797→1795、U5 8230→8228→8236 只是行号位移（同一调用行），按惯例换 key 重算 digest，没有新增登记。动作台账重建：控件数 5606 不变（只有行号、猜数字提交钮的显示条件、翻转门读屏文案）。
- 后端：本轮没改 Java。

### 真机上仍未验证（第四轮）

- P-08 等待屏三点的明暗在真机帧率下的观感；放行后从「等待放行」换到翻转门的那一下（依赖带领轮询 4–10 秒）。
- P-09 顶栏按 `getMenuButtonBoundingClientRect` 对齐：rig 的胶囊是固定位置，不同机型的胶囊高度 / 位置没在手机上看过；两行题目和胶囊的距离以真机为准。
- P-10 / P-11：配图是站内路径经媒体出口换成的地址，真机上走 CDN / 本站前缀；`getLocation` 精度 ≤80 米才自动到达，rig 的定位精度是假的。

STATUS: COMPLETE

## Round 5C

| 项 | 状态 | 文件 | 证据 |
|---|---|---|---|
| P-08 等待放行屏中间的三点几乎看不见 | fixed | `pages/play/index.wxss`（只在 `.chhold__wait` 下加一行） | 三点原来用集合屏的 `--cy-color-text-tertiary`（#919191），在深灰底上又随 `fade` 动画在 0→1 之间明暗，几乎看不见。改成与标题「等待放行」同一个既有 token `--cy-color-text-primary`（#F8F8F8）。大小、动画、减弱动态的静止态、集合屏三点一律不动。`play-chapter-hold-dots-p08-r5c.test.js`（2 条，旧代码上 2 条都红）。**rig**：before = 第四轮的 `P-08-r4-after.jpg`（同一屏、同一包除这一行外相同）；after = `P-08-r5-after.jpg`。浏览器里读到三点计算色 `rgb(248, 248, 248)`（原来 #919191） |

说明（照实写）：三点仍按 P-13 批准的动画依次「0 → 1」明暗，所以任一时刻总有一颗在暗端。after 截图是在三颗不透明度都高于 0.3 时拍的（0.99 / 0.84 / 0.31）。如果业主要它们任何时刻都清楚可见，就得给这一屏的动画加一个最低亮度，也就是新增一条 keyframes（门禁对 keyframes 只减不增）或改共用的 `fade`。这超出了「只换颜色」，本轮没做，**needs owner**。

## Round 5D

| 项 | 状态 | 文件 | 证据 |
|---|---|---|---|
| P-08 等待放行屏底部「返回地图」 | fixed | `pages/play/index.wxml/js/json/wxss`，`scripts/uiaudit/action-ledger.json`，`tests/lint/ui-integrity-baseline.js` | 见下 |

做法：
- 等待屏最后一个元素是既有底部按钮条 `cy-footer-bar`（`primary="返回地图"`、次按钮样式）。它的承托底读 `--cy-color-bg-page`（纯黑），所以这一屏把它接到最接近本屏深灰的既有 token `--cy-color-bg-elevated`，屏底不会多出一条黑带。
- 点「返回地图」（`leaveChapterHold`）时，等待屏收起（`chapterGate.away`），打开故事流的大地图（`openStoryMap`，城市定向里唯一的地图层）。章节门不撤，仍挂着这一章。
- **在地图上被放行**：带领轮询（原来末尾那段对账抽成 `_syncChapterHold`）读到放行后，收起地图，挂上正常的 `cy-chapter-flip`，播完照常进这一章；门没发 continue 时按原兜底计时（4.2 / 7.8 秒）进。
- **地图上再点回这一章**：用地图左上的「回到故事」（`closeStoryMap`）。仍被挡着就回到等待放行屏；如果在地图上时已经放行、轮询还没轮到，就直接放翻转门进章，不再停在等待屏。
- **回到等待屏后再被放行**：与第四轮相同，换翻转门进章。
- 没有任何带坐标地点的主题（城市定向里极少见）：`openStoryMap` 原来就会退回收起故事流。这时记下这一章，放行后重开这一章，开场门走翻转门。普通的地图「回到故事」行为不变。

测试：`play-chapter-hold-map-p08-r5d.test.js` 8 条，用页面真代码驱动，视图条件照 wxml 求值。覆盖按钮位置和样式、点按钮、在地图上放行、回到故事时仍被挡、回到故事前已放行、无坐标退回、普通地图不受影响、没被挡时按钮不动作。旧代码上 7 条红，1 条是负控（普通地图），新旧都绿。第四轮的 `play-chapter-hold-screen-p08-r4.test.js` 原来写着「这一屏没有按钮」，`play-lead-chapter-hold.test.js` 原来锚着旧 wx:if，两者按业主本轮的决定改口径。

rig（浏览器 + 真后端，候选 12932a33，下文「rig 说明」）：
- 场 10（玩家 9009）：第一章答对后停在第二章门前。`P-08-r5-after`：等待放行、三点、底部「返回地图」。点按钮后 `P-08-r5-after-2`：大地图、「回到故事」、下一站乍浦路桥，状态 `away:true, storyMap:true, held:true`。点「回到故事」后 `P-08-r5-after-3`：回到等待放行屏。再点「返回地图」，队长 `POST /api/club/lead/unlock-chapter` 42→43 → `{"msg":"已开启:第二章 · 钟楼","code":200}`。轮询读到后地图收起，`P-08-r5-after-4`：正常的章节翻转门，加上「队长放行了「第二章 · 钟楼」」；9 秒后 `P-08-r5-after-5`：第二章正文「电报的下半截被人带过了苏州河。」+「继续探索」。
- 场 11（玩家 9004）：等待屏 → 返回地图 → 回到故事，回到等待屏后被放行。`P-08-r5-after-6`：翻转门；`P-08-r5-after-7`：第二章正文。
- 两场页面错误都为 0。

### 门禁与测试（第五轮）

- `node chengyinhub-xcx/tests/lint/ui-integrity.js` → `UI-GATE-0:PASS`。U5 loadMilestone silentError 8236→8265 只是行号位移（同一调用行），按惯例换 key、重算 digest，没有新增登记。第一版用的 `Object.assign` 动态 setData 被 U4 拦下，改成两条静态 setData，没有登记。
- 动作台账重建：控件 5606→5607（「返回地图」）。本地 tap 分母 2449→2448，是因为地图「回到故事」`closeStoryMap` 现在能接着放翻转门进章（章节旁白、存档），分类从 state 变成 external。`action-ledger.test.js` 两个计数按重建实测改，原因写在注释里。
- 小程序全部单测 `node --test "tests/unit/**/*.test.js"`：15624 条通过，0 失败（5C + 5D 之后）。
- 后端：本轮没改 Java。

### rig 说明（第五轮）

- 新容器。rig = `origin/claude/cloud-walk-20261101-rig` + 俱乐部 lane 的 `harness-patches.diff`（只在 rig 里），`up.sh` 的 `SHA` 指向 12932a33。MySQL / Redis 从 `mirror.gcr.io` 按同一摘要拉取，再打本地标签。
- 夹具全部走真接口：作者 9003 实名登记、模板 6005 / 6006（文字题）、`/business/audit/review` 审核、主题 990015（两章各一站，LINEAR，¥0，俱乐部 7001）、`/api/club/open-session` 开场 10 / 11、`lead/start`、玩家 9009 / 9004 入会 + 同意 + quote + create（¥0）、`/api/play/answer` 答完第一章、`lead/unlock-chapter` 放行。直接写库只有 `seed-walk.py shift-session 10 / 11`（改开始时间）。

### 真机上仍未验证（第五轮）

- 三点在真机上的亮度和动画节奏；`cy-footer-bar` 在真机上的安全区内边距。
- 地图在 rig 里是画布替身，真机是原生 `<map>`。从地图被拉回翻转门那一下，依赖 4–10 秒一次的带领轮询。

### needs owner

- 三点任何时刻都清楚可见，要给动画加最低亮度，见 Round 5C 说明。

STATUS: COMPLETE
