# G 完整冻结 producer/source 包（ROOT_FINAL_CONTRACT_V3）

结论：本包补齐 G 所有生产端、正式创建 caller hunk 与配套测试源码。**全部测试/编译/Java/DB/HTTP/CI NOT_RUN，TG1/TG2 未验收、all_complete=false。** 不把旧绿色或源码摘要转签本包。root 唯一串行接入 H/F/E 的共享源码后再统一验证。

父链：公共 fa5c → G `edf731c15090d2289553dd7f966f6c7d8ec7f5e2`（RR13声明）→ `db2ec9417b3dfa4f4c632df1eff787a1e16855f1`（interactionType +1）→ `1718c569ffb9e09847a6199c85b27b2dafd56689`（collector +5）→ 本完整 producer 头。root 已收前两片，不整树覆盖；完整增量可按 db2→本头提取，已收 collector 可只取1718→本头。

已实际读取协调完整方案 ceb4、F `e3068c120af738b954b72afd22ceec953477b6d2` 的 CONTRACT/RUNTIME-CALLERS、E `56bc9324e1ebb7244130c6f9525a43262a046d88` 的 README/source-evidence 及最终 `4e29814c8e0257a53b6f7b2eb5c3b733203490d6` caller 源。F/E 生产文件未编辑。E 最终 current collector caller 与测试已交付并由 root exact 装配，保持 E 独占，G 不交一份旧 guard 覆盖它。

## 最终字段表（给 H，废止之前交叉建议）

不新增 `contentSnapshotOnly`，producer 不复制“是否需局”判定。保全原冻结 `journeyRules/journeyStory/nodeConfigs/nodeJourney/themeDefinition/themeConfig/opening` 供 H 复用原 helpers；已有局优先、新内容版仅读冻结事实、旧无标志原路径均由 H 保留。

| 原 JSON 位置 | 新字段与类型 | 合同 |
| --- | --- | --- |
| root | `stateKeySnapshotVersion:1`, `stateKeys:[]` | 完整作者定义；不是 session/character 运行值 |
| stateKeys 每行 | `topicId`, `key`, `stateKey`, `keyType`, `scope`, `label`, `initialValue`, `minValue`, `maxValue`, `displayType`, `color`, `icon`, `sortOrder`, `visible`, `persistScope`, `rulesJson` | `key==stateKey`；原 NULL 原样保留；不含技术 id/createTime/updateTime，沿 F 原读取顺序 |
| nodeAnswers[nodeId] | `contentSnapshotVersion:1`, `templateId`, `templatePresentation`, `nodePresentation`, `chapterAudioUrl` | 仅 fresh publication 追加，不回填 old V1。正模板 ID 与实际实体一致；合法非模板为 null+空 presentation，不能伪造 ID |
| templatePresentation | `id,title,description,imgUrl,players,usageLocation,requiredMaterials,duration,difficulty,ruleInstructions,merchantGuide,interactionType,questionName,questionImg,questionAudio,questionOptionMediaJson,questionA,questionB,questionC,questionD,storyText,storyImg,feedbackText,photoRequireDesc,audioUrl,audioDuration,medalName,medalStyle,medalImg,couponId` | 实际消费者需要的只读白名单；不是可整块公开的 HTTP DTO，券/奖牌为展示引用，不改变经济授权 |
| nodePresentation | `id,topicId,chapterId,templateId,merchantMemberId,name,description,address,longitude,latitude,imgUrl,hookText,cardHookLong,fragmentText,nodeTime,businessTime,sortId,unlockAfterNodeId` | 当前锁定原节点身份与展示字段；不含 checkinCode/xpValue/审核管理字段 |
| nodeAnswers 私密顶层 | 原答案/揭示/preference/sensor 字段保留；补 `hint1/hint2` | H 仍经原 hint/reveal/sensor/public projection，绝不把整个 nodeAnswers/presentation 直接输出 |

`ThemeStateKeyDeclarationSnapshot` 明确挑选 F 的作者字段，非 mapper bean 全量倾倒。`copy` 复制作者 bean，hash/capture 排除 ID/时间。`includingRuleAttributes` 保留完整已注册声明；原 journeyRules 同名 label 的优先级不变，但不再用两字段 bean 覆盖 type/range/persist 等；缺注册行的已有规则属性只按已归一的原 rules 推导完整定义，默认 NULL 不改写为策略。没有持久新键/新状态表。

