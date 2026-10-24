# D8 发布入口补闸候选：部分闭合，待统一锁序

Parent：`cc91b0845e3abd4a2060d54a857c8c179bd22d4f`。只改 `TopicPublicationService` 三处调用，新增一个 8-case unit 类；不修改 guard/mapper/事务边界/金额/结算/活动壳实现。Java、MySQL、HTTP、CI 全部 **NOT_RUN**。

## 本片源码修复与断言

| 入口与真实链 | 原缺口 | 本片修改 |
| --- | --- | --- |
| `ApiTopicController.update` → `updateTopicByOwner*`（外层 Tx）→ `publishBeforeReview` → `publishValidatedRouteSnapshot` → `persistRouteSnapshot` | 非草稿重发不经草稿规模检查 | 原媒体闸之后、quota/复核单/状态/快照/壳之前复用规模检查 |
| 同一 V2 create/edit → `submitTopicBundle` → `publishBeforeReview` | 原审核任务先持久化，随后才可能走其他发布检查 | 保留 freeze 的归属/内容错误优先级，在审核任务写入前检查 |
| `POST /business/audit/review` → `AuditTaskServiceImpl.review`（外层 Tx）→ `applyModeration` → `CmsTopicServiceImpl.publishApprovedRouteSnapshot` → 发布 owner → 快照 | 审核首发未按库检查 | 保留原成功状态守卫，然后检查，失败传播回滚原审核事务 |

`TopicPublicationSizeEntryTest` 新增 8 个普通 `@Test`：31章重发、301节点重发、组合提交拒绝且零审核任务、审核首发拒绝且零版本写入、原未过审错误优先级、审核首发30/300边界、重发30/300边界及原壳/公开行为、组合提交30/300边界及原审核结果。真实 size 算法；其余既有内容校验用 spy 隔离。本片不修改任何旧 case/断言。拒绝时断言零 quota、快照、主题更新、广场、活动壳调用；**unit doubles 不能证明真库回滚或并发**。

## 直接创建已存在的写前门禁（不重复加层）

`ApiTopicController.create` → 外层 `createTopic/createTopicV2WithReview` → `createTopicInternal` → `contentGraphAuthoringService.saveTopicChapters`。真实 writer 的旧五参 `saveChaptersAndNodes` 与 V2 `compileChapterFlow` 都以 `TopicSizeLimits.requireWithinLimits(chapters)` 起始，位于任何 mapper 写入之前。原两处 simple 模式 `persistRouteSnapshot` 调用均在此之后；pro 模式继续进入本片检查的审核发布链。故本片不改写口或添加同一 payload 的第二个 graph budget。原 `TopicSizeLimitsTest` 四例及 D8 三处旧门禁保持原字节。

## 不得宣称已完成的三项

1. **审核重新上架另有实际绕口**：`AuditTaskServiceImpl` 在 `alreadyPublished=true` 时不调用 `publishApprovedRouteSnapshot`，但随后仍同步公开和 `ensureSelfHostActivityShell`。原 `topicSupplyAutoOnlineBlockReason` 仅检查供给且 quota 失败时会被短路。须由 root 分配审核决定写入之前的 budget 接线；本片没有该文件权限，没有偷改。普通 `casUserStatus` 恢复公开也不是本片三入口之一。
2. **原 guard 的库内统计不是全部物理行**：`assertTopicSizeWithinLimits` 复用普通 chapter/node list；`CmsTopicNodeMapper.xml` 的普通 list 会过滤待审/已退出商家节点。存在的 `selectByTopicForUpdate` 可以取全部行，但不能在没有锁序决定时直接替换。
3. **并发证据未闭合**：整包 V2 原链 `task→topic`，组合审核原链 `task→topic→chapter→node`；章节 CRUD 的 `lockAndAssertChapterMutable` 原链 `chapter→topic`；通用 node insert/update 未先锁 topic。普通 list 在 MySQL RR 下还可能命中旧 read view/MyBatis 缓存。直接把 guard 改成 topic→chapter 锁读会扩大既有反向边；只锁 topic 不能 fence node CRUD。必须统一这些实际写者的范围锁/锁序，再以真实 MySQL 验证 current-read、phantom、阻塞与回滚。本片不新增任何锁，不以并发 mock 充当完成证据。

上述是源码可核验阻塞，不是金额/授权新业务决定；本片提供实际入口漏校验 delta，**不签署 D8 全入口/全并发完成**。无生产 DDL/新依赖/CI 调度。回退仅撤销本片三个调用和专属测试，不回退既有 D8/公开主线政策。
