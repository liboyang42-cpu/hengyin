# 漫游走查 2026-11-14(只读,先收集后一次性修)

范围:origin/master `73f4f7be`。只读,未改产品代码。路径相对 `chengyinhub-xcx/`(后端相对仓库根)。
局限:未跑模拟器/真机,未连测试库(9 行 roam_poi 的内容未核对);标「需真机」的是读码推断。#1273 通过只读 API 取 diff,未克隆、未合并。

**总判断**:#1273 的内容与逻辑**已经进了 master**(换了形态),所以「33 处地点上地图」现在卡在**数据**而不是前端:目录 33 条坐标全空,没有一条能画到地图上。要让漫游"有意思",先补坐标/封面/上架流程,再修下面的前端问题。

严重度:blocker=玩家看不到/走不通;quality=体验明显差;polish=细节。

---

## A. 漫游端到端

### A1 入口地图(pages/roam,screen=entry-map)
| # | 位置 | 问题 | 对玩家的影响 | 建议 | 级别 |
|---|---|---|---|---|---|
| A1.1 | pages/roam/index.js:6094 | 未定位(`entryLocated=false`)时直接走 `_loadEntryAnyCity`,只拉 8 个公开主题,**不请求 /api/roam/pois**,并清空 markers | 拒绝定位或首次进入的玩家看到一张没有任何地点的图,城市清单里的地点完全不出现;「开启定位」只在底部小字 | 未定位时按地图中心(或上次城市)照样读 roam_poi;"距你"才依赖定位 | blocker |
| A1.2 | index.js:6135 | 入口地图只画 `isCatalogPlace`(type=1 且带 `roam:seed:v1:` 标签且有坐标);**商家(type=2)、测试库那 9 条非种子地点、主题针都不画** | 入口图只会出现导入工具生成的点,现有 9 个上海点在入口图上看不到 | 入口图画全部已上架 roam_poi,种子标签只用来决定"有没有推荐理由" | blocker |
| A1.3 | utils/roam-entry-map.js `buildPlaceMarkers` | 地点 marker 只有一枚统一图钉 `roam-pushpin.png`,无 callout、无分类图标、无名字 | 一屏几十个相同图钉,点之前不知道是公园还是书店,不好玩 | 按 `presentationForPoi` 的分类用不同图标;缩放到位时显示名字气泡 | quality |
| A1.4 | index.js:6139-6145、wxml 图层弹层 | 地点不进图层开关(只有活动/队伍/动态三项)、不进搜索(`searchPlaces` 只含活动)、不进底部卡片带 | 玩家搜"鲁迅公园"搜不到;关不掉地点图层;卡片带里没有地点 | 图层加「地点」;搜索并入地点;卡片带加就近地点卡 | quality |
| A1.5 | index.js:6241-6252 | 每次拖图停手 600ms 后一次性发 3 个请求(entry/nearby、team/nearby、roam/pois),固定 radius=3000,不随缩放变化,无取消 | 拉远看全城仍只有中心 3km;快速连拖流量浪费;失败时 `_entryPlaces=[]` 直接清掉已有图钉(index.js:6135,请求失败与"这里没有"不分) | 按视口外接圆取半径(上限由后端定);失败保留旧点;请求去抖+合并 | quality |
| A1.6 | index.js:6145 | `_syncEntryMap` 被三个请求各调一次,每次 setData 整个 markers 数组+cards | 进入/拖动时 3 次大 setData | 合并为一次(Promise.allSettled 后一次 sync) | polish |
| A1.7 | pages/roam/index.wxml:165、wxss:`.entry-peek`(高 366rpx 固定) | 底部卡片带固定高并带渐变,盖住地图下 1/3;小屏+大字体下顶栏(搜索/图层)与底卡之间可视地图很小(需真机) | 地图是主角却被挤 | 卡片带可收起为一行;大字体下降级 | quality |

