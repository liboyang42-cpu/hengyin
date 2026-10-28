# F：play 12 路径三方解析 SOURCE 交付

已完成指定 12 个生产路径的解析后像；9 份与固定 master 逐字相同，3 份合并治理改动。未修改 A 的测试、共享接口、SQL、workflow 或 compiler 实际文件。compiler 必要跨 owner 补片及完整建议后像单独编码附带，交 root 审装。

## 固定来源与交付形式

- donor 的唯一父提交：`62d4e0bbf47efa2f73aa7e604f1dfe092518d923`。
- 治理来源：`1b71ae555a3141da948fa4e36ea68cb48a7b9b39`。
- 三方共同祖先：`51f05499c1523648a328f57bcd7927f6d9546003`，Git `merge-base --all` 实际返回此唯一 SHA。
- 分支：`codex/architecture-day-f-play-merge-62d4`。这是供体，不是真正的双父集成提交。
- A 的测试供体：`9358eda03e893a1f5c1b4bb815d8ff3979315edb`，只读其接缝，不修改测试。
- `OWNED-PATHS.txt` 是全部 12 条路径；root 解决冲突时应取供体的全部 12 份后像，而非只取相对 master 变化的 3 份。
- 每份完整源码均可通过 `git show <本供体完整SHA>:<OWNED-PATHS中的路径>` 取回，包括 9 份未变化文件。没有删除路径。
- `PREIMAGES.tsv` 列三版本 Git blob / SHA256（add/add 的共同祖先为 ABSENT）；`SOURCE-POSTIMAGES.sha256` 列全部 12 份后像。
- `owned.review.patch.b64` 解码为 master → 本供体 12 路径的完整差分，只有 3 条路径有实际 diff。解码字节数和 SHA256 见 `PAYLOADS.tsv`。

## 逐路径解析

| 路径末名 | 解析结果与冲突选择 |
| --- | --- |
| ApiAdvancedPlayController | 保 master 的 CheckReceiptProjection、`/receipt` 和 ServiceException.data 投影；合入治理 typed `AiProviderOutcomeUnknownException` 的原 520/intentUnknown 响应。提交后铸卡的调用次序不改。 |
| ApiPlayProgressController | master 后像 exact。保留原局 routeSessionId / expectedRouteVersion 向应用层传递、旧缺身份 overload 拒绝，保留治理薄控制器委托。原生逐 hunk 合并的结果也与 master 相同。 |
| PlayBasicCommandApplication | master 后像 exact。保 SCAN/ANSWER/GPS/SENSOR 原 receipt 入口、PlayOriginalRun、冻结答案与原实例进度；guardLogId 十进制投影及原局身份完整保留。 |
| PlayBasicIntentApplication | master 后像 exact。保 action-context.routeSessionId 的十进制 String；使用请求中的原 session 锁并核 member/activity/topic，Function<PlayOriginalRun, AjaxResult> 传递原局，拒绝已结束原局，不退回旧 Supplier / 当前局推断。 |
| PlayNodeCompletionApplication | master 后像 exact。保 instance-scoped 进度和重复读、shared run 拒绝、OrientScoring 同步、原 advanceOriginal 在奖励 member 锁之前的次序及原奖励约束。 |
| PlayNodeContext | master 后像 exact。保 D1：旧快照无 nodeAnswers 才沿旧行为，新冻结缺答案不补 live；共享 runtimeInstance/authorityEpoch 与原局内容归属校验保留。 |
| PlayPhotoCommandApplication | master 后像 exact。保原 photo receipt / 原局入口及缺原身份 overload 拒绝，沿原冻结内容与原实例写进度。 |
| PlayPreferenceApplication | master 后像 exact。保原 preference receipt / 原局 / expected version 与原冻结内容合同，不回退治理早期的非原局直调。 |
| PlayPuzzleCommandApplication | master 后像 exact。保 reveal 原局 receipt / 原冻结内容与进度实例，旧 overload 仍拒绝。 |
| GameRuntimeSessionMapper | 两边同名同签名的 selectDefaultMemberSessionsByTopic 只保一份；带入治理对默认实例/可见性不变的注释。其余 master 方法完整保留，不改 XML。 |
| TopicRouteRuntimeServiceImpl | 7 个冲突 hunk 手工结合，详见下节。合入既有 compiler/narrative/projection 职责拆分与治理只读、冻结、receipt 加固，同时保 master 原局/评分/完整属性定义功能。 |
| PlayNodeAccessService | master 后像 exact。在治理共有实现上保留 originalPlayInstance 精确进度读取 overload，空身份拒绝。 |

## Runtime 的七处冲突及非冲突治理合入

1. `advanceOriginal` 保 master 的原 session / 原局分支；模板探测改调治理 `publicationCompiler().probe(liveNodes)`，不直接实例化 compiler 私有构造器。
2. `sessionRules` 保 master 的 `FrozenAttributeDefinitions.rules(snapshot, session.getConfigVersion())`，完整冻结定义不能被旧解析降级。
3. `freezeJourney(..., configByTemplate, templatesById, sceneIndex)` 保 master 的场景参数入口，但委托既有治理 compiler 的同签名方法；移除 runtime 中被 compiler 接管的旧编译体，不保留两套编译权威。compiler 的必要复制语义见独立补片。
4. `seedCharacter` 保 master 增加的 frozenSnapshot 参数，不退回实时定义读取。
5. `seedCharacter` 调用和正文保 master 冻结属性初始化；治理抽取的其它 helper 照常委托。
6. `themeAction` 保评分锁后读取原 nodeId 的次序；合入治理 version 非空非负前置校验与原输入 receipt fingerprint。原节点、请求身份、CAS/事务次序不改。
7. replay 保 master 的完整属性定义、原快照传递；使用治理 compiler probe。原节点选择与开局初始化仍由原函数负责。

