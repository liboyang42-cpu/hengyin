# 迁移门禁与唯一 notice forward plan 接缝

代码候选已实现；直接 Python 控制通过，不代表 Java/数据库/迁移/HTTP/最终 CI 验收。

- parent/public source：`6fcd5ac009cec1dd82c438528d24da30c1f302f4`。
- 隔离分支：`codex/architecture-day-g-forward-plan-gate`。
- 原失败：协调方提供 `40162302151 / 121076298259` Migration safety；本地原样复现同一 `SharedLedgerExecutionOrder.test_actual_schema_runner_uses_ledger_order_and_mutations_are_rejected`，具体输出见 `before.log`。
- 源码仅修改 `ci/migration_gates.py` 中的该 checker 与 `ci/test_migration_gates.py` 的同一直接控制类。runner/旧 SQL/替代文件 hash/DAG/fresh 准入/实际资金源均未修改。

根因是原 checker 只允许一次 ordered 赋值和原循环。实际已批准 runner 保留原 selection，并在验证精确 D/oldC/v2 pair、确认不存在且成功新建 scratch 后，做唯一二次 ordered 赋值；v2 走原 run_file 且强制失败停止。旧 checker 对此合法 AST 误拒绝。

本片仍精确验证六个 selection 赋值及其顺序、唯一 fresh preparation try/finally、唯一执行循环、path/migrationId/结果赋值、v2 与其他项 keep-going/stop 条件和两个原 scratch 连接。只认可已批准 6fcd selector/依赖与 fingerprint 校验、loopback/scratch 保护的规范 AST 指纹；不是从被检查 runner 动态推导白名单，也未复制另一套迁移依赖或 SQL 文件 hash 算法。不导入/执行受检 runner。仅注释/布局变化仍可通过，未知 forward plan 源码需明确评审，不能沿合法 call site 借证。

保留原 16 个 source mutation 的拒绝断言，将已经不匹配实际新循环的字符串迁到真实等价变体；每项都 assert 原片段存在、变异确实改变源且 Python 可解析，再要求 checker 拒绝。新增 26 个身份/依赖/指纹/伪替代/foreign target 控制和 29 个 fresh/strict execution 控制，共 **71 个源码变异负控**（subTest，不冒充 71 个 unittest case）。另保留该类的 manifest 缺边/逆序/缺路径/重复/依赖不存在/环控制，新增纯格式正控。

执行：Python 3.12.14，`python3 ci/test_migration_gates.py SharedLedgerExecutionOrder -v`，**7 tests / OK / 4.362s**，完整输出见 `after.log`。其中包含原失败用例的修后复验。原失败输出保留，未跳过测试或删除原断言。`git diff --check` 通过，源码 hash 见 `SOURCE_SHA256SUMS`。

NOT_RUN：完整 migration-safety 套件、CI、Java、MySQL、Redis、HTTP、provider。没有触发 CI，没有执行 DDL。root 唯一集成后安排原目标验证。冻结 G7e4 资金增量未重做。
