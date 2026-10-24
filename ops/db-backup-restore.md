# 数据库逻辑备份、隔离恢复与恢复演练（F-W3-01 / F-L6-05 / F-W3-02）

工具：`python3 scripts/db-backup-restore.py`（无第三方依赖，只调用 MySQL 官方 `mysql` / `mysqldump` 客户端）。
凭据放在仓库外、权限 600 的 MySQL defaults 文件；命令行不传口令，不把 SQL、凭据、私有回执纳入 Git。

## 1. 工具保证什么

- 备份命令固定为 `mysqldump --single-transaction --routines --triggers --events --set-gtid-purged=OFF --no-tablespaces --hex-blob`，**不加 `--force`**。坏视图、权限不足、任何对象导出失败都会让整次备份失败，而不是得到「看似成功、实际不完整」的文件（旧交接文档里的 `--force` 命令作废）。
- 备份前后各取一次快照：每张表的行数与 `CHECKSUM TABLE … EXTENDED`、视图清单、例程/触发器/事件数量，以及数据库、表、视图、过程、函数、触发器、事件的 `SHOW CREATE` 字段指纹。表定义含列、索引、约束和 `AUTO_INCREMENT`；对象定义含 SQL 正文、definer、SQL mode、字符集、事件计划等。前后不一致即判失败，不发布 `.partial` 为成功备份。
- 定义按字段名排序后计算 SHA-256，不替换 schema 名、不折叠 SQL 空白、不删 `AUTO_INCREMENT`。仅排除触发器的 `Created` 字段：它是恢复时重新生成的创建时刻，不是触发器正文。MySQL batch 输出只解转义一次，保留 Unicode、真实换行/制表符与字面反斜杠的区别；客户端显式使用 `utf8mb4`。定义不可见（例如例程正文返回 SQL NULL）时拒绝生成成功备份。参见 [MySQL batch/raw 输出规则](https://dev.mysql.com/doc/refman/8.0/en/mysql-command-options.html)、[SHOW CREATE TRIGGER 字段](https://dev.mysql.com/doc/refman/8.0/en/show-create-trigger.html)。
- 回执 `<备份>.json`（600）使用 `formatVersion: 3` 和 `definitionAlgorithm: mysql8-column-charset-v1`，记录来源 server_uuid、MySQL 版本、库名、备份文件 SHA-256 与快照；只存定义指纹，不写 SQL 正文。
- MySQL 已确认 [#110825](https://bugs.mysql.com/bug.php?id=110825)：隐式列排序规则在 `SHOW CREATE` 导出、重建后，可能多显示 `CHARACTER SET utf8mb4`。v3 仅根据真实 `information_schema.COLUMNS` 的列名、字符集、排序规则，把列声明顶层 `COLLATE` 前缺少的 utf8mb4 字符集显式写入指纹输入，并把上述列元数据一起纳入指纹。扫描器区分引号、反引号、注释和括号；字符串、注释、生成表达式内的同名文字不作改写。其余定义字节、CHECK、索引、外键和 AUTO_INCREMENT 保持；元数据矛盾、重复/缺失列或无法解析的定义均拒绝。遇到改变引号或反斜杠含义的 `ANSI_QUOTES` / `NO_BACKSLASH_ESCAPES` SQL mode，扫描器明确拒绝，不猜测其语法。SQL 备份文件本身不作改写。
- 恢复只接受：与来源**不同** server_uuid、**相同** MySQL 版本、库名与来源一致、目标库**完全为空**、`event_scheduler` 为 OFF/DISABLED 的实例，且必须显式 `--confirm-test-target`。恢复后重新计算快照，必须与回执逐表、逐对象完全一致。失败目标保持现场，不覆盖重试，不 DROP 任何东西。

## 2. 备份

备份需要一个**写入静止的窗口**（工具会核对前后快照）。生产上应放在发版维护窗口里、停应用写入之后执行；在线热备份应改用云盘快照或只读从库（见第 6 节，需用户确认）。

```sh
python3 scripts/db-backup-restore.py backup \
  --defaults-file /private/path/source.cnf --database chengyinhubdb \
  --server-uuid SOURCE_UUID --output /private/path/backup-YYYYMMDD-HHMM.sql
```

`SOURCE_UUID` 用 `SELECT @@server_uuid` 人工核对后填写，工具会再次比对，防止连错实例。

**迁移前必须先备份**：执行任何 `migration_*.sql`（`ci/run_migration.py`）前，先完成一次本节备份并保存回执；迁移报错时的「restore verified backup」指的就是这份带回执的备份。

## 3. 隔离恢复

恢复到任务独占的另一台 MySQL 实例（同版本、`--event-scheduler=OFF`），先建同名、数据库定义选项一致的空库（含字符集、排序规则等）。保留同名 schema 是为了避免备份中的视图引用错库。当前 dump 不含 `CREATE DATABASE`，工具不会代建或修改目标库；数据库定义指纹不一致会在导入前拒绝。

```sh
python3 scripts/db-backup-restore.py restore \
  --defaults-file /private/path/restore.cnf --database chengyinhubdb \
  --server-uuid RESTORE_UUID --backup /private/path/backup-YYYYMMDD-HHMM.sql --confirm-test-target
```

确认参数不自动证明主机属于测试环境：执行者必须先核对容器/实例归属、端点与 server_uuid。

旧 v1/v2 回执、未知算法或 v3 回执缺少任何对象指纹时，工具在访问恢复目标、执行导入前就拒绝。旧 SQL、回执和匹配版本的工具必须保留原件；v3 工具不自动升级、改写或导入旧算法回执。只有旧备份时须保留现场并单独核验其原算法和恢复目标，不能把旧 raw hash 或对象数量当作 v3 结构证明。先前的失败、OOM 或哈希不一致记录也不因新算法出现而改写。

## 4. 恢复后应用冒烟与 RTO/RPO 记录

数据库恢复通过后，起一个**不接生产流量**的应用实例指向恢复库（独立 Redis），至少回读：`/actuator/health`（受保护健康检查，含 db/redis）、后台登录、`/api/topic/list`、一笔历史订单详情。每次演练记录：

| 字段 | 含义 |
|---|---|
| 备份数据时点 | 停写后、备份开始时刻（= 可恢复到的最近时点） |
| 备份开始/完成 | 备份耗时 |
| 恢复开始/完成 | 数据恢复耗时 |
| 应用冒烟完成 | RTO 终点 |
| RPO | 故障时刻 − 备份数据时点（取决于备份频率与 binlog 留存） |
| RTO | 应用冒烟完成 − 开始恢复 |

## 5. 恢复应用还需要的密钥（F-W3-02）

只有数据库备份**不足以**恢复可用应用。以下密钥由生产 systemd drop-in 注入（见 `ci/deploy-restart.sh` 注释），仓库里没有、也不能有：

- `CHENGYIN_WITHDRAWAL_PII_KEY`：提现/报名/收货 PII 的 AES-256 密钥。**丢失后，即使数据库恢复成功，加密的银行卡、身份证、手机号也永久不可读。**
- `CHENGYIN_JWT_SECRET`：变更后全部登录态失效。
- `COUPON_QR_SECRET`：变更后已发出的券出示码失效。
- `CHENGYIN_DB_PASSWORD`、`SPRING_DATA_REDIS_PASSWORD`、`CHENGYIN_WXPAY_APIV3_KEY`、`CHENGYIN_WXMINI_SECRET`、`CHENGYIN_OSS_ACCESS_KEY_*`、`ALARM_WEBHOOK`。
- `CHENGYIN_WXPAY_REFUND_NOTIFY_URL`(非密钥,但生产必填):退款结果通知地址,`application.yml` 默认留空(=不下发 notify_url、由商户平台默认回调兜底),生产须显式注入指向 `/api/registration/refund-callback` 的 HTTPS 地址,`scripts/production-preflight.sh` 会校验。
- `CHENGYIN_WXPAY_NOTIFY_URL`(非密钥,但生产必填):支付结果通知地址,`application.yml` 默认留空(=拒绝下单并提示「支付回调地址未配置」,不会带空值或生产地址调用微信),生产须显式注入指向 `/api/registration/callback` 的 HTTPS 地址,`scripts/production-preflight.sh` 会校验;测试/预发环境注入各自域名的回调。

要求（需用户落实，仓库无法代办）：把上述密钥放入离机托管（云 KMS 或密码库），写明谁能取用；演练时用托管副本 + 备份恢复出可用应用，才算恢复演练通过。不要把密钥放进备份文件、回执或本仓库。

## 6. 仍需用户确认/执行的事项

- 生产是否另有云盘快照、binlog 留存期限（测试库观测为 ROW 格式、保留 30 天，生产未知）；在线备份是用快照、只读从库还是维护窗口内的本工具。
- 生产上的坏视图需先修复或删除，否则无 `--force` 的备份会如实失败。
- 定期（建议每月及每次大版本发布前）按第 3、4 节演练一次，并记录 RTO/RPO。
- 本工具的生产执行、生产恢复都需要用户单独授权；工具单测和 VPS 演练不构成生产恢复证明。

## 7. VPS 工具夹具（回归用）

在两个新建、任务独占、不同 UUID 的 MySQL 8.0.45 测试实例中准备同名空库，源库加入以下夹具（不构成业务验收）：

```sql
CREATE TABLE sample_data (id BIGINT PRIMARY KEY, sample_text VARCHAR(100), data_blob BLOB) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
INSERT INTO sample_data VALUES (1, '恢复夹具', UNHEX('00FF1020')), (2, 'plain fixture', NULL);
CREATE TABLE trigger_history (sample_id BIGINT) ENGINE=InnoDB;
CREATE VIEW sample_view AS SELECT id, sample_text FROM sample_data;
CREATE TRIGGER sample_added AFTER INSERT ON sample_data FOR EACH ROW INSERT INTO trigger_history VALUES (NEW.id);
CREATE PROCEDURE sample_total() SELECT COUNT(*) FROM sample_data;
CREATE FUNCTION sample_next(v INT) RETURNS INT DETERMINISTIC NO SQL RETURN v + 1;
CREATE EVENT sample_disabled ON SCHEDULE EVERY 1 DAY DISABLE DO INSERT INTO trigger_history VALUES (0);
```

正控：恢复侧行数、BLOB、视图、`CALL sample_total()`、`SELECT sample_next(8)`、例程/触发器/事件定义一致；向恢复侧插入新行后 trigger_history 增加，证明触发器可用；事件保持禁用。负控：篡改备份一字节、同 UUID、非空目标、缺确认、源库坏视图时必须失败且不覆盖备份、不清空目标。同名/同数量对象被改定义、索引或约束丢失、事件计划改变时也必须失败；仅触发器创建时刻变化允许通过。旧回执、缺对象指纹、目标库默认定义不同必须在导入前拒绝。
