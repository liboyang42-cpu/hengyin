# H：G c829 存储修复独立复审

结论：相对 61834b 的 13 文件差异，H-G618-01 与 H-G618-02 的源码修复已对应原失败根因，本轮未发现新增可证 P1/P2。**c829 的 Java 编译/45 条 MySQL 运行尚未由 H 取得结果，运行裁决仍 UNKNOWN**；不据静态检查关闭既有资金 P1，不授权生产 DDL 或恢复。

## 候选与独占范围

- 公共参考基线：`2ec68899099a1e250df0949a8383bf85b5d3ffe0`。
- 被审候选：`c829f158ea549ff4e844f92d35725a60ef58e864`，parent `61834b1ba96fcfb025d8442955b69f1d6b35d5f3`，tree `cc55d2b8f032d857b21f4fbc4a4353ad373bc9dc`；实际 13 个文件变化，与给定身份一致。未使用旧 2ec 行号判断新实现。
- H 开工目录 `/workspace/hengyin-h-review`，分支 `codex/architecture-day-h-review`，实际 HEAD `c6d8115335d8c569b4e5cf5106a96822754f4cc0`，clean。另在临时目录保存只读候选快照。
- H 仅提交本目录报告/静态证据索引；没有编辑 G/root 的实现、测试、DDL、CI，也没有新增反例补丁。完整 13 文件清单和额外真实建单调用者的 blob 在 SOURCE.json。
- 原 H 报告/4 反例不改写。root 已核验的历史实证：`97b823e5c8bb86675825476a07c9cdf671063f2a` / run40044883897，原30 PASS + 新4 FAILURE，0 error/skip，CLEAN。四个失败分别是 NULL-parent 借 payer、零价从单自称 payer、预检遗漏索引冲突、半迁移 full 回退缺列。此项是 root 取得的原 XML 证据；本轮 H 未重复下载，不冒称亲自运行或独立二次核验。

## 1. 原付款主体身份

精确位置：`CoopSettlementCashEvidenceMapper.xml:52–80`，`migration_20261023_g_cash_evidence_expand.sql:121–130`；Mapper default 方法 `recordContribution:32–44`。

- 独立原单的 parent=NULL 时，INSERT SELECT 现在要求 payer.id=r.id；原 registrationNo/payerOrderNo 的 binary 等值检查继续存在。新 CHECK 对 NULL parent 同时要求 self ID/self 原号。因此原 101 已付10元借 102 待付90元的反例不能再由“NULL=NULL、同 member/owner、同 close 中存在”获得写入资格。
- 非空 parent 要求确有非空白原号、payer 原 payable 非 NULL 且>0；同 binary parent 的整个原单集合不得有跨 member/owner、未知/负 payable，payer 以外均须 payable=0。不是按最小 ID/最大金额选人；301 小 ID 零价从单不能冒充真实 302 付款主体。矛盾数据/全零父单不能证明原主体时明确拒绝，不补默认。
- 已追真实建单代码：`CmsRegistrationServiceImpl.createMultiTicketRegistrationsInternal:685–775` 在同轮生成非空 parent、同 member/owner，从单 payable=0，并把整单 payable/out_trade_no 主体留在创建结果首单；普通单按原 parent 参数保存，通常为 NULL。`RegistrationOrderServiceImpl.loadOrder:299–315` 无 parent 时仅原单。新 predicate 对可证明正额组与真实建单规则相符，没有强造支付 VERIFIED 事实。
- 该检查只证明此批结构所需的原支付身份；payment/registration 当前状态与 sales basis 仍按各自原观察核对，待付单不被伪装成已付。它不证明全组关账集合完整、实际供应商收款、整组退款或并发共同 fence 已接线。
- 同自然身份的 prior current-lock read 仍先于新插入；所有字段/规范 JSON payload 比较继续存在，同 hash 改金额或 JSON 仍冲突；没有 UPDATE/upsert 覆盖旧贡献。source 后来退款不要求重写原回执。
- DB CHECK 只对 NULL parent 增补独立 self 关系；非空父单的跨行真实性靠上述 Mapper INSERT SELECT。未将同 close FK 或单行 CHECK 说成任意 direct SQL 的跨表事实认证。原 claim 非空、旧 unique、REFUND_APPROVED source/status/amount 校验未减。

应保留运行裁决：原 H 两条 payer 反例；正额父单原 payer+从单+重复；全零、双正额、未知金额、NULL direct insert 负例。源码均存在，不能用其存在代替实际通过。

## 2. 第一条 DDL 前的冲突阻断

精确位置：`_precheck_g_cash_evidence_20261023.sql:11–29` 和 migration 首断言 `:17–35`。