### A2 开始漫游后的主地图
| # | 位置 | 问题 | 影响 | 建议 | 级别 |
|---|---|---|---|---|---|
| A2.1 | index.js:2531 | `if (p.state === 'fog') return;` —— 未发现的 POI **一律不画**。服务端 `found` 为假就是 fog | 玩家看不到前面有什么好玩的地方,只能"瞎走碰运气";owner 说的"已上传很多有趣地点要在漫游里展示"在主地图上做不到 | 未发现的点以"问号/剪影"图钉显示(可限定 node_level 或距离),走近再点亮;或做成可开关的"探索提示" | blocker(与产品意图冲突,需 owner 定) |
| A2.2 | index.js:1259、3135、5942 | `/api/roam/pois`(3km)与 `/api/map/nearby`(radius 800、limit 20,index.js:1420-1424)**只在开局、重试、改筛选时拉**,走出范围不再刷新 | 走远了周围没有任何新地点;商家最多 20 家 | 玩家位移超半径 1/2 时按新中心重拉并去重合并 | blocker |
| A2.3 | index.js:1246、1546、1468 | `metaLine` 写死「· 距你很近」,商家无距离时也如此 | 文案不真实,实际可能 2km 外 | 用 haversine 实际距离;没有就不写 | quality |
| A2.4 | photo-pins.js:10、63 | 缩放≤14 时普通点只画小圆点(`dots`),拉近才有图钉;图钉最多 40 | 想看全城地点时只剩圆点,点不开也不知道是什么 | 圆点按分类着色;≥N 点聚合并显示数量 | quality |
| A2.5 | index.js:2515-2640 `_syncMarkers` | 每次状态变化(定位 500ms 一次)遍历全部 `_pois`、每点算状态+建 callout;POI 到数百后会变慢(`_pinRegion` 有合并 250ms,但状态/目标变化直接调用) | 点位多了掉帧 | 先按视口/距离裁剪再建 marker;marker 签名不变不 setData(已有部分) | quality |
| A2.6 | index.js:1535-1551 | 平台地点 `desc` 不直出(审核策略),且无照片(`photo` 只认 `cover_img`);**导入工具不写 cover_img** | 地点卡上只有名字+分类+地址,没有照片和故事 | 见 B4(补封面和推荐理由) | quality |

### A3 地点卡 → 挑战 → 勋章
| # | 位置 | 问题 | 影响 | 建议 | 级别 |
|---|---|---|---|---|---|
| A3.1 | pages/roam/index.wxml:393-417 | 地点卡是顶部 `prompt-slot`,与 event/nearby/footprint 等共用一个槽(`hud.promptSlot` 8 处重复求值);高优先级提示出现时地点卡被顶掉 | 点了地点却看不到卡 | 地点卡改独立半屏或明确优先级 | quality |
| A3.2 | wxml:401-405 | 推荐理由、来源都是同一种 `.pc-body` 纯文字;来源显示成域名串(如 `touch.shio.gov.cn · …`),不可点 | 玩家看不懂域名,也看不到"为什么值得去" | 理由做成引文样式;来源折叠为「资料来源」可复制链接 | polish |
| A3.3 | subpackageRoam/nearby/index.js:919、1077、pages/play/poi-challenge/index.js | 挑战页(拍照→答题→勋章)只被**附近页**调用;**漫游主页**走另一条 `scene-roam-poi-detail`(subpackageMember)。两条路径文案/流程不同 | 同一个地点从两个入口体验不一致 | 合并为同一流程 | quality |
| A3.4 | 后端 RoamMapper.xml:557(`application_type=3`) | `/api/city/nodes/{id}` 只认官方据点;导入的目录点(application_type 空)**没有详情、没有挑战** | 33 处城市地点上线后点开只有基础卡,没有挑战/勋章 | 见 B4;需单独决定目录点是否可挑战 | blocker(对"挑战"目标) |
| A3.5 | poi-challenge/index.js:~148 | 勋章页 `img:''`,`mic:'key'` 通用钥匙图标;主按钮「投一张换一张」(城市签旧玩法)无解释 | 拿到勋章没有画面感;文案莫名 | 用地点封面/专属勋章图;改成「去集邮册」类可理解文案 | polish |
| A3.6 | poi-challenge/index.js:~118 | 客户端 `haversine > radiusM+30` 拒绝,且文案「离这里还有点远」没有给距离/方向 | 玩家不知道离多远 | 显示"还差 xx 米",加导航按钮 | polish |

