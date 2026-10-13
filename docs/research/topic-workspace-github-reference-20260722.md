# 主题 360 工作台：GitHub 一手模式调研（2026-07-22）

## 结论

没有发现一个成熟开源后台能替代本项目的「**主题为中心 + 白名单投影 + 显式不可用**」方案。

三个上游项目分别证明了其中的一段模式，但没有任何一个同时具备内容主体、主题生命周期、治理、商家供给、俱乐部承接与履约这些城瘾领域语义：

| 上游项目 | 能直接借鉴的模式 | 不能照搬的部分 |
| --- | --- | --- |
| [Directus](https://github.com/directus/directus) | 单一内容对象页可收拢版本、发布、协同、修订、评论和权限动作。 | 它是 schema 驱动的通用 CMS；关系只提供 M2O/O2M/M2M/M2A，不能替主题定义商家供给、俱乐部承接或可开跑判断。 |
| [Odoo](https://github.com/odoo/odoo) | 一个业务单据内将主生命周期、开票/资金和交付事实分开建模、分别展示。 | `sale.order` 的客户与订单交付不等于主题的内容主体、运营主办方和多方履约合同。 |
| [Backstage](https://github.com/backstage/backstage) | 对象中心页按真实能力挂载页签；缺少集成时显示原因明确的不可用态，而不是伪造空数据。 | 它是开发者门户的实体目录，不提供商业合作或履约领域模型。 |

因此，当前方案不应改为通用内容表单、全字段详情页或“全部主题”总览。主题仍应是运营主对象；`publisher`、`operation`、`governance`、`clubFulfillment`、`merchantSupply` 等只读投影继续按各自真源独立构建。这个结论与《主题运营后台_信息架构诊断与主题360工作台方案_2026-07-22.md》的状态轴、Gate 0 与白名单聚合方向一致，**不构成需要暂停实施并重做信息架构的发现**。

## Directus：最接近“主题＝内容对象”的参考

Directus 的单对象编辑页把版本菜单、协同、动态表单、预览、修订、评论、分享和流程侧栏挂在同一个 item 上；这支持主题工作台采用一个稳定对象壳、把内容与治理相关的明细留在后续页签/侧栏，而不是从主题跳到多个孤立菜单。[对象页源码](https://github.com/directus/directus/blob/b7bcfa4b7d418900394b57a49266cd4a3d50504e/app/src/modules/content/routes/item.vue#L1020-L1392)

其官方工作流说明以显式 `status`（draft / under review / published）和角色/字段权限控制发布，而不是把发布能力混入任意字段保存；版本说明还把已发布版本和 Draft 编辑语义区分开。[审核工作流](https://github.com/directus/docs/blob/6a39537ef20790dd7fb491aceb028b50e74bea3f/content/tutorials/7.workflows/build-content-approval-workflows-with-custom-permissions.md#L12-L85) [内容版本](https://github.com/directus/docs/blob/6a39537ef20790dd7fb491aceb028b50e74bea3f/content/guides/02.content/6.content-versioning.md#L9-L18)

但它同样提供了一个重要反例：官方访问控制文档明确指出，API 的 `null` 既可能是原始数据，也可能意味着访问受限；不能仅从 payload 的空值猜测状态或权限。[访问控制说明](https://github.com/directus/docs/blob/6a39537ef20790dd7fb491aceb028b50e74bea3f/content/guides/03.auth/2.access-control.md#L54-L59) Studio 还会直接移除无读取权限字段；其 item-permission 请求失败时甚至会以允许编辑/删除/分享的 optimistic fallback 继续 UI。[字段权限源码](https://github.com/directus/directus/blob/b7bcfa4b7d418900394b57a49266cd4a3d50504e/app/src/composables/use-permissions/item/lib/get-fields.ts#L20-L91) [权限请求错误处理](https://github.com/directus/directus/blob/b7bcfa4b7d418900394b57a49266cd4a3d50504e/app/src/composables/use-permissions/item/utils/fetch-item-permissions.ts#L30-L56)

对主题 360 的结论是：借鉴其“对象壳 + 有边界的动作/审计”即可；绝不能把缺字段、无权限、未接线、请求失败折算为 `0`、`关闭`、`正常` 或绿色状态。

## 两项交叉核对

Odoo 的 `sale.order` 把 `state`（报价/已发送/订单/取消）定义为主生命周期，同时独立定义 `invoice_status` 和交易集合；UI 用 `state` statusbar、发票入口和订单行的已交付数量分别呈现它们。[状态与开票字段](https://github.com/odoo/odoo/blob/3a5f7431effd4b2b2eb8ce3eed81aaba42fcd8ea/addons/sale/models/sale_order.py#L19-L31) [业务单字段](https://github.com/odoo/odoo/blob/3a5f7431effd4b2b2eb8ce3eed81aaba42fcd8ea/addons/sale/models/sale_order.py#L64-L81) [独立开票/交易投影](https://github.com/odoo/odoo/blob/3a5f7431effd4b2b2eb8ce3eed81aaba42fcd8ea/addons/sale/models/sale_order.py#L225-L280) [状态条与交付事实 UI](https://github.com/odoo/odoo/blob/3a5f7431effd4b2b2eb8ce3eed81aaba42fcd8ea/addons/sale/views/sale_order_views.xml#L379-L548)

这验证了当前文档的核心约束：生命周期、公开招募、实际承接、保证金、商家供给和治理不能共用一个“主题状态”。Odoo 不是主题工作台替代品，但其分轴规则支持现方案。

Backstage 的 `EntityLayout` 把 owner 与 lifecycle 放在对象头，并只在 predicate 成立时加入相应 route；其 TechDocs Router 在缺少注解/集成时主动渲染 `MissingAnnotationEmptyState`，而不是把页面伪装成无内容。[对象头与条件页签](https://github.com/backstage/backstage/blob/3aa8f7c87c6f2ad08c850844428f647bdca8a016/plugins/catalog/src/components/EntityLayout/EntityLayout.tsx#L133-L160) [条件 route](https://github.com/backstage/backstage/blob/3aa8f7c87c6f2ad08c850844428f647bdca8a016/plugins/catalog/src/components/EntityLayout/EntityLayout.tsx#L256-L283) [缺集成时的显式空态](https://github.com/backstage/backstage/blob/3aa8f7c87c6f2ad08c850844428f647bdca8a016/plugins/techdocs/src/Router.tsx#L102-L113)

这验证了“只展示已证实投影、其余明确不可用”的 UI 方向；不是要求为每一张主题都补全所有状态轴。

## 建议：把现有 boolean 门禁收紧为 capability 合同

这不是信息架构替代方案，而是当前白名单投影的实现级增强。每个工作台页签、指标卡或状态轴应由后端显式返回 capability，前端不得以字段空值自行推断：

```ts
type TopicAxisCapability = {
  state:
    | 'AVAILABLE'       // 有可回读的、口径确定的数据
    | 'NOT_APPLICABLE'  // 该主题天然不适用
    | 'NOT_GRANTED'     // 当前运营角色无权查看
    | 'NOT_INTEGRATED'  // 尚无关系模型或读模型
    | 'LOAD_FAILED';    // 请求/聚合失败，不能降级为“无数据”
  reasonCode: string;
  asOf?: string;        // 数据观察时间；只在 AVAILABLE 时可提供
};
```

使用规则：

1. 只有 `AVAILABLE` 可渲染业务数值、状态或可执行动作；空数组仅能表示“已成功读取且确实为零”。
2. `NOT_INTEGRATED` 显示“暂未接入”，`NOT_GRANTED` 显示“无查看权限”，`LOAD_FAILED` 显示“读取失败，可重试”；三者都不能显示为 `0`、关闭或绿色。
3. `NOT_APPLICABLE` 才可以不把该轴当作风险，但仍应保留明确原因，避免被误读为漏数。
4. `reasonCode`、数据源和 `asOf` 应进入 Gate 0 的异常样例与负控；这让“显式不可用”从页面文案升级为可测试的 DTO 合同。

该增强与文档既有“非空回读、缺字段显式不可用、负控必须变红”完全兼容，可在实现 `TopicWorkspaceDTO` 白名单投影时一并落实；无需先改菜单边界或推翻五个工作台页签。
