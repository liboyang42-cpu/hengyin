# CYV P1/P2 本地施工记录

> 观察日期：`2026-09-21`
> 基线：`github/master@a7d179760308cdf2a247311b1b0679e09ed73244`
> 证据边界：本文件记录 E0～E2 与 PR/CI 进度；没有生产 SQL 写入、合并、部署、微信上传或真机验收。

## 已关闭的本地缺陷路径

- `CYV-P1-001`：客户端上传真实裁剪坐标；服务端统一校验并量化到 3 位小数，落库和匿名读回均不再信任客户端精度。历史快照迁移已在 Oracle MySQL `8.0.45` scratch 上执行两次并回读，生产未执行。
- `CYV-P1-002`：主题授权取消绑定 `topic + club + activity`，锁后读取当前俱乐部 owner；旧 owner 在转让提交后被拒绝。MySQL IT 使用测试自己创建的唯一 schema、仓库 DDL/migration 和真实事务代理，不再依赖固定 `cy_e2e_51`。
- `CYV-P1-003`：见同目录 [`CYV-P1-003执行记录.md`](CYV-P1-003执行记录.md)。
- `CYV-P2-001`：存档 key 改为 `prefab_life_v2_{activity|topic}_{id}`。历史 v1 从未保存来源类型，无法安全证明归属，因此不自动迁移，避免跨会话同步姓名、照片或进度。
- `CYV-P2-002`：`count → delete → upsert` 进入单事务 Service，以现有 `ums_member` 行串行化同一会员的配额写入。
- `CYV-P2-003`：两个被改写的历史 migration 恢复为 commit `737f0c2eab1dab2afaa51944d8654a7f803671c3` 原字节；新建第一层前向 migration 承接字段、索引、数据回填和视图变化。

## RED / GREEN / 负控

- P1-001：旧 Controller 测试证明原始精确坐标会被保存；修复后正常、旧版和恶意请求均由服务端量化，重复发布令牌与快照稳定。
- P1-002：单元 RED 复现无 series 路径使用历史创建者导致退款回执不完整；修复后 MySQL `8.0.45` 的 3 条并发 IT 通过。第二个取消请求先被观测到已进入 series 行锁，再验证首事务提交前不能完成；重复退款只创建/派发一次。
- P2-001：活动 123 与主题 123 的旧 key 可串档；新 key 重启后隔离。歧义 v1 负控返回全新状态且不生成 v2 存档。
- P2-002：临时移除 `lockMemberById` 后，`twoFirstPublishesFrom49NeverLeaveMoreThan50` 在“第二请求已穿过会员行锁”处明确失败；恢复锁后 2/2 通过。测试先观测第二线程已调用行锁，再断言未到达 count 且 Future 未完成，排除单纯调度慢的假绿。
- P2-003：历史文件当前 SHA-256 已分别回到 `3a27459a...6ee` 与 `21f49a4f...95f4`；旧改写 SHA 与 manifest `sourceSha256` 不符时 `build_schema` 单测明确红，恢复后转绿。新 migration 在 MySQL `8.0.45` 的 durable fresh/existing 形状和二次幂等重放通过；重复业务日志不再自动删除，而是在任何 DDL/DML 前失败并回读原行保留。

## 当前门禁