### A4 附近页(subpackageRoam/nearby)
| # | 位置 | 问题 | 建议 | 级别 |
|---|---|---|---|---|
| A4.1 | utils/map-layers.js:35-37 `splitTags`、:115-125 `poiCard` | 导入工具把 tags 写成 JSON 数组串 `["roam:park","roam:seed:v1:…","树荫散步"]`(roam-catalog-import.js:59)。`splitTags` 只按逗号/空白分,结果是 `["roam:park"`、`"roam:seed:v1:sh-…"` 这样的**带引号括号的脏标签**,会进卡片 chip、「地点」下拉筛选(`filterOptions`)和地图小牌(`map-pins.js:firstTag`) | 先读 JSON,过滤 `roam:*` 保留标签,再取展示标签(直接复用 `utils/roam-poi-presentation.js:readTags/presentationForPoi`) | blocker(种子点一上线就触发) |
| A4.2 | map-layers.js:123 `desc: text(p.description)` | 附近页**直出 `description`**;导入工具把推荐理由写进 `description`(roam-catalog-import.js:77)。而漫游页明确"非商户 description 绝不直出"(index.js:1535) | 两页策略不一致;需统一:要么附近页同样不直出,要么承认目录理由是审核过的并用 `placeNoteForTags`/受控字段 | quality(策略冲突,需 owner 定) |
| A4.3 | nearby/index.js:~898 + map-team.js:13 | 「全部距离」`ALL_RADIUS=1000000` 交给服务端截断(500 条,按距离排序) | 全城浏览时远处点被静默丢弃且无提示;见后端 A5.2 | quality |
| A4.4 | nearby 点位卡 | 不读 `placeNoteForTags`,无推荐理由/来源;类别只有「地点/商家」 | 复用分类展示和 note | quality |

### A5 后端(ApiRoamController / roam_poi)
| # | 位置 | 问题 | 建议 | 级别 |
|---|---|---|---|---|
| A5.1 | RoamMapper.xml:78 `poiCols` | `/api/roam/pois` 不返回 `gallery_json/quotes_json/photo_credit/badge_code`,只有 `cover_img`;照片/引述只能走 `/api/city/nodes/{id}` | 地点卡拿不到照片/引述。给 `/pois` 加轻量投影,或给目录点开公开详情读口 | blocker(对"照片/引述"目标) |
| A5.2 | ApiRoamController.java:122 `POIS_NEAR_LIMIT=500`;RoamMapper.xml:118-121 | 超过 500 按距离截断,无 total/truncated,无分页,不按 node_level 优先 | 返回 truncated 标记;排序 node_level desc,distance asc;或按 city_code | quality |
| A5.3 | RoamMapper.xml:90-121 | 包围盒后对盒内每行算 `power()/cos()` 做 filesort,且每行 3 个相关子查询(npc_avatar、found 两表 exists、pending_redeem);无 city_code 过滤用不上 `idx_city_status` | 数百行可接受,上千会慢。先内层 limit 再算子查询 | quality |
| A5.4 | `/api/city/nodes`(ApiCityNodeController.java:72-84;RoamMapper.xml:517-545) | 无 limit/分页;不校验经纬度;只含 type=2;tag 用 `LIKE '%…%'` | 加 limit、校验 | quality |
| A5.5 | schema 注释 vs 导入脚本 | `tags` 注释写"逗号分隔",导入写 JSON 数组串,`/city/nodes?tag=` 用 LIKE。两种格式并存 | 统一并写注释 | polish |
| A5.6 | sql/base_schema_20260818.sql:4515 | 基础 schema 的 roam_poi 缺 `quotes_json/photo_credit/gallery_json/photo_required/badge_code`,必须再跑 2 个迁移,否则 `/city/nodes` 查询报错 | 新库初始化脚本合并 | polish |
| A5.7 | ApiRoam*.java | 无 `@RateLimit`;`/poi/discover` 只验到点与速度,未查"同会话一次发现数量上限"(未深查 recordPoiFound,需确认) | 确认并补 | polish |
| A5.8 | 后端未审到 | 迷雾/集章/时间胶囊:**后端未找到"时间胶囊"端点**;reveal/finish/checkin、stamp、medal 的归属校验本轮未逐行审 | 下一轮专审 | — |

