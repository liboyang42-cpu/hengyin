# qx-final4 结果：l-icons、l-iconsB 合入 launch/questo-20261110

分支 `launch/questo-20261110`（PR #779，草稿，未合并、未标 ready）。本线按 LAUNCH.md / BRIEF.md 执行：等 qx-final3、l-icons、l-iconsB 三份 RESULT.md 都出 `STATUS:` 后，依次把 l-icons、l-iconsB 以 `--no-ff` 合入 launch，跑全部门禁，推送后盯 #779 的 CI。

## 一、等待记录

| 前置 | 分支 | 出 STATUS 的时间（UTC） | 内容 |
|---|---|---|---|
| qx-final3 | `launch/questo-20261110` | 2026-11-11 约 15:26（轮询 15:35 看到） | `STATUS: PARTIAL`（门禁全绿，CI 只剩架构门禁与 runner 解析不了 Ubuntu 源） |
| l-icons | `claude/l-icons-20261111` | 约 12:05 | `STATUS: COMPLETE` |
| l-iconsB | `claude/l-iconsB-20261111` | 约 11:20 | `STATUS: COMPLETE` |

轮询每 15 分钟一次，从 10:35 开始，约 5 小时后三项齐备，没有用到 24 小时上限。

## 二、合并记录

| 顺序 | 来源 | 来源提交 | 合并结果 | 冲突与处理 |
|---|---|---|---|---|
| 1 | l-icons | `1f3956a6` | **已在 launch 中**（`11c3fb69`，qx-final3 期间另一个代理已合入），本线没有新的合并提交 | — |
| 2 | l-iconsB | `fb9f1c28` 时合入至 `41da6575` 等；本线补合最新的 `19c8b33e` | `5f5084b9`（`--no-ff`，合并树与 launch 一致，零内容变化，只把 l-iconsB 最后一次「同步 launch」的提交记入历史） | 无冲突 |

**已有合并（`fb9f1c28`）的冲突处理，我逐项核过，与两条 lane 的要求一致：**
- 通行证页 `subpackageMember/market/membership/*`：页面结构取 l-iconsB 的 ps-pass（实色卡、列表行、底部固定药丸）；卡里的钥匙换成 l-icons 的 `glass-key` 3D 玻璃序列帧（l-iconsB RESULT「跨 lane」的建议）；开通成功取 l-icons 的钥匙开场（`subpackagePass/reveal`），旧 `.ok` 层与样式删除。
- `glass-pass.webp` 在 ps-pass 里没有调用者，已删除；l-icons 与 l-iconsB 两份单测已按合并结果改钉。
- `cy-illus`：B 风格插图数据与样式（`art-b.js`、`index.wxss` 的阶段类，四份分包副本）归 l-iconsB；序列帧播放器 `cy-frames`、圆点章节号与章节印（`chapter-flip`）、钥匙开场归 l-icons。两边文件不重叠，没有互相覆盖。
- 动作台账在合并树重建，计数断言按实测更新；没有新增任何 UI-integrity 基线条目。

我在合并前另用临时工作树独立做了一遍同样的冲突解法（l-iconsB 结构 + glass-key 进 `.pp__key` + 删 `.ok` + 删 glass-pass），结果与 `fb9f1c28` 一致，三份相关单测 82 条通过，只剩台账两条计数要按实测更新，与已有合并提交的处理相同。

## 三、本线额外修复（让 #779 的 CI 变绿）

| 提交 | 问题 | 处理 | 验证 |
|---|---|---|---|
| `865b6992` | 真库 IT `FreeExploreSelfPlayPriceGateMySqlIT`（launch-f-money3 批次，f-money3 新增）在 CI 报 `Boolean cannot be cast to Number`：`cms_topic.lifecycle` 是 `TINYINT(1)`，MySQL 驱动按 Boolean 返回 | 只改测试：断言改用 `select cast(lifecycle as signed)` 读整数，断言值不变 | `mvn -q -pl chengyinhub-admin -am -DskipTests test-compile` 通过；后续 Backend tests 里 469 个 MyBatis/MySQL IT 全过（该类不在此 job 中，靠 real-storage-it 批次复跑） |
| `60c43860` | Migration safety gate：`ci/test_prelaunch_business_seeds.py` 报 `scripts/e2e/seed_fixtures.py` 哈希不符（f-money2 / f-play2 改了 e2e 夹具：俱乐部 owner 角色、`cms_setting` 与活动结算费率行，不涉及授权） | `ci/prelaunch-seed-provenance.json` 里该文件的哈希更新为当前值 | 本地 `python3 -m unittest ci/test_shallow_source_custody.py ci/test_candidate_resource_manifest.py ci/test_prelaunch_business_seeds.py ci/test_release_manifest.py`：43 条 OK；CI 复跑 Migration safety gate 变绿 |

