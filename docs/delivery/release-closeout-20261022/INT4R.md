# INT4R：batch4 集成候选刷新记录（2026-10-22）

- 集成分支：`integrate/closeout-batch4-20261022`（PR #530，draft）。
- 本次刷新前的 head：`ce043852`。master：`38dc1bb7`。
- 起因：#530 用的 lane SHA 是在 BF2 推评审修复之前取的。本次把新 head 并进来，并核对其余 lane。
- 范围：
  - 只做集成：没有合并、关闭或评论任何 PR，没有推 master，没有改 ready 状态，没有删分支。
  - 不部署、不连生产、不执行任何 DB 变更。
  - 没有运行 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`。

## 1 base 判定

- `#512`（batch3）状态：**open / 未合并**。所以 base 保持 `integrate/closeout-batch3-20261022`。
- batch3 当前 head `e7fe467a` 已经在本分支祖先里（`HEAD..batch3` 为 0 个提交），不需要再并。
- #512 合入 master 后，需要做两件事：
  - 把 origin/master 并进本分支；
  - 把 #530 的 base 改为 `master`。

## 2 lane head 核对（15 个）

| PR | Lane | #530 原 SHA | 当前 head | 处理 |
|---|---|---|---|---|
| #513 | DL1 | `5cd520be` | `ab60b971` | **并入** → `943cd6c4`（无冲突） |
| #514 | F1a | `6e746b30` | `6e746b30` | 无变化 |
| #515 | F1b | `458995f7` | `458995f7` | 无变化 |
| #516 | F1c | `525c4342` | `525c4342` | 无变化 |
| #517 | F1d | `57a5e55c` | `57a5e55c` | 无变化 |
| #518 | O15 | `b8c08c19` | `b8c08c19` | 无变化 |
| #519 | O14 | `1be14852` | `e08ba609` | **并入** → `cad50eec`（1 处冲突） |
| #520 | O3/O9 | `0fe2c513` | `0fe2c513` | 无变化 |
| #521 | F5 | `92aa3703` | `92aa3703` | 无变化 |
| #522 | O11/O17 | `666f5b63` | `666f5b63` | 无变化 |
| #523 | O8/O10 | `852f3782` | `852f3782` | 无变化 |
| #524 | F2 | `f82e1c87` | `f82e1c87` | 无变化 |
| #525 | F7 | `c4f434f4` | `c4f434f4` | 无变化 |
| #526 | F4 | `d87be57f` | `d87be57f` | 无变化 |
| #527 | F3 P1 | `9982d242` | `182faa85` | **并入** → `cb793e71`（2 处冲突） |

三个新 head 在各自 PR 上的 CI（base = master）都是绿的：

| PR | GitHub CI | Funds MySQL IT |
|---|---|---|
| #513 `ab60b971` | 39835806495 ✅ | 39835806512 ✅ |
| #519 `e08ba609` | 39837919960 ✅ | 39837919966 ✅ |
| #527 `182faa85` | 39836078600 ✅ | 39836078572 ✅ |

## 3 冲突处理

- **#519**：`ci/backend-test.sh` 的 `MYBATIS_ITS`。
  - 两边都是末尾追加。按并集保留：集成侧的 AC1/AC2/DL1/F2/F3 条目在前，O14 新增的 `O14CityCodeBackfillMySqlIT` 接在后面。
  - `ci/migration-dependencies.json` 自动合并。
- **#527**：
  - `ci/backend-test.sh`：只有 F3 那条注释冲突，采用 lane 的新说法（「Phase 1 不动 settlement_claim（7 列唯一键原样）」）。其余条目保持并集。
  - `ci/migration-dependencies.json`：
    - 采用 lane 的意图，删除 `doc2_f3_settlement_claim_unit_20261022` 这一条依赖登记；
    - 保留 F4 的 5 条追加（`merchant_desk_receipt`、`merchant_redemption_anchor`、`pms_sku_booking`、`verification_review_evidence`、`20261022_merchant_desk_switch_seed`）。
- 校验：`python3 -m json.tool ci/migration-dependencies.json` 通过；`bash -n ci/backend-test.sh` 通过。