### A6 层叠/布局(shared .pdim 等)
| # | 位置 | 问题 | 级别 |
|---|---|---|---|
| A6.1 | style/proto-sheet.wxss:14-17 `.pdim` z=60、`.psheet` z=70,**所有场景(约 20 处)共用同一对数值**;而 pages/roam/index.wxss:483 `.arrive`=75、:532 `.finish`=78、:197 `.roam-opening`=79、:422 `.mask`=80 都比它们高 | 若在 arrive/finish 显示时再开一个 proto 场景半屏,半屏会被盖在下面(需真机核实调用链);`.roam-scene-host`(`--cy-z-sheet`=800)包住的场景不受影响 → 同样的面板在不同入口层级不一致。建议所有场景统一进 `.roam-scene-host`,或把 `.pdim/.psheet` 提到 token 档 | quality |
| A6.2 | index.wxss:575 `.sharesheet` z=97 / :759 `.woo` z=121 / :811-814 `.bigqr` 128-130 / :918 `.pp-open` z=900 | 散落的魔法 z 值,与 token 档(sheet 800/modal 900)交叉;`.pp-open`=900 压住 `--cy-z-sheet`;`.maptip` z=60 与 `.pdim` 同值(靠父级 stacking context 才没事,易碎) | polish |
| A6.3 | `.pdim`/`.entry-sheet__mask` 同时使用(wxml:23) | 入口半屏遮罩与场景遮罩是两套,关闭手势/动画时长不同步 | polish |
| A6.4 | 安全区 | 入口按钮区 `entry-map__actions`、底卡带固定 366rpx;小屏+iPhone 底部安全区叠加后可视地图过小;`.pbar` 用 `bottom: 38rpx+safe-area`(wxss:700) 可能与 peek 重叠(需真机截图) | quality |

### A7 "不够好玩"
1. 地图上只有一种图钉、没有名字、没有照片(A1.3、A2.6)。
2. 未发现的点完全隐藏(A2.1),玩家没有"前方有宝"的期待。
3. 走远后不刷新地点(A2.2)——漫游的核心是"走",走出去却什么都没有。
4. 点开只有地址+一行文字,没有一句"为什么值得去"的好看引文、没有照片(A3.2、A5.1)。
5. 目录地点不能挑战、没有勋章(A3.4),没有"走到 3 个公园解锁…"这类串联目标。
6. 城市清单入口在 master 已被取消(见 B1),远方城市只能靠拖图看,但拖到别城并未标注"这里有 3 处推荐"。建议加:拖到有地点的城市时顶部出现「上海 · 3 处新发现」条。

---

## B. 城市地点(11 城 33 处)与 #1273

### B1 #1273 现状(重要)
- PR #1273(draft,Oct 23,13 文件 +1208/-8)的**所有内容已在 master**,但形态与 PR 不同(commit 980b7a87 随快照入库,后续 V3-35 重做):
  - `utils/roam-poi-presentation.js` ✔(与 PR 相同:分类 street/shop/park/landmark、type 与展示分离)。
  - 目录数据由 `data/roam-city-catalog.json` 改为 `subpackageRoam/data/roam-city-catalog.js`(进分包,不占主包),`subpackageRoam/utils/roam-city-catalog.js` 增加 `placeNoteForTags`(按种子标签查理由/来源),新增 `subpackageRoam/components/roam-place-note`。
  - **`components/cy/roam-city-guide`(「城市清单」入口按钮和半屏)已删除**;入口改成"目录地点直接画在入口地图上"(`utils/roam-entry-map.js` `isCatalogPlace/buildPlaceMarkers`,V3-35)。
  - `scripts/roam-catalog-import.js`、`tests/unit/roam-city-catalog.test.js`、`docs/roam-city-catalog-20261023.md` 都在。
- 因此**不应再合并/摘取 #1273 的 pages/roam 改动**(会冲突并把已删除的按钮加回来)。#1273 只剩两类可取之物,见 B5。

### B2 内容质量(33 条,逐条读过)
- 构成:街道 11 / 公园 11 / 书店茶馆 4 / 文化空间 7;每城 3 处;`reviewedOn` 全是 2026-10-23(今天 2026-11-14,已 22 天;导入工具要求核对日期 ≤90 天,2027-01-21 前必须重核)。
- 优点:每条有地址、34 字内的原创推荐理由、2 个来源、到访说明;明确"未实地核验""不声称营业/免费";已剔除已停业项。
- 问题:
  1. 11 段"街道/街巷"的地址是路名或"周边公共道路",没有明确入口;**不适合直接做 80m 到点点位**(街上哪个点?)。需要给"起点入口"坐标,或做成线路/区域而非点。
  2. 来源里 `you.ctrip.com`、`ditu.amap.com`、`big5.cri.cn`(繁体镜像)等**不是官方来源**,与"公开资料交叉核对"的措辞比有落差;部分页面可能随时失效。
  3. 理由文案很短(≤34 字),玩家向的故事感不足,缺"一句引文/一个细节";与 owner 想要的"有意思"有距离。
  4. 目录里全是"公共/官方"类地点,没有任何一处是玩法点(无挑战/问答/拍照题)。
  5. 部分非中国大陆点(香港 3 处)坐标系与大陆 GCJ-02 的处理需单独核(香港在腾讯/高德地图上的 GCJ-02 偏移行为不同;导入工具已按城市包围盒校验,但香港包围盒与坐标源要人工确认)。
  6. 测试库里已有的 9 条上海点与目录上海 3 条是否重复/重叠,未核(需 SELECT 对比 name/address)。