没有改 `ci/architecture/*`，没有改已有迁移，没有加 UI-integrity 基线。

## 四、门禁（合并树 `5f5084b9`，本地）

| 门禁 | 结果 |
|---|---|
| 小程序全量单测（仓库根 `node --test chengyinhub-xcx/tests/unit/*.test.js`） | **17288 个测试，17288 通过，0 失败** |
| `node chengyinhub-xcx/tests/lint/ui-integrity.js` | **UI-GATE-0:PASS**（A11Y PASS、Legacy error 0 / warn 0；没有新增基线） |
| 动作台账 `build-action-ledger.js --write` | 6022 个控件，重建后 **无 diff** |
| 路由矩阵 `build-route-matrix.js --check` | PASS，171 条路由 / 27 个场景，**无 diff** |
| 危险操作确认门 `npm run lint:danger-confirm` | DANGER-GATE PASS：53 个危险调用点已登记，38 个不可逆动作都有确认弹窗并写明不可撤销 |
| 危险操作确认门自证 `npm run lint:danger-confirm:selftest` | DANGER-GATE 自证通过 |
| 主包体积 `scripts/mainpkg-size-report.js --json` | `mainTotal` **3,307,305 字节 × 0.633 ≈ 2044.5 KiB < 2048 KiB**（余量约 3.5 KiB） |
| keyframes 棘轮 `tests/unit/motion-property-ratchet.test.js` | **9/9 通过**（上限 146 未动） |
| `mvn -q -DskipTests package`（`LANG=C.UTF-8`） | **通过**（退出码 0） |

