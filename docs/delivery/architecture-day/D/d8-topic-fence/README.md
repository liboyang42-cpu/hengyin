# D8 topic 栅栏核心候选（禁止脱离三个 caller 接线单独集成）

> 本页记录历史4061核心交付状态；后续候选已补齐三个caller接线，见 `../d8-caller-closure/README.md`。两批均未在本执行器运行Java/MySQL，不以源码完成替代实际验收。

Parent：`5d25cbce8b813f9bf3ef9bc57c8f71187fc082b4`。Java/MySQL/HTTP/CI：**NOT_RUN**。未执行 DDL、未改 schema/依赖/金额/权限政策。

已实现：`TopicContentWriteGuard` 是 D8 库内预算的真实 owner；`lockTopicContent` 使用同一 `cms_topic` 行，原图保存/重建/删除在碰章节、节点前持有该行至原事务结束。保存的原两处 payload 30/300 校验保留；图写后再按实际存量复核，失败由原 REQUIRED/外层事务回滚。发布 guard 委托同一预算 owner。

三个专属 Mapper 查询：父主题 `SELECT id ... FOR UPDATE`；章节、节点 `COUNT(*) ... FOR UPDATE`。均 `useCache=false,flushCache=true`，当前读避免 RR 旧 read view 与一级缓存；不再使用玩家可见 list。章节/节点实体无 del_flag，现 mapper 使用物理 DELETE，所以待审、status=0、退出商家的未删除行均计数，物理删除才释放预算。不存在的父主题、无法核实/负计数、整型溢出均 fail closed。

必要测试接线：既有 `TopicPublicationTestWiring` 为原 mapper doubles 提供新窄锁查询的 ACK（保留显式 stub，不改旧身份/权限查询），并把真实预算 owner 接到原 collaborator doubles；原 `TopicSizeLimitsTest` 四 case 与 `TopicPublicationSizeEntryTest` 八 case 名和断言保持，仅新查询 stub/owner 装配变化。原图32 case/62 scenario/SQL/receipt/comparator不改。新增8个 guard unit、8个真实 MySQL IT 源码；MySQL fixture复用原隔离 scratch/清理，不新增 registry/CI。

新增 MySQL 范围：物理隐藏/待审节点计数、物理删除释放预算、31章拒绝、RR 快照后的当前读、图写超限全行回滚、两个299起点图写者只能一个提交、发布预算锁阻塞图写者、回滚释放栅栏。不宣称完整 Spring/审核/财务 mapper/process-kill；尚未执行。

## 仍需同批应用的精确 caller 扩展

1. `CmsTopicChapterServiceImpl.lockAndAssertChapterMutable/lockAndAssertChaptersMutable` 当前先持有 chapter 再申请 topic；须用 locator 收集父主题、按 topic ID 升序先锁父主题，再按 chapter ID 锁子行并复查归属；保留原 scope/lifecycle/条款/资金校验。更新迁移 topic 时须同时锁旧/新父主题；批量删除不可反向补锁。
2. `CmsTopicNodeServiceImpl` insert/update/delete目前未先锁父主题。须在原 CRUD/商家节点调用链共用父主题栅栏；迁移 topic 时锁旧/新主题并复查 locator，不能只修 graph owner 后宣称所有写者已 fence。不得变更原授权/商家审核规则。
3. `AuditTaskServiceImpl.review/applyModeration` 的 TOPIC APPROVE 接线。`review` 的精确审计回执回放在业务调用前已直接 return 1，须保持；`alreadyPublished` 是新审核决定的分支，仍更新 status/check_status/user_status、清 review/hold 标记、同步广场并可能建活动壳，因此不是纯回放，必须在这些原副作用前做同预算检查。scope_report 的“不成立维持原状”早返回不能加拒绝；拒绝决定不需要发布预算闸。外层审核回滚合同保留。

以上三个生产文件不在本轮明确列出的新增独占文件中，已向 root 请求精确接线授权；当前未改它们。**此核心候选尚不关闭全部D8，不可单独集成后宣称并发已安全。** 还须核对既有资金/购票的非内容 chapter 锁调用者，不能改 G 金额算法来消除锁序冲突。上述 count current-read 会持有子行锁，root 必须连同实际 caller 锁序审查后统一验收。
