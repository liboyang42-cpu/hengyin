# WP09：节点提示解锁完整用例归位（doc-only v1，待 root 确认）

结论：首个候选选 `POST /api/play/hint/unlock`，完整移到**既有 `IPuzzlePlayService / PuzzlePlayServiceImpl`**。迁移范围包括授权准入、模板、定价、唯一消费流水、扣分、重复结果和回滚，不能只移动 collect，也不把 Controller 的 Supplier/lambda 传给 Service。共享准入 helper 先成为一份实现，原控制器只保留必要的参数/结果适配。**本提交只有文档；接口未实现，Controller 未修改，Java/MySQL 未运行，WP09 未完成。**

## 1. 依据、基线和边界

- 已读公开规范 `4a6e3cf423594f4e4dc4b30aa769a90048f6aa6e:docs/delivery/architecture-day/COORDINATOR/governance-scope.md`，重点 WP09、§5.1/5.2、§6.1、A01–A05/A12/A15、T14/T17/T22。
- 规划工作树 `/workspace/hengyin-c`，公共分支 `codex/architecture-day-c-play-command`，起点 `1ebfbf6f6303e0d71bcd787be9e958266e5689b6`；冻结候选均不重写。
- 实查控制器 `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiPlayProgressController.java`，blob `ad6a86d75fc1f2e056810c245ea1dd605f75e371`，与已核对 root07172 同文件相同。编码前必须绑定 root 当时的完整联合 SHA，再比较本 blob，不能把 C 旧整树覆盖联合修复。
- 配套 `wp09-inventory.json` 登记全部端点、private 签名/行号/body hash、直接 Mapper 方法、保守 helper 调用闭包及旧测试文件。该清单是源码盘点，不是 AST、真实 SQL、事务或行为等价证明。
- 当前联合 CI/H 真缺陷优先；root 独占 ThemeRuntime/APPLIED 回滚场景、台账和静态合同适配，C 不重叠修改。
- **WP13 未完成。** Photo 四阶段是原意图/事务闭环工作；WP13 另需业务 ID/MDC/告警链路、通道超时/并发/队列预算以及 T13/T19 隔离实测。

## 2. 实际端点与事务迁移地图

共 **25 个映射端点（9 GET、16 POST）**。下表事务只指控制器入口的实际注解，`无`不表示内部 service 无事务或 GET 无副作用。RC=READ_COMMITTED，D=DEFAULT；所有已标写事务的传播均为默认 REQUIRED、rollbackFor=Exception。六个入口另有 `@DeadlockRetry`，本用例不改变它们。

| HTTP /api/play 下的路径 | 实际入口 | 控制器事务 / retry | 首批归位范围 |
|---|---|---|---|
| GET /nodes | nodes | 无 | 保留查询编排；hintUnlockCost 改调用同一 Service 真源 |
| GET /route-state | routeState（有未映射重载） | 无 | 保留，已调 route runtime |
| GET /ending | ending | 无 | 保留历史完成/结局授权 |
| GET /companionLine | companionLine | 无 | 保留 NPC 查询 |
| GET /action-context | basicActionContext | readOnly=true | 保留原动作上下文合同 |
| GET /action-receipt | basicActionReceipt | readOnly=true | 保留原回执读取授权 |
| POST /checkin | checkin | RC / retry | 完整业务仍保留；仅共享准入 helper 委托 |
| POST /scan-entry | scanEntry | 无 | 已调 PlayScanEntryService，保留 |
| POST /answer | answer | RC / retry | 同上，不移动 executeBasicIntent lambda |
| POST /puzzle/reveal | revealPuzzle | RC / retry | 保留完成、解谜分与奖励编排 |
| POST /puzzle/hint | puzzleHint | RC | 保留免费逐级提示；不并入付费解锁规则 |
| GET /preference/{nodeId} | preference | 无 | 保留；共享准入委托 |
| POST /preference/{nodeId}/submit | submitPreference | 无 | 保留；现有 Controller completion lambda 记为后续债务，不复制到新用例 |
| POST /tag/{tagId}/confirm | confirmPreferenceTag | 无 | 已调 PreferencePlayService，保留 |
| POST /tag/{tagId}/revoke | revokePreferenceTag | 无 | 保留 |
| POST /tag/{tagId}/correct | correctPreferenceTag | 无 | 保留 |
| GET /os/{topicId} | newLifeOperatingSystem | 无 | 保留 |
| POST /sensor-result | sensorResult | RC / retry | 保留传感器验证与完成事实 |
| POST /arrive | arrive | RC / retry | 保留位置/到达与原回执 |
| POST /photo | photo | RC / retry | 保留上传事实/到店/完成；只适配共享进度投影 |
| POST /my-completed | myCompleted（有未映射重载） | 无 | 已调 CompletedRouteFactReader，保留 |
| GET /leaderboard | leaderboard | 无 | 保留资格与排名 |
| POST /growth | growth | 无 | 保留成长查询 |
| POST /achievements | achievements | 无 | 保留收藏查询 |
| POST /hint/unlock | unlockHint(Long,String,String) | D，无 retry | **整条用例移入 PuzzlePlayServiceImpl.unlockNodeHint** |

