# E：ecff 完整恢复的 current allocator / 差异证据 / 每 boot 日志闭环

交付结论：3个 Python 源码增量 + 3份交付文件；全部测试、解析/import、编译、Java/DB/HTTP/Docker/故障/CI NOT_RUN。不改 canonical tool、Java child、B/F生产或测试、workflow、SQL、权限、金额或恢复放行。历史目标差异的具体原因仍 UNKNOWN；不拿新源码回签原 run。

## 公共身份与证据

- parent ecff37372bfae059faa38e9eb420894cde1bcee9；tree 17ef62c85313de126c56878fb336327ae05131f0。
- 原E旧工作区 /workspace/hengyin-e-final-reuse-coupons、HEAD037afa2eb546402460738e5767c8df86e246cf1f保留；此包隔离目录 /workspace/hengyin-e-restore-compare-ecff。只从公共parent读取原像。
- root 已独立核 run40243433436/job121319922478/artifact12617924102（ZIP SHA256 3710c5e5d6b4c0276b7a88ceecc387114814afa19cbf8cbe9d6f9c9c5a52d998）。source false真实boot/PID1/Quartz/原提现完整事实与原章回执、READY_JVM_EXIT_CONFIRMED均已越过；新首因是 RESTORED_SQL_COMPARE。
- 原artifact没有target typed快照或差异表。因此真实差异是行/类型/nextId/集合中的哪一项仍 UNKNOWN。没有修改目标来凑相等，不推断生产丢数据；ebd前一轮的历史IllegalStateException亦不重判。

## 当前高水位：确认的独立采样缺口与修补

