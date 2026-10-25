# TG3 全游戏机制复用源码地图

观察源：`fa5c263440573992701263d6bee60830844a49b8`；不是运行验收。所有测试 **NOT_RUN**。本片只新增本文件；原 QA 三例及 README 的运输原字节不变。不改发布 owner、判定算法、费用、权限、证明白名单、schema 或客户端。

完整方案对齐：`ceb4e5f923091dc0fc5e5b6b7670d60c27bff85f` 的 `COORDINATOR/全游戏资源库与主题复用完整施工方案-20261025.md`（原 blob `52e71c9346c66a9716cfcea3b64ed9eef48a2a4e`）已读。完整目的为机制→内容库→原ID转换/绑定→审核发布冻结→独立实例；本文提供机制侧全量源，B/D/C/G/E各自交付不由本文冒领。

## 结论和共享接缝

当前 GameplaySupportMatrix 静态注册块逐一展开为 **42 个 key：32 types＋10 addons**，不是旧34条。它是通用结算能力表，不是42种完全相同的游戏协议。普通 completionProof 为真的只有 qaText、qaPick、sort、match、classify、scan 六项；31个别名条目标记 r5CompletionProof，实际 R5 主配置段为27个。R5 标记只有 outcomeRules.version=1、对应段 enabled 且模式匹配时才适用，不能把行标记当任意配置的完成许可。

已有共用链是：模板内容 → 真实节点 template_id → 原发布编译/内容冻结 → Advanced runtime 的同一动作 owner → 原个人/共享 route 证明与总命令回执。同一类型换主题/题面是换数据，不再生成 Java 判题类。qaPick 的两主题不同正确答案回归已经按此链写源，未执行。其余类型保留自己的输入和可信边界；传感器、真实上传、异步预测、题组、多人、Bingo 不应硬合成同一提交协议。

对 G/E/客户端 **无新签名**：
- 能力读取：GET /api/play/theme/support-matrix → GameplaySupportMatrix.view()，返回副本。
- 内容校验：AdvancedGameConfigValidator.validateAndNormalize；不是从本清单复制校验器。
- 编译：GameplayTemplateRules.resolveNodeConfigs/merge；真实节点与 actKey/quest/facility 身份保持原逻辑。
- 运行：POST /api/play/advanced/start、/action；读 /state、/leaderboard。actor 来自实际 principal；session/version/instance/epoch/原 receipt 不从模板继承。
- 普通扫码/GPS/旧单题仍走 /api/play/checkin、/arrive、/answer；这些不等价于 advanced 的不同 payload。
- 主题新建应选已有模板/机制并填内容；不因主题名生成新机制 key。模板创建/引用/采纳权限与资源目录是 B 的实体侧任务，本片不替代该 owner。

## 公共路径及冻结边界

下表中的短名均映射至本文末尾精确 path＋原 Git blob；行号只对本观察 SHA 有效。

| 段 | 真实调用及源码位置 | 复用时必须保留 |
|---|---|---|
| 编辑/采纳 | ApiTemplateController.draft/publish/adoptDraft；TemplatePublishServiceImpl.normalizeAdvancedConfigValue:1072 → Validator.validateAndNormalize | adoptSourceOnly 与原权限/内容审核；不让调用者填正确结果或 passed |
| 节点引用 | CmsTopicNodeMapper.xml:23/123/196 的 template_id；Compiler.templateEntities/JourneyTemplateProbe | 真实节点归属；不是字符串模板ID映射到任意节点 |
| 规则编译 | Rules.resolveNodeConfigs:146、merge:164/169；Compiler.compileTemplateRules | 将交互费用/次数/奖励规则绑定原 nodeId/actKey；不把题面判定复制到编译器 |
| 内容冻结 | Compiler.freezeJourney:540/566/572，内部冻结 nodeConfigs/nodeJourney/nodeAnswers；Route.nodeRuntimeConfig:2919 | 已发布/已开局快照、原版本、答案隐藏；live 模板更新不替换旧局 |
| 运行恢复/提交 | Runtime.requireInput、actInTransaction；Judge.evaluate:82 | 当前有效报名/活动窗口、node.topic、session owner、原实例/version/TEAM epoch；原请求指纹重放先于再次效果 |
| 纯本地判定 | Judge.evaluate 分派到本文逐项 owner | 只提出本局状态/分数/choice/evidence；不得由客户端自报服务端通过 |
| 证明消费 | Route.recordNodeResult:2765、recordGameplayOutcome:2819；共享 GameSession 对应原方法 | 原活跃 quest/operationNode/actor/instance；普通节点 verifiedNodes 与 pendingTaskEffects，随后 QUEST_RESOLVE 才消费并结算 |
| R5 | OutcomeRules.MAIN_GAMEPLAYS；Judge.serverGameplayDecision:302；Runtime 原 outcome owner/feedback | 最终结果冻结；原结算/归零挑战/恢复策略。旧 mechanicsReady 或 submitted 不能直接冒充可信成功 |

