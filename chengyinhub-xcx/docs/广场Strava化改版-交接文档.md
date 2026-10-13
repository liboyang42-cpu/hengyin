# 广场「运动 feed」改版 · 技术交接文档

> 目标：把「创意广场」从图文社区升级为 **Strava 风格的运动 feed**——发布动态时可关联「已完成的活动」，并在 feed 卡片 / 详情页展示该活动的整体完成状态（距离 / 完成节点 / 地图轨迹 / 通关）。
> 视觉参考：Figma 文件 `ku2oaN9ag1XUMC9lKNnrcr`，画板 `Home`（node `3700-11775`，Strava 深色 feed）。
> 本次范围：① 发布编辑器加「选择活动」入口（P1）；② 发布编辑器换肤（P2）；③ feed 卡片 + 详情页换肤并展示运动状态（P3）。三处页面均已存在，**是升级改造，不是新建**。

---

## 0. 一句话结论

> 你要的能力（路线距离 / 完成节点 / GPS 轨迹 / 通关）**后端已经在「主题(topic)+打卡(play)」业务线里实现了**，本次改版的核心工作是：
> **(A) 前端：发布器加「选择活动」+ 三个页面换深色皮 + 卡片渲染运动状态；**
> **(B) 后端：把广场帖子和「活动」关联起来，并让 `list/info` 接口吐出活动状态字段。**

---

## 1. 现状代码地图（改动涉及的文件）

| 角色 | 文件 | 关键点 |
|---|---|---|
| 广场 feed 展示 | `pages/square/list/index.{js,wxml,wxss}` | `getList()` → `POST /api/creativesquare/list`；卡片在 `index.wxml:52` 起 |
| **发布编辑器（P1/P2）** | `components/touch/index.{js,wxml,wxss}` | 「创意」浮层 `popOrig`（wxml:78 起）；`submitFormData()`(js:323) → `POST /api/creativesquare/action`；按钮行 `.btn-list`(wxml:130) |
| 详情页（P3） | `pages/square/detail/index.{js,wxml,wxss}` | `POST /api/creativesquare/info` + 评论 |
| 主题/路线（数据源·复用） | `pages/topic/index/index.js` | 路线轨迹构建 `js:909+`；`<map>` at `wxml:305`；`routeMarkers/routePolyline/routeGeometry` |
| 打卡/节点（完成进度·复用） | `pages/play/index.js` | `GET /api/play/nodes` → `{topicId,total,doneCount,nodes[]}`；`POST /api/play/checkin` → `{completed}` |
| 活动列表（选择活动·数据源） | `/api/activity/list` | 用例见 `pages/index/index.js:1251`；参数 `{is_my,pageNum,status}` |

### 1.1 广场帖子（creativesquare）现有字段
`memberId / memberAvatar / memberNickname / rz(认证) / contents / link / pics(分号串→picList) / createTime(→formattedCreateTime) / likeCount / isLiked(0未/1赞/2踩) / commentCount / id / isFollowTheUser`
> ⚠️ **没有任何 activity / 距离 / 路线 / 节点 字段** —— 这是本次后端要补的核心。

### 1.2 发布提交现有字段（`submitFormData` → `creativesquare/action`）
`contents / pics / file_url / address / longitude / latitude / is_crowdfunding`

---

## 2. 设计规范（已用 Figma MCP 实测提取，非猜测）

**Design tokens**（`get_variable_defs`）+ feed 节点 design context 实测色值：

| 用途 | 值 |
|---|---|
| 页面背景 `Neutral/0` | `#0a0300` |
| 卡片面 | `#1c1310` / `#251b18` |
| 描边 / 分隔 | `rgba(255,255,255,.08)`、`rgba(255,255,255,.04)` |
| 主文字 `Labels/Primary` | `#ffffff` |
| 次级文字 | `#543a31` / `#53362c` |
| 强调色 `Neutral/50` | `#ff4400`（珊瑚橙，feed 里另见 `#ff8d80`） |
| 链接蓝 | `#0078f0` |
| 蒙层 | `rgba(14,4,1,.64)` |
| 字阶(px) | 32 / 24 / 20 / 16 / 14 / 12 / 11 |

