# A：提现原扣款/原提交身份与渠道终态绑定

A 源码候选完成；Java/JUnit/MySQL/CI 均未执行，整体闭包尚未验收。独立基线 B `ae7b93f62bb62385bed8dc4110a85e4c0f315a8b`（parent master `e2e05b9614a3c5d62b774718797f9a6792a3eeca`），分支 `codex/architecture-day-a-withdraw-original-intent`。仅提现闭包增量；未带入共享玩法/配置拆分，未改 B 网关、接口、共享 CI/registry、依赖或金额/费用规则。不要将此候选当 PR598 发布制品。

基线 B caller 缺少 A 原 DEBIT/SUBMIT 护栏，因此从公开 root `986b88d9640bf45900bd807675bed1d6a06c5938` 复用已审资金事实链、冻结/双审、未提交崩溃恢复与原事务结构，再补原请求身份。未另建存储平台或余额写框架；银行 debitBank/通用余额写侧不变。

真实入口：`ApiWalletController` 和 `ApiClubSettlementController` → `withdraw`；`WithdrawTransferQueryTask` → `syncPendingWithdrawals/syncOne`；`CashWithdrawAdminController` → `requery/manualRefund`。保留所有入口/权限、原 CW/CD、客服调查、冻结和两名不同处理人；没有一刀关闭正常提现。

`domain.dto.CashWithdrawTransferIntent` 严格采用 root public record 字段序、record accessors、`canonicalRequestHash()/requireValid()`。exact 分金额；备注沿 null/空→分润提现、否则 Java substring(0,32) 原规则，本 caller 仍传“城瘾分润提现”。版本化 SHA256 绑定实际 appid/mchid/批次/明细/整数分/openid/有效备注/固定批次名和单笔数，排除 DB ID，避免自增循环。覆盖 toString 隐去原参数，不收 HTTP DTO、不含状态或凭据。原 withdraw 金额输入的既有 setScale(2,DOWN) 策略未改，DTO 转分不再做舍入。

原扣款及 DEBIT/关联 ID 同一短事务提交；持原单锁验证扣款和当前资格后提交完整 SUBMIT_INTENT，再事务外 SDK。唯一意图不随 6h 超时、人工状态或响应 TTL 释放。DEBIT 已提交、尚无 SUBMIT 的健康崩溃窗口仍按原双审回补，不查渠道/污染 UNKNOWN。提交未知、一次 NOT_FOUND、PROCESSING/CLOSED 或历史缺证据不授权回补，不换号/重发。

查单只传持久原 DTO。终态同时核对原 mchid/appid/CW/CD/整数分/openid、FINISHED+同名明细终态及有效渠道 ID；在原单锁内再次核对捕获的 intentId、完整 hash 和已有原批次。渠道批次/明细彼此一致不是原单证明。FINAL_FAIL/NOT_ACCEPTED 必须引用原意图与同 hash 才可回补；本地拒绝仍要求本次首意图且无先前 UNKNOWN/ACCEPTED。迟到 SUCCESS 阻断复核回补；已回补后发现原单真实 SUCCESS 只保留冲突证据/告警日志，不再移动钱。余额/回补事实/回执状态继续原子提交。

必要迁移候选：`migration_20261023_cash_withdraw_fund_fact.sql` 与 root986 原 bytes 相同；新 `migration_20261024_cash_withdraw_transfer_intent.sql` 只加原请求 7 字段和渠道 batchId、约束，不回填/清空任何旧资金/回执。历史 NULL 证据保留调查。生产预检须核对原表/触发器、列与约束是否已存在及版本；存在部分新结构时停止，不能重跑/吞重复列错误。无表时先原迁移再新迁移；已有原表只加新迁移。需 root/数据库 owner 审批后执行，A 未执行 DDL。新意图启用后不得 drop 字段/回退无原身份核验的旧 caller；回退必须保留原事实读取与核验能力，不能靠删证据恢复。

测试源码：`CashWithdrawFundEvidenceMySqlIT` 原 23 场景保留并适配 DTO/完整渠道事实，新增身份错配、hash/历史缺失、受理写回交错、6h UNKNOWN、升级保留旧账和原事务窗口，共 49 拟执行实例。真实生产 XML/financial writer/事务代理，渠道为确定性 stub，不冒充微信实转；仅批准 GitHub-hosted scratch DB，复用 B 现有 MybatisItDatabase/批准标记并自有随机 schema 清理。DTO 5 项纯 unit。既有提现 unit/H2 与五个共享提现夹具只迁移证据/端口，不删除用例或减少断言；原银行等职责断言保留。沿用 root986 已有两处回滚场景重命名；旧正控的 CLOSED 假终态夹具改为合法 FINISHED+FAIL，独立 CLOSED UNKNOWN 负控仍保留。H2 不作为 MySQL 验收。

实际仅 `git diff --check`、Python 源码/方法保留、XML INSERT 22 列配对、独立 Python hash 参考向量等静态检查；不是编译或业务通过。完整每文件 SHA256 见 static-result.json。

必要余项：B 按批准 record 合同提供两 DTO 重载及 SDK 前实际 merchant-client/hash 校验、查询原 openid 等事实（本候选基线接口尚无重载，未抢改，须组合后才能编译）；root/H 组合审查；root 统一 Java+真实 MySQL 执行/清理回执与迁移登记；生产 schema 预检/授权及供应商链路仍未验证。所有旧绿项不能替代本 head 验收。中间合同 554e 的旧包 class 已由最终 record 替换，集成取 ae7→最终 head 范围或按 A 文件增量，勿只拿中间提交/整树覆盖 root。

test-only 后续增量（parent f676）：已只读核对 B `dc87a41ebc4a18d696d094554e5f91a08f32a03f` 的签名和全部消费字段一致；另补 2 个实际 A caller + 实际 immutable record + 实际 B 网关、SDK stub 的合法成功/失败组合实例，原 49 不删不改，总 51。B client 绑定在测试中复用其反射 fixture，不冒充真实凭据初始化/供应商链路。此分支未应用 B 文件，必须由 root 组合。全部生产源与 f676 相同；51+5 仍仅源码，Java/DB/CI 未执行。
