# BUG-ROAM1 · 漫游与定位(第一部分)上线前缺陷处理

分支 `fix/bug-roam1-20261023`,基于 master。每条先在当前 master 上重读代码再定性。

定性口径:FIX = 用户/商家可感知的错误行为,改并补回归测试;DEFER = 纯代码卫生、无用户可见影响;
ALREADY_FIXED / NOT_REPRODUCIBLE = 当前 master 上不成立(附证据);OWNER = 涉及资金/定价/产品规则,只做安全的收紧部分并写明待拍板事项。

## 汇总

| 定性 | 数量 | ID |
|---|---|---|
| FIX | 6 | A:P0-23, A:P0-24, A:P0-50, A:P0-58, A:P0-102, B:P1-058 |
| OWNER | 7 | A:P0-01, A:OFF-ROAM-01, B:P1-002, B:P1-026, B:P1-060, B:P2-001, B:P2-028 |
| NOT_REPRODUCIBLE | 4 | B:P1-047, B:P1-081, B:P2-036, B:P2-038 |
| DEFER | 3 | B:P1-059, B:P1-084, B:P2-034 |
| ALREADY_FIXED | 0 | — |

## 逐条

| id | triage | what changed (file) | test | notes |
|---|---|---|---|---|
| A:P0-01 | OWNER | 无改动 | — | 报名侧与积分商城侧的积分抵扣比例/上限统一属于资金与定价规则,需产品/财务拍板「统一汇率、单笔上限、是否由服务端报价」后再改;当前两侧各自的服务端结算逻辑未动。 |
| A:P0-23 | FIX | `ApiRoamController.java`:/reveal 只收 7 位合法 geohash;按会话时长给新格子预算(基础 200 格 + 时长×35 m/s÷100 m),超出部分丢弃不报错(避免客户端待传队列卡死);/finish 的自报里程按 `min(200 km, 时长×35 m/s + 格子数×300 m)` 封顶后落库 | `RoamRevealFinishBoundsTest`(4 条) | 里程只用于展示(历史、社区帖路线引用),探索值仍只按服务端格子/到点记录结算。`distance_source` 标记列需新 DDL,未做(见 P2-034)。 |
| A:P0-24 | FIX | `CommunityPostServiceImpl.java`:关联可选项白名单改为复用发帖校验的同一份 `REFERENCE_TYPES`;`CommunityPostMapper.xml`:删掉 MEMBER 可选项分支,新增 CLUB 可选项(本人创建或已通过的在架俱乐部,口径与快照一致) | `CommunityReferenceOptionsWhitelistTest`(3 条) | 修复前选得出成员但发帖必 400、俱乐部却选不出。 |
| A:P0-50 | FIX | `subpackageMember/components/cy/scene-roam-poi-detail/index.wxml`:答题半屏加 0 尺寸 `page-container` 返回守卫,物理返回/侧滑等同点「取消」 | `bug-roam1-album-and-back-guard.test.js` | 选中提交时先把 `choiceSheetShow` 置 false,原有守卫会忽略随后的 afterleave,不会重复记取消。统一的 `components/cy/sheet` 返回转发属于全站改造,不在本 lane。 |
| A:P0-58 | FIX | 新增 `utils/album-save-outcome.js`(saved/cancelled/denied/failed);`pages/roam/index.js` 存相册返回结果值,导出结果带 `outcome`,渠道结果带 `cancelled`;`subpackageRoam/session/index.js` 用户取消不再挂「保存失败，请重试」 | `bug-roam1-album-and-back-guard.test.js`、`roam-session-share-card.test.js` 新增 1 条 | 回看页修复前点系统面板「取消」会显示失败错误条。分享快照部分保持原设计。 |
| A:P0-102 | FIX | `pages/roam/index.js` `_fetchRoamPois`:每次重拉领请求序号,只认最后一次 | `roam-recovery-flow.test.js` 新增 1 条(负控:去掉序号判断即红) | 附近商家拉取只在进图时调用一次,不存在同代次重入,未改。 |
| A:OFF-ROAM-01 | OWNER | 无改动 | — | 漫游内的任务列表是「全部官方活动」列表(含即将开始/已结束/我的四个分页),换成只返回报名中/进行中且绑定漫游的 `/active-for-roam` 会让后两个分页失效;列表应展示什么范围需产品拍板。护照大卡已走 roam 绑定口径。 |
| B:P1-002 | OWNER | 无改动 | — | 给 `roam_poi` 加坐标唯一键会让同一商场/同一楼里的多家店互斥,且线上若已有重复行迁移会失败;需先定「同坐标多点是否合法」与存量去重规则。 |
| B:P1-026 | OWNER | 无改动 | — | play 页离开时暂停计时与后台定位是否继续是同一个待拍板问题(本 lane 规则:play 页定位行为保持现状);拍板后再按 `_watching` 决定是否暂停计时。 |
| B:P1-047 | NOT_REPRODUCIBLE | 无改动 | — | 两个调用方都到不了「同 key 重复 acquire」:play 每次 `watchLocation` 用带自增序号的独立 key 且先判 `_watching`(`pages/play/index.js` watchLocation);roam 由 `_realStarting/_realOn` 挡住重复开启,`_stopReal` 先 release。 |
| B:P1-058 | FIX | `RoamMapper.xml` `selectPoisNear`:按到包围盒中心的近似距离由近到远排序并 `limit`;`RoamMapper.java` 增加 `limit` 参数;`ApiRoamController.pois` 传 `POIS_NEAR_LIMIT=500` | `RoamPoiPlayerVisibilityMyBatisIT` 新增排序与截断断言(本机 MySQL 8.0.45 实跑通过);`ApiRoamCoordinateBoundaryTest` 校验传入上限 | 截断时丢的是最远的点。 |
| B:P1-059 | DEFER | 无改动 | — | 只是缺 CHECK 约束/枚举常量,所有写入都走 0→1 的 CAS(`updateSessionFinish ... and status = 0`),无用户可见影响。 |
| B:P1-060 | OWNER | 无改动 | — | 三处到点半径(路过 120 m、进店 100 m、点到店宽松 200 m)是各自注释说明过的产品口径;「点到店」坐标由客户端提供、首访发探索值,是否收紧/统一以及是否给探店加瞬移校验需产品拍板。 |
| B:P1-081 | NOT_REPRODUCIBLE | 无改动 | — | 小程序只调用 `/api/ai/club/design`,且 `pages/club/detail/index.js` `onClubAiGenerate` 有 `aiState === 'generating'` 在途锁,连点不会重复请求;其余创作接口无小程序调用方。服务端 requestId 幂等属后续加固。 |
| B:P1-084 | DEFER | 无改动 | — | 现存 88 处 `wx:key="*this"` 绝大多数是静态装饰列表(刻度、星级、拼图块等),未找到会出现重复字符串导致错位的漫游场景;加 lint 规则属门禁扩展,且跨多个 lane 的文件。 |
| B:P2-001 | OWNER | 无改动 | — | 俱乐部勋章当前是主理人手动发放(`/api/club/badge/grant`),创建接口与小程序都没有阈值规则输入;是否支持自动解锁规则是新的产品能力。 |
| B:P2-028 | OWNER | 无改动 | — | 广播失败已可用同一 requestId 补发;自动退避重投与过期终态需要定「重投窗口/过期时长」这类玩法规则,并新增列与定时任务。 |
| B:P2-034 | DEFER | 无改动 | — | 仅列注释措辞;改注释需要一条只为注释的 DDL。P0-23 已在服务端给该值封顶,代码注释已说明其来源是客户端。 |
| B:P2-036 | NOT_REPRODUCIBLE | 无改动 | — | 所有迷雾点要么带 `r: TILE_REVEAL_M`(110),要么不带半径;没有任何来源产出 `r = 0`,`> 0` 判断与 `isFinite` 判断结果一致。 |
| B:P2-038 | NOT_REPRODUCIBLE | 无改动 | — | 本机格子已按 30 天保留裁剪、写入后回读校验;单键约 2.9 万格才触顶,需 30 天内点亮约 680 km²。同步失败的静默是有意的离线降级(继续用本机记忆,下次打开从游标续传)。 |

