# qx-final2 · launch 二次收口 — RESULT(2026-11-11)

分支 `launch/questo-20261110`(草稿 PR **#779** → `master`)。本 lane 按顺序 `--no-ff` 合入 qx-green、l-editor2、l-payout3、l-icons、l-iconsB，跑全部门禁、真库 IT 和 Gameplay HTTP，盯 PR #779 的 CI，修红灯，并补写 PR 正文。

以下事情都没有做：
- 没有合并到 `master`，没有把 #779 标为就绪；
- 没有碰生产、VPS、私有仓；
- 没有改已有迁移；
- 没有运行 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`(静态门的各子门禁是单独调用对应脚本复现的)；
- 没有新增任何 UI-integrity / 弱锚点 / U4 基线条目(弱锚点基线只按单测要求**删掉**了已修好的 A10、A12)；
- 没有提交任何密钥。

## 一、等待与合并记录

04:59 UTC 开始。前三条开工时已有 `STATUS:`，直接合入；l-icons、l-iconsB 当时远端还没有分支，每 15 分钟轮询(上限 20 小时)，12:03 UTC 两条都报了 `STATUS:`，没有跳过任何一条。

| # | Lane | 合入的远端头 | 合并提交 | STATUS | 冲突与解法 |
|---|---|---|---|---|---|
| 1 | qx-green | `6cc5ca7b` | `7047de8b` | PARTIAL(gitleaks 历史命中、架构门 AuthorWriteReceipt 消费者) | 无冲突 |
| 2 | l-editor2 | `a548364a` | `d2298a7c` | COMPLETE | 台账自动合并，合并树重建后无差异 |
| 3 | l-payout3 | `ac27787f` | `2256f79e` | PARTIAL(合单接口未连微信沙箱，运营端无新页面) | 无冲突 |
| 4 | l-icons | `1f3956a6` | `11c3fb69` | COMPLETE | 无冲突 |
| 5 | l-iconsB | `5629650a` | `fb9f1c28` | COMPLETE | 通行证页 `membership/index.{js,wxml,wxss}` 和台账冲突，见下 |

**l-iconsB 冲突解法(两侧合并)：** 两条 lane 都改了通行证页。按 l-iconsB RESULT「跨 lane」一节的建议：
- 页面结构取 l-iconsB 的 ps-pass(实色卡 + 标准列表行 + 底部固定白药丸，不做玻璃)；
- 卡里的钥匙换成 l-icons 的 `glass-key` 3D 玻璃序列帧(尺寸 184 不变)；
- 开通成功取 l-icons 的钥匙开场页 `subpackagePass/reveal`，旧的 `.ok` 开通成功层和两侧残留样式删除；
- `glass-pass.webp` 在 ps-pass 里没有位置，已无调用者，删除资产；
- l-icons 删掉了 `cy-illus` 注册而 l-iconsB 还在用，注册补回；
- 两条 lane 的单测按合并结果改钉；台账在合并树重建 6009 → 6010，计数断言按实测更新、两侧注释全保留。

**同一分支上的并行合入(不是本 lane 做的)：** 另一个收口代理(提交里自称 qx-final3)在 07:16–14:02 UTC 往 `launch/questo-20261110` 合入了 f-release、f-money、f-compliance、f-play、f-pages、f-merchant，并推了几笔门禁收口。我没有回退它们，每次都先 fast-forward / `merge` 远端再继续，没有改写历史。它们带来的红灯我一并修了(见第二节)。

## 二、收口修复(小提交)

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机未验 |
|---|---|---|---|---|---|
| F2-1 通行证页死字段 | 已修 | 合并后 `library` 在 WXML 零消费(U4)，去掉主题库请求和字段，主题数仍取概览 `libraryCount` | `subpackageMember/market/membership/index.js` | UI-GATE-0:PASS；l-pass / l-icons / l-iconsB 单测通过 | 通行证页真机 |
| F2-2 钥匙开场页静态门 | 已修 | 新路由补截图矩阵 F314 行(STATS 按实际 +1)和生成器产出的 fixture；`paid` 只决定跳转目标，改存 `this._paid`；`.ky__h` 50rpx 是原型逐值，按同文件做法标 ds-ok；通行证页 `{{view.count}} 个主题` 加 `|| 0` 兜底(`wx:if` 已挡住未知) | `scripts/shot-matrix.js`、`scripts/fixtures.json`、`subpackagePass/reveal/index.{js,wxss}`、`membership/index.wxml`、l-iconsB 单测 | DS 硬编码门、孤立单位门、U4 死字段门、截图矩阵、覆盖率门单独复跑全过 | — |
| F2-3 台账单测语法错 | 已修 | 并行合入时 `action-ledger.test.js` 729 行丢了多行注释的开头，整文件语法错。按合并前两侧原文补回 | `tests/unit/action-ledger.test.js` | 48/48 | — |
| F2-4 帖文详情举报弹窗 | 已修 | f-merchant 把帖文详情改成按视角浅色后，举报原因弹窗挂在主题容器外。按其它页做法补 `theme-merchant / theme-dark` | `pages/square/detail/index.wxml` | `popup-theme-scope-contract` 通过 | 商家视角举报弹窗 |
| F2-5 弱锚点基线回收 | 已修 | A10、A12 已经不弱，按单测要求从基线删掉(只减不增) | `scripts/uiaudit/weak-anchor-baseline.json` | `selector-anchor-strength-contract` 通过 | — |
| F2-6 快速配置起草类型 | 已修 | f-merchant 撤掉引导句后 `mode` 在 WXML 零消费，改存 `this._productType`，只进起草请求体；相关单测改读实例字段 | `pages/publish/simple/index.js`、`simple-ai*.test.js` | 引用该页的 20 个单测文件 385/385；U4 门通过 | — |

## 三、门禁(最终头 `4cf14763`)

| 门禁 | 结果 |
|---|---|
| 小程序全量单测(仓库根 `node --test chengyinhub-xcx/tests/unit/*.test.js`) | **17288 个测试，17288 通过，0 失败** |
| `node chengyinhub-xcx/tests/lint/ui-integrity.js` | **UI-GATE-0:PASS** |
| 动作台账 `build-action-ledger.js --check` | PASS，控件 6022，无 diff |
| 路由矩阵 `build-route-matrix.js --check` | PASS，171 条路由 / 27 个场景，无 diff |
| `mvn -q -DskipTests package`(`LANG=C.UTF-8`) | 通过 |
| 改动类的 Java 测试 | 第一轮(`2256f79e`，l-payout3 等 24 个 main 类)：114 个测试类 1309 条，0 失败；第二轮(f-compliance / f-money 改动)：73 个类 671 条，0 失败；第三轮(f-* 的 `ProductRole*`)：26 个类，0 失败 |
| 管理端 `npm run build:prod` | 通过 |
| 主包体积 `mainpkg-size-report.js --json` | 3,307,305 字节 × 0.633 ≈ **2044.5 KiB < 2048**(余量只剩 3.5 KiB，见风险) |
| keyframes 棘轮 `motion-property-ratchet` | 9/9 通过，上限 146 未动 |
| 小程序静态门子门禁(单独调用，不跑 `xcx-check.sh`) | DS 硬编码 `--ci`、孤立单位、U4 死字段、截图矩阵、覆盖率门全过 |

## 四、真库 IT 与 Gameplay HTTP(GitHub 托管 runner)

| 工作流 / 批次 | 源 SHA | 结果 |
|---|---|---|
| Real Storage IT `marketing-db-acceptance`(25 类，launch 波次营销 / 通行证 / 席位 / 工坊等) | `2256f79e` / `fb9f1c28` / `4cf14763` | **三次都通过**(run 42963697329、43022498843、43037552106) |
| `launch-l-payout`(EcomPayoutMapperMySqlIT) | `2256f79e` / `fb9f1c28` | **通过**(42963700842、43022503089) |
| `launch-l-payout2`(EcomPayoutWiringMySqlIT) | `2256f79e` / `fb9f1c28` | **通过**(42963704191、43022507681) |
| `launch-l-payout3`(EcomCombineMapperMySqlIT) | `2256f79e` / `fb9f1c28` | **通过**(42963707446、43022512446) |
| `payment-intent-correctness`(4 类) | `2256f79e` / `fb9f1c28` | **通过**(42963710687、43022517045) |
| `launch-f-money`(CreatorIncomeDetailIdempotencyMySqlIT) | `fb9f1c28` / `4cf14763` | **通过**(43022522171、43037569736) |
| `architecture-day-integrated-changes` / RoamLocationRetentionMySqlIT(f-compliance 改了留存清理) | `fb9f1c28` | **通过**(43022526108) |
| Gameplay HTTP `generic-only` | `2256f79e` / `fb9f1c28` | **通过**(42963713005、43022530866) |
| 同上 | `4cf14763` | 见第五节末尾(runner 装包失败，非代码) |

`fb9f1c28` 之后到 `4cf14763`，支付、分账、通行证代码没有变化(只有小程序和 `ProductRole*`)，所以 payout / payment-intent 批次没有在最终头再跑。

## 五、PR #779 的 CI

按时间顺序，每一轮红灯和处理：

| 头 | 红的 job | 原因 | 处理 |
|---|---|---|---|
| `2256f79e`(前三条合入) | Fast gates、Backend tests | 架构门禁(见下) | 只能在 master 受信策略放行 |
| `e01a45de`(l-icons / l-iconsB 合入) | Mini program static gates | DS 硬编码、孤立单位、U4 死字段、截图矩阵、覆盖率 5 个子门禁 | F2-2 修好 |
| `c7bc7584` | 小程序单测 1 条 | F314 缺生成器 fixture | 补 fixture |
| `eb272ae7`(并行合入 f-*) | 小程序单测 4 条 + U4 | 见 F2-3 至 F2-6 | 修好，全量单测 17288/17288 |
| `4cf14763` 及之后的纯文档头 | Mini program static gates、Mini program static checks；Gameplay HTTP | runner 从约 14:04 UTC 起解析不了 `*.ubuntu.com`，「Ensure ripgrep」/「Install clients and private Redis」装包失败，门禁脚本和测试**一条都没执行** | 重跑 4 次仍是同一原因；不是代码问题，本地已逐个复跑这些子门禁全部通过。等 runner 恢复后重跑 failed jobs 即可 |

最终头(`4cf14763` 及其后的纯文档提交)上各 job：

| Job | 结果 |
|---|---|
| Detect changed areas、Secret scan (gitleaks)、SBOM + dependency scan | success |
| Mini program unit shard 1–20 | 全部 success |
| Migration layout gate、Migration safety gate | success |
| Admin UI build gate、Admin UI E2E contract gate | success |
| Funds MySQL IT、Funds IT gate self-test、Followup 39 selected backend cases、isolated-authenticated-redis-recovery、Mini followup focused regression | success |
| Mini program static gates / static checks | **failure(基础设施)**：apt 装 ripgrep 失败，未执行 |
| Fast gates | **failure(要你放行)**：架构门 9 条，StoreKit 已接受，另 8 条新边界见待你决定 1 |
| Backend tests | **failure(要你放行)**：构建、全部单测、整应用启动、迁移 MySQL IT 都 success，只有末步编译期引用门 `EXACT_STORAGE_PORT_CONSUMER` |
| Gameplay HTTP generic-only(手动) | `2256f79e`、`fb9f1c28` 通过；`4cf14763` / `17de18de` 因同一个 apt 故障未执行 |

## 六、剩余风险

- **主包余量只剩约 3.5 KiB**(2044.5 / 2048)。f-* 并行合入后涨了约 5 KiB，再往主包加东西就会超。
- l-payout3 合单下单 / 查单 / 关单 / 通知 / 子单退款全部没有连微信沙箱，字段按搜索摘录的官方文档核对。开关默认关闭，关闭时特征测试证明现有结果逐字不变。
- 合单打开后又关闭，未结清的合单会卡住(失败关闭，不会多付)。
- 供应商应付只记账，付款靠运营线下处理后录入；运营端没有页面。
- 通行证页合并了两条 lane 的方案(ps-pass 结构 + 玻璃钥匙 + 钥匙开场)，只做了单测和静态门，**没有在 rig 里复拍**合并后的样子。
- `subpackagePass` 序列帧合计 1.7 MB，首次进入才下载，网络差时首帧前会有黑屏(l-icons 待你决定 3)。
- 另一个代理在同一分支上并行合入，两边都在修门禁，后续还可能互相覆盖；本 lane 每次推送前都先合远端。

## 待你决定(括号里是已经实现的默认)

1. **架构门禁放行**(不改代码，等你在 master 受信策略里批)：
   - Fast gates：除你已接受的 StoreKit `DEPENDENCY_EXPANSION` 外，还有 8 条 `NEW_UNOWNED_BOUNDARY`：`ApiMerchantPayoutAccountController`、`ApiPlatformPassController`、`ApiPlayFeedbackController`、`EcomPayoutMapper`、`EcomCombineMapper`(l-payout3 新增)、`PlayHelpAuditMapper`、`PlayRunFeedbackMapper`、`PlayRunFeedbackService`；
   - Backend tests 末步：`EXACT_STORAGE_PORT_CONSUMER`，`ApiTopicTemplateController → AuthorWriteReceiptService`(`M_AUTHOR_WRITE_RECEIPT`)；
   - 都要在 master 的 `ci/architecture/owners.json` 里登记，PR 自己改会被判 `POLICY_CHANGE`。
2. **通行证页合并方案**：(ps-pass 实色卡 + 卡里玻璃钥匙 + 开通成功跳钥匙开场。)如果你要卡里也是 B 风格平面钥匙，把 `membership/index.wxml` 里 `.pp__key` 换回 `<cy-illus name="key" size="184" />` 即可。
3. **l-icons 的 3 条、l-iconsB 的 5 条、l-payout3 的 5 条待你决定**原样保留，见各自 RESULT.md。
4. qx-final 的待你决定 1、2、5、6、7、8 仍然有效；3、4、9 已由 qx-green 解决。

STATUS: PARTIAL - qx-green、l-editor2、l-payout3、l-icons、l-iconsB 五条都已合入并修完合并与并行合入带来的红灯；本地门禁全过(小程序全量 17288/17288、UI-GATE-0、台账与路由矩阵零差异、mvn、Java 0 失败、管理端、主包 2044.5 KiB、keyframes)，真库 IT 全部通过；PR CI 只剩架构门禁两处(要你在 master 受信策略放行)和 runner 装包故障导致未执行的静态门与 Gameplay HTTP(等 runner 恢复后重跑)
