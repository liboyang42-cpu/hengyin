# E02_25 真库夹具前提：仅静态核查

固定源码 `6c6f82d7d9049f3f2d65f609be6b01c4a5643ec4`，直接父 `c35a46357cbbadfbcc61c881db46b6e51e7cc725`。只在 E02_25MySqlIT 的独占 scratch schema 初始化添加一行 `create table cms_activity(id bigint primary key,publish_status int,cancel_time datetime)`，满足新 selectReferences 对既有表的读取前提。

root 实际 diff/assert：精确去掉这一行后文件逐字等于父版本；两项测试方法、全部断言、显式 MySQL gate、创建自有 scratch 数据库及其清理均未变。没有生产、依赖、迁移或入口变化。

**实际测试 0；未编译，真实 MySQL 仍 NOT_RUN。** 本次是一个 Java SQL 字符串的静态前提修正，无可用的现有真库执行入口，重跑之前 4 项不能验证这处真实 MySQL fixture。6143 的普通 4 PASS 不继承到本 SHA。

另外 E02_01Test、E02_11Test、E02_06Test 及 B05CommunityVisibilityMySqlIT 的同类旧夹具仍需精确写权登记或原 owner 交接，未修改。不得因本次前提修正声称五个真库门禁完成。保持现有入口不新增，未迁移、合并或部署。详细静态断言见 [summary.json](summary.json)。
