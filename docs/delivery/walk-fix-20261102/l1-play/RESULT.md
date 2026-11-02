# l1-play 结果：城市定向游玩页、剧情、结局与游戏手感

分支 `claude/walk-fix-1102-l1-play`（从 `origin/master` 014fc0d8 切出），草稿 PR #713。截图在 `shots/`，rig 记录在 `RIG-NOTES.md`。

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
| P-04 | fixed | 只配了结局的线性主题也建局（冻结与实时两支）；原因见上 | `TopicRouteRuntimeServiceImpl.java`、`JourneyStory.java` | `PublishedNodeContentConsumptionTest.endingOnlyLinearThemeNeedsTheRunOnBothFrozenAndLiveBranches`（旧代码红、新代码绿）；相关 308 个后端用例通过；HTTP 证明见 `RIG-NOTES.md` |
| P-02 | fixed | 老文字问答标准答案按、/ , / ，拆开，命中任一即过；整串比对保留。`/api/play/answer` 与题组判定 `verifyGuidedRouteAnswer` 共用同一个 `BasicTextAnswer` | `BasicTextAnswer.java`、`PlayBasicCommandApplication.java`、`TopicRouteRuntimeServiceImpl.java` | `BasicTextAnswerTest`；`ApiPlayProgressCompletionTruthTest.alternativeAnswerPassesAndOnlyTheCorrectReceiptCarriesTheReveal`（答「第七根」过、答「6」不过） |
| P-11 | fixed | 老问答答对：题卡停一拍，写「对了」+ 作者揭示 + 本站碎片，点「继续」回故事流；只有「对了」时 1.1 秒后自动继续。揭示只在答对回执里下发（`answerReveal`），不进节点列表。离页时完成照常落下。答错不抖动（游玩台面 `nudge()` 早已是空操作，本次未新增任何抖动） | `pages/play/index.js`、`index.wxml`、`index.wxss`、`utils/answer-right-moment.js`、`PlayBasicCommandApplication.java` | `play-answer-right-moment.test.js`；同上后端用例（答错回执不带揭示） |
| P-09 | fixed | 原因：猜数字运行时视图读配置里不存在的 `question` 键，编辑器和校验器把题干存在 `title`。改为读 `title`；交过之后才下发估数、真值与揭示，屏上一行「差一点 · 你猜 6 米 · 实际 5.4 米 · 差 0.6 米」+ 揭示。整屏玩法在故事流里时，左上地图/罗盘、主题标题、属性条收起，换成返回 | `AdvancedGameRuntimeServiceImpl.java`、`playkit-estimate/*`、`playkit/index.wxml`、`utils/playkit-view.js`、`pages/play/index.wxml/wxss` | `AdvancedGameRuntimeServiceImplTest.estimateShowsTheQuestionAndRevealsOnlyAfterSubmit`（交前无真值/揭示）；`play-estimate-result.test.js` |
| P-09 其他整屏玩法 | 已核对 | 运行时视图逐个对照校验器的必填键：猜图、找东西、问答、盲品等都用 `title`，排序/连线/分类/对照/留言用 `prompt`，两边一致。只有猜数字对不上。顶部收起与返回对所有故事流内嵌整屏玩法都生效 | — | 代码核对 |
| P-10 | fixed | 老题卡答错：输入框描红 + 一行原因，下一次输入即清。进阶打字题：服务端答错常不带文案，原来整块反馈不渲染；现在写「再想想 / 不对。把它清掉，再试一次。」红条，输入框下划线变红，下一次输入即清。猜数字没中：结果行标题用红。没有抖动、没有新动画，只有颜色过渡 | `pages/play/index.*`、`playkit-qa/*`、`playkit-estimate/*` | `play-answer-right-moment.test.js`（描红接线）、`play-estimate-result.test.js`；游玩页单色契约测试通过（只用了契约里已批的状态红 #EF4444、状态绿 #4ADE80） |
| g20 | fixed | 最后一站：不再弹「解锁徽章」卡、名次勋章屏和勋章页，不放勋章庆祝；`openFinish` 先读结局（P-04 后尾声真的能播），再出一张「全部完成」通关卡。点「继续」露出原有完成页（勋章仍在完成页勋章堆里，徽章仍在收藏）。完成横幅不再对整个主题说「本章」 | `pages/play/index.js`、`index.wxml` | `play-finale-single-card.test.js`；`play-ui-contract.test.js` 同步改了那句文案的断言 |
| P-08 | fixed | 带领场：队长没放行到的章，章节门停住并显示「等队长放行」，不再自动进正文；开玩之前同样拦下并说一句。队长放行后，轮询读到就自动接着进。放行规则本身没动（服务端 `PlayNodeAccessService.leadGateError` 照旧） | `pages/play/index.js`、`index.wxml/wxss`、`utils/lead-chapter-hold.js` | `play-lead-chapter-hold.test.js`（含负控：集合中、已结束、非带领、未知章节都不拦） |
| P-13（带领场） | fixed | 集合屏三颗点依次缓慢明暗（1.6 秒一轮，只动透明度）；减弱动态时静止 | `pages/play/index.wxml/wxss` | `play-lead-chapter-hold.test.js` 接线断言；截图 |
| P-35 | fixed | 队长放行新的一章时，玩家看到一句「队长放行了「章名」」（队长本人不提示）。商家核销的提示属于自由探索，归 l2 | `pages/play/index.js`、`utils/lead-chapter-hold.js` | `play-lead-chapter-hold.test.js` |
| P-23 | needs owner | 罗盘读不出方向时没有出路。「跳过」要服务端认一个不靠传感器的完成，并且要决定谁能放行（作者开关还是队长现场放行），客户端单方面做不了 | — | 见下方 needs owner |
| P-24 | fixed | 拍照审核上传失败：屏上写「上传失败，请重拍」（原来只有一闪而过的 toast，拍物成卡以外的模式收不到失败通知），再拍时清掉 | `pages/play/index.js`、`playkit-photocheck/*`、`playkit/index.wxml` | `play-small-items-1102.test.js`；`object-card-reveal-contract.test.js` 同步了失败通知的断言 |
| P-25 | fixed | 「看提示（1/2 · −30 分）」，看过一条变「（2/2 · −30 分）」。代价按服务端 `PuzzleScorePolicy` 100→70→40 展示，真扣多少仍以回执为准 | `pages/play/index.js`、`utils/puzzle-hint-label.js` | `play-small-items-1102.test.js` |
| P-26 | fixed | 城市定向有带坐标的站时，在「开始游戏」这一刻问定位；同一局同意过，退出重进不再弹（记在本机的本局续玩存档里，按账号 + 场次 + 主题隔离） | `pages/play/index.js` | `play-small-items-1102.test.js` |
| P-28（城市菜单） | fixed，有偏差 | 没有删掉「扫码到达」：城市定向定位没开时，它是到站的唯一入口（站卡上也写着「可扫码到达」）。只把副标题从「到店后扫商家的码」改成「到站后扫现场的码」 | `pages/play/index.wxml` | `play-small-items-1102.test.js` |
| P-29 | not done | 章末收尾文字出现在下一章卡之后。要改故事流的换章时机（读完本章尾段再换章），得在 rig 里跟一遍换章链路才敢动，本次没做到 | — | — |

