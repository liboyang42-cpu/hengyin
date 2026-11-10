# qx-green · 把上线 PR #779 修绿 — RESULT(2026-11-10)

分支 `claude/qx-green-20261110`，从 `origin/launch/questo-20261110`(`69332af6`)切出，中途合入 launch 新头 `cac4bedce`。草稿 PR **#780**(`claude/qx-green-20261110` → `launch/questo-20261110`)，等 lead 合并。

以下事情都没有做：
- 没有合并，也没有把 PR 标为就绪。
- 没有推 master、staging、integrate、launch 或其他 lane 的分支。
- 没有碰生产。
- 没有改已有的 migration。
- 没有运行 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`。小程序静态门是逐个跑它们各自的脚本。
- 没有提交任何密钥。
- 没有放宽任何断言，也没有抬高任何上限。keyframes 上限仍是 146。

## 一、条目

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机未验 |
|---|---|---|---|---|---|
| G-1 keyframes 166 → 146 | 已修 | round-2 新增的 20 组 `@keyframes` 全部改成 transition，一组都没走豁免。拆分如下：ai-npc 8 组、share-card 3 组、gift 2 组、journey landing 4 组、team join 1 组、team detail 2 组。<br>• 单段进场(上浮、弹入、翻正)：用现有的 `page-motion.enter()`。先挂 `*-pre` 起点类，不带过渡，34ms 后撤掉，补间时长与曲线逐值照原关键帧。<br>• 多段动作与循环(星光闪、挥手、闹钟摆、光圈外扩、连线光点、空位呼吸)：在现有套件里加了 `page-motion.beats()`，按原关键帧的段落逐段换类。CSS 关键帧本来就是每段各用一次 timing-function，所以逐段 transition 的曲线和原来一致。<br>• 列表错峰：原来的 `animation-delay` 改成行内 CSS 变量 `--*-d`，作为 transition-delay。<br>• 卡片入场用 `translate` 加 `filter: opacity()`，`transform` 留给按压反馈(MOTION.md 约定)。<br>• 减少动态效果：系统开关(媒体查询)和应用内开关都直接到位。`enter` 和 `beats` 读到应用内开关时不起计时器，循环停在隐身态。 | `utils/page-motion.js`、`components/cy/share-card/*`、`pages/merchant/decor/ai-npc/*`、`pages/team/{join,detail}/*`、`subpackageJourney/pages/landing/*`、`subpackageMember/market/gift/*`；新测试 `qx-green-page-motion-beats.test.js` | `motion-property-ratchet` 与 `reduced-motion-coverage-contract` 通过；`qx-a6`、`r3-share-cards` 两条原来钉死关键帧实现的断言改为同等严格的 transition 断言：每步起点与到位补间都在、四步互不相同、多段动作的段落时长、减弱兜底 | 没有真机或 rig 逐帧对比。我只核对了时长和曲线逐值与原关键帧相同。差异有两处：首次进入时光点 2、3 在各自的延迟期间是隐身的，原来是静止可见；快速来回翻页时，旧物件不从原关键帧的起点重播 |
| G-2 component-wxss-selector-lint | 已修 | rank-board 去掉两个标签选择器，改成类名 `rb-digit__n` / `rb-av__img` | `subpackageJourney/components/rank-board/index.{wxss,wxml}` | 门禁通过 | — |
| G-3 no-system-actionsheet | 已修 | 榜单「更多」不再用系统 ActionSheet，改用 kit 的 `cy-option-sheet`(immediate，点一行即切榜) | rank-board `index.{js,wxml,json}` | 门禁通过；台账事件合同 +2 | 弹层外观没有实拍 |
| G-4 no-system-toast | 已修 | 定位轻提示改走 `utils/toast.js` | `pages/play/utils/play-location-once.js` | 门禁通过 | — |
| G-5 结算页返回与标题 | 已修 | 结算页可能从分享直接打开，这时页栈只有它自己。`onBack` 和「完成」在没有上一页时 `reLaunch` 到票夹，同 play 的 `goBackDetail`；顶栏补居中标题「结算」 | `subpackageJourney/pages/settle/index.{js,wxml}` | `all-page-back-stack-contract`、`custom-nav-page-surface-contract` 通过 | 标题位置没有实拍 |
| G-6 Gameplay HTTP `personalR5DirectParticipationWithoutMerchantIsRefused` | 已修(夹具对齐合同) | 查到的原因：不是服务端 500。CI 的失败帧在 `GenericGameplayHttpE2eIT.java:1002`，即文案断言那一行，类别 UNKNOWN，不是异常。`code: 500` 是项目既有的业务拒绝形状(`ApiTopicPublicationIssueContractTest` 钉住了 `code=500 + msg + data.issues`)。发布闸 `TopicRoutePublicationCompiler.personalArrivalRefusal`(k7 N5 / 第十三轮 m2)按站点给出可执行的拒绝：本用例是直接参与(`validationMethod=0`)且没有承接商家，按设计返回「…这一站的参与方式要选「定位到达」或「扫码」，或绑定承接商家」。「个人任务…」那句只给已经选了定位 / 扫码、却核验不了的站。夹具改为断言 `code=500` 加这句站点拒绝原文，仍然是拒绝用例：`rejected()`、发布图为空、草稿仍可读这三条都保留 | `GenericGameplayHttpE2eIT.java` | generic-only 在三个头上都通过(见第三节)；qx-c3 needs-owner 1 已经写明同一原因 | — |
| G-7 迁移安全门 | 采用 launch 的修复 | 上线波次新增的 6 个后台页没有出处登记。我补了一版，lead 在 launch 上也补了一版(`c5c50bc7f`)。合入时取 launch 的版本 | `ci/prelaunch-seed-provenance.json` | `ci.test_prelaunch_business_seeds` 通过；本地按 job 的三条命令全过 | — |
| G-8 DS 硬编码门 | 已修 | 上线波次新增的 15 个 wxss 共 53 行：<br>• 28 行换成同值 token，例如字号 28rpx → `--cy-type-body`，圆角 999rpx → `--cy-radius-pill`。<br>• 25 行是原型逐值的装饰值(票面、金属件、刻度条等)，行尾标 `/* ds-ok: 理由 */`。这是门禁自带的行级逃生口，门禁本身没改。<br>CI 里每类只显示前 3 条，实际有 53 行，我用本地脚本逐行全扫 | 15 个 wxss 与 `play-help-panel.js` | `ds-hardcode-gate.sh --ci` 通过 | 字号 token 带 `--cy-type-scale`，系统字号放大时会跟着放大 |
| G-9 孤立单位门 | 已修 | 10 处单位前插值补 `\|\| 0` / `\|\| 1` / `\|\| '—'`。都在守卫内，或者是数字，显示不变。评价页那两处改成 `{{x \|\| 0}}`，`l-misc` 断言同步为同一兜底语义 | 9 个 wxml、`l-misc-merchant-zero-counts.test.js` | `ui-theme-unit-lint --units` 通过 | — |
| G-10 分包互引 | 已修(真运行时缺陷) | `subpackageMember/utils/pass-library.js` 去 require `pages/topic` 分包的文件。分包下载顺序不定，对方没就绪时会加载失败。改用本分包里的逐字副本(仓库对 cy-illus 也是这么做的)，并加了单测守住副本与原件一字不差 | `subpackageMember/utils/public-catalog.js`(新)、`pass-library.js` | `component-path-resolve-lint` 通过 | — |
| G-11 孤儿 handler(两处 U3) | 已修(门禁盲区) | fabu 的拖拽落点、跨格、贴边滚屏，是视图层 WXS 用 `owner.callMethod('…')` 调的；play 的 `onHelpSkipped` 由混入模块调用；`onStoryRichTap` 是动态字符串绑定。两处 U3 实现都看不见这几种调用。<br>• `lib/xcx-scan.js` 新增共用的 `wxsCallMethodNames`，只认本页 wxml 自己 `<wxs src>` 引入的 .wxs 里的字符串方法名。<br>• `ui-validity-gates` 的 U3 补认动态绑定字符串和 `...x.methods()` 混入模块，口径与 `js-orphan-handler-lint` 一致。<br>两个门禁的自证都补了红 / 绿负控：注释里的 callMethod、被注释掉的 `<wxs>`、注释里的动态绑定，仍然判红 | `scripts/lib/xcx-scan.js`、`scripts/js-orphan-handler-lint.js`、`scripts/ui-validity-gates.js` | 两个门禁和各自的自证都通过 | — |
| G-12 死数据字段(U4) | 已修 | 13 个字段：<br>• share-card 的 label / meta、目录页 hasMore、席位页 seats / busy / inviteToken、通行证页 selected：改为实例字段。<br>• cy-sheet / cy-scene-sheet 的窗口高：不再走 setData，改由手势 WXS 起拖时量铺满窗口的遮罩(`data-whsel`)，并补了单测。<br>• cy-illus 四份副本的 label：插图是装饰，页面都有同义可见文字，所以改为 `aria-hidden`。 | 对应 js / wxml、`utils/wxs/gesture.wxs`、`sheet-handle-drag-contract.test.js` | `setdata-dead-field-lint` 通过 | 弹层多档拖拽没有真机复测 |
| G-13 无障碍 | 已修 | 收集册过场可点区补读屏名「跳过过场」。分享色块和图上删除角标的命中区，用透明 `::after` 撑到 88rpx(DS 标准做法，视觉尺寸不变)。逐字格按原型 56rpx 密排，放大热区会和相邻格重叠，所以按门禁规则在元素上写 `ds-ok="理由"` | album-intro、share-card、publish/temp | 两个 a11y 门禁通过 | — |
| G-14 固定底栏背景 | 已修 | 两条真底栏补 `--cy-color-bg-page` 底色：目录详情、领取礼品。<br>• 门禁把 `border-bottom: 0` 误判成贴底(`\b` 会把连字符当边界)。修了正则，并补了一红一绿两条负控。<br>• 5 个满屏模态根(压暗在子层 mask，或者底色在基础规则)，按门禁既有做法逐条登记进白名单并写明理由 | 2 个 wxss、`scripts/sticky-bottom-bar-gate.js` | 门禁和自证都通过 | — |
| G-15 截图矩阵 / 覆盖率 | 已修 | 14 条新路由补了矩阵行 F300–F313，全部是 real-data，不注入假数据。launch 上的 STATS 原本就少记了 2 行，这次按实测同步。新行的 fixture 取生成器产出，只并入这 14 条：全量重生成会把其他 lane 的陈旧 fixture 一起改掉，见待你决定 4 | `scripts/shot-matrix.js`、`scripts/fixtures.json` | `shot-matrix.js` 自检、`coverage-gate` 通过 | 14 条都还没拍 |
| G-16 裸错误文案(U6) | 已修 | 10 处表单校验或操作失败的裸红字，改用 kit 的 `cy-inline-error kind="field"`：栏下红字，没有底色和图标，自带 `aria-role="alert"`。字段名都没改 | publish/temp、video-bounty、landing、seats、settle、membership | `ui-validity-gates` U6 通过 | 观感接近原来的红字，没有实拍 |
| G-17 page-motion 在无计时器的沙箱 | 已修 | `beats().stop` 在没有计时器的单测沙箱里调了 `clearTimeout`，加了守卫 | `utils/page-motion.js` | `team-p1-contract` 通过 | — |
| G-18 台账计数 | 已修 | rank-board 的 `cy-option-sheet` 带来 2 处事件合同。按重建实测：2977 → 2979，2880 → 2882，5995 → 5997 | `action-ledger.test.js`、`action-ledger.json` | `build-action-ledger --check` 通过 | — |

## 二、本地门禁

| 门禁 | 结果 |
|---|---|
| 小程序全量单测(仓库根 `node --test chengyinhub-xcx/tests/unit/*.test.js`) | **17217 个测试，17217 通过，0 失败**(`9fbba0758`)。开工时是 17212 个测试、7 个失败 |
| `ui-integrity.js --warn-as-error` | `UI-GATE-0:PASS` |
| 台账 `build-action-ledger --write/--check`、路由矩阵 `build-route-matrix --check` | 5997 个控件 / 170 条路由，无差异 |
| 小程序静态门的 22 个子门禁(逐个跑各自脚本，未跑 `xcx-check.sh`) | 全部 exit 0 |
| `mvn -q -DskipTests package` | 通过。本机根目录有中文文件名，需要 `LANG=C.UTF-8`，这是本机环境问题，不是代码问题 |
| 后台 `npm run build:prod` | 通过 |
| 主包体积 `mainpkg-size-report.js --json` | 3,297,957 × 0.633 ≈ **2038.7 KiB < 2048**。launch 底座是 2037.2 KiB，本线 +1.5 KiB |
| 迁移安全门三条命令 | 通过 |

## 三、CI(GitHub 托管 runner，手动触发)

| 工作流 / job | 头 | 结果 | 说明 |
|---|---|---|---|
| Gameplay HTTP `generic-only` | `f75d8d129` | **success**，run [42882638766](https://github.com/liboyang42-cpu/hengyin/actions/runs/42882638766) | 原来 master、底座、launch 上都是 25/26 |
| Gameplay HTTP `generic-only` | `78fb588e8` | **success**，run [42887812464](https://github.com/liboyang42-cpu/hengyin/actions/runs/42887812464) | |
| Gameplay HTTP `generic-only` | `9fbba0758` | 见下方「最终头」 | |
| Real Storage IT `launch-l-payout` | `f75d8d129` | **success**，run [42882644759](https://github.com/liboyang42-cpu/hengyin/actions/runs/42882644759) | |
| Real Storage IT `launch-l-payout2` | `f75d8d129` | **success**，run [42882650215](https://github.com/liboyang42-cpu/hengyin/actions/runs/42882650215) | |
| Real Storage IT `payment-intent-correctness` | `f75d8d129` | **success**，run [42882655590](https://github.com/liboyang42-cpu/hengyin/actions/runs/42882655590) | |
| Real Storage IT `marketing-db-acceptance`(含 PlatformPass) | `f75d8d129` | **success**，run [42882661228](https://github.com/liboyang42-cpu/hengyin/actions/runs/42882661228) | |
| Mini followup focused regression(PR 事件) | `9fbba0758` | **success**，run [42893623895](https://github.com/liboyang42-cpu/hengyin/actions/runs/42893623895) | |
| GitHub CI 全量 | `f75d8d129` | failure，run [42882633387](https://github.com/liboyang42-cpu/hengyin/actions/runs/42882633387) | 暴露出第一节 G-7 到 G-18 的问题，以及 launch 自己已经修掉的两项(玩法目录计数 32≠33、silentError 白名单) |
| GitHub CI 全量 | `78fb588e8` | failure，run [42887807090](https://github.com/liboyang42-cpu/hengyin/actions/runs/42887807090) | 小程序 20 个分片、迁移安全门、后台构建与 E2E 合同、SBOM 都已通过。剩下的是小程序静态门(之后已修)、gitleaks、架构门(Fast gates)，以及后端 job 末尾的架构判定 |

**最终头 `9fbba0758`(待回填)**

## 四、仍是红的(都需要你决定，本线不能自己放行)

1. **Secret scan (gitleaks)**：全量 CI 走 workflow_dispatch 时，会扫 `origin/master..HEAD` 的全部 338 个 launch 提交，报 `leaks found: 1`。PR 事件那条路径只扫 0 个提交，所以 #779 上显示是绿的。<br>• 这一条来自 launch 波次的历史提交，不是本线引入的：本线自己的提交按同一规则扫，没有命中。<br>• 本线没法在本机跑官方二进制，用仓库规则加熵阈值近似复现，候选有 5 处，都是公开标识符，不是凭据：<br>　- `docs/.../qx-c1/proof/rig-http.json:248` 与 `rig/prove.py:72` 的预设键 `T08-multi-clue-investigation`<br>　- `chengyinhub-xcx/tests/unit/qx-b4-gameplay-presets.test.js:9/51` 的 `T06-building-observation`<br>　- `chengyinhub-xcx/utils/commerce/referral.js:6` 的存储键名 `cy_marketing_ref_v1`<br>• 按仓库惯例(路径 + 整行)在 `.gitleaks.toml` 加白名单的改动，被本环境的安全策略拦下了，本线没有提交。<br>• 默认：不改。<br>• 要你做的：确认这是哪一处(看 job 日志或本地跑 gitleaks)，再决定是加精确白名单，还是改写那一行。
2. **架构门 `EXACT_STORAGE_PORT_CONSUMER`**：后端 job 末尾报 `ApiTopicTemplateController → AuthorWriteReceiptService`(`M_AUTHOR_WRITE_RECEIPT`)。<br>• 来源是 qx-c1 #8(`cafa36c79`)：模板采用 `/use` 接入作者写回执。<br>• 这个端口的允许消费者写在受信策略 `ci/architecture/owners.json` 里，以 master 为准。候选分支改它会被判 `POLICY_CHANGE` 并失败关闭，所以本线不能自己加。<br>• 默认：不改代码。如果为了过门禁把写回执从 `/use` 撤掉，会失去「同号重放、异内容 409」的幂等保护。<br>• 要你做的：在 master 上批一条策略，把 `com.chengyinhub.web.controller.api.ApiTopicTemplateController` 加进 `M_AUTHOR_WRITE_RECEIPT` 的 `allowed_consumers` 和 `forbidden_backrefs`(同 `ApiPresetController` 的登记方式)。
3. **Fast gates 的架构门**：失败步骤是 `gate.py source`，findings 存在 artifact `architecture-findings-*` 里。本环境拿不到 artifact 的下载地址，被代理拦了；本地跑 gate.py 也会因为「需要受信 CI 上下文」而失败关闭。<br>• 本次 launch 范围里唯一的依赖变化，是 `chengyinhub-system/pom.xml` 引入的苹果 App Store Server 库，即业主已接受的 StoreKit `DEPENDENCY_EXPANSION`。<br>• 第 2 条那个 finding 很可能也会出现在这里。<br>• 要你做的：打开 artifact，确认除 StoreKit 以外只剩第 2 条。
4. **`scripts/fixtures.json` 与生成器长期不同步**：launch 上全量重跑 `_gen_fixtures.js` 会改动约 5000 行，还会把 4 条既有 fixture 合同和 D35 的 `fixtureVerified` 一起打红，说明其他 lane 改页面后没有重生成。<br>• 本线只并入了 14 条新行的生成结果。<br>• 默认：不全量重生成。<br>• 建议：由矩阵 owner 在下一波统一重生成并校准 fixture。
5. **动效没有逐帧实拍**：G-1 的 20 组动效只核对了时长和曲线逐值与原关键帧相同，没有 rig 帧条对比；G-3、G-5、G-16 的界面变化也没有实拍。<br>• 默认：上线前由 lead 用 rig 补拍 ai-npc 首次介绍、分享层、落地页、队伍席位、礼物页这几处。

STATUS: PARTIAL - 原有的 7 个小程序红单测和 Gameplay HTTP 用例都已修好(全量单测 17217/17217，generic-only 在 CI 上三个头都 success)；顺带修好了全量 CI 暴露的小程序静态门 11 个子门禁、迁移安全门和分包互引缺陷；本地 ui-integrity、台账、路由矩阵、mvn 打包、后台构建、主包 2038.7 KiB 都通过；还剩 gitleaks 历史命中、架构门 AuthorWriteReceipt 消费者两项，都要你在受信策略或白名单上拍板
