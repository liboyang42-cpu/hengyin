# E / WP11：IM 红点与会话批量读取候选

结论：已完成最小批量读取候选，普通单聊列表从代码路径的 `1+4N` 次查询调用合为一条批量 SELECT，红点从完整列表投影改为一条独立 SUM。群资格校验、群资料展示及路线卡权限投影仍沿用原路径，不能声称所有场景 N+1 已消除。实际只通过静态与补丁检查；Java 编译、JUnit、真 MySQL、EXPLAIN 与性能测试均未运行，尚未满足合并验收门槛。用户后续已明确授权仅隔离 CI 候选提交/推送的例外，不授权合并部署。

## 基线、隔离与冻结候选

- 公共仓库仍为 `https://github.com/liboyang42-cpu/hengyin.git`。
- 建立第二批分支时的 HEAD：`2ec68899099a1e250df0949a8383bf85b5d3ffe0`，第二批独立分支 `codex/architecture-day-e-wp11-im-batch`，工作树 `/workspace/hengyin-e-wp11`。原 E 工作树 `/workspace/hengyin-e` 保留，不切换或重置。
- 没有切换原工作树或创建其他会话/Agent；只从公共基线建立第二批工作树及分支，未修改其他任务文件。允许扫描后创建/推送隔离 CI 候选，运行未通过不得合并或部署。
- 首批路线缓存的三份源文件 SHA256 保持不变，冻结 tree 仍为 `9fcff59e1dfe0da5fbf265125d35acd30a7214b1`。本目录是第二批交付，上一批报告是当时只读 IM 的快照。
- WP11 独立源码 tree：`d62ceb5f19683292a529cdc5fa9399bc00b2912e`（公共基线加 WP11 八文件）。
- 冻结缓存加 WP11 的参考合成源码 tree：`dff239cc5be7507d2e24729b10d022321874e3f8`。
- `WP11-candidate.patch` SHA256：`d267264ff83dbd7e4b07cdd8469cbb8a1b780d195ea59f27816c8ea8aa02bcbc`。
- 上述均为候选 tree / diff 身份，不是 commit；`candidate.json` 含八份文件 SHA256、测试数量和全部未运行标记。源码补丁不含首批缓存和交付文档，可独立应用于基线或已有冻结缓存的集成树。CI 提交直接保存八份源文件；原始补丁留在原 E 交付目录，不重复放进 CI Git tree。可按 candidate.json 的 patch_export 重建，并核对原始补丁 SHA256。本 CI 分支只包含 WP11，三个缓存路径保持公共基线；合成 tree 仅用于协调者核对，不混入第二批提交。

## 独占文件清单

| 仓库相对路径 | 内容 |
| --- | --- |
| `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ImServiceImpl.java` | 列表消费批量读模型；红点消费精确口径聚合 |
| `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/ImConversationMemberMapper.java` | 两个只读方法及 mapper 内嵌列表 DTO |
| `chengyinhub-system/src/main/resources/mapper/business/ImConversationMemberMapper.xml` | 批量 JOIN 及红点 SUM；既有语句全部保留原文 |
| `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/ImConversationBatchReadTest.java` | 新增 7 个服务单测 |
| `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/ImConversationBatchReadMySqlIT.java` | 新增 4 个受控真 MySQL 测试，缺显式参数失败、不跳过 |
| `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/ImServiceImplTest.java` | 适配列表夹具；原红点过滤责任转至真 SQL 用例，服务层改断言只调用正确聚合；仍为 53 例 |
| `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/ImRouteProjectionTest.java` | 适配批量返回夹具，保持 9 例投影断言 |
| `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/ImSendIdempotencyMybatisIntegrationTest.java` | 临时库补最小 `ums_member` 展示字段表；保持 14 例 |

只在本目录添加报告、静态核对脚本/输出和补丁。没有改控制器/API、鉴权/RBAC、其他域 mapper、资金/回执/意图、schema/迁移、pom、CI 或小程序；没有增加依赖或业务缓存。

## 实际调用者与结果合同

开工再次搜索全仓服务调用和小程序 URL：

