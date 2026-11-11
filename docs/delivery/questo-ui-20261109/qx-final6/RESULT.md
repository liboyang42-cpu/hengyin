# qx-final6 · 合入 f-rehearsal（#796）、修公开目录 NPE、补齐发布 pack 契约 — RESULT（2026-11-11）

分支 `launch/questo-20261110`。开工前确认 `qx-final5/RESULT.md` 已有 `STATUS:` 行（COMPLETE），无需等待。

**没有做的事**：没有合进 master；#779 仍是草稿，没有标 ready；没有碰生产；没有改任何已有迁移，也没有新增迁移；没有改 `ci/architecture/*` 和 `.github/workflows/ci.yml`；没有运行 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`；没有提交密钥；没有 force push。

## 一、提交

| 提交 | 内容 |
|---|---|
| `1eb2ba0c` | `--no-ff` 合入 `claude/f-rehearsal-20261111`（#796）。**没有冲突**：f-rehearsal 已在 `960bb2ad` 同步过 launch，launch 之后的 `865b6992`、`ed9e3406` 不碰迁移和发布清单 |
| `4e9e418a` | 发布迁移 pack 契约补齐 10-29 之后的 68 项元数据（第三节） |
| `8dd648bd` | 修 `PublicContentCatalogServiceImpl.chapterNames` 的 NPE（第二节） |
| `ced6265f` | 发布产物按代码头 `8dd648bd` 重算 |

合并后各登记取并集：`ci/migration-dependencies.json`、迁移台账、`release-migrations.json`、`DEPLOY.md` 两侧条目都保留。f-release 生成器 `gen_release_set.py` 和 f-rehearsal 增补的预检形状重新跑过：156 项，轮次 98 / 28 / 6 / 18 / 2，`--head 8dd648bd --check` 输出「OK 156 项，产物一致」。`release-manifest.json` 由 `ci/release_manifest.py manifest --commit 8dd648bd…` 重新生成，共 641 个迁移，小程序 tree `8e3abc38…`，`app.json` blob `ee546466…`。DEPLOY 第 0 节和第 9 节写明了这次重算的 SHA。

## 二、公开目录详情 NPE（f-rehearsal 第八节第 1 条）

- 原因：`cms_topic_chapter.name` 为 NULL 时，`selectChapterNames` 只选这一列，MyBatis 对这一行返回 null map，`row.get` 抛 NPE，`/api/public-catalog/info` 返回 500。
- 修法：遇到 null 行或 name 为 NULL，就用仓库已有的「未命名章节」占位，章节数不丢。后端 `TopicSaleFinalizationServiceImpl`、`MerchantChapterApplicationServiceImpl` 和小程序章节卡都用这个口径，所以抽成常量 `UNNAMED_CHAPTER`。name 为空串的原有行为（跳过）不变。
- 测试：
  - `PublicContentCatalogServiceTest.chapterWithNullNameKeepsItsSlotWithUnnamedLabelInsteadOfNpe`：映射器返回 `[第一章, null, {chapterName:null}]`，结果是 `[第一章, 未命名章节, 未命名章节]`。
  - `ApiPublicContentCatalogControllerTest.infoReturns200WhenAChapterNameIsNull`：真实服务加映射器桩，`info("7")` 返回 200，章节名是 `[未命名章节]`。
  - 反证：把实现退回修复前，两条用例都报 `NullPointerException: Cannot invoke "java.util.Map.get(Object)" because "row" is null`。修复后通过。

## 三、发布 pack 契约补齐（f-rehearsal 第八节第 2 条 / f-release RESULT 待决第 4 条）

`ci/test_release_migration_rehearsal.py::test_pack_matches_head_release_set` 的断言**没有改动**。修复方法是补齐 pack 里缺的数据：

