# 小程序模板游戏源码盘点（2026-08-21）

## 结论

以 `github/master` 的 `62dd6e3eae7d5755d5f429790ff7e01b17dd7d7a` 为唯一源码基线，不能只报一个不带口径的数字：

- **当前源码明确会进入“小程序公共玩法库”的官方种子玩法：19 个。**其中 12 个通用节点玩法 + 7 个《生活不掉线》玩法；它们都写入 `cms_template_library`，且种子设置 `status=1 / publish_status=1`，满足 `/api/template/list` 与 `/homeData` 的查询条件。
- **另有 18 个“具名玩法包”整包主题模板**（第一批 10 个、第二批 8 个），内含 **32 个节点玩法定义**（22 + 10）。但按当前 `master`，主题模板列表、详情和 `/use` 都强制要求 `cms_topic_template_meta.template_status='VERIFIED'`；两批种子没有写这张元数据表，所以它们是**已定义、默认不暴露、默认不可使用**，不能算成当前可见玩法。
- 因而若问“现在源码确定能在小程序玩法货架出现多少个官方模板游戏”，答案是 **19 个**；若问“代码/迁移一共做了多少个游戏模板产品”，则是 **37 个**（19 个独立玩法 + 18 个整包玩法包），其中只有前 19 个具备源码闭环的公共货架暴露条件。
- 若按最底层“独立节点玩法定义”去重，则是 **51 个**：19 个独立公共玩法 + 18 个玩法包内的 32 个节点玩法。这个数字是工程资产数，不是用户可见卡片数。

生产公开接口 `POST https://www.chengyinhub.com/prod-api/api/template/homeData` 本次只读回读返回 HTTP 401，未取得生产动态总数。因此本文不把源码种子数冒充“生产库所有历史/用户自建模板总行数”；生产精确现状仍需带合法登录态调用 API，或由数据库迁移流程做只读回读。

## 计数口径

| 层级 | 实体/入口 | 本次数量 | 是否算“模板游戏” | 当前源码暴露状态 |
|---|---|---:|---|---|
| 独立节点玩法模板 | `cms_template_library`，`/api/template/list`、`/homeData` | 19 | 是 | 可暴露、可查看、可“开始应用”起草 |
| 具名玩法包 | `cms_topic(is_template=1)` + chapter/node/member_template | 18 包 / 32 节点玩法 | 是，但属于整包模板 | 默认隐藏；缺 `VERIFIED` 元数据，`/list`、`/info`、`/use` 均过不了 |
| 非游戏主题模板 | 《生活不掉线》1 个 + 第二组圈层主题 5 个 | 6 | 否，属于完整主题骨架 | 都不是可直接算作独立模板游戏；当前也因成熟度门禁默认不公开 |
| 官方运营活动 | 运营团队实际发起并维护的主题活动 | 本批种子创建 0 个 | 否 | 这些迁移不写 `cms_activity`；不能由模板定义反推运营活动总数 |
| 具体活动实例 | 用户/商家/代理用模板复制出来的草稿、城市、日期实例 | 本批种子创建 0 个 | 否 | `/use` 复制后才产生，源码无法静态计数动态总量 |

主题模板与玩法模板是两套实体，小程序源码也在 UI 注释中明确写明“一张卡=完整主题（章节+节点+玩法）”，并将其单列为“主题模板”。证据：`github/master:chengyinhub-xcx/pages/template/index.wxml:98-120`。

## 当前可进入公共玩法库的 19 个

### A. 通用节点玩法 12 个

来源：`github/master:chengyinhub-admin/sql/migration_seed_template_library_12packs_20260820.sql`。该迁移逐项写 `cms_template_library`，写后 guard 断言必须恰好 12 条，且状态为在架；见 `:79-275`、`:279-322`。

