# C / WP09 M1 T14 canonical 对照候选

结论：已交测试观察层、原 14 case 输入清单、旧/新接线补丁和严格比较器；**本切片尚未编译，Java/MySQL/两侧 canonical 14 均未执行，不能宣称行为等价或 WP09 验收完成**。实际完成的是 Python 比较器 16/16 与只读静态检查。root 已告知的先前 555 unit、hint10+B1 4 真库/CLEAN 属于原 source 的证据，未借给本观察候选。

## 基线、范围与文件

- 旧 owner：`9a2dcf21aef4d7aec7a14065a2601bd86aaad805`（B1 修后、M1 前）。
- 新 owner/本分支 parent：`1c63e55a167f235930b5811bf28203a29231c8ef`（与 53f 同 M1 Java）。
- 独立工作树 `/workspace/hengyin-c-m1-equivalence`，分支 `codex/architecture-day-c-m1-equivalence`；公共 hengyin 历史，无私有仓库输入。
- 新增唯一 Java helper：`chengyinhub-admin/src/test/java/com/chengyinhub/business/it/Wp09M1EquivalenceTrace.java`。
- 实际改动 C 所有的 `PlayLeadAdmissionFailureMySqlIT.java`：仅配置观察插件、Capture 字段、BeforeEach TestInfo/启动、AfterEach 落盘与原 Controller ProxyFactory 最外层观察 advice。
- **实际 `PlayHintUnlockMySqlIT.java` 未改**。root 单写；本目录提供 `PlayHintUnlockMySqlIT.{old,new}.review.patch`。lead4 两侧同样有精确 review.patch，便于 root 在旧侧接线。四份 patch 的 original/observed/原 case 尾段/cleanup SHA 在 `fixture-patch-proof.json`。
- `inputs-manifest.json`：14 原 case/完整带 Java 数字类型的 actor 和参数、并发比较模式、21 个两侧字节相同 source/DDL/mapper 的 SHA、归一白名单与前提。
- `compare_receipts.py`：只读 artifact 比较；`test_compare_receipts.py` 是比较器自身的合成模型负控，不生成或冒充真库回执；`check_static.py` 是本切片只读静态检查。

无生产算法、schema、CI/registry/harness、D 共享测试、root Photo/Theme/APPLIED、ledger/UI 基线改动，无新依赖。回退仅撤销观察接线/helper；没有数据迁移、清库或资金回退。

## 观察合同

观察 advice 位于原 Controller 事务 advice 外侧。保留原 actor、scope/command 完整参数、调用后原参数、原 AjaxResult，或原根异常的 class/message/code/data/detail/cause/SQLState/vendorCode；不调用新的异常映射，不替换原返回或异常。现有 B1 case 内的 GlobalExceptionHandler/503/NOT_RUN 断言原样保留。本切片不声称经过 HTTP/JWT。

同一 SqlSessionFactory 注入 MyBatis Interceptor：记录 Executor query/update 尝试及实际 StatementHandler prepare/query/update，保留 statement ID、SQL 原文、绑定值/Java/JDBC 类型、cache flags、实际事务/readOnly/isolation、事务资源身份与 commit/rollback。记录 mapper 尝试和真正 JDBC 执行的区别，不能把 L1 cache 命中伪装成查库。原 node/TX/connectionId/latch 断言不移动、不关闭。

Capture 使用 InheritableThreadLocal；两竞态 case 在 BeforeEach 后创建的原 worker 会继承同一 Capture，原 Controller 入口建立逻辑调用上下文。顺序执行 case、支持 case 内原 worker；未传播 worker、迟到 worker、并发运行不同 case、未完成调用或重复输出文件均明确失败。事务完成只记同步回调事实，不在回调取连接或做 SQL。

在原 seed 完成后和原 case 结束后，读取 fixture schema 的**全部 BASE TABLES、全部列/JDBC 元数据与全部行**。非竞态 case 另记每个 Controller 调用前后完整行。仅原 schema 白名单允许快照；快照借独立连接、无业务事务、无 FOR UPDATE，不写数据库。原重命名故障注入、DELETE/INSERT seed、REQUIRES_NEW 竞争扣款、恢复与所有 cleanup 保持不变。

每 case 输出 `target/surefire-reports/wp09-m1-equivalence/<Suite>--<case>.json`。包含 exact `SOURCE_SHA`、运行序号、环境、initial/final、调用帧及原始 rawTrace；CREATE_NEW 防覆盖。既有 real_storage harness 的 reports copytree 会保留子目录，不需要修改 harness。