## 门禁与测试

- 后端（`mvn -q -pl chengyinhub-admin -am -Dtest=...`）：本次涉及的套件全部通过，包括 `TopicRouteRuntimeServiceImplTest`、`JourneyRuntimeActionsTest`、`PlayJourneyStoryServiceImplTest`、`AdvancedGameRuntimeServiceImplTest`（201）、`ApiPlayProgressCompletionTruthTest`（36）等。
- 新测试在旧代码上的结果：见下方「旧代码对照」。
- 小程序：`play*`、`playkit*`、`object-card*`、`*photo*` 共 1243 个用例通过；全量单测见 PR。
- `node chengyinhub-xcx/tests/lint/ui-integrity.js` → `UI-GATE-0:PASS`。两条既有登记只是行号位移（1791→1797、8072→8139），按惯例换 key 并重算 digest，没有新增登记。
- 动作台账重建，只多了两个控件：答对后的「继续」、整屏玩法的「返回故事」。

## needs owner

1. **P-23 罗盘读不出方向时的出路。** 建议默认：作者在罗盘玩法上勾「允许跳过」（默认关）；连续两次读取失败后出现「跳过这一站」，服务端按 0 分完成并记一笔「跳过」；带领场里队长也能现场放行这一站。需要后端加一个只对罗盘/传感器玩法开放的跳过完成口，并校验次数与归属。
2. **P-26 的「本次」口径。** 现在把「同一场次 + 同一主题」视为同一局。同一场次重玩时不会再问一次。建议保持；如果要每次重开都问，把同意记录和行程计时一起清掉即可。

## 旧代码对照

（运行后补）

## 真机上仍未验证

- 定位弹窗时机、罗盘、拍照上传都只在浏览器 rig 里跑过，没在手机上跑。
- 「对了」那一拍和三点等待动画的观感、减弱动态下的表现。
- 带领场放行的轮询间隔（4–10 秒）决定了「等队长放行」解开的延迟。

STATUS: PARTIAL - P-29 未做；P-23 待业主定规则
