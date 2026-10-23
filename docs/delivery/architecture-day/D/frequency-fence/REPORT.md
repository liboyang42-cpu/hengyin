# D-P2：不同广播共享成员频控锁（候选，未完成 Java/MySQL 验证）

结论：补上广播站内投递的 member 级事务围栏；频控读取、真实 IM 写入与 SUCCESS 在同一个短事务内。保留超额任务原 PENDING/FAILED 身份供窗口过后恢复。生产源码仅改 OfficialBroadcastServiceImpl，未触碰微信投递、鉴权、资金规则或其他领域实现。

## 基线、隔离与依赖

- 公共仓库：liboyang42-cpu/hengyin；公共基线 2ec68899099a1e250df0949a8383bf85b5d3ffe0。
- 本次远端提交父身份：冻结候选 e71777f375fcbb19a17b46647c36391ec03ed3b4；父源码树 7a836874abeec1d9af3a23770dcc5d7e6868a4cf。
- 独立目录 /workspace/architecture-day-d-frequency，分支 codex/architecture-day-d-frequency-fence。原 D 与 WP08 工作树、分支和候选未改写。
- 本地 HEAD 仍为公共基线：在新工作树应用原 D 已保护完整补丁，git write-tree 精确复现父树，再实施本次增量。通过 GitHub tree/commit/ref API 发布，指定 e717 为唯一 parent，不能把本地 HEAD 差异误当本次增量。
- 依赖仅 e717 的广播状态机及候选迁移；与 WP08 文档候选、A/B/G 资金规则无源码依赖。不把本次补丁反向塞进 e717。
- H 所给 4f1096a2 不在本地对象库；公共 GitHub fetch_commit 返回 422 No commit found for SHA，报告路径同 ref 返回 404。未宣称已读该补丁，按协调者给出的确定反例独立补回归。待协调者将 H 测试纳入联合候选时，应保留其关键断言。

## 调用链与失败机制

入口 publish 的 afterCommit 唤醒及 runDueScheduled → dispatch(NOT_SUPPORTED) → prepare 的独立事务冻结身份 → 循环处理日志 → deliverInapp 的 REQUIRES_NEW → IImService.sendSystem 的 REQUIRED → systemMessage → im_conversation 锁/成员关系/消息写入 → 回执 SUCCESS → 提交。

旧频控每 member 在循环外读取一次，不同广播没有共同围栏；已有 4 条时两个 dispatcher 都读取 4，虽各自 delivery 行锁不同、IM 会话最终串行，仍会各增加一条，累计 6 条。IM 会话锁前没有重查频控，不能挡住该竞态。

修复合同和锁序：

1. 新短事务先调用现有 UmsMemberMapper.lockMemberById，实际 SQL 为 ums_member.id 主键 SELECT FOR UPDATE。没有该成员则保留意图并零发送。
2. 持有 member 锁后才锁原 delivery 行；终态/UNKNOWN 不发送。核对原行 member 与所持成员锁一致。
3. 在本次新事务第一次一致性读取时重查 countRecentByMember，仍用既有 SUCCESS/inapp/60 分钟口径。prepare 和事务外预检不共享这个 REQUIRES_NEW 的 RR 快照；lockDelivery 现有 flushCache=true/useCache=false 还会清除 MyBatis 本地缓存。等待 member 锁的另一广播会在前一条 SUCCESS 提交后建立新快照。
4. 达 5 条或读取异常时不发消息、不写假 SUCCESS、不删除/替换身份；窗口过后沿原日志恢复。
5. 未达上限才执行现有 sendSystem，在同一事务内写实际 IM、SUCCESS/message_id，提交才释放 member 锁。失败仍由原独立回查逻辑保留 SUCCESS 或标本地 FAILED。

本路径统一锁序为 ums_member → official_broadcast_log → IM system conversation → conversation member/message；没有持有 delivery 再申请 member 的分支。已查全部四个 lockDelivery 位置：错误回查、微信领取、微信回执只持 delivery，不再取 member；prepare/汇总只持广播主行且不会取 member。sendSystem/systemMessage/ensureMember 不申请 ums_member 锁，不调用外部微信。IM 的 lockReadMember 锁的是 im_conversation_member，非 ums_member。既有 member-first 的 badge 恢复模式保持。

事务外原预检继续作为既有各通道的提前筛选，**不承担站内上限保证**；锁内重查才是站内发送许可。保留原预检避免本次顺带改变订阅通道的筛选政策。微信仍先持久 UNKNOWN，再在事务外调用；没有引入租约发送证明或 UNKNOWN 自动重发。

## 文件清单与字段影响

