# ROUND14 GAME — 同页开站、通关与扫码回调

状态：源码修复与前端行为测试已交付；全游戏运行时验收 **PARTIAL**。基线 `4aff0697d`，独立分支 `codex-play-map-20261105`；没有推送、合并、部署、生产访问或 DevTools 操作。协调器持有统一 VPS 构建、Java 测试和 rig，本文不把 Node 测试称作业务走查。

## 本轮实际修复

| 问题 | 失败证据与修复 | 验证边界 |
|---|---|---|
| 上一站结算后下一站需重开页面 | 生产 `_applyRouteState` 行为测试原先保留 `advancedPlay.show=true`，没有重读正文；现有统一路线投影在新完成上升沿关闭已结算玩法并重读 `/nodes`，后续重复轮询不再重复加载。普通节点加载和路线状态回读共用 `_nodesForRoute`。 | 新题在同页接到本站的实际 `startGame`→`onAdvancedSession`→`openChapterFull` 测试通过；完整 rig 点击链仍待 root 复跑。 |
| 末站完成但黑屏 | 真实任务局结束时 `quests=[]`，原 `_hasGenericProofFlow` 变 false，LINEAR 完成事实未应用；现在保留服务端 `runtimeScope=PLAYER` 识别，末站也重读原节点投影。 | 空任务列表的完成事实与重读行为已测；未伪造 done 或奖励。 |
| 互斥另一站仍被当作下一站 | 原 `computeNext` 对 LINEAR 总按 sortId 找未完成站，测试实际取 86 而服务端推荐 87；任务局现在只认服务端 ACTIVE 的推荐/当前 PLAYABLE 节点，结束局不再推荐未选站。 | 没把未选站标为完成；服务端原报告的 PLAYABLE 状态是否仍存在，需统一 Java 与冻结配置/rig 读回。 |
| 同页结局被完成卡/旧章节覆盖 | 重复 `openFinish` 原先跳过在途结局直接开完成卡；`rebuild` 原先会用上一章未读收尾替换结局章，测试复现 51→50。现在同局等待完整结局链，结局章不被后台节点回读/章节收尾逻辑覆盖。 | 生产方法完整执行：服务端 ending DTO→结局章（含视频地址）→继续读章→摘要→通关卡；无结局/读取失败也能进卡。视频实际解码由 VIDEO/root 验收。 |
| 迟到回调开错站/错局 | QUEST_START 原回调换号或换局后仍开站；扫码原生回调在隐藏、换局、换号、登录代次改变、组件销毁后仍发 scanned；全部先复现失败再修。 | 开任务回调绑定 owner/session/epoch；扫码绑定账号/登录代次/玩法 session/组件代次，双击只开一次，取消可重试；旧相机去重定时器不清新局状态。 |

调用范围：`index.js` 的路线投影、`loadData`、`computeNext`、`startGame` 与结局链；QR 组件及分发器透传既有 `kit.sessionId`。不增加引擎、依赖、接口、数据库列或支持开关，不改鉴权、资金/结算、风控、券或奖励幂等。

## 二维码与互斥后端证据

- 本轮运行既有 `r13-m7-scan-station.test.js`：4/4 通过。证明扫码作为文字/拍照主玩法的参与方式存成 4、主玩法与扫码共存及原限制未放宽，不证明实际二维码完成。
- 当前源已有 `AdvancedGameRuntimeServiceImplTest#qrStationScanProvesOwnCodeAndTaskSettlesOnceThroughGameplayOutcome`：错码、别站码、空码不写；本站码只记录参与；主玩法服务端 PASS；别人的局拒绝；作答和扫码重复只落一次结果。**本 worker 未执行 Java，需 root 在批准 VPS 运行这个精确目标。**
- 新增 `R14BranchPublicationRuntimeTest` 两个参数场景：真实 compiler→freeze→`GameplayTaskEngine`，包含空开场章、3 个站、空结尾章，两侧分别被选，检查同组冻结配置、章节结束、未选站锁定且未完成、重试状态不变。这里只 mock Mapper，不 mock 编译器/任务引擎；尚无 Java 执行结果。
- 未改后端生产类。若原 rig 冻结任务没有 `exclusiveGroup`，不能靠客户端或改旗标补完成，应对照新发布配置查具体编译/保存入口；已有发布局不自动改快照。

## 已执行检查

18 个指定 Node suite 共 **201/201 PASS**，零 fail/skip。逐条输出：`proof/r14-game/node-tests.txt`。其中新页面行为 11、新扫码行为 8；已有 R13 拒绝规则、可信完成、未知回执、路线版本、跨设备/登录隔离、章节收尾与音视频接口测试一起通过。

实际命令（cwd `chengyinhub-xcx`）：