- 我**没有**把私有仓库的答案或题库写进本文件;上面只引用了公开目录字段的概况。

### B3 缺什么
| 缺项 | 现状 | 说明 |
|---|---|---|
| GCJ-02 坐标 | 33 条全是 `coordinate:null`,仓库内也没有任何坐标清单 | 阻塞项。需要运营用腾讯/高德逐条查"入口"坐标并填 `--template` 生成的模板(含 provider、providerPoiId、verifiedOn、matchConfirmed) |
| 封面/照片 | 导入 SQL 不写 `cover_img/gallery_json/photo_credit`(roam-catalog-import.js:75-88);仓库只有 3 张 CC0 护照图(subpackageRoam/assets/photos/SOURCES.md) | 需要有授权的照片;每张要记录来源、作者、许可、是否可商用/可改 |
| 引述/一句话 | `quotes_json` 列存在(migration_20261025),导入不写;`/pois` 不返回 | 目录的 `reason` 目前写进 `description`(255) |
| 许可/来源追溯 | 无 license/source 列;来源只在 SQL 注释 | 建议来源写进 `photo_credit` 或新增列;文字推荐理由须原创(目录已是原创) |
| 上架手段 | 点位 status=0;后台只有逐条 `PUT /business/roamPoi/online/{id}` | 无按城市/标签批量上架 |
| 挑战/勋章 | 目录点非 `application_type=3`,无 `/city/nodes/{id}` 详情 | 需官方草稿接口走审核才能带挑战 |

### B4 静态目录 vs roam_poi 行?
**结论:以 roam_poi 行为准(经导入),静态目录只保留为"推荐理由/来源的文案库"。**
- 33 条需要:可被地图读取、有状态(found/pendingRedeem)、可挑战、可后台下架、可统计;静态文件做不到,且会和 `isCatalogPlace`(要求服务端 id)一致。
- 目前 master 已经是这个方向:地图只认带种子标签的服务端行,目录文件只用来"查理由"(placeNoteForTags)。问题是**文案存在双份**(`roam_poi.description` 与目录文件 `reason`),且 `description` 在附近页直出(A4.2)。建议理由只放一处:`roam_poi.quotes_json`(或保持文件但统一入口),并删除 `description=reason` 的写入。
- 静态目录仍有价值:**未定位/未上架时的"城市清单"浏览**(原 PR 的核心体验)——现已删除。如 owner 仍想保留"先看清单、再去地图"的体验,可以在入口地图上叠一个"本城推荐"卡片带,数据来自同一份目录(不请求定位)。

### B5 #1273 移植方案(具体步骤)
原则:不合并 #1273 分支;不 push 任何东西到私有仓库;只提交经审查的补丁到公开仓库新分支,提交前扫描(无密钥、无私有题库)。

1. **不要移植**:`pages/roam/index.*` 的改动(master 已有更新版)、`components/cy/roam-city-guide`(已被 V3-35 废弃)、`data/roam-city-catalog.json`(master 已有分包版本;核对两份内容是否一致即可:master 版 33 条、`reviewedOn` 同为 2026-10-23)。
2. **核对一致性**(只读):把 PR 的 `data/roam-city-catalog.json` 与 master 的 `subpackageRoam/data/roam-city-catalog.js` 做内容 diff,确认没有 PR 后续提交里的修正漏进来。PR 描述自己声明"GitHub 未返回 CI、微信编译与 MySQL 验收未完成",所以 master 上的这套**仍缺 CI 与真机/测试库验收**——这是主要欠账。
3. **补齐数据(新工单,阻塞)**:
   a. 运营填写坐标模板(`node scripts/roam-catalog-import.js --template`),33 条逐条取 GCJ-02 入口坐标;街道类给"步行入口"。
   b. 每城编译一份待审 SQL(`--coordinates … --city SH --output …`),在**隔离测试库**执行并回读;不得连生产库(AGENTS.md 边界)。
   c. 改进脚本:写 `cover_img`、`photo_credit`;不再把 `reason` 写进 `description`(改 `quotes_json`);`tags` 统一格式(见 A5.5)。