审核 body 仍用 `topic.content.stateKeyDeclarations` 绑定原始作者声明（空声明沿原旧 hash 不额外加空字段），并同时原 hash 绑定 journeyRules；这不是发布 JSON 的额外同义数组。发布只用一个完整 `stateKeys` 数组。规则 label 的既有显示优先级在 freezeKeys 中处理，不能据运行值改作者 hash。

## 生产顺序及全部写 caller

1. `TopicPublicationService.prepareDeclarations`：原 caller 写事务/topic/content 锁内，原 Runtime 叶子派生 template clue → E.deriveOptionTagsForPublication → F.listCurrentDeclarations → 稳定 copy。模板 clue 用 root 已落地 `StateKeyRefs.declaredTemplateClues(JsonNode)` 唯一 regex/walk/label 序；无第二套工具规则。
2. 派生阶段仅延后“最终注册集合引用检查”，保留原配置/绑定/机制检查；该阶段本身不能发布。最终集合捕获后，E 两 List 重载完整验证；G 三参 freeze 对比当前声明并进入 final 模式，缺新 clue 直接拒绝、禁止 upsert，不允许先 hash 后悄增。
3. `prepareTopicBundle` 将同一声明 list 送 E 正式校验、Bundle 三参 overload/hash；`submitTopicBundle` 再以该 list 调用发布，最后 `assertDeclarationsUnchanged` 核对整条事务未新增声明。拒绝仍原事务回滚；无外部 I/O。
4. `publishDraftInternal` 简易发布、专业提交及供给重提全部经该顺序；`publishBeforeReview` 的 held/quota/status/square/shell 与 rowcount 顺序保留。
5. **批准/安全恢复** `publishApprovedRouteSnapshot` 只复制审核锁下当前声明，绝不重新派生；原 applyDecision hash/模板批准/CAS/回执由原完整审核事务保持。
6. 新建已发布主题的两直写分支（CmsTopicServiceImpl 原2554/2558）交 root 窄 hunk，改为 `persistCreatedRouteSnapshot(topicId)`，保留原各自 rowcount/error/XP等后续顺序。不硬改 requiresFullRuntimeSnapshot，不开启属性/机制。publication owner 准备原声明/引用/正式完整内容冻结后仍走原 persistRouteSnapshot。
7. 原 `TopicContentGraphAuthoringServiceImpl.persistTemplateInteractionRules` 仍走单参原编译口；实际写事务/parent→key fence 补齐。纯预览需要新自动声明时不能写库；旧普通预检走 E/StateKeyRefs 的只读投影。

新注入仅现成 runtime/state-key 服务或 parent Mapper，无新 managed type、HTTP入口、schema、依赖、权限/费率/奖惩/UNKNOWN 策略。新纯 helper 一个、collector 内短生命 CurrentContent 一个，不存跨请求状态。

## current reference 输入

collector 完整独立依赖片见 CURRENT-REFERENCE-CONTRACT.md。`currentForPublication` 原 parent→chapter→node→template 锁，拒绝缺失/跨 topic；`collectCurrent` 复用原 node/journeyRules/journeyStory/chapter parser，零重读。`collect(topic)` preview 保留。E 正式 caller 已在4e当前内容包交付，G 不覆盖。

必要 outcome caller adapter 仅交 review：`TopicRouteOutcomeReader.declaredRouteOutcomes(CurrentContent)`。旧 Long 入口普通读取保留，两个入口共享一个原 parser 闭包；当前分支图不能用普通旧 RR 的 node/outcome 声明。无新结果码、proof 或支持白名单。

## 必须串行应用的共享最小 hunk

- `shared-producer-ports.review.patch.b64`：原 ITopicRouteRuntimeService 两新默认入口只 throw，不返回默认成功；实现仅委托现有 compiler。签名：`void deriveTemplateCluesForPublication(CmsTopic)`、`String freezePublication(CmsTopic,String,List<ThemeStateKey>)`。同 patch 的 JourneyInitialState 仅新增 fresh-publication 四参 freezeKeys 序列化 overload，旧三参 legacy 和 H 的 visibleKeys 读取方法不动。
- `shared-created-publication.review.patch.b64`：CmsTopicServiceImpl 仅两正式创建持久化 caller，不含财务/发布内容其他 owner 代码。
- `shared-outcome-current-input.review.patch.b64`：原 outcome parser 的 CurrentContent 输入 overload。配套 `shared-formal-branch-input.review.patch.b64` 仅从 E 最终4e源将正式分支图输入接该 overload，普通 preview、原validator/gate/located错误闭包不改，root 唯一串行接入；不整包重放 E 已交 current caller。
- `shared-native-fixture.review.patch.b64`：ThemeRuntimeMySqlIT 仅 canonical 原 state-key 两迁移、真实 Mapper/注入与该类自有 fixture 清理；原普通34+参数展开24等原方法/断言不改，不给其声明新增 case。

