# 迁移差异台账（Lane R1，2026-10-22）

> 对应 Doc 1 §10 第 2 步（migration diff）。**这是只读的静态分析**：本 lane 没有连接任何数据库或主机，没有执行任何 SQL，也没有对其他 PR 做任何写操作。
> 下表中的 **applied? 全部为 UNKNOWN**。本文件**不声称**任何迁移已在生产执行或尚未执行。只有 owner 走 chengyin-db-migrate 只读预检之后，才能回填这一列。

## 0 口径

| 项 | 值 |
|---|---|
| 生产参考基线 | `19408d49172c`（Merge #452，2026-10-22）。该提交在 master 历史中存在，所以不回退到 `063a6c3c` |
| master 终点 | `f0b43d62d4d1`（Merge #475，batch 1） |
| batch 2 集成分支终点 | `integrate/closeout-batch2-20261022` @ `b2624505b5e2`（生成时的快照；分支后续若有新推送，需要重跑） |
| 受管迁移目录 | `chengyinhub-admin/sql/`。仓库根的 `sql/` 下没有 `migration_*.sql`，只有 bootstrap 快照 |
| 受管迁移总数 | 基线 486 → master 488 → batch 2 为 491（含 2 个显式纳管的 `seed_*`） |
| 执行序 | 与 runner 一致：先按 `ci/migration-dependencies.json` 的显式依赖，再按路径字节序做拓扑排序（`ci/migration_ledger.py`） |
| 生成方式 | `python3 ci/release_manifest.py diff --base 19408d49 --head <rev>`（只读 git 对象） |

“执行序”列是该迁移在终点 commit 全量清单中的序号。对生产来说，有意义的只是**待执行迁移之间的相对顺序**。

## 1 基线 → master（`19408d49..f0b43d62`）：3 个

| 执行序 | 变更 | 文件 | sha256（master） | 幂等性（静态） | 破坏性语句 | 依赖 | applied? | 需 owner 批准 |
|---|---|---|---|---|---|---|---|---|
| 45 | 新增 | `migration_20261018_infomation_change_audit.sql` | `0e023798a882a7aec4e5a9de9815d7dcf39175600fc9d3c6d6c640a4dc304695` | 静态判定为幂等：`CREATE TABLE IF NOT EXISTS`，另有 `information_schema` 结构断言（`runner: assert=scalar-true`），同名但结构残缺的表会被判红 | 无 | 无 | UNKNOWN | **是** |
| 468 | **修改** | `migration_20261013_admin_role_page_grants.sql` | `1f147a2136e999b6fd87a7e0a865d8fde5b0c332170d561b28e415b9fccaf56e`（基线为 `0834bcafb1c9a2f169377445d463cc34a6dce7fa2e96e6b6f81d09ab65254ead`） | 静态判定为幂等：只做 INSERT，每条都带 `NOT EXISTS`；临时表先 `DROP TEMPORARY … IF EXISTS` 再建 | 无（只删临时表） | `web_admin_roles_20260719`、`20261012_missing_business_menu`、`20261012_media_review_menu`、`role2_analytics_perm_20260918` | UNKNOWN | **是** |
| 469 | 新增 | `migration_20261016_admin_role_payee_separation_forward.sql` | `4eeef3936f72f05e69b3440aa818c2c39d53e046aaabd82cfdef10c868cca34e` | 静态判定为幂等：INSERT 都带 `NOT EXISTS`，DELETE 重跑时不会再删到东西；`runner: requires=transaction`，guard 表主键冲突会让整个事务回滚 | **2 条带 WHERE 的定点 DELETE**：撤销 `cy_operator` 的 `business:clubpayee:verify` 授权，以及 `cy_finance` 的 `business:clubpayee:payout` 授权（收款资料审核与打款职责分离） | `20261013_admin_role_page_grants` 和三个 tandianri batch3 资金迁移 | UNKNOWN | **是** |

**要特别看的一项：`20261013` 的字节在基线和 master 之间变了。** `5db04c62` 把它恢复成了原始字节，收款审核/打款的互斥改由前向迁移 `20261016` 来纠正（owner 已裁定）。生产的迁移历史里记录的是哪一个 checksum，目前未知：
- 如果生产按**基线版本**（`0834bcaf…`）执行过，runner 会遇到 checksum 不一致，需要 owner 先决定怎么对账，不能直接重跑；
- 如果生产没执行过，或者执行的是原始字节（`1f147a21…`），就按正常顺序处理：先 `20261013`，再 `20261016`。

只读预检时必须拿生产历史表里的 checksum 和**这两个值**都比一遍。

## 2 基线 → batch 2 集成分支（`19408d49..b2624505`）：6 个

第 1 节的 3 个全部包含在内（sha256 相同），另外多出 3 个。

