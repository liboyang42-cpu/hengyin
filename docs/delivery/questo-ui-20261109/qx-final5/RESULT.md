# qx-final5 · 合入 f-play2（#795，含 f-money2 #792、f-money3 #794）到 launch

分支 `launch/questo-20261110`。本线负责这次合入；qx-final4 同时在这条分支上推送。每次推送前都先抓远端，远端动了就重新合并，没有强推。

**没有做的事**：PR #779 保持草稿，没有合进 master，也没有标 ready。没有碰生产，没有改已有迁移，没有新增迁移，没有改 `ci/architecture/*` 和 `.github/workflows/ci.yml`。没有运行 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`。没有提交密钥。

依据：`qx-final3`、`f-play2`、`f-money2`、`f-money3` 四份 RESULT.md。

## 一、合并记录

| 顺序 | 动作 | 提交 | 说明 |
|---|---|---|---|
| 1 | 第一次试合 `e9febffe`（#795 当时的头） | 未提交，已放弃 | 只有 `ui-integrity-baseline.js` 冲突（U4-DYNAMIC 和 U5 的 digest 加注释）。两边注释都保留，digest 按门禁 `baselineKeyDigest` 在合并树上重算，得到 `c0361fca…` / `72869805…`；各项条数与 launch 一致（u1 9、u3 55、u4Debt 99、u4Dynamic 62、u5 42），没有新增登记。全部门禁在这棵树上跑完（见第二节） |
| 2 | 改合 `757429f5`（#795 最新头） | `b0f6a04f`（`--no-ff`） | f-play2 线已经把 launch `17de18de` 合进了 #795，冲突也按同样方式解决，算出的 digest 与第 1 步一字不差，两棵树只差三行基线注释。所以改用 #795 头，零冲突，合并树与 #795 头一致 |
| 3 | 推送前抓远端，发现远端已前进 | `25b8ad03`（合并远端，已推送） | 远端多了 qx-final2 的 RESULT 定稿 `97a52227`，以及 qx-final4 合入的 l-iconsB（`19c8b33e`、`5f5084b9`）。和 `b0f6a04f` 相比只差 `qx-final2/RESULT.md` 一个文件，门禁结论不受影响 |
| 4 | 真库 IT `launch-f-money3` 红 | — | `FreeExploreSelfPlayPriceGateMySqlIT` 4 条里 1 条报 error：`lifecycle` 是 `tinyint(1)`，Connector/J 读回来是 Boolean，测试强转成 Number 时抛 ClassCastException。问题只在测试代码，生产代码没问题 |
| 5 | 测试修复 | `865b6992`（qx-final4 推送） | 我本地也写了等价修复，推送前抓远端发现 qx-final4 已经推了同一处（用 `CAST(lifecycle AS SIGNED)`）。我丢弃了自己的提交，用对方的版本，并在这个头上重新派发 IT |

合进来的内容：

- **f-play2 F2-1**：游玩工具菜单里，队长的五种行提到最前，队员停在「等待放行」时，队长打开菜单就能点到「解锁下一章节」。
- **f-play2 夹具**：新增单测 `f-play2-20261111.test.js`，另有 rig 夹具、脚本和帧带。
- **f-money2**：
  - 合单开关关闭时，二级商户之间的分账在提交前失败关闭，转人工处理（原因码 `SUB_MERCHANT_SHARE_UNSUPPORTED`）。
  - 管理端失败重试页加了原因文案。
  - 新增 P0-23 真库 IT，`p0-acceptance-ac1` 由 13 条变为 17 条。
  - rig 资金链路夹具。
- **f-money3**：
  - 新增 `FreeExploreSelfPlayPricePolicy`。自由探索主题设了收费自玩票时，创建、更新、发布检查、锁价、开售都失败关闭。
  - 定价页在栏位下方显示红字，确认按钮置灰。
  - 新批次 `launch-f-money3`。
- **台账与登记**：
  - 动作台账：合并后重建，与 #795 头一致。
  - 真库 IT 登记（`ci/real-storage-it-registry.json`、`ci/test_real_storage_it.py`、`real-storage-it.yml` 的批次选项、`ci/backend-test.sh`）：取并集。
  - 迁移：无新增，迁移依赖登记不需要改。

## 二、门禁

除特别注明外，下表都在合并树上跑（`b0f6a04f` 及其等价树）。

| 门禁 | 结果 |
|---|---|
| 小程序全量单测（仓库根 `node --test chengyinhub-xcx/tests/unit/*.test.js`） | **17293 / 17293 通过，0 失败** |
| `node tests/lint/ui-integrity.js --warn-as-error` | **UI-GATE-0:PASS**，selftest PASS。没有新增基线登记，条数不变 |
| 动作台账 `build-action-ledger.js --write` / `--check` | 重建后**零差异**，控件 6022；`action-ledger.test.js` 通过 |
| 路由矩阵 `build-route-matrix.js --json` | 重建后**零差异** |
| 危险动作门禁 `npm run lint:danger-confirm` | PASS，53 个调用点，38 个不可逆动作全部有确认 |
| 危险动作门禁自证 `npm run lint:danger-confirm:selftest` | 自证通过 |
| keyframes 棘轮 | 通过（`motion-property-ratchet` 等动效合同在全量里）。本次 wxss 新增 `@keyframes` 为 0 |
| 主包体积 `mainpkg-size-report.js --json` | `mainTotal` **3,307,305 字节**，与 qx-final3 完全相同；按同一系数 ×0.633 ≈ **2044.5 KiB < 2048 KiB**。本次改动都在分包（`pages/play`、`pages/publish`、`pages/topic`），主包一个字节都没变，不需要挪代码 |
| `mvn -q -DskipTests package` | 通过。第一次因为容器 locale 不是 UTF-8，读不了中文文件名，编译报错；设 `LANG=C.UTF-8` 后通过。这是环境问题，与代码无关 |
| 改动类 Java 测试 | 范围是 162 个测试类：改动的测试类，加上引用了改动 main 类（`PricingResult`、`PublicationProblem`、`EcommerceProfitShareService`、`ProfitSharePlanner`、`CmsTopicServiceImpl`、`PricingServiceImpl`、`TopicSaleFinalizationServiceImpl`、`FreeExploreSelfPlayPricePolicy`、`TopicPublicationContentGuard`）的全部测试类。共 1618 条，19 条跳过。失败 47、错误 23，**全部出在 14 个需要真库的类**（本机没有 MySQL，日志是 Communications link failure / Connection refused），其他类 0 失败。改动最大的类在下面的真库 IT 里跑了 |
| `python3 -m unittest ci.test_real_storage_it` | 92 条 OK |