```sh
node --test tests/unit/r14-game-session-flow.test.js tests/unit/r14-scan-session-fence.test.js tests/unit/r13-m1-branch-play.test.js tests/unit/r13-m1-chapter-task-games.test.js tests/unit/r13-m7-scan-station.test.js tests/unit/play-generic-flow.test.js tests/unit/r5-player-runtime.test.js tests/unit/play-route-page-behavior.test.js tests/unit/play-chapter-trail-p29.test.js tests/unit/play-player-projection-order.test.js tests/unit/play-finale-single-card.test.js tests/unit/play-cross-device-resume.test.js tests/unit/play-proof-receipt-contract.test.js tests/unit/play-login-generation.test.js tests/unit/playkit-scan-ar-contract.test.js tests/unit/playkit-scan-overlay-contract.test.js tests/unit/sensor-games-trust-boundary-c1.test.js tests/unit/k10-immersive-audio.test.js
```

- 额外核对结局动画/章节完整合同：`node --test tests/unit/play-chapter-story-blocks-contract.test.js` **84/84 PASS**（`proof/r14-game/story-contract-tests.txt`）。
- `git diff --check`：PASS。
- 暂存公开补丁 `gitleaks stdin --redact --no-banner`：no leaks found。
- 第一次提交由强制远端编译钩子返回 `exitCode=75, another_build_holds_lock` 拦下，没有绕过或盲重试；协调器随后接管 Java 测试提交并将它纳入 batch2 immutable snapshot，指示本 worker 仅撤下自己的新 Java 测试、先正常提交前端/报告。新 Java 文件保留在本工作树，root 负责提交/执行。
- `node tests/lint/ui-integrity.js`：FAIL，现有 `index.js` 的 U4-DYNAMIC 与 U5 两个行号锚点移动（理由、调用文本与债务数不变）；已报协调器统一刷新，不添加豁免、不弱化闸。
- `node scripts/uiaudit/build-action-ledger.js --check`：FAIL，台账位置漂移；由协调器统一生成审阅。第一次误用 `scripts/build-action-ledger.js` 得 MODULE_NOT_FOUND，随后已用真实路径重跑。
- 未运行完整 CI、Java/Maven/Docker/MySQL/Redis 本地进程、发布预检或原生 DevTools。新 Java 测试与现有 QR Java 测试仍需统一 VPS 执行。

## 逐游戏证据/缺项

下表的“配置测试”指实际执行的 R13 任务主题配置/拒绝测试，不等于运行该游戏；“旧走查”只引用 ROUND13-FULLGAME 与 CODEX-PLAY-MAP 的明确范围。本轮所有玩法的真实运行时/设备验收仍需 root 的统一新源码 rig 或真机；不从一种玩法推断其它玩法通过。

