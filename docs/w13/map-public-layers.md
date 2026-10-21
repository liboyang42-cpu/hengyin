# W13 MapPublic 只读图层（候选合同，本组冻结）

本文冻结的是**新增**候选入口，不是已经上线的接口。`POST /api/map/nearby` 仍是旧的 App 玩家地图：`status` / `auditStatus` 可选并原样透传，SQL 只强制 `del_flag = '0'`。那个旧合同不因本文改变，也不被本文冒充。

候选路径：`POST /api/map/public-layers`。  
鉴权：不改 `SecurityConfig`。现有 `anyRequest().authenticated()` 已经要求登录；本文不新增 `permitAll`，也不提供匿名降级。调用方必须是已登录的 App 玩家（`getAppUserId()`）。后台账号令牌不能代替玩家身份。  
生产：本文件描述的是源码候选。它不表示生产已启用、不附带密钥、不包含 DDL，也不授权对生产库调用。

## 请求

`Content-Type: application/json`。字段都必须由调用方显式给出。服务端不把缺省半径、缺省层或客户端曾经出现过的 0.5° 包围盒补成业务规则。

| 字段 | 含义 |
| --- | --- |
| `datum` | 坐标基准。存储侧玩家坐标沿用既有 GCJ-02 链路，因此只接受字面量 `GCJ-02`。缺省、空串或其他基准一律拒绝，不做换算。 |
| `minLat` `maxLat` `minLng` `maxLng` | 有限十进制。纬度 [-90, 90]，经度 [-180, 180]，且 min ≤ max。不跨日期变更线。 |
| `limit` | 每一层本页条数，整数，至少 1，且不超过 `min(配置页大小, 硬上限)`。当前配置 50，硬上限 100。超过即拒绝，不改写成较小值。 |
| `layers` | 按请求顺序的来源名，至少 1 个，不超过 `min(配置层数, 硬上限)`。当前配置 5，硬上限 8。重复或未知名称拒绝。 |
| `cursor` | 省略或 JSON `null` 表示各层第一页。空串、缺字段、错版本、query 任一字段变化、玩家身份变化或可见水位变化都拒绝，不退回第一页。 |

面积 = 纬度差 × 经度差（平方度）。配置上限 `1`，硬上限 `4`，生效值取二者中较小者。这是滥用封顶，不是 0.5° 产品半径。0.5° × 0.5° 只有在调用方自己写进 bbox 且没有超过封顶时才会被执行。

## 响应

`AjaxResult` 成功时 `data` 为：

- `datum`：回显 `GCJ-02`。
- `cluster.supported`：`false`。没有可复用的聚类算法，不返回簇个数或假坐标。
- `layers[]`：与请求顺序一致。每层有 `sourceType`、`status`、`reason`、`capabilities`、`items`。
- `nextCursor`：还有任一**可读**来源可能存在下一页时给出；全部读完、全部不可读，或任一可读来源本次失败时为 `null`。

`status`：

- `OK`：本页只含该来源既有玩家公开条件选出的行。
- `UNAVAILABLE`：没有权威玩家公开条件。`items` 为空。不会先查出全表再让客户端隐藏。
- `FAILED`：该来源本次读取失败。`items` 为空，`reason` 为 `SOURCE_FAILED`。其他层仍返回本页。不写库。用**同一个** cursor 重试。

`capabilities.read / count / cluster` 是**层**的读取能力，在本版可读来源上分别是 `true / false / false`。层可读不等于条目可报名或可导航。没有与公开条件相同的计数查询，因此不给 `total`。`items.length` 不是总数。

`aggregatedQueryEnabled` 固定为 `false`。请求里没有这个字段，响应也不提供打开它的开关。本入口按层分页，不是聚合查询。

每条 `items[]` 在 `OK` 页上还必须有：

| 字段 | 含义 |
| --- | --- |
| `visibilityVersion` | 这一行的可见版本，字符串。不是游标水位 `map-public-v1`，不是客户端序号，也不是写死的 `1`。 |
| `visualState` | 这一行的视觉状态。没有状态的行不会以 `available` 补上。 |
| `actions` | 这一行已经能证明的动作名。没有的动作不出现，也不给 `false`。 |

版本来源：`store` 与 `activity` 用各自表上已有的 `update_time`（`yyyy-MM-ddTHH:mm:ss`，秒精度，与商家更新和活动更新写入的同一列）。`roam_poi` 没有 `update_time` 或版本列，读取 `visibility_version`。该列的候选 DDL 在 `docs/w13/map-public-visibility-version.sql`，只写不执行，没有 `DEFAULT 1`。空版本不发布：该层本页为 `FAILED`，`reason` 为 `VISIBILITY_VERSION_ABSENT`，`items` 为空，用同一个 cursor 重试。列尚未落库时，查询失败同样是 `FAILED` / `SOURCE_FAILED`，不改用 `1`。

视觉状态：

| sourceType | visualState | 依据 |
| --- | --- | --- |
| `roam_poi` | `pending_redeem` / `found` / `unfound` | 当前玩家的 `pendingRedeem`，否则 `found`，否则未发现。两列缺失则本页 `VISUAL_STATE_ABSENT`。 |
| `store` | `open` / `closed` | `business_status = 0` 为打烊；空值与 `1` 为营业中，与既有打烊门禁一致。其他值没有状态。 |
| `activity` | `notStarted` / `running` / `completed` | `ActivityLifecycle.timeBucket(start_date, end_date)`。公开闸已经排除取消、未过审、下架和已结束场次。 |