范围代号：
- **N**：以上模板→真实节点→冻结配置→runtime session 链。跨主题可复用同一代码；新主题/节点/局必须有自己的真实身份、状态和回执。共享局仍走原 TEAM owner。
- **T**：会员＋主题累计聚合；不宣称按 runtime session 隔离。
- **R**：真实节点＋业务日 round 与会员 bet；另有原局/原预测单关联。不是普通即时答案。
证明代号：
- **O**：普通通用完成桥已在 matrix 标记。
- **R5**：该行标记 R5，仍受上面的配置/结果可信约束。
- **P**：呈现/修改器，不是独立结算证明。
- **L**：已有 legacy/runtime 处理，普通通用桥未开放。无桥不等于没有实现，也不等于允许自行放行。

## 32 个 types：逐个展开

所有字段是源码中的主要允许配置，不是完整 JSON schema；大小写、边界、未知字段和效果仍只听原 Validator/发布闸。

| key → section / mode | 内容配置、校验位置（Validator） | 实际动作 → 唯一运行/判定 owner | 完成证明和可复用边界 | 已有测试源（未运行） |
|---|---|---|---|---|
| album → album | images/url/line；:1776，R5另走上传内容 | SUBMIT_ALBUM → Runtime.submitAlbum:2139 | N；普通P，R5要求真实自有上传；旧相册展示不是上传证明 | RuntimeTest.r5PhotoAndAlbumUseVerifiedServerEvidence |
| qaText → qa / TYPE | title、answerText、maxTries；:1220 | SUBMIT_QA → Judge.submitQa:664 | N；O/R5；服务端原答案匹配；与旧 validationMethod=1 的字符串合同分开 | JudgeTest；PersonalTest；新增 QaReuseTest |
| qaPick → qa / PICK | options[id,label,correct]、judge、multi、maxTries；:1220 | 同 SUBMIT_QA → submitQa；多选 submitQaMulti:758 | N；O/R5；单/多选共享 owner。judge=false 是已知单选完成，非知识正确性；普通免费重答约束保留 | RuntimeTest QA 单/多选/非法/重放；QaReuseTest 两主题 |
| qaShot → qa / SHOT | imageUrl 相关配置、题面；:1220 | SUBMIT_QA → Judge.submitQa；提出 PhotoEvidence，Runtime 核原上传 | N；L/R5；R5核真实自有图片，不能冒作文字/选项正确性或 photoCheck 视觉审核 | JudgeTest.photoQaReturns…；RuntimeTest 照片证据 |
| branch → branch | steps/options/nextStep/startStep、effects；:605 | CHOOSE → Runtime.choose:1444 | N；L；原步图/终点推进。R5已结算反馈 CHOOSE 是 outcomeFeedback，非另一个 branch 主证明 | PlayKitBranchRandomPredictTest.branchShipsOnlyTheStepYouAreStandingOn |
| sort → sort | items、answerOrder、maxAttempts、effects；:1373 | SUBMIT_SORT → Judge.submitSort:842 | N；O/R5；完整集合和顺序；普通 maxAttempts=0 | RuntimeTest 排序；PersonalTest 题组；MatrixRuntimeTest |
| match → match | left/right、pairs、effects；:1426 | SUBMIT_MATCH → Judge.submitMatch:903 | N；O/R5；原左右集合一一匹配；不接受局部/伪造 passed | RuntimeTest 连线；PersonalTest 题组；MatrixRuntimeTest |
| classify → classify | bins、items、answer、effects；:1489 | SUBMIT_CLASSIFY → Judge.submitClassify:968 | N；O/R5；每个原 item 的精确分箱 | RuntimeTest 分类；PersonalTest 题组；MatrixRuntimeTest |
| estimate → estimate | min/max、answer、tolerance、unit/reveal；:976 | SUBMIT_ESTIMATE → Judge.submitEstimate:465 | N；L/R5；HIT 才成功，CLOSE 的安慰本局分不等于通过 | RuntimeTest/PlaykitPayloadTest；Judge 原判定 |
| pricePair → pricePair | 当前 items[id,name,image,correct]、maxTries；:1030 | SUBMIT_PRICE_PAIR → Judge.submitPricePair:525 | N；L/R5；历史名已为图片猜选，非金额比价算法；旧 price 数组由原 retireLegacyPricePair 禁用 | RuntimeTest/PlaykitPayloadTest |
| hidden → hiddenObject | image、hotspots[id,x,y,r]、maxTries；:1090 | SUBMIT_HIDDEN_OBJECT → Judge.submitHiddenObject:577 | N；L/R5；坐标命中、foundIds 去重；全部原热点才完整成功 | RuntimeTest.r5HiddenObjectKeepsPartialHitsPendingAndOnlyWholeGameSettles |
| predict → predict | question/options、closeAtHour、revealDays/revealHour；:1166 | SUBMIT_PREDICT → Runtime.submitPredict:1916；PlayPredictServiceImpl.settle；Runtime.synchronizePredictSession | N＋R；L/R5；投注只是原 bet。冻结揭晓时间后核原 round/bet/actor/局；早揭晓不提前完成，闭局不换新局 | RuntimeTest 三个 r5Predict…；PlayKitBranchRandomPredictTest |
| random → random | items[id,weight,content]、drawCount；:567 | DRAW → Judge.draw | N；L/R5；服务端抽取、原抽数、抽过不再抽；matrix.addon 还将 random 当随主玩法附加 | PlayKitBranchRandomPredictTest.randomShipsHowManyDrawsAndWhatYouGotButNeverTheBox |
| profile → profile | questions[text/pick]、options、required/avatar；:1635 | SUBMIT_PROFILE → Runtime.submitProfile:2044 | N；L/R5；原必填/审核/状态effects；偏好资料不是客观答题正确性 | RuntimeTest/PlaykitPayloadTest |
| note → note | prompt、maxLength、presets、showPrevious；:1800 | SUBMIT_NOTE → Runtime.submitNote:2203 | N；L/R5；原文字安全和持久 note；不能复制为 QA 或跳过审核 | RuntimeTest/PlaykitPayloadTest |
| objectCard → photoCheck / CARD | requirement、minConfidence、maxTries、fallback、card样式；:1723 | SUBMIT_PHOTO_CHECK → 原 actPhoto/submitPhotoCheck:2147；finishPendingCardMint:931 | N；L/R5；共用 photoCheck，真实PASS后待铸卡；TX后铸卡不是新的完成凭证或备用默认成功 | RuntimeTest 原 photo/card 组；PlaykitPayloadTest |
| bingo → 空 section | topic.complete_rule_json.bingo；TopicCompletionRulePolicy | 节点完成 → PlayNodeCompletionApplication:524 → PlayBingoServiceImpl.onNodeDone:71 → BingoCells.evaluate | T；matrix P；真实已完成节点/时间累计，BingoCard 位图与奖励CAS。不是配置空就无实现；不支持任意外部事件条件 | PlayBingoArrivalFactTest；PlayKitBingoRuntimeTest |
| steps → steps | goal、eyebrow、xp；:902 | SUBMIT_STEPS → Runtime.submitSteps:1797 → VerifiedWeRunService（R5） | N；L/R5；会员/密文/水印/业务日核验后达标，不接收客户端自报步数完成 | RuntimeTest.r5StepsRejectsOtherIdentityThenFreezesOnlyVerifiedGoal |
| react → reaction | rounds、goalMs；:2441 | START_CHALLENGE＋SUBMIT_REACTION → Judge.submitReaction:1294 | N；L/R5/原归零挑战；服务器 attempt、真实用时和最快轮判定。六项自报设备边界仍禁止资金权益 | RuntimeTest reaction 原挑战/计时/重放组 |
| shake → ballShake | goal、timed、seconds；:2458 | START_CHALLENGE＋SUBMIT_BALL_SHAKE → Judge.submitBallShake:1334 | N；L/R5/挑战；hits 与真实耗时可行性、达标线；不升级可信度 | RuntimeTest.ballShakeRejectsMoreHitsThanTimeAllows、challengeBall… |
| quiet → quietHold | seconds；:2476 | START_CHALLENGE＋SUBMIT_QUIET_HOLD → Judge.submitQuietHold:1357 | N；L/R5/挑战；服务器时钟＋heldMs；自报设备限制 | RuntimeTest 原 quiet 组；PlaykitPayloadTest |
| compass → compass | bearing、tolerance、holdSeconds、hint；:2488 | START_CHALLENGE＋SUBMIT_COMPASS → Judge.submitCompass:1380 | N；L/R5/挑战；原目标/角度域/最短圆弧容差；自报设备限制 | RuntimeTest compass 域/环绕/一次结算组 |
| shout → shout | seconds；:2510 | START_CHALLENGE＋SUBMIT_SHOUT → Judge.submitShout:1412 | N；L/R5/挑战；原 attempt/heldMs/真实用时；自报设备限制 | RuntimeTest shout start/held/一次/失败组 |
| countdown → countdown | seconds、doneText；:2522 | SUBMIT_COUNTDOWN → Judge.submitCountdown:1429 | N；L/R5/挑战；服务器实际等候时长，不认改设备时间 | RuntimeTest.countdownRefusesToFinishEarly、countdownCompletesOnceTheClockRanOut |
| stopwatch → stopwatch | targetSeconds、toleranceMs、tries；:2535 | START_CHALLENGE＋SUBMIT_STOPWATCH → Judge.submitStopwatch:1442 | N；L/R5/挑战；原 attempt 单次消费、stoppedMs/用时复核；自报设备限制 | RuntimeTest 原 stopwatch 组 |
| photoCheck → photoCheck | requirement、minConfidence、maxTries、fallback/frame；:1723 | SUBMIT_PHOTO_CHECK → Runtime.actPhoto/submitPhotoCheck | N；L/R5；原图核验→锁外模型→原结果入账；R5仅PASS，不以UNKNOWN或FALLBACK_PASS作可信证明 | RuntimeTest.r5PhotoMustVerifyExistingUploadBeforeAccepting、r5PhotoAndAlbum… |
| check → check | checkId、tier easy/medium/hard、mods、条件/effects/bargain；:2122 | ROLL_CHECK/REROLL_CHECK/SUBMIT_CHECK → Runtime:551 → Route/Shared GameplayCheckEngine | N；L/R5；原服务端骰据/重掷及费用；tier不是客户端任意DC。不能以 d20 动画结果替代原 check receipt | RuntimeTest；现有 check/Outcome 源码测试 |
| d20 → diceRoll / d20 | dc、modifier、rollMode、success/fail文案；:2388 | ROLL_DICE → Judge.rollDice | N；L/R5；与d6同一owner；服务端抽值、原dc/优劣势；独立于check技能判定 | RuntimeTest.rollDiceReturnsServerPipsAndKeepsThem；PlaykitPayloadTest |
| typeIn → typeIn | target、caseSensitive、tries、seconds；:1835 | SUBMIT_TYPE_IN → Judge.submitTypeIn:1077 | N；L/R5/挑战；目标文本/原次数；R5时长来自原timer，不把旧seconds偷作新deadline | RuntimeTest.r5TypeInIgnoresLegacySecondsWithoutTimer |
| coin → coinFlip | heads/tails 内容；:2366 | FLIP_COIN → Judge.flipCoin | N；L/R5；服务端随机且保留首次结果；完成随机动作不代表答对知识题 | RuntimeTest.flipCoinKeepsTheFirstSideOnRepeat；JudgeTest |
| dice → diceRoll / d6 | diceCount=1或2、六面内容；:2388 | ROLL_DICE → 同 Judge.rollDice | N；L/R5；不复制第二套掷骰/重放算法 | RuntimeTest.rollDiceReturnsServerPipsAndKeepsThem；PlaykitPayloadTest |
| scan → scan | kind TEXT/VOICE/IMAGE/OVERLAY/NONE、reply/audio/image、arMode；:1304 | SUBMIT_SCAN → Runtime.submitScan:2010；普通 /checkin 原 code guard | N；O（validationMethod=4），非R5主段；真实 node.checkin_code 相等，AR只展示；不能把模型/图片加载当扫码 | MatrixRuntimeTest；RuntimeTest 扫码/overlay/AR；PersonalTest |