没有运行 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`（按规定）。

## 五、PR #779 CI（头 `538a47ab`，含其他代理在本线之后推送的 qx-final5 / qx-final6 提交）

| Job | 结果 | 说明 |
|---|---|---|
| Detect changed areas | ✅ | |
| Secret scan (gitleaks) | ✅ | |
| Mini program unit shard 1–20/20 | ✅ 全部 20 片 | |
| Admin UI build gate | ✅ | |
| Admin UI E2E contract gate | ✅ | |
| Migration layout gate | ✅ | |
| Migration safety gate | ✅ | 本线 `60c43860` 修后变绿 |
| SBOM + dependency scan | ✅ | |
| Funds IT gate self-test | ✅ | |
| Funds MySQL IT (50/9/30, skipped=0, fail-closed) | ✅ | |
| Followup 39 selected backend cases | ✅ | |
| isolated-authenticated-redis-recovery | ✅ | |
| real-storage-it 批次（EcomPayoutMapperMySqlIT、EcomPayoutWiringMySqlIT、P0* 6 个等） | ✅ | `FreeExploreSelfPlayPriceGateMySqlIT` 本线已修，等批次在新头复跑 |
| **Backend tests** | ❌（架构项） | 单测 18759 条（0 失败，672 跳过）、MyBatis/MySQL IT 469 条（0 失败）全过；末步 compiled-ports 只报 1 条 `EXACT_STORAGE_PORT_CONSUMER`：`ApiTopicTemplateController → AuthorWriteReceiptService` |
| **Fast gates (CWD / API existence / silentError)** | ❌（架构项） | 我在本地用受信 master 的 `ci/architecture/gate.py` 模拟 pull_request 事件复跑，findings 正好 9 条：StoreKit `DEPENDENCY_EXPANSION`（`chengyinhub-system/pom.xml`）+ 8 条 `NEW_UNOWNED_BOUNDARY`（`ApiMerchantPayoutAccountController`、`ApiPlatformPassController`、`ApiPlayFeedbackController`、`EcomCombineMapper`、`EcomPayoutMapper`、`PlayHelpAuditMapper`、`PlayRunFeedbackMapper`、`PlayRunFeedbackService`），没有新增项 |
| Mini program static gates | ✅ | `844638cf7` 上曾因 runner 解析不了 Ubuntu 源（`apt-get install ripgrep` 失败）而红；runner 恢复后在 `538a47ab` 上通过 |
| **rehearsal**（release-migration-rehearsal） | ❌ | `[1] 模拟基线 … FAIL: 基线库上预检不符合 pack 的 expectedAtBaseline`（`844638cf7`、`538a47ab` 两次相同）。workflow 是 qx-final6 / f-rehearsal 在本线之后合入的；qx-final6 RESULT 剩余风险 1 已写明「隔离演练还没有在 156 项上实跑」，原因是 `20261101_stagger_sys_job_seconds` 的断言依赖生产 `sys_job` 编号，合成基线没有这些行，要补合成行或由业主定这项可选迁移。复现与验证都要起 MySQL 演练库，本线不能本地起 Docker，所以没改 |

以下由另一份 master 受信策略 PR 处理，本线没有改 `ci/architecture/*`：
1. StoreKit `DEPENDENCY_EXPANSION`（`chengyinhub-system/pom.xml`）
2. 8 条 `NEW_UNOWNED_BOUNDARY`（见上表）
3. `EXACT_STORAGE_PORT_CONSUMER`：`ApiTopicTemplateController → AuthorWriteReceiptService`

## 六、剩余风险

1. **主包只剩约 3.5 KiB 余量**（2044.5 / 2048 KiB）。l-icons 的新素材都在分包（`pages/play`、`subpackageMember`、`subpackagePass`），本线没有增加主包体积；下一处改主包的需求很可能超限。真实压缩体积要用开发者工具上传后的「代码质量」或 CLI `--info-output` 核对。
2. **launch 分支上同时有多个代理在推送**（qx-final5、qx-final6 在本线推送之后又合入了 f-play2 / f-rehearsal）。每次推送前我都先同步、不强推，但 #779 的最终 CI 状态取决于最后一次推送。
3. **rehearsal 红**：发布迁移 pack 的基线期望与演练库不一致，属于 f-rehearsal / qx-final6，见上表。
4. runner 解析不了 Ubuntu 源的问题（14:04Z 起）已恢复，小程序静态门在 `538a47ab` 上已通过。
5. l-icons 待你决定 3 条、l-iconsB 待你决定 5 条，原样保留在各自 RESULT.md（例如 `glass-key.webp` 55 KB 略超 50 KB 目标、钥匙开场分包首次下载 1.7 MB 是否加 `preloadRule`、v2 未重画的 15 枚旧插图与新插图同屏、失败插图的「落地抖一下」）。

## 七、需要真机验的

- 通行证页：ps-pass 实色卡里的 3D 玻璃钥匙序列帧（WebP 解码、切帧是否掉帧）、底部固定药丸与安全区间距；商家账号进入只看到「商家账号没有通行证」。
- 开通成功跳钥匙开场（`subpackagePass/reveal`）：首次进入完整版 83 帧 @18fps 的内存与解码、首帧前的加载空白、「跳过」、减少动态效果直接终态。
- 章节开场 / 完成屏的圆点章节号与章节印（canvas 帧率、矮屏 ≤667pt 的缩放）。
- 奖励时刻的勋章 / 成功玻璃序列帧（本局完成）。
- cy-illus B 风格插图：data URI SVG 的 `rgba()` 描边与 `clip-path="inset()"` 在 iOS / 安卓上的渲染，以及进场 / 待机过渡的帧率。
- 星尘主题内卡片 / 弹窗的 `backdrop-filter` 性能。

## 八、待你决定

1. **rehearsal 怎么收口**：默认留给 f-rehearsal / qx-final6（合成基线补照生产编号造的 `sys_job` 行，或由你决定 `20261101_stagger_sys_job_seconds` 这项可选迁移是否进发布集），本线不改。
2. 架构门禁三类 10 条：在 master 受信策略里登记（另一份 PR）。

STATUS: PARTIAL - l-icons、l-iconsB 已全部在 launch（l-iconsB 最后一次同步以 5f5084b9 补记），本地门禁全过（小程序 17288/17288、UI-GATE-0、台账与路由矩阵零差异、危险操作门与自证、主包 2044.5 KiB、keyframes 9/9、mvn package）；修了两处 CI 红（f-money3 真库 IT 的 TINYINT 读取、e2e 夹具来源哈希），小程序静态门已恢复变绿。#779 CI 除架构门禁项外还剩 rehearsal 红（qx-final6 已知的发布迁移隔离演练合成基线问题，需起 MySQL 演练库）