- `ClubEventTransferCancellationMySqlIT,RoamShareSnapshotConcurrencyMySqlIT`：`5/5`，Oracle MySQL `8.0.45`，scratch 均销毁回读。
- 并发锁点计数审查修复后，`ClubEventTransferCancellationMySqlIT` 单独复验 `3/3`；独立标准复审判定 retry 观测已绑定真实第二请求。
- 完整 `bash ci/funds-mysql-it-gate.sh`：`542/542`，`0` failures/errors/skipped；新增 P1/P2 MySQL IT 均在正式门禁中真实执行。
- `ci.test_funds_it_gate`：`36/36`；两套新 MySQL IT 已登记 `EXPECTED + SOURCE_FILES + -Dtest + workflow trigger`，并锁定独立 Oracle MySQL 8.0.45、动态端口、服务端版本回读与失败清理负控。
- `ci.test_migration_ledger`：`41/41`。
- 预制人生 Node：`11/11`；路线分享 Node：`9/9`。
- action ledger：`4254 controls`，检查通过；UI-GATE-0 与 UI validity 均通过。
- migration layout：`420` 个第一层 migration，通过；两份新 SQL 的 `run_migration.py --dry-run` 分别解析 `7`、`33` 条语句。dry-run 不冒充执行成功。
- 原子迁移 MySQL 门禁：`35/35`，`0 skipped`；其中 P2-003 durable MySQL IT `2/2`，scratch 销毁后回读为 0。
- 本地终态 contract/unit 汇总：`125/125`（Funds gate 合同、migration ledger、schema replay policy、migration runner/CI 合同）；`git diff --check` 通过。
- `scripts.e2e.test_build_schema`：`6/6`；从 source commit Git 对象独立回读 262 条 SQL，与 252 条已入快照 migration + 10 条快照缺失 migration 逐路径、逐 SHA-256 比对；25 条纯数据 migration 另行指纹化，未知/新增 migration 默认重放。base seed 和 7 份来源 SQL 也完整性锚定；指纹篡改、清单删项、非 loopback/非 scratch 目标和数据库连接前 preflight 均有负控。
- CI 接线：`scripts/e2e/**`、base snapshot 和 base seed 已进入 migration 路径触发，`test_build_schema.py` 在 `fetch-depth: 0` 的 `migration_safety` job 执行；完整接线命令 `173/173` 通过，CI cost routing contract 通过。
- rebase 到 `github/master@78ffaafc8...` 后复验：增量 migration safety 对 4 个变动 SQL 真实扫描并通过，2 个新 migration 的最小直接依赖均已显式登记；小程序完整静态门禁通过。
- 后端完整 `bash ci/backend-test.sh` 在 `127.0.0.1:3316` 的 Oracle MySQL `8.0.45` 上通过；25 套具名真库 IT 实际运行，75/75，0 skip/failure/error。本机默认 3306 为非 MySQL 8 实例，本轮不将其失败冒充业务回归。

## 生产只读前置回读

- `2026-09-21T12:37Z` 经 `ci/run_migration.py --check` 的 SSH 隧道连通 MySQL `8.0.45`；本轮只执行 `SELECT`，未执行 DDL/DML。
- `sys_role` 回读：`1/admin`、`2/common`、`100/cy_operator`、`101/cy_auditor`、`102/cy_support`、`103/cy_finance`。
- `sys_menu` 回读的最小前置：`1→2216`、`2007→2209`、`2008→2007`、`2111→2214`、`2147→2211`、`2157→2210`、`2209→0`、`2210→0`、`2211→0`、`2214→0`、`2216→0`、`2219→2214`；同时回读各自 `path/component/perms` 与 seed 合同一致。
- 冷却配置在生产已有当前值，但 base seed 刻意不复制该值：它必须由目标 migration 自己创建，否则会把 migration 遗漏写成假绿。

## 未关闭项

- 完整 `build_schema.py` 在 Oracle MySQL `8.0.45` 上重放 144 条，得到 361 表；baseline/seed/migration 问题均为 0，数据观察为 0，8/8 角色菜单授权回读通过。seed 二次重放和 6 类破坏前置负控均能转红，scratch 销毁后回读为 0。E2E schema 本地门禁已为 `PASS`，不代表生产迁移通过。
- P1-001/P2-003 的生产只读连通与前置角色/菜单事实回读已完成；未执行任何生产写入/迁移 SQL，E4 执行仍是 `BLOCKED_AUTH`。
- 已 push 并创建 [PR #1134](https://github.com/liboyang42-cpu/chengyin/pull/1134)；前一 head 暴露 gitleaks 测试串误报及 runner 3306 版本漂移，均已根因修复，最终 clean head required checks 尚在回读，E3 仍为 `RUNNING`。
- 小程序未上传，真实账号与真机未验收，E6/E7 未执行。