`migration_doc2_f3_settlement_claim_unit_20261022.sql` 被 #527 lane 自己删除。这个迁移从没进过 master，也从没落过库，所以不属于「删除既有 migration」。

## 4 settlement_claim 唯一键核查

在合并树上 grep `sql/`：
- 没有任何迁移再对 `settlement_claim` 做 `ALTER` / `DROP` / `ADD` 唯一键；
- `uk_settlement_claim_key` 只出现在 `base_schema_20260818.sql` 的原始 7 列定义里；
- 全仓只剩历史记录 `integration-batch4.md` 还提到 `settlement_claim_unit`。

## 5 再生成

在最终合并树上各跑一次：
- `build-action-ledger.js --write`：4967 控件，零差异；`--check` PASS。
- `build-route-matrix.js --json`：145 routes / 27 scenes，零差异；`--check` PASS。

结果：
- 没有产物变化，所以没有再生成提交。
- `ui-integrity-baseline.js` 未改。
- 新 head 的 PR 正文里都没有 CY-HANDOFF 行。

## 6 本地验证（`cb793e71`）

- `migration-layout-check.sh` 通过；`migration-safety-check.sh` 通过。
- `mvn -pl chengyinhub-admin -am test`，只跑本次变动相关的用例：
  - 全模块编译通过；
  - `MerchantReferralBountyDeadlockRetryTest` 10/10；
  - `O14MigrationContractTest` 4/4。
- 本地需要 `LANG=C.UTF-8`，否则 quartz 模块会因仓内中文文件名报 `InvalidPathException`。这是环境问题，与代码无关。
- 真库 IT（`O14CityCodeBackfillMySqlIT`、`FreeExploreBundleLedgerMySqlIT`、`MerchantReferralBountyDeadlockRetryMySqlIT`）本地没有 MySQL，交给 CI 跑。

## 7 CI（head `cb793e71`）

base 不是 master，所以 `pull_request` 事件只触发 3 个辅助 workflow。**完整的 GitHub CI 不会因 PR 自动跑**。和上一轮一样，这次用 `workflow_dispatch` 在本分支手动触发了 GitHub CI 和 Funds MySQL IT。

| workflow | 触发 | run | 结果 |
|---|---|---|---|
| GitHub CI | workflow_dispatch | 39866446843 | ✅ 31/31 job success（Backend tests 含 MYBATIS_ITS 真库 IT、完整启动、迁移 IT） |
| Funds MySQL IT | workflow_dispatch | 39866450110 | ✅ success |
| Mini followup focused regression | pull_request | 39866418956 | ✅ success |
| Isolated authenticated Redis recovery | pull_request | 39866418838 | ✅ success |
| Isolated pool recovery | pull_request | 39866418846 | ✅ success |

没有集成失败需要修复。本文档提交会让 `pull_request` 的 3 个 workflow 重跑一遍。这次提交只改文档，以上结果仍适用于代码树 `cb793e71`。

## 8 保留的警告（合并 / 开开关前必读）

- **F3（#527）K1–K8 待 owner 裁决。** 保持 `chengyin.free-explore.bundle.reservation-enabled` 关闭。K3（罚没可被整单全退绕过）解决前不得开启。
  - 本次 182faa85 只去掉了 settlement_claim 唯一键改动，单位维度推迟到 Phase 2（需另走资金审批），K1–K8 本身并未解决。
- **#524 F2 部署顺序：先迁移，后发 jar。** `ThemeStateKeyMapper` 会查询扩展列，没落库就上 jar 会报 `Unknown column`。
- **RV3 should-fix 项**：#515 / #516 / #525 / #527 的 should-fix 要在打开对应开关前处理完。本次刷新没有处理这些项。
- #513 DL1 是资金路径。本次的新行为是：savepoint 内的 1205 锁等待超时不再上抛，只有死锁和 savepoint 失效才交给 `@DeadlockRetry`。需要 owner 审阅。
- #519 O14：
  - city_code 回填现在只认俱乐部城市字段，并新增 `o14_city_code_backfill_log` 表和只读预览 `_precheck_o14_content_city_code_backfill_20261022.sql`；
  - 落库前先跑预览，人工核对歧义清单。
- #518 O15 的 `revenue_share` 口径仍待定。
