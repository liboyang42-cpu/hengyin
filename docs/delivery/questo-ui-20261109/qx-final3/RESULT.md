# qx-final3 · 上线前最后一次集成（PR #779 合入 master 之前）

分支 `launch/questo-20261110`（本线负责合入）。PR #779（`launch/questo-20261110` → `master`）保持草稿，**没有合并，也没有标记 ready**。没有碰生产，没有改任何已有迁移，没有运行 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`，提交里没有密钥。

依据：`claude/questo-ui-brief-20261109` 上的 LAUNCH.md、BRIEF.md、AUDIT-GAPS.md。

## 一、等待与合入

先等 qx-final2 的 RESULT.md 和六条 f-* 修复线的 RESULT.md 都出现 `STATUS:` 行，每 15 分钟查一次。从 05:15Z 开始等，13:46Z 六条全部到齐，没有缺席的线。

| 顺序 | 线 | 分支头 | 线自报 STATUS | 合并提交 | 冲突与处理 |
|---|---|---|---|---|---|
| 1 | f-release | `46fc4860b` | COMPLETE | `68617b367` | 无 |
| 2 | f-money | `d6496bc41` | PARTIAL（真机 / 沙箱资金验收没做；合单与转付的二清定性要你和法务决定） | `1a2374854` | 无 |
| 3 | f-compliance | `ed379945a` | COMPLETE | `aee716fe1` | `ci/migration-dependencies.json`：两侧新增的迁移依赖都保留 |
| 4 | f-play | `771d86473` | PARTIAL（城市定向场次票、自由探索真实报名两条路径没有重新走通） | `e73bcb075` | `action-ledger.test.js` 计数：在合并树上重建台账后按实测填，两侧注释都保留 |
| 5 | f-pages | `42d2a3ea6` | COMPLETE | `f02fe89fd` | 台账与计数，处理同上；`ui-integrity-baseline.js` 的 U5 digest：两侧都只是行号位移，条数仍是 42，**没有新增登记**，digest 按门禁算法重算 |
| 6 | f-merchant | `bf91627d2` | COMPLETE | `1eac3a65f` | 台账与计数，处理同上 |

全部用 `--no-ff` 合入。期间 qx-final2 那边的代理还在同一分支上推了 l-icons、l-iconsB 和几次门禁收口（`11c3fb69c`、`fb9f1c287`、`e01a45ded`、`c7bc75843`、`0f7406f3e`、`4cf14763e`）。我每次都先同步远端（`52193cc26`、`eb272ae74`），没有强推，也没有覆盖对方的提交。

### 合入后的收口修复（都是两边合在一起才暴露的问题）

| 提交 | 问题 | 处理 |
|---|---|---|
| `afa90ffd5` | Funds MySQL IT 红：`RoamLocationRetentionMySqlIT` 手工装配 `DataRetentionServiceImpl`，没有注入 f-compliance 新加的 `aiGenerationLogMapper` | 只改测试：补表、补 Mapper，加 181 天旧行的断言 |
| `afa90ffd5` | 危险动作门禁自证红：设置页「AI 对话记录」的清除调用没有登记 | 在 `danger-action-registry.json` 补一条，和 NPC 面板用同一个接口、同一个确认 key |
| `00331811d` | WXML 孤立单位门禁红：套票页「可退金额 {{quote.amount}} 元」 | 补「—」兜底。`quote()` 已经校验 amount 是非空数字串，兜底只在异常时生效，不会把未知显示成 0 |
| `181e222f3` | silentError 门禁红：`pages/mylike/mylike.js` 实际 6 处，只登记了 4 处（f-pages 新增的「据点」页签） | 白名单 4 → 6，理由和原有的收藏双域一致 |
| `181e222f3` | 路由矩阵漂移：f-merchant M-35 让帖文详情、搜索、搜索结果在商家入口时换成 `theme-merchant`，启发式把这三页误判成商家页 | 在 `build-route-matrix.js` 的 `ROLE_OVERRIDES` 里设为 player（和 `searchmap` 同理，那只是条件类）。重建后零差异 |
| `181e222f3` | f-release 发布清单已经过期：之后又合进来 4 个新迁移 | 补标注后重算，发布集变为 **155 项**（必须 102、可选 29、需业主授权 20、不执行 4），`--check` 结果一致。`release-manifest.json` 重新生成（640 个迁移）。DEPLOY.md 的候选头、计数和 step 序号同步更新 |
| `4cf14763e`（qx-final2 推送，和我本地的修复等价，我改用它） | ① 我合 f-pages 时冲突脚本把 `action-ledger.test.js` 一行跨行注释的开头截掉了，整个文件语法错误；② 帖文详情的举报半屏在主题容器外；③ 弱锚点基线里 A10、A12 已经不弱，按棘轮回收（6 → 4，只减不增）；④ `publish/simple` 的 mode 字段没有消费方 | 已在远端修复。我本地等价的提交没有推送，丢弃了 |