## 10 个 addons：逐个展开

addon 表的 category=PRESENTATION/requiresProof=true 不表示全部只读：下列 owner 有自己的状态。普通结算必须附着可信主证明；blindTaste/dailySign 仅在对应 R5 合同中才有独立结果。

| key → section | 内容配置、校验位置（Validator） | 实际 owner / 公开动作 | 完成/状态及复用边界 | 已有测试源（未运行） |
|---|---|---|---|---|
| timer → timer | durationSeconds、timeoutResult；:554 | Runtime.deadline:1437/原 expireIfNeeded，无独立完成提交 | N；P；原开局服务器deadline，重入不重置；超时终态不是成功证明 | RuntimeTest timer/timeout 组 |
| leaderboard → leaderboard | metric、scope ACTIVITY/TOPIC、limit；:684 | GET advanced/leaderboard → Runtime.leaderboard | N；P；原局成员/活动或主题范围读；不新造排行榜写owner | RuntimeTest 原 leaderboard 组 |
| multiplayer → multiplayer | mode SEQUENTIAL/ROLE_BASED、players、assignment、roles、turnOrder；:697 | ASSIGN_ROLE/COMPLETE_UNIT → Runtime.assignRole/completeUnit；原TEAM owner | N；P；必须真实活动/队伍/当前成员/角色；同主题模板不能复制团队身份 | RuntimeTest.multiplayerTemplateInSelfPlay…、WithoutTeam…、runtimeViewDoesNotExpose… |
| timeWindow → timeWindow | openFrom/openTo、跨夜时段；:773 | Runtime 原开放窗口 gate；SUBSCRIBE_TIME_WINDOW → subscribeTimeWindow | N；P；订阅仅原状态标记，不是完成；服务器判时，不因页面重入绕过 | RuntimeTest.timeWindowGateFailsClosedOnMalformedClockText |
| blindTaste → blindTaste | title/steps/hint/image、options、answerKey；:795 | SUBMIT_BLIND_TASTE → Judge.submitBlindTaste:423 | N；普通附加需主证明；R5可原答案判定；重复命中不再加分，不等于branch | RuntimeTest/PlaykitPayloadTest |
| silentOrder → silentOrder | title、rule、limitSeconds；:827 | 原 runtime 投影；没有独立 SUBMIT_SILENT_ORDER | N；P；文案/现场提示不校验客观完成，也不阻断原 completeGate | RuntimeTest.musicCornerAndSilentOrderDoNotGateCompletion |
| diyName → diyName | title、maxLength、suggestions；:839 | SUBMIT_DIY_NAME → Runtime.submitDiyName:1771 | N；L附加；原会员openid内容安全检查/保存name；不能当客观答题 | RuntimeTest/PlaykitPayloadTest |
| musicCorner → musicCorner | trackName/audio/durationSeconds；:866 | 原 runtime 音乐投影，无独立完成 action | N；P；音轨时长不是通关计时，不能加入relax改变难度 | RuntimeTest.musicCornerAndSilentOrderDoNotGateCompletion |
| dailySign → dailySign | poems、每首lines；:917 | CLAIM_DAILY_SIGN → Runtime.claimDailySign:1832 | N；普通附加；R5服务器日期＋原局确定选诗，原日重试不换签；非客户端随机答案 | RuntimeTest/PlaykitPayloadTest |
| slowTask → slowTask | startLabel/waitHint/unlockText/waitDays；:949 | START_SLOW_TASK/CLAIM_SLOW_TASK → Runtime:1858/1873 | N；L附加；原服务端startedDate、天数门槛、一次claim；不因模板复用复制等待完成 | RuntimeTest/PlaykitPayloadTest |

