# F 联合候选迁移接线维护

结论：已修复 A/D 两份新增、未执行候选迁移的目录与调用绑定、精确依赖/资源分类及旧权限来源 hash。SQL 内容字节原样保留，因此仍有两项 SQL 安全合同阻塞；没有放松门禁，不能宣称 migration safety 或联合候选验收通过。

## 基线与范围

独立工作树 `/workspace/hengyin-f-migration-layout`，分支 `codex/architecture-day-f-migration-layout`，代码父候选为公共联合 `089790aaf3254f62ebbc79394b8aaf5fa66cb2b5`。冻结 527/manual-base/WP01 和根联合候选未重写。根 ci.yml/report、POM、架构 gate/baseline、真库 harness/registry、C caller/UI 均未修改。

只修改两份候选迁移的位置，三份 Java IT 的资源路径、A/D 的必要报告/静态引用和 custody 条目、ci/migration-dependencies.json 的两条追加、ci/migration_ledger.py 的四个逐文件辅助分类及其正反自测、ci/prelaunch-seed-provenance.json 的两个已审来源 hash。详细路径以 Git diff 为准，历史 migration 正文未修改或删除；没有 DDL/数据库/生产访问。

## 原因与修复合同

1. 布局门实际复现两个非法目录；release manifest 只发现 chengyinhub-admin/sql 第一层。将下列文件原字节移动到该目录，均不在公共 2ec 基线中，是任务明确标识的新增、未执行候选，不是历史已执行迁移：

| 原位置 | 规范位置 | SHA256（移动前后相同） |
| --- | --- | --- |
| sql/migration_20261023_cash_withdraw_fund_fact.sql | chengyinhub-admin/sql/migration_20261023_cash_withdraw_fund_fact.sql | cf981d89214b219c41639537f301f2b55b2b568f2d310c06621e6df473b3f4ba |
| docs/delivery/architecture-day/D/sql/migration_20261023_d_official_broadcast_delivery.sql | chengyinhub-admin/sql/migration_20261023_d_official_broadcast_delivery.sql | 83c7fc4aa90a68a782b931c0736f43db90aaca3bed9c75c4b9f09222b474554c |

2. 更新 OWN_C04_21MySqlIT、CashWithdrawFundEvidenceMySqlIT、OfficialBroadcastDeliveryMySqlIT 真实加载路径，各仅改资源字符串。A/D custody 只刷新这些受影响记录及迁移路径；原 A static-check-output.txt 是历史真实输出，保留原样，不伪造重跑证据。A validate-static.py 仍保留独占任务边界检查，不因联合候选修改 B/CI 而扩大其白名单，也不把它当联合树的全局验收工具。
3. 依据真实 CREATE TABLE/FK/ALTER/UPDATE 引用，只追加 A→member_cash_withdraw、D→official_broadcast。所有旧依赖逐项保持，未用空数组跳过依赖。
4. 核查真实 Java 测试使用两份一次性 fixture，以及 A 的审阅 schema 副本（与规范候选逐字相同）和只读 preflight（SELECT）。对四个确切路径登记 TEST_FIXTURE/AUXILIARY_NOT_EXECUTED，不进可执行迁移；新增邻近文件仍报 UNMANAGED_SQL_LOCATION，正反自测实际通过。规范迁移仍经过全部 SQL 校验，没有目录通配例外。
5. CashWithdrawAdminController 相对公共 2ec 仅注释与捕获调查异常后返回错误；全部路由/PreAuthorize 保留，操作者和服务调用参数不变。BusinessOfficialEventController 还存在同类旧 hash，其差异只有返回文案“已发送”改“已提交”、移除误导人数，路由/权限及发布分支不变。只更新这两个 sourceFiles hash，未修改控制器、菜单、角色、权限或其他来源条目；现有真实来源/权限测试已通过。

## 实际验证

- before-layout.txt / before-seeds.txt：原失败，分别指出两个目录及旧 controller hash/非法 SQL 库存。
- permissions.txt：现有 test_source_hashes_and_real_controller_permissions，1 项通过；CI 成本合同通过。
- static-gates.txt：执行 `python3 -m unittest ci.test_migration_gates ci.test_migration_ledger ci.test_shallow_source_custody ci.test_candidate_resource_manifest ci.test_prelaunch_business_seeds ci.test_release_manifest`，共 224 项，222 通过；2 项因下面相同的 SQL 库存阻塞失败/报错，没有跳过或改断言。
- sql-contract.txt：既有 check_sql_text 对两份规范迁移仍明确拒绝；review.json：两份迁移字节身份、旧依赖保留、A/D custody、共享文件冻结及剩余库存问题精确回读。
- 在代码提交 `f4c5b18db69e8bc55ba96e4a9202aa9f4a82b808` 实际执行 `bash ci/migration-layout-check.sh`：exit 0，519 个规范目录迁移，增量前缀通过。`bash ci/migration-safety-check.sh`：exit 1；其 130 项分句/迁移门禁及 52 项 ledger 自测均通过，SQL/manifest 仍拒绝这两份原字节迁移。任务注册检查通过，依赖声明棘轮只增未减；非 PR 上下文的标题/描述检查按既有逻辑未执行。原始输出 layout-full.txt / safety-full.txt，退出码 full-gate-status.json。Python/Git 静态检查不是 Java/MySQL 验证。

## 仍阻塞、后续重放与回退

A 的两个单语句 CREATE TRIGGER 以普通分号结束，当前受控门禁要求 routine 使用自定义 DELIMITER；D 的两条全表 UPDATE 没有 WHERE/可执行范围 guard。用户本次明确要求 SQL bytes 不改，F 未改 SQL、分句器或范围门禁。需 A/D 在各自候选修订实际 SQL 合同；A 还须核对其 Java SQL 加载器能否正确处理最终 delimiter。D 的历史回填范围应由 owner 明确，不能追加 WHERE 1=1 或放宽门禁冒充解决。

对应两项残留失败是 test_predeploy_snapshot_is_static_and_never_claims_historical_git_verification 与 test_task_dictionary_forward_is_selected_by_supported_fresh_builder，共因是 UNSUPPORTED_SQL_SHAPE。修复目录不会证明可安全执行；不得把两个源级权限检查通过或总测试计数当成迁移绿。

本批未执行 Java 编译/测试、MySQL/Redis、DDL/迁移演练、Docker、SSH/VPS、workflow dispatch 或部署。沿用已批准的隔离候选提交例外；静态安全尚未通过，不得正式集成/合并/部署。

A/D 后续候选若继续使用旧路径，由根在组装时重放相同移动及三个 Java 路径替换，再按最终受影响字节更新 A/D custody；不得覆盖新版业务/IT 逻辑。SQL 内容若由 owner 改变，需重新跑 layout/safety、库存、来源及实际 MySQL 测试。回退只限本维护差异；恢复旧路径会恢复已知漏收，不应用于交付，不回滚其他业务、记录或历史迁移。迁移落库授权/窗口/只读预检及恢复方案仍须独立满足，本批不授予执行权限。