## 二、门禁（最终头 `4cf14763e`，本地）

| 门禁 | 结果 |
|---|---|
| 小程序全量单测（仓库根 `node --test chengyinhub-xcx/tests/unit/*.test.js`） | **17288 / 17288 通过，0 失败** |
| `node chengyinhub-xcx/tests/lint/ui-integrity.js` | **UI-GATE-0:PASS**（没有新增基线登记；U5 只按行号位移重算了 digest） |
| 动作台账 / 路由矩阵（`build-action-ledger.js --write`、`build-route-matrix.js --json`） | 重建后 **零差异**。控件 6022，tap 3027 |
| 危险动作门禁 | PASS，53 个调用点，38 个不可逆动作全部有确认 |
| WXML 孤立单位、silentError 白名单、接口存在性 | 全部通过 |
| `mvn -q -DskipTests package`（`52193cc26`；之后没有改过 Java、XML 或 SQL） | 通过 |
| 改动类的 Java 测试（`206049b6f..HEAD` 改动的 main 类和 Mapper 的全部调用方测试，再加改动的测试类） | 99 个类、1021 条：**0 失败、0 错误**，18 条跳过（需要真库的类在没有库时按设计跳过，这些都在下面的真库 IT 里跑了） |
| 管理端 `npm run build:prod`（`52193cc26`；之后 `chengyinhub-ui` 没有改动） | `DONE Build complete` |
| 主包体积 `mainpkg-size-report.js` | `mainTotal` 3,307,305 字节 × 0.633 ≈ **2044.5 KiB < 2048**（余量只剩 3.5 KiB，见风险 6） |
| keyframes 棘轮（全量单测里的 motion / keyframes 合同） | 通过（包含在 17288 条里） |
| 迁移门禁 `ci.test_migration_gates` + `ci.test_migration_ledger` | 202 条 OK；`migration_ledger.py manifest` 0 问题 |
| f-release `gen_release_set.py --check` | `OK 155 项，产物一致` |

## 三、真库 IT 与 Gameplay HTTP

真库 IT 在 `eb272ae74` 上跑（这之后没有改过 Java、XML 或 SQL），时区都是 UTC。