## 发现入口与真实受限项

原 MatrixTest.tableCoversTheRealCatalogAndCannotBeChangedThroughReadback 已从实际小程序 catalog 源核32个types；MODIFIERS仅4个；blindTaste/dailySign在ALL作为形式；silentOrder/diyName/musicCorner/slowTask保留服务端存量但原作者端已不提供。故后端10addons不等于前端10个作者按钮。本片不能以“全部可发现”为理由恢复已裁定退场入口；D负责本轮真实目录与既有入口接线，root合成分类索引。所有行的共同作者入口、采用与绑定链在前述表给出；本片不声称已读取实际上架库存。

可直接交root合成的分类：
- **已共享机制**：上表的实际 handler/进度owner已经复用，同类内容无需主题专属代码。
- **受限模式/不同协议**：N/T/R、O/R5/P/L明确区分；device/上传/供应商结果/团队事实各守原边界。
- **目录/证明差异**：compare下面单列；存量addon的作者可见性按原政策保留，不能误写为“缺handler”。
- **运行/库存UNKNOWN**：本轮未执行、实际上架数量/设备行为/部署数据/供应商链均不由源映射证明。没有把未知补成支持或把缺测试判BUG。

## 目录外的真实相邻机制及已证实差异

| 条目 | 已读源码事实 | 最小接缝/处理，不扩大证明 |
|---|---|---|
| compare（对照找差异） | Validator.validateCompare:1558、Judge.SUBMIT_COMPARE/evaluate:108、submitCompare:1024 和 RuntimeTest 原对照用例实际存在；当前42行 matrix 没有 compare；OutcomeRules.MAIN_GAMEPLAYS 也没有它；verifyObjectiveAnswers 允许的题组不是 compare | 统一目录不能说它“未实现”，也不能默认升级为普通或R5结算。若要作为可发布主玩法供选择，应由 A/G/E 同时明确目录行、原配置、证明消费者与发布约束；不能只 add 一行（add 会默认给 effectSlotSupport）就宣称已接通。本片不加白名单 |
| GPS/旧单题 | Matrix.legacyProofs:50 与 basicCompletionKind:94；ApiPlayProgressController /arrive、/answer | 已有入口继续复用；旧 validationMethod=1/3 的 answer 字符串与 advanced optionId 不等价。GPS 是基本完成证据，不是42行中的独立小游戏 |
| 偏好题组 | ApiPlayProgressController.preference/submitPreference → PlayPreferenceApplication；与 profile.questions、qa.PICK 是不同 caller | 不以都带 options 就并入客观判题；本片未改偏好算法/标签/权利，不把仅看HTTP委托称为全链已核验 |
| backgroundMusic | Validator.validateBackgroundMusic:883；明确禁止 enabled 作为新玩法段 | 原气氛音轨复用；与 musicCorner 不同，不凑成第43个注册玩法 |
| present/roleViews/requires/effects/variants/clues | Validator 原各 helper；规则 owner 仍原主题/角色与状态边界 | 呈现/条件/状态效果/容错，不创建另一个可任意提交的主玩法；variants.relax 不可换题面/答案/奖励 |
| check.bargain | Validator.validateBargain:2160；仅原 check 开关、有限 bonus 与原代价效果 | 仍属 check；不把“交易”名字当成可增加现金/券/积分的授权 |
| 普通附加与R5反馈 | Matrix.addon(section) 对 branch/random 也返回true；Runtime.outcomeFeedback:3262 | branch/random 的辅助身份与R5主结果/反馈要分开；R5反馈不能重跑已冻结主结果 |
| 多机制同时 enabled | Judge.mechanicsReady:190 与 sharedMechanicsReady:179；Judge.judgedOutcome/passedFact/serverGameplayDecision | 老 completeGate 的“走完/已提交”不总是答对。R5主段与普通题组各有完整性约束；不能把 enabled 段随意混搭后调用一个布尔 passed |

