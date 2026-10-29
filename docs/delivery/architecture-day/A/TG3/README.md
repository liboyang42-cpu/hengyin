# TG3：复用既有 qaPick，不按主题新增判题实现

结论：公共 parent `fa5c263440573992701263d6bee60830844a49b8` 已有同一 qa/PICK 判题 owner 和能力导出。新主题应创建/采用内容模板并绑定节点，不创建 qaPick2、主题专属 Controller、能力表或新的判题器。本片新增真实组件复用回归源码和此用法清单，生产源码零修改；不是 Java/HTTP/MySQL/审核发布验收通过。

## 原生产链与共享签名

| 阶段 | 实际源码符号 / 接口 | 原合同 |
|---|---|---|
| 能力发现 | `ApiThemeRuntimeController.supportMatrix`；GET `/api/play/theme/support-matrix` → `GameplaySupportMatrix.view()` | 已有 schemaVersion=1；返回 deepCopy；qaPick → section=qa、mode=PICK。不是客户端声明即授权 |
| 模板编辑/采用 | `ApiTemplateController.draft/publish/adoptDraft` → `TemplatePublishServiceImpl.saveOrUpdateDraft/publishTemplateWithTransaction`；`normalizeAdvancedConfigValue` → `AdvancedGameConfigValidator.validateAndNormalize` | 原 owner/角色、内容审核和媒体检查不变。adopt-draft 采用已有配置并受控补回服务端答案，不复制机制代码 |
| 节点绑定 | `CmsTopicNode.templateId` → `cms_topic_node.template_id`；`TopicRoutePublicationCompiler.templateEntities/JourneyTemplateProbe` | 模板 ID 来自本主题真实节点，按真实 ID 批读；不是作者 JSON 自报实现类 |
| 编译规则 | `TopicRouteRuntimeServiceImpl.compileTemplateRules` → `TopicRoutePublicationCompiler.compileTemplateRules` → `GameplayTemplateRules.resolveNodeConfigs/merge` | 模板费用/次数/奖励规则绑定真实 nodeId、actKey 和 tpl_node_<nodeId>；原物资/作用域/槽冲突校验保留 |
| 冻结内容 | `TopicRoutePublicationCompiler.freezePublication/freezeJourney` | 冻结 nodeConfigs、nodeJourney、nodeAnswers；必要内容副本隔离是数据复制，不是复制算法。审核与发布身份闭环仍由 G 的 TG1/TG2 负责 |
| 运行入口 | `ApiAdvancedPlayController.start/action/state`；POST `/api/play/advanced/start`、POST `/api/play/advanced/action` | actor 从实际 principal 派生；原节点/主题、当前资格、会话 owner、版本和原实例检查不变；action=SUBMIT_QA |
| 唯一 PICK 判定 | `AdvancedGameRuntimeServiceImpl.actInTransaction` → `AdvancedActionJudgement.evaluate(SUBMIT_QA,...)` → `submitQa`（多选再调 submitQaMulti） | 读取本局 config_snapshot；客户端只提交 optionId/optionIds，不能提供正确答案或完成事实 |
| 引导题组复用 | `TopicRouteRuntimeServiceImpl.verifyGuidedRouteAnswer` → `AdvancedActionJudgement.verifyObjectiveAnswers` → 同一个 `submitQa` | qa/sort/match/classify 的整组校验；拒绝缺题及 passed 标记；题组不是另一份 PICK 算法 |
| 原完成证明 | Advanced runtime 的 `recordNodeResult` / `recordSharedRouteResult`；R5 使用 `recordGameplayOutcome` / `recordSharedGameplayOutcome` | 正确判定不等于直接完成章节。个人路径在真实原任务实例内写 verifiedNodes/pendingTaskEffects，QUEST_RESOLVE 才结算；共享路径保原 epoch/TEAM 合同 |
| 原重放 | `AdvancedGameRuntimeServiceImpl.commandFingerprint/replay/themeActionKey`；个人 `gameplayInstance` | 指纹包含 actor/session/topic/node/owner/instance、原 expectedVersion 和 payload。任务实例来自真实 routeSessionId+start receipt；不借别局证明，不释放原回执身份 |

本片不改上述任何签名，不依赖 E/G/D 新增接口。客户端编辑/渲染的实际路径由 D 在其所有权范围交付；这里不以服务端测试推断小程序已发送或已显示。

## 能力 key、配置和不可合并的合同

下表是本片实际追踪的 QA 族及容易混淆的相邻机制；完整公开能力集合继续以现有 support-matrix 为准，不新增另一份注册表。