| 批次 | 覆盖 | run | 结果 |
|---|---|---|---|
| `marketing-db-acceptance` | 营销与通行证真库类整批（含 `PlatformPassMySqlIT`、`NpcGeneratedAudioMySqlIT`） | 543–550 号之一（`eb272ae74`）；qx-final2 在 `4cf14763e` 上另跑了一次（run 43037552106） | 通过 |
| `launch-l-payout` | `EcomPayoutMapperMySqlIT` | 同上 | 通过 |
| `launch-l-payout2` | `EcomPayoutWiringMySqlIT` | 同上 | 通过 |
| `launch-l-payout3` | `EcomCombineMapperMySqlIT` | 同上 | 通过 |
| `launch-f-money` | `CreatorIncomeDetailIdempotencyMySqlIT` | 同上；`4cf14763e` 上另跑了一次（run 43037569736） | 通过 |
| `payment-intent-correctness` | 支付单、报名回调、现金提现、OWN_C04_21 | 同上 | 通过 |
| `port-prelaunch-69` | 含 `RoamLocationRetentionMySqlIT`（本线改过） | 同上 | 通过 |
| `ai-quota` | 含 `AiQuotaRecoveryMySqlRedisIT`（f-compliance 改了 AI 网关） | 同上 | 通过 |
| PR 自带的 Funds MySQL IT（50/9/30） | — | `4cf14763e` run 43037529717 | 通过 |
| Gameplay HTTP `generic-only` | `GenericGameplayHttpE2eIT` | `eb272ae74` run 43034778591；`4cf14763e` run 43037545384（attempt 1 到 4；attempt 5 还在跑） | **没有跑到测试**：runner 在「Install clients and private Redis executable」这一步 apt 解析不了 `archive.ubuntu.com` / `security.ubuntu.com`（Temporary failure resolving），退出码 100，测试一条都没执行。同一个 workflow 当天 05:08 在 `2256f79e` 上、12:41 在 `fb9f1c287` 上都通过了。这是基础设施故障，不是代码问题 |

## 四、PR #779 CI

最终代码头是 `4cf14763e`：CI 运行 43037529574，attempt 1 到 3。之后的 `44a97d84c` 是 qx-final2 只改文档的提交，对应运行 43046610801。

| Job | 结果 | 说明 |
|---|---|---|
| Detect changed areas、Secret scan（gitleaks）、SBOM + dependency scan | 通过 | |
| Mini program unit shard 1–20 | 20 / 20 通过 | |
| Admin UI build gate、Admin UI E2E contract gate | 通过 | |
| Migration layout gate、Migration safety gate | 通过 | |
| Funds MySQL IT（50/9/30，skipped=0）及其自证 | 通过 | `afa90ffd5` 之前是红的，已经修复 |
| Followup 39 selected backend cases、isolated-authenticated-redis-recovery | 通过 | |
| **Backend tests** | 红，原因是受信策略，代码本身没问题 | 「Build and test backend」（全量 Java 测试加 MyBatis IT）、整包启动、原子迁移 IT、O14、部署契约、重复建表门禁**全部通过**。只有「Check private Mapper compiled references」判 BLOCKED：`EXACT_STORAGE_PORT_CONSUMER` `ApiTopicTemplateController` → `AuthorWriteReceiptService`。这一项从 `2256f79e` 起就是红的，见风险 1 |
| **Fast gates** | 红，原因是受信策略 | CWD、接口存在性、silentError 和架构自证都通过；「Check architecture changes」判 BLOCKED：StoreKit `DEPENDENCY_EXPANSION`（已接受）加上 8 个 `NEW_UNOWNED_BOUNDARY`（没有被接受），见风险 1 |
| **Mini program static gates** | 红，原因是基础设施 | 「Ensure ripgrep for static scanning」这一步 apt 解析不了 `*.ubuntu.com`（Temporary failure resolving），退出码 100。后面的 RequestBody、危险动作、`ci/xcx-check.sh` 都被跳过了。从 14:04Z 起，GitHub hosted runner 一直解析不了 Ubuntu 源，43037529574 attempt 2 和 43046610801 两次都一样。**这些子门禁我在本地逐个跑过**（危险动作门禁 PASS、WXML 孤立单位通过、UI-GATE-0 PASS；按规则没有在本地运行 `xcx-check.sh` 本身）。在 15:22Z 之前的 `aee716fe1` / `afa90ffd5` 上，这个 job 真的跑到过 `xcx-check.sh`，暴露出的孤立单位问题已经在 `00331811d` 修好 |
| Mini program static checks（汇总） | 红 | 跟着 static gates 一起红 |
| Gameplay HTTP `generic-only`（手动） | 没有跑到测试 | 原因同上：「Install clients and private Redis executable」这一步 apt 失败（`eb272ae74` run 43034778591；`4cf14763e` run 43037545384，attempt 1 到 4 都在这一步失败，attempt 5 写这份文件时还在跑） |

