# O14 city_code 受控撤回

`ci/o14_backfill_rollback.py` 只撤回经过审核的 `cms_topic` / `cms_activity` **FILL** 行：原值必须有执行前快照证明为 `NULL`，当前值必须精确等于 manifest 的写入值，当前日志的 `city_code`、`source`、`write_time` 必须逐项相同。默认预览；只有显式 `--apply` 才执行更新。

此工具只清空选定行的 `city_code`，不删除来源日志、不执行迁移或其他 DDL，不修改任务开关、通知、奖励或其他业务列。已有迁移不作修改。仅支持现有 runner 接受的 MySQL 8.0.45 及其血统排除规则，复用已有 PyMySQL，不增加依赖。

## 证据与适用边界

真实日志表 `o14_city_code_backfill_log` 只有 `target_table,row_id,city_code,source,write_time`，主键为 `(target_table,row_id)`；没有旧值或批次号。现有回填会删除/重插自己认领的日志，刷新 `write_time`，也可能执行 `CORRECT_OWN` / `CLEAR_OWN`。因此不能用来源字段或当前日志推断原值是 NULL，也不能仅按日期范围整批清空。

执行前须同时具备：

1. 实际回填前保存的逐行前像 JSON。由 owner 对原始快照、导出记录与捕获时间核对其真实性；不能在撤回时根据当前日志伪造前像。工具校验结构、摘要、库和服务器、精确目标集合及每行 NULL；SHA256 只能证明文件完整性，不能独立证明采集来源或时间真实性。
2. 回填后人工审核的 manifest，逐行固定目标、写入值、来源、日志写入时间，并绑定前像文件的 SHA256。
3. 独立审核记录中保存的 manifest 原始字节 SHA256、预期数据库和总行数。执行时显式传入这三个值；不要在执行脚本中从待执行 manifest 动态计算摘要后立即当成审核结果。
4. 经授权、已核实的目标实例与库，及所需读写和元数据检查权限。工具比对实际 `DATABASE()` 和 `@@server_uuid`，但环境变量和文件本身不能代替环境授权。

缺少前像、原值非 NULL、快照目标不一致或日志漂移时拒绝整批操作。`CORRECT_OWN` 的旧非 NULL 值不能由本工具恢复；需要保留原始前像并另行审核专门的恢复方案，不得把它混入 FILL 撤回。对之前已经做过人工修改又改回相同值、或同一秒内完全相同日志重写，现有五列日志无法辨别历史；如存在这类历史疑点，暂停执行并核对外部审计记录。

## 文件合同

前像 `before.json` 必须来自回填前的真实采集。下面仅展示格式；所有示例标识均须替换为获准目标的真实值。`captured_at` 为 UTC，日志 `write_time` 保留数据库原始 DATETIME，不做时区换算。两个文件都不包含主机、账号或口令。

```json
{
  "format_version": 1,
  "database": "approved_database",
  "server_uuid": "11111111-2222-3333-4444-555555555555",
  "captured_at": "2026-10-22T03:00:00Z",
  "rows": [
    {"target_table": "cms_topic", "row_id": 11, "city_code": null},
    {"target_table": "cms_activity", "row_id": 12, "city_code": null}
  ]
}
```

manifest 与前像必须是同一精确目标集合，不接受重复行、额外字段或重复 JSON 键。`before_snapshot_sha256` 为前像文件**原始字节**摘要，必须是 64 位小写十六进制。

```json
{
  "format_version": 1,
  "database": "approved_database",
  "server_uuid": "11111111-2222-3333-4444-555555555555",
  "expected_count": 2,
  "before_snapshot_sha256": "<审核过的前像文件64位SHA256>",
  "rows": [
    {"target_table": "cms_topic", "row_id": 11, "city_code": "310100", "source": "o14_v2:club_city", "write_time": "2026-10-22 11:01:02"},
    {"target_table": "cms_activity", "row_id": 12, "city_code": "310100", "source": "o14_v2:parent_topic", "write_time": "2026-10-22 11:01:02"}
  ]
}
```

主题只接受 `o14_v2:club_city`；活动另可接受 `o14_v2:parent_topic`。城市码必须是六位数字；主键必须是正有符号 BIGINT。工具不会自行枚举或扩展目标。

## 运行

