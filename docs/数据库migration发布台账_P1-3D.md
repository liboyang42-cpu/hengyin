# 数据库 migration 发布台账（P1-3D）

本底座只生成不可变 manifest、校验 history 快照并给出只读计划，不连接数据库，也没有 `apply` 命令。生产执行仍严格走 `chengyin-db-migrate` 的“只读预检 → 用户确认 → 单个执行 → 从数据库实际结构/数据读回”流程；history 行不是数据库效果的替代证据。

## 文件与命令

- `ci/migration_ledger.py audit`：盘点仓库内全部 `.sql`。默认仅 `chengyinhub-admin/sql/` 第一层的 `migration_*.sql` 进入托管执行集合；两个不可重命名的历史 seed 由代码内精确路径合同纳入 managed，四个参考/IT 文件由精确路径合同标为 `REFERENCE_ONLY/TEST_FIXTURE`。本轮冻结 inventory 的 manifest 快照为 managed 238、auxiliary 27；数量必须从 audit/manifest 实时生成，不以本文档快照作为后续计数真源。除此之外的 `seed_*.sql`、错目录、嵌套 migration、大小写碰撞、重复 ID、无法由 `ci/sql_splitter.py` 安全分句的形状仍全部报红；不使用前缀或目录通配白名单。输出只有路径、摘要、分类和问题码，不含 SQL 正文。
- `ci/migration_ledger.py manifest --target-commit <40位SHA> --output <file>`：生成相对路径、SHA-256、显式依赖、稳定拓扑顺序和目标 commit。依赖真源是 `ci/migration-dependencies.json`。
- `scripts/migration-ledger-precheck.sh <manifest> <history-snapshot>`：默认且唯一的只读 plan seam。先重算工作区文件 checksum，再输出 `PENDING`、`APPLIED` 或阻断态。
- `scripts/migration-ledger-rehearsal.sh --local-only`：纯本地状态机和分句负控；不把缺失 MySQL 8 验证写成 PASS。不带 `--local-only` 时，没有真实 MySQL 8 验证会以 exit 2 收口。
- `scripts/release-manifest.sh`：发布清单生成前调用同一 manifest 校验器，保留旧 `migrations[path,sha256]` 接口并嵌入完整 `migrationLedger`。

## 状态合同

| plan 状态 | 含义 | 行为 |
|---|---|---|
| `PENDING` | manifest 有文件，history 无记录 | 只进入人工生产 Gate，不自动执行 |
| `BLOCKED_BOOTSTRAP` | 既有数据库没有 reconciliation 基线，或基线 migration 缺 history 行 | 禁止把全量历史文件误当首次待执行 |
| `APPLIED` | history 为 `APPLIED/RECONCILED`，且 ID、路径、checksum 一致 | 幂等跳过，禁止重复执行 |
| `BLOCKED_DRIFT` | 已记录 ID 的路径或 checksum 改变 | 立即停止，禁止覆盖 history 或重跑 |
| `BLOCKED_UNKNOWN` | history 仍是 `APPLYING/UNKNOWN` | 立即停止，从数据库实际结果读回后人工 reconciliation |
| `BLOCKED_MISSING` | history 有记录但仓库不可变文件缺失 | 立即停止，恢复文件或完成审计，不得删除记录掩盖 |
| `BLOCKED_DEPENDENCY_ORDER` | history 显示子迁移早于依赖被应用 | 立即停止，人工核对真实结构与执行顺序 |

`cy_migration_history` 只保存 `migration_id / migration_path / checksum_sha256 / target_commit / applied_at / status` 和行时间戳，不保存密码、Token 或 SQL 正文。状态流是 `APPLYING → APPLIED`；任何失败、断连或无法确认的结果进入 `UNKNOWN`（若连更新也失败则残留 `APPLYING`，计划同样阻断）。只有基于数据库实际结构/数据的人工核验才能改为 `RECONCILED`。

plan 只接受 `LOCAL_FIXTURE_ONLY`（仅 `FRESH_EMPTY`）或 `CHENGYIN_DB_MIGRATE_READ_ONLY_EXPORT` 两种来源，拒绝 `SELF_ASSERTED`。来源标签仍不是数据库效果证明：输出固定包含 `productionApplyAuthorized=false` 与 `databaseEffectReadback=REQUIRED_BEFORE_PRODUCTION_APPLY`，必须继续走人工生产读回 Gate。

## Bootstrap、失败与恢复边界

