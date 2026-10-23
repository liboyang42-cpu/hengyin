# 发布收尾 batch3 集成候选记录（2026-10-22）

- 集成分支：`integrate/closeout-batch3-20261022`，对应 draft PR #512，只供人工审阅后合并。
- 基线：#486（batch2）在本批开始时**尚未合入 master**，所以本分支从 `integrate/closeout-batch2-20261022` 的 head `94e19fd1` 切出，PR base 指向该分支。#486 合入 master 后，PR base 需要改指 `master`。
- 范围：
  - 不部署、不连生产、不执行任何 DB 变更。
  - 没有合并或关闭任何 PR，没有推 master，没有删分支。
  - 没有运行 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`，只单独跑了子门禁和 Node 单测。

## 1 合入顺序

全部用 `git merge --no-ff <PR head>`。顺序是：先后端，再测试，再小程序，CI 和文档放最后。没有任何 lane 在自己的 head 上改 `scripts/uiaudit/action-ledger.json` 或 `route-matrix.json`，所以不需要丢弃 lane 的改动。台账最后在合并树上统一再生成一次（见第 3 节）。

| 顺序 | PR | Lane | head | 合并提交 | 说明 |
|---|---|---|---|---|---|
| 1 | #485 | U1 | `47c441f2` | `371fc3da` | `TemplatePublishServiceImpl` 据点模板 duration 丢失修复，附 lane-G 未决项核实 |
| 2 | #503 | TF1 | `4cb7ba78` | `75da370f` | 夹具加固：团核销台账对齐生产唯一键；城市跑服务窗口改用 JVM 时钟 |
| 3 | #497 | AC1 | `945eca72` | `5a999b59` | P0 验收真库 IT 5 类 13 条，新增 real-storage 批次 `p0-acceptance-ac1` |
| 4 | #499 | AC2 | `eff2c5fb` | `ae874308` | P1 验收真库 IT（36 卡） |
| 5 | #500 | AC3 | `3858e898` | `97d5f5a5` | W0/P2 补测；顺带修复 `subscribe-recipient-contract` 与 `roam-comment-scan` 临时目录的同分片竞态（即 batch2 记录里 #484 shard 13 的那个问题） |
| 6 | #501 | L21 | `1f7df6de` | `0e8be99f` | #21 遗留：fabu 防连点 / BL-185 / BL-186 / B9 |
| 7 | #493 | R1 | `5262d362` | `6915f48b` | 发布清单生成器 `ci/release_manifest.py`、迁移台账与发布步骤 |
| 8 | #494 | O16 | `e06b7739` | `645274ee` | 后台 UI 的 npm 下载缓存，以及 CI 提速测量文档 |
| 9 | #505 | O16b | `45df470a` | `f920ac68` | xcx 路径过滤改为显式正向清单 |
| 10 | #483 | D2S | `1cceb200` | `d7f68b58` | 实施文档二现状盘点（纯文档） |
| 11 | #506 | RV1 | `f4f2c08e` | `33a22f26` | batch2 复审报告（纯文档） |
| 12 | #510 | RV2 | `cf19afe8` | `df8b8799` | batch2 RV2 复审（纯文档） |
| 13 | #507 | MR1 | `2c43e0a4` | `3c3e2d7c` | 人工验收 Runbook（纯文档） |
| 14 | #510 | RV2 | `b9663208` | 见 git log | 集成过程中 #510 有新 head（只多一行，标注 N7 已解决），追加合入 |

### 纳入依据（各 PR 自身 CI）

- 全绿：#503（run 39784352969）、#497（39783367439）、#499（39784421824）、#500（39783981613）、#505（39785053679）、#485、#493、#506（39786885023）、#507（39787705047）、#510（`cf19afe8`：39798545782）。
- #501（39783845843）：唯一的红是 `action-ledger.test.js`「release 前重建 inventory」，原因是台账漂移，属于待落地的 CY-HANDOFF。在本合并树上再生成后，这一项已转绿（第 6 节）。
- #494、#483：只改 CI 缓存配置和文档，集成后的 CI 结果以本 PR 为准。

### 排除

无。

### 需要人工在合并前注意（来自各 PR 自述，集成没有改变它们的结论）

- #501 B9：确认退款时，在同一手势内申请 `refund` 和 `refundRejected` 两个订阅。lane-H2/H5 之前把它标成 NEEDS_OWNER_APPROVAL。B9 单独放在提交 `a5f43297`，owner 不同意可以整体 revert。订阅弹窗还需要真机验证。
- #501 BL-185：发布前试检查拿不到结果时放行，由 `publish-draft` 在服务端照样拦截。为此 `ci/silent-error-allowlist.txt` 给 `pages/publish/fabu/index.js` 新增 1 条登记（附理由）。这是 lane 代码自带的改动，不是保留文件交接；silentError 门禁在合并树上 PASS。
- #500 AC3 记录了 BUG-AC3-1：`certImages` 没有服务端媒体校验。#499 AC2 记录了 BUG-AC2-01：撤回位置同意后，到访头像仍会露出。两者都只是记录，没有修复，需要 owner 排期。
- #505：今后新增的小程序测试如果读取过滤清单以外的仓外路径，必须同步更新 filter 和 `XCX_READ_SET`，否则会出现假绿。

## 2 冲突处理

- `ci/backend-test.sh` 的 `MYBATIS_ITS` 列表冲突了两次：
  - #497 与 batch2 的 W10b `LaneW10bReadbackMySqlIT` 冲突；
  - #499 与 #497 冲突。
  - 三方都只是在列表末尾追加 IT 类名，按并集保留全部条目和注释，`bash -n` 通过。
- `chengyinhub-xcx/tests/unit/get-auth-matrix-contract.test.js`：#501 自带 2 条 MATRIX 行，directCount 65 → 66，wrapperCount 29 → 30。batch2 已经把 directCount 改到 70（W10b +4、W9 +1）。集成时 MATRIX 两边全部保留，directCount 叠加为 **70 → 71**，并补一行注释说明基数；wrapperCount 29 → 30 按 #501 原样。该测试 29/29 通过。
- `GameSessionLedgerMySqlFixture.java`：#503 改了 `station()` 的时钟，#499 加了 `impl` 字段，把 `statement()` 放宽为包内可见。两处不重叠，自动合并。
- `.github/workflows/ci.yml`：#493、#494、#505 三处改动不重叠，自动合并（#493 在 dependency 过滤和候选物 unittest 里加入 `release_manifest`；#494 给 admin_ui 和 admin_ui_contract 加 npm cache；#505 改 xcx 过滤）。

## 3 再生成（在合并树上各做一次）

| 产物 | 生成器 | 旧 → 新 | 说明 |
|---|---|---|---|
| `scripts/uiaudit/action-ledger.json` | `build-action-ledger.js --write` | 控件 4958 → **4958** | 新增和删除的控件都是 0。按 id 计 +129 / −129，全部是 `play/index.wxml` 插入 5 行造成的行号重键；内容变化 86 条（L21 交接块按 master 基线自报 84 条，多出的 2 条来自 batch2 合并树上的差异）。提交于 `36ec0d6d` |
| `tests/unit/action-ledger.test.js` local tap 分母 | 重建实测 | 2114 → **2111**；local 事件合同 2467 不变；controlCount 4958 不变 | L21 B9 让订单详情 `cancelRegistration` ×1 和订单历史 `cancelOrder` ×2 带上 `requestSubscribeMessage`，生成器把这 3 个控件归为 external（real-device-required），因此移出 local 分母。这是如实分类，不是放宽。L21 分支没有提交重建结果，所以它自己的 CI 只暴露了「台账过期」一项，看不到这条 |
| `scripts/uiaudit/route-matrix.json` | `build-route-matrix.js --check` | 145 路由 / 27 场景，无差异 | 不需要写回 |
| `tests/lint/ui-integrity-baseline.js` | 未改 | 不变 | UI-GATE-0 直接 PASS：没有过期条目需要删除，不需要重锚，冻结值没有上调。L21 改动时有意没有挪动基线里按行号登记的行 |

## 4 落地的 CY-HANDOFF

owner 已于 2026-10-22 批准由集成方接管保留文件维护。本批只有 #501 带 `<!-- CY-HANDOFF -->` 块（在 `lane-l21.md` 文末）：

| 来源 | 文件 | 落地内容 |
|---|---|---|
| #501 L21 | `scripts/uiaudit/action-ledger.json` | 「在合并树上 `npm run audit:actions:update` 后审阅」：已用同一生成器 `build-action-ledger.js --write` 落地。结果和交接块自报一致：controls 4947 → 4947（基于 master），新增 0，行号重键 ±129。本合并树上为 4958 → 4958 |

- 由交接引出的计数：`action-ledger.test.js` 的 local tap 2114 → 2111，见第 3 节，附 L21 B9 注释。
- #501 对 `get-auth-matrix-contract.test.js` 的改动不在交接块里，而是 lane 直接提交的；按第 2 节的方式叠加。
- 其余 12 个 PR 都没有交接块。
- 被拒绝的交接行：无。上面这一条只是重建台账，没有放宽任何门禁。

## 5 #505 CI 路径过滤核对

- `python3 ci/test_ci_cost_contract.py` 在合并树上运行：`CI cost routing contract: PASS`。#493 在 `dependency` 过滤里加了 2 条路径，和 #505 改的 `xcx` 过滤互不影响。
- 必需 check 名：#505 没有改任何 job 名或 `if:` 条件。本 PR 改了 `.github/**`、`ci/**`、后端和小程序，按新清单 xcx、backend、ui、dependency、migration 都会命中，所以所有必需 check 名都会出现在本 PR 上，以第 7 节的 CI 为准。

## 6 本地验证（合并树）

- `TZ=Asia/Shanghai node --test "tests/unit/**/*.test.js"`：重建台账后跑 13717 条，13716 条通过，唯一失败是 local tap 2111≠2114（第 3 节）。按重建实测改为 2111 后，`action-ledger.test.js` 46/46 通过，即全量 13717/13717 通过
- `build-action-ledger.js --check` / `--selftest`：PASS（4958）
- `build-route-matrix.js --check`：PASS（145 / 27）
- `ui-integrity.js --warn-as-error`：UI-GATE-0 PASS
- `ci/silent-error-gate.js`：PASS；`ci/api-existence-gate.js`：rc=0（A=0，C=1 处未判定，与基线相同口径）
- `ci/test_ci_cost_contract.py`、`ci/test_release_manifest.py`、`ci/test_real_storage_it.py`：PASS / OK

