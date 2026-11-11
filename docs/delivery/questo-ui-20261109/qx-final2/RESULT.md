# qx-final2 · launch 二次收口 — RESULT(2026-11-11)

分支 `launch/questo-20261110`(PR **#779** → `master`，草稿)。本 lane 把 launch 之后补的五条 lane 按顺序 `--no-ff` 合入，跑全部门禁、真库 IT、Gameplay HTTP，盯 PR #779 的 CI，并补写 PR 正文。

以下事情都没有做：没有合并到 `master`，没有把 #779 标为就绪；没有碰生产、VPS、私有仓；没有改已有迁移；没有运行 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`；没有新增 UI-integrity baseline 条目；没有提交任何密钥。

## 一、等待与合并记录

04:59 UTC 开始。qx-green、l-editor2、l-payout3 开工时已经有 `STATUS:` 行，直接按顺序合入。l-icons、l-iconsB 当时远端还没有分支，按每 15 分钟轮询(上限 20 小时，到 2026-11-12 00:59 UTC)。

| # | Lane | 合入的远端头 | 合并提交 | STATUS | 冲突与解法 |
|---|---|---|---|---|---|
| 1 | qx-green | `6cc5ca7b` | `7047de8b` | PARTIAL(gitleaks 历史命中、架构门 AuthorWriteReceipt 消费者要你拍板) | 无冲突 |
| 2 | l-editor2 | `a548364a` | `d2298a7c` | COMPLETE | 动作台账自动合并；在合并树上 `build-action-ledger.js --write` 重建，无差异 |
| 3 | l-payout3 | `ac27787f` | `2256f79e` | PARTIAL(合单接口未连微信沙箱，运营端无新页面) | 无冲突；迁移依赖、真库 IT 登记自动合并 |
| 4 | l-icons | — | — | 待合入 | 05:26 UTC 远端出现分支 `claude/l-icons-20261111`，RESULT 还没有 `STATUS:` 行 |
| 5 | l-iconsB | — | — | 待合入 | 远端还没有分支 |

## 二、门禁(合并树 `2256f79e`)

| 门禁 | 结果 |
|---|---|
| 小程序全量单测(仓库根 `node --test chengyinhub-xcx/tests/unit/*.test.js`) | **17218 个测试，17218 通过，0 失败** |
| `node chengyinhub-xcx/tests/lint/ui-integrity.js` | **UI-GATE-0:PASS**(没有新增 baseline) |
| 动作台账 `build-action-ledger.js --write` | 重建后无 diff，控件 5997 |
| 路由矩阵 `build-route-matrix.js --check` | PASS，170 条路由 / 27 个场景，无 diff |
| `mvn -q -DskipTests package`(`LANG=C.UTF-8`) | 通过 |
| 改动类的 Java 测试 | 本轮改动的 24 个 main 类，引用它们的 114 个测试类：1309 个测试，0 失败，0 错误，180 个跳过(需要真库的类在无库时按设计跳过，真库部分见第三节) |
| 管理端 `npm run build:prod` | 通过(`DONE Build complete`)，lockfile 没改 |
| 主包体积 `mainpkg-size-report.js --json` | `mainTotal` 3,297,957 字节 × 0.633 ≈ **2038.7 KiB < 2048** |
| keyframes 棘轮 `motion-property-ratchet` | **9/9 通过**(qx-green 把 20 组 round-2 keyframes 改成 transition，上限 146 没动) |

## 三、真库 IT 与 Gameplay HTTP(GitHub 托管 runner，源 SHA `2256f79e`)

| 工作流 / 批次 | 结果 | 说明 |
|---|---|---|
| Real Storage IT `marketing-db-acceptance`(25 类，launch 波次新增的营销 / 通行证 / 席位 / 工坊等真库类全在这一批) | **通过**(run 42963697329) | |
| Real Storage IT `launch-l-payout`(`EcomPayoutMapperMySqlIT`) | **通过**(run 42963700842) | |
| Real Storage IT `launch-l-payout2`(`EcomPayoutWiringMySqlIT`) | **通过**(run 42963704191) | |
| Real Storage IT `launch-l-payout3`(`EcomCombineMapperMySqlIT`) | **通过**(run 42963707446) | |
| Real Storage IT `payment-intent-correctness`(4 类) | **通过**(run 42963710687) | 支付回调、退款链路有改动，加跑 |
| Gameplay HTTP `generic-only` | **通过**(run 42963713005) | qx-final 时 master 和底座都红的那一条，qx-green 已修 |

## 四、PR #779 的 CI(头 `2256f79e`)

| Job | 结果 |
|---|---|
| Detect changed areas、Secret scan (gitleaks)、SBOM + dependency scan | success |
| Mini program unit shard 1–20 | 全部 success |
| Mini program static gates | **success**(qx-final 时的 12 个子门禁红灯已被 qx-green 修掉) |
| Migration layout gate、Migration safety gate | success |
| Admin UI build gate、Admin UI E2E contract gate | success |
| Funds MySQL IT、Funds IT gate self-test | success |
| Followup 39 selected backend cases、isolated-authenticated-redis-recovery | success |
| Fast gates(CWD / API existence / silentError) | **failure**，只有架构门 `gate.py source` 一步红，见下 |
| Backend tests | **failure**：构建和全部单测、整应用启动、迁移 MySQL IT 都 success；只有最后一步「Check private Mapper compiled references」红，见下 |

两处红都是架构门禁，只能在 `master` 的受信策略里放行。候选分支改 `ci/architecture/*` 会被判 `POLICY_CHANGE` 并失败关闭，所以本 PR 里不能修：

1. **Fast gates 的架构门**(我在本地按 CI 同样的参数复跑 `c7c5e838` 版验证器，结果 BLOCKED，共 9 条)：
   - `DEPENDENCY_EXPANSION`：`chengyinhub-system/pom.xml` 的 StoreKit 依赖。**你已接受，按约定不处理。**
   - `NEW_UNOWNED_BOUNDARY` 8 条：`ApiMerchantPayoutAccountController`、`ApiPlatformPassController`、`ApiPlayFeedbackController`、`EcomPayoutMapper`、`EcomCombineMapper`(l-payout3 新增，本轮多出来的一条)、`PlayHelpAuditMapper`、`PlayRunFeedbackMapper`、`PlayRunFeedbackService`。要在 master 的 `ci/architecture/owners.json` 登记 owner。
2. **Backend tests 的编译期引用门**：`EXACT_STORAGE_PORT_CONSUMER`，`ApiTopicTemplateController → AuthorWriteReceiptService`(`M_AUTHOR_WRITE_RECEIPT`)。来源 qx-c1，qx-green 已报。要在 master 的策略里把 `ApiTopicTemplateController` 加进允许的消费者。

## 五、剩余风险

- l-payout3 的合单下单 / 查单 / 关单 / 通知 / 子单退款全部没有连微信沙箱，字段按搜索摘录的官方文档核对，开通后必须在沙箱再核。开关默认关闭，关闭时特征测试证明现有下单、退款、T+14 结果逐字不变。
- 合单打开后又关闭，未结清的合单会卡住(失败关闭，不会多付)。有合单没结清时不要关 `combined-enabled`。
- 供应商应付只记账，付款靠运营线下处理后录入；运营端没有页面，只有带权限的接口。
- qx-green 的 keyframes → transition 改造没有真机逐帧对比；首次进入时光点 2、3 在延迟期间隐身(原来静止可见)。
- l-icons、l-iconsB 还没合入，合入后门禁和 CI 要整轮复跑。

## 待你决定(括号里是已经实现的默认)

1. **架构门禁放行**(默认：不改代码，等你在 master 上批策略)：登记上面 8 个新边界的 owner，并把 `ApiTopicTemplateController` 加进 `M_AUTHOR_WRITE_RECEIPT` 的允许消费者。批完让 #779 合上新的 master 再跑一次 CI。
2. **l-payout3 的五条待你决定**原样保留(预留口径、供应商应付付款方式、HOLD_REFUND、ABNORMAL、运营端页面)，见 `l-payout3/RESULT.md`。
3. **qx-green 的 gitleaks 历史命中**：本轮 CI 的 Secret scan 已经是 success，不需要再处理，除非你要求清历史。
4. qx-final 的待你决定 1、2、5、6、7、8 仍然有效；3(keyframes)、4(Gameplay HTTP 那一条)、9(小程序静态门)已由 qx-green 解决。

STATUS: PARTIAL - qx-green、l-editor2、l-payout3 已合入，门禁全过(小程序全量 17218/17218、UI-GATE-0、台账与路由矩阵零差异、mvn、Java 1309/0 失败、管理端、主包 2038.7 KiB、keyframes)，5 批真库 IT 和 Gameplay HTTP generic-only 全部通过；CI 只剩架构门禁两处要你在 master 受信策略里放行；l-icons、l-iconsB 还在等 STATUS