1. `migration_schema_history_20260724.sql` 本身也只是仓库 migration；上线前须按 `chengyin-db-migrate` 做只读预检并由用户确认，不能由 CI、部署脚本或本工具自动落库。
2. 历史 migration 是否执行过，不能从“文件在仓库”“PR 已合并”或“history 刚写入的值”推断。既有数据库默认是 `RECONCILIATION_REQUIRED/BLOCKED_BOOTSTRAP`，不会把空 history 解释成 manifest 中全部 managed migration 均可执行的 `PENDING`。Bootstrap 必须逐项依据数据库自身产生的 schema/data 事实核验后写 `RECONCILED`，并记录已核验的 baseline target commit；baseline 中少一行也继续阻断。`FRESH_EMPTY` 只允许显式的 `LOCAL_FIXTURE_ONLY` 临时环境。
3. MySQL DDL 会隐式提交；多语句 migration 不能宣传全事务或 exactly-once。某条 DDL 成功、后续语句失败时，整体只能标 `UNKNOWN`，修复前必须读回每个受影响对象。
4. `ci/sql_splitter.py` 正确保留字符串内分号，并严格解析独立行 `DELIMITER $$` / `DELIMITER //`：过程/函数/触发器必须由这两种仓库合同内的自定义 delimiter 完整结束，文件结束前必须恢复 `;`；非法指令、裸过程或未恢复 delimiter 均由 audit fail-closed。不得改用裸 `split(';')` 绕过。

## 历史 SQL 精确分类

以下是为修复 2026-08-14 inventory blocker 冻结的逐文件合同；历史文件不改名、不改 bytes，因此 checksum 保持不变。任何未列出的 seed、参考 SQL 或测试目录 SQL 仍报红；`REFERENCE_ONLY` 即使路径命中，只要出现可执行语句或解析错误也会报红。

| 路径 | 分类 | 理由 |
|---|---|---|
| `chengyinhub-admin/sql/migration_compliance_uniqueness_guard_20260712.sql` | managed | schema migration；由受校验的 `DELIMITER $$` 完整解析 |
| `chengyinhub-admin/sql/migration_fund_withdraw_dedup.sql` | managed | schema migration；由受校验的 `DELIMITER $$` 完整解析 |
| `chengyinhub-admin/sql/migration_fund_withdraw_statemachine.sql` | managed | schema migration；两个 procedure 块均完整解析 |
| `chengyinhub-admin/sql/migration_registration_request_id.sql` | managed | schema migration；由受校验的 `DELIMITER //` 完整解析 |
| `chengyinhub-admin/sql/seed_preset_demo.sql` | managed | 生产 DML seed，显式依赖 `preset_route` |
| `chengyinhub-admin/sql/seed_refund_manual_menu_20260714.sql` | managed | 生产 `sys_menu/sys_job` 注册 DML |
| `chengyinhub-admin/sql/seed_official_content_template.sql` | `REFERENCE_ONLY` | 全文件只有注释和占位模板，无可执行语句 |
| `chengyinhub-admin/sql/_四账映射与守恒规则_20260725.sql` | `REFERENCE_ONLY` | 文件头明确声明只读参考文档，不产生数据库效果 |
| `chengyinhub-admin/src/test/resources/it-schema/cms-topic-it-schema.sql` | `TEST_FIXTURE` | IT 从 classpath 加载的生产 schema 快照 |
| `chengyinhub-admin/src/test/resources/it-schema/coop-it-schema.sql` | `TEST_FIXTURE` | IT 从 classpath 加载的生产 schema 快照 |
5. 生产 apply 仍是人工 Gate：只读预检、风险评估、用户确认、一次一个 migration、执行后从数据库读回。`audit_sys_job.py` 继续负责 Component task 与生产 `sys_job` 对账，本底座不复制该能力。

## History 快照格式

只读查询结果应在受控流程中转成以下元数据结构，再交给 plan；不要在仓库提交生产快照：

```json
{
  "schemaVersion": 1,
  "source": "CHENGYIN_DB_MIGRATE_READ_ONLY_EXPORT",
  "bootstrap": {
    "status": "RECONCILED",
    "targetCommit": "完成逐项核验时的40位commit SHA"
  },
  "rows": [
    {
      "migrationId": "example_20260724",
      "path": "chengyinhub-admin/sql/migration_example_20260724.sql",
      "checksum": "64位小写SHA-256",
      "targetCommit": "40位小写commit SHA",
      "appliedAt": "2026-07-24T12:00:00Z",
      "status": "APPLIED"
    }
  ]
}
```