这里的 compare 是**实际目录/桥接覆盖差异**；不是以未运行测试证明一个线上BUG。其余限制是现有合同，不是建议放宽。“游戏都可复用”可覆盖配置和运行代码复用；每种玩法进入哪一种发布结算路径仍需其原可信证明。不能为了全选可用把 NO_BRIDGE 改成 PROOF。

## 已有测试源和本次新增源码

简写对应：
- RuntimeTest = `AdvancedGameRuntimeServiceImplTest`；JudgeTest = `AdvancedActionJudgementTest`。
- PersonalTest = `PersonalGameplayRuntimeServiceTest`；MatrixRuntimeTest = `GameplaySupportMatrixRuntimeTest`。
- PlaykitPayloadTest = `PlaykitActionPayloadContractTest`，用 source-controlled playkit-server-actions.json 走真实 act，并非客户端单方声明成功。
- PlayKitBranchRandomPredictTest = `PlayKitRuntimeBranchRandomPredictTest`。
- QaReuseTest = 本次已交 `GameplayQaPickTemplateReuseTest`，三个固定@Test；原所有测试和断言不改。
- 原 `GameplaySupportMatrixTest`、`GameplayReskinFrozenThemeComparisonTest`、`GameplayTemplateRulesTest` 保留。这里列测试是源码入口，不宣称所有机制都有同强度/同环境覆盖。