保持所有原 `routeActionId / expectedRouteVersion / registrationId / routeSessionId / runtimeInstance / authorityEpoch` 的原值、分流和回执处理；不在共享 helper 中生成 ID、刷新 expected、补默认 runtime，亦不改各原事务注解。第一个完整用例归位不等于其余 24 个端点已归位。

## 3. 18 个直接 Mapper 的现状

下列为控制器直接注入；细到方法与入口闭包的关联见 JSON。单纯搬动 Mapper 字段不作为完成标准。

| Mapper | 实际用途 | 首批处理/事实 owner |
|---|---|---|
| WxContentCallbackInboxMapper | countRiskyCallback 内容风控读 | 保留查询，community/media 事实 |
| PlayAttemptLogMapper | insertPlayAttemptLog | 保留原 play 尝试写 |
| NpcProfileMapper | scope/global 人设读 | 保留 NPC 查询 |
| NpcLineCacheMapper | profile/event 台词读 | 保留 NPC 查询 |
| ClubLeadMapper | selectByActivity | 原 lead gate 读移入共享准入；ClubLead 写权不动 |
| GameSessionRuntimeMapper | session/receipt 锁读、claim、complete | 保留 basic intent，不新建账本 |
| TopicRouteSessionMapper | 当前/默认局及授权锁读 | 保留 basic intent |
| BusinessAuditEventMapper | insertBusinessAuditEvent | 保留原拒绝/进度审计 |
| PlayerBadgeMapper | 注入，未发现方法体引用 | 不顺手删字段 |
| CmsRegistrationMapper | CurrentPlayRegistration 的当前资格、首次成功扫码 CAS/锁读 | 资格调用随原 helper 移；扫码写仍在原链 |
| CmsActivityMapper | selectCmsActivityByIdForUpdate，亦传给 CurrentPlayRegistration | 随原准入 helper 移，不改锁序 |
| TopicOnsiteMetricsMapper | selectInStoreLoads | 保留 nodes 批读 |
| MerchantChapterOfferMapper | selectLiveByChapterIds | 保留 nodes 批读 |
| CoopPerkMapper | selectById | 保留权益查询 |
| CmsTopicTemplateMetaMapper | selectChapterMetaByTopicId | 保留章节查询 |
| GameRuntimeSessionMapper | player session/state 查询 | 保留完成视图 |
| MemberCollectibleMapper | countOne/insert/selectList | 保留收藏；不以移 13 行 collect 冒充本用例 |
| NodeMedalClaimMapper | 注入，未发现方法体引用 | 不顺手删字段 |

新用例的积分写只调用现有公开 `IUmsMemberPointsDetailService`、`IUmsMemberService.deductPoints`，不新增 play→积分内部 Mapper 写。共享准入保留的三个读侧依赖（ClubLead/CmsActivity/CmsRegistration）必须由 root/架构 owner 对照旧消费者逐项登记迁移、查询预算及退出条件；不是新增包级忽略，也不声称已消除跨域读耦合。若 A01 不允许这一条精确迁移，先由原域提供同语义公开查询/准入合同，再编码，不借新类隐藏违规。

## 4. 共享 helper 唯一归属

当前 **68 个 private 实现、58 个名字**。首批仅移以下 15 个实现；其他 helper 明确留在原控制器，完整签名/调用关系已登记 JSON。

