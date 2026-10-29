# 119d migration / HTTP 共同前置修补

结论：交付 gate 两文件源码与一份必须由 root 串行应用的 inventory 精确 review.patch；不改 runner、任何 SQL、DAG、工作流或生产业务。尚未运行修后控制，不能称验收通过。

## 身份与证据

- 公共 parent：`119d3f87bbf89162d47db837c404b361915d1b81`。
- 独立目录：`/workspace/hengyin-g-119d-migration-gates`；分支：`codex/architecture-day-g-119d-migration-gates`。未切换、覆盖其他 owner 工作树。
- 实际回读标准 `40215793929/job121236649595` 完整日志：原 139 tests / 2 failures，均为 runner 四个 contract 指纹 `SCRATCH_SCHEMA / validate_target / connect / notice_forward_plan`；后续依赖棘轮点名删除 `20261024_official_roam_challenges / 20261025_official_poi_quotes`。
- 实际回读 HTTP `40215802616/job121236545029` 完整日志：56 tests / 1 failure / 4 errors。四 error 均由 `build_manifest -> audit_inventory -> LedgerError("SQL inventory is unsafe")`，尚未连接 MySQL / 启动应用；另一 ManualRouter failure 属 B/F 原范围，本片不改。原日志没有打印 `LedgerError.problems`，不虚构该次运行逐项回读。

## 三个接缝

1. **跨 Python AST 默认显示**：`git diff 6fcd5ac009cec1dd82c438528d24da30c1f302f4 119d... -- scripts/e2e/build_schema.py` 为空。四个失败节点均含 `Call`，其余六个常量指纹未报错。原 hosted 日志路径为 Python 3.14；3.13 起 `ast.dump` 默认省略空列表。新 `_schema_contract_dump` 在支持版本明确 `show_empty=True`，恢复原已审指纹使用的 `args=[] / keywords=[]` 序列化，**十个原 hash 不改**。其余 AST 结构比对、唯一执行序、源身份、fresh-only、严格 v2 stop/finally/路径/无额外 loop 和全部原 mutation 断言不变。新增固定字符串控制捕获序列化漂移及真实参数变化。修后跨版本结果仍 NOT_RUN。

2. **棘轮比较源错位**：原 shell 先算 `BASE=merge-base(remote/master, HEAD)` 并传 `dep-order --base "$BASE"`，但 Python 再调用 `master_dependencies(root)` 直接读取 live master。真实本批 merge-base 是 `51f05499c1523648a328f57bcd7927f6d9546003`；该源没有上述两项，更新后的 master `58f4cc429b0d0e3035ccdf1bd4a9c3dfd76eb9a4` 才带它们。新代码用同一个明确祖先读取依赖；手工文件模式也取公共祖先而非未来独立 master。明确基线不可读、非祖先或 diff 失败返回拒绝，不能换 ref 或跳过。旧基线内丢 entry / 丢 edge 仍在空新增批之前拒绝。没有把新 master SQL/边搬入旧候选。

3. **HTTP inventory 真遗漏**：119d 的已跟踪 `docs/delivery/architecture-day/G/supply-order-fulfillment-contract/PROPOSED_TABLE.sql` 没有出现在 `EXPLICIT_AUXILIARY_SQL`，也不属于 managed migration 或 bootstrap；依 `audit_inventory` 原分支必达 `UNMANAGED_SQL_LOCATION`。它是明确标注不可执行的历史方案，不是 canonical 迁移。给 root 的 `sql-inventory.review.patch.b64` 仅将这一个准确路径列为 `AUXILIARY_NOT_EXECUTED`；其 SHA 仍进入完整 inventory，真实 `migration_20261025_coop_order_station_fulfillment.sql` 仍是 managed，不能借此跳过。新增源码控制检验 canonical 保留、提案不进执行清单、同目录未审路径仍拒绝。该静态必拒来源不冒充原 HTTP 未输出的 problems 全集；是否还有其他运行问题待集中复验。

## root 装配

先收本提交的 `ci/migration_gates.py / ci/test_migration_gates.py`，再串行应用同目录 `sql-inventory.review.patch.b64` 解码后的两个窄 hunk（`ci/migration_ledger.py / ci/test_migration_ledger.py`）。后者只是 proposal，没有在 G 分支覆盖共享文件；忘记应用时库存拒绝仍然成立。无需 source-custody、DAG、旧 SQL、runner、HTTP fixture 或 CI 的其他增量。inventory proposal postimage hashes 见 `POSTIMAGE.sha256` 的 `/tmp/g-119d-review/...` 两项；路径指向 root 应用后的目标内容。

新 gate 普通 test 声明 +5（原 139 声明保留）；review.patch ledger +1。新增真实临时 Git 图控制固定 base、未来 master、删边、删 entry、缺失/非祖先 base，未以 mock 固定成功。全部原正负控、strict fresh v2 保护均保留；没有迁移 baseline 扩免。

## 已做与未做

- 已做：Git 源码/历史/差异读取、上述两个已结束 job 日志读取、精确源码补丁、配套测试源码、文件 SHA 与 `git diff --check`。
- **NOT_RUN**：所有 Python 控制（含新增与原失败定点）、编译、Java、MySQL、HTTP、CI、应用启动。遵守本批只 SOURCE 指令，没有 dispatch。
- runner SHA256 保持 `f7852a8d2caa6c2d2a34ff43fdaa68013d2ca5a2e7e953ce022f7b40f01a61a0`；历史提案 SQL SHA256 保持 `f1748c7fd10eee3186af31fa5bd012090729e51a8bced37ff94ed2f3b7a5e120`。全部 SQL/DAG/runner 字节未改，未 DDL/部署。
- 回退只撤本 gate 与精确辅助分类增量，不改历史/source custody，不将未执行迁移记为 applied。root 同源集中复验后才可裁决关闭。