| capability key | 本轮源码证据 / 保留规则 | 剩余运行时证据 |
| album | 目录与原完成桥保留；本轮未逐游戏执行引擎 | 带章节规则的新局：成功、拒绝、重试、重复提交、同页下一站/结尾未走 |
| qaText | 新生产页面方法同页下一题/结尾链通过；旧走查 TYPE 完成后需重载 | 带章节规则的新局：成功、拒绝、重试、重复提交、同页下一站/结尾未走 |
| qaPick | 目录与原完成桥保留；本轮未逐游戏执行引擎 | 带章节规则的新局：成功、拒绝、重试、重复提交、同页下一站/结尾未走 |
| qaShot | 旧走查定位照片结算通过；本轮回归任务凭证与末站空列表 | 带章节规则的新局：成功、拒绝、重试、重复提交、同页下一站/结尾未走 |
| branch | 配置测试通过：章节任务中不能自行结算 | 保持拒绝；互斥站点组与此玩法不同，需两侧新局复测 |
| sort | 目录与原完成桥保留；本轮未逐游戏执行引擎 | 带章节规则的新局：成功、拒绝、重试、重复提交、同页下一站/结尾未走 |
| match | 目录与原完成桥保留；本轮未逐游戏执行引擎 | 带章节规则的新局：成功、拒绝、重试、重复提交、同页下一站/结尾未走 |
| classify | 目录与原完成桥保留；本轮未逐游戏执行引擎 | 带章节规则的新局：成功、拒绝、重试、重复提交、同页下一站/结尾未走 |
| estimate | 配置测试通过；仍用原主玩法/结果规则 | 带章节规则的新局：成功、拒绝、重试、重复提交、同页下一站/结尾未走 |
| pricePair | 配置测试通过；仍用原主玩法/结果规则 | 带章节规则的新局：成功、拒绝、重试、重复提交、同页下一站/结尾未走 |
| hidden | 配置测试通过；仍用原主玩法/结果规则 | 带章节规则的新局：成功、拒绝、重试、重复提交、同页下一站/结尾未走 |
| predict | 配置测试通过：需承接商家给答案 | 双身份商家出答案→玩家结算未走 |
| random | 配置测试通过；仍用原主玩法/结果规则 | 带章节规则的新局：成功、拒绝、重试、重复提交、同页下一站/结尾未走 |
| profile | 配置测试通过；仍用原主玩法/结果规则 | 带章节规则的新局：成功、拒绝、重试、重复提交、同页下一站/结尾未走 |
| note | 配置测试通过；仍用原主玩法/结果规则 | 带章节规则的新局：成功、拒绝、重试、重复提交、同页下一站/结尾未走 |
| objectCard | 配置测试通过；仍用原主玩法/结果规则 | 带章节规则的新局：成功、拒绝、重试、重复提交、同页下一站/结尾未走 |
| bingo | 原矩阵不允许任务完成证明，不改旗标 | 不把只读/无完成桥条目放开；普通主题证据另核 |
| steps | 配置测试通过；仍用原主玩法/结果规则 | 带章节规则的新局：成功、拒绝、重试、重复提交、同页下一站/结尾未走 |
| react | 配置测试通过：带分支/章节要求时继续拒绝手机自报成绩 | 普通主题需真机；本轮不解禁章节任务 |
| shake | 配置测试通过：带分支/章节要求时继续拒绝手机自报成绩 | 普通主题需真机；本轮不解禁章节任务 |
| quiet | 配置测试通过：带分支/章节要求时继续拒绝手机自报成绩 | 普通主题需真机；本轮不解禁章节任务 |
| compass | 配置测试通过：带分支/章节要求时继续拒绝手机自报成绩 | 普通主题需真机；本轮不解禁章节任务 |
| shout | 配置测试通过：带分支/章节要求时继续拒绝手机自报成绩 | 普通主题需真机；本轮不解禁章节任务 |
| countdown | 配置测试通过；仍用原主玩法/结果规则 | 带章节规则的新局：成功、拒绝、重试、重复提交、同页下一站/结尾未走 |
| stopwatch | 配置测试通过：带分支/章节要求时继续拒绝手机自报成绩 | 普通主题需真机；本轮不解禁章节任务 |
| photoCheck | 配置测试通过；仍用原主玩法/结果规则 | 带章节规则的新局：成功、拒绝、重试、重复提交、同页下一站/结尾未走 |
| check | 配置测试通过；仍用原主玩法/结果规则 | 带章节规则的新局：成功、拒绝、重试、重复提交、同页下一站/结尾未走 |
| d20 | 配置测试通过；仍用原主玩法/结果规则 | 带章节规则的新局：成功、拒绝、重试、重复提交、同页下一站/结尾未走 |
| typeIn | 配置测试通过；仍用原主玩法/结果规则 | 带章节规则的新局：成功、拒绝、重试、重复提交、同页下一站/结尾未走 |
| coin | 配置测试通过；仍用原主玩法/结果规则 | 带章节规则的新局：成功、拒绝、重试、重复提交、同页下一站/结尾未走 |
| dice | 配置测试通过；仍用原主玩法/结果规则 | 带章节规则的新局：成功、拒绝、重试、重复提交、同页下一站/结尾未走 |
| scan | 作者扫码配置4/4；新原生回调8/8；后台错码/重放测试源码已存在 | 本站码/别站码/错码/重试/无双奖励的 Java执行与真实扫码局未走 |

## root 接续顺序

1. 统一 VPS 跑新增 branch compiler/runtime 两侧、既有 QR 精确 Java 目标与相关发布测试，记录源码 SHA 和真实结果。
2. 以新发布局读回冻结的 `themeConfig.gameplayRuntime.quests` 互斥关系；分别走两侧，验证另一侧不可再开始、章结束、奖励差值一次、途中退出/重进。
3. 无分支照片→文字两站及有分支局都在同一会话进入尾声视频/音乐→摘要→通关卡；复跑慢网/失败/四 look 照片、扫码、错误页，并核对两类结局未被刷新覆盖。
4. QR 从正规发码入口取得本站码，实际错码、别站码、正确码、取消/重试/断网原回执、重复点击，独立读回任务/进度/奖励记录；手机声音、相机与权限仍需真机。

已打开并检查既有 `fix-shots/m3-u-12.jpg` 与 `m3-r-07.jpg`，确为有导航工具、正文空白的旧截图；没有产生新走查截图。没有改地图样式、接管共享 rig 或打开 DevTools。

STATUS: PARTIAL - 源码/Node已交付；branch/QR Java结果、运行时全游戏同页通关及原生设备验收待协调器统一执行