## 14 个原用例及比较方式

完整原输入以 `inputs-manifest.json` 为准。所有正文/wrapper/assert/helper 尾段、原清理相对各自 owner 逐字不动；两侧原来已有的 M1 owner 装配适配仍各自保留。

| suite | case | 比较 |
|---|---|---|
| hint | legalSelfPlayUsesOneRealDebitAndOriginalEventIdentity | 完整顺序 |
| hint | legalActivityUsesOneRealDebitAndOriginalEventIdentity | 完整顺序 |
| hint | unauthorizedOrWrongScopeNeverReturnsHintsOrChangesEconomicState | 完整顺序 |
| hint | duplicateAfterLostReplyAndReconstructionKeepsOneDebitAcrossAuthorizedScopes | 完整顺序 |
| hint | twoConnectionsSerializeOriginalRegistrationAndDebitOnce | 每逻辑调用完整顺序/事务偏序 |
| hint | balanceLostAfterPrecheckRollsBackHintLedgerButRetainsOtherCommittedSpend | 每逻辑调用完整顺序/事务偏序 |
| hint | ledgerWriteThenThrowRollsBackRealRowAndLeavesDebitUntouched | 完整顺序 |
| hint | debitWriteThenThrowRollsBackBothRealBalanceAndLedgerBeforeOriginalRetry | 完整顺序 |
| hint | revokedRegistrationBlocksPreviouslyPurchasedHintWithoutChangingItsOriginalLedger | 完整顺序 |
| hint | disabledRuleStillRequiresEntitlementAndWritesNoPoints | 完整顺序 |
| lead | preferenceLeadStorageFaultCannotCreateBranchSessionBeforeEitherHttpAdmission | 完整顺序 |
| lead | preferenceLeadStorageFaultCannotCreateJourneySessionBeforeEitherHttpAdmission | 完整顺序 |
| lead | realLeadStorageFaultReturnsNoHintAndHasZeroEconomicOrRegistrationEffect | 完整顺序 |
| lead | realLeadStorageFaultRollsBackTheOriginalBasicActionClaimWithoutTerminalReceipt | 完整顺序 |

竞态保留 raw 全局时间线，但只比较每个逻辑调用的完整有序 trace，包括嵌套 REQUIRES_NEW 的相对位置；不要求两个 worker 的无关全局交错相同。不把 SQL 转多重集：每笔 member debit 前须已有**同事务、同 member、同金额的成功 ledger INSERT**；保留每个实际事务的结束事实、原两个 distinct connectionId、原锁读和每次 ledger 尝试。响应按带原输入与完整有序 trace 的调用束比较；最终完整行（仅行展示顺序排序）和余额/账本守恒严格比较。两种 race 的原闩锁、阻塞 Timeout、连接号和回滚断言仍必须通过。

额外要求：真实 claim INSERT 后该事务 rollback 且最终无 receipt；恢复后的 preference 必须有实际 route INSERT+commit；NOT_RUN 拒绝前后完整原行相同。原 TopicRouteRuntimeServiceImpl.canPlay 仍 callRealMethod、真实 mapper/事务/会话；保留原目录数据 doubles 和 PreferencePlayService 的原隔离替身，不新增“图服务直接成功”替身。

## 精确归一白名单与限制

1. 仅去除观察标签 seq/span/call/thread/jvmMillis/connectionHandle；事务 label 按首次出现重编号，仍区分每个事务及其结束。原测试连接号只归一数字标签，保留 distinct 数量（并发原断言要求 2）。
2. seed 日期仅 `cms_activity.start_date/end_date`、`cms_registration.pay_expire_time/expires_at`。逐原 row id 校验：每份 raw 后续快照必须与该侧原 initial 值**完全相同**，然后用带原行 id 的字段标记比较；没有忽略其他日期。
3. 操作日期仅 points_detail.create_time、route_session.create_time/update_time、game_command_receipt.create_time/update_time 及 manifest 中对应 3 个 INSERT 的 Date 参数。值必须在 case 开始/结束窗口，最多按 DATETIME 秒精度边界取整。事务外只读 `SELECT CAST(? AS DATETIME(3))` 探针固定绑定 Timestamp `2000-01-02T03:04:05.123Z`，精确记录 DateTypeHandler 同类 JDBC 编解码 roundtrip 差值；持久日期据此校验。固定探针原事实（含 JVM timezone）两侧必须完全相同，无任意时区/时钟容差。该探针不写 fixture 行。
4. 不归一金额、原 member/node/activity/session/action/receipt/ledger id、资格、状态、版本、角色、SQL 原文、非登记参数、JSON 字符串或错误文字。所有原始数据和日期保留在 artifact。