原 snapshot 通过 query() 每次新开连接读 TABLES.AUTO_INCREMENT，没有同连接 expiry=0。官方MySQL8.0.45源码：
- [tables.cc](https://github.com/mysql/mysql-server/blob/mysql-8.0.45/sql/dd/impl/system_views/tables.cc)：AUTO_INCREMENT经INTERNAL_AUTO_INCREMENT消费stat.auto_increment/cached_time（root核blob66e0d14e44509cdc1da31d8297173743740fea58）。
- [table_stats.cc](https://github.com/mysql/mysql-server/blob/mysql-8.0.45/sql/dd/info_schema/table_stats.cc)：session information_schema_stats_expiry=0使缓存过期；未过期可直接返回table_stat_data（blob67f736d546b78dee67180e596c40e88d6c1cbd17）。
- [官方手册](https://dev.mysql.com/doc/refman/8.0/en/information-schema-optimization.html)：默认86400秒；0从引擎取当前动态元数据。

此事实证明原采样缺乏新鲜度保证，不证明原run差异就是缓存。新 snapshot(auth,port,schema,expected_uuid) 必须在同一真实 auth.database(port,schema) / cursor 中依次 SET SESSION expiry=0 → 读回整数0+server_uuid+DATABASE → 原TABLES完整查询。UUID/schema必须等于原受控身份；不会将另一连接SET当证明。全表行仍用原query/typed编码读完，不新增表过滤、LIMIT或高水位fallback。只改SESSION，不改GLOBAL/ANALYZE/业务数据。

全部实际调用已迁移：source_cohort及source_quiescent_snapshot使用原source/ready UUID；normal、held三模式、callback/replay及其报告的全部target snapshot使用原target UUID。原query(args=None)的driver合同不变。原无参snapshot不是公开API；直接fixture补相同原schema前三参和新增UUID的独立断言。

## canonical 工具与严格独立比较

精确读取 scripts/db-backup-restore.py（SHA256 fead7bfce99af5aadeb15b696190fade40109f37d25233aa401116d10ea1279c，未修改）。v3工具用SHOW CREATE的完整定义hash（含原AUTO_INCREMENT DDL、列charset规则）、COUNT/CHECKSUM及view/routine/trigger/event对象证明；备份前后比较、不同UUID同版本、空目标、dump receipt和restore后校验均保留。工具的TABLES object_rows只用于对象枚举；不以其COUNT/CHECKSUM替代本独立全行/nextId断言。

原 whole snapshot == before 仍是必要条件，另比较canonical bytes，防Python bool==int掩盖type-only变化。无目标回填、重写ID、忽略nextId或新增例外表。原三个ALLOWED_MUTABLE不扩大。

不相等时：
- 固定来源是原source表名roster；每表只给缺表/新增表/ROW_COUNT_DIFFER/TYPED_ROWS_DIFFER/NEXT_ID_DIFFER、原/目标rowCount、完整rowSHA和带类型nextId。未知target名字仅hash，不公开其文本。
- 详情最多32表/64KiB；changedTableCount、source/target全snapshot hash覆盖全部比较。截断只限制诊断，不截断谓词、数据读取或拒绝。
- 绑定source/JAR/run/attempt/schema、原source/target UUID、fresh独立UUID读回、原source stop identity/exit与日志摘要、精确toolSHA、dump/receipt SHA以及工具真实正常返回事实；不伪造工具OS exitcode。
- UUID读回、hash或报告写失败只附固定异常类型/阶段码；原ValueError、原可识别消息与INVARIANT_REJECTED保持。没有原行、SQL、rawdump、token、password或异常cause输出；不假定差异原因。

## 原停止容器和日志生命周期

source_quiescent_snapshot 改为 stop(remove=False)：真实ready/stop证明和原全表快照顺序不变，canonical阶段任何失败时原source CID仍可供原outer owner取日志；不再主动造成本轮已证的UNAVAILABLE。

每个原已创建CID（最多原5个）：
1. 沿原running/pre-ready stop proof；保持20秒、command30秒、0/143和137/OOM/身份冲突拒绝。
2. 停止后再核原CID/name/owner/image/env、原JAR/config/ready文件hash及不可变process identity，确认没有恢复运行。捕获原完整stdout+stderr到私有boot-log-N.private，fsync/权限0600/读回SHA和大小。超过8MiB或命令失败即拒绝，不能截断冒充完整；沿原owner的20秒Docker logs方式，不声称新增了流式传输硬终止预算。
3. 只有完整私有capture验证后才能rm。重复source/final stop只复核同record、同identity和私有文件，不再次采集/替换旧记录。保留每boot的source/JAR/config/startticks/stop状态与安全log digest；cid=None也能给lastStoppedEvidence，不伪造current CID或absence。
4. 后续新boot沿原stop/rm/independent absence再create。所有private raw档保留在同一私有subtree，最终逐个读回验证，先持久写原JSON中的安全history，再精确擦除私有subtree；原最终停止容器仍留给不变的outer owner保存原application.log。raw per-boot档不是新增public artifact。
5. 缺日志/漂移/无法stop时不擦除私有证据、不移除容器、不声称quiescence；原首因优先。full-success还必须有与5个原boot逐个匹配的ready stop/log history及原精确private erasure。原UNAVAILABLE守卫和finalizer拒绝不删、不忽略；资源gone仍不等于业务通过。

本包不会为了“保证日志可用”修改共享auth/cleanup合同；若原create回包丢失等路径确实无可观察当前CID，记录safe历史与UNKNOWN并仍拒签，而非伪称outer日志成功。

## 直接控制 SOURCE

原所有场景、金额/授权/UNKNOWN/回执/清理断言保留；fixture仅补stop_history/完整log bytes、同源有效UUID和新expected_uuid实参。source stop调用断言同步为remove=False；原source前三实参保留精确断言并增加第四UUID断言。5项Java接线测试源码和真实child全部逐字不变。

新增18个Python方法（subTest分支非新增case数）：
- 8项canonical：全等不额外诊断；真实行漂移不修复且不泄密；allocator回退/前进/null/类型拒绝；decimal/binary/float/temporal/布尔类型不归一；缺/增表；32项详情限制不影响完整拒绝；UUID/DB失败保原错；报告IO失败保原错。
- 6项日志：source停后保原CID；重复stop只采集一次且identity漂移拒绝；缺/坏archive阻止rm与擦除证明；完整stdout/stderr和size/exit拒绝；history缺失/foreign/preready/137/UNAVAILABLE拒绝；outer日志不可用即使资源全部absence也仍NOT_ACCEPTED。
- 4项current allocator：同连接四步骤；另一会话SET无效；缓存/错误UUID/schema/非整数0拒绝；fresh null/0/高水位与真实行变化仍严格拒绝。

这些是transport/cursor/file doubles上的直接控制源码，不能冒充真实MySQL缓存、完整恢复或生产RPO/RTO/UAT通过。

owned.patch只含3 code增量；提交另有README、manifest、patch，共6文件。逐原/后像和完整patch SHA见manifest。只允许git diff/applycheck/hash/有限秘密扫描；无可用gitleaks时完整扫描由root统一安排。所有新实际运行证明均NOT_RUN，无新资源、依赖、DDL、费用或发布。