动作名只有 `navigate` 会在具备依据时出现：`store` 且 `location_verified = 1`（商家自己地图选点）。推断坐标、未定位、漫游点和活动都不给 `navigate`。`register` 是报名动作名，本读取没有票档、打烊归属和已报名事实，**不出现在 `actions` 里**。报名仍走活动详情上的既有门禁。本接口不请求定位，也不调用报名或导航服务。

条目主键是 `sourceType + sourceId`。不同表里相同的数字 id 不会合并，也不按数字 id 跨表排序。每一层内部按自己的 `sourceId` 升序做有界 keyset（`id > afterId`）。

`found` 与 `pendingRedeem` 只在 `roam_poi` 上按**当前** App 玩家投影。其他来源这两列为 `null`，不是「未发现」。

## 游标

游标绑定：规范化后的 datum、bbox、limit、layers 顺序、App 玩家 id、可见水位 `map-public-v1`，以及每个可读来源的页位置。位置要么是上一页最后的 `sourceId`，要么是 `*`（该来源已读完）。不可读来源不进入游标。缺失、错序、身份不符或水位不符都拒绝。

水位 `map-public-v1` 是这组公开规则的版本。规则语句变化时必须改水位，旧游标失效。它不是逐项 `visibilityVersion`，也不是运营状态或签到水位。

## 来源

| sourceType | 本版 | 依据 |
| --- | --- | --- |
| `roam_poi` | 可读 | 复用 `RoamMapper#selectPoisNear` 的 `p.status = 1` 与 `cityNodeContentApproved`（`application_type` 为空或 2，或 `application_type = 1` 且 `audit_status = 1`）。`found` / `pendingRedeem` 按 `viewerId` 投影。不使用管理员 `selectPoisByIds`。 |
| `store` | 可读 | 复用玩家公开列表 `ApiMerchantController` 公开列表强制的 `status = 1 AND account_status = 1 AND del_flag = 0`，再加 bbox 与 keyset。只返回公开主页已有的点位字段。 |
| `activity` | 可读 | 复用 `selectPublicCmsActivityList` 写死的公开闸，并按 `PublicActivityScope.PUBLIC_SHELF` 排除社群活动、只留未结束场次。该枚举把地图列在公域货架的调用方里。 |
| `theme` | `UNAVAILABLE` | `selectPublicCmsTopicList` 没有单一公开坐标。`selectNearbyTopics` 少了 `primaryCirclePublicGate` 和 `clubMemberOnlyGate`，不能当作权威公开条件。 |
| `story_node` | `UNAVAILABLE` | `status = 1` 且 `audit_status = 1` 不能证明未来剧情节点可公开。不得改名成 `sideQuest`。`sideQuest` 是未知层名，请求会被拒绝，不会被读成剧情节点。 |

`cityNodeContentApproved` **不包含**商家启用或删除条件。门店是否公开只由 `store` 来源单独核对，不宣称 roam 片段已经包含。

`cms_registration_merchant.status` 是 0 待审 / 1 审核通过 / 2 失败，`auditStatus` 是 0 待调配 / 1 中标 / 2 落选。旧地图注释里的「活动状态 / 审核状态」不能拿来当公开闸。该表不是本入口的来源；请求这个名字会被拒绝，不会把 `del_flag = 0` 的全部行放出来。

W20 和未批准支线没有实现。

## 读写

成功、拒绝、失败和重试都不写签到、奖励、presence 或运营状态。`MapPublicMapper` 只有查询。

未登录请求由现有安全链返回 HTTP 401。已登录但不是 App 玩家时，响应体 `code` 为 401。参数或游标拒绝时，响应体 `code` 为 500，`msg` 为稳定代码（如 `CURSOR_REJECTED`），不带回第一页数据。

聚类不支持。隐藏行不得进入 `items`；本版也没有计数或聚类字段可以混进隐藏行。

## iOS wire

给 iOS 分页适配的字段合同。`aggregatedQueryEnabled` 保持 `false`。不新增权限，不在这条链路上请求定位或下游服务。

请求 JSON：`datum`、`minLat`、`maxLat`、`minLng`、`maxLng`、`limit`、`layers`、`cursor`。`datum` 只接受 `GCJ-02`。`sourceId` 是正整数。每一层 `limit` 为 1 到配置页大小 50（硬上限 100，超过拒绝）。`layers` 名字只使用下表，按请求顺序，不按客户端另排。

| wire `sourceType` | 含义 |
| --- | --- |
| `roam_poi` | 漫游点 |
| `store` | 门店 |
| `activity` | 可报名活动的公开货架行。可读仍不等于可报名 |
| `theme` | 层状态 `UNAVAILABLE`，不是空的 `OK` 页 |
| `story_node` | 层状态 `UNAVAILABLE`。不是 `sideQuest` |

去重键是 `sourceType + sourceId`。同一数字 id 在不同来源是两条。排序只在层内按 `sourceId` 数值升序。`FAILED` 时 `nextCursor` 为 `null`，用**请求里的同一个** cursor 重试，不把失败页当成空页翻过去。`UNAVAILABLE` 的 `items` 为空，但 `status` 不是 `OK`。账号、bbox、limit、层顺序或水位变化都拒绝旧 cursor。

`OK` 条目除点位字段外固定带 `visibilityVersion`、`visualState`、`actions`。`actions` 是字符串数组，当前词汇只有 `navigate`；`register` 不输出。层上的 `capabilities.read` 不是条目动作。
