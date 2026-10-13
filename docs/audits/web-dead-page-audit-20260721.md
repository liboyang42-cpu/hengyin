# Web 后台疑似死页核查（2026-07-21）

核查范围：`node`、`item`、`couponhistory`、`betagame`、`world`。判断必须同时查看前端路由、生产 `sys_menu`、后台接口、代码引用；单一维度缺失不作为删除依据。

| 页面 | 路由证据 | 生产菜单证据 | 后台接口证据 | 引用证据 | 结论 |
| --- | --- | --- | --- | --- | --- |
| `node` | 无静态/动态路由声明 | 无组件、路径或权限记录 | 无 `/business/node` Controller | 仅残页与配套 API 自引用 | 删除生成器残页及无效 API |
| `item` | 无静态/动态路由声明 | 无组件、路径或权限记录 | 无 `/business/item` Controller | 仅残页与配套 API 自引用 | 删除生成器残页及无效 API |
| `couponhistory` | 无静态路由 | 无组件、路径或权限记录 | `SmsCouponHistoryController` 仍提供完整接口 | 页面引用配套 API | 保留；接口仍存，不能证明为真死页 |
| `betagame` | `/betagame/edit` 隐藏路由仍在 | 菜单 2098 及按钮权限 2106–2110 均启用、可见 | `CmsBetaGameController` 与投票接口仍在 | 列表、编辑、投票子页相互引用 | 保留；生产功能存活 |
| `world` | 由动态菜单加载 | 菜单 2178、2179 均启用、可见 | `IpWorldController` 与小程序接口仍在 | P3 迁移、设计与实现文档引用 | 保留；生产功能存活 |

生产菜单证据来自只读查询：以 `component/path/perms` 同时匹配上述五个资源，查询时间为 2026-07-21。查询结果不含任何凭据。

防回归契约位于 `chengyinhub-ui/tests/unit/dead-page-contract.test.js`：锁定 `node/item` 不得重新生成，同时保护仍有存活证据的三个页面不被误删。
