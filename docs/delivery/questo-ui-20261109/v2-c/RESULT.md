# 动效 v2 · lane v2-c RESULT(支付到账 / 原地半屏打勾 / 主题卡→详情)

分支 `launch/questo-20261110`(直接推,rebase 后推,未 force)。原型 `proto/motion-v2/index.html`(`claude/questo-ui-brief-20261109`),走查 rig `claude/cloud-walk-20261101-rig` 的 mpweb。只动呈现层:支付、建单、同意、导演台写命令、权限与错误出口一概不变;未动 Java、`ci/architecture/*`、`.github/workflows/ci.yml`。

## 1. 做了什么

| 时刻 | 真实落点 | 呈现 |
|---|---|---|
| 4 主题门票支付 | `pages/topic/index`(自玩票)+ `self-play-checkout`(确认订单整屏层) | 点支付:支付键**原地**收成 52px 加载圈(先量现宽再写目标宽,transition .3s,不跳页);服务端确认到账:外观色**边缘光**扫屏一圈(1.3s,`cy-edge-glow`),底栏升起**紧凑成功卡**(勾按笔顺描出:短笔→长笔),停 1.1s(卡升起 .56s,合计 1.6s,与原型一致)后卡下滑、结算层**原地淡出**(不再右滑);再 350ms 页面下的主按钮**原地**换强调色变「已购 · 开始游戏」并弹一下(有 registrationId 时下一步直接带票开局,否则「已购 · 去票夹」)。建单失败/取消/待确认/资料缺失每个出口都回到空闲,不留死圈 |
| 4 报名结算 | `pages/activity/baoming` | 支付键开 `morph`(`cy-btn` 已有的药丸→圆+转圈);到账 `glowKey` 触发同一个边缘光;成功面板沿用 `cy-result-sheet`(紧凑卡+描出的勾,2s 自愈后跳订单详情——该页本来就会离开,没有「页面下原卡」可改) |
| 5 半屏原地打勾 | `club-director-broadcast-sheet`(定向广播)+ 俱乐部场次导演台 `topic-detail` | 点发送:半屏**不收**,按钮原地「发送中…」;**服务端确认后**被选中的同行人每 160ms 依次打勾(弹簧 .5s,超过 8 人并入末拍)→ 按钮原地变「已发出」→ 700ms 后收起;回到触发它的「报名参与者」半屏,顶上一条卡原地写「已发出 N 人 · 刚刚」(强调色),停 1.6s 后 max-height 收走。驳回/待确认/异常/版本冲突四个出口半屏立刻收起(与改造前「点发送立刻收」一致);草稿仍「写成功才清空」(CL-20) |
| 6 主题卡→详情 | 首页主题卡 `pages/index` → `pages/topic/index` | 点卡片量封面矩形(`utils/hero-fly.js`,随 eventChannel 传给详情页);详情页飞层从该矩形长成全屏头图(.56s `cubic-bezier(.32,.72,0,1)`,圆角 24→0),等主题数据到位再淡出飞层,**之后**主按钮条(`#heroActs`)从下滑上来(.5s);返回键反向缩回再退页;导航标题 .35s 淡入+上移(原为纯淡入,滚到第二屏/吸顶时出现)。无矩形(深链/分享)或减少动态:什么都不做,页面直接就位 |

动画手段:`this.animate`(旋转、勾、弹一下、按钮条上滑)+ CSS transition(尺寸、颜色、淡入);**没有新增 `@keyframes`**(棘轮仍 146);外观色一律 `var(--lk-a)`,没有外观上下文时回落到玩家域成功绿 `--cy-comp-sheet-player-success`;主题门票的外观由 `info.look.style` 映射到 `--lk-a/--lk-b` 行内变量(目录色表放在 `pages/topic/index` 分包,不进主包)。减少动态:边缘光不播、勾/圈/飞层/收起全部一步到终态(`readReducedMotion` + 页面 `reducedMotion`),半屏打勾时长为 0。

