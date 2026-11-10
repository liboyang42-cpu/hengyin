# qx-final · launch 收口 — RESULT(2026-11-10)

分支 `launch/questo-20261110`。七条 lane 都按顺序 `--no-ff` 合入，跑完全部门禁，修了合并和 lane 带来的问题。之后开了草稿 PR **#779**(`launch/questo-20261110` → `master`)，正文是 §七的上线清单。

以下事情都没有做：
- 没有合并到 `master`，PR 没有标为就绪。
- 没有碰生产、VPS 或私有仓。
- 没有改已有的 migration。
- 没有运行 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`。
- 没有新增 UI-integrity baseline 条目，只做了两侧行号重锚的合并。
- 没有提交任何密钥。

## 一、等待与合并记录

我从 14:50 UTC 开始，每 15 分钟检查一次。17:05 时七条 lane 的 RESULT.md 都有了 `STATUS:` 行，所以没有一条 lane 被跳过。

| # | Lane | 合入的远端头 | 合并提交 | STATUS | 冲突与解法 |
|---|---|---|---|---|---|
| 1 | l-play | `6c179c13` | `a9b3228e` | COMPLETE | 无冲突 |
| 2 | l-kits | `143dc8c7` | `63f54146` | COMPLETE | `cy-illus/art.js`：两边各自写了同一个「重复属性去重」函数，保留 l-play 的版本(按 HTML 规则取第一个)，自闭合写法按 l-kits 单测定为 `/>`。动作台账在合并树上重建，`action-ledger.test.js` 的计数按重建实测写，两侧注释都保留 |
| 3 | l-after | `4606214e` | `c989698f` | PARTIAL | `art.js` 又多了第三份去重函数，l-after 的版本取的是最后一个。JS 函数提升会让后定义的那份生效，所以删掉它，统一取第一个。理由：原型在浏览器里就是这么画的，l-play、l-kits、l-pass 三条 lane 也都这么定。三个分包副本(Journey、P3、Member)和规范实现逐字同步。台账照样重建 |
| 4 | l-pass | `ed2e9b5d`，补合 `5446c9bb` | `06db0974`，补合 `3e9266b2` | PARTIAL | l-after 和 l-pass 各自新建了同一页 `subpackageMember/market/library`。合并后**界面用 l-after 的**(俱乐部式卡、筛选、标题不写数量)，**数据用 l-pass 的 `GET /api/platform-pass/library`**(官方开放自玩主题，服务端判定)。`¥0 可玩` 只认服务端的 `covered === true`；通行证开关关闭时显示「通行证暂未开放」。数据映射加在 `subpackageMember/utils/pass-library.js`(`passQuery` / `passLibraryCard` / `passLibraryList`)。「通行证与礼品」里的「游戏主题库」行改为服务端开放通行证(`member`)时才出现。`app.json` 页面取并集；路由矩阵、标题盘点、cy-page-title 计数里同一页只算一次 |
| 5 | l-payout | `a03b4ea4` | `29496034` | PARTIAL | 路由数 169 → 170；标题盘点 170 / custom 169；cy-page-title 91 → 93。`ci/test_real_storage_it.py` 两个批次合计合并为 `marketing-db-acceptance: 236` 加 `launch-l-payout: 9`。迁移依赖文件自动合并 |
| 6 | l-editor | `aa90efd8` | `5e93cda2` | COMPLETE | 只有台账冲突，在合并树重建 |
| 7 | l-misc | `8a741fef` | `a991b10f` | PARTIAL | 队伍详情：保留 l-after 的「邀请队友 → 统一邀请页」，加上 l-misc 的「二维码」按钮(仅在队成员可见)，两个组件都注册。UI baseline：fabu 锚点取 l-editor 的重锚，temp 锚点取 l-misc 的重锚，条数仍是 62，`u4Dynamic keyDigest` 按门禁算法在合并树上重算。台账重建 |

## 一之二、PR 开出后又合入的 l-payout2(不是本 lane 合的)

我开 PR #779 之后，另一个代理(提交人 `Bob (OpenClaw Agent)`)在 18:39 UTC 往 `launch/questo-20261110` 推了合并提交 `69332af6`：「merge: l-payout2 分账接入下单/回调/退款/结算(默认关闭)」。

- **改了什么**：38 个文件，约 3.3k 行。l-payout 的 P-09 已经接入：
  - 下单按卖家二级商户路由；
  - 新增回调端点 `/api/registration/ecommerce-callback`、`/api/registration/ecommerce-refund-callback`；
  - 退款前先回退分账；
  - T+14 入余额时扣掉微信侧的份额；
  - 新增定时任务和运营端页面「资金售后 → 电商收付通」；
  - 新增迁移 `migration_20261110_ecommerce_payout_wiring.sql`。
  - 详细说明见 `l-payout2/RESULT.md`(STATUS: PARTIAL)。
- **我没有回退它**，也没有改它的代码。我在合并后的头 `69332af6` 上复核了：
  - `mvn -q -DskipTests package` 通过；
  - 管理端 `npm run build:prod` 通过；
  - 支付和分账相关 Java 测试共 238 条，0 失败：l-payout2 的特征测试、接线测试、定时任务测试，l-payout 原有单测，回调路由，通行证，退款快照，CoopSettlement；
  - Real Storage IT `launch-l-payout2` **通过**(run 42878206758)；
  - `payment-intent-correctness` 整批 **通过**(run 42878212631)；
  - 迁移台账 33 个新迁移，0 个问题。
  - 另外，run 519 是我误用占位 SHA 触发的，在 SHA 校验步骤失败，没有执行任何测试。
- **顺带修了一条底座既有红**：`GameplaySupportMatrixTest` 期望客户端玩法目录是 32 行加上服务端独有的 `hangman`。但底座里 qx-b2 已经把逐字猜词加进客户端目录(33 行)，测试一直是红的(l-payout2 的 RESULT 也提到了)。我把断言收紧为「客户端目录 33 行，与服务端矩阵完全一致」，37/37 通过。只改了测试。
- **需要你确认**：l-payout2 是否属于这次上线(见待你决定 8)。它的开关全部默认关闭；关闭时，特征测试证明下单、回调、退款、T+14 的结果逐字不变。

## 二、收口修复(小提交)

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机未验 |
|---|---|---|---|---|---|
| QF-1 游戏主题库合并 | 已修 | 见上表第 4 行(在合并提交 `06db0974` 内完成) | `subpackageMember/market/library/*`、`subpackageMember/utils/pass-library.js`、`subpackageMember/market/passes/index.wxml` | l-after-items、l-pass-platform-pass、get-auth-matrix、标题与路由类单测全部通过；rig 实拍持有者、非持有者、自由探索三态，以及从通行证与礼品点进主题库 | 真机封面(rig 的本地封面被媒体审核门清空，显示兜底底图) |
| QF-2 隐私授权弹窗 | 已修 | l-after 的统一邀请页带「保存图片」(`wx.saveImageToPhotosAlbum`)，挂到席位页和队伍详情后，这两页没有 `cy-privacy-gate`，触发授权时会被隐私页整页盖住。按 settle 页的写法补上 gate、`showPrivacyGate`、`onPrivacyGateSettled`。台账：事件合同 +2，控件 5993 → 5995 | `subpackageJourney/pages/seats/*`、`pages/team/detail/*`、动作台账与单测(`2d211991`) | `privacy-gate-coverage-contract` 4/4 通过 | 真机授权弹窗 |
| QF-3 邀请相关测试改钉 | 已修 | `r2-store-lobby` 和 `scene-content-contract` 还钉着旧的「邀请同行」半屏和 `openScene('share-invite')`。按你批准的统一邀请页(proto sh-invite)改为钉 `cy-share-card`；`open-type="share"` 仍在组件里，单测也断言了 | 两个单测文件(`2d211991`) | 两个文件共 22 条全部通过 | — |
| QF-4 延伸任务翻页卡按压反馈 | 已修 | `.xd__c--press` 被 `.xd__c.is-on{opacity:1}` 压过，按下没有反应；门禁也查不到这个类(partial 只读同名样式表)。把规则移到 `play-topbar.wxss`，并提到同等特异度 | `pages/play/play-stage.wxss`、`pages/play/play-topbar.wxss`(`b36aaf03`) | `press-feedback-resolves-contract` 4/4 通过 | 真机按压手感 |
| QF-5 榜单「更多」按压反馈 | 已修(底座既有红) | `rank-board` 是 isolated 组件，取不到全局 `.cy-pressed`，改为在组件内定义(写法同 `components/cy/card`) | `subpackageJourney/components/rank-board/index.wxss`(`b36aaf03`) | 同上 | — |
| QF-6 页面清理清单 | 已修(含底座既有红) | 设计文档的完整页面清单补上 `pages/merchant/payout-account` 和 `subpackageJourney/pages/settle` 两行 | `docs/design/miniprogram-page-cleanup-1009.md`(`b36aaf03`) | `page-cleanup-audit` 2/2 通过 | — |
| QF-7 报名回调真库 IT | 已修(底座既有红，仅测试夹具) | `RegistrationCallbackIntentMySqlIT` 在 `master` 上 14/14 通过，在 staging 底座 `ca1f85b6` 和合并树上都是 13/14 失败。我在本机用真 MySQL 8.0.45 复现后查到两个原因，都只在测试夹具里：① `CmsTopicMapper` 共享 resultMap 已经读 `city_code` / `content_contract_json` 两列，本类没有叠加对应的两个迁移，报 `Unknown column 'city_code'`；② 支付成功事务新增了必选依赖 `marketingRevenuePostingService.enqueueAccrual`，手工组装的服务没有注入，报 NPE。按 `FreeExploreNodeSupplyMySqlIT` 和 `RegistrationFundsMybatisIntegrationTest` 的现成写法补齐。**生产代码没有改**；生产里这个依赖是必选 `@Autowired`，缺 bean 会直接启动失败，所以不会静默跳过 | `RegistrationCallbackIntentMySqlIT.java`(`30cefcb6`) | 本机真库(UTC)14/14 通过；CI 结果见 §四 | — |
| QF-8 截图汇总 | 已做 | 七条 lane 的 rig 截图都复制到 `qx-final/shots/<lane>/`；合并后有变化的屏另外复拍，放在 `qx-final/shots/qx-final/` | 见 §六 | 每张都看过 | — |

## 三、门禁(合并树)

| 门禁 | 结果 |
|---|---|
| 小程序全量单测(仓库根 `node --test chengyinhub-xcx/tests/unit/*.test.js`) | 合完七条后(`a991b10f`)：17212 个测试，17200 通过，12 失败。修复后(`30cefcb6`)：见本节末尾的复跑结果 |
| `node chengyinhub-xcx/tests/lint/ui-integrity.js` | **UI-GATE-0:PASS**(U4 动态 setData 63/63 已登记，没有新增登记) |
| 动作台账 / 路由矩阵 | 每次合并都在合并树上 `build-action-ledger.js --write` 和 `build-route-matrix.js --json` 重建，提交后再重建 **无 diff**。控件 5995，路由 170 |
| `mvn -q -DskipTests package`(`LANG=C.UTF-8`) | 通过 |
| 改动类的 Java 测试 | 改动的 main 类及调用它们的测试类，加上全部新测试，共 221 个类：2136 个测试，0 失败，1 个错误，147 个跳过(需要真库的类在无库时按设计跳过)。唯一的错误是 `MerchantDashboardAggregationMyBatisTest`：它需要真 MySQL，本机没有库，连不上就判失败；这个类在 PR 的 CI(`ci.yml` 的 MyBatis IT job)里跑 |
| 管理端 `npm run build:prod` | 通过(`DONE Build complete`)。用 `npm ci --registry=https://registry.npmjs.org/ --replace-registry-host=registry.npmmirror.com`，lockfile 没改 |
| 主包体积 `mainpkg-size-report.js --json` | `mainTotal` 3,295,637 字节 × 0.633 ≈ **2037.2 KiB < 2048**(底座 2030.7 KiB，lane 合计 +6.5 KiB) |
| keyframes 棘轮 | **本波没有新增 `@keyframes`**：非注释 keyframes 从底座的 169 降到 166(三条 lane 删了旧动画)。上限 146 没有改，测试照样是红的(底座本来就红，是 r2 波次留下的，见待你决定 3) |
| CI「Mini program static gates」(`ci/xcx-check.sh`，按约束本地不跑，只看 CI 日志) | **红**，相对 master 新增 12 个子门禁红灯，见待你决定 9。之前只报了 DS 硬编码一项，漏报了其余各项 |
| 迁移门禁 | `python3 ci/migration_ledger.py manifest`：32 个新迁移全部进入台账，0 个问题(合入 l-payout2 后为 33 个，仍是 0 个问题)；`ci/test_migration_gates.py`、`ci/test_migration_ledger.py` 共 202 条全部通过 |

**全量单测失败明细(`a991b10f`，12 条)及处理：**

- **合并树新出现、已修好(5 条)**：
  - `privacy-gate-coverage-contract`：QF-2；
  - `r2-store-lobby`、`scene-content-contract`：QF-3；
  - `page-cleanup-audit` 和 `press-feedback-resolves-contract` 里新增的那部分：QF-4、QF-6。
  - 这五条在 l-after 自己的分支头上也是红的(已检出 worktree 复跑)，lane 的 RESULT 没有写到。
- **底座既有、顺手修好(2 条)**：`page-cleanup-audit` 的 settle 行、`press-feedback` 的 rank-board(QF-5、QF-6)。
- **底座既有、lane 修好(1 条)**：`theme-look`。
- **底座既有、仍是红的(7 条)**：都属于 r2 波次留下的问题，原样保留，见 qx-s2/RESULT.md §六。
  - `all-page-back-stack-contract`、`component-wxss-selector-lint`(只是行号变了)、`custom-nav-page-surface-contract`、`no-system-actionsheet-contract`、`no-system-toast-contract`、`reduced-motion-coverage-contract` 这六条，失败内容和底座相同或更少，没有新增项。
  - `motion-property-ratchet` 的实测值从 169 降到 166。

修复后在 `30cefcb6` 复跑：**17212 个测试，17205 通过，7 失败**。剩下的 7 条正好是上面「底座既有、仍是红的」那 7 个文件，失败内容和底座相同或更少，没有新增。

## 四、真库 IT 与 Gameplay HTTP E2E(GitHub 托管 runner，手动触发)

| 工作流 / 批次 | 源 SHA | 结果 | 说明 |
|---|---|---|---|
| Real Storage IT `marketing-db-acceptance` / `PlatformPassMySqlIT`，UTC | `a991b10f` | **通过**(run 42868729621) | l-pass 新增的真库类 |
| 同上，Asia/Shanghai | `a991b10f` | **通过**(run 42868740919) | 生产 GMT+8 时区 |
| Real Storage IT `launch-l-payout` / `EcomPayoutMapperMySqlIT`，UTC | `a991b10f` | **通过**(run 42868735311) | l-payout 新增的真库类 |
| Real Storage IT `payment-intent-correctness`(整批 4 类)，UTC | `a991b10f` | 失败：3 类通过；`RegistrationCallbackIntentMySqlIT` 13/14 失败(run 42868746452) | 回调路由和支付单锁有改动，所以加跑了这一批 |
| 同上，只跑 `RegistrationCallbackIntentMySqlIT` | 底座 `ca1f85b6` | 失败，13/14(run 42871814319) | 底座本来就红 |
| 同上 | `master` `000a48f6` | **通过**，14/14(run 42872959956) | 所以是 staging 引入的；原因是测试夹具，见 QF-7 |
| `payment-intent-correctness`(整批)，修复后 | `30cefcb6` | **通过**，30/30(run 42875493336)：WechatPaymentIntent 4/4、RegistrationCallbackIntent 14/14、CashWithdrawTransferIntent 9/9、OWN_C04_21 3/3 | |
| Gameplay HTTP `generic-only` | `a991b10f` | 失败：26 条里 `personalR5DirectParticipationWithoutMerchantIsRefused` 1 条返回 500(run 42868752570) | |
| 同上 | 底座 `ca1f85b6` | 失败，同一条(run 42871824633) | |
| 同上 | `master` `000a48f6` | 失败，同一条(run 42872966299) | **master 本来就红**，不是本 PR 引入的。见待你决定 4 |
| 同上，修复后 | `30cefcb6` | 失败，仍是同一条，26 条里 25 条通过(run 42875499549) | 和 master 一样，不是本 PR 引入的 |

另外：我先手误触发了三次源 SHA 填错的运行(run 513、514、64)，都在 SHA 校验步骤失败，没有执行任何测试，可以忽略。

## 五、合并后有变化的屏(在 rig 里复拍)

rig 用的是 `claude/cloud-walk-20261101-rig`，加了 staging 的 `rig-up-sha-param.diff`、`rig-app-oss-seam.diff`，club 分支的 `harness-patches.diff`，u1 的 `rig-wxs-event-shim.diff`，以及 l-pass 的 `rig-extra-args.diff`(只在 rig 里打开 `platform-pass.enabled` 和 `platform-pass.jsapi-enabled`)。候选是本分支 `b36aaf03` 的 jar 加小程序包，夹具用 l-pass 的 `rig/seed-pass.sql.txt`(只写 rig 测试库)。

- `QF-1-library-holder-after`：持有者，每张卡都有柠檬绿「¥0 可玩」。
- `QF-1-library-nonholder-after`：非持有者，不出价签。
- `QF-1-library-explore-after`：切到「自由探索」。
- `QF-2-passes-shop-after`：通行证与礼品「可购买」分段，通行证卡下面有「游戏主题库」行。
- `QF-2-passes-to-library-after`：从这一行点进主题库。
- 没有拍到：
  - **队伍详情(邀请队友 + 二维码)**：rig 里没有活动场次，而组队只支持活动场次(`/api/team/create` 回「组队仅支持活动场次」)，所以造不出队伍。接线由单测覆盖；l-misc 的 `team-qr-*` 是合并前拍的。
  - **席位页和队伍详情的隐私授权弹窗**：rig 不模拟微信隐私授权。

## 六、截图清单(全部屏，`qx-final/shots/`)

每张都是 390 宽、JPEG q70、rig 实拍，来自各 lane 的 `<lane>/shots/` 原样复制，加上 qx-final 复拍。共 196 张。

### 主题内游玩(l-play) · 38 张

| 屏 | 说明 | 文件 |
|---|---|---|
| `bag` | 背包页 | `l-play/bag-after.jpg`、`l-play/bag-before.jpg` |
| `bag-clues` | 背包 · 线索 | `l-play/bag-clues-after.jpg`、`l-play/bag-clues-before.jpg` |
| `branch` | 岔路弹窗 | `l-play/branch-after.jpg`、`l-play/branch-before.jpg` |
| `branch-entry` | 岔路入口 | `l-play/branch-entry-after.jpg`、`l-play/branch-entry-before.jpg` |
| `ch-end` | 章节结束整屏 | `l-play/ch-end-after.jpg`、`l-play/ch-end-before.jpg` |
| `ch-end-dissolve` | 章节结束 · 粉尘消融 | `l-play/ch-end-dissolve-after.jpg`、`l-play/ch-end-dissolve-before.jpg` |
| `ch-end-next` | 章节结束 · 下一章 | `l-play/ch-end-next-after.jpg`、`l-play/ch-end-next-before.jpg` |
| `ch-start-2` | 章节开始(第二章) | `l-play/ch-start-2-after.jpg`、`l-play/ch-start-2-before.jpg` |
| `ch-start` | 章节开始 · 波点数字 | `l-play/ch-start-after.jpg`、`l-play/ch-start-before.jpg` |
| `ext-2` | 延伸任务翻页卡(第二张) | `l-play/ext-2-after.jpg`、`l-play/ext-2-before.jpg` |
| `ext` | 延伸任务翻页卡 | `l-play/ext-after.jpg`、`l-play/ext-before.jpg` |
| `ext-entry` | 延伸任务入口 | `l-play/ext-entry-after.jpg`、`l-play/ext-entry-before.jpg` |
| `hud` | 故事流顶栏(雷达/血量/背包/进度) | `l-play/hud-after.jpg`、`l-play/hud-before.jpg` |
| `kit-entry` | 玩法入口卡 | `l-play/kit-entry-after.jpg`、`l-play/kit-entry-before.jpg` |
| `kit-start` | 玩法开始弹窗(嵌入/全屏) | `l-play/kit-start-after.jpg`、`l-play/kit-start-before.jpg` |
| `qr` | 入场码(翻面二维码) | `l-play/qr-after.jpg`、`l-play/qr-before.jpg` |
| `qr-front` | 入场码正面 | `l-play/qr-front-after.jpg`、`l-play/qr-front-before.jpg` |
| `run-end` | 本局完成(绿色+插图) | `l-play/run-end-after.jpg`、`l-play/run-end-before.jpg` |
| `station` | 地点卡(整屏照片+玻璃面板) | `l-play/station-after.jpg`、`l-play/station-before.jpg` |

### 玩法组件(l-kits) · 32 张

| 屏 | 说明 | 文件 |
|---|---|---|
| `lk-acc` | 判案主屏 | `l-kits/lk-acc-after.jpg`、`l-kits/lk-acc-before.jpg` |
| `lk-acc-clue` | 判案 · 线索展开 | `l-kits/lk-acc-clue-after.jpg`、`l-kits/lk-acc-clue-before.jpg` |
| `lk-acc-confirm` | 判案 · 确认弹窗 | `l-kits/lk-acc-confirm-after.jpg`、`l-kits/lk-acc-confirm-before.jpg` |
| `lk-acc-failed` | 判案 · 失败插图 | `l-kits/lk-acc-failed-after.jpg`、`l-kits/lk-acc-failed-before.jpg` |
| `lk-acc-flip` | 判案 · 嫌疑人翻页 | `l-kits/lk-acc-flip-after.jpg`、`l-kits/lk-acc-flip-before.jpg`、`l-kits/lk-acc-flip-reduced-after.jpg` |
| `lk-acc-pending` | 判案 · 调查中 | `l-kits/lk-acc-pending-after.jpg`、`l-kits/lk-acc-pending-before.jpg` |
| `lk-acc-solved` | 判案 · 成功插图 | `l-kits/lk-acc-solved-after.jpg`、`l-kits/lk-acc-solved-before.jpg`、`l-kits/lk-acc-solved-reduced-after.jpg` |
| `lk-acc-wrong` | 判案 · 指认错误 | `l-kits/lk-acc-wrong-after.jpg`、`l-kits/lk-acc-wrong-before.jpg` |
| `lk-hang` | 逐字猜词 | `l-kits/lk-hang-after.jpg`、`l-kits/lk-hang-before.jpg` |
| `lk-hang-solved` | 逐字猜词 · 答对 | `l-kits/lk-hang-solved-after.jpg`、`l-kits/lk-hang-solved-before.jpg` |
| `lk-hang-typing` | 逐字猜词 · 系统键盘输入 | `l-kits/lk-hang-typing-after.jpg`、`l-kits/lk-hang-typing-before.jpg`、`l-kits/lk-hang-typing-reduced-after.jpg` |
| `lk-qa-img` | 嵌入图片题 | `l-kits/lk-qa-img-after.jpg`、`l-kits/lk-qa-img-before.jpg` |
| `lk-qa-img-picked` | 图片题 · 已选 | `l-kits/lk-qa-img-picked-after.jpg`、`l-kits/lk-qa-img-picked-before.jpg`、`l-kits/lk-qa-img-picked-reduced-after.jpg` |
| `lk-qa-img-wrong` | 图片题 · 答错 | `l-kits/lk-qa-img-wrong-after.jpg`、`l-kits/lk-qa-img-wrong-before.jpg` |

### 主题外页面(l-after) · 41 张

| 屏 | 说明 | 文件 |
|---|---|---|
| `album-flip-2` | 收集册翻卡(二) | `l-after/album-flip-2-after.jpg`、`l-after/album-flip-2-before.jpg` |
| `album-flip` | 收集册翻卡 | `l-after/album-flip-after.jpg`、`l-after/album-flip-before.jpg` |
| `album-ring-2` | 收集册环装卡堆(二) | `l-after/album-ring-2-after.jpg`、`l-after/album-ring-2-before.jpg` |
| `album-ring` | 收集册环装卡堆 | `l-after/album-ring-after.jpg`、`l-after/album-ring-before.jpg` |
| `invite-me` | 统一邀请页(我的→邀请好友) | `l-after/invite-me-after.jpg` |
| `library` | 游戏主题库(l-after 版) | `l-after/library-after.jpg` |
| `library-free` | 游戏主题库 · ¥0 可玩 | `l-after/library-free-after.jpg` |
| `passes` | 通行证与礼品 | `l-after/passes-after.jpg`、`l-after/passes-before.jpg` |
| `settle-overview` | 结算总览 | `l-after/settle-overview-after.jpg`、`l-after/settle-overview-before.jpg`、`l-after/settle-overview-f1-after.jpg` |
| `settle-rank` | 结算 · 排行榜 | `l-after/settle-rank-after.jpg`、`l-after/settle-rank-before.jpg`、`l-after/settle-rank-f1-after.jpg` |
| `settle-rate` | 结算 · 评价 | `l-after/settle-rate-after.jpg`、`l-after/settle-rate-before.jpg` |
| `settle-rate-error` | 结算 · 评价报错 | `l-after/settle-rate-error-after.jpg`、`l-after/settle-rate-error-before.jpg` |
| `settle-rate-stars` | 结算 · 评价星级 | `l-after/settle-rate-stars-after.jpg` |
| `settle-share` | 结算 · 分享 | `l-after/settle-share-after.jpg`、`l-after/settle-share-before.jpg` |
| `settle-share-story` | 结算 · 分享故事卡 | `l-after/settle-share-story-after.jpg` |
| `wallet` | 票夹 | `l-after/wallet-after.jpg`、`l-after/wallet-before.jpg` |
| `wallet-drag-1` | 票夹翻页拖动 1 | `l-after/wallet-drag-1-after.jpg`、`l-after/wallet-drag-1-before.jpg` |
| `wallet-drag-2` | 票夹翻页拖动 2 | `l-after/wallet-drag-2-after.jpg`、`l-after/wallet-drag-2-before.jpg` |
| `wallet-drag-3` | 票夹翻页拖动 3 | `l-after/wallet-drag-3-after.jpg`、`l-after/wallet-drag-3-before.jpg` |
| `wallet-drag-4` | 票夹翻页拖动 4 | `l-after/wallet-drag-4-after.jpg`、`l-after/wallet-drag-4-before.jpg` |
| `wallet-filter` | 票夹筛选 | `l-after/wallet-filter-after.jpg` |
| `wallet-filter-done` | 票夹筛选 · 已通关 | `l-after/wallet-filter-done-after.jpg` |
| `wallet-flip` | 票夹翻面二维码 | `l-after/wallet-flip-after.jpg`、`l-after/wallet-flip-before.jpg` |

### 通行证(l-pass) · 12 张

| 屏 | 说明 | 文件 |
|---|---|---|
| `PS-0-passes` | 通行证与礼品(入口卡) | `l-pass/PS-0-passes-after.jpg`、`l-pass/PS-0-passes-before.jpg` |
| `PS-0-passes-holder` | 通行证与礼品 · 持有者 | `l-pass/PS-0-passes-holder-after.jpg` |
| `PS-1-pass` | 通行证页(安卓可开通) | `l-pass/PS-1-pass-after.jpg` |
| `PS-1-pass-holder` | 通行证页 · 持有者 | `l-pass/PS-1-pass-holder-after.jpg` |
| `PS-1-pass-ios` | 通行证页 · iOS 未开虚拟支付 | `l-pass/PS-1-pass-ios-after.jpg` |
| `PS-2-failed` | 开通失败(服务端回查未付) | `l-pass/PS-2-failed-after.jpg` |
| `PS-3-success` | 开通成功钥匙动效 | `l-pass/PS-3-success-after.jpg`、`l-pass/PS-3-success-frames.jpg` |
| `PS-4-library` | 游戏主题库(l-pass 版) | `l-pass/PS-4-library-after.jpg` |
| `PS-4-library-explore` | 游戏主题库 · 自由探索 | `l-pass/PS-4-library-explore-after.jpg` |
| `PS-4-library-holder` | 游戏主题库 · 持有者 | `l-pass/PS-4-library-holder-after.jpg` |

### 商家收款账户(l-payout) · 8 张

| 屏 | 说明 | 文件 |
|---|---|---|
| `PA-01` | 商家「更多」菜单(收款账户入口) | `l-payout/PA-01-after.jpg`、`l-payout/PA-01-before.jpg` |
| `PA-02` | 收款账户 · 开关关闭 | `l-payout/PA-02-after.jpg` |
| `PA-03` | 收款账户 · 未开通 | `l-payout/PA-03-after.jpg` |
| `PA-04` | 收款账户 · 审核中 | `l-payout/PA-04-after.jpg` |
| `PA-05` | 收款账户 · 待签约 | `l-payout/PA-05-after.jpg` |
| `PA-06` | 收款账户 · 已开通 | `l-payout/PA-06-after.jpg` |
| `PA-07` | 收款账户 · 已驳回 | `l-payout/PA-07-after.jpg` |

### 主题编辑器(l-editor) · 47 张

| 屏 | 说明 | 文件 |
|---|---|---|
| `ED-01` | 编辑器壳(顶部添加栏+章节卡) | `l-editor/ED-01-after-2.jpg`、`l-editor/ED-01-after-3-end.jpg`、`l-editor/ED-01-after.jpg`、`l-editor/ED-01-before.jpg` |
| `ED-01-empty` | 编辑器 · 空章节 | `l-editor/ED-01-empty-after.jpg`、`l-editor/ED-01-empty-before.jpg` |
| `ED-01-selected` | 编辑器 · 选中块 | `l-editor/ED-01-selected-after.jpg` |
| `ED-02` | 长按拖动块(帧条) | `l-editor/ED-02-after-f1.jpg`、`l-editor/ED-02-after-f2.jpg`、`l-editor/ED-02-after-f3.jpg`、`l-editor/ED-02-after-f4.jpg`、`l-editor/ED-02-after-f5.jpg`、`l-editor/ED-02-after-f6.jpg`、`l-editor/ED-02-after-f7.jpg`、`l-editor/ED-02-after-f8.jpg`、`l-editor/ED-02-strip-after.jpg`、`l-editor/ED-02-strip-before.jpg` |
| `ED-02-drop` | 拖动放下 | `l-editor/ED-02-drop-after.jpg` |
| `ED-03` | 拖入正文拆段(帧条) | `l-editor/ED-03-after-f1.jpg`、`l-editor/ED-03-after-f2.jpg`、`l-editor/ED-03-after-f3.jpg`、`l-editor/ED-03-after-f4.jpg`、`l-editor/ED-03-after-f5.jpg`、`l-editor/ED-03-after-f6.jpg`、`l-editor/ED-03-after-f7.jpg`、`l-editor/ED-03-after-f8.jpg`、`l-editor/ED-03-strip-after.jpg` |
| `ED-03-drop` | 拆段放下 | `l-editor/ED-03-drop-after.jpg` |
| `ED-04-layout` | 图片组版式 | `l-editor/ED-04-layout-after.jpg` |
| `ED-05-sheet` | 分支块 | `l-editor/ED-05-sheet-after.jpg` |
| `ED-07-branch` | 预览 · 分支 | `l-editor/ED-07-branch-after.jpg` |
| `ED-07-chend` | 预览 · 章节结束 | `l-editor/ED-07-chend-after.jpg` |
| `ED-07-chstart` | 预览 · 章节开始 | `l-editor/ED-07-chstart-after.jpg` |
| `ED-07-gend-fail` | 预览 · 玩法结束(失败) | `l-editor/ED-07-gend-fail-after.jpg` |
| `ED-07-gend-ok` | 预览 · 玩法结束(成功) | `l-editor/ED-07-gend-ok-after.jpg` |
| `ED-07-gstart` | 预览 · 玩法开始 | `l-editor/ED-07-gstart-after.jpg` |
| `ED-07-look-cyber-default` | 预览 · 夜城外观 | `l-editor/ED-07-look-cyber-default-after.jpg` |
| `ED-07-look-paper-default` | 预览 · 纸本外观 | `l-editor/ED-07-look-paper-default-after.jpg` |
| `ED-07-look-paper-yellow` | 预览 · 纸本黄 | `l-editor/ED-07-look-paper-yellow-after.jpg` |
| `ED-07-look-stardust-blue` | 预览 · 星尘蓝 | `l-editor/ED-07-look-stardust-blue-after.jpg` |
| `ED-07-look-survival-default` | 预览 · 末世外观 | `l-editor/ED-07-look-survival-default-after.jpg` |
| `ED-07-theme` | 预览 · 主题开始 | `l-editor/ED-07-theme-after.jpg` |
| `ED-08` | 玩法模板列表 | `l-editor/ED-08-after.jpg`、`l-editor/ED-08-before.jpg` |
| `ED-08-node-sheet` | 节点弹窗选地点 | `l-editor/ED-08-node-sheet-after.jpg` |
| `ED-08-selected` | 模板已选 | `l-editor/ED-08-selected-after.jpg` |
| `ED-09` | 减少动态效果 | `l-editor/ED-09-reduced-after.jpg` |

### 第二轮杂项(l-misc) · 13 张

| 屏 | 说明 | 文件 |
|---|---|---|
| `game-picker` | 选择玩法(列表) | `l-misc/game-picker-after-logic.jpg`、`l-misc/game-picker-after.jpg`、`l-misc/game-picker-before.jpg` |
| `join-code-entry` | 加入码输入 | `l-misc/join-code-entry-after.jpg` |
| `join-code-error` | 加入码错误 | `l-misc/join-code-error-after.jpg` |
| `join-code-found` | 加入码找到队伍 | `l-misc/join-code-found-after.jpg` |
| `merchant-repost` | 商家发现页转发 | `l-misc/merchant-repost-after.jpg`、`l-misc/merchant-repost-before.jpg` |
| `my-teams` | 我的队伍 | `l-misc/my-teams-after.jpg`、`l-misc/my-teams-before.jpg` |
| `team-qr` | 队伍二维码半屏 | `l-misc/team-qr-after-sheet.jpg`、`l-misc/team-qr-after.jpg`、`l-misc/team-qr-before.jpg` |

### 合并后复拍(qx-final) · 5 张

| 屏 | 说明 | 文件 |
|---|---|---|
| `QF-1-library-explore` | 合并后游戏主题库 · 自由探索 | `qx-final/QF-1-library-explore-after.jpg` |
| `QF-1-library-holder` | 合并后游戏主题库 · 持有者(¥0 可玩) | `qx-final/QF-1-library-holder-after.jpg` |
| `QF-1-library-nonholder` | 合并后游戏主题库 · 非持有者 | `qx-final/QF-1-library-nonholder-after.jpg` |
| `QF-2-passes-shop` | 合并后通行证与礼品 · 可购买(主题库入口在通行证开放时出现) | `qx-final/QF-2-passes-shop-after.jpg` |
| `QF-2-passes-to-library` | 合并后从通行证与礼品进入主题库 | `qx-final/QF-2-passes-to-library-after.jpg` |

## 七、上线清单(也写在 PR 正文里)

### 迁移(按台账顺序执行，33 个，全部是新文件)

按 `ci/migration_ledger.py` 的拓扑序(依赖声明在 `ci/migration-dependencies.json`)。必须走 chengyin-db-migrate 的只读预检，并经你确认。

| 顺序 | 台账序号 | 文件 |
|---|---|---|
| 1 | 87 | `migration_20261108_play_help_run_quota.sql` |
| 2 | 88 | `migration_20261108_registration_seat_invitation.sql` |
| 3 | 89 | `migration_20261108_topic_content_revision.sql` |
| 4 | 90 | `migration_20261109_app_media_provenance.sql` |
| 5 | 91 | `migration_20261109_play_run_feedback.sql` |
| 6 | 92 | `migration_20261110_ecommerce_payout.sql` |
| 7 | 93 | `migration_20261110_ecommerce_payout_wiring.sql` |
| 8 | 94 | `migration_20261110_platform_pass.sql` |
| 9 | 227 | `migration_marketing_attribution_20261108.sql` |
| 10 | 228 | `migration_marketing_product_20261108.sql` |
| 11 | 229 | `migration_marketing_extra_seat_20261108.sql` |
| 12 | 230 | `migration_marketing_revenue_allocation_20261108.sql` |
| 13 | 231 | `migration_marketing_z_channel_voucher_20261108.sql` |
| 14 | 232 | `migration_marketing_task_registration_20261108.sql` |
| 15 | 364 | `migration_marketing_redemption_constraint_name_20261108.sql` |
| 16 | 365 | `migration_marketing_promotion_20261108.sql` |
| 17 | 382 | `migration_preset_draft_source_20261108.sql` |
| 18 | 393 | `migration_public_content_catalog_20261108.sql` |
| 19 | 394 | `migration_public_content_catalog_index_shape_20261109.sql` |
| 20 | 395 | `migration_public_content_catalog_node_topic_index_20261108.sql` |
| 21 | 428 | `migration_club_host_reservation_20261109.sql` |
| 22 | 528 | `migration_topic_location_contract_20261107.sql` |
| 23 | 570 | `migration_video_bounty_host_tip_20261108.sql` |
| 24 | 571 | `migration_video_bounty_group_verification_20261108.sql` |
| 25 | 599 | `migration_20261109_play_help_audit_permission.sql` |
| 26 | 600 | `migration_20261109_questo_admin_menus.sql` |
| 27 | 626 | `migration_z_channel_voucher_snapshot_20261109.sql` |
| 28 | 627 | `migration_z_marketing_revenue_channel_reverse_kind_20261108.sql` |
| 29 | 628 | `migration_z_marketing_revenue_channel_reverse_kind_index_shape_20261109.sql` |
| 30 | 629 | `migration_z_workshop_apple_iap_20261109.sql` |
| 31 | 630 | `migration_z_workshop_purchase_20261109.sql` |
| 32 | 631 | `migration_z_workshop_paid_review_20261109.sql` |
| 33 | 633 | `migration_zz_questo_collation_align_20261109.sql` |

注意：
- `migration_marketing_task_registration_20261108.sql` 会插入两个 `sys_job`：「渠道退款事实补消费」和「营销收入分配队列消费」，**默认都是暂停状态**。
- `migration_20261109_questo_admin_menus.sql` 和 `migration_20261109_play_help_audit_permission.sql` 会写后台菜单和权限。
- `migration_20261110_platform_pass.sql` 写入的两个套餐默认**不上架**。
- `migration_20261110_ecommerce_payout_wiring.sql`(l-payout2)新增 `ecom_refund` 表、两个**暂停**的定时任务(`ecommercePayoutTask.shareAndRetry()`、`syncOnboarding()`)、后台菜单「资金售后 → 电商收付通」和按钮权限。

### 配置开关(都在部署配置里，默认关闭)

| 配置键 | 默认 | 作用 |
|---|---|---|
| `platform-pass.enabled` | false | 通行证总开关(出售、¥0 报价、领取、游戏主题库) |
| `platform-pass.jsapi-enabled` | false | 安卓 / 开发者工具的 JSAPI 渠道 |
| `platform-pass.xpay.enabled` | false | iOS 小程序虚拟支付渠道 |
| `platform-pass.xpay.offer-id` | 空 | 虚拟支付 OfferID |
| `platform-pass.xpay.app-key` | 空 | **密钥，只能经环境变量注入** |
| `platform-pass.xpay.env` | 1(沙箱) | 0 是现网 |
| `payout.ecommerce.enabled` | false | 电商收付通总开关 |
| `payout.ecommerce.seller-routing-enabled` | false | 主题门票按卖家二级商户 `sub_mchid` 下单(需要先打开总开关；l-payout2 已接入) |
| `payout.ecommerce.profit-sharing-enabled` | false | 按账本分账 |
| `payout.ecommerce.sp-mchid` / `sp-appid` / `platform-receiver-mchid` | 空 | 电商平台商户号、AppID、佣金接收方 |
| `payout.ecommerce.max-share-percent` | 30 | 分账比例上限，以微信核定为准 |
| `payout.ecommerce.private-key-path` / `merchant-serial-number` / `api-v3-key` | 空 | **密钥类，只放服务器** |
| `payout.ecommerce.notify-url` / `refund-notify-url` | 空 | 指向 `/prod-api/api/registration/ecommerce-callback` 和 `/ecommerce-refund-callback` |
| `payout.creator.enabled` / `payout.creator.provider` | false / none | 创作者灵活用工打款 |
| `marketing.product.cash-enabled` | false | 营销商品现金收款(底座既有) |
| `workshop.wechat-app-enabled` | false | 工坊 App 微信支付(底座既有) |
| `workshop.apple-iap.enabled` | false | 工坊 Apple IAP(底座既有) |
| `registration.seat-invitation-ttl-hours` | 168 | 席位邀请有效期 |
| `cy.slo.*` | 见代码默认 | 只读 SLO 阈值 |

### 你需要在商户平台和公众平台做的事

1. **通行证 · 微信支付 JSAPI**：沿用现有商户号和回调地址；确认经营类目能卖「会员 / 虚拟服务」。
2. **通行证 · iOS 虚拟支付**：
   - 在公众平台开通虚拟支付(企业主体、已认证、签约、填结算信息)；
   - 记下 OfferID、沙箱 AppKey 和现网 AppKey(AppKey 只放环境变量)；
   - 在「道具管理」建两个道具直购商品：年卡 29900 分、月卡 3900 分，**道具价格必须和套餐价一致**；
   - 把道具 ID 回填到 `platform_pass_plan.xpay_product_id`，再把套餐上架(走 chengyin-db-migrate)；
   - 确认发货和退款消息推送走现有的 `/api/content-sec/callback`(明文 JSON)；
   - 先用沙箱(`env=1`)联调，再切到现网；
   - 确认 iOS 分成和手续费；
   - 确认服务类目允许卖虚拟会员，用户协议写明「不自动续费、期间内官方主题 ¥0、领取过主题不能整单退」；
   - 给管理员角色授 `business:platformPass:refund`。
3. **电商收付通(商家 / 俱乐部收款，不走二清)**：
   - 以公司主体申请成为「电商平台」并开通电商收付通(营业执照、法人证件、对公账户、已上线小程序、ICP 备案、平台规则和入驻协议)；
   - 关联小程序 AppID；
   - 申请 API 证书，设置 APIv3 密钥(只放服务器)；
   - 开通分账，确认比例上限；
   - 准备两个公网 https 回调地址；
   - 由运营在后台提交每个商家 / 俱乐部的进件，商家超级管理员按「收款账户」页复制的链接完成验证和签约。
4. **创作者打款**：选一家持牌灵活用工平台签约，拿到 API 后实现 `CreatorPayoutProvider`。
5. **法务和税务**：平台佣金由平台开票，二级商户自行开票；「退款先回退分账」写进商家协议。
6. **队伍小程序码**：码固定落到 `release` 版，**新版发布上线后才能扫进加入页**。

### 开发者工具上传(沿用 `docs/delivery/release-closeout-20261028/release-steps.md` 的顺序：先部署后端，再上传体验版)

1. **后端**：
   - 先触发 `.github/workflows/release-migration-rehearsal.yml`，在隔离库里按上表顺序演练全部迁移；
   - 再用 `.github/workflows/backend-jar.yml` 以 PR 合并后的最终 SHA 出 jar；
   - 你在生产执行迁移(只读预检加确认)，然后部署 jar，做健康检查。
   - 这几步都由你操作，每一步留一份独立回执。
2. **导出**：从**最终公开树**干净检出同一个 SHA，只用 `chengyinhub-xcx/` 目录；核对 `app.json` 没有本地改动。AppID 和 API 目标在你的私有环境里配，不写进仓库。
3. **开发者工具**：
   - 打开 `chengyinhub-xcx/`，保持 `project.config.json` 的现有设置(ES6 转 ES5、代码压缩、增强编译、WXSS / WXML 压缩、上传 sourcemap)，不要另外勾选「不校验合法域名」；
   - 先「编译」，用真机预览走一遍：主题游玩、判案、结算、票夹、通行证页(开关关闭时不出现)、编辑器拖动、队伍二维码。
4. **上传**：版本号按现有规则递增，备注写这次的 SHA。上传后在「代码质量」里确认主包小于 2 MB(本地估算 2037.2 KiB)。
5. **体验版和提审**：在公众平台设为体验版，验收后再提交审核。如果开了虚拟支付，审核说明里写清「虚拟支付只在 iOS、通行证不自动续费」。

### 回滚

- **后端**：重新部署上一版 jar。33 个迁移都是新文件，以新增表、列、索引、菜单、暂停的任务为主。回滚 jar 前，用 chengyin-db-migrate 只读核对各迁移文件头写的「旧 jar 兼容」说明。不要删表，也不要删列(已写入的订单和分账记录要留着对账)。
- **出问题时**：先把 `platform-pass.*`、`payout.*` 关掉。
- **通行证已售订单**：用后台整单退款(只限没领取过主题的)。
- **小程序**：在公众平台把线上版本回退到上一版。

## 待你决定(括号里是已经实现的默认)

1. **游戏主题库的数据来源**：(用 l-pass 的通行证接口，只列官方开放自玩主题；通行证开关关闭时，通行证与礼品里不出现入口，直接打开页面显示「通行证暂未开放」。)如果你想让主题库在通行证关闭时也能当公开目录用，可以改回 l-after 的 `/api/public-catalog/list`，`pass-library.js` 里两种映射都留着。
2. **`cy-illus` 重复属性取第一个还是最后一个**：(取第一个，和原型在浏览器里实际看到的一致。)l-after 原来按最后一个实现，已经统一。
3. **keyframes 上限 146 和实测 166**：(上限不动，本波没有新增。)红灯由 r2 波次 lane 改成 transition 后消除。
4. **Gameplay HTTP `personalR5DirectParticipationWithoutMerchantIsRefused` 返回 500**：`master` 上也是红的，不是本 PR 引入的。(没有在本 PR 里修；这个用例的 owner 是玩法 HTTP 原线。)
5. **底座里的 r3-visual / r3-share / r3-pass**(qx-base 待你决定 1)：(保留，不回退；各 lane 已按原型覆盖同一元素。)
6. **l-payout 的 P-09**：PR 开出后已由 l-payout2 接入(见 §一之二)。开关全部默认关闭。打开顺序和微信支付侧的步骤见 `l-payout2/RESULT.md` 第四、五节。
7. **各 lane 自己的待你决定**原样保留，见 `l-play/`、`l-kits/`、`l-after/`、`l-pass/`、`l-payout/`、`l-editor/`、`l-misc/` 各自的 RESULT.md。
8. **l-payout2 是否属于这次上线**：它是 PR 开出后由另一个代理合入的，不在 LAUNCH.md 的 lane 列表里。(默认保留，开关全部关闭；我已复核构建、测试和真库 IT。)如果不要，在 PR 分支上 `git revert -m 1 69332af6` 即可，不用改历史。
9. **小程序静态门(CI `Mini program static gates`)相对 master 新增的红灯**：(本 PR 没有改；草稿 PR #780 改了其中多数涉及的文件，合入后要再看一次 CI。)以 `846dbe80` 的 CI 日志为准：
   - DS 硬编码：16 个文件新增字号 / 圆角 / 颜色字面量；
   - 组件 WXSS tag 选择器：rank-board 2 处(#780 已改)；
   - 孤立单位：10 处 `{{x}}` 后紧跟单位没有 `||` 兜底(商家评价、编辑器、邀请记录、rank-board、订单详情、会员、通行证列表)；
   - 分包互引：`subpackageMember/utils/pass-library.js` 引了 `pages/topic/utils/public-catalog.js`(#780 复制了一份到分包内)；
   - 死数据字段 U4：新增 13 个(cy-illus 的 label、share-card、seats、membership、catalog 等)；
   - 无障碍：album-intro 1 处无名点击区；share-card 色块、编辑器 2 处命中区小于 44px；
   - 固定底栏缺背景：8 处(指认、游玩帮助、舞台弹层、编辑器工具条、目录详情底栏、结算弹层、礼品底栏)；
   - 截图矩阵和覆盖率：14 条新路由没有 shot-matrix 行(视频悬赏、主题目录 2 页、收款账户、通行证 / 礼品 / 会员 / 主题库 5 页、旅程 report / landing / seats / promo / settle)；
   - UI 有效性：U3 新增 7 个孤儿 handler(play 的 onHelpSkipped、编辑器 6 个拖拽 / 点按 handler)，U6 新增 10 处裸错误文案(编辑器玩法件、视频悬赏、landing、seats、settle、会员)。

STATUS: PARTIAL - 七条 lane 都已合入，收口修复已推送。UI-GATE-0、台账和路由矩阵零差异、mvn 打包、改动类 Java 测试、管理端构建、主包 2037.2 KiB 都通过。真库 IT 通过：PlatformPass(UTC 与 Asia/Shanghai)、launch-l-payout、payment-intent-correctness 30/30。草稿 PR #779 已开。PR 开出后另一个代理合入了 l-payout2，我已在 69332af6 上复核：构建、支付 Java 测试、launch-l-payout2 和 payment-intent 真库 IT 都通过。还剩四项：CI 小程序静态门相对 master 新增 12 个子门禁红灯(见待你决定 9)；小程序全量单测 7 条红灯，都是底座既有、r2 波次留下的；Gameplay HTTP generic-only 1 条失败，master 上同样失败；队伍详情没有在合并后复拍(rig 没有活动场次夹具)。