**受控运行的必要前提**：两侧使用相同 MySQL/JDK/时区/sql_mode/charset/collation、相同 per-suite case 执行顺序、相同隔离 schema 名/suffix。主键不映射，完整列元数据严格比。旧/新不同 GitHub run 的现有自动 suffix 可能不同，缺表驱动 message 也随 schema 名改变；比较器不会删 schema 或泛化错误。root 需在受控路径保证相同隔离 schema 名（例如相同 run 下的隔离执行），或先报告无法满足，**不得把失败改成跳过/放大归一**。本切片不修改 runner/CI 去实现这一点。

无损观察边界：仅覆盖这两个既有 fixture 的 MyBatis SIMPLE query/update；不跟踪任意 JdbcTemplate fixture SQL，不覆盖 batch/并行不同 case。fixture 自有 rename/revoke/seed/竞争输入由被冻结的原代码、每调用快照和 initial/final 全表行承接。Executor 为绑定值观察会额外求一次 getBoundSql；已核对相同 8 XML，无 bind、setter/随机/时钟表达式，现有值为原请求/行字段；这是本 fixture 限定，不能推广为全站观测框架。快照/观察新增只读成本可能影响原 timeout，必须在两侧真实运行验证；不允许延长原 latch/timeout 以通过。

## root 接线与验收步骤（此处未执行）

1. 从 exact old owner 建独立受控候选；复制本候选唯一共用 helper（SHA 见 static-proof），应用本目录两份 `*.old.review.patch`。不要把新侧 M1 fixtures 或生产类型搬旧侧。
2. 新侧从 exact new owner 取本切片；lead4 已接线，root 在自己文件上审阅并应用 `PlayHintUnlockMySqlIT.new.review.patch`。若只复制 helper，另应用 lead 的 new patch。**不要重复应用同一接线**。
3. 旧/新均提交为完整 source SHA，SOURCE_SHA 各绑定真正含观察层的候选，不填 owner SHA 冒充。由 root 用现有批准 GitHub 路径分别执行原 hint10、lead4；所有原真实 TX/connectionId/latch/断言、严格14条计数、两个 CLEAN 都必须成立。保持原 fixture/清理规则与原两个普通 hint wrapper。
4. 下载两侧真实 XML、storage receipt 和 reports 子目录 JSON。比较器同时验证 exact14 case、源码 SHA、precheck SHA、Maven0、XML0fail/error/skip、receipt14passed/两个CLEAN、无重复/缺失 artifact。写出每 case canonical SHA 和原 artifacts 的 SHA；原 rawtrace 继续保留。

```sh
python3 docs/delivery/architecture-day/C/evidence/wp09-m1-equivalence/compare_receipts.py \
  --old-dir OLD_DOWNLOADED_ARTIFACTS --old-source OLD_OBSERVED_CANDIDATE_40HEX \
  --new-dir NEW_DOWNLOADED_ARTIFACTS --new-source NEW_OBSERVED_CANDIDATE_40HEX \
  --out canonical-comparison.json
```

输出 PASS 只在上述两侧证据完整且 canonical 相等时成立；缺失 source/CLEAN/trace/XML 均 FAIL。比较器本身不运行 Java、SQL、网络或 CI；没有新 runner。

## 本次实际执行

- `PYTHONDONTWRITEBYTECODE=1 python3 .../test_compare_receipts.py -v`：**16/16，0fail/error/skip**；原输出 `comparator-tests.txt`。含金额/原输入/错误文字/日期/锁序/缺 artifact/非 CLEAN/并发顺序负控，仅是 Python 合成比较器检查。
- `PYTHONDONTWRITEBYTECODE=1 python3 .../check_static.py`：PASS，`static-proof.json`。4 精确 patch 应用检查、14 原用例保留、21 common source 相等、8 XML 检查、仅 Java 词法括号检查、根所有 hint 文件 untouched、改动范围白名单；**不等于 Java 编译**。
- `git diff --check`：PASS。
- 秘密扫描结果见 `secret-scan.json`（原 ignore/config 不改）。

明确未跑：Java 编译、JUnit、MySQL、Spring 启动、HTTP/JWT、任何 CI、旧/新14 canonical 比较。当前没有真实 canonical receipt；T14 待 root 两侧同源实跑和完整证据比较。
