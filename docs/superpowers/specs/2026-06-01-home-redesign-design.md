# 城瘾首页大改 · 设计规格

> 日期：2026-06-01 ｜ 分支：master ｜ 页面：`chengyinhub-xcx/pages/index/index`
> 基准参考图：`~/Downloads/小程序UI参考/`（IMG-6921、IMG-8390、IMG-8395 为结构来源；6921 + 8393 为整体风格基准）
> 世界观约束：深空 + 节点/路线母题 + 电光蓝（见 plan-chengyin-worldview-upgrade）

## 目标

把首页从"整屏 snap 翻页、显空、不满意"改成参考图那样的**连续密排、有门面、有节奏**的消费级首页。以用户给的 10 张参考图为基准全改，但**现有信息一条不丢**。

## 核心结构变更（最重要）

**整屏 snap 翻页 → 连续密排滚动。**
- 现状：每个区块 `snap-page` + `min-height: 一屏`（`_vh`），一屏一屏翻，造成大量空白。
- 改为：所有区块在一条连续滚动流里，靠体量阶梯（巨 → 中 → 小）产生节奏，而非整屏留白。
- 文字 hero 的"漂星空"气质并入页面背景，不再单独占一屏。

## 信息清单（现状真源，全部保留）

| 现区块 | 数据字段 | 必须保留的信息 |
|---|---|---|
| idx1 + idx-hero | userInfo | 用户入口、问候 |
| idx-banner | bannerList | banner 横滑 + 轮播 |
| idx3 为你推荐主题 | recommendedTopicList | 图/标题/时间/地址/分类标签(sysCategoryList)/日期角标(day,month)/isLike |
| idx4 附近活动 | nearbyActivityList | 名/图/时间/地址/距离(distance) |
| idx5 即将上线 | upcomingActivityList | 名/图/时间 |
| idx6 底部 feed | bottomTopicList / bottomActivityList | 主题·活动分段、cat 标签、标题、分类 tag、¥价格、地址、formattedDateTime、点赞、分享、吸顶 tab、"没有更多了" |
| `<tabBar raised>` | — | 5 tab（已是自定义组件，可做浮动发光）|

## 区块规格（自上而下，连续滚动）

### ① 顶部问候 — 参考 IMG-8395
- 头像（圆，置于上方）→ `Hi, {昵称}` 巨号粗字 → 副行 `晚上好 · 6月1日 周日`（蓝色节点 dot 分隔符）
- 右上角：搜索图标 + 消息/通知图标
- 吸收原 idx1 用户入口与 idx-hero 文字；文字 hero 气质转为背景星空，不再单独占屏
- 数据：userInfo（昵称、头像）；日期/问候语本地计算

### ② Banner — 参考 IMG-8395
- bannerList 横滑，圆角大卡 + 轮播 dots + 蓝色 CTA
- 数据：bannerList（沿用 bannerClick / data-inx）

### ③ 推荐主题 — 参考 IMG-6921「Casino's on Live」卡（用户点名照搬）
- 横滑大媒体卡，露出第 2.x 张（peek）
- 单卡解剖：全幅活动大图 + 顶部主理人头像/名 + 底部叠层大标题(主题名) + **报名人数**（替代 6921 的 LIVE/Follow）+ 左上日期角标(day/month) + 右上节点 dot（cy-node）
- meta 行保留：时间、地址、分类标签（sysCategoryList → cy-tag）、isLike
- 数据：recommendedTopicList（字段不变）

### ④ 即将上线 — 参考 IMG-8390 紫色 feature 卡（已确认形态；城瘾换蓝系渐变）
- 大 feature 卡，可横滑多张（peek）
- 单卡解剖：左侧 `即将上线` 标题 + 活动名 + 日期(formattedDateTime)；右侧活动图；底部操作行 `▶ 提醒我 · ♡ · ↗`
- section header：`即将上线` + "换一批/查看全部"（参考 8392 refresh 图标）
- 数据：upcomingActivityList（沿用现有条件渲染 length!=0）

### ⑤ 附近活动 — 列表行（用户确认）
- 形态：参考 IMG-8390「Top daily playlists」列表行——缩略图 + 名 + 副信息（距离/时间） + 右侧动作
- 与 ④ 的大 feature 卡形成大小节奏对比
- 数据：nearbyActivityList（不再两列交错；distance/时间/地址保留）

### ⑥ 主题/活动 feed — 现有保留 + 增强
- 吸顶分段控件（主题/活动），**选中态 = 蓝光描边**（世界观全局选中语言）
- feed 卡保留全部信息：图 / 主题·活动 cat 标签 / 标题 / 分类 tag(sysCategoryList) / ¥价格 / 地址 / formattedDateTime / 点赞 ♡ / 分享 ↗
- 增强：右上角节点 dot（cy-node）、obsidian `#0A090D` + 1px hairline 卡面
- 保留 "没有更多了" footer 与吸顶逻辑（isTabSticky / switchTab）

### ⑦ 底部导航 — 参考 IMG-6921 浮动发光药丸（用户点名）
- `<tabBar>` 已是自定义组件 → 改为：悬浮药丸形 + obsidian 半透明 + blur + active 态 = 蓝光发光节点（cy-node 语言）
- 5 个 tab 路径与功能不变：index / square/list / template / talent/list / member/index
- raised 凸起逻辑（_navUp）保留评估

## 视觉语言（基准 6921 + 8393）

- 底色：深空黑 obsidian `#0A090D`
- 强调：电光蓝 `#0052F4` + glow（`0 8px 24px -6px rgba(0,82,244,.7)`）
- 描边：1px hairline `rgba(255,255,255,.08)`
- 圆角：12px（24rpx）
- 字体：拉丁/数字 = Bebas Neue + DM Sans（已 `wx.loadFontFace` 加载）；中文随系统 + font-weight 800；巨幅 section 标题取 8393 编辑体气质
- 母题：节点 dot（cy-node 四态）+ 全局选中=蓝光描边，贯穿卡片/标签/分段/nav

## 组件复用（M1 已落地，直接用）

- `components/cy/node` — 节点 dot（idle/active/done/selected + 慢脉冲）→ 卡片角标、nav active、分区标记
- `components/cy/tag` — 标签（blue/red/done/mono + 选中蓝光）→ 分类 tag、cat 标签
- `components/cy/btn` — 按钮（blue glow / secondary / red / disabled）→ banner CTA、报名/提醒我

## 硬约束（不可违反）

1. 报名→支付→核销闭环脆弱：首页只换"壳/排版"，零业务逻辑、零 API 契约改动
2. `publish/activity` 创建流程锁定：不进本次重构
3. 商家页 merchant：不改（用户明确）
4. 暗色值消费 tokens；与 Bible 不符处暂硬编码蓝，待对齐
5. git 手动提交、按文件名精确 add，绝不 `git add -A`（仓库有并行后端未提交改动）

## 范围外（本次不做）

后台改动、WebGL/AR、复杂动效系统、社交扩展、后端逻辑重构。

## 验证方式

wechat-miniprogram-devtools 驱动模拟器编译 + 截图自验证；首页列表数据用脚本侧 `setData` 注入 mock（零生产代码改动）；截图存 `~/Downloads/小程序截图/`。逐区块"截图→分析→复述→改→截图验证"闭环。

## 下一步（按用户协作铁律：创意 → Figma → 工程）

1. 本规格用户确认
2. 出 Figma 首页结构帧（连续滚动版，含 7 区块）
3. 工程化：先抽区块组件外壳，再逐区块换皮接入，逐屏截图验证