新增 QA 三例使用真实 Validator、Compiler/Rules、Runtime/Judge、个人 Route 证明/QUEST_RESOLVE；不复制判题算法。两主题不同内容共用 owner，错答无证明、状态隔离、发布后live编辑不替换快照、跨局/跨actor/改payload拒绝、原回执幂等保留。unit Mapper doubles 不等于真实数据库/HTTP或并发回滚证据；共享局/异步预测/设备/供应商/真实存储的运行假设仍待 root 最终同源统一回归。本片不运行这些测试，也不为凑42项另造42套假判定fixture。

## 推荐落地顺序（复用现有 owner）

1. **统一发现入口**用现有 support-matrix 和真实模板资源目录：key/section/mode 是机制标识，模板/题目/图片是内容；显示已有 unsupported/PROOF_REQUIRED/CHALLENGE_ONLY 理由。不要在每个新主题自动发明另一种同义玩法。
2. **统一引用与版本绑定**沿现有 template_id、原发布编译和开局快照；内容可在不同主题采纳/引用，进行中的原局仍用原快照。后台模板owner B 和发布owner G/E 分工保留，不复制新引擎/表/SPI。
3. **统一调用、保留异构输入**复用现有 advanced action 分派及普通 /checkin、/arrive、/answer；真实上传/模型/微信步数/预测回合/Bingo沿各自事实owner；没有一个可安全替代它们的通用客户端 passed 字段。
4. **扩展有真实缺口的桥**只对明确要进入某发布路径的机制成组接目录＋validator＋原事实＋消费者/回归，例如 compare；先保原禁用边界，不能靠 UI 显示可选或新增能力列宣称结算已完成。