| 原 helper | 唯一目标 | 迁移约束 |
|---|---|---|
| resolveCtx 两个重载 | 新 concrete `PlayNodeAccessService` | 原 activity/selfPlay、CurrentPlayRegistration、continuing 与错误码保持；不复制授权规则 |
| activityTimeError、isNightNow | 同上 | 保留时间边界和夜间提示；不引入另一套时区/Clock 规则 |
| leadGateError | 同上 | 原 runtime 优先、章节顺序分支原样；异常 fail-open 风险单列，不混改 |
| routeAccessError | 同上 | 原下架已完成豁免、canPlay、LINEAR fallback、unlock gate 次序不变；原 actionId 透传 |
| isDelistedNode、hasCompletedNodeFact 两重载、findNodeProgressRow | 同上 | 一份权威进度查询；只认 status=1；自玩含 topic 限定 |
| resolveMode、firstTicketMode、isPrevDone、unlockGateError | 同上 | 原模式/票/前置节点规则一份实现，不把 NodeAccessPolicy 当成已等价替代品 |
| hintUnlockCost | 既有 PuzzlePlayServiceImpl | nodes 展示与 unlock 收费共用；type=1/eventType=14、停用/0/空规则和 pointsNum/value 优先级不变 |

控制器原私有方法可暂留为**纯 DTO/结果转换委托**，无规则判断、SQL、授权闭包或奖励回调；这使其他入口保持调用形状。`CurrentPlayRegistration`、`NodeAccessPolicy` 不复制、不改写。新共享 helper 自身不增加事务注解，不暗改原 GET/非事务入口的传播；完整 unlock 的外层事务由应用方法持有。

为避免公开合同复用可写实体，新 access 返回不可变 context：actId/topicId/selfPlay/nightWarning/continuing、registrationId/purchaseKind、可选 failure；node 输入是 id/topicId/chapterId/sortId/status/unlockAfterNodeId 的不可变投影；progress 返回 status/picUrl 的不可变投影。原 `PlayCtx.registration` 只在章核销判断与 recordFirstSuccessfulScan 两处读取：改为 registrationId/purchaseKind 的直接投影，不增加二次查单，不改变首次扫码 CAS/权益读取。photo 的两个局部 progress 变量仅消费 status/picUrl，按同值投影适配，不迁移其业务。

保留原处的组：basic intent/hash/replay、checkinOnce/answerOnce/arriveOnce、preference/frozenTemplate、照片来源/ShopDay proof、六个 advanceProgress 重载、完成/票查询、collect/奖励/券/bingo、history/NPC、normalizeAnswer/parseGeoCoordinate，以及仅提取可信 actor 的 currentMemberId。此次不顺带拆控制器大类或发明 RuntimeResolver。

## 5. doc-only 接口冻结 v1（确认后才实现）

现有 `IPuzzlePlayService` 只追加：

```java
NodeHintUnlockResult unlockNodeHint(NodeHintUnlockCommand command);
int hintUnlockCost();
```

新 DTO（无 Spring/HTTP/可写 DB entity）：

```java
record NodeHintUnlockCommand(Long memberId, Long nodeId, String activityId, String topicId) {}
record NodeHintUnlockResult(Hints hints, Failure failure) {
    record Hints(String hint1, String hint2, int cost) {}
    record Failure(int originalAjaxCode, String message) {}
}
```

Hints/Failure 恰有一项非空；hint1/hint2 的 null/空串保持原值。memberId 只由 Controller.currentMemberId 从可信 App 身份构造，不能绑定客户端 actor。String scope 保持现有解析/错误顺序，机械批不改变空字段语义。

新 `PlayNodeAccessService` 为一个具体 @Service，不另造 interface+impl，公开以下有限合同，返回其嵌套不可变 record：

```java
Context resolveContext(Long actor, String activityId, String topicId, NodeScope node, Long registrationId);
String leadGateError(Long activityId, NodeScope node);
String routeAccessError(Long actor, Long activityId, Long topicId, NodeScope node, String originalActionId);
int resolveMode(Long activityId, Long topicId);
String activityTimeError(ActivityWindow window); // epoch millis，保持原 start/end 比较
boolean isDelistedNode(NodeScope node);
boolean hasCompletedNodeFact(Long actor, String activityId, NodeScope node);
boolean hasCompletedNodeFact(Long actor, Long activityId, NodeScope node);
ProgressFact findNodeProgressRow(Long actor, Long activityId, Long topicId, Long nodeId);
boolean isPrevDone(Long actor, Long activityId, NodeScope node);
String unlockGateError(Long actor, Long activityId, NodeScope node);
```

对应 nested record 的字段也在 v1 固定：

```java
record NodeScope(Long nodeId, Long topicId, Long chapterId, Long sortId, Long status, Long unlockAfterNodeId) {}
record ActivityWindow(Long startEpochMillis, Long endEpochMillis) {}
record ProgressFact(Integer status, String picUrl) {}
record Failure(int originalAjaxCode, String message) {}
record Context(Long activityId, Long topicId, boolean selfPlay, boolean nightWarning,
               boolean continuing, Long registrationId, Integer purchaseKind, Failure failure) {}
```