## 2. 和原型不一致、且我没有掩盖的地方

1. **时刻 4 的「半屏」不是半屏**:自玩票的确认订单是整屏层(owner 2026-10-22 定稿),不是原型里的半屏。把底栏收成成功卡、整层原地淡出,达到同样的「同一块面收成成功卡 → 回到原页」。没有为了像原型去改结算层结构。
2. **整屏「通行证已到账」揭晓层不再自动弹出**:`pass-reveal` 组件与 `onReveal*` 仍在(回归),只是付款成功改走紧凑成功卡 + 原页已购;商家营销同意行(拍板第 40 条 A)照旧——有同意可选时在原卡变「已购」后弹原结果面板。这是对 2026-10-22 业主「到账层」落点的替换,依据是本 lane 任务书(spec 明写成功卡、原卡就地变已购)。
3. **时刻 5 的「触发它的卡」**:原型里是页面上的一张卡;导演台的「定向广播」入口只在「报名参与者」半屏里(进广播会先关掉它,既有行为)。所以广播收起后**重新打开该半屏**并在顶部原地写结果、再收走。收走的是这张结果条,不是「定向广播」入口本身(入口还要能再发)。
4. **时刻 6 的「列表淡出 / 返回缩回到列表」**:详情页是真实新页面,下面没有列表可画;列表淡出由原生页面转场承担,返回时头图缩回的是**原卡片矩形的位置**,落在黑底上,随后原生退页。时刻 6 的头图目标是整屏(这页是整屏头图+上滑第二屏,不是原型的 420 高头图),所以飞层长到全屏。「大标题收进导航栏」沿用页面已有的「第二屏出现 → 导航标题」机制(没有用 ScrollTimeline:这页是两屏切换而不是连续滚动)。
5. **外观色**:原型四种外观的强调色是原型自己的取值;小程序用的是 `look-tokens` 目录(星尘米白、纸本朱红、末世橙、夜城黄/青粉),所以并排图里色相不同是 token 差,不是动效差。
6. 导演台页没有外观上下文,打勾按钮/结果条用成功绿回落。
7. 原型时刻 6 本身有个小缺陷(飞层挂到 `.phone` 而样式作用域在 `.m6`,飞层不渲染),并排对照用的是只改这一处的副本 `rig/proto-index.patched.html`;时刻 4/5 用原文。

## 3. 并排对照(`compare/`)

`compare/m{4,5,6}-<look>-contact.jpg` 是每个时刻的总览(左原型 | 右小程序),`m{4,5,6}-<look>-<ms>.jpg` 是单帧,`m6back-*` 是返回,`skew-*.json` 是真实时钟截屏相对目标时间戳的偏差(多数 ≤ 5ms;m6 飞层前几帧 ≤ 41ms,m5 首帧 70ms)。m4 四种外观 × 12 个时间点,m5/m6 星尘+纸本。生成方法见 `rig/README.md`。

逐轮发现并已改掉的差异(都是对照帧看出来的):支付键收圈时「微信支付」文字溢出(label 先于宽度淡出)→ 已修;成功卡高度偏矮(340rpx→700rpx 对齐原型半屏高);结算层关闭时整层右滑把成功卡带走 → 改为原地淡出、卡自己下滑;主题页下方票卡只改了首屏而用户其实在第二屏 → 改为就地改第二屏主按钮;广播「已发出」比原型晚一拍 → 160×人数,不再多加一拍;结果条在名单深处看不见 → 挪到半屏顶部;成功卡停留 1.1s → 与原型 1.6s 收起对齐(含升起)。仍存在、接受的差:见第 2 节。

## 4. 验证

