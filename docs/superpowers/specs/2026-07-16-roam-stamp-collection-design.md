# 漫游集邮:上街拍照生成邮票

**Date:** 2026-07-16
**Status:** 设计已拍板,待实施
**参考:** STAMPA(电子集邮 app)—— 相机仪式 + 齿孔咬边 + 散落集邮册
**关联:** [[2026-07-16-badge-wall-consume-wall-v2-design]](身份卡目录真源搬进库)

---

## 1. 一句话

用户上街拍照,任何东西加一圈齿孔咬边就成一枚邮票,攒进集邮册。
两档:**自由拍** = 普通邮票;**盖销拍** = 在某个 geohash7 格里拍,带邮戳与坐标,一格一生只能盖一枚。

**为什么现在做**:`badge_def` 的 COLLECTION(纪念)层从建成起就是 0 行,而且是**故意的** ——
2026-07-12 计划原话「L3/L4 上传后台:运营/俱乐部图案上传接口(**我们不出图**)」。
「我们不出图」正是卡死那层的东西。**用户拍 = 内容引擎**,这个洞就是照这个形状留的。

**为什么是城瘾而不是又一个 STAMPA**:STAMPA 的邮票无地理,是好看的剪贴簿。
城瘾的邮票带 GPS + 迷雾格 —— **邮票是在场的证据,不是装饰**。
文案早就在等这个机制:`ID_CITY_LIT` = 「我为这座城,点亮了一个真实坐标」,`ID_ARRIVAL` = 「我不是路过,我开始在场」。

## 2. 基建盘点(读现码,不是推断)

要的东西 80% 已在仓库:

| 已有 | 位置 | 用途 |
|---|---|---|
| `RoamFogTile` | `business/domain/RoamFogTile.java` | `uk(member_id, geohash7)`,~153m×153m 格 |
| `RoamPoi` | `business/domain/RoamPoi.java` | 运营布点 |
| `/roam/reveal` | `ApiRoamController:140` | 客户端上报**批量最多 500 格**,`batchInsertTile` 点亮 |
| 图片机审 | `IWxContentSecurityService.checkMediaAsync(mediaUrl, openid)` | 异步送检,需公网 https URL |
| OSS 上传 | `/common/uploadOSS`、app.js 统一上传通道(Phase 1.3) | 出图 URL |
| 规则引擎 | `RuleEvaluator`(master,`@Component`) | `aggregate/threshold` 求值 |
| 拍照打卡 | `/api/play/photo`(带 `picUrl`) | 已跑通的参考链路 |

**需要新建的只有:一张表、一个相机页、一个集邮册页。**

## 3. Seam:字典与 UGC 彻底分家 ⭐

| | `growth_badge`(def,已有) | `roam_stamp`(新表) |
|---|---|---|
| 存什么 | **定义**(身份卡 / 成长 / 运营纪念章) | **UGC 邮票实体** |
| 谁出图 | 我们 / 运营 | 用户 |
| 规模 | 十几行 | 每人数百行(STAMPA 实例用户 302 枚) |
| 读法 | `selectAllActive()` **全量发给所有人** | **必须按 member 过滤 + 分页** |
| 界面 | badge-wall 数字装置(**≤16 枚**) | 集邮册 collage(数百枚) |

### ⚠️ 用户邮票绝不能进 `badge_def`

`buildWallV2` 里是 `growthBadgeMapper.selectAllActive()` —— 无 member 过滤、无分页,
**把所有启用定义返回给每一个用户**。若每张用户照片建一行 def:

1. 每个人的墙上会出现**所有人的照片**
2. 字典表被几十万行 UGC 撑爆
3. `selectAllActive` 直接雪崩

### 两者只通过规则引擎连接

`badge_def` 加三行**定义**(我们出图),用 UGC 计数触发:

```
初次集邮   trigger_event=stamp_created  {"aggregate":"exists","threshold":1}
集邮 x10   trigger_event=stamp_created  {"aggregate":"count","threshold":10}
盖销 x50   trigger_event=stamp_canceled {"aggregate":"count","threshold":50}
```

`RuleEvaluator` 已在 master 跑着,**引擎零改动**。
墙上是「盖销 ×50」那枚(我们的图),册子里是你拍的 50 张(用户的图)。职责干净。

## 4. 两档规则

```
自由拍 → 普通邮票:照片 + 日期 + caption(可选)。无地理约束、无上限。进集邮册。
盖销拍 → 盖销邮票:普通邮票 + geohash7 + 邮戳 + 坐标。
         规则:人在该格内 且 该格此生未盖过 → 可盖一枚
         uk(member_id, geohash7) —— 与 roam_fog_tile 同键,天然幂等、天然防刷
```

### 走路照旧散雾,拍照不碰 `/roam/reveal`

雾是**走**散的:踏进格子那一瞬客户端上报就点亮了。所以「在未探索的雾格里拍」在物理上不存在 ——
你人到了,雾就散了。硬要「拍照才散雾」会推翻 `/roam/reveal`(批量 500 格)与整个迷雾玩法,
把漫游从「走路就有反馈」变成「不拍就没反馈」。**不做。**

语义分工反而更好:

> **走过 = 雾散了(你知道那里有什么);盖销 = 你真的看见了什么。**

盖销因此有重量,且现有链路一行不动。

## 5. 相机仪式(成败在这)

STAMPA 的核心**不是功能是仪式**:实体机身与 `Pinch to resize` ——
**构图发生在按快门之前**,手艺在当场不在后期。

