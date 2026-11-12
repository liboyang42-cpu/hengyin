# DEC-UI-2 结果（2026-11-12）

分支 `claude/dec-ui-2-20261112`，基于 master `1c80c5ae`。只改各条决定点名的内容，其余版式、文案、颜色不动。

| 决定 | 状态 | 做了什么 | 主要文件 |
|---|---|---|---|
| D-91 商家看玩家公开主页：浅色 | 已改 | 新增 `viewerLight`：他人宿主、非本人、观看者是商家时，主页根节点和浮层挂 `pc-merchant theme-merchant`，原生导航栏也切浅色，离开页面还原深色。主题跟随观看者。`isMerchantView` 不动，商家 About、合作 CTA 仍由被看者决定。 | `subpackageMember/components/cy/profile/index.{js,wxml}` |
| D-81 预览里切风格/配色 | 已改 | 发布预览页的「画面风格」下加风格和配色选择条，复用 `onLookStyle`/`onLookPalette`，写回 `formData.look`。草稿自动保存、保存上送 `payload.look`、后端校验与归属校验都是编辑器原路径，没有新接口。切换后重算预览。 | `pages/publish/fabu/index.{js,wxml}` |
| D-87 添加栏分割线 | 已改 | 新画 `divider` 图标（24 视口、描边 2、圆头，与其它图标同源），经 `ds-build-icons.py` 生成进 `icons.wxss`，补 `spec.json` 光学元数据；去掉短横 `sx-add__rule`。 | `design/icons/coolicons/divider.svg`、`components/cy/icon/*`、`pages/publish/fabu/*` |
| D-76 官方合集路线两页入口 | master 已有，未重复加 | 漫游护照已有「官方路线」大卡（f-pages P0-79，服务端回 ≥1 条路线才出），进 `official-routes`，再进 `official-route`。两页展示的就是普通城市定向路线。我没有再加第二个入口，只补了合同测试。 | `pages/roam/index.js`（未改） |
| D-116 钥匙开场序列帧预下载 | 已改 | 进开场页即用 `getImageInfo` 预热：完整版先热 intro 前 3 张加待机第 0 张，其余低并发补；失败吞掉。`app.json` 增加 `preloadRule`：进通行证页/通行证入口页时预拉 `subpackagePass`。帧图仍在 `subpackagePass`，主包不增，`pages/play` 未动。 | `subpackagePass/reveal/index.js`、`app.json` |
| D-112 通行证卡 | 已改（见下） | 后端 `holding` 新增 `planCode`、`planLabel`（年卡/月卡）、`daysTotal`、`daysRemaining`、`teammateBenefit`、`badge`；`selectActiveMembership` 带出订单 `plan_code`。前端在卡上画套餐标签、剩余/总天数进度条、角标，只画服务端给的字段。Java 单测三条。 | `PlatformPassService.java`、`PlatformPassMapper.xml`、`PlatformPassServiceTest.java`、`subpackageMember/market/membership/*`、`utils/platform-pass.js` |

## 需要你确认的两点

1. `teammateBenefit` 现在恒为 `false`。通行证只覆盖本人自玩（`coverage` 不含同行席位），后端没有「同行可用」这个事实，所以前端不会画这个标签。要做同行权益需要先定业务规则。
2. `badge` 目前只有一种：有效段剩余 ≤7 天返回 `EXPIRING`，卡上显示「即将到期」。原型里的其它角标需要另给口径。

## 门禁

- 全量小程序单测：见末尾运行记录；改动引起的旧口径断言已更新并注明决定号（D-91 的 4 处根节点/浮层 class 断言，D-81 的 ledger 点击数 3029→3031、总数 6025→6027）。
- `ui-integrity`：`UI-GATE-0:PASS`，无新增基线项；仅 5 条 U1/U4 基线因行号位移改键，条数不变，两个 keyDigest 按同算法重算。
- 动作台账已重建（6027），route-matrix `PASS 171 routes / 27 scenes`（未新增页面）。
- 其余门禁（WXSS 选择器/未定义 token/组件属性/死链/孤儿 handler/danger-confirm/sticky-bottom/a11y 等）均通过；未新增 `@keyframes`；`js-scope-lint` 通过。
- 未运行 `ci/xcx-check.sh` 与 `ds-hardcode-gate-selftest.sh`。
- Java 测试按仓库约定不在本地跑，交 CI。
- 主包：仅 `app.json` 增 12 行 `preloadRule`，无新资源进主包。
- 未碰 `ci/architecture/*`、`.github/workflows/*`，无生产与密钥。

STATUS: DONE
