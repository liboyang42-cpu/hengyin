# 无形象伴随式漫游：GitHub 一手实现调研（2026-07-22）

## 结论

**没有找到会实质优于、或应当替换当前设计的整套开源方案。** 当前的取舍——地图/真实漫游事实为主、低频短文本为辅、无 AI 头像/名称/Chat/FAB、路线与地点内容走模板和审核——比“地图任务游戏”和“路线导航器”更符合目标。

外部源码只带来两项值得吸收的实现模式，且都不要求引入它们的库或产品形态：

1. **把内容显示资格配置化。** 用“可信事实条件 → 已审核模板 → 可显示”替代页面里散落的文案选择；这是 MapComplete 的 `condition`、`mappings`、`render` 组合所证明的成熟边界。
2. **把地图状态与文字状态分开。** 已探索、足迹边缘、普通区域是地图的纯视觉状态；只有通过频控和内容审核后，才可有一条文字。这是 MapLibre `feature-state` 的核心模式。

这两点是对现有设计的**实施约束补强**，不是产品方向变化：仍不加实时导航、任务清单、角色气泡、对话入口或模型调用。

## 对照基线

本调研以《漫游AI基座_无形象伴随式漫游体验设计_20260722》的已确认范围为准：

- 路线结算只回看真实、已结算的足迹；下一次进图只提示可继续探索的空间方向，不替用户导航。
- 打卡说明只来自可信或已审核内容；没有内容就不出现。
- 地图是空间表达，不能堆叠卡片、通知或拟人气泡；商家提示与 AI/内容层分轨。
- 首发仅模板与人工审核内容，零模型调用。

当前 worktree 的 `pages/roam/index.wxml` 已有原生地图的 `map-markers` / `map-polyline` / `map-polygons` 和互斥的 `roamPrompt` 槽位。因此下述“借鉴”均是概念/契约迁移，**不是把 Web 地图库、Android App 或导航 SDK 搬进微信小程序**。

## 一手来源与判断