- `ApiImController.conversations`：`POST /api/im/conversations -> listConversations`。调用者为收件箱 `subpackageB/pages/im/list/index.js`、聊天页 `subpackageB/pages/im/chat/index.js`、商家工作台 `pages/merchant/index/index.js`。
- `ApiImController.unreadTotal`：`POST /api/im/unread-total -> unreadTotal`。当前小程序调用者为 `pages/talent/list/index.js`。
- 小程序 `tabOf` 排除 TYPE_GROUP=4，但后端会话列表仍按实时群权限返回有效群；不能直接从服务列表删掉所有群。红点既有口径则排除所有 TYPE_GROUP=4。
- `selectMyConversations` 原本 JOIN 有效会话并过滤当前成员、`deleted=0`、`status=0`。批量读保留同样条件，不根据调用者提供的任意 conversation IDs 扩大读取范围。
- 最新消息继续按 `status != 2 AND (msg_type != 2 OR check_status=1 OR sender_id=当前读者)` 取 `coalesce(visible_at,create_time) DESC, id DESC LIMIT 1`；待审图片只对发送者可见。撤回状态仍按原条件读取，没有自创屏蔽/撤回规则。
- Java 最终排序比较器原样保留：可见时间倒序，其次消息 ID 倒序；均无可见消息时保留查询输入顺序。底层 SQL 仍按 `c.last_msg_at DESC` 排列，与原索引查询一致。
- 摘要字段、`lastMsgAt=createTime`、未读值、`muted=null -> 0`、系统/客服固定身份均保持。无消息时返回空摘要，不回落到会话行的旧私密名称。
- 对方资料继续使用 nickname 非 null 则 nickname，否则 name；不存在资料时保留对方 member ID 并返回空昵称/头像；资料存在而字段为 null 时保持 null。没有新增用户状态/删除标志过滤来改变原展示口径，也不读取手机号、密钥等其他资料字段。
- 群实时资格仍由原 `hasActiveGroupAccess` 校验，允许后才组装输出；路线卡仍调用原 `projectRouteMessage` 及当前读者主题权限查询。批量 DTO 不用于授权、写入或已读游标推进。

## 失败机制与最小修复

原 `unreadTotal` 为求总数调用整个列表，先查询最新消息、群资格、对方资料及路线投影并排序，然后才排除静音和隐藏群。原普通单聊列表对 N 个成员索引逐个查询会话、最新可见消息、参与者和资料。

新增 `selectMyConversationSummaries`：成员索引 JOIN 会话，LEFT JOIN 每个会话最新可见消息、单聊对方成员与其展示字段。两个相关子查询各只选一个主键，避免把消息历史或多个参与者扇出到应用层；查询返回每个成员索引一行。没有 IN 长列表、逐行跨服务调用替代 JOIN 或持久缓存。群资料仍走原 helper。

新增 `sumInboxUnread`：成员索引 JOIN 有效会话直接 SUM，保留当前成员及未删条件，排除 TYPE_GROUP=4、仅排除 muted=1。保留原 Java 对 null/非标准静音值、未知非群 type 的处理：`muted IS NULL OR muted != 1`，`type IS NULL OR type != 4`。聚合返回 Long，服务以 `intValue` 保持原 int 返回及累加转换口径，不在本批改动溢出政策。旧 `sumUnread` 仍供原调用者使用，SQL 完全未改，它未排除群，不能直接拿来替换红点。

生产修改仅为两个 SELECT、一个映射和对应读路径。`check_static.py` 对比基线，确认所有已有 mapper 节点以及群资格、路线投影、原 counterparty 和两个 markRead 方法体未变。

## 查询次数与性能边界

| 路径 | 旧代码查询调用 | 新代码预期 statement / 调用 |
| --- | --- | --- |
| N 个非路线卡普通单聊 | `1+4N` | 1 条批量 SELECT |
| N 个非路线卡系统/客服会话 | `1+2N` | 1 条批量 SELECT |
| 红点 | 完整列表的全部查询、投影及排序 | 1 条 SUM，无列表读取 |
| 含群、路线卡的列表 | `1+N+V+A+C+R` | `1+A+C_group+R`，群资格/群资料及路线投影仍逐项调用 |

这里是实现与测试约束，**不是已经执行观测到的 SQL 数或性能结果**。MySQL 测试准备了 StatementHandler.prepare 计数，等待受控执行。批量查询内仍有按会话的相关查找和可见序排序；减少往返不等于数据库工作量成为 O(1)，需要 EXPLAIN/扫描行数/真实负载验证。原列表未分页，此次保持接口合同，仍会返回 O(N) 摘要。

单聊存在多个非当前参与者时，旧查询未定义顺序；新子查询也未添加业务选择规则。有效双人会话只有一个对方；异常多参与者行的具体首选不具有既有确定性合同，需另外核验数据质量，不能据此宣称清洗或修复了该类数据。无消息、相同 `last_msg_at` 等完全并列的底层顺序同样没有新增承诺。

## 测试方案及实际状态

先补服务失败场景，再实现批量读：64 个普通单聊必须只走一个 mapper 方法，禁止逐行访问会话、消息、资料服务；同时逐行检查所有结果字段和顺序。另有可见序/ID 排序、空摘要与稳定顺序、资料缺失/null 字段、当前私密路线卡、群退出/有效成员与原资格守卫、红点独立聚合、空结果等共 7 例。

4 个真 MySQL 用例使用既有三个 IM mapper 的旧查询作为独立 SQL oracle，比较新服务完整 Map 列表，覆盖：