**CI 结论**：除了已接受的 StoreKit 以外，还有两类红没有消除：
- 受信策略里另外 9 个边界的登记，只有你能在 master 上做；
- GitHub runner 解析不了 Ubuntu 源，导致小程序静态门和 Gameplay HTTP 没有真正执行。

所以 #779 的 CI **不算绿**，本线不能报 COMPLETE。等 runner 恢复后，重跑 43046610801 的 failed jobs 和 Gameplay HTTP 就可以补上第二类。

## 五、仍然存在的风险

1. **架构门禁仍然红（Fast gates 的 architecture 步骤 + Backend tests 的 compiled-ports 步骤）。** PR 内部改不了：改 `ci/architecture/*.json` 会被判 POLICY_CHANGE，校验器从受信提交 `c7c5e838` 取规则。
   - `DEPENDENCY_EXPANSION`：StoreKit `app-store-server-library:5.2.0`，你在 11-09 已接受。f-money 核实过，它服务的是工坊苹果内购验付（`apple-iap.enabled`），不是通行证。
   - `NEW_UNOWNED_BOUNDARY` 8 个（**没有被接受**）：`ApiMerchantPayoutAccountController`、`ApiPlatformPassController`、`ApiPlayFeedbackController`、`EcomCombineMapper`、`EcomPayoutMapper`、`PlayHelpAuditMapper`、`PlayRunFeedbackMapper`、`PlayRunFeedbackService`。比 qx-final 那时多了 l-payout3 的 `EcomCombineMapper`。
   - `EXACT_STORAGE_PORT_CONSUMER` 1 个（**没有被接受**，Backend tests 因此红）：`ApiTopicTemplateController` → `AuthorWriteReceiptService`（技术 owner `M_AUTHOR_WRITE_RECEIPT`），来自 staging 底座的 qx-c1 #8「模板采用 /use 接入作者写回执」。它在 `2256f79e`（我开始之前）就已经红，qx-final2 的 RESULT 只写了架构门禁那两类，**漏掉了这一项**。
   - 这些都只能由你在 `master` 的受信策略（`ci/architecture/owners.json`，再更新 `ci.yml` 里校验器钉住的 ref）里另开一个独立审核的 PR 登记，然后让 #779 合上新的 master。
2. **资金：「没有二清」目前不成立**（f-money）。
   - 开关全关时，协作主题、活动票、会费仍然是平台代收、T+14 记余额，这是上线前就有的做法。
   - 目标开关下，核销费、带队费、合单多出来的预留都是先进平台，再由平台付出去。
   - 建议：上线时只对「卖家是唯一第三方收款方」的主题打开电商收付通，`combined-enabled` 保持关闭，等法务定性。
3. **资金路径没有任何真机或沙箱验收**：服务商 JSAPI、合单、分账、回退、电商退款、虚拟支付都没有连过沙箱。25 张 P0 角色卡只跑了后端半边（P0-01 到 P0-22），P0-23 没有真库覆盖，P0-24、P0-25 的卡义没有对上号。
4. **发布清单只做过静态校验**：155 项没有做隔离演练（`release-migration-rehearsal.yml` 钉的是 10-29 包），预检 SQL 也没有在真实 MySQL 上跑过。
5. **游戏路径**：城市定向场次票（缺夹具）和自由探索真实报名（被开售快照闸挡住）这次没有重新走通（f-play）。rig 的资金链路夹具还是按已经停售的四店产品写的（f-money 待你决定 7）。
6. **主包只剩 3.5 KiB 余量**（2044.5 / 2048 KiB）。下一个改主包的需求很可能超限，新代码要放进分包。
7. **同一分支上有两个代理并行改动**（本线和 qx-final2）。这次没有出事，因为每次都先同步、不强推。但 `4cf14763e` 和我本地的修复撞了车，我丢弃了自己那份。
8. **漫游打开动效没有还原**：「之前的版本」只在私有旧仓，按规则不能取（f-pages 待你决定 1）。**33 个目录地点没有坐标**，要等运营核验后填写（f-pages 待你决定 3）。

## 六、还需要真机验的