### 真库 IT（Real Storage IT，UTC）

| 批次 | 类 | 源 | run | 结果 |
|---|---|---|---|---|
| `p0-acceptance-ac1` | 17 条，含 `P0FreeExploreSupplyRecoveryMySqlIT`（f-money2 新增） | `25b8ad03` | 43054254411 | **通过** |
| `launch-l-payout` | `EcomPayoutMapperMySqlIT`（f-money2 改过） | `25b8ad03` | 43054260865 | **通过** |
| `launch-l-payout2` | `EcomPayoutWiringMySqlIT` | `25b8ad03` | 43054266050 | **通过** |
| `launch-f-money3` | `FreeExploreSelfPlayPriceGateMySqlIT` | `25b8ad03` | 43054246802 | **红**：4 条里 1 条 error，原因是 `tinyint(1)` 读成 Boolean，只是测试读法问题 |
| `launch-f-money3`（重跑） | 同上，4 条 | `865b6992` | 43055250851 | **通过** |

## 三、PR 处理

- **#792（f-money2）、#794（f-money3）**：各写了一条中文说明，然后按「经 #795 合入」关闭，没有单独合并。
- **#795**：已在 PR 下留言。它的头 `757429f5` 已经是 launch 的祖先，GitHub 会自动显示为已合入。没有标 ready。
- **#779**：保持草稿，没有动。

## 四、CI 说明（按指示，只记录不修）

- GitHub runner 从 11-11 14:04Z 起解析不了 Ubuntu apt 源。
  - 受影响的 job：Mini program static gates、Gameplay HTTP、后端 strace 相关步骤，它们可能因为基础设施失败。
  - 上面这些 job 里的子门禁，我在本地跑过的有危险动作、UI-GATE-0、台账、路由矩阵，都通过；`xcx-check.sh` 按规定没有在本地跑。
- 架构门禁（Fast gates 的 architecture 步骤、Backend tests 的 compiled-ports 步骤）由 master 上的策略 PR #791 处理，本线没有改 `ci/architecture/*` 或 `ci.yml`。

## 五、剩余风险

1. **主包余量只有 3.5 KiB**（2044.5 / 2048）。本次没有吃掉余量，但下一个改主包的需求很可能超限，新代码应放进分包。
2. **同一分支有多个代理并行推送。** 这次又撞了一次：tinyint 的测试修复和 qx-final4 撞车，我丢弃了自己那份。推送前一律先抓远端，没有强推。
3. **资金没有沙箱或真机验收**（f-money2）：「没有二清」目前不成立，`combined-enabled` 要保持关闭，等法务定性。场次结算两套比例不一致、会费退款只建审核单，这些仍待你决定。
4. **自由探索存量违规行**（f-money3）：要先在生产只读账号上跑 f-money3 RESULT 第三节的预检 SQL，再决定怎么处置。闸只拦新增和编辑，不改存量数据。
5. **rig 夹具**：f-money2 的「自由探索门票 39.90」夹具直接写了 `self_play_price`。合入 f-money3 后，这类主题无法开售也无法编辑，rig owner 要把它改成门票价。
6. **真机待验**：
   - 自由探索路径，拍照凭证交齐后通关卡是否弹出（f-play2 待决定 1）。
   - 队长菜单的新顺序（F2-1）。
   - 真机支付面板、iris 转场时长。
7. **架构门禁仍然红**：8 个 `NEW_UNOWNED_BOUNDARY` 和 1 个 `EXACT_STORAGE_PORT_CONSUMER`，要等 #791 在 master 上登记，再让 #779 同步。

STATUS: COMPLETE - f-play2（#795，含 f-money2 #792、f-money3 #794）已经 `--no-ff` 合入 launch 并推送（`b0f6a04f` → `25b8ad03`，测试修复 `865b6992` 由 qx-final4 推送）；本地门禁全绿：小程序 17293/17293、UI-GATE-0:PASS、台账与路由矩阵零差异、危险动作门禁及自证、keyframes、mvn package、改动类 Java 测试中非真库类 0 失败；主包 3,307,305 字节 ≈ 2044.5 KiB，没有变化；真库 IT：p0-acceptance-ac1、launch-l-payout、launch-l-payout2 通过，launch-f-money3 首跑因测试读 tinyint(1) 失败，`865b6992` 修复后重跑通过（run 43055250851），四批全部通过；#792、#794 已关闭。CI 里 apt 源（基础设施）和架构门禁（由 #791 负责）的红照旧，本线按指示只记录
