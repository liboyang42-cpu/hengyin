# CyTimeline：处置轨迹的 GitHub 一手调研（2026-07-22）

## 结论

`CyTimeline` 不接收审核任务、接口地址或动作枚举；页面把领域记录归一化成带稳定 ID 的轨迹项后，组件只负责顺序、空态、状态点、操作人/时间和可选证据提示。

审核台是首个真实消费者：它保留 `action` 的中文翻译与状态色、`reasonText/reasonCode` 的回退、`operatorId` 的身份说明和 `snapshotJson` 是否存在的证据语义。组件不会解析 JSON 或请求详情。

## 上游证据与边界

| 一手实现 | 可借鉴的事实 | 本仓落实 |
| --- | --- | --- |
| [Element UI Timeline 容器源码](https://github.com/ElemeFE/element/blob/c345bb453bf11badb4831a6a3f600c9372b3a336/packages/timeline/src/main.vue) | 容器只管理列表顺序，不承担每一条业务内容。 | `CyTimeline` 仅接收页面已准备的 `items`，不传接口或领域参数。 |
| [Element UI Timeline Item 源码](https://github.com/ElemeFE/element/blob/c345bb453bf11badb4831a6a3f600c9372b3a336/packages/timeline/src/item.vue) | 时间、节点和内容是独立视觉层，节点可表达不同状态。 | 采用本项目 token 的状态点；标题、摘要、操作人和时间有稳定层级。 |
| [Element UI MIT 许可证](https://github.com/ElemeFE/element/blob/c345bb453bf11badb4831a6a3f600c9372b3a336/LICENSE) | 上游在 MIT 下发布。 | 仅借鉴信息分层和节点/轨道结构；不复制源码，不引入依赖。 |

## 验收边界

1. `items` 中没有稳定 ID 或标题的条目不渲染；组件不从原始 action、状态码或 JSON 推导显示内容。
2. 每条可显示标题、摘要、操作人、时间，以及页面明确提供的证据提示。
3. 首批审核记录没有后端提供的前后值，因此不预留或伪造该字段；仅以 `snapshotJson` 是否存在显示“附有当时内容快照”。
4. 下一个消费者须有同构的事件记录，且需以真实字段重新设计前后值或可点击证据，不能把订单的注释代码或优惠券状态列表冒充时间线。