1. 读者/发送者差异、待审与已过审图片、屏蔽消息、撤回消息、可见时刻晚于创建/ID、同刻 ID 排序、空会话、nickname/name、资料行缺失、null 头像；查询计数断言 1。
2. 未读 SUM 排除静音、两类群夹具、软删成员、关闭会话、孤儿会话、其他读者数据；未读数据前后不变，空账号为 0。
3. 64 个会话时与旧逐行查询结果完全一致，列表和红点各 1 条 statement；空账号不读出他人会话。
4. 屏蔽/恢复、待审图片放行后立即按最新可见序读回，不使用会话旧摘要；计数仍为 1。

新真库测试不使用 H2，不在默认单测模式下伪装执行：类名以 IT 结尾，必须点名；点名后缺少显式开关/参数会失败，不通过 Assume 或 EnabledIf 跳过。只允许连接执行端 loopback 和显式端口，使用独占随机 `cy_im_batch_<uuid>` 库，从公共 migration 建 IM 表，结束仅删除自身成功创建的库并核验清理。没有复制生产数据/凭据。上述夹具 DDL 目前只写在测试中，**没有在云 VM 执行**。

实际执行仅为 Python 标准库静态核对和 Git：

```text
PASS standalone WP11: all 3 cache paths remain at public baseline; frozen candidate untouched
PASS XML parses; all prior mapper statements unchanged; additions are 2 SELECTs and 1 result map
PASS original group guard, route projection, counterparty and markRead bodies unchanged
PASS latest visibility/order matches legacy message selector
PASS prior suite counts unchanged: 53 / 9 / 14
PASS regenerated WP11 source patch and 8 source/test file SHA-256 match manifest
NOT RUN Java compilation, JUnit, MySQL, EXPLAIN, load or network tests
```

另已通过 `git diff --cached --check`、八文件范围检查、补丁反向可应用检查，以及分别从公共基线/冻结缓存 tree 在隔离临时 index 应用补丁、生成 tree 并核对身份。静态证据不能代替 MyBatis 映射解析、Java 类型检查或真实数据库执行。

## 受控验收交接

由集成人在已批准 VPS / GitHub-hosted Java 21 路径，注入其既有受控测试连接参数：`CHENGYIN_MYBATIS_IT_PORT/USER/PASSWORD`；新套件要求 `CHENGYIN_IM_BATCH_MYSQL_IT=1`，既有发送集成套件还要求 `CHENGYIN_IM_SEND_MYSQL_IT=1`。不申请或输出任何凭据。

```sh
mvn -B -pl chengyinhub-admin -am test \
  -Dtest=ImConversationBatchReadTest,ImServiceImplTest,ImRouteProjectionTest,ImConversationBatchReadMySqlIT,ImSendIdempotencyMybatisIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -DskipTests package
```

reactor 的 no-specified-tests 仅用于没有这些测试类的依赖模块。必须检查 admin 新鲜 XML：上述五套分别 7、53、9、4、14 例，共 87 例，failures/errors/skipped 全 0，并核对执行 SHA、MySQL 版本与 statement 断言；不能只看 Maven 退出码。首批缓存的 15 例仍需独立按首批报告验收。还需由共享 CI owner 决定显式纳入新 IT，此处没有私改 CI 白名单、跳过既有失败或扩 baseline。

## 迁移、回退与未完成项

- 无生产 schema/迁移变更，不需要执行线上 DDL。只使用已有 IM 可见时间字段和会员展示字段；仅测试临时库新增最小会员表。没有任何经济、回执、审计、权益或外部动作写入。
- 回退须将 WP11 的 service 与 mapper Java/XML 一起撤回，避免新方法和映射不匹配；只撤本批八文件补丁，不撤冻结路线缓存，不改其他任务，也不清 Redis/业务记录。无数据修复/资金回滚。
- 用户已明确给予根 AGENTS 提交前编译/测试规则的窄例外：仅扫描后隔离 CI 候选提交/推送。此例外不允许将未运行/失败写成通过，不允许合并部署；后续提交 SHA/远端状态由交付回执补充。
- 未验证：Java/MyBatis 实际映射、87 例实际执行、真实 MySQL/EXPLAIN/负载及完整集成回归；群权限和路线投影保留的查询不是本批已消除项。不得把候选代码或静态通过写成上线/性能验收完成。

## CI 候选扫描与网络状态补充

用户已明确授权仅隔离 CI 候选提交/推送的例外。实际执行：仓库 secret-file-gate 通过，自测 3/3 通过；候选文件范围及受限 token/key/凭据字面量扫描通过，输出不含凭据值。它们不等同于完整 gitleaks 扫描。

gitleaks 本机未安装，按仓库 CI 固定版本 v8.24.3 下载时 curl 无法连接 proxy:8080，因此 gitleaks 未运行，不能记为通过。gh auth status 也报告认证不可用。保持既有代理和工作区，不重建环境、不导入凭据；分支推送结果另记回执。此处没有出现 No usable execution environment。Java/MySQL 仍全部未运行，运行和完整秘密扫描门槛不因候选提交例外而降低。