独立从本候选 DDL 枚举并与两个预检核对：新表3、旧表新增列7、旧表新增 index8（head5/adjustment3）、schema 内精确 FK11/CHECK5 共16约束名，均已覆盖。新表名也阻断同名 view；index 按其真实表作用域，约束按 schema。没有覆盖/删除已有冲突对象。原 uk_settle_order 单列与 uk_coop_adjustment_event 完整列序继续核对。

原 uk_g_cash_adjustment_event(id) 碰名现在在任何 CREATE/ALTER 前被两处预检挡住。postassert 的 FK/CHECK 也改为精确集合计数，不借无关同前缀对象凑数。此处是已知冲突的 fail-closed 修复，不承诺消除预检之后的外部 DDL 竞态或网络中断。

## 3. 只用于受控测试的空阶段恢复

精确位置：test-scope `GCashEvidenceEmptyRecovery.java:23–106,108–183`，shell 入口及 full-only rollback 首断言 `:4–12`。

- URL 必须 loopback 127.0.0.1/localhost 且显式端口；catalog 必须 G disposable IT UUID 命名，连接 autoCommit。main 仅从显式 IT env 组装，不读应用/生产配置；shell 只调用已编译 test 类，不新增运行时服务/生产 jar 入口。这是受控测试工具，不是生产恢复授权，也不是任意库的所有权证明。
- 使用冻结公共 base DDL 和本候选 forward SQL，在同一服务器创建本次唯一 reference schema；记录 CREATE 成功后才负责其清理。顺序生成旧形状0及五种 DDL committed prefix 的标准 metadata。
- 目标五张 affected 表比对 table属性、完整列/默认/生成式/字符集/注释、index定义/可见性/表达式、CHECK完整表达式/enforced、FK列序/引用/动作、入向FK及trigger；仅将自身 schema 名归一。未知列、削弱 CHECK、外来引用等不会进入目标恢复 DDL。
- **所有目标 DDL 前**查本阶段已存在的新证据表为空。head 阶段拒绝 cash_close 链接、ACCOUNTING 或 null merchant 占用；adjustment 阶段拒绝任一 cash_close/event 链接。不查询尚未安装的列。
- 逆序限定 adjustment → head → event → contribution → close；stage1/2/3 只撤确已存在的新表，stage4 先撤 head，stage5 先撤 adjustment。固定对象名，无 wildcard DROP、DELETE、TRUNCATE 或 target database DROP。每个成功逆步骤回到下一已审 prefix；不假称 DDL 事务原子回滚。
- 结束比对回旧 shape；finally 仅 DROP 本次成功创建的 reference 并读回不存在。连接/DDL 本身失效仍可能中断，必须据执行错误/清理 receipt 处理，不能仅凭 finally 源码称实际 CLEAN。
- 现有 full SQL 新增阶段断言，partial 阶段先安全拒绝；它仍是 full-stage 空回退。半迁移恢复合同通过新 helper 提供，不把 full SQL 称通用阶段恢复。

必要前提仍是停止相关写入、独占 IT schema、冻结 repository。helper 没有在扫描/DDL间协调并发写入，不能用于仍在工作的资金库；这是其明确受控用途，不报告为新生产并发事故。未空或未知 shape 必须保留现场，无清数据换回退。

## 实际检查与候选裁决

| 项目 | 本轮证据 | 裁决边界 |
|---|---|---|
| 13 delta + 3 真实建单/base 文件 | Git blob 与 c829 tree 一致；交付 manifest 的12项 SHA256 一致 | 静态身份确认 |
| 原30及 fixture | 原30方法正文、测试头部、旧 helper 尾部逐字一致 | 未弱化旧断言；不是重新运行 |
| H4反例 | 前三正文一致；第四仅替换为阶段恢复调用，旧 schema/index/金额断言一致 | 历史4 FAIL保留，不自动转绿 |
| 新候选测试 | 45 authored 方法 | Java/MySQL NOT_RUN by H；root新结果未取得 |
| 预检对象 | 3/7/8/16 从真实 DDL 枚举并在两处逐名核对 | 静态 PASS |
| SQL/XML/shell | SQL gate PASS；分句7/1/7与恢复 splitter 一致；XML解析 PASS；bash -n PASS | 没有执行 Java、SQL 或恢复入口 |
| 既有 P1 | 资金共同 fence、late fact、挂账后退款全头调整/跨 head 债务、未决多人费用政策 | 未因结构修复关闭 |

逐项静态输出与文件身份见 SOURCE.json / STATIC.json。H 未派 CI、未调用数据库/真实支付或供应商、未触及生产。root 应将**实际组装的新完整 SHA、45 case 原 XML、运行 receipt/source/hash/cleanup**一起核验，不能沿用 618 的30绿或改写97b的4红。

本目录仅文档，回退无业务迁移影响；被审方案仍需保留原 ledger/receipt/evidence/audit，任何非空占用不得执行空恢复。没有提交新的 negative patch，因为本轮没有发现可证的新触发机制；并未用空测试补丁冒充实现或验收。