- chengyinhub-system/.../OfficialBroadcastServiceImpl.java：增加现有 UmsMemberMapper 注入；站内短事务先成员后日志锁，再重查频控。无公共接口/字段/schema 更改。
- chengyinhub-admin/.../OfficialBroadcastDeliveryTest.java：fixture 注入成员锁；新增 3 个回归；原 12 个保留，总 15。
- chengyinhub-admin/.../OfficialBroadcastDeliveryMySqlIT.java：加载真实成员 mapper；新并发回归；原 7 个保留，总 8。
- chengyinhub-admin/src/test/resources/it-schema/official-broadcast-delivery-it-schema.sql：原两张广播表 DDL 不动；增加仅含 id 的既有成员锁测试表，位于该 IT 随机专用库内，不代表成员领域完整集成。
- 本目录 REPORT.md、verification.txt、CANDIDATE.sha256 为本任务增量证据。最终完整 tree/commit 在交付消息给出。

## 最小回归与实际执行

先写回归，再修生产路径。新增单测：过时预检 4/锁内 5 时不发第六条且保留原号恢复；member→delivery→频控→消息→SUCCESS 顺序；member 缺失时零消息/零 SUCCESS。

新增 MySQL 回归：用实际 IM 路径先生成 4 条；CyclicBarrier 强制两个不同广播的事务外预检都读到 4；并发完成后只允许一条新消息和一个新 SUCCESS，另一个原意图及其广播仍 PENDING。新 service 重跑满窗口不能发送；仅在隔离库把成功回执时间移出窗口后，原 deferred ID 恢复成功。调用微信桩次数必须为零。

**实际执行仅 Python/静态检查及 git diff --check**，输出见 verification.txt。修前静态检查为 FAIL，修后锁序/mapper/原测试名保留检查通过。静态结果不能证明 MySQL 隔离/并发、Java 编译或 JUnit 成功。

**未运行**：Java compile/package、全部 JUnit、原 D 的 7 项/追加后 8 项 MySQL IT、真实进程重启、实际微信。未在本云 VM 起 Java/MySQL/H2 或 runner。协调者提到联合编译 C 变量冲突已修，D 原 7 项仍未实跑；这里不据此宣称已有真库通过。

批准 VPS/现有 GitHub-hosted CI 中应核对新鲜 Surefire XML：OfficialEventContractTest=17、OfficialBroadcastDeliveryTest=15、OfficialBroadcastSubmissionControllerTest=4、OfficialBroadcastDeliveryMySqlIT=8，failures/errors/skipped 全为 0；必须点名 IT，不得只跑默认 *Test 后误判验收。

原 IT 自定义 CHENGYIN_BROADCAST_MYSQL_IT 断言按冻结 e717 保留。协调者已授权在联合 CI 候选删除该冗余断言、registry gates={}，保留显式 -Dtest 与 localhost/PORT/USER/PASSWORD 缺失硬失败；本次不重复改该接线切口、不加条件跳过。需在联合候选保留已做的删除，不能重新引入旧 flag 要求。没有相关凭据、受控执行输出或权限即明确未验证。

## 迁移、只读预检、回退与阻塞

本次无生产迁移或新增字段，不执行 DDL；只复用现有 ums_member 主键行锁。e717 的六列候选迁移仍单独审评、未执行。额外只读预检提案（当前未执行）：

```sql
SELECT table_name, engine FROM information_schema.tables
 WHERE table_schema=DATABASE() AND table_name IN ('ums_member','official_broadcast_log','im_conversation');
SELECT table_name,index_name,column_name,seq_in_index FROM information_schema.statistics
 WHERE table_schema=DATABASE() AND table_name='ums_member' AND index_name='PRIMARY';
SELECT COUNT(*) AS missing_member_intents FROM official_broadcast_log l
 LEFT JOIN ums_member m ON m.id=l.member_id
 WHERE l.channel='inapp' AND l.delivery_state IN ('PENDING','FAILED') AND m.id IS NULL;
```

确认 InnoDB 和 ums_member.id 主键。缺成员的意图交证据核对，不创建假成员或删除回执。经济影响为零业务字段更新；新锁可与既有同成员资金/权益事务短暂排队，应在受控 MySQL 验证等待和死锁边界。事务中没有微信网络调用。

本次代码回退会重新开放跨广播竞态；必须先停广播投递/恢复任务再回退，不能用删除回执、清数据或同时运行未加锁旧 worker 避免冲突。没有 DDL 回退或资金回滚。

验收阻塞：批准环境编译/JUnit/MySQL 并发实跑缺失；H 原报告/4f1096a2 尚无法从公开仓库读取；所有广播 worker 必须统一使用本修复，混用旧代码不能保证全局 cap。候选提交/推送仅供 CI 与审阅，不代表正式集成、合并或部署。
