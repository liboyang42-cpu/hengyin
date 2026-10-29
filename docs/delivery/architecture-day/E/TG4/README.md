# TG4：复用发布组合校验，只补可定位诊断

代码候选，测试全部 **NOT_RUN**；父提交 `fa5c263440573992701263d6bee60830844a49b8`。独立分支 `codex/architecture-day-e-tg4-diagnostics`，不继承 6fcd 或站点候选的运行结果。不改 G PublicationService/Review/Compiler、A SupportMatrix/TemplateRules、provider、SQL、公共接口或 CI。

## 已有源码与真实调用

以下链接固定父提交，属于源码证据，不代表已执行验收：

- [`CmsTopicServiceImpl.publishApprovedRouteSnapshot`](https://github.com/liboyang42-cpu/hengyin/blob/fa5c263440573992701263d6bee60830844a49b8/chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsTopicServiceImpl.java) 原事务 façade → [`TopicPublicationService.publishApprovedRouteSnapshot/publishValidatedRouteSnapshot`](https://github.com/liboyang42-cpu/hengyin/blob/fa5c263440573992701263d6bee60830844a49b8/chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TopicPublicationService.java)：先 status/size，再状态键、thought、剧情、runtime、NPC/sys 引用，再 normalized graph，最后原 persistRouteSnapshot 及 rowcount。draft 发布也调用同一 ContentGuard；不另建发布口。
- [`TopicPublicationContentGuard`](https://github.com/liboyang42-cpu/hengyin/blob/fa5c263440573992701263d6bee60830844a49b8/chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/TopicPublicationContentGuard.java) 复用 `TopicStateKeyRefService`、`JourneyStoryValidator`、`ThemeRuntimeConfig`、`AdvancedGameConfigValidator.assertCompletedNodeIdsOwned`：注册键/类型、思维阁引用、章节与 NPC 归属、NODE_COMPLETED/sys 节点范围、物资/数值规则、scene 引用均已有拒绝路径。
- [`TopicRouteConfigValidator.validateAndNormalize`](https://github.com/liboyang42-cpu/hengyin/blob/fa5c263440573992701263d6bee60830844a49b8/chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/TopicRouteConfigValidator.java)：schema、实际 node 集合、outcome 声明、变量/效果、fallback、静态拓扑与终点等原校验；随后 [`TopicRoutePublishGate`](https://github.com/liboyang42-cpu/hengyin/blob/fa5c263440573992701263d6bee60830844a49b8/chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/TopicRoutePublishGate.java) 构造有限分区见证，直接调用 `TopicRouteEngine.topPriorityEligibleEdgeIdsForVerification`；保留稀有边、优先级遮挡、不可能条件、50000 组合预算和现实门禁 fallback 拒绝。没有复制 eligibility/priority 算法或另做编译器。
- [`TopicRoutePublicationCompiler.compileTemplateRules/freezePublication`](https://github.com/liboyang42-cpu/hengyin/blob/fa5c263440573992701263d6bee60830844a49b8/chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TopicRoutePublicationCompiler.java) 的现有适用分支调用 `GameplaySupportMatrix.validateNode/validateChallenge`、`GameplayTemplateRules.resolveNodeConfigs/merge`、`TemplateResourceSlots.assertEffectTargets`。模板引用写侧另复用 `TopicContentWriteGuard` 的原归属/审核/草稿校验。这些保持原 owner/实际条件，不据此声称所有旧模板运行组合均已证明；TG3/完整冻结验证由 A/G 联合收口。未加 capabilities 列、Yarn/Markdown 生成器或通用 validator。

## 本片唯一改动

1. ContentGuard 给已拒绝配置附 `[topicId=原ID, path=实际字段]`。runtime/图/剧情/场景数组、章节 index/cost、物品 stat 有所属位置；模板物资和 NODE_COMPLETED/sys 引用含原 node/template ID，同名地点不再无法区分。早期 StateKey 收集失败也能定位原 node 的 template 配置。
2. gate 错误保留原 `IllegalArgumentException` 和原可识别文案，只追加实际 `routeGraphJson.edges[i]`、from/to ID 或 `fallbacks[i]`。数组顺序、求解域、优先级、预算、fallback 安全规则均不变。
3. 原 `ServiceException` 不变；原 validator 的 IllegalArgumentException/IO cause 留在新增定位异常链。DB 读不放进这些 config catch，不把存储失败改为作者错误/空成功。附加内容只有字段路径和实际 ID，不追加原配置、答案或敏感正文。没有修改原写入顺序/事务。
4. 纯发布存在性见证不会变成当前玩家/世界可达证明。`RouteReachabilityProvider.UNKNOWN` 完全未改，也没有写入见证缓存。运行期实际可达性仍须原 provider 事实。

## 配套测试源码（未运行）

- `TopicRoutePublishGateTest` 原 4 场景和拒绝断言保留；3 个精确文案断言追加相同定位。新增 2 场景：实际第二条 edge 索引；发布见证不改变已有玩家变量/UNKNOWN，原 runtime engine 在不可用目标下仍走原 fallback。后者仅纯边界单测，不冒充真实 provider/路网集成。
- 新 `TopicPublicationDiagnosticsTest` 10 例：真实 façade→真实 publication owner→真实 ContentGuard/StateKey collector/原结构与构造性 gate；Mapper/额度/快照端口为 doubles，不跳过任何 content guard。覆盖坏 runtime、模板缺物资及同名 node、剧情外章、scene 精确索引、外来 NODE_COMPLETED、早期坏模板、未声明变量、不可能组合、正常归一化 JSON 字节不变、DB 异常原样传播。拒绝验证未调用持久快照/主题 update/配额/审核/活动壳/状态键注册；不是 MySQL rollback、HTTP 授权或 A/G 完整编译实证。
- JSON 场景来自父版既有 `AdvancedGameConfigValidatorTest.itemConditionsPreserveValidQuantityBoundaries`、`JourneyStoryValidatorTest.openingReferenceMustBelongToTheTopicAndFollowChapterCopies`、`TopicRouteConfigValidatorTest`、`ThemeRuntimeChapterRequirementsTest`，没有虚构新 API。

执行仅源码读取/编辑、git diff 和有限秘密模式扫描；未运行 Node/Python 测试、Java/JUnit/MySQL/HTTP/CI。未做新全仓审计。root 统一最终同源审查与回归；新 A/G 片合并后仍需按原发布/冻结合同验收。无迁移/依赖/部署影响；回退只丢新增诊断，不需要数据回退。

完整四源码 diff 为 `owned.patch.b64`（标准 base64；解码后可 git apply），`source-evidence.json` 提供解码 patch SHA256 和每份 postimage SHA256；补丁不包含其自身文档。