非冲突治理变更也一并保留：`queryState`、`queryNodeContentSnapshot` 的纯读/原内容优先；currentScope 不再借读入口刷新；`TopicRouteNarrativeProgress` 与 `GameplayActionProjection` 原实现委托；compile/freeze/derive 声明 API；arrival/gameplay/theme 的输入指纹、历史不充分 receipt 拒绝、写回失败检查以及原保存响应回放。master 的 TopicPlaySetup 门控、OrientRouteSettlement 的 lock/beforePersist/complete、原局 roll/reroll/settle/checkReceipt 与 advanceOriginal 全部保留。

这 12 份生产解析没有删改任何测试文件或 case/断言。A 的 17 测试仍由 A/root 解析；本包不能证明集成测试已经通过。

## 跨 owner：compiler 必须由 root 应用的完整补片

目标：`chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TopicRoutePublicationCompiler.java`。

- 之前：治理来源 1b71 的完整文件，SHA256 `c4d9cd869c79ed890cd7f0301a334c016e768810490f9354cfbdb8a0382bb788`。
- 建议之后：SHA256 `cc158451cea198fb0c5bddebc76591b50a390f625a2ea177f5637906516c7a2b`。
- `compiler-shared.review.patch.b64` 为 source 1b71 → 建议后像的完整 3 hunk 差分；`TopicRoutePublicationCompiler.review.java.b64` 为完整建议源码。它们是审装材料，没有在本供体新增/覆盖 compiler 实际源路径。
- 三个 hunk：导入 master 已有 FrozenAttributeDefinitions；六参 freezeJourney 解析根后、V1 分支前复制发布完整属性定义；非 V1 的原 journeyRules 赋值后再次复制，并在完整 attributeDefinition 已存在时不给三参 freezeKeys 传实时声明备用值。
- 这精确搬入 master runtime 原 `FrozenAttributeDefinitions.copyPublishedDefinition` / 完整定义权威语义；不改变治理 `declarationRows` 所使用的 captured/current 声明源。
- caller：本包 runtime 三参/四参 JourneyTemplateProbe/六参 sceneIndex 的 freezeJourney wrappers → compiler 对应 overload；runtime initialSession/ensureJourneySession/replay 继续沿原冻结入口。compiler 自己的 freezeCurrentPublication/freezePublishedJourney 也复用这同一六参实现。
- 不变：`withCurrentContent` 原 topic → chapters → nodes → templates → declarations 的 current 锁/采集次序；`freezePublication(topic, routeSnapshot, declarations)` 的原声明相等校验；最终四参 `JourneyInitialState.freezeKeys(..., topicId)` 的完整声明快照与 hash 内容来源。运行 state_json 不进入作者 hash。
- root 合并共享 `JourneyInitialState` 时还须同时保 master 的完整 attributeDefinition 消费与治理的四参冻结 API；本包不擅写该共享文件。

## caller / 共享接口接缝

- ApiPlayProgressController → 七个应用 owner；PlayEntryApplication、PlayNodeQueryApplication、PlayCompletionFacts 与 GuardArrivalEvidenceReaderImpl 沿既有 PlayNodeContext / PlayNodeAccessService 方法读取。本包保各原公开签名；PuzzlePlayServiceImpl 仍沿原 PlayNodeAccessService。
- PlayBasicIntentApplication → PlayOriginalRun → TopicRouteRuntimeServiceImpl 的 advanceOriginal/原 session overload；ApiAdvancedPlayController → IAdvancedGameRuntimeService / 原 AdvancedActionReceiptReader。
- root 的 ITopicRouteRuntimeService 三方解析必须同时保 master 的 advanceOriginal、checkReceipt、带 sessionId 的 roll/reroll/settle，以及治理的 queryState、deriveTemplateCluesForPublication、带 declarations 的 freezePublication 和唯一 queryNodeContentSnapshot 声明。该接口不在 F 写权内，本包只给必要合同，未覆盖实际接口文件。
- 治理新增 TopicRoutePublicationCompiler、TopicRouteNarrativeProgress、GameplayActionProjection 与其既有依赖必须从原 source 1b71 一并集成。本 donor 只以 master 为父，不是可脱离其它 owner 直接构建的完整集成树。
- GameRuntimeSessionMapper 的同名 SQL、master FrozenAttributeDefinitions、原 scope/锁/权限/金额/UNKNOWN/回执归属均不改授予或基线。

## 实际检查与未验证

实际仅做：固定公共 Git blob/差分读取、仅本 12 路径的三方 hunk 对照、9 份 master byte-exact 对照、冲突标记搜索、Git diff --check、两份补片各对固定前像临时 index 的 Git apply --cached --check、字节摘要及限定新增行敏感模式检查。不是源码解析、测试或编译。

测试、解析/import、Node/Python 控制、Java/Maven、DB/Redis/Docker、HTTP、CI、微信：全部 NOT_RUN。没有新增依赖、SQL、权限或调整任何堆/GC/线程/资源预算。

原 1b71 的绿色只覆盖原 SHA，不能转签本供体或 root 双父合并。新合并的全调用链、Spring 装配、真实编译/HTTP/清理及共享 helper 合并结果均待 root 统一验收；all_complete=false。
