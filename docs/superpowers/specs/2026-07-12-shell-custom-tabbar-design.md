# 批3 · Role Shell 统一自定义 tabBar 设计

> 2026-07-12 · 分支 `feature/shell-custom-tabbar` · 来源:城瘾 Questify 页面架构与模板库 v1.0 §1.5 + 修复TODO清单 批3
> 前置拍板:B-1 方案A(双端统一自定义 tabBar)、B-2(取消中心凸起,五 tab 等权)

## 1. 问题

一级导航当前双分叉,是第一阶段 P0-01 的工程根源:

- **玩家端**:`app.json` 原生 tabBar(`switchTab`),同时 5 个玩家页又各自在页内画了一份 `<tabBar>` 组件 → **双栏叠加**。
- **商家端**:非原生 tabBar,页内画 `<tabBar mode="merchant">` + `reLaunch` 跳转。
- 结果:导航栈行为、主题能力、图标机制三处分叉;"Role Shell 是一级导航唯一来源"不成立。

现有资产:`components/tabBar` 组件**已同时内置** `CONSUMER_LIST` 与 `MERCHANT_LIST`、mode/dark/activeKey/raised 逻辑;consumer 用 switchTab,merchant 用 reLaunch。即渲染早已统一,分叉只在**注入机制**。

## 2. 决策:WeChat 官方 `custom: true`,不拆原生、不碰 switchTab

关键约束:全库 **79 处 `wx.switchTab`(38 文件)** 指向 5 个玩家 tab 页。若改走 reLaunch 统一,会全部断裂。因此保留 switchTab 语义。

`app.json` → `tabBar.custom: true` 的效果:隐藏系统默认 tab 条(消灭双栏),但 `switchTab` 与页面 keep-alive **照常**;WeChat 自动在 5 个声明 tabBar 页底部注入根组件 `custom-tab-bar/index`。

- **79 处 switchTab:零改动**(custom:true 不改 switchTab)。
- **keep-alive 保留**(优于 §1.5 预期的"自管状态/闪烁"代价)。

### WeChat 结构限制(无法回避)

双角色共 >5 个不同 L1 页,`app.json tabBar.list` 只允许 2–5 项。故商家 5 页无法都成为声明 tab 页,WeChat 不会给商家页自动注入 `custom-tab-bar`。→ **商家保持页内 `<tabBar mode="merchant">` + reLaunch,不变。** 渲染同一组件,仅注入方式按角色不同。方案A 已认这条。

## 3. 架构(施工中修正:保留页内栏,shim 杀原生)

> **修正原因(施工勘察)**:`template/index`、`member/index/index` 是**双角色共享页**,靠页内 `<tabBar mode="{{isMerchant?'merchant':'consumer'}}">` 按自身状态渲染对应栏;`square/list` 亦有 `dark="{{!isMerchantView}}"` 双态。WeChat 自动注入的 `custom-tab-bar` 是**单例**,无法为"同一页两角色"渲染对应栏。故页内 `<tabBar mode>` 是双角色页的**承重件**,不能删。原"删页内栏、custom-tab-bar 渲染"方案会破坏双角色页,废弃。

```
app.json  tabBar { custom: true, list: [5 玩家页] }   ← 开关:隐藏原生条(杀双栏)
custom-tab-bar/index   ← 新增极简 shim(WeChat 要求存在;渲染空,唯一职责=允许 custom:true 从而隐藏原生条)
components/tabBar       ← 唯一真实底栏,双端两角色页内引用不变;已内置双 list;去凸起(若有)+ 主题按角色
```

- **唯一真实底栏 = 页内 `components/tabBar`**:双端、双角色页均页内引用,已用 `mode`/`dark`/`raised`/`activeKey` 正确处理各页状态,**不动**。
- **`custom-tab-bar/index`**:极简 shim,渲染空视图。它存在的唯一目的是让 `app.json custom:true` 合法(WeChat 要求),从而**隐藏原生 tab 条**、消灭"原生+页内"双栏。不承载导航 UI/逻辑(那是页内 tabBar 的职责)。
- **无需抽 tabbar-config.js**:custom-tab-bar 不渲染栏、不读 list,故 `components/tabBar` 的双 list 无第二消费者,保持内联,不抽取(YAGNI)。
- **switchTab / keep-alive**:custom:true 不改这两者,79 处 switchTab 与页面缓存零影响。

## 4. 主题 / 中心 tab / 角色切换

- **主题(施工结论:保留页内 `dark` prop,不集中)**:原设想"主题由角色单一来源、不再传 dark"。真机勘察否决:`square/list` 用 `dark="{{!isMerchantView}}"` 且**不传 mode**——商户看帖文是"消费者 tab + 浅色",主题与 mode **故意解耦**。若把主题改成随 mode/角色单一决定,square 商户态会被强制变暗 → 回归(真机 shell_player_square 已证浅色为正确态)。故保留各页 `dark` prop:它捕获的是"该页在当前角色下的视图态",非单例 tabBar 能推断。主题现状功能等价于角色驱动,只是按页取值。
- **中心 tab(B-2)**:删除"发布"橙色凸起方块样式,五 tab 等权(纯 wxss 改动,去掉 `.li` 特殊中心态)。
- **角色切换 + 栈清空**:切角色 = `reLaunch` 到目标角色首页(玩家 `pages/index/index` / 商家 `pages/merchant/index/index`)。Shell 暴露统一落点函数(如 `roleShell.switchTo(role)`),内部 reLaunch + 清空栈,替代散落的角色跳转。

## 5. 改动面 / 不动面

| 改 | 不动 |
|---|---|
| `app.json`(custom:true) | 79 处 switchTab 调用 |
| 新增 `custom-tab-bar/` 空 shim(4 文件) | **5 玩家页页内 `<tabBar>`(双角色承重,保留)** |
| `components/tabBar` 主题/去凸起(若有) | 商家所有页 + `<tabBar mode="merchant">` + MERCHANT_LIST |
| — | 各 tab 页业务逻辑 |

## 6. 风险 / 验收

- `custom: true` 是**全局开关**,一上全站玩家页统一切换 → 必须真机核对:5 个 tab 页底栏正确、无双栏残留、keep-alive 生效、切换高亮同步、暗色主题正确。
- 商家页回归:确认注入机制隔离后商家 in-page 栏与 reLaunch 未受影响。
- 用 `wechat-miniprogram-verify` automator 逐页截图 5 玩家 tab + 1 商家页,断言底栏渲染 + 高亮(自动化端口已开)。

## 7. 不在本批范围

- 玩家身份页迁移(world/growth 等收进「我的」子 tab,B-3)——那是页面迁移(批5),本批只统一 Shell 机制。
- 商家财务中心合并(B-4)。
- tab 图标矢量化(cy-icon,批2)——本批沿用现有 PNG 两态图。

## 8. 成功判据

- [ ] `app.json custom:true` 生效,玩家页无双栏,原生条隐藏,页内 tabBar 为唯一底栏。
- [ ] 5 玩家 tab 页 switchTab 高亮/keep-alive 正常;双角色页(template/member)按状态渲染对应栏不变。
- [ ] 79 处 switchTab 无回归。
- [ ] 商家页 in-page 栏 + reLaunch 无回归。
- [ ] 中心 tab 五等权,无凸起。
- [ ] 角色切换经 Shell 统一落点,栈清空。
- [ ] 真机截图验收通过。