4. **后端小改(一个 PR)**:`/api/roam/pois` 加 `truncated` 与 cover/photo_credit 投影;目录点公开详情读口;批量上架接口(按 city_code+种子标签);基础 schema 合并两个迁移。
5. **小程序小改(一个 PR,依赖第 4 步)**:
   a. A4.1 修标签解析(复用 `readTags`)——**在任何种子点上架之前必须先上**;
   b. A1.1/A1.2 入口图不依赖定位、画全部已上架点;
   c. A2.2 走远重拉;A1.3 分类图钉;A1.4 图层+搜索;
   d. 推荐理由/来源 UI(A3.2)。
6. **验收**:测试库导入 SH 三条 → 上架 → 模拟器截图:入口图(未定位/已定位)、点开地点卡、主地图走近发现、附近页 chip/筛选无脏标签;CI 通过;再批量 11 城。
7. **时效**:`reviewedOn` 2026-10-23 → 导入工具 90 天校验在 2027-01-21 失效,需重新核对来源后更新日期。
8. 若 owner 要求"城市清单"半屏回归:仅从 PR 取 `roam-city-guide` 的 wxml/wxss 作为参考,重写为入口地图上的"本城推荐"卡片带(数据复用 `subpackageRoam/utils/roam-city-catalog.js`),不恢复独立入口按钮。

---

## C. 地图

### C1 `<map>` 清单与样式(layerStyle=绑定序号:white 1 / black 2 / paper 3 / 2077 4;`utils/map-style.js:MAP_STYLE`,仓库内全为 0,上传前由 scripts/release-map-style.js 填)
| 位置 | 用途 | 样式来源 | 是否符合 owner 规则 |
|---|---|---|---|
| components/cy/free-map/index.wxml:2(`layer-style="{{layerStyle}}"`:11) | 通用封装,样式由父页传 | 父页 | — |
| pages/roam/index.wxml:112(入口)、:235(主图)、:1001(结算) 经 free-map;index.js:93 `playerMapStyle()` | 漫游 | 固定 black | 玩家**不能切换**(规则要求可切) |
| subpackageRoam/nearby/index.wxml:14 经 free-map;js:11 | 附近 | 固定 black | 同上 |
| pages/searchmap/index.wxml:40;js:1162 | 搜索地图 | 商家视角 white,其余 black | 符合(搜索图是否算"自由图"需 owner 定) |
| pages/play/index.wxml:101/143/1353 经 free-map;index.js:156、play-look.js:26 `_applyMapLook`/`kindForLook` | 玩法 | 按主题外观 black/paper/2077 | 符合;但雷达(wxml:104 `radarMap`,index.js:577)固定 black,不随主题 |
| pages/topic/index/index.wxml:412;js:24 `lightMapStyle()` | 路线详情页(玩家看路线) | 固定 white | **不符合**:城市定向/自由探索应用发布时设置的外观;若是创作者预览则保留并注明(需 owner 确认页面角色) |
| pages/square/components/route-map/index.wxml:2;js:6 | 广场/发布静态图 | white | 编辑器侧符合;给玩家看则同上 |
| pages/publish/fabu/index.wxml:45;js:145 `lightMapStyle()` | 发布编辑器 | white | 符合 |
| components/cy/route-thumb/index.wxml:3;js:5 | 漫游历史轨迹缩略 | 固定 black | 符合;但无浅底 tone(`toneLines`),若加切换需补 |
- **玩家切换样式**:全库无切换 UI、无持久化(只有 `playerMapStyle()` 一次读取)。建议在 roam/nearby 加切换(white/black/paper/2077),`wx.setStorageSync('cy_map_kind')` 带 try/catch;`layer-style` 不能热改,复用 play 的"撤图→nextTick→重建"(`fmStyleReady`);`mapToneFor` 同步给折线/光圈配色;route-thumb、结算图随偏好。**quality**。
- **DARK_MAP.md 措辞**(components/cy/free-map/DARK_MAP.md):第 19、27、53、58(`layerStyle: 你的styleId`)、75 行把 `layerStyle` 说成控制台 **styleId**,与文末附录("绑定序号")及 map-style.js 相矛盾;另外 39-42、55-58、73 行仍写已被 map-style.js 取代的 `MAP_DARK` 填值做法。会误导 owner 填控制台 ID。建议:前半标注历史或按四套序号重写(white=1、black=2、paper=3、2077=4 是**该 subkey 下的绑定序号**,不是 styleId)。**polish**(但填错会整图失效)。

