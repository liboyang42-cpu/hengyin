# 给 root/G 的作者声明生产者合同

观察/实施 parent：G `edf731c15090d2289553dd7f966f6c7d8ec7f5e2`（G parent fa5c）。已读完整方案 `ceb4e5f923091dc0fc5e5b6b7670d60c27bff85f:docs/delivery/architecture-day/COORDINATOR/全游戏资源库与主题复用完整施工方案-20261025.md`。仅 F 原声明 Mapper/服务/直接测试源码；G 的 Bundle/Compiler/hash/freeze 和 H 的运行消费者均未编辑。

## 实际声明与运行值

`theme_state_key` 原表确实只存作者声明。`ThemeStateKey` 类注释和原 canonical `migration_theme_state_key_20260916.sql`、`migration_20261022_theme_state_key_attribute_def.sql` 一致：

- 作者定义：state_key、key_type、scope、label、initial_value、min_value、max_value、display_type、color、icon、sort_order、visible、persist_scope、rules_json。
- 归属/物理身份与技术时间：topic_id、id、create_time、update_time。G 应明确构造内容投影；不得把 mapper bean 的全部字段未经选择直接塞入 hash，尤其时间字段不代表作者内容变化。
- 可变值另在原 session `state_json.variables/tags`；`ThemeCharacter.values_json/source_session_id/source_version` 为原跨局投影/台账。本片不查询、hash、修改这些运行值。
- 现有 `JourneyInitialState.freezeKeys` 的 `stateKeys` 仅 key/label；不等于范围/初值/规则已随完整声明冻结。topic 的原 journeyRules 属性又是另一个已有作者字段。G/H 需沿既有快照明确消费，不能据 mapper 全列查询声称已完成冻结。

## 可编译签名与锁序

保留 `ThemeStateKeyService(ThemeStateKeyMapper)`；无新 DI collaborator/type/table/权限。

```java
Long ThemeStateKeyMapper.lockTopicForStateKeyWrite(Long topicId);
List<ThemeStateKey> ThemeStateKeyMapper.selectByTopicIdForUpdate(Long topicId);
ThemeStateKey ThemeStateKeyMapper.selectByTopicIdAndStateKeyForUpdate(Long topicId, String stateKey);
List<ThemeStateKey> ThemeStateKeyService.listCurrentDeclarations(Long topicId);
```

三个新 SELECT 均 `useCache=false/flushCache=true` 且明确 FOR UPDATE。parent 是同一 `cms_topic.id` 物理行，和 G 现有 `CmsTopicMapper.selectCmsTopicByIdForUpdate` 互斥。整批声明按原 id 顺序读，显式沿原 `(topic_id,state_key)` 唯一索引限定扫描，再排序，避免依赖小表优化器选择全表排序扫描。原普通 SELECT/upsert/update/delete SQL不变。

共同顺序为 **原写事务→topic parent→state-key 当前行/范围**。作者 register 先完整校验批次、后锁 parent 再 upsert；updateDefinition 先锁 parent，再单键 current read，保留键名/类型/scope 不可变等全部校验；delete 补原 Spring REQUIRED 事务，先锁 parent 再按原 topic+key 删除。不存在 parent 拒绝，绝不无锁 fallback。parent fence 同时保护空声明集，不能只锁现存键而漏掉并发新注册。

`listCurrentDeclarations` 为 MANDATORY，显式拒绝无事务/只读事务；锁 parent 后读同 topic 声明，null/归属不符为 UNKNOWN/拒绝而非空表。返回不可增删的 list，但原 ThemeStateKey 为可变 bean；G 的操作内投影仍须自己稳定保存/复制，不能跨请求缓存或改变后再当审核原文。preview/runtime 的原 `listByTopic` 一字语义保留，不按隐式事务上下文自动换加锁读取。

## 所有生产直接调用者与剩余串接（root/G）

| 调用者 | parent 源行为 | 本片/剩余动作 |
| --- | --- | --- |
| ApiTopicStateKeyController register/update/delete | 原 owner、编辑窗口、内容安全/引用检查，调用 ThemeStateKeyService | 服务写入现在共用 parent fence；Controller 前置校验/原权限不改、不称已在 fence 内重做所有引用检查 |
| CmsTopicServiceImpl.syncInitialAttributeKeys（新建/更新后） | 调用 service.register 派生原 journeyRules 属性 | 原调用者签名不变；在原内容写事务里同 topic fence；不改属性规则 |
| TopicPublicationContentGuard.autoRegisterOptionTags | 普通 listByTopic 判断缺键→service.register | root/E/G 将作者写路径的检查/消费接到同次 current 投影；当前普通读可能受旧 RR 视图影响，F 未跨 owner 改此文件 |
| TopicPublicationContentGuard.assertStateKeysRegistered / 其它发布引用检查 | 原 listByTopic 多处普通读 | 纯预检查仍普通读；正式发布必须复用同次当前声明，不重新普通读 |
| TopicRoutePublicationCompiler.registerTemplateClues | 直接 Mapper 普通 selectByTopicId→upsertThemeStateKey | 唯一绕服务的生产 writer，归 G；必须同 topic fence，声明派生/注册必须在捕获一致投影前完成或明确同步同次投影，不能审核后悄换声明/在纯预览加写 |
| TopicRoutePublicationCompiler.assertCompiledReferences / freezeCurrentPublication | 重复普通 selectByTopicId，后者喂 freezeKeys | G 在 CurrentContent 中保存一次当前声明，与审核 hash/校验/freeze 消费同一内容；不能每步再读不同投影 |
| TopicBundleReviewService submit/applyDecision | 原 parent/章节/节点/模板锁和原 hash | G 补声明投影/hash；建议在 parent 后立即锁/保存 keys，再走其原子表顺序；保留原 hash/旧待审 stale 合同 |
| TopicRouteRuntimeServiceImpl 注册检查、GameSessionRuntimeServiceImpl、ThemeCharacterService | 原 Mapper.selectByTopicId；角色卡/跨局值分别来自原运行源 | H 独占；本片不改变已开局状态、读值或启动顺序，普通 API 不被强制加锁 |
| PlayJourneyReadServiceImpl 等经 service.listByTopic | 原案件册/可见性路径 | 同上，H 与 G 沿原冻结 stateKeys 处理；不扩大到 runtime 值 hash |

原 Mapper 的生产 mutation 搜索仅 service 三类操作与 Compiler.registerTemplateClues。service `check` 和 `listByTopic` 无写；TopicStateKeyRefService 只是收集主题/章节/模板引用，不是注册表 writer。本表无运行期值 writer。

**G 尚未串接前不能称目标闭合。** 本片提供真实当前读/共同 writer fence，未替 G 伪造作者 hash，也不把仅 key/label 的旧快照说成完整声明。新增 Mapper API 没有新的 namespace/primary type，技术 policy 不自动加 owner/业务表授权；待全部新消费者实际齐后 root 才核必要映射。
