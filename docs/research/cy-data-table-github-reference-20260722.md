# CyDataTable：批量选择与工具栏动作的 GitHub 一手调研（2026-07-22）

## 结论

批量选择不是“导出模块”的同义词。`CyDataTable` 只负责在页面明确开启时渲染选择列、统一已选数量的反馈，并把整行选择通过事件还给页面；新增、批量修改/删除、导出权限和下载请求仍由每个页面拥有。

本轮两个真实消费者验证了这个边界：

- 优惠券运营同时需要行选择、批量删除和按当前筛选导出。
- 票务管理需要行选择与批量动作，但当前没有启用导出入口。

因此模块接口保持为 `selectable`、`selection-change(rows)` 和 `toolbar-actions` slot；不会接收下载 URL、权限字符串、批量 API 或领域动作配置。

## 上游证据与边界

| 一手实现 | 可借鉴的事实 | 本仓落实 |
| --- | --- | --- |
| [Element UI Table 源码](https://github.com/ElemeFE/element/blob/dev/packages/table/src/table.vue) / [TableColumn 源码](https://github.com/ElemeFE/element/blob/dev/packages/table/src/table-column.js) | 表格选择是表格呈现层发出的 `selection-change` 交互；选择列由 `type="selection"` 描述。 | 组件用已有 Element UI 选择列维护呈现所需的已选计数，并只 emit 选择行；页面继续把行 ID 转成自身的批量操作条件。 |
| [Element UI MIT 许可证](https://github.com/ElemeFE/element/blob/dev/LICENSE) | 本仓已有 Vue 2 / Element UI 能力，无须引入表格或下载依赖。 | 只组合现有 `el-table`/按钮和 semantic token；不复制上游源码或样式。 |
| [RuoYi-Vue 用户列表](https://github.com/yangzongzhuan/RuoYi-Vue/blob/master/ruoyi-ui/src/views/system/user/index.vue) | 页面工具栏保有权限指令、业务方法和查询刷新；表格只提供行交互。 | `toolbar-actions` 只给页面一个统一布局位置，优惠券/票务页仍各自保有 `v-hasPermi`、`handleDelete`、`handleExport`、`getList` 与筛选显隐状态。 |

## 验收边界

1. 未传 `selectable` 时不出现选择列，也不会把选择状态作为额外的页面负担。
2. 传入 `selectable` 时，组件显示已选数量并 emit 原始行数组；页面决定 ID 映射、单选/多选禁用和后续动作。
3. `toolbar-actions` 不暗含导出：优惠券页放导出，票务页不放，二者都能工作。
4. 组件的刷新仅 emit `refresh`；接入页必须把它显式连接到自己的 `getList`，避免把请求偷偷放入表格模块。