firstTicketMode/isNightNow 保持 service 私有。Context 的 Failure 仅携原 code/message，controller 同构映射为原 AjaxResult；无新异常分类框架。接口不接受 Supplier/Function/Runnable、Controller、Mapper、SecurityContext 或可变客户端期望对象。

`PuzzlePlayServiceImpl.unlockNodeHint` 自己完成 node/template 读取→共享准入→同一 hintUnlockCost→余额/流水/扣分→typed result。其公开方法设置原 `@Transactional(rollbackFor=Exception.class)`、默认 REQUIRED/DEFAULT/readOnly=false；没有新增 RC、REQUIRES_NEW、deadlock retry 或事务外业务写。Controller 保留 URL、参数绑定、Operation、RateLimiter、身份提取和 Ajax 包装，删除该入口业务事务注解；所有业务数据库动作仍位于同一个外层事务。异常扣分仍 markRollbackOnly 后返回原业务错误，不能把这个“正常返回”误当可提交。

## 6. 真正的当前调用与积分合同

唯一生产 HTTP caller 是 `chengyinhub-xcx/pages/play/index.js:5972 unlockHint`，由 useHint 调用，确认后 `POST /api/play/hint/unlock`，body 为 nodeId + `_sessionParams()`（activityId 或 topicId）。当前 caller 冻结 `_legacyHintIntent`，旧回包不能覆盖新题；不在机械批改小程序。搜索 admin/system/UI 未发现第二生产 HTTP caller。Controller 的单参 Java 重载仅由测试调用，仍是未映射方法；不能误报第二 HTTP 入口。

实际没有名为 PointsLedger 的新服务：

1. `cms_points_setting(type=1,event_type=14)` 第一行，status!=1（非 null）免费，pointsNum 优先 value，cost<=0 返回当前两条提示和 cost=0，不写积分。
2. 收费入口当前先普通读会员 point 并预检，再经 `UmsMemberPointsDetailServiceImpl.insertUmsMemberPointsDetail` 插支出；唯一键是 `ums_member_points_detail.uk_member_event_once(member_id,event_type,event_id)`，eventType=14、eventId=nodeId、changeType=2。**身份跨 activity、session、配置版本不变**，没有 TTL。
3. 插入唯一冲突时当前返回 hints + cost=0，不扣分；成功插入后调用 `UmsMemberServiceImpl.deductPoints`，底层 `UPDATE ums_member SET point=point-? WHERE id=? AND point>=?`，0 行抛异常，不是 false；捕获后标整事务回滚。
4. 顺序：activity→registration 当前资格锁（自玩无 activity），原活动再次锁读，lead/route，普通余额读→唯一 ledger 插入→会员条件 UPDATE。机械批不前移会员锁、不改隔离或收支顺序。
5. 当前 hint 端点**没有 actionId、expectedVersion 或不可变 response receipt**。不能虚构它们已存在，也不在本批生成 UUID、换 key 或把权益永久身份改成 member+node+session。`IPointsAwardService` 是正向发奖，不能用负数 award 代替扣分。现有 RegistrationFunds MySQL 的 `migrationKeepsLegacyEventUniquenessForWritersWithoutBusinessKeys` 只证明 event14 唯一键迁移保留，不证明完整 hint 用例通过。

机械等价不得被写成更强的原请求回执已实现。缺身份/版本、当前模板与原配置、免费与收费切换、重复时 cost=0 的业务定义如需增强，须单独冻结新合同/迁移与当前 caller；本轮无旧小程序兼容负担不是混改这些语义的理由。

## 7. 先表征，再分批：执行计划与证明向量

先对 root 绑定的联合旧候选运行同一组表征用例；机械候选再跑，比较同输入的 HTTP 语义和数据库向量。使用**两个受控测试 schema/候选**，不对生产双写，不复制旧业务实现到新服务作 oracle。每个结果含候选 SHA、actor/scope、响应 code/msg/hints/cost、余额、ledger identity/金额/数量、报名/进度/奖励向量、SQL 调用及锁顺序。只允许逐项登记的生成 ID/时间精度归一化；不忽略金额、状态、错误或 query count。