这批 A 交付为已存在机制映射＋QA真实复用回归源码。不存在必须为“统一”先建全新引擎的源证据。产品入口/模板库的最终组合由 root 合成，不在本片跨owner改写。

## 源码身份

下列均在观察源父提交读取，Git blob 为精确原内容身份。短名只用于上表；不是将这些文件复制到交付目录。行号若后续合成变化，以 path＋symbol＋blob 对照。

| path / 表中短名 | Git blob |
|---|---|
| `chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/GameplaySupportMatrix.java` | `6da27823c30b8b7c2f16f8b7446bef4c70531e8a` |
| `chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/GameplayTemplateRules.java` | `b49ed58945f1d31bb097babef3fc1ef59e152b18` |
| `chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/AdvancedGameConfigValidator.java` | `234e3032d719cf2baf685fe9d6b4ae9ecf811da7` |
| `chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/GameplayOutcomeRules.java` | `bf0e59e40b3a970eba3b0ad8252c58ae816b9e02` |
| `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/AdvancedActionJudgement.java` | `f9188528d46ec24819c4f7c0b70d328603208915` |
| `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/AdvancedGameRuntimeServiceImpl.java` | `3d182681070436e5fce80fdfe6f54371e16dd793` |
| `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TopicRoutePublicationCompiler.java` | `8c4b7d36b59fb7b864cd38a96d9efe57cc21f2a6` |
| `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TopicRouteRuntimeServiceImpl.java` | `ac15bc6c9dbae47f00e2fa655aa82dc6780e8a1e` |
| `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/GameSessionRuntimeServiceImpl.java` | `c669a2ea4c7d4ad705976ddcf14a1e70c7ec1830` |
| `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TemplatePublishServiceImpl.java` | `6fef172a5b8214071f59a0afc427ee0daf989199` |
| `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/PlayBingoServiceImpl.java` | `01ac54b77838fe60ff9840b6bb93253cca5e22c7` |
| `chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/BingoCells.java` | `daf9cb878534f2bf2377de5fe02633271519c18f` |
| `chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/BingoCard.java` | `9b0b1fff545984bb8d09899f6807a4241fd308b8` |
| `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/PlayPredictServiceImpl.java` | `868978b79975987769223812cf64270d1c6c3bd6` |
| `chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/VerifiedWeRunService.java` | `a5b055800a9e2bd3094a9c6c75ed5e80393f4ddd` |
| `chengyinhub-system/src/main/resources/mapper/business/CmsTopicNodeMapper.xml` | `e0155d5e31a0bc5cc06ec7da6ed1574f0a748bcf` |
| `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiThemeRuntimeController.java` | `577a065640735cbd4580904f3e24fdbe1638f1cb` |
| `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiAdvancedPlayController.java` | `e86afcf9befdc4836be50d76d6b609b7e01aa711` |
| `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiTemplateController.java` | `553200ebb2c37b0ebf069219cd35af2b800aa7ad` |
| `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiPlayProgressController.java` | `e79f10d8a3de37f93990ee8932a3973a73e73f2a` |
| `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiPredictSettleController.java` | `148df477a9d903049fdb1aeb5e7d6f710198f754` |
| `chengyinhub-admin/src/main/java/com/chengyinhub/web/service/play/PlayNodeCompletionApplication.java` | `817f2bdeb0dee1fb2fcfe72c7f7f99e904db54b8` |

交付状态：新 QA 源码/本文已写；公共发布、Java/JUnit/MySQL/HTTP/Node/Python/CI 全部 **NOT_RUN**。没有把任何旧green继承为本片结果；没有执行DDL或供应商调用。公开开源参考对照由root统一进行，本文不冒充已完成外部比较。