- 摇一摇的手感（阈值 2.65 g、防抖 900 ms），全屏与嵌入两份不能同时动；弹球的倾斜物理（f-play）。
- 相册整屏打开（修前在真机上的「死循环」没有复现过），通关后回看与选章，进度游标在 25 套外观下的观感（f-play）。
- 城市定向场次票全程、自由探索真实报名全程（f-play 路径 1 和 3）。
- 资金：开关全关和目标开关两种状态下，各用一张小额主题门票真机走完付款 → 结算 → 分账 → 退款 → 对账。通行证的 Android JSAPI 和 iOS 虚拟支付各走一遍（f-money 待你决定 6）。
- 25 张 P0 角色卡的人工 / 真机半边：扫码摄像头、两台真机并发核销、订阅消息到达、注销冷静期（f-money 第五节）。
- 紧急求助：拨号与「复制我的位置」；AI 对话记录导出到剪贴板、清除；附近队伍举报（f-compliance）。
- 商家：核销台 409「待复核」态、经营团队页、搜索与帖文详情的浅色态（包括举报半屏）、店铺参谋的身份同步（f-merchant）。
- 二维码出现动效、主地图横滑卡、据点评价半屏、我的收藏「据点」页签、本人主页发帖入口（f-pages）。
- 全站二维码空白（AUDIT B-1）要在正式版上核对 AppSecret、OSS 和 Redis。

## 七、只有你能做的

1. **架构门禁**：在 `master` 的受信策略里登记上面 9 个边界（8 个 NEW_UNOWNED_BOUNDARY + 1 个 EXACT_STORAGE_PORT_CONSUMER），作为独立审核的 PR。再在 #779 上确认 StoreKit 依赖已接受。
2. **合并 PR #779**、部署后端、上传小程序，都按 PR 说明里 f-release 的 DEPLOY.md 步骤做。合并后用 `gen_release_set.py --head <最终 SHA>` 重算一次。
3. **生产迁移**：155 项走 chengyin-db-migrate 只读预检，再逐项确认。其中 20 项需要业主授权（积分规则 id 6 关闭、id 1 改为 50、3% 抽成、商家默认档位、模板文案、官方模板规则拆步等）。
4. **ACTION-01**：撤销旧微信支付私钥证书。
5. **生产配置**：审核回调 token（F-L5C-08）、腾讯地图 Key、深色地图 subkey、剧情字体 OSS / CORS / downloadFile 合法域名、`ALARM_WEBHOOK`、AIGC 备案号（`aigc.algorithm.filing-no`）、AI 日志留存天数是否与隐私政策一致。
6. **商户平台 / 公众平台**：电商收付通进件、分账、回调地址；虚拟支付 OfferID、AppKey、道具；创作者灵活用工签约。
7. **法务**：合单与转付是否算二清；平台佣金开票与「退款先回退分账」写进商家协议。
8. **开关**：`withdrawal.fast-withdraw.enabled` 保持关闭，直到你定下提现口径。`combined-enabled` 保持关闭，直到法务定性。
9. **线上脏数据**：E-2（玩家账号上的商家行）、E-3 / P-09（测试主题、QA 分群），需要你授权。
10. **各线的「待你决定」**：f-play 3 条、f-pages 3 条、f-merchant 3 条、f-compliance 5 条、f-release 5 条、f-money 7 条，原样保留在各自的 RESULT.md 里。每条都已经按默认实现。

STATUS: PARTIAL - 六条 f-* 修复线全部合入，本地门禁全绿（小程序全量 17288/17288、UI-GATE-0、台账与路由矩阵零差异、mvn、Java 99 类 1021 条 0 失败、管理端、主包 2044.5 KiB、keyframes），8 批真库 IT 全部通过；#779 CI 没有绿：受信架构策略里除了已接受的 StoreKit，还有 8 个 NEW_UNOWNED_BOUNDARY 和 1 个 EXACT_STORAGE_PORT_CONSUMER 要你在 master 登记；小程序静态门与 Gameplay HTTP 因为 GitHub runner 解析不了 Ubuntu 源（14:04Z 起）没有真正执行，需要等 runner 恢复后重跑
