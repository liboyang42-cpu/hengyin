# 数据库逻辑备份、隔离恢复与恢复演练（F-W3-01 / F-L6-05 / F-W3-02）

工具：`python3 scripts/db-backup-restore.py`（无第三方依赖，只调用 MySQL 官方 `mysql` / `mysqldump` 客户端）。
凭据放在仓库外、权限 600 的 MySQL defaults 文件；命令行不传口令，不把 SQL、凭据、私有回执纳入 Git。

## 1. 工具保证什么

- 备份命令固定为 `mysqldump --single-transaction --routines --triggers --events --set-gtid-purged=OFF --no-tablespaces --hex-blob`，**不加 `--force`**。坏视图、权限不足、任何对象导出失败都会让整次备份失败，而不是得到「看似成功、实际不完整」的文件（旧交接文档里的 `--force` 命令作废）。
- 备份前后各取一次快照：每张表的行数与 `CHECKSUM TABLE … EXTENDED`、视图清单、例程/触发器/事件数量。前后不一致即判失败（说明备份期间有写入），不发布 `.partial` 为成功备份。
- 回执 `<备份>.json`（600）记录来源 server_uuid、MySQL 版本、库名、备份文件 SHA-256 与快照。
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

恢复到任务独占的另一台 MySQL 实例（同版本、`--event-scheduler=OFF`），先建同名空库。保留同名 schema 是为了避免备份中的视图引用错库。

```sh
python3 scripts/db-backup-restore.py restore \
  --defaults-file /private/path/restore.cnf --database chengyinhubdb \
  --server-uuid RESTORE_UUID --backup /private/path/backup-YYYYMMDD-HHMM.sql --confirm-test-target
```

确认参数不自动证明主机属于测试环境：执行者必须先核对容器/实例归属、端点与 server_uuid。

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

要求（需用户落实，仓库无法代办）：把上述密钥放入离机托管（云 KMS 或密码库），写明谁能取用；演练时用托管副本 + 备份恢复出可用应用，才算恢复演练通过。不要把密钥放进备份文件、回执或本仓库。

## 6. 仍需用户确认/执行的事项

- 生产是否另有云盘快照、binlog 留存期限（测试库观测为 ROW 格式、保留 30 天，生产未知）；在线备份是用快照、只读从库还是维护窗口内的本工具。
- 生产上的坏视图需先修复或删除，否则无 `--force` 的备份会如实失败。
- 定期（建议每月及每次大版本发布前）按第 3、4 节演练一次，并记录 RTO/RPO。
- 本工具的生产执行、生产恢复都需要用户单独授权；工具单测和 VPS 演练不构成生产恢复证明。

## 7. VPS 工具夹具（回归用）

在两个新建、任务独占、不同 UUID 的 MySQL 8.0.45 测试实例中准备同名空库，源库加入以下夹具（不构成业务验收）：

```sql
CREATE TABLE sample_data (id BIGINT PRIMARY KEY, sample_text VARCHAR(100), data_blob BLOB) ENGINE=InnoDB;
INSERT INTO sample_data VALUES (1, '恢复夹具', UNHEX('00FF1020')), (2, 'plain fixture', NULL);
CREATE TABLE trigger_history (sample_id BIGINT) ENGINE=InnoDB;
CREATE VIEW sample_view AS SELECT id, sample_text FROM sample_data;
CREATE TRIGGER sample_added AFTER INSERT ON sample_data FOR EACH ROW INSERT INTO trigger_history VALUES (NEW.id);
CREATE PROCEDURE sample_total() SELECT COUNT(*) FROM sample_data;
CREATE EVENT sample_disabled ON SCHEDULE EVERY 1 DAY DISABLE DO INSERT INTO trigger_history VALUES (0);
```

正控：恢复侧行数、BLOB、视图、`CALL sample_total()`、例程/触发器/事件定义一致；向恢复侧插入新行后 trigger_history 增加，证明触发器可用；事件保持禁用。负控：篡改备份一字节、同 UUID、非空目标、缺确认、源库坏视图时必须失败且不覆盖备份、不清空目标。