| GitHub 一手来源 | 源码/README 已证明的实现 | 可借鉴 | 不应借鉴 | 对当前设计的影响 |
| --- | --- | --- | --- | --- |
| [MapComplete：`TagRenderingConfigJson` schema](https://github.com/pietervdvn/MapComplete/blob/develop/Docs/Schemas/TagRenderingConfigJson.schema.json)；[解析与校验实现](https://github.com/pietervdvn/MapComplete/blob/develop/src/Models/ThemeConfig/TagRenderingConfig.ts) | 配置可以声明 `condition`（不满足即不显示）、`mappings`（固定条件映射固定文案）和 `render`（模板回退）；实现会把条件解析成过滤器，并对 mapping 的 `if/then` 完整性做校验。 | **借内容选择契约**：以服务端已验证事件、POI 类型、审核状态、表面、冷却键为输入，选择一条已审核模板；无匹配即不渲染。 | 它的完整产品是 OSM 编辑器，`question/freeform` 和上传标签的能力会把漫游变成采集任务，不能引入。 | 不改变体验；补足“模板/审核优先”如何落到可测试规则，而非硬编码文案。 |
| [MapLibre GL JS：`feature-state` 官方示例源码](https://github.com/maplibre/maplibre-gl-js/blob/main/test/examples/create-a-hover-effect.html) | 示例保留 GeoJSON source 不变，仅用 `setFeatureState` 写瞬态状态，并由图层的 `feature-state` 表达式改变填充透明度。 | **借地图状态分层**：从已验证的 `RoamContext` 导出稳定 cell/区域 ID 的 `explored`、`frontier`、`ordinary` 视觉状态；状态变化不等于发一条提示。 | 不引入 MapLibre 或其 Web 渲染器；小程序继续用原生 `<map>` 的 marker/polyline/polygon 能力实现同一语义。也不把 hover 动画照搬为抢注意力效果。 | 不改变“地图优先”；使“足迹边缘”成为空间状态，而不是另一张卡或导航指令。 |
| [StreetComplete README](https://github.com/streetcomplete/StreetComplete/blob/master/README.md)；[Quest 控制器](https://github.com/streetcomplete/StreetComplete/blob/master/app/src/commonMain/kotlin/de/westnordost/streetcomplete/data/osm/osmquests/OsmQuestController.kt) | README 明确说明它主动寻找附近待调查地点、在地图放 quest marker，并要求用户现场回答问题以编辑 OSM。 | 仅可把“基于已验证地点决定是否显示”的先决条件视为反例校验。 | 不借整套 quest marker、问题流、可完成任务清单或数据采集闭环；这会把“安静回应已发生漫游”改写成“系统不断派任务”。 | **明确不改变当前设计**；它证实地图优先可行，但交互动力与本项目相反。 |
| [Mapbox GL Directions API](https://github.com/mapbox/mapbox-gl-directions/blob/master/API.md) | 控件以 origin/destination、waypoint、driving/walking/cycling profile、路线 alternatives 和 turn-by-turn instructions 为中心。 | 无。最多只借“路线与用户行走记录必须区分”的反向边界。 | 不引入 Directions、规划路线、目的地输入、路线备选、逐向指令或路况；这会越过“不做实时导航、用户自己决定如何继续走”的明确边界。 | **明确不改变当前设计**；路线故事和足迹方向不是导航产品。 |

## 两个可直接落地的细节

### 1. 受审核提示规则必须先过滤，再选模板

MapComplete 的可取之处不是 UI，而是它把“是否应出现”和“出现什么”都变成可验证配置。对漫游只保留这个最小结构即可：

```text
已验证 RoamContext
  → surface / event / POI 类型 / footprint 状态 / cooldown 条件
  → audit_status=已审 的固定模板
  → 当前互斥 prompt 槽位，或静默
```

边界应保持严格：

- 规则不会请求模型、推断地点事实或给商家排序；模板文本只能引用结构化、已验证字段。
- `audit_status != 已审`、条件不满足、频控命中、或已有更高优先级表面时，都输出“无提示”，不占位。
- 不把 MapComplete 的问答、自由输入、地图编辑、上传语义带入城瘾。

这恰好把当前设计稿的“无来源即不显示”“模板/审核优先”“低打扰”变成同一条可测选择链。

### 2. 足迹地图只表达状态，不表达命令

MapLibre 的示例将视觉高亮视为 feature 的瞬态状态，而不是改写底层几何或生成一个新的交互流。映射到当前原生地图时，应只保留三种空间含义：

| 视觉状态 | 事实来源 | 允许的表达 | 禁止的表达 |
| --- | --- | --- | --- |
| 已探索 | 已结算会话/已验证格子 | 已走过区域或足迹 | “你必须返回这里” |
| 足迹边缘 | 本地历史的断点与相邻未探索区域 | 轻量高亮；最多一条中性说明 | 箭头、ETA、转向、路线规划 |
| 普通 | 无已验证增强事实 | 保持底图 | 为填满界面而冒出卡片/提示 |

因此地图可以更有“陪伴感”，但不需要增加任何角色、聊天入口或新的浮层。当前设计稿已要求“足迹推荐只高亮区域、不画路线、不用方向性动词”，这项源码借鉴与其一致，而非替代它。

## 为什么其他相近开源路径不更优

1. **地图任务型（StreetComplete）**的地图优先和现场事实校验值得尊重，但其核心价值是“发现附近待办并完成问题”。这会持续唤起用户、制造任务压力，与“没有值得解释的真实事件就安静”相冲突。
2. **地图导航型（Mapbox Directions）**天然围绕目的地、路线计算和逐向指令设计。即使关闭部分控件，数据模型和用户预期仍是“系统带我走”，与“只回看已发生的漫游”相冲突。
3. **地图编辑型（MapComplete）**有最好的声明式规则边界，但完整主题/问答/编辑栈远超需要。只取其条件化模板选择，不取其编辑产品。
4. **通用渲染型（MapLibre）**适合解释视觉状态如何分层，不是小程序地图依赖替换方案。

## 最终建议

维持当前设计，不做产品级改向。若后续进入“新地点意义 / 足迹推荐 / 地图文字密度”三项未定规格的实施设计，只补写以下两条验收即可：

1. 每条可见文本都能从“已验证事实 + 已审核模板 + 条件/频控判定”回溯；任一项缺失时静默。
2. 每个地图增强元素都有可读的空间状态来源；它不能同时成为导航箭头、任务入口或商家导购。

这比采用任何外部完整项目更小、可审计，并且不会破坏现有“地图 + 文本提示”的产品边界。

## 来源核验说明

以上均为项目维护者在 GitHub 发布的 README、schema 或源码（非二手产品文章），于 2026-07-22 读取。文中只总结实现边界，未复制第三方代码；所有建议均适配现有微信原生地图，而非引入外部运行时依赖。