| 编号 | 名称 | 完成原语 | 定义行 | 暴露/使用判断 |
|---|---|---|---|---|
| M01 | 今晚的暗号 | 文字问答 | `:78-92` | 在架；可起草 |
| M02 | 老板的秘密题 | 选项问答 | `:95-111` | 在架；可起草 |
| M03 | 拍下这一刻 | 拍照 | `:114-128` | 在架；可起草 |
| M04 | 收集这枚风味印章 | 扫码 | `:130-144` | 在架；可起草 |
| M05 | 新品盲测局 | 选项问答 | `:146-162` | 在架；**只展示/可起草，但不是可信问卷**：当前原语是“正确答案”模型，真实偏好收集能力未建 |
| M06 | 今日搭配任务 | 拍照 | `:164-178` | 在架；可起草 |
| C01 | 集合点亮 | 扫码 | `:180-194` | 在架；可起草 |
| C02 | 小队竞速点亮 | GPS | `:196-210` | 在架；可起草；实时排行榜未建，按迁移说明只支持活动后排序口径 |
| C03 | 街角谜题 | 文字问答 | `:212-227` | 在架；可起草 |
| C04 | 城市取景任务 | 拍照 | `:229-243` | 在架；可起草 |
| C05 | 城市接力棒 | 文字问答 | `:245-260` | 在架；可起草；第一版队伍顺序由领队线下控制 |
| C06 | 今日角色任务 | 拍照 | `:262-276` | 在架；可起草；第一版角色可线下抽卡 |

完成方式分布由迁移 guard 固定为：文字 3、拍照 4、选项 2、扫码 2、GPS 1（`:284-322`）。

### B. 《生活不掉线》玩法 7 个

来源：`github/master:chengyinhub-admin/sql/migration_seed_life_backup_games_20260820.sql`。同一批同时写 `cms_member_template`（用于主题节点）与 `cms_template_library`（公共玩法库）；后者明确设置 `status=1 / publish_status=1`，见 `:45-157`、`:154-234`。

| 编号 | 名称 | 定义行 | 暴露/使用判断 |
|---|---|---|---|
| G0 | 30秒生活掉线测试 | `:48-61` | 公共库在架；主题内也已接线 |
| G1 | 现在出状况，你先选什么 | `:63-76` | 公共库在架；主题内也已接线 |
| G2 | 我的底线三选一 | `:78-91` | 公共库在架；主题内也已接线 |
| G3 | 营业时间拼图 | `:93-106` | 公共库在架；主题内也已接线 |
| G4 | 去之前准备什么 | `:108-121` | 公共库在架；主题内也已接线 |
| G5 | 值不值得现在处理 | `:123-136` | 公共库在架；主题内也已接线 |
| G6 | 首选失效怎么办 | `:138-151` | 公共库在架；主题内也已接线 |

这 7 个统一使用 `validation_method=0`（无判分/到点即完成语义），不是七套新的服务端判分引擎；其差异主要在内容、流程与主题接线。迁移说明见 `:13-24`。

## 已做但当前默认不暴露的 18 个具名玩法包

### 第一批 10 个（22 个内部节点玩法）

来源：`github/master:chengyinhub-admin/sql/migration_seed_playpack_templates_20260820.sql`。预期关系表在 `:62-86` 固定 10 个主题与 22 个节点玩法；总数 guard 在 `:1327-1403` 再断言 10 topic / 10 chapter / 22 member_template / 22 node。

1. 隐藏菜单（1 个节点玩法）
2. 店内寻宝（3）
3. 拍照挑战（1）
4. 神秘店员的暗号（1）
5. 三步解谜（3）
6. 听音辨物（1）
7. 三连快答（3）
8. 集章巡店（4）
9. 岔路口的选择（2）
10. 三个盲盒（3）

其中 4 个名字对应的高级机制尚未实现，种子主动降级文案，不应按完整机制宣传：三连快答没有服务端 TIMER；集章巡店仍是全节点完成而非 N/M；岔路选择仍是线性剧情；三个盲盒全部可见而非随机下发。证据：该迁移 `:19-24`。

### 第二批 8 个（10 个内部节点玩法）

来源：`github/master:chengyinhub-admin/sql/migration_seed_playpack_batch2_figma_20260821.sql`。主题与节点预期表见 `:45-65`、`:77-111`。

1. 闭眼味觉师（1 个节点玩法）
2. 菜单顺口溜（1）
3. 沉默点单（1）
4. 便利店调酒师（1）
5. 逆向NPC（1）
6. 爪印巡街（3）
7. 治愈音乐角（1）
8. 穿搭盲配（1）

这一批明确声明“零新机制”，只组合现有文字、拍照、选项、扫码、GPS 原语；限时概念也降级为不承诺计时。证据：该迁移 `:1-15`。

