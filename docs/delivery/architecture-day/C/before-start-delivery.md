BEFORE_START 最小完整候选；基线/父提交 `def951bef36a94417d90259906f6bda6abe6efb3`。

集成顺序：取本提交后，root 先 `git apply docs/delivery/architecture-day/C/before-start-team-group.review.patch`，再统一编译/测试。共享 TeamGroup 实际文件未修改；patch 只改提醒方法及其注释，静态确认 A 取消/captureCancellation/observe 和所有其它 helper 字节不变。旧批扫测试适配以该 patch 已应用为前提。没有 CI/registry/baseline 变更。

实现：复用 CommittedOfficialNoticeService、原表/Mapper/领取与终态 CAS，仅加显式 BEFORE_START 类型。广播 enqueue、旧请求 hash 字节、原 IM 次序、广播与其它订阅模板字段/渲染算法保持。广播仍强制 IM；提醒不创建 IM。新增 typed payload 与可空 IM 的约束仅容许 BEFORE_START，未新增 outbox。

原身份为 `before-start:<ticketId>:<memberId>`，永久唯一。冻结原票次、收件人、原题名、计划毫秒和同一 BEFORE_START 模板的 openid/templateId/page/五字段；字段仍为 thing1/date5/thing8/thing7/thing12。原模板独立配置，不回落广播模板，用户授权仍由原微信订阅发送流程处理，不新增或绕过授权。prepare 不取 access_token、不发外呼；配置/用户信息变化不重渲染已落意图。

执行：短事务保存 PENDING；短事务同原 owner token 领取并提交 UNKNOWN；无活动数据库事务/锁时发送冻结消息；短事务以原 token CAS 保存 ACCEPTED。false/抛错/响应丢失/终态写入失败均保留 UNKNOWN，不按租约/TTL 再领。已受理重放只读，deliveredNow=false，不再累计本轮送达。原 token 的迟到受理可由既有 Mapper CAS 处理，错误 token 拒绝；没有新增人工授权接口或自行确认成功。

唤醒：仍由原未来 24 小时票次、已支付成员查询进入；领取再核原计划时间。通用官方通知恢复只枚举广播，不能绕过当期付费/票次准入发送提醒；提醒由当前合法批扫唤醒，已过期 PENDING 保留，UNKNOWN 只读。原计划/题名变更同键冲突，不自动换号。旧成功 Redis key 仅保留读取兼容，新结果以持久事实为准，不再写两日 key。任务仍沿原 failed 计数告警，不将待核实计成送达。

实际验证：`python3 docs/delivery/architecture-day/C/before-start-static-check.py` PASS；原广播 hash/enqueue、原通道渲染主体、13 原广播 IT、5 原 date5、18 原 fanout 及 4 原任务告警正文保持；Mapper XML 解析、Java 词法括号检查及 git diff --check 通过。这些是静态证据，不是 Java 或 MySQL 通过记录。未跑 Java/数据库/HTTP/微信外呼/启动/CI，未执行 DDL。

交 root 实跑：BeforeStartPreparedWxTest 新 6 例（真实渲染/post 测试缝）；BeforeStartNoticeMySqlIT 新 12 例（同规范迁移/真实 Mapper/事务，provider double）；BeforeStartNotifyRetryContractTest 原 5 场景按新合同适配并加 2 例，合计 7。原 false→retry 断言已拆为 NOT_READY 明确未发送可首次执行、UNKNOWN 只能原身份读回。两层各有边界，端口 mock 不冒充存储/授权验收。

存储 12 例覆盖：持久 UNKNOWN 先于外呼且零 IM、PENDING 崩溃与重启恢复、成功顺序重放、false、抛错丢响应、ACK 写后抛错真实回滚及原 token 迟到回执、双 worker 并发、异题名/计划冲突、票次×人隔离、未就绪/窗口/过期 PENDING、payload 篡改/读库失败、外层事务与缺回执零调用。provider 断言在真实事务之外；新测试未执行。

迁移候选：`chengyinhub-admin/sql/migration_20261024_c_before_start_notice_typed.sql`。须先 D 原表迁移；规范旧行自动默认广播，不改旧 hash/金额/回执/唯一身份，原 UNKNOWN 领取条件不变。具体预检/回退限制写在 SQL 注释；实际生产 DDL、job 启用和付费/真实外呼均未授权执行。

部署前既有任务须受控暂停并核旧记录：旧 Redis key 缺失不是历史未发送证明，旧无回执的可能已发送项必须查供应商/人工核实，不能补造“未执行”或静默回填受理。保留旧正向 key；出现 BEFORE_START 行后不得删 payload/行、制造 IM id 或重置 UNKNOWN 来回退。这是切换前置限制，本候选未作生产操作。