**feed 卡片解剖**（Figma `Home` 3700-11775）：
头像+昵称+时间 → 活动标题+🏆挑战角标 → **三栏指标(距离/配速/时长)** → 地图轨迹+实景图 → 挑战完成横幅 → 点赞/评论/分享 + kudos 头像堆叠 + 内联评论。

---

## 3. 字段映射（Strava → 程引领域）★已与产品确认

| Strava 元素 | 本项目对应 | 数据来源 |
|---|---|---|
| Distance 距离 | 主题路线距离 | `activity.distance` / 主题路线 |
| **Pace 配速** | **完成节点 `doneCount/total`** | `GET /api/play/nodes` |
| Time 时长 | 活动时长 / 发布时间 | activity.duration / createTime |
| GPS 路线图（橙线） | 已实现的轨迹 | 主题 `routeMarkers/routePolyline/routeGeometry` |
| 挑战完成横幅 | 通关达成 | play `completed`（doneCount==total） |
| kudos 点赞 | 点赞 + 点赞者头像 | likeCount（头像列表待后端补） |
| 内联评论 | 最新 1 条评论 | 待 list 接口补 |

---

## 4. 前端改动清单（谁做：前端）

### P1 ·「选择活动」入口  —— `components/touch/index.*`
在「创意」发布浮层里新增选择已完成活动的能力。

**wxml**（`popOrig` 浮层内）
1. `.btn-list`(wxml:130) 增加第 4 个按钮「活动」：
   ```xml
   <button bindtap="openActivitySheet"><image src="/images/icon_flag.png" mode="widthFix" /></button>
   ```
2. 仿 `address` chip（wxml:118），在选中后展示一条「活动状态 chip」：
   ```xml
   <view class="file-list activity" wx:if="{{selectedActivity}}">
     <view class="li flex-cb">
       <view class="tit ep1">{{selectedActivity.name}} · {{selectedActivity.distance}}km · 完成 {{selectedActivity.doneCount}}/{{selectedActivity.total}}</view>
       <view class="close"><image catchtap="clearActivity" src="/images/close2.png" mode="widthFix" /></view>
     </view>
   </view>
   ```
3. 新增「活动选择」底部弹层（列出我的已完成活动，单选）。

**js**
- `data` 增：`activitySheetShow:false, activityList:[], selectedActivity:null`
- `openActivitySheet()`：拉「我的活动」列表（见 §6 开放问题，先按 `POST /api/activity/list {is_my:1, status:已完成}`），对每条可并发 `GET /api/play/nodes?activityId=` 拿 `total/doneCount`。
- `selectActivity(e)`：写入 `selectedActivity`，关弹层。
- `clearActivity()`：置空。
- `submitFormData()`(js:323) 的 `data` 增 `activityId: that.data.selectedActivity ? that.data.selectedActivity.id : ''`。

### P2 · 发布编辑器换肤 —— `components/touch/index.wxss`
按 §2 token 把 `popOrig` 浮层改深色：背景 `#0a0300`，输入区/卡片 `#1c1310`，文字白 + 次级 `#543a31`，按钮/分隔线用 `rgba(255,255,255,.08)`。结构不动，仅改色与间距贴 Figma。

### P3a · feed 卡片换肤 + 运动状态 —— `pages/square/list/index.*`
- wxss：整页换深色（§2）。
- wxml：当 `item.activityId`（或后端返回的 `item.activity`）存在时，在正文下方插入「运动状态块」：
  - 三栏指标：`距离 {{item.distance}}km` / `完成节点 {{item.doneCount}}/{{item.nodeTotal}}` / `时长`
  - 地图轨迹：`<map>` + `item.routePolyline/routeMarkers`（逻辑直接搬 `pages/topic/index/index.js:909+`，建议抽成公共组件 `components/route-map/`）
  - `item.completed` 为真时显示「🏆 已通关」横幅
