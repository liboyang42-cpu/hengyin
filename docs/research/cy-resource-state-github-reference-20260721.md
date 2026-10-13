# CyResourceState：GitHub 一手实现调研（2026-07-21）

## 结论

`CyResourceState` 只服务于已由页面判断的两种资源不可用情形：`forbidden` 与 `error`。它统一图标、文字层级、语义颜色以及可选的重试入口；不会发请求、判断权限或把“空数据”伪装成失败。

首批消费者来自真实代码：

- 主题工作台的途中彩蛋读取：页面以 `checkPermi(['business:npcRouteEgg:list'])` 判断权限，读取失败后页面保留 `loadRoamEggs()`。
- 俱乐部工作区读取失败：页面保留 `openWorkspace()` 与原查询权限边界，组件只回传 `retry`。

## 上游证据与边界

| 上游实现 | 可借鉴的事实 | 本仓落实 |
| --- | --- | --- |
| [Element UI Alert 文档](https://element.eleme.io/#/zh-CN/component/alert) / [Alert 源码](https://github.com/ElemeFE/element/blob/dev/packages/alert/src/main.vue) | 信息提示只负责呈现，关闭与业务动作由调用方决定。 | 组件只接收标题、说明、状态种类和可选重试文案；重试以事件回交页面。 |
| [Element UI Button 源码](https://github.com/ElemeFE/element/blob/dev/packages/button/src/button.vue) | `loading` 与 `disabled` 可以避免重复触发。 | 页面提供 `retrying`，组件同时禁用和显示加载，方法也拒绝重入。 |
| [Element UI MIT 许可证](https://github.com/ElemeFE/element/blob/dev/LICENSE) | 本仓现用 Element UI 2.15，复用其已有按钮/图标能力，无需新依赖。 | 只组合已有基础组件与现有 semantic token，不复制上游样式或引入状态库。 |

## 验收

1. `forbidden` 与 `error` 是唯一支持种类；加载、空态、局部失败和权限判断仍由页面决定，后续要有真实消费者再扩展。
2. 没有 `retryText` 就不渲染动作；有重试时组件仅 emit `retry`，不持有 URL、请求函数或错误翻译。
3. 失败说明不声称数据为空；无权限说明不试图根据前端猜权限。