这些文件是 review.patch，不是已应用外国文件的整类 postimage。root 与 H/E 串完再计算合成树的共享 Java postimage。原 C/V2/资金等 migration 字节未改、无 DDL 执行。

## 配套测试源码与实际验证边界

相对 db2 本包新增 29 个普通 @Test 声明；相对 fa5c G 累计43个。均 NOT_RUN，无 ParameterizedTest 新增/拆分、无 skip；原参数用例继续原样。文件与新增声明：

- `TopicRouteOutcomeCurrentInputTest` +3：同一 outcome parser 消费 current/ordinary 两输入、零 SQL 与原异常/cause，依赖 shared outcome adapter。
- `TopicStateKeyRefCurrentContentTest` +5：current/ordinary 分离，捕获后零SQL，preview不锁，原 block/chapter parseError、inactive，TX/parent/scope/source 拒绝。
- `TopicPublicationContentSnapshotTest` +6：真实白名单/秘密分离、合法无模板、坏身份、旧无标志不回填、check/requires/effects 冻结、opening/原 theme 配置事实保全；不另判定需局。
- `ThemeStateKeyDeclarationSnapshotTest` +4：全声明和 NULL、顺序/技术元数据、scope/重复拒绝、rules label 覆盖不抹完整定义及 fresh序列化标志。
- `TopicBundleReviewServiceTest` +2：作者声明更改拒绝、hash 后新增声明拒绝；旧原场景全部保留。
- `TopicRoutePublicationCurrentReadTest` +3：原 clue 真 upsert/精确读回/同 frame刷新端口、纯preview不写、最终声明不符拒绝；unit flags 不充当数据库锁。
- `TopicPublicationSizeEntryTest` +3：准备→hash→发布序、普通内容直写 typed caller、批准无派生；原8断言场景不删，仅装配原模型 rows/明确 mock 端口。
- `TopicPublicationBindingMySqlIT` +3（原6→9）：声明 RR restore/current hash/freeze；hash 后声明同TX rollback；普通题/无模板新建完整持久回读且 check/state/gameplay 不开启（一 @Test 内两独立 fixture）。原模板 RR case 已增强到实际 publication owner。复用原 controlled MySQL fixture/真实 mapper/原 canonical DDL；不是本轮已执行或完整 HTTP 审核验收。

`TopicContentGraphMySqlFixture` 原表/断言保留，补实际 state-key canonical迁移/真实Mapper/实际同数据源事务服务与真实collector parent；snapshot 也包含该既有表以检查回滚，未用空 Mapper 冒充拒绝保护。共同 unit wiring 仅在原 port 已明确 stub 时转接三参，或将 Bundle mock 三参映射其原两参 snapshot；不替 real Mapper/spy，不构造不存在的 snapshot/支付fact。

源码检查仅 git diff/文件摘要，非测试；未运行任何 Node/Python控制/Java/JUnit/MySQL/HTTP/CI。H 的全部运行消费者/旧局优先/无局判定测试属于其源包，E正式引用 caller/原诊断和F writer 真锁源码依赖必须一起合成。未合这些依赖，本头不宣称 standalone 可编译、可启用或TG1/TG2通过。

无业务数据迁移/回填。回退应作为同一producer+reader合同回退，不删除已存快照/自动声明/审核回执，不改历史hash制造匹配；旧发布/旧局无新标志继续原行为。本文及文件摘要不是最终业务验收。

共享 hunk 以标准 base64 保存：解码后按 REVIEW-PATCHES.sha256 核字节，再由 root 串行应用；不是整类替换。首次 git diff --check 对原 unified patch 的三个必要空白上下文行报 trailing-whitespace，源码无该问题；编码保存未改变解码补丁字节。没有运行 apply/test/compile 控制，最终只做 git diff 静态检查。
