# A：单条拒绝触发器的精确迁移门禁修复

结论：保留 canonical SQL、两个 append-only 触发器及所有 Java 加载器原字节，修复门禁把合法单条 SIGNAL 触发器误报为“缺 DELIMITER”的判据。没有给文件/目录加白名单，没有删除触发器、改变 SQL 业务语义或引入客户端 DELIMITER。A 库存阻塞消除；**整体 safety 仍被 D 两条无 WHERE UPDATE 阻塞，不能称整体通过**。

## 基线与证据选择

- 仓库：`https://github.com/liboyang42-cpu/hengyin`。
- 开工 HEAD/parent：F `8aaa141c5990b089749d4364fa2472748a69d9ab`；分支 `codex/architecture-day-a-trigger-delimiter`，目录 `/workspace/hengyin-a-trigger-delimiter`。
- 实际读取 F `docs/delivery/architecture-day/F/migration-layout/REPORT.md`、`safety-full.txt`、`sql-contract.txt`。原输出确实将 A 第2/3条 CREATE TRIGGER 标为缺自定义 DELIMITER，另将 D 第2/3条 UPDATE 标为无 WHERE；F只移动原字节，未修 SQL/parser。
- A 原 SQL 是 CREATE TABLE 加两个 `FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='...'`。三个语句各自以默认分号结束；触发器体是单语句，没有 BEGIN/END 或内部语句分隔符。MySQL 该语法无需客户端 DELIMITER。两个真库调用者用 ScriptUtils 默认分号加载；若仅添加 DELIMITER，会破坏该加载协议。
- 最小正确方案因此是精确调整门禁的语法判据，而不是给 SQL 引入不必要的客户端协议。修改前新增 canonical 回归实际运行1项失败，输出为两个 CREATE TRIGGER 的 DELIMITER 误报；修改后运行见下。

## 修改范围与严格边界

1. `ci/migration_gates.py`：默认分号只额外接受完整匹配的 `CREATE TRIGGER <identifier> BEFORE UPDATE|DELETE ON <identifier> FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='<literal>'`。仅有常量错误状态及常量消息，不含动态表达式、其他 SQL 或复合体；必须有实际分号终止。按语法识别，与文件名、表名和路径无关。其余 routine 规则保持原合同。
2. `ci/test_migration_gates.py`：新增6项正反控。包含 UPDATE/DELETE 正例及不同路径、实际 canonical 文件原 SHA/两个 SIGNAL/三条完整语句、BEGIN...END 负控、其他体/AFTER/INSERT/DEFINER/警告态/动态消息负控、缺头/缺分号/附加正文负控、字符串分号与后续无 WHERE 写的边界。
3. `ci/sql_splitter.py`：仅同步两行说明；分句器执行代码逐字不变，所有真实 SQL 拆分路径仍复用它。

canonical `chengyinhub-admin/sql/migration_20261023_cash_withdraw_fund_fact.sql` 与 F 原版逐字一致，SHA256 仍为 `cf981d89214b219c41639537f301f2b55b2b568f2d310c06621e6df473b3f4ba`。拆分结果仍正好3条；表及两个触发器名、BEFORE UPDATE/DELETE、SQLSTATE45000、错误消息均保留。两个 Java 调用者 `CashWithdrawFundEvidenceMySqlIT`、`OWN_C04_21MySqlIT` 逐字不变；未把不支持 DELIMITER 的 ScriptUtils 当作客户端执行器。

## 实际运行与未验证

- `python3 -m unittest ci.test_migration_gates ci.test_migration_ledger`：188项实际通过（136+52），其中新增6项；不是 Java/MySQL 数。
- `bash ci/migration-safety-check.sh`：实际 exit1；136项门禁、52项台账自测通过，A 不再报错；仅 D 的两个无 WHERE UPDATE 使 SQL/共享库存检查失败。原始输出 `safety-full.txt`，退出码 `safety-status.json`。
- `audit_inventory`：剩余问题仅 D 的 `UNSUPPORTED_SQL_SHAPE`。A `check_sql_text=[]`；SQL及两Java loader与F字节相同比对通过；diff/秘密文件检查通过，见 `verification.txt`。
- **未运行** Java 编译、ScriptUtils/MySQL 实际重放、触发器 UPDATE/DELETE 真实拒绝测试、Redis/backend/支付/CI dispatch。协议未改变的静态证据不能冒充新一次数据库执行；根统一安排波次验证。

没有改业务 DDL，无 schema/hash 变化，无需因本候选刷新 SQL 内容身份；根仍须保留 F 的 canonical 路径与精确 custody。无生产 DDL、部署、权限、B Checkout/网关或 G 结算操作。沿用已授权隔离 CI 候选 commit/push 例外，整体门禁未通过不正式集成。回退本补丁只恢复误报，不影响已经落库的业务记录。旧 A/P2/fixture/WP12 候选及进行中的 merchant-cancel 工作树均未重建覆盖。