- 小程序全量单测(仓库 `chengyinhub-xcx` 下 `node --test "tests/unit/**/*.test.js"`):**17413 条,0 失败**(最终树,已 rebase 到并入 v2-b 的 `0e33b949` 之后重跑);新增 `tests/unit/motion-v2-c-contract.test.js` 13 条(组件行为、时长、终态、失败不演、飞层矩形、减少动态)。中途按真实结果同步了 4 处合同:`self-play-order-idempotency`(到账落点由整屏揭晓层改为 `selfPlayPhase=done` + 边缘光)、`motion-property-ratchet`(布局动画上限在 v2-b 的 8 上 +2 → 10,理由写在注释)、`action-ledger` 三处分母(各 +1)、`ui-integrity-baseline` 一条行号锚点与 u4Dynamic digest。
- `tests/lint/ui-integrity.js --warn-as-error`:**UI-GATE-0:PASS**(Legacy error 0 / warn 0)。
- 动作台账 `build-action-ledger.js --write` → `--check`:PASS **6022 控件**(并入 v2-b 后;本 lane +1:广播半屏 `bind:sentdone` 事件合同);路由矩阵 `build-route-matrix.js --check`:PASS **171 路由 / 27 场景**。
- WXS 语法门禁 PASS(14 个 .wxs);WXSS 注释/选择器/组件选择器门禁 PASS;未定义 token、底栏不透明、a11y 命中区/可点名称、组件属性与路径门禁 PASS;`danger-confirm-gate` PASS(53 个危险调用点,38 个不可逆有确认);`ds-hardcode-gate.sh`(暂存区)PASS。并入 v2-b 之后 ui-integrity / 台账 / 路由矩阵 / 全量单测均在最终树上重跑。**没有**跑 `ci/xcx-check.sh` 与 `ds-hardcode-gate-selftest.sh`。
- keyframes 棘轮:`motion-property-ratchet`(`KEYFRAMES_CEILING` 仍 146、`transition: all` 0)与 `reduced-motion-coverage-contract` 通过,新文件没有 `@keyframes`(测试里单独钉了三个新 wxss)。
- 主包:`mainpkg-size-report` 源码合计 **3231KB**(上一轮 x-fix7 记 3233KB,×0.633 ≈ 2046 KiB;本轮 ≈ 2045 KiB,**< 2048 KiB**,净 −2KB)。新增主包文件 `components/cy/edge-glow`(≈2.3KB)与 `utils/hero-fly.js`(≈0.8KB);为它们腾位,把只被 `pages/club/settlement` 用的 `club-direct-ledger` 组件下沉进 `pages/club`(−6.4KB,报告原本就列为「可下沉」),`view-model.js` 因分包 A 也在用而挪到 `utils/`。真实压缩后体积只有 `cli upload` 能回答,这里给的是同口径源码估算。
- Java:`chengyinhub-admin` / `chengyinhub-system` / `ci/` / `.github/` 一行未动(`git diff --stat` 为空)。
- 没有碰生产、没有密钥、没有合 master、没有标 #779 ready。

## 5. 真机/发版前仍需人眼看一遍的

- 走查 rig 是 headless Chrome 里的 WebView 近似:`this.animate` 在 rig 里是 WAAPI 桩(我只把关键帧 `ease` 映射成 easing),真机微信里 `ease` 只认其内置曲线名——边缘光渐隐用的 `cubic-bezier(...)` 如被忽略,退回线性渐隐,不影响时序。边缘光的 `-webkit-mask-composite: xor` 在 iOS WKWebView 与较老安卓 X5 上需要真机确认;不支持时 `.eg__in` 的内发光仍在。
- 时刻 5 在真机上需要真的发一条广播(rig 里是注入 `broadcastSendPhase` 驱动的,请求链路本身未改且有单测)。
- 时刻 6 的飞层起点来自首页主题卡;其他入口(搜索/收藏/俱乐部卡等)还是普通跳转,没有接 `heroFly.go`(同一函数,按需逐个接)。

STATUS: DONE — 时刻 4/5/6 已落地并推到 launch/questo-20261110;全量单测 17413/0、UI-GATE-0:PASS、台账 6022/路由 171×27、各门禁与 keyframes/danger 通过;偏差(整屏结算层、广播触发卡、列表淡出)见第 2 节。