| 用例 ID | 表征/安全场景 | 必须保留的断言 |
|---|---|---|
| C-W09-01 | 活动与自玩合法首次收费 | hints 原值、cost 正确；一条 event14 支出；余额恰减一次；不发奖励/推进节点 |
| C-W09-02 | 无规则/0/停用、pointsNum/value、只配 hint2 | 原免费响应；零积分写；nodes 定价/扣发正文同源 |
| C-W09-03 | 匿名、坏 node、无 template/hints | 原拒绝 code/msg、无正文；余额/流水/权益/进度零变化 |
| C-W09-04 | A/B、跨 topic/activity、退款/撤资格、未开/已结束 | 原授权拒绝及零变化；不能用重放绕过读取授权 |
| C-W09-05 | 自玩过期但原局 continuing、非 continuing、带领集合/章未放行、前置/下架已完成 | 原分支/顺序/范围；受控负例覆盖每个 helper，而非提前拒绝假绿 |
| C-W09-06 | 同会员节点顺序重复、提交后丢响应、重新构造 service | 只有一个经济效果；原授权可读；精确登记现有 cost=0 返回，不伪称首次 response 原样 |
| C-W09-07 | 两连接同节点并发 | 真 MySQL 唯一赢家、一条流水一次扣分；败者不再扣；原响应合同；锁/清理实证 |
| C-W09-08 | 同节点跨 actor、跨 activity、模板/价格变动 | 精确显示现有身份覆盖范围；无擅自改 key 或释放历史唯一身份 |
| C-W09-09 | 余额预检足够后被另一消费扣至不足 | 条件 UPDATE 拒绝；刚插 ledger 与余额同事务回滚；无孤立解锁事实 |
| C-W09-10 | ledger 插入前故障、插入后扣分故障、扣分写后抛错 | 业务拒绝/异常路径与旧链一致；余额/ledger 均回滚；重试仍只一次 |
| C-W09-11 | 同会员不同节点/其他积分消费交错 | 余额与流水快照守恒；不得用 Mock before/after 当真实当前读 |
| C-W09-12 | Controller 适配 vs 直接 application 调用 | 应用测试无需构造 Controller；真实 Spring manager/代理有效；HTTP auth/RateLimiter/包装保持 |
| C-W09-13 | 其他共享 helper caller | 原 routeActionId/expected、奖励、照片来源、扫码首次时间、偏好和 nodes 展示不漂移 |
| C-W09-14 | 注册整事务回滚、DB 故障、读 helper 错误 | 不通过吞异常、换 runtime、放宽资格或扩大 baseline 取得等价 |

现有 `ApiPlayHintUnlockTest` 有 15 条 Mock 表征（包含正常、拒绝、唯一冲突、线程并发及 nodes），不能证明真实唯一约束/事务。新增真实 MySQL 测试使用真实 points/member/ledger/registration Mapper 与实际 transaction manager；无关 provider/runtime 查询可明确替身，不能把授权锁、扣款、唯一流水和回滚替掉。CI 必须显式必要环境变量、受限 DB_SUFFIX、跟踪本 suite 创建的精确 schema，AfterAll 关闭本 pool 并只删本 schema；不复用会漏清理的默认建库行为。所有 Java/MySQL 仅由 root 在批准 CI/VPS 执行。

建议提交顺序：

1. **W09-T 表征批**：先只加测试/最小合法 fixture，在原联合候选真实跑。发现旧错误时保留红证据，不改期望洗白。
2. **W09-B 独立行为批（如有）**：根因修复与明确业务决定单独审核、验证；冻结修正后的旧路径作机械 oracle。不能把已知越权/资金错误作为“与旧代码一致所以通过”。
3. **W09-M1 共享 helper 机械批**：14 个实现归位，原入口委托；全相关旧测试及锁/SQL向量等价。
4. **W09-M2 完整 unlock 机械批**：hintUnlockCost 与完整命令归入 PuzzlePlayService；移走入口事务，完成 direct-application/HTTP/真库 T14 对照。
5. **联合验收**：root 单一合入接口/owner/门禁登记、重建 ledger，运行同 SHA 全量和所需 IT。仅这个用例满足 T14 后可报 WP09 首批用例完成，其余入口不报完成。

## 8. 源码已见的风险/决策项（未冒充实测）

