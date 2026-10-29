# H：D authoring 六生产文件复审

结论：未发现可证的新 P1/P2。候选 `dea48565cc8789df7a9aac44a01657d2924e0985`、tree `7ada3a2ecb728eb25368a7c49bee335a23ab619a`，实际 parent/base 为 `91962c94af463cbfe9b4d4406f9d88d269b097e4`。这是源码审查结论，不是 Java/SQL/启动或新旧运行等价通过；不覆盖尚在进行的 root4197 rebase。H 未改任何实现/夹具，未运行 Java/DB/CI。

H 开工 HEAD `b44a98bd2c8662567a9990064168e503b6b912dd`，独立工作树 `/workspace/hengyin-h-review`、分支 `codex/architecture-day-h-review`，开工干净。只新增本目录报告/静态索引。

## 关键链结论

位置相对候选 `chengyinhub-system/src/main/java/com/chengyinhub/business/service/`：

- **外层授权与事务保留。** `impl/CmsTopicServiceImpl.java:3412–3429,3779–3802` 的六个 create/edit 公共入口仍有原 `@Transactional(rollbackFor=Exception.class)`；方法体未改。3808–3823 保留首次 owner 校验、V2 supply→audit→topic 锁序及锁后 owner 重验；旧 blocks 拒绝、编辑窗口/钱闸仍在原 facade。新增 graph 是内部写口，没有新增 Controller/公开请求入口；不能把它当作另一个独立授权边界。
- **图仍在原事务里写。** `impl/TopicContentGraphAuthoringServiceImpl.java` 的公开写方法使用默认 REQUIRED，既不 REQUIRES_NEW，也不 afterCommit。外层异常仍向外抛出；先写章/节点，再 template/story/route/runtime/interaction，继而原 XP catch、collaborator/ticket/review/活动壳编排。新增 graph 调用不位于原 XP 吞错块内部，未发现因新增事务代理使该已知保留路径变成 rollback-only 的新触发链。直接 new 的测试装配不等于 Spring 事务已实证。
- **V1/V2 顺序保留。** facade:4207–4241：先读取 hidden；compiled V2 先删除非 flow 子项，再进入 compile、完整章/模板校验、删除 flow、重建；V1/非 compiled 仍先删除 flow→非 flow，再写 flow。随后 hidden→template remap→unlock→story→runtime→interaction→route CAS；原 expected/request/target version 表达式不变。graph:293–386 的校验、删除点和异常再抛与原算法匹配，未把 V1/V2 合并成新通用顺序。
- **共享 guard/reader 未改规则。** `support/TopicContentWriteGuard.java` 保留原7个方法定义，包括 REVSHARE 的短路及 Topic→slot 左到右读取、私有模板/组合草稿判定。`TopicRouteOutcomeReader.java` 保留原 outcome 读取与分支，只冻结返回 map/set；未引入新事务或回调。检查真实 validator/runtime/story 消费处，未发现它们修改这些输入集合的既有路径。
- **不可变值没有带出工作。** `TopicAuthoredGraphIds.java` 复制有序 map/set，ending JSON 在入值和出值时 deepCopy；Builder 的 record/recordChapter 保留原身份检查，writer 结束后 freeze。没有 DTO 缓存、后续可执行动作或延迟落库。
- **DI 源码可接。** 新 graph 为 `@Service` 并实现新接口；guard/reader 为 `@Component`、各只有一个构造器。新增依赖来自原 facade 已有 mapper/template/runtime/product-role/category；检查真实 template/runtime 实现，未发现新增直接反向依赖 facade/graph。没有可证的新增循环或缺实现；完整组件扫描、代理、profiles 下的装配仍需启动验证，不凭手工 wiring 签通过。

## 实际执行与保护核对

Python 校验22份读取文件 Git blob 身份。独立方法体比对确认：

- 168个其他 facade 方法体逐字一致；旧 facade 注解 delta 仅新增3个注入，没有改原事务注解。
- 25个迁移定义在明确的 owner/Builder/不可变返回替换下匹配；移出23定义，保留2个 shared 薄 delegate。
- 四处 facade 编排在逆转明确委托后逐字恢复原方法体；两段 inline runtime 写入仅 owner/身份变量名及缩进变化。
- 抽查原 `ChapterFlowTransactionTest`、`TopicUpdateByOwnerTest`：仅移除新增装配调用便逐字恢复原文件，原失败/owner/回滚断言未改。未声称独立验完其余366项。

以上均是静态结果，见 `SOURCE.json`、`STATIC.json`。没有发现须新写负控的具体生产失败机制，因此不复制实现或再造模型测试。

必须由 root 按最终同一源验证：原366 case、snapshot新增3 unit、32 SQL及完整清理；六入口实际代理和 owner 负例；V1/V2 删除/校验/写入顺序、第二章失败及 template/story/runtime/route/ticket/review/调用者回滚；新旧32组结果/异常/mapper写序严格比较；完整 Spring 装配。新 IT 虽为 facade/graph 加了事务代理，template/role 等仍有领域 doubles，不代表全服务/HTTP/RBAC或所有资金路径。旧 copy 的25组绿不移作本候选证据。

无新增生产 schema/依赖/迁移；资金、发布、销售、transfer 的其他方法体保护在本源保持。代码回退须六文件及 facade 接线整体回到919，不删内容/资金/回执。既有业务风险和资金 P1 不因机械等价或本次无新发现而消失。