城瘾版以全屏真实 `<camera>` 取景；用户提供的 Canon 原图只裁出竖放的实体机身，LCD 挖空透出同一条实时画面。pinch 以 LCD 内的实时裁切百分比反馈，**不叠白色虚线/齿孔框，也不在机身外加黑白遮罩**。邮票齿孔若保留，应落在拍后成品而非遮住取景的 UI 层；**绝不能退化成「从相册选图」** —— 那是相册，不是集邮。

盖销可用时,取景器上多一枚**待盖的邮戳**;按下快门邮戳落下。**这是唯一需要新美术的地方。**

## 6. 数据模型

```sql
-- migration_p6_roam_stamp.sql(③,合并前须先落库)
CREATE TABLE roam_stamp (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  member_id     BIGINT       NOT NULL,
  pic_url       VARCHAR(500) NOT NULL COMMENT 'OSS 原图',
  caption       VARCHAR(140) NULL,
  kind          TINYINT      NOT NULL DEFAULT 0 COMMENT '0=普通,1=盖销',
  geohash7      CHAR(7)      NULL COMMENT '盖销专用,与 roam_fog_tile 同精度',
  lat           DECIMAL(10,6) NULL,
  lng           DECIMAL(10,6) NULL,
  city_code     VARCHAR(16)  NULL,
  check_state   TINYINT      NOT NULL DEFAULT 0 COMMENT '0=送检中,1=通过,2=违规下架',
  check_trace   VARCHAR(64)  NULL COMMENT 'checkMediaAsync trace_id',
  del_flag      TINYINT      NOT NULL DEFAULT 0,
  create_time   DATETIME     NOT NULL,
  UNIQUE KEY uk_member_geohash (member_id, geohash7),  -- 盖销一格一枚;普通票 geohash7=NULL 不受约束
  KEY idx_member_time (member_id, create_time)
) COMMENT='漫游集邮 UGC 邮票';
```

**为什么 `uk(member_id, geohash7)` 对普通票无害**:MySQL 唯一索引**允许多行 NULL**,
所以 `geohash7=NULL` 的普通票可以无限多,只有盖销票(非 NULL)受一格一枚约束。
**一个 uk 同时喂两档**,这是本设计的关键技巧。

✅ **2026-07-16 已在生产库(MySQL 8.0.45)实测坐实**,不是查文档得来的
(用 `CREATE TEMPORARY TABLE` 会话级验证,未触碰生产 schema):

| 断言 | 结果 |
|---|---|
| 同 member 三张普通票(`geohash7=NULL`) | 全部插入成功,NULL 行数 = 3 |
| 同格盖第二次(**负控**) | 被拒:`1062 Duplicate entry '1-wx4g0bm'` |
| 同人不同格 / 同格不同人 | 均可插入,总行数 6 = 3 普通 + 3 盖销 |

实施时仍要写测试钉住这条(同格盖两次必失败)—— 它是防刷的**唯一**机制,
没有它盖销就是无限刷。

## 7. 存储与合规

`/common/uploadOSS` → OSS URL → `checkMediaAsync(url, openid)` 异步送检
→ `wxa_media_check` 回调 → 违规置 `check_state=2` 下架。

**默认私密**:邮票只进自己集邮册,**不进广场、不进他人信息流**。分享需手动导出单张。
肖像权风险因此压到最低(不公开传播 ≠ 侵权主要场景),且与现有「机审直发 + 举报处置」立场不冲突。

⚠️ **两条必须说明,不替用户决定**:
1. **OSS 合规 P0 未清**(合规八件套那条)。本功能会把存储量级直接拉起来 —— **建议先清 P0 再放量**。
2. 集邮册是**本地渲染 + 远端图**,数百张必须**分页 + 懒加载**,不能一次拉 302 张。

## 8. 界面分工

- **badge-wall(现有,不动)**:系统 11 枚身份卡 + 成长。稀缺、仪式、WebGL 装置。
  ⚠️ `engine.js:309` `GRID=4` → `setBadges` 取前 16 枚,**数百枚邮票塞不进去,别试**。
- **集邮册(新)**:散落、带随机角度、密集堆叠、日期轴(参考 STAMPA 的月份 collage)。
  **不是网格 —— 乱才是魅力。** 入口挂 member 成就 tab(与勋章墙并列),不新增 tabBar。

## 9. 分期(每期独立上线、独立验证)

| 期 | 内容 | 验收 |
|---|---|---|
| **P0** | `roam_stamp` 表 + 相机仪式 + 集邮册 collage(**只做自由拍**) | 能拍、能存、能看;**先验手感** |
| **P1** | 盖销:geohash7 + 邮戳 + `uk` 幂等 | 同格盖两次必失败(负控) |
| **P2** | `badge_def` 加三行集邮成就(复用 RuleEvaluator) | 集满 N 张点亮墙上那枚 |

**P0 先行的理由**:这个玩法成败 100% 在手感。先小成本验「拍起来爽不爽」——
爽了再接盖销与成就;不爽的话盖销和成就都是白做。

## 10. 不在本设计内

- 邮票公开 / 广场 / 社交流(默认私密的直接推论)
- 运营上传的 L3/L4 纪念章后台(仍是独立的后续计划)
- badge-wall 引擎扩容(16 枚上限维持;邮票本就不该上那面墙)
- 拍照驱动散雾(见 §4,已否决)