## 7 CI

PR base 不是 master，`ci.yml` 的 `pull_request` 触发器不会派发，所以 GitHub CI 和 Funds MySQL IT 都是手动派发（workflow_dispatch）。Mini followup 和 Redis recovery 由 `pull_request` 自动触发。

| head | workflow | run | 结果 |
|---|---|---|---|
| `36ec0d6d` | GitHub CI | 39805162954 | 手动取消：本地全量单测发现 local tap 分母需要校准，修正后在新 head 上重新派发 |
| `36ec0d6d` | Funds MySQL IT | 39805172323 | success |
| `36ec0d6d` | Mini followup focused regression | 39805152975 | success |
| `36ec0d6d` | Isolated authenticated Redis recovery | 39805153182 | success |
| `5016e184` | GitHub CI | **39806526890** | **success**：31 个 job 全部 success。包括 Detect changed areas、Fast gates、Secret scan、Backend tests、Migration layout gate、Migration safety gate、SBOM + dependency scan、Admin UI build gate、Admin UI E2E contract gate、Mini program static gates、20 个 Mini program unit shard、Mini program static checks |
| `5016e184` | Funds MySQL IT | **39806531604** | **success** |
| `5016e184` | Mini followup focused regression | **39806524530** | **success** |
| `5016e184` | Isolated authenticated Redis recovery | **39806524581** | **success** |

