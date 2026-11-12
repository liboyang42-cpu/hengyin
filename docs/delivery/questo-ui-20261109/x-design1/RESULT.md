# x-design1：设计评审 D1–D16 落地

分支 `launch/questo-20261110`，依据 `DESIGN-REVIEW-20261112.md`（D1–D16）、BRIEF、MOTION 与 proto/game。基于 x-fix7 的提交继续做；D2、D16 在它的规则上追加，没有重写。每项一个提交，推送后再做下一项。

通用兜底（D3 / D8 / D13 / D15 共用）：`style/components.wxss` 新增 `.cy-cover-fb`（外观渐变 `--cover-a` → `--cover-b`，宿主不传就是中性深渐变）+ `images/cover-route.svg`（小路线插画）。全仓 wxml 不再出现「封面暂不可用」「暂无海报」（作者端「原封面暂不可用，点按重新上传」是操作提示，保留）。

## 逐项

| 编号 | 改动 | 文件 | 测试 | 前后截图 |
|---|---|---|---|---|
| D1 | 玩页主面不再有常驻扫码浮钮和站点详情里的「出示入场码」按钮；入场码只走「⋯」工具菜单的「入场码」行。半屏里去掉翻面大票卡，只放一个 400rpx（≈200px）的码。原先组件里就没有亮度调节（有测试禁止 `setScreenBrightness`），所以没有「保留亮度提升」可言，未新增 | pages/play/index.wxml/.wxss、pages/play/components/scene-qr-ticket/* | x-design1-d1-d3；改写 l-play-20261110、dw29-r2-v02-scene-qr-ticket、play-free-explore-shop-card-g21、x-fix7-hero-steps-above-bar | 未截（见下） |
| D2 | 在 x-fix7 的 `.cf--finale` 规则后追加：文案区在标志下方多留到 `+ 64rpx`，左右取页面边距铺满内容宽（不再比内容窄一圈），解码乱码字 `.is-n` 在通关卡里不显示（去掉标题后面的字影）。x-fix7 的 `pre-wrap`、底部避让保留 | pages/play/components/chapter-flip/index.wxss | x-design1-d2-finale | 未截 |
| D3 | 无封面一律是 look 渐变 + 小路线插画，不写字：活动列表、商家主题详情 hero、俱乐部主题详情（D12 另行折叠）、IM 报名卡、模板货架四处、模板预览、漫游任务列表 | style/components.wxss、images/cover-route.svg、上述各页 wxml/wxss | x-design1-d1-d3；改写 activity-list-cover-fallback、no-blank-cover-contract、template-shelf-redesign、c02-topic-merchant-apply、empty-state-figma-illustration 等 | D3-before / D3-after |
| D4 | 玩页地址条改深色（`cy-map-address-search` 新增 `dark` 属性，色取玩页 chrome token；漫游页不传，仍是白条）；通关（`allDone` / `topicCompleted`）后整条不挂 | components/cy/map-address-search/*、pages/play/index.wxml | x-design1-d4-search-dark | 未截 |
| D5 | 首页「编辑精选」「附近活动」没有内容（含加载中、失败之外）时整段连标题不挂，删掉两个空态 | pages/index/index.wxml | x-design1 无独立文件，改写 home-reco-why-button-empty-state | D5-before / D5-after |
| D6 | 通行证页删掉「兑换码 暂不可兑换」「付费模板」行及无人消费的 `workshopReason` 与 `/api/workshop/capabilities` 请求；主题库入口条件不变 | subpackageMember/market/passes/* | x-design1-d6-passes；get-auth-matrix-contract 直接 GET 计数 129→128 | D6-before / D6-after |
| D7 | 商家口碑整页空态补 `kind="empty"`（原来没有插画，只剩一行字），去掉空 `sub`。公开态的资格说明有既有契约测试，保持原样 | pages/merchant/reviews/index.wxml | x-design1-d7-reviews-empty；改 x-fix4 L-5 断言 | D7-before / D7-after |
| D8 | 站点详情卡面无图：由整卡大图钉改为 look 渐变 + 小路线插画；「到店三步」标题与三步放进同一个 `block`，同生同灭。卡片尺寸由 JS 量，未改，所以「缩小占位」只做到换成小插画，卡片本身没变小 | pages/play/index.wxml | x-design1-d8-station | 未截 |
| D9 | 开场页主题标题 80rpx → 56rpx，最多两行加省略号 | pages/play/index.wxss | x-design1-d9-intro-title | 未截（页面需要真实后端，兜底后端下只出加载失败） |
| D10 | 收入区去掉 360rpx 最小高，底部一档 `--cy-space-5`；收入反馈条回到文档流；问候语去掉 `ep1`，两行加省略号 | pages/merchant/index/index.wxml/.wxss | x-design1-d10-workbench | D10-before / D10-after |
| D11 | 票务设置十行日期/时间/地点：去掉「选择开始日期」等说明字和「选择 ›」药丸，右侧显示值，空为「未设置」（淡色）。「编辑」「收起」等其它药丸不动 | pages/publish/fabu/step3.wxml、index.wxss | x-design1-d11-ticket-rows | 未截（需要整套发布编辑器状态） |
| D12 | 俱乐部活动详情/导演台没有封面时整块封面区不出，标题区上提（`--ctd-shift`），并避开透明导航 | pages/club/topic-detail/* | x-design1-d12-director；改写 no-blank-cover-contract | D12-before / D12-after |
| D13 | 结算页「暂无海报」改用同一兜底图 | pages/activity/baoming/* | x-design1-d13-checkout-poster | D13-before / D13-after |
| D14 | 完成面板：「保存图片 · 分享」本来就是一行；有「查看结算」时它是唯一实心主按钮，「继续」改成无底色文字按钮（位置与底座高度不变，x-fix6 R4-2 的几何测试不动） | pages/play/index.wxss | x-design1-d14-finish-buttons | 未截 |
| D15 | 票夹无图票卡：大插画换成统一兜底（120rpx 小插画 + look 渐变）。卡片高度是整副牌共用的几何（577×865rpx，正反面翻转共用），没有为无图卡单独缩高，这一点 **未做** | subpackageMember/signup/* | x-design1-d15-wallet；改 x-fix2-player | D15-before / D15-after |
| D16 | 包面主题名沿用 x-fix7 P-6（只许主题名一个文字节点），没有再在包上方重复一份标题。发牌间隔 240ms → 440ms，同一时刻最多 4 张在飞，总时长由 `(n-1)*DEAL_STAGGER + DEAL_FLIGHT` 驱动 | pages/play/index.js | x-design1-d16-pack-deal；play-pack-timing-lock 仍绿 | 未截 |

## 截图方法与未验证项

走查 rig（`claude/cloud-walk-20261101-rig` 的 mpweb 运行时）：这个容器没有 dockerd，起不了 rig 的整套后端，所以只用 mpweb 浏览器运行时 + 一个返回通用 `{code:200}` 的兜底后端（`rig/fake-backend.mjs`），打开真实页面后用 `setData` 注入状态再截图（`rig/xd1.mjs`、`rig/spec-*.json`）。前图用 x-fix7 末提交 `5e94c2b5` 的 bundle，后图用当前提交的 bundle，同页同状态，390 宽 JPEG q70。16 张图逐张看过：

- D3：活动卡片缺图由「图标 + 封面暂不可用」变成深色渐变加小路线图。
- D5：首页「编辑精选 / 附近活动」两段空标题消失。
- D6：通行证页底部「兑换码 暂不可兑换」消失。
- D7：口碑空态在前后图里都有插画（运行时里按注入的 `pageState:'empty'` 渲染；原先线上没有插画是因为缺 `kind`，兜底后端下无法复现缺 kind 的旧样子，旧样子只由测试断言覆盖）。
- D10：问候语两行，收入与财务/更多之间的空档明显收紧。
- D12：封面区折叠，标题上提，没有「封面暂不可用」。
- D13：「暂无海报」灰块变成渐变加小插画。
- D15：无图票卡的大插画变成小路线图；卡片整体仍偏空（卡高没改，见上）。

没有截图的项：D1、D2、D4、D8、D9、D11、D14、D16。它们依赖玩页运行态（开局、通关、发牌）、发布编辑器整套状态或真实后端，兜底后端注入不出来；这些项只有规则级/源码级测试。D2 的标志与文案间距、D16 的发牌错开需要 DevTools 或真机复拍确认，D2 的 64rpx 是按 `_cy = 33.3vh`、标志占 `140*S` 的几何算出的，没有看到过实际画面。

## 门禁结果

- 全量单测（仓库根 `node --test chengyinhub-xcx/tests/unit/*.test.js`）：TOTALS_PLACEHOLDER
- 动作台账 `build-action-ledger.js --write`：PASS 6025 控件；路由矩阵 `--check`：PASS 171 路由 / 27 场景。
- danger-confirm：自证通过；PASS（53 个调用点）。
- ui-integrity：**UI-GATE-0:PASS**（首次跑出 1 条 U4：`workshopReason` 无人消费，已删代码而不是登记基线；无新增基线）。
- keyframes 棘轮：本轮没有新增 `@keyframes`（随整仓单测）。
- WXS 语法门禁 `node --expose-internals scripts/wxs-syntax-gate.js`：PASS（14 个 .wxs，4 段内联）；WXSS 注释入值门禁、选择器门禁、组件选择器门禁、未定义 token 门禁、WXML 条件链门禁、页面根主题门禁均 PASS，无注释写进声明值。
- 主包：源码侧 `mainpkg-size-report` 合计 3234KB，×0.633 ≈ 2047 KiB，< 2048 KiB（x-fix7 为 3233KB，本轮净增约 1.1 KB 源码：兜底类、`dark` 属性、`cover-route.svg` 490 字节，同时删掉了几处旧占位与文字；富余只有约 1 KiB，下一轮要先下沉再加）。新页面没有；所有改动落在已有文件。
- 未改 `ci/architecture/*`、`.github/workflows/ci.yml`；未跑 `ci/xcx-check.sh`、`ds-hardcode-gate-selftest.sh`；未标 ready、未合并；没有碰迁移、生产或密钥。
- PR #779 CI：CI_PLACEHOLDER

STATUS: PARTIAL - D1–D16 代码与测试全部完成；8 项（D1/D2/D4/D8/D9/D11/D14/D16）没有前后截图，D15 卡高未缩
