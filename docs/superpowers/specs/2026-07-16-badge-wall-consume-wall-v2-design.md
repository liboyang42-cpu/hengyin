# badge-wall 消费 wall-v2:身份卡目录真源搬进库

**Date:** 2026-07-16
**Status:** 设计已拍板,待实施
**Scope:** ③ 迁移 + ① 后端字段 + ② 小程序接线

---

## 1. 这是什么

《勋章引擎地基 + 成就页四层分区 Implementation Plan》(2026-07-12)在「后续计划」里登记过一条:

> **badge-wall Shader 接 wall-v2**:把 `subpackageP3/pages/badge-wall` 的硬编码 `IDENTITY_CARDS` 装配改为消费 `/api/badge/wall-v2`。

本设计就是兑现这一条。**不是**恢复 `subpackageP3/pages/achievements`——那页已被 PR#51(§8 IA收敛 Batch-7)以「勋章墙唯一入口迁入 member 成就 tab」为由删除,本轮尊重该决策:不新增页、不改 `app.json`。

## 2. 现状(读 master 现码得出)

- `POST /api/badge/wall-v2` 已在 master(`ApiBadgeController:27`),四层读模型装配完整,**零消费者**。
- `badge-wall/index.js` 自带 10 张 PRD 身份卡硬编码目录,点亮事实靠 `/api/growth/center` + `CODE_TO_CARD` 映射(仅 `FIRST_STEP`/`TOPIC_CLEAR` 两条)。
- `growth_badge` 仅 9 行种子:IDENTITY 3(`FIRST_STEP`/`TOPIC_CLEAR`/`ID_ROAM_FIRST`)+ GROWTH 6。
  - **10 张 PRD 身份卡(`ID_ARRIVAL`…`ID_CITY_LIT`)从未入库**,只存在于前端。
  - `category` 全为 `EXPLORE` → 五色轨道塌成一色。
  - `statement`/`name_en` 9 行中 8 行为空。
  - **无 `cond`/下一步 字段**:表、`GrowthBadge`、`BadgeWallItemVO` 都没有。

即:后端是骨架,前端目录比库更富。页里那句「wall-v2 后端接口是 P2」是当时的知情判断,不是遗漏。

## 3. Seam(本设计的核心)

| 归属 | 内容 | 理由 |
|---|---|---|
| **库(`growth_badge`)** | `badge_name` / `name_en` / `statement` / `unlock_hint` / `category` / 点亮规则 | 都是**可编辑文案与规则**,运营要能改而不发版 |
| **前端(`glyphs.js`)** | 徽记几何、五色轨道映射 | 是**渲染器代码**(程序化矢量),不是内容;库里没有也不该有图 |

Join key = `badge_code`。这条缝解释了为什么 glyph 留在前端而 `cond` 进库——不是妥协,是分工。

## 4. 范围:只接 IDENTITY 层

- `wall-v2.identity[]` 驱动身份卡,**取代**硬编码目录。
- 城市纪念章继续走 `/api/medal/wall`,**不动**。
- **GROWTH 六枚不上墙**:`MILE_*`/`STREAK_*`/`GW_ROUTE_*` 无徽记、无宣言、category 全 `EXPLORE`,上墙即六个无字同色点。
- **COLLECTION / HONOR 零行是设计意图,不补种子**:计划已登记「**L3/L4 上传后台**:运营/俱乐部图案上传接口(**我们不出图**)」。

## 5. ③ 迁移 `chengyinhub-admin/sql/migration_p5_identity_card_seed.sql`

前缀 `migration_*` + 目录 `chengyinhub-admin/sql/` 第一层 —— 两条都满足 CI `migration_layout` 门禁。

1. `ADD COLUMN unlock_hint VARCHAR(255) NULL` —— 锁定态下一步(PRD §8.3)。MySQL 8 `ADD COLUMN` nullable,INSTANT 不锁表,可逆。
2. `UPDATE FIRST_STEP` → 开始在场 / `ARRIVAL` / statement / unlock_hint(PRD §12.4 复用)。
3. `UPDATE TOPIC_CLEAR` → 完成一程 / `ROUTE CLEARED` / statement / unlock_hint。
4. `INSERT` 缺失 8 张,带**真实 category**(恢复五色轨):
   `ID_CITY_PROPOSAL`(CREATE)、`ID_FOUNDING_CIRCLE`(ORGANIZE)、`ID_JOINED_CIRCLE`(CONNECT)、
   `ID_GATHERING`(ORGANIZE)、`ID_FOLLOWED_PATH`(CREATE)、`ID_NEW_COMPANION`(CONNECT)、
   `ID_CITY_ALLIANCE`(CO_CREATE)、`ID_CITY_LIT`(EXPLORE)。
   **不带 `trigger_event`** —— 授予事件属「平台事件授予」后续计划,本轮为锁定展示(PRD §14:首发 4 张点亮)。
5. `UPDATE ID_ROAM_FIRST` 补 `unlock_hint`。

约束:注释内**禁 ASCII 分号**(2026-07-14 落库实测:按 `;` 拆句的执行器会把 ALTER 劈成两半)。

