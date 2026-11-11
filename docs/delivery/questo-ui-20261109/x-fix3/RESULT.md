# x-fix3 结果（商家 / 俱乐部 / 作者 + 后端健壮性）

分支：`launch/questo-20261110`。依据：`ACCEPT-ISSUES-20261111.md`。

## 已修

| 编号 | 角色 | 提交 | 结论 |
|---|---|---|---|
| M-1 | 商家（后端） | 299f2f57 | 媒体输出序列化器 `AppMediaOutputConfiguration.visible()` 对单个 `media-asset:N` 引用捕获 RuntimeException，记一条 warn，该项按无图输出；整条 `access/me`、`project/my` 不再 500。`AppMediaOutputConfigurationTest` 补了用例。 |
| C-1 | 俱乐部 | 8658616a | 俱乐部管理 tab 调商家专用 `/api/coop/list` 返回 403 时，按「无合作邀约」处理，不再卡在「重试管理进度」。 |
| A-1 | 作者 | cc49afff | 我的节点玩法简介为字面 `null` 或空时不渲染，不再印出「null」。 |
| A-2 | 作者 | f34da944 | 发布预检遇 AI 配置缺失只降级为通用建议项，不透出后端报错；补测试。 |
| L-5 | 商家 | 388c3ceb | 商家裸开口碑进自己的管理态，不再落「商家信息不完整」。 |
| ME-1 | 商家 | 9f91019d | 门店信息基础资料空值统一为「未设置」。 |
| ME-3 | 商家 | a257939d | 账户收益页 `onShow` 无条件恢复深色状态栏，商家浅色页上时间白字隐形。改为商家视角走 `merchantPageShow()`；补测试。 |
| ME-2 | 商家 | 3f270dc6 | 更多面板退款售后 / 收款账户 / 管理优惠券共用钱包图标，改为 `repost` / `finance` / `coupon` 三枚独立线性图标；新增 `merchant-more-sheet-distinct-icons.test.js`。 |

## 未改动 / 需判定

- **L-4 商家主页「链接已失效」**：未复现为页面缺陷，不改代码。
  - 门店信息「看公开主页」只走 `merchantHomeUrl(m.memberId)`，且 `memberId` 非法时前面已拦成「店铺资料不完整」；落点 `pages/userinfo/userinfo` 的缺参态文案是「这个主页链接不完整」，`cy-empty` 显式 title 优先于 kind 默认，不会印出「链接已失效」。
  - 「链接已失效」只来自旧兼容壳 `pages/merchant/profile`（无 id 时）和若干玩家子页；本页无调用者指向该壳。
  - 后端 `/api/merchant/public-home` 的 `isPubliclyVisible` 要求 `status=1`、`account_status=1`、`del_flag=0`；测试库该商家若未审核通过或账号未启用，就会走「商家不存在或未开放」→ 主页退成非商家态，属测试数据缺口。未能在本环境连测试库，无法给出该行数据，待走查方确认商家 16 的 status/account_status。
- **A-3 编辑器顶部大标题被导航压住 / 票务底栏压最后一行**：仅凭静态阅读无法定位（`fabu/index.wxss` 1268 行附近已有尾部净空补丁），需截图复现，未改。
- **A-4 模板介绍空黑区**：数据缺口（无精选示例），未改。
- **C-2 / C-3**：C-2 名册数字属测试数据（0 报名 / 2 未到场），C-3 为主理人非本场报名玩家的后端规则，均未改。

## 验证

- Java：本仓库 AGENTS.md 规定后端构建与 Java 测试只在测试 VPS 跑，本环境不跑本地 Maven，因此 `mvn -q -DskipTests package` 与 M-1 Java 测试**未在本次执行**；M-1 的改动与测试在 299f2f57 中，待 VPS 验证。
- 小程序门禁结果见下节。

- ui-integrity：`UI-GATE-0:PASS`，未新增基线条目。
- route-matrix：PASS（171 路由 / 27 场景）。
- 新增与相关单测（earnings-merchant-theme-fallback、merchant-more-sheet-distinct-icons）通过。
- 台账 `--check`、主包体积、全量 `npm run test:unit`：执行时仍在运行，**本文件提交时未取得结果**，需补跑确认。

STATUS: PARTIAL（M-1/C-1/A-1/A-2/L-5/ME-1/ME-2/ME-3 已修已推；L-4 判为测试数据、A-3 待截图；Java 构建与测试待 VPS）