- **余额预检早于重复判断**：已解锁但后来余额不足会在遇到 duplicate 前被拒；是否改为原权益优先读回，以及旧回包/当前内容的定义，属于独立行为合同，不偷偷调换到机械批。
- **流水快照是普通余额读**：同会员不同消费交错时 current_points/after_points 可能不是实际扣款前后余额。余额 UPDATE 条件本身不证明流水快照守恒；C-W09-11 必须真库测，若失败先修旧链、再迁移。无需猜金额或放宽守恒。
- **leadGateError 的 catch 返回 null**：原带领状态 DB 异常可能被当放行；这是源级风险，未运行漏洞用例。应先给具体故障负例，按 fail-closed 合同单独修，不能在迁移中保留成已认可安全规则。
- 单参 Java fallback、无 action/version、按当前 template 重读正文、rule 关闭免费的既有行为不宣称满足所有 WP05。需要 root 明确严格命令合同与迁移边界；不以“旧客户端兼容”争取放宽。

## 9. 精确文件所有权与旧测试影响

**本 doc-only 提交**：仅 `docs/delivery/architecture-day/C/wp09-plan.md` 和 `wp09-inventory.json`。

**确认后的 C 独占建议**（未开始）：

- `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiPlayProgressController.java`：仅上述入口/纯委托适配。
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/IPuzzlePlayService.java`：共享接口只追加两方法，由 root 单一合入合同；现有方法不改。
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/PuzzlePlayServiceImpl.java`：完整新方法、唯一 cost 真源；现有 requestHint/reveal/score 不混改。新增依赖必须生产必需，无 null 直跑 fallback；现有四参构造的旧纯谜题测试仍单独验证，测试新用例须显式装完整依赖。
- 新 `chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/PlayNodeAccessService.java`：共享原规则唯一实现及有限 nested records。
- 新 `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/dto/NodeHintUnlockCommand.java`、`domain/vo/NodeHintUnlockResult.java`：上述不可变 DTO。
- `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiPlayHintUnlockTest.java`、`CurrentPlayControllerFixtures.java`：旧场景保留、显式接线，禁止把原断言改成 any() 或只测早拒绝。
- 新 `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/NodeHintUnlockApplicationTest.java`、`business/it/PlayHintUnlockMySqlIT.java`：表征/独立应用/真实存储。确需其他旧 fixture 改动时只从下述清单取最小 delta，先登记，不批量替换。

**root/原 owner 保留**：PointsAward/UmsMember/UmsMemberPointsDetail 的生产实现与 Mapper/唯一键、CurrentPlayRegistration、ApiClubController、RegistrationFundsMybatisIntegrationTest、ThemeRuntime/APPLIED 测试、根 pom/CI、架构 owner/command-contracts 与 inventory/ledger。若 W09-B 确认积分旧缺陷，先请求对应 owner 的精确合同，不跨写其文件。

影响清单完整路径已在 JSON：55 份 Java 测试直接引用此 Controller，41 份显式 new；含 subclass/反射/安全/DeadlockRetry 契约，不能只跑名为 Hint 的测试。重点包括 ApiPlayHintUnlock/PuzzleHint/UnlockGate/LeadSpectatorGate/SelfPlayPassGate/CurrentUseBoundary/OpeningTransaction/ProgressCompletionTruth/PhotoRetryAndFailureContract/DelistedCompletedRetry/RouteConflictHttpContract/CheckinLiveCode，以及 JourneyPublishFreeze。四份直接 new PuzzlePlayServiceImpl 的测试为 ApiPlayPuzzleHintTest、JourneyPublishFreezeTest、PuzzlePlayServiceImplTest、PuzzleRevealArrivalFactTest；现有 ApiClubController.topicNodeAnswer 对既有 puzzle 方法也需合同回归，C 不擅改该控制器。

当前小程序关联 `longrun-own-c01-28.test.js`、`play-c-fixes-0916-contract.test.js`、`play-chapter-story-blocks-contract.test.js` 与 root action ledger 对应 hint 控件；本轮无小程序改动，实施后按同源再次核对。架构 owner 应验证新应用入口映射、依赖图/Spring 装配无新增环、SQL写权与读预算；不新增 @Lazy 掩盖环、不扩 baseline。

## 10. 回退与确认范围

无新 schema、DDL、双写、账本回填或功能开关。机械批回退只替换同数据合同的代码，不删除 event14 消费、恢复余额、释放唯一身份、发奖或改当前 runtime。行为修复与机械批独立提交，使回退不重新引入已修资金/授权漏洞；必要时停新 hint 解锁，保留已解锁事实和授权读回。

请 root 确认：首用例/上述新增文件与共享接口 owner、精确跨域读依赖迁移登记、W09-T→B→M 的顺序及风险决策责任。确认前仅保持这份 doc-only 方案；不是申请部署、DDL、Java VM 或 CI dispatch 授权。