- 必需 check 名核对：#505 合入后，GitHub CI 的全部 job 名都在 39806526890 中出现并通过，与 batch2 的 check 集合相同；batch2 有 3 个按变更区域跳过，本批这些 job 也都实际运行了。
- 说明：由于是 workflow_dispatch，本 PR 无法实测 #505 新 filter 在 `pull_request` 事件下的路由结果。这一点由合同测试 `ci/test_ci_cost_contract.py`（PASS）覆盖。合入 master 后，第一个 PR 应复核一次 Detect changed areas 的输出。
- 之后只追加本记录文件的提交（纯文档），最终 head 以 PR #512 的检查为准。

## 8 master refresh（#486 合入后，2026-10-22）

#486（batch2）已于 2026-10-22T17:45Z 合入 master（`38dc1bb7`，其中含 #511 店铺参谋改版）。本分支随即 `git merge --no-ff origin/master`，合并提交 `90bffc43`；PR #512 的 base 已从 `integrate/closeout-batch2-20261022` 改为 `master`，`ci.yml` 的 `pull_request` 触发器从此生效。

### 冲突

- 只有 `chengyinhub-xcx/tests/unit/action-ledger.test.js` 的 local tap 分母一处冲突：
  - master 侧：batch2 再并 #511（+7 tap），2114 → 2121；
  - 本分支：L21 B9 3 个控件归 external，2114 → 2111；
  - 叠加为 **2121 − 3 = 2118**，与合并树重建实测一致。两侧注释全部保留，另补一行说明。