| 执行序 | 变更 | 文件 | 来源 | sha256 | 幂等性（静态） | 破坏性语句 | 依赖 | applied? | 需 owner 批准 |
|---|---|---|---|---|---|---|---|---|---|
| 45 | 新增 | `migration_20261018_infomation_change_audit.sql` | W8 port（#311） | `0e023798…4dc304695`（同上） | 同上 | 无 | 无 | UNKNOWN | **是** |
| 46 | 新增 | **`migration_20261022_audit_activity_switch_seed.sql`** | **#376 活动审核总闸落种**，Lane W5（#471） | `4a9d4e63cde4afc7b5d6f4a17f46d0489b7508bafead3f3e8363448c3d4f59c7` | 静态判定为幂等：两条 `INSERT … WHERE NOT EXISTS`（自然键 `config_key`），不覆盖运维已经配置的值；有回读断言 | 无 | 无（只依赖 RuoYi 的 `sys_config`） | UNKNOWN | **是** |
| 176 | 新增 | `migration_member_profile_version_20261022.sql` | Lane W10b #25（#477） | `8be4a0d82578fe1d74579f5abbf859307f0b827655d670ff0e398cf026d018ba` | 静态判定为幂等：`CREATE TABLE IF NOT EXISTS`（没有结构断言，同名残表会被静默接受） | 无（注释里写的回滚是 `DROP TABLE`，不会执行，需要另行批准） | 无 | UNKNOWN | **是** |
| 361 | 新增 | `migration_roam_share_version_20261022.sql` | Lane W10b #23（#477） | `563ddd80ae4e23ce96d02183380d9175209e68702aaf06f1061fd9a6688170b6` | 有 `information_schema` 守卫：列已存在就执行 `SELECT 1`，否则走动态 `ALTER … ADD COLUMN`；有回读断言 | 无（注释里写的回滚是 `DROP COLUMN`，不会执行，需要另行批准） | `20260917_roam_share_snapshot` | UNKNOWN | **是** |
| 471 | **修改** | `migration_20261013_admin_role_page_grants.sql` | 见第 1 节 | `1f147a21…ccaf56e`（同上） | 同上 | 无 | 同上 | UNKNOWN | **是** |
| 472 | 新增 | `migration_20261016_admin_role_payee_separation_forward.sql` | W8 port（#311） | `4eeef393…68cca34e`（同上） | 同上 | 2 条带 WHERE 的定点 DELETE（同上） | 同上 | UNKNOWN | **是** |

batch 2 相对 master 新增的 W-lane 迁移只有上表里的 #471（W5）和 #477（W10b，2 个）。合入 batch 2 的 #472、#473、#474、#478、#479、#481、#482 都没有带迁移。

## 3 待执行迁移之间的依赖顺序（batch 2 终点）

```
20261018_infomation_change_audit          (无依赖)
20261022_audit_activity_switch_seed       (无依赖)
member_profile_version_20261022           (无依赖；须先于新 jar)
roam_share_version_20261022               ← 20260917_roam_share_snapshot（须先于新 jar：新 jar 发布时会写 version 列）
20261013_admin_role_page_grants           ← 4 个菜单/角色迁移（checksum 对账见第 1 节）
20261016_admin_role_payee_separation_forward ← 20261013_admin_role_page_grants + 3 个资金迁移
```

上面只列出了这 6 个迁移的直接前置。前置迁移在生产上是否已经执行，同样是 UNKNOWN，要在只读预检里一并确认。

## 4 结论

- **需要 owner 批准的迁移数：6**（batch 2 终点；只看 master 的话是 3）。生产 DDL/DML 一律要经过 owner 确认，并先做 chengyin-db-migrate 只读预检。
- **静态扫描未发现不安全语句**：没有 `DROP TABLE`/`DROP COLUMN`、`TRUNCATE`、不带 WHERE 的 `DELETE`/`UPDATE`，也没有 `RENAME`；`ci/migration_gates.check_sql_text` 对这 6 个文件都没有报问题。
- **需要人工核对的风险点**：
  1. `20261016` 有 2 条定点 DELETE，会撤销两个现有的角色-菜单授权。这是有意为之的权限收紧，但会改变生产上的权限。
  2. `20261013` 的 checksum 在基线和 master 之间变了，需要先对账（见第 1 节）。
  3. `member_profile_version_20261022` 只靠 `IF NOT EXISTS`，没有结构断言。
- 次要发现（不影响排序）：`ci/migration-dependencies.json` 里 `20261015_retention_job_missing_registration` 和 `20261015_wx_bill_ingestion` 各出现两次，第一次的值是 `[]`。runner 用的是 `json.loads`，以最后一次为准（非空的那一份），所以本台账的顺序和 runner 一致。本 lane 没有改动这个文件。
- 静态分析不能代替只读预检，只读预检也不能代替 owner 批准。