- 无 activity 的老帖 → 保持纯图文卡（**两种卡片形态，必须降级**）。
- 可选：kudos 头像堆叠、内联最新评论（依赖后端字段）。

### P3b · 详情页换肤 + 完整状态 —— `pages/square/detail/index.*`
- 换深色皮；activity 存在时展示全屏地图轨迹 + 全部节点列表 + 打卡进度 + 通关横幅；保留现有评论区。

> 建议：把「运动状态块 / 路线地图 / 完成进度」抽成 **可复用组件**，list 卡片、detail、发布 chip 三处共用，避免三份重复逻辑。

---

## 5. 后端改动清单（谁做：后端）★前端被这部分阻塞

1. **`creativesquare` 表新增 `activity_id`（可空）**。
2. **`POST /api/creativesquare/action`**：接收并存储 `activityId`。
3. **`POST /api/creativesquare/list` 与 `/info`**：当帖子关联 activity 时，按帖子返回：
   ```jsonc
   {
     "activityId": 123,
     "activityName": "黄浦江夜骑",
     "distance": 24.7,            // 主题路线距离
     "nodeTotal": 5,             // 来自 play
     "nodeDoneCount": 5,         // 来自 play
     "completed": true,          // 是否通关
     "routePolyline": [...],     // 复用主题已算好的轨迹；或只返回 topicId 让前端二次拉
     "routeMarkers": [...],
     "kudosUsers": [{ "avatar": "..." }],   // 可选：点赞者头像
     "latestComment": { "nickname": "...", "content": "..." } // 可选：内联评论预览
   }
   ```
4. **（可选）顶部周报**：`GET /api/creativesquare/summary` → 本周活动数 / 总时长 / 总距离 + 同比趋势（Figma 顶部三栏；无历史可先隐藏趋势）。
5. **确认「我的活动」列表接口**：`/api/activity/list?is_my=1` 能否筛出「已完成」并带 `name/distance/cover`；若不能，需新增专用接口（见 §6）。

---

## 6. 待确认的开放问题（交接时请拍板）

1. **「选择活动」数据源**：用 `/api/activity/list {is_my:1, status:已完成}`，还是基于 play 的「我打卡过/已通关」列表？目前 `activity/list` 未见「已完成」筛选条件，需后端确认或新增。
2. **轨迹下发方式**：`list/info` 直接内联 `routePolyline`（卡片即时渲染、但数据量大），还是只给 `topicId`、前端进详情再拉（列表轻、但卡片地图需懒加载）？建议列表给 `topicId`+静态预览图，详情页才渲染交互地图。
3. **周报头部**：本期是否要做顶部「活动数/时长/距离」统计条？无历史聚合则先不做趋势。
4. **老数据降级**：确认无 activity 的历史帖子在 feed 里走「纯图文卡」形态（已按此设计）。

---

## 7. 建议落地顺序

1. 后端先开 `activity_id` 字段 + `action`/`list` 接口契约（§5.1~5.3）——解阻塞。
2. 前端并行：抽 `components/route-map/` 公共组件（搬 topic 轨迹逻辑）。
3. P1 选择活动入口 → P2 发布器换肤 → P3a 卡片 → P3b 详情。
4. 每步在微信开发者工具模拟器截图自测（广场 tab：`pages/square/list/index`）。

---

## 附:Figma 资源
- 广场 feed（展示态参考）：`figma.com/design/ku2oaN9ag1XUMC9lKNnrcr?node-id=3700-11775`
- 首页改版（同套深色规范参考）：node `3565-11390`
- 切图/取色用 Figma MCP：`get_design_context` / `get_variable_defs`（已验证可用）。