在已授权执行环境中，由受保护的环境变量注入器设置 `O14_MYSQL_HOST`、`O14_MYSQL_PORT`、`O14_MYSQL_DATABASE`、`O14_MYSQL_USER`、`O14_MYSQL_PASSWORD`。五项必须显式存在，口令允许显式空值用于隔离 CI；不读取 YAML，不自动选择生产或打开隧道。不在命令行传凭据，不把环境文件复制入仓库或报告。

数据库账号需有直接授予的全局 SELECT（用于排除跨库指向 city_code 的外键）、UPDATE 两个业务表及相关元数据权限。为证明触发器不存在，还须对三张目标表具有直接授予的 `TRIGGER` 权限，或对应库级/全局 `TRIGGER` 权限；仅经角色继承、工具无法直接证明可见性的授权会被拒绝。工具只检查这些权限，不修改授权或创建、删除触发器。任一目标存在触发器、city_code 外键、非 InnoDB 基础表、主键形状不符或 city_code 不可空时拒绝操作，先单独审核。不要为绕过拒绝而临时扩大生产账号权限；应由已有授权且满足审核可见性的执行通道处理。

先执行默认预览，示例中的参数均来自审核记录：

```sh
python3 ci/o14_backfill_rollback.py \
  --manifest /secure/o14-manifest.json \
  --sha256 "$REVIEWED_MANIFEST_SHA256" \
  --before-snapshot /secure/o14-before.json \
  --database "$REVIEWED_DATABASE" \
  --expected-count 2
```

预览与 apply 都在显式 REPEATABLE READ 事务中，按固定顺序锁定每行日志和业务行，重新核对完整批次，随后检查已持有元数据锁的表结构。预览不发 DML，最终回滚释放锁。预览仍会短暂阻塞冲突写入，选低峰执行；退出成功只表示本次核对通过，不能替代 apply 时的重核。

完成授权与审核后，以完全相同参数增加 `--apply`。每次 UPDATE 都带主键和值的二进制精确匹配条件，必须恰好影响一行；写后在同一事务内再次核对所有日志未变和所有目标为 NULL，才提交。任一行不符、锁冲突、超时或数据库错误都拒绝整批提交。提交阶段连接失败时结果可能不确定，工具不会声称成功；必须先独立回读，不能盲目重试。

成功输出仅含状态、库名、行数、日志保留标识与 manifest SHA256；失败不输出驱动异常中的主机、SQL或凭据。退出码 0 表示预览通过或提交成功，非 0 必须停止并在受保护环境查明原因。

## 收口与恢复

用新的只读连接按 manifest 的精确主键回读：目标 `city_code` 全为 NULL；日志五列与执行前逐项相同；未选行、其他业务列和任务开关不变。保存前像、manifest、独立审核摘要、命令状态、回读证据与操作授权。不要将业务导出或受保护材料放进公开仓库。

再次撤回同一 manifest 会因当前值已 NULL 而拒绝，不把重复执行计为成功，也不会删掉旧日志来伪造可重试状态。撤回后保留日志是审计要求；未来重新执行原回填迁移仍可能把 NULL 行再次填入，**本工具不禁用回填或后台任务**。owner 必须在后续操作计划中保留这一事实。需要恢复刚撤回的值时，也应基于保留的证据重新审核，不能自动重跑全库回填。

## 定向验证

Mac 只运行标准库测试，不连接任何数据库：

```sh
python3 -m unittest ci.test_o14_backfill_rollback -v
```

真实 MySQL 测试交标准 GitHub-hosted CI 的既有隔离 MySQL 服务：显式 `CHENGYIN_MYSQL_IT=1`，同时注入 `CHENGYIN_MYSQL_IT_HOST/PORT/USER/PASSWORD`，HOST 必须为 loopback；缺前提会失败，不会 skip 或回退。命令：

```sh
python3 -m unittest ci.test_o14_backfill_rollback_mysql_integration -v
```

每类只创建一个 `cy_o14_rollback_it_<随机值>` 库，拒绝接管已有库，结束只清理本类库并回读确认。每例执行仓库真实 O14 列、日志和回填迁移后测试撤回工具，包括默认预览、CLI apply 与独立回读、保留日志和未选行、人工漂移、日志刷新/删除、第二张表 CHECK 失败时第一张表回滚，以及真实行锁阻挡并发人工写入。标准库 mock 通过不能替代这些 MySQL 证据，CI 通过也不代表任何生产撤回已获授权或已经执行。