### 2/3 步是用户可见改名

`FIRST_STEP` 初次启程→开始在场、`TOPIC_CLEAR` 通关纪念→完成一程。这**不是**新造名字:前端 `CODE_TO_CARD` 早已这样显示,本次是让库对齐 UI 既成事实。副作用:`/api/growth/center` 等其它读该行 `badge_name` 的界面也会跟着改名——是收敛,非回归。

## 6. ① 后端:`unlockHint` 贯通

`GrowthBadge` 加字段 → `GrowthBadgeMapper.xml` 的 `resultMap` + `selectAllActive` + `selectByCode` 三处补 `unlock_hint` → `BadgeWallItemVO` 加字段 → `BadgeReadModelServiceImpl` 装配。

⚠️ **`selectByCode` 漏列是有前科的**:PR#24 的 CRITICAL「徽章上线即静默全废」就是漏列。加列必须三处同改,单测覆盖。仅用 Java 8 API(target=1.8)。

## 7. ② 前端 `badge-wall/index.js`

- 删 `IDENTITY_CARDS`(10 张硬编码)与 `CODE_TO_CARD`。
- 加 `CODE_TO_GLYPH`(badge_code → glyph key)。`ID_ROAM_FIRST` **无需特判**:`glyphs.js` 的 `draw()` 是 `(G[key] || G.DEFAULT)(ctx)`,未知 key 自动落 `DEFAULT` 坐标点。
- 加 `CATEGORY_TO_TRACK`:`EXPLORE→0 CREATE→1 ORGANIZE→2 CONNECT→3 CO_CREATE→4`。
  ⚠️ **命名陷阱**:`CAT_META[4].key` 是 `'CO-CREATE'`(连字符),库注释是 `CO_CREATE`(下划线)。映射层归一,勿靠巧合。
- `loadData`:`/api/badge/wall-v2` 取代 `/api/growth/center`(后者在本页**仅**用于推导点亮事实,wall-v2 直接返 `unlocked`/`unlockTime`)。`/api/medal/wall` 保留。
- `__injectDemo` 改造:它依赖 `IDENTITY_CARDS`,目录搬走后须改为注入受控 wall-v2 形状(automator 自证要用)。

## 8. 风险(不埋)

1. **可用性回归**:今天 10 张卡即使全部 API 挂了也照渲染(前端即真源);改后 wall-v2 失败 = 空墙。页有 `loadFail` 错误态(`index.wxml:43`),降级为「报错」而非「撒谎」,但确是行为变更。
2. **③ 硬依赖**:种子没先落库 → 墙静默只显 3 张身份卡,无报错无日志。与 `selectByCode` CRITICAL 同一失败家族。**顺序:迁移落库 → jar → 微信上传**。
3. **16 枚上限(既有,非本次引入)**:`engine.js:309` `GRID=4` → 图集 4×4,`setBadges` 取前 16。身份卡 10→11 意味着纪念章预算 6→5。**本次只记录,不擅改**引擎容量。

## 8b. 自审登记(既有缺陷,按「只提不改」处理)

`/code-review` high 档扫出 5 条,3 条已修(见下),2 条属既有、不在本次请求范围,按 当时的项目约定
「注意到无关死代码只提不改」登记:

- **`index.wxml:30` 计数与渲染数会对不上**:wxml 写「共 `{{badges.length}}` 枚」是全量计数,
  而引擎只渲染前 16。用户 11 卡 + 8 纪念章时页面写「共 19 枚」而墙上 16 个点。
  本次把身份卡从 10 抬到 11,只是让触发更容易,病根是既有的 `GRID=4`。
- **`en` 是死字段**:`itemToBadge` 产出的 `en` 无人消费(wxml 不引用,engine 只读
  name/rarity/locked/glyph/iconUrl),从旧 `cardToBadge` 原样平移而来。

已修 3 条:①`CAT_META[t] ? t : 0` 一次盖住 null/未知/越界/原型键(原 `t == null` 守卫会让
`category='toString'` 查出函数 → `CAT_META[fn].key` 抛错整页崩,已实测复现);
②迁移回填 `player_badge.badge_name` 快照,消除 def 与发奖快照的分叉;③去掉 `wallV2 &&` 死守卫。

## 9. 验证

- **单测**:扩 `BadgeReadModelServiceImplTest` 断言 `unlockHint` 贯通四层装配。
- **负控铁律**:每条新断言先注入变异 → 必须变红 → 撤销 → 绿。假绿有前科(mock any()/fixture 过时)。
- **automator 自证**(`wechat-miniprogram-verify`,禁肉眼截图):`callMethod` 喂受控 wall-v2 数据,断言 `applyBadges` 产出(11 枚 / 五色 legend / 锁定态带 cond);用页内 `__capture` 回读 GL 帧出图——devtools 截图不含 WebGL 同层内容,视觉证据只能引擎自取。

## 10. 不在本轮

- 平台事件授予(`club_created`/`referral_activated`…)→ 8 张卡本轮锁定展示。
- 漫游授予、L3/L4 运营上传后台、GROWTH 上墙、引擎 16 枚扩容。
