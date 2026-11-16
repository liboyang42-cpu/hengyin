# 漫游后端与数据工具修复波次 2026-11-14

分支 `fix/roam-backend-1114`(基于 origin/master),范围只含后端与数据工具,不碰 `chengyinhub-xcx` 页面。依据审计 `roam-review-1114/RESULT.md` 的 A3.4、A5.*、B3–B5。
本地未跑任何 Java 构建/测试/数据库(遵守 AGENTS.md:Java 只在 CI/测试 VPS 跑);JS 单测在本地跑过。

## 改了什么

| # | 项 | 做法 | 位置 |
|---|---|---|---|
| 1 | `/api/roam/pois` 轻量投影(A5.1–A5.3) | 新增 `cover_img`(原有)、`gallery_json`(只下发前 3 张)、`quotes_json`、`photo_credit`、`badge_code`、`node_level`;排序 `node_level desc, 距离 asc, id`;内层子查询先 `limit` 再算 3 个相关子查询;控制器多取 1 条判断截断,`data` 仍是数组,**同级新增 `truncated`**(向后兼容) | `RoamMapper.xml` `selectPoisNear`、`ApiRoamController.pois`、`RoamPoiTags.galleryHead` |
| 2 | 目录地点公开只读详情(A3.4) | `GET /api/city/places/{id}`:仅 `type=1` + 种子标签 + 已上架 + 无商家/模板/申请;只回照片、引述、署名、坐标、勋章码,**没有挑战、模板、券、答案**;未命中统一 410 | `ApiCityNodeController.place`、`CatalogPlaceVO`、`selectCatalogPlaceById` |
| 3 | `/api/city/nodes`(A5.4)+ 写接口限流(A5.7) | `nodes` 增加 `limit`(默认 100、上限 200)与坐标校验(NaN/越界拒绝)、tag 字符校验;`/poi/discover`、`/checkin`、`/shop/visit` 挂 `@RateLimiter`(按账号 60 次/分);**单次漫游新发现上限 50**(超出回滚,重复发现不计) | `ApiCityNodeController`、`ApiRoamController`、`RoamSessionWriteService.recordPoiFound` |
| 4 | 后台批量上/下架 | `PUT /business/roamPoi/catalog/batch-status?cityCode=&seedTag=&status=0|1&dryRun=`,权限 `business:roamPoi:edit`、记操作日志;**范围钉死**:`type=1`、无商家、无模板、`application_type` 为空、同城、带 `roam:seed:v1:` 标签,商家据点即使同城同标签也碰不到;上架要求已有坐标;`dryRun` 只回命中数;幂等 | `RoamPoiAdminController.catalogBatchStatus`、`RoamMapper.xml` |
| 5 | tags 统一为 JSON 数组串(A5.5) | `/city/nodes?tag=` 改为按带引号整标签匹配(不再子串);商家资料同步经 `RoamPoiTags.normalize` 写 JSON 数组;新增迁移 `migration_20261114_roam_poi_tags_json.sql`(把历史逗号串转 JSON、改列注释,可重复执行) | 见左 |
| 6 | 导入脚本 | 见下 | `scripts/roam-catalog-import.js` |

### 脚本 `scripts/roam-catalog-import.js`
- 写 `cover_img`、`photo_credit`(坐标行里提供时;有封面必须有署名)。
- 推荐理由进 `quotes_json`(`[{text, source:"城瘾编辑推荐", author, date, url}]`),`description` 改放到访说明 `visitNote`,不再写 `reason`。
- 接受按目录 id 索引的坐标 JSON(`gcj02:[lat,lng]`、`coordSystem`、`precision`、`source`、`verifiedOn`、`{missing:true}`);缺坐标的地点不导入并在 SQL 头注释列出;校验城市包围盒、坐标系(香港只允许 `WGS84(HK,no offset)`)、精度。原有数组式严格模板路径行为不变(原测试全过)。
- 街道/起点/近似精度的到店半径放宽到 150m(poi 精度保持 80m)。
- 新参数 `--status 0|1`、`--revert-output`;`status=1` 追加幂等 UPDATE。
- 坐标数据已提交:`catalog-coords-1114.json`(来自私有仓库该文件,OpenStreetMap 公开地理数据整理;只保留 gcj02/coordSystem/precision/source/verifiedOn 字段)。

### 测试库 SQL(由 lead 执行,我没有连任何库)
- `roam-catalog-test.sql`:31 条 `status=1` 的 `type=1` 目录点(INSERT … WHERE NOT EXISTS 按种子标签幂等,再 UPDATE 到上架),末尾回读。**未导入**:`cd-a4-museum`、`hk-aco-books`(无坐标,保持未发布)。
- `roam-catalog-test-revert.sql`:先删本批「没有任何发现/打卡记录引用」的行,仍被引用的只下架;末尾回读剩余行数。
- 执行前提:测试库已有 `quotes_json`、`photo_credit`(`migration_20261025_official_poi_quotes.sql`)与 `gallery_json`;并建议先执行 `migration_20261114_roam_poi_tags_json.sql`。
- 执行后用 `PUT /business/roamPoi/catalog/batch-status?cityCode=SH&status=0&dryRun=true` 可演练批量下架。

## 测试
- 新增/更新 JUnit:`ApiCityNodeSearchTest`(limit、标签整匹配、坐标/标签校验、目录详情范围、批量上下架范围,H2)、`ApiRoamCoordinateBoundaryTest`(truncated、相册前 3、数组形态不变)、`RoamPoiCatalogBatchTest`(权限注解、入参校验、dryRun、幂等)、`RoamPoiTagsTest`、`RoamPoiFoundSessionCapTest`;`RoamPoiPlayerVisibilityMyBatisIT` 夹具补 4 列。
- JS:`roam-city-catalog.test.js` 18 条全过,含已提交 SQL 与坐标清单逐字同源校验。
- Java 编译与测试以 PR 上 CI 为准。

## 取舍与未做
1. **目录详情接口没有放行游客**:需要改 `SecurityConfig` 的 `permitAll`(属「改鉴权」,按 AGENTS.md 须先问用户)。当前只对已登录玩家开放;若要游客可读,在 `SecurityConfig` 的精确 GET 放行列表加 `/api/city/places/{id:\d+}` 即可(已有 `/api/city/nodes/{id}` 先例)。
2. **不折叠进基础 schema**:项目约定是 `base_schema_20260818.sql` 冻结快照 + `scripts/e2e/base_schema_20260818_migrations.json` 迁移清单,不改既有迁移也不改快照,因此只新增一个迁移。
3. 官方据点草稿保存(`OfficialRoamPoiService`)仍原样保存 tags;历史行靠迁移转换,之后若运营又手填逗号串,商家同步路径会规整、官方草稿路径不会。
4. 未审:迷雾 reveal/finish、stamp、medal 的归属校验(审计 A5.8 已列下一轮);hangout 接口已是下线桩,未挂限流。
5. 坐标来自 OSM,**未经高德/腾讯入口复核**(`matchConfirmed` 在源文件里为 false);11 条街道/起点/近似点位置不是门牌级,已放宽半径,仍建议上架前真机走一遍。目录 `reviewedOn` 2026-10-23,导入工具 90 天校验在 2027-01-21 失效。
6. 小程序侧(A4.1 标签解析、入口图不依赖定位等)由其他 lane 负责;消费新字段:`/pois` 的 `truncated`、`gallery_json`(前 3)、`quotes_json`、`photo_credit`、`badge_code`,以及 `/city/places/{id}`。

STATUS: DONE