### C2 假地图/画出来的"地图"
| # | 位置 | 画了什么 | 级别 | 替换方案 |
|---|---|---|---|---|
| C2.1 | **pages/play/utils/route-card.js:96-110 `drawBase`**(通关卡 `#routecv`,pages/play/index.wxml:1345;play/index.js:9150-9240、`paintRouteThumb` :9092-9110 `#snapcv`) | 纯黑底+8 条竖向 bezier+9 条横向 bezier 的"街道"网格,注释写明"与城市无关,任何城市都一样",上面叠真实路线折线(`_drawRoute` index.js:9011) | quality(owner 点名的"带一条线的网格") | 结算页已有真实 free-map(wxml:1353);用 `MapContext.takeSnapshot` 取图作底图(见 C3) |
| C2.2 | pages/roam/index.js:5636 `_drawShareArch`;wxml:1084-1092 `rsh-map`(view+rotate 拼线) | 漫游分享足迹卡:深色渐变+线段 | quality | 同 C3 |
| C2.3 | subpackageRoam/session/index.js:500-560;wxml:30-37 | 会话足迹卡,纯色 `mapBg`+线段 | quality | 同 C3 |
| C2.4 | pages/topic/components/cy/route-sketch/index.js:39-45 | 固定种子随机"街道纹理"+真站点连线,文件头注明"示意" | polish | 有坐标时直接挂真 `<map>`(同 route-thumb 做法),sketch 仅作无坐标兜底 |
| — | pages/play chapter-flip、subpackageJourney settle | 无地图绘制(chapter-flip 的 grid 是点阵字;settle 是文字海报) | 无需处理 | |

### C3 分享图方案(canvas 不能截原生 `<map>`)
- 代码库已有可用能力:`wx.createMapContext(id, this).takeSnapshot(...)`,见 pages/square/components/route-map/index.js:88。无新依赖。腾讯静态图 API 不推荐(需 WebService key 与域名、且不带个性化样式)。
- 流程:页面里有一个可见、`interactive=false`、`include-points` 的真实 `<map>`(已挂折线/marker)→ `bindupdated` 后 `takeSnapshot` 得 `tempImagePath` → 作为底图 `drawImage` 进现有分享 canvas → 叠文字/统计/二维码 → `canvasToTempFilePath`。失败显示"地图未生成 · 重试"(play 已有 `finishSnapError`),**不要**悄悄退回画线冒充真图。
- 隐私:漫游图须遵守现有路线隐私约束(tests/unit/roam-route-privacy.test.js),起终点不额外暴露;快照含底图 POI,需评估。
- **需真机验证**:个性化 subkey 下 takeSnapshot 是否带样式、安卓/iOS 同层渲染差异、被 cy-sheet 遮挡时是否截成空白。

---

## 修复建议顺序(一个波次里的先后)
1. 数据线(B5 步骤 3-4)与 **A4.1 标签解析**——种子点上架前必须先修。
2. A1.1/A1.2、A2.2、A2.1(定产品:未发现点是否可见)——决定"地点在漫游里出现"。
3. A1.3-A1.5、A3.x 地点卡/挑战统一。
4. C2 真地图分享、C1 样式切换与 `topic` 页样式、DARK_MAP.md。
5. A6 层级与布局(需真机截图后再动)。

## 待 owner 决定
1. 主地图是否显示未发现的点(A2.1)。
2. `topic/index` 路线地图是玩家页还是预览(C1)。
3. 附近页是否直出 `description`(A4.2)。
4. 目录点是否需要挑战(影响是否走官方草稿流程)。
5. "城市清单"是否回归(B5.8)。

STATUS: DONE