| 能力 / 情形 | 唯一生产实现 | 可配置内容与提交 | 证明及已有测试 |
|---|---|---|---|
| qaPick，judge 缺省/true，multi 缺省/false | AdvancedActionJudgement.submitQa | qa.enabled=true、mode=PICK；title；2–4 个唯一 id/label；恰好一个 correct；optionId 提交。反馈/媒体/effects 受原 validator 与公开投影约束 | 普通模板规则要求 maxTries=0；错误选项不产生通过事实。GameplaySupportMatrixTest、AdvancedActionJudgementTest、PlaykitActionPayloadContractTest、此片 GameplayQaPickTemplateReuseTest |
| qaPick，judge=true、multi=true | 同 submitQa → submitQaMulti | optionIds 非空、无重复且均属于原 options；正确集合至少两个且不能全为正确 | 原集合完全相等才通过；不能把少选/多选授权成正确。AdvancedGameRuntimeServiceImplTest 的既有 QA 场景保留 |
| qaPick，judge=false | 同 submitQa 的明确非判对错分支 | 原有效单个 optionId；任一有效选项完成；optionIds 拒绝。不是客观知识题正确性证明 | 不把此模式描述成“选中了正确答案”，不因复用而改变原完成语义 |
| qaText | 同 submitQa 的 TYPE 分支 | qa.mode=TYPE、title、服务端 answerText、input；原文本归一化和 maxTries 合同 | 与 PICK 共享 QA owner，输入合同不同，不把文本转成选项 id |
| qaShot | 同 submitQa 的拍照分支 | qa.mode=SHOT、imageUrl 等原配置；R5 还受原图片证据校验 | 无文字/选项正确答案；不能用 qaPick 的答案合同替代照片证据。AdvancedActionJudgementTest 与既有图片 owner 测试 |
| legacy validationMethod=1/3 | TopicRouteRuntimeServiceImpl.verifyGuidedRouteAnswer 的 nodeAnswers 分支；基本到达/回答证明链 | 老字段 questionAnswer/correctAnswer；基本 answer 字段 | 与 advanced qa.optionId 不等价，不能强行迁移原单题字母答案。PersonalGameplayRuntimeServiceTest.basicTextAndPickAnswersBindFreshControlledProofWithoutAdvanced |
| sort / match / classify | AdvancedActionJudgement.submitSort/submitMatch/submitClassify；题组仍复用这些方法 | 各自原 order/pairs/placement 合同 | 不是同一 PICK payload。PersonalGameplayRuntimeServiceTest.guidedGroupReusesAllFourJudgesRejectingPartialAndForgedPassed |
| profile 偏好/资料题组 | AdvancedGameRuntimeServiceImpl.submitProfile | 原 profile.questions 和 answers 对象；其资格和档案/加成合同 | 不改名成 qaPick，不按客观正确答案判所有偏好题 |
| branch 剧情选择 | AdvancedGameRuntimeServiceImpl.choose；R5 反馈另走 outcomeFeedback | 原 currentStepId/options/nextStepId 及选择 effects | 不是知识题判对错；完成证明要求见现 matrix，不能仅靠同名 optionId 代替主玩法通过 |

普通 qa 的免费重答约束、R5 outcomeRules.version=1 的单独结算合同、归零挑战隔离、跨队/全场工具范围都仍由原 matrix/规则校验负责。本片未放宽未知字段、效果、金额、次数、权限或完成证明。

## 新主题最小用法

仍编辑已有模板的 advancedConfigJson，例如：

```json
{
  "schemaVersion": 1,
  "qa": {
    "enabled": true,
    "mode": "PICK",
    "title": "桥梁建成年份？",
    "maxTries": 0,
    "xp": 0,
    "options": [
      {"id": "a", "label": "1908", "correct": true},
      {"id": "b", "label": "1926", "correct": false}
    ]
  }
}
```

另一主题可换题干/选项/正确标记，并绑定另一份内容模板；仍使用 qa/PICK 与 SUBMIT_QA。确需采用同一模板时走已有 adopt-draft/模板来源机制，保 owner 校验与隐藏答案恢复，不能从客户端删掉的 correct 字段猜答案。节点挂实际 templateId 后走原审核/发布流程；运行提交形状仍是：

```json
{
  "sessionId": 1005,
  "version": 0,
  "idempotencyKey": "original-command-key",
  "action": "SUBMIT_QA",
  "payload": {"optionId": "a"}
}
```

示例 sessionId/version 仅说明字段，实际值必须来自服务器原会话；不得复制示例 ID。不要提交 correct、奖励结果或 passed 来替代原判定。相同原命令重试保留原版本/输入/key；新主题或新局须使用其实际会话身份。

## 本片配套测试源码和限制

仅新增 `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/GameplayQaPickTemplateReuseTest.java`，3 个固定 @Test：

1. 两主题使用相同选项 id、不同正确答案，走同一真实 Advanced runtime/submitQa/个人任务 owner；错误答案不通过，A 完成不改变 B，B 后续独立完成；模板费用通过原编译器绑定，分别只扣一次 HP。
2. 发布后改 live 模板不替换既有局配置；实际任务证明不能跨局借用；原指纹重放不二写，改输入/错 actor/错主题节点拒绝；同 client key 在另一个合法会话是其自己的命令，不读 A 回执。
3. 读取原公开 qaPick 能力，核实真实模板规则生成的节点设施身份；普通付费重答配置及伪造题组 passed 保留拒绝。

夹具只用内存 Mapper/current-paid-registration 替身保存两主题的真实服务输出，调用实际 normalize、compile/freeze、start/act、recordNodeResult、QUEST_RESOLVE。没有模拟“判题必过”或复制判题/指纹算法，没有调用 provider。两主题在同一运行服务和共同存储 Map 中用不同真实夹具 ID，不用两个互不相见的假数据库掩盖隔离。

这不是 HTTP 鉴权链、真实 SQL/CAS 并发/回滚或完整审核发布测试；对应原测试不删不改，最终由 root 同候选统一验收。已有 GameplaySupportMatrixRuntimeTest、GameplayReskinRuntimeServiceTest、GameplayTemplateRulesTest、PersonalGameplayRuntimeServiceTest 与 PlaykitActionPayloadContractTest 保留。

## 源码交付状态

- parent：fa5c263440573992701263d6bee60830844a49b8。
- GameplaySupportMatrix、GameplayTemplateRules、AdvancedActionJudgement、发布/运行 owner：全部原样复用，无生产增量。
- 新增测试 3 个声明，不等于执行通过；所有旧测试原文未改。
- 用户最新时机约束：本片不运行 Node/Python/Java/JUnit/MySQL/HTTP/CI 测试，仅准备源码与精确补丁摘要。
- 未修改 E/G/D、CI、schema、依赖、金额或权限；未合并/部署、未执行 SQL/外呼。