## 动作台账 / 基线

- 动作台账(`scripts/uiaudit/action-ledger.json`,C00 保留文件)未提交重建:控件数 **5009 → 5010**(新增答题半屏返回守卫的 afterleave),另有 roam/session 页 handler 内容漂移。CI 的台账漂移检查与 `tests/unit/action-ledger.test.js` 的两条台账断言会因此报红,需 C00 统一重建。
- `tests/lint/ui-integrity-baseline.js` 按行号登记:本 lane 对 `pages/roam/index.js`、`subpackageRoam/session/index.js` 的改动保持登记点行号不变,未改基线。

<!-- CY-HANDOFF
lane: BUG-ROAM1
c00_action: 重建 chengyinhub-xcx/scripts/uiaudit/action-ledger.json(npm run audit:actions:update),预期 controlCount 5009 -> 5010;新增控件 = subpackageMember/components/cy/scene-roam-poi-detail/index.wxml 的 page-container bind:afterleave="onChoiceSheetCancel"。
-->

## CI

PR #561,head `dee18185`:

| 运行 | 工作流 | 结果 |
|---|---|---|
| 39963118482 | GitHub CI | Backend tests、迁移布局/安全门、Fast gates、gitleaks、单测分片 19/20 绿;分片 10/20 与小程序静态门 / static checks 红 —— 仅动作台账漂移(C00 保留文件,见上) |
| 39963118439 | Funds MySQL IT | 绿 |
| 39963118641 | isolated-authenticated-redis-recovery | 绿 |
| 39963118360 | Followup 39 selected backend cases | 绿 |