- 其余文件自动合并：#511 的后端（`MerchantInsightServiceImpl` 等）、`ai-insight` 页面及其单测，`action-ledger.json`，`integration-batch2.md`。batch3 各 lane 的修复和测试都没有丢。

### 再生成与计数（合并树上各做一次）

| 产物 | 结果 |
|---|---|
| `action-ledger.json`（`build-action-ledger.js --write`） | 4967 控件。自动合并结果与 `--write` 输出逐字节一致，无额外提交；`--check` / `--selftest` PASS |
| local tap 分母 | 2111 → **2118**（master 2121，叠加 L21 B9 −3） |
| local 事件合同 | **2469**（master 值；batch3 没有增减） |
| `controlCount` | **4967**（master 值；batch3 控件数不变） |
| `route-matrix.json`（`--check`） | 145 路由 / 27 场景，无差异，不写回 |
| `ui-integrity-baseline.js` | 未改；UI-GATE-0 PASS（没有新增违规，冻结值没有上调） |
| L21 CY-HANDOFF | 唯一一条是「在合并树上重建台账」，已按上面重建落地，没有丢失 |

### 本地验证（`90bffc43` 合并树）

- `TZ=Asia/Shanghai node --test "tests/unit/**/*.test.js"`：**13725/13725** 通过（`action-ledger.test.js` 46/46）
- `ui-integrity.js --warn-as-error`：UI-GATE-0 PASS；`ci/silent-error-gate.js`：通过；`ci/api-existence-gate.js`：rc=0
- `ci/test_ci_cost_contract.py`：PASS；`ci/test_release_manifest.py`：OK
- 后端本地离线 Maven 缺依赖，没能编译；Java 文件全部自动合并，以 CI 的 Backend tests 为准
- 仍然没有运行 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`

### CI 修复

- `90bffc43` / `4c6f901e` 上 GitHub CI run 39830163088 唯一的红：`Mini program unit shard (14/20)`，`p1-feedback-wiring-contract.test.js`「内容淡入确实铺开了」报 ENOENT，读的是 `tests/unit/__pickerview_negative_control__.wxml`。
- 根因：`picker-unification-contract.test.js` 在仓内 `tests/unit/` 下临时建删负控 wxml，另一条契约遍历全仓 wxml 时先列后读。并入 master 后新增测试文件，分片平移，两者落进同一分片，于是并发撞上。这和 #500 AC3 修的 roam-comment-scan 是同一类问题，master 上本来就潜伏着。
- 修复 `d98d7f7e`：负控夹具改写到系统临时目录，负控断言不变（系统 picker 仍要判红，picker-view 仍不许误伤）。没有跳过或放宽任何测试。

### CI（base = master，`pull_request` 事件）

| head | workflow | run | 结果 |
|---|---|---|---|
| `4c6f901e` | GitHub CI | 39830163088 | failure：shard 14/20 ENOENT（见上），其余 job 被新提交取消 |
| `d98d7f7e` | GitHub CI | **39830569976** | **success**：31 个 job（与 batch2 / 本批 39806526890 的 check 名集合相同） |
| `d98d7f7e` | Funds MySQL IT | **39830569923** | **success** |
| `d98d7f7e` | Mini followup focused regression | **39830569868** | **success** |
| `d98d7f7e` | Isolated authenticated Redis recovery | **39830570027** | **success** |

- #505 新 filter 首次在 `pull_request` 事件下实测：Detect changed areas 让 backend、admin UI、migration、dependency、xcx 全部命中，所有必需 check 名都出现并通过；`ci/test_ci_cost_contract.py` PASS。
- 之后只追加本记录文件的纯文档提交，最终 head 以 PR #512 的检查为准。
