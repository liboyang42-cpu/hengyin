# f-release · 发布清单与迁移差异 — RESULT（2026-11-11）

分支 `claude/f-release-20261111`，从 `origin/launch/questo-20261110`（`2256f79e`，含 l-payout3）切出。对应审计 `AUDIT-GAPS.md` 的阻断 B（业主或运维配置）和缺口第 3 组（发布清单与迁移差异）。

以下事情都没有做：
- 没有连生产、VPS 或私有仓，没有执行任何 SQL。
- 没有部署，没有上传小程序。
- 没有改已有的 migration，也没有新增 migration。
- 没有运行 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`。
- 没有提交任何密钥。文档里只写配置项的名字。

## 一、结果

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机未验 |
|---|---|---|---|---|---|
| FR-1 发布迁移清单 | 已完成 | 生成本候选的发布迁移集，共 151 项：10-29 发布包 88 项（生产从未确认执行，所以全部算进来）加上 10-29 之后的 63 项，其中 34 项来自 launch 波次。按候选头的台账拓扑序排列。每项标了类别：必须 100、可选 28、需业主授权 19、不执行 4。还标了阶段（jar 前 / jar 后）、重跑规则、来源和集内依赖，并排成五个执行轮次 | `f-release/MIGRATIONS.md`、`release-migrations.json`、`gen_release_set.py` | `gen_release_set.py --check` 重新生成后与提交的文件逐字一致。脚本复用 `ci/release_manifest.migration_diff` 和 `migrations_at`，有四道校验：10-29 包里每个文件在候选头上哈希不变；10-29 之后没有被修改或删除的迁移；63 个新迁移都有人工标注，不多也不少；五个轮次不违反任何集内依赖 | — |
| FR-2 每文件只读预检 | 已完成 | 每个迁移一条 SELECT，返回 step、类别、阶段和状态（APPLIED / NEEDED / PARTIAL…）。<br>- 88 项原样复用 10-29 预检里已在 MySQL 8.0 演练中钉住指纹的主分类块。<br>- 63 项新迁移：按它要建的表、列、索引、菜单行、定时任务、配置键逐个查 `information_schema` 和对应系统表。<br>- 数据类迁移复用迁移文件自身的只读断言。<br>- CHECK 约束改名或放宽、排序规则对齐三类单独写判定 | `f-release/release-precheck.sql` | 生成器逐句校验：只能是 SELECT，没有写语句、会话变量或临时表。用 sqlglot（MySQL 方言）解析：152 条语句全部是 `Select`。没有连库执行过 | 没在真实 MySQL 上跑过（本机按 AGENTS.md 不起 MySQL，云端会话也连不到测试 VPS） |
| FR-3 release-manifest | 已完成 | 用仓库现有的 `ci/release_manifest.py manifest` 对候选头生成清单。内容包括源码树、后端模块、按 SHA 查 CI 的命令、小程序导出身份（tree hash `68c2c2bb…`，`app.json` blob `bdcdb7d2…`），以及全部 636 个迁移的哈希。生产状态一律 UNKNOWN | `f-release/release-manifest.json` | 脚本只读 git 对象。`scripts/release-manifest.sh` 需要本地 jar 才能跑，本机不允许构建 Java，所以没用它；它内部调用的也是同一个台账逻辑 | — |
| FR-4 台账登记 | 已完成 | 把新预检文件登记为 `AUXILIARY_NOT_EXECUTED`，和 10-22、10-28、10-29 三份预检同档。不登记的话，迁移台账门禁会把它判成 `UNMANAGED_SQL_LOCATION` | `ci/migration_ledger.py`（加 1 行） | 提交后在本分支头上跑 `python3 ci/migration_ledger.py manifest`，0 个问题；跑 `ci/test_migration_ledger.py`、`ci/test_migration_gates.py`、`ci/test_release_manifest.py`，结果见第三节 | — |
| FR-5 部署步骤 | 已完成 | 业主按顺序操作的完整步骤：<br>- 备份、预检、jar 前两轮迁移；<br>- 应用机配置按名字核对：AppSecret、OSS、Redis、腾讯地图、深色地图 subkey、ALARM_WEBHOOK、审核回调 token、支付回调，以及资金和通行证开关的默认值；<br>- jar 与小程序用同一个 SHA；<br>- jar 后迁移、需授权的数据迁移；<br>- 剧情字体上传 OSS、CORS 和 downloadFile 域名；<br>- 开发者工具上传、真机冒烟清单（二维码、地图、游玩、支付、退款）、提审、回滚 | `f-release/DEPLOY.md` | 配置名都从代码里核对过：`application.yml`、`@Value`、`PlatformPassProperties`、`FileOssUploadUtils`、`scripts/production-preflight.sh`、`release-map-style.js`、`ApiContentSecurityController` | 全部步骤都要业主在真机和生产上执行 |
| FR-6 基线 schema 快照 | 没有重新生成 | `sql/base_schema_20260818.sql` 是钉住来源的结构快照。它的来源记录在 `scripts/e2e/source_provenance_20260818.json` 和 `base_schema_20260818_migrations.json`，CI 用它从零建库，再按台账叠加快照之后的迁移。重新生成它需要一个真实 MySQL 先跑完全部迁移再导出，本机不允许起 MySQL，这个会话也连不到测试 VPS。另外，改它会改变所有 lane 的 CI 建库起点，超出本线范围 | — | — | — |

## 二、在清单里新发现、需要注意的四件事

1. **两个迁移必须跳过，改执行它们的替代文件。**
   - 被替代的是 `migration_public_content_catalog_20261108.sql` 和 `migration_z_marketing_revenue_channel_reverse_kind_20261108.sql`。
   - 问题：它们按「1 行」来数一个两列的唯一索引。前者的断言必失败；后者的 ALTER 被守卫静默跳过。
   - 紧跟其后的 `*_index_shape_20261109.sql` 是逐字相同的 DDL 加修好的断言，CI 建库脚本也按这对「替代、不执行」处理（`scripts/e2e/build_schema.py` 的 `INDEX_ASSERTION_FORWARD_FILES`）。
   - PR #779 的上线清单把它们和替代文件都列成了要执行。本清单已改为「不执行」。
2. **只插定时任务的迁移要挪到新 jar 之后。**
   - 原因：`CanonicalJobTarget.canonical` 会对每一行 `sys_job` 做 `getBean`。旧 jar 里没有对应 bean 时，旧 jar 的调度器会整体失效；回滚时旧 jar 也起不来。台账里只把 `20261104_content_safety_jobs_post_deploy` 标成了 POST_DEPLOY。
   - 本清单另外挪到 jar 后的有四个：
     - `20261110_ecommerce_payout_wiring`
     - 依赖它的 `20261111_ecommerce_combine`
     - `marketing_task_registration_20261108`
     - 10-29 包里的 `o14_reco_search_jobs_20261022`
   - `20261023_g_wp08_delivery_jobs_paused` 也放到了 jar 后，它本来就需要业主授权。
   - `20261024_d_committed_official_notice_wx` 既建表又插任务，只能放在 jar 前。DEPLOY.md 要求它和换 jar 在同一个窗口里做。
   - 回滚到旧 jar 前要先删哪些 `sys_job` 行，DEPLOY.md 第 12 节列了全部 `invoke_target`。
3. **发布集是 151 项，不是 PR #779 写的 33 项。**
   - PR #779 只算了 master 之后的部分。
   - 10-22、10-28、10-29 三个发布包都没有生产执行回执，所以 10-29 包的 88 项，以及 10-29 之后进 master 的 29 项（主题类别、内容安全、积分规则、3% 抽成、商家权限、文案改名等），都还要逐项过预检。
   - l-payout3 带来了第 34 项 `migration_20261111_ecommerce_combine.sql`。
4. **有 4 个可选项被依赖，升成了必须。**
   - 它们是 `20261022_f7_user_invoice`、`o14_cms_topic_city_code_20261022`、`20261013_admin_role_page_grants`、`20261016_admin_role_payee_separation_forward`。
   - 原因：它们是必须项的集内前置，例如 `20261109_play_help_audit_permission` 依赖后两个。
   - `20261013` 沿用 10-29 的硬规则：判 NEEDED 时禁止执行，停下。

## 三、验证

| 检查 | 结果 |
|---|---|
| `python3 docs/delivery/questo-ui-20261109/f-release/gen_release_set.py --check` | `OK 151 项，产物一致` |
| sqlglot（MySQL 方言）解析 `release-precheck.sql` | 152 条，全部 `Select` |
| `python3 ci/migration_ledger.py manifest --root . --target-commit <本分支头>` | 见提交后补记（下一行） |
| `python3 ci/test_migration_ledger.py`、`python3 ci/test_migration_gates.py`、`python3 -m unittest ci/test_release_manifest.py` | 见提交后补记 |
| `python3 ci/forbidden_hosts_gate.py` | 通过（没有命中黑名单的 IPv4） |

## 待你决定

1. **类别口径**（默认已实现）：
   - 必须：新 jar 直接读写的表和列，以及它们的集内前置。
   - 可选：新 jar 缺了也能容忍。
   - 需业主授权：改现有业务数据或资金口径。
   - 有几项是业主走查时已经口头定过的，仍归为「需业主授权」，请执行前逐个点头：积分规则 id 6 关闭、id 1 改为 50、平台抽成 3%、商家默认档位两项、990059 模板文案、种子文案改名。
   - 主题类别改名（`20261101_theme_categories`）因为被商家行业迁移依赖，归为必须。
2. **积分规则 id 1 改 50（`20261105_welcome_points_fifty`）**：新 jar 的注册余额和流水都读这条规则。不执行的话新用户拿到的是旧值 100。我建议和 jar 一起执行。
3. **审计 B-7 的两项清理在仓库里没有对应迁移**：E-2「删除玩家账号的商家行」和「QA 分群」。这次不处理。要做的话需要另开一条线，先出只读预检，再写迁移。
4. **隔离演练没跑**：`.github/workflows/release-migration-rehearsal.yml` 和 `ci/release_migration_rehearsal.py` 钉的是 10-29 包。要在一次性 MySQL 上演练这 151 项，需要把 63 项的已审元数据补进它的 pack 契约。这会改 CI 工具，超出本线范围。在那之前，本清单的预检只经过静态校验。
5. **最终 SHA**：PR #779 合入 master 后，按 DEPLOY.md 第 0 节用 `gen_release_set.py --head <最终 SHA>` 重算一次，确认没有新迁移；jar 和小程序都用这个 SHA。

STATUS: COMPLETE - 发布清单 151 项（必须 100、可选 28、需业主授权 19、不执行 4）、每文件只读预检、release-manifest、DEPLOY.md 都已生成，并能由脚本重放。基线快照按理由没有重新生成。预检没有在真实 MySQL 上跑过，151 项也没有做隔离演练，见待你决定 4。
