# TagsView：最近访问与对象上下文页签的 GitHub 一手调研（2026-07-22）

## 结论

本仓原 `TagsView` 与上游 Vue Element Admin 一样，以 `path` 作为唯一身份。它对普通列表页足够，但 `/order/detail?id=1024` 和 `/order/detail?id=2048` 会被合并为同一枚“订单详情”页签，运营无法并行比较对象。

本轮不复制上游的路径去重策略，而是收紧成一个本仓语义：

- 普通页面仍按路径去重；避免筛选、分页等 URL 变化无限增生页签。
- 带 `query.id` 的对象页按“路径 + ID”去重，显示为“对象详情 · #ID”。
- 固定页签永远保留；非固定页签仅保留最近访问的 8 个。

对象详情的 `router-view` 也使用同一身份作为 key；同时不进入现有按组件名维护的 keep-alive。否则 `#1024` 与 `#2048` 会复用旧实例，或关闭一个对象页签时一并逐出同类对象的缓存，造成错误数据或不可预测的状态丢失。

这使“订单详情 #1024 / #2048”可并存、可切换，同时不把每个查询参数都变成新页签。

## 上游证据与边界

| 一手实现 | 可借鉴的事实 | 本仓落实 |
| --- | --- | --- |
| [Vue Element Admin 的 TagsView store](https://github.com/PanJiaChen/vue-element-admin/blob/master/src/store/modules/tagsView.js) | 上游 `ADD_VISITED_VIEW` 用 `view.path` 判断重复，固定页签在“关闭全部”时保留。 | 继承固定页签的保护语义；把“对象页”身份细化到 `path + query.id`，并限制最近页签数量。 |
| [Vue Element Admin 的 TagsView 视图](https://github.com/PanJiaChen/vue-element-admin/blob/master/src/layout/components/TagsView/index.vue) | 页签渲染、关闭、刷新与左右关闭均通过同一个 tags store 协作。 | 所有关闭与当前态判断统一改用 `tagKey`，不在页面另建一套页签状态。 |
| [Vue Element Admin MIT 许可证](https://github.com/PanJiaChen/vue-element-admin/blob/master/LICENSE) | 该项目可作为实现行为的参考。 | 未复制源码；仅复核已有行为并按本仓对象路由需求实现最小状态接口。 |

## 验收边界

1. `id` 不同的同一路由详情页签可共存，标题保留页面名称并附 `#ID` 上下文。
2. 再次访问同一对象不会产生重复页签，而会移动到最近位置。
3. 最旧的非固定页签超过 8 个时自动淘汰；固定首页不参与数量上限。
4. 不以任意筛选参数作为身份，避免列表页和筛选页出现无上限页签。