### 为什么 18 个现在不能算“用户可见”

当前主题模板 SQL 门禁要求：

- 列表只返回 `is_template=1 / status=1` 且存在 `template_status='VERIFIED'` 元数据的主题；
- 详情与 `/use` 使用相同门禁。

证据：`github/master:chengyinhub-system/src/main/resources/mapper/business/CmsTopicMapper.xml:783-809`。两批具名玩法包迁移均未插入 `cms_topic_template_meta`；在固定 SHA 上执行：

```bash
git show github/master:chengyinhub-admin/sql/migration_seed_playpack_templates_20260820.sql \
  | rg 'cms_topic_template_meta|VERIFIED'
git show github/master:chengyinhub-admin/sql/migration_seed_playpack_batch2_figma_20260821.sql \
  | rg 'cms_topic_template_meta|VERIFIED'
```

两条均无命中。因此源码默认状态是：**库内定义存在，但列表查不到，详情与复制也被拒绝**。只有生产另行人工补了 `VERIFIED` 元数据时才可能不同；本次没有合法的生产读回证据，不能猜。

## 另外 6 个主题模板，不计入独立模板游戏

1. 《生活不掉线》：完整主题模板，内含上面的 G0-G6；其元数据种子明确为 `EXPERIMENTAL / NONE`，见 `github/master:chengyinhub-admin/sql/migration_seed_topic_template_shelf_20260726.sql:23-27`、`:92-144`。当前 `VERIFIED` 门禁下主题卡默认隐藏，但 7 个公共玩法卡仍可独立出现。
2. 第二组五个圈层主题：下班后切换频道、一个人也出发、亲子各自开心、今天拍点好看的、夜猫子城市生活。它们是 5 个自由探索主题骨架 + 40 个供给候选，不是节点游戏；见 `github/master:chengyinhub-admin/sql/migration_seed_circle_theme_batch2_20260821.sql:1-7`、`:22-39`、`:50-64`、`:220-271`。种子明确写 `EXPERIMENTAL / NONE`（`:77-127`），当前也默认不公开。

## 注册、展示、使用链路

### 独立玩法模板

```text
cms_template_library 种子
  -> /api/template/homeData 或 /api/template/list
  -> 小程序模板页节点玩法卡
  -> /pages/templatedetail/templatedetail
  -> “开始应用”跳 /pages/publish/temp?id=...
  -> /api/template/info 回填
  -> /api/template/draft 或 /api/template/publish 生成用户自己的玩法
```

证据：

- 后端公共列表过滤与返回：`github/master:chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiTemplateController.java:249-289`。
- 首页只取审核、上架、未删数据并返回总数：`github/master:chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TemplatePublishServiceImpl.java:658-701`。
- 详情页“开始应用”跳编辑器：`github/master:chengyinhub-xcx/pages/templatedetail/templatedetail.js:254-264`。
- 编辑器从 `/api/template/info` 回填 `originalTemplateId` 等字段：`github/master:chengyinhub-xcx/pages/publish/temp/index.js:1448-1531`；保存/发布入口分别在 `:1312`、`:1353`。

### 完整主题模板

```text
cms_topic(is_template=1) + chapter + node + member_template
  -> VERIFIED 成熟度门禁
  -> /api/template/topic-template/list
  -> 小程序“主题模板”区
  -> /api/template/topic-template/use
  -> copyCmsTopic(copyType=3) 复制为当前会员草稿
```

证据：

- 主题模板接口与整包复制：`github/master:chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiTopicTemplateController.java:25-55`、`:145-172`。
- 小程序列表、展示和“用模板”动作：`github/master:chengyinhub-xcx/pages/template/index.js:436-492`、`index.wxml:98-120`。
- 当前的实际阻断点：`github/master:chengyinhub-system/src/main/resources/mapper/business/CmsTopicMapper.xml:783-809`。

## 最终回答建议

对用户直接说：**“按当前 `master` 能闭环暴露到小程序公共玩法库的官方模板游戏，一共 19 个。仓库另外还做了 18 个整包具名玩法包（共 32 个内部节点玩法），但它们现在缺 `VERIFIED` 元数据，源码默认不展示、也不能复制使用；所以不能把 19+18 直接报成已经可用的 37 个。”**