- 新增 `qx-final6/extend_release_pack.py`。它只读 git 对象和已提交的文件，可以重放。对 `release-closeout-20261029/release-migration-pack.json` **只追加** 10-29 之后进入发布集的 68 项。原 88 项的已审字段一个都没改，只有 `refresh_pack` 重算的 step 和 order 会变。
  - 阶段：从 f-release 的类别和阶段映射过来（必须·PRE 对应 `PRE_DEPLOY_REQUIRED`；可选·PRE 对应 `PRE_DEPLOY`；需业主授权对应 `OWNER_GATED_DATA`）。jar 之后才能执行的项，在 pack 里新增阶段 `POST_DEPLOY` 并写明原因。`execution_rounds` 校验依赖通过，三轮项数是 102 / 34 / 20。
  - 说明：取 f-release 的 note。守卫取幂等分类加重跑规则。锁风险按迁移语句归纳，生产行数标为 UNKNOWN。只读回读是每项预检 SELECT 改写成的 `SELECT (…) = N`，期望 1。
  - `expectedAtBaseline` 和 `expectedAfterApply` 取 f-rehearsal 第四节在 MySQL 8.0 上实测的「预检 前→后」。
  - 预检分类块：f-release 的 68 个 SELECT 改写成 pack 主分类的列形状（exec_order / migration_id / status / detail），追加进 `release-migration-precheck.sql`。改写后的预检仍然只有 SELECT，`test_precheck_is_select_only` 通过。
  - 两个「不执行」项按已有约定登记为 `REPLACED_UNAPPLIED`：`public_content_catalog_20261108` 由 `*_index_shape_20261109` 替代，`z_marketing_revenue_channel_reverse_kind_20261108` 由 `*_index_shape_20261109` 替代。
- `ci/release_migration_rehearsal.py`：`SINGLE_APPLY` 加入 21 个新增的「APPLIED 时必须跳过」项，加上原有 25 项，共 46 项，和 f-release 一致。`REPLACED_UNAPPLIED` 加入上面 2 项。判定逻辑没有改。
- `gen_release_set.py`：pack 现在也收录了新增项，所以生成器只把 10-29 之后新增的 68 项以外的条目当作包内项，新增项仍按 NEW_META 标注。产物没有变化。

## 四、门禁

| 门禁 | 结果 |
|---|---|
| 小程序全量单测（仓库根 `node --test chengyinhub-xcx/tests/unit/*.test.js`） | **17293 / 17293 通过**，0 失败 |
| `node tests/lint/ui-integrity.js --warn-as-error` | **UI-GATE-0:PASS** |
| `mvn -q -DskipTests package`（`LANG=C.UTF-8`） | 通过 |
| 改动类 Java 测试：`PublicContentCatalogServiceTest`、`ApiPublicContentCatalogControllerTest`，以及引用该服务的 `ReferralLandingServiceTest`、`ContentLandingMetadataServiceTest` | 23 + 9 + 20 + 17 = 69 条，0 失败 |
| `python3 -m unittest`：`ci.test_release_migration_rehearsal`（13 项，含 `test_pack_matches_head_release_set`）、`test_migration_ledger`、`test_release_manifest`、`test_migration_gates`、`test_run_migration`、`test_migration_runner_ci_contract` | 338 项，OK |
| `gen_release_set.py --head 8dd648bd --check` | OK 156 项，产物一致 |
| `ci/forbidden_hosts_gate.py` | 通过 |
| 主包体积 `mainpkg-size-report.js --json` | `mainTotal` **3,307,305 字节**，与 qx-final5 相同（约 2044.5 KiB，上限 2048 KiB）。本线没有改小程序 |

## 五、剩余风险

1. **隔离演练 workflow 还没有在 156 项上实跑过**。静态契约已经和 HEAD 一致。按 f-rehearsal 的结论，`20261101_stagger_sys_job_seconds` 的断言依赖生产的 `sys_job` 编号，在 `release_migration_rehearsal.py` 的合成基线上可能断言为假并停下。要实跑通过，得在合成行里补上照生产编号造的任务行，或者由业主决定这项可选迁移的处理方式。这不在本线范围内。
2. pack 里新增项的锁风险是按 SQL 语句归纳的，生产行数仍然 UNKNOWN。大表索引项（如 `public_content_catalog_node_topic_index_20261108`）上线前要在预检里看表体量。
3. 主包余量仍然只有约 3.5 KiB。

STATUS: COMPLETE - #796 已经 `--no-ff` 合入 launch（无冲突），发布清单、预检和 manifest 都已按 `8dd648bd` 重算（156 项、641 个迁移）；公开目录 `chapterNames` 的 NPE 已修，复现用例和 /info 返回 200 的用例都通过；pack 契约补齐了 68 项，`test_pack_matches_head_release_set` 通过，断言没有改；门禁全绿：小程序 17293/17293、UI-GATE-0:PASS、mvn package、Java 69 条、ci unittest 338 项；主包 3,307,305 字节，没有变化；隔离演练 workflow 还没有在 156 项上实跑（stagger 合成编号问题）
