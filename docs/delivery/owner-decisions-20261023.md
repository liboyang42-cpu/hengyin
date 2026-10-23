# owner 2026-10-23 上线前八项决定(D1–D8)交付说明

分支 `feature/owner-decisions-20261023`,基于 master `c1aff1b8`。每项都先在 master 上重读代码再改;没有一项在 master 上已经满足。

## 汇总

| 项 | 改了什么(文件) | 测试 | 配置键 / 缺省值 | 说明 |
|---|---|---|---|---|
| D1 积分价值(P0-01) | 新增 `chengyinhub-system/.../service/support/PointsValuePolicy.java`;`CmsRegistrationServiceImpl.java`(报价/下单改读 policy,`point_deduct_rate` 与计算同一次读取);`UmsMemberCartServiceImpl.java`(积分商城结算受单笔上限约束,预览返回服务端算好的数字);`MemberCartVO.java`(+`pointsCost`/`pointsValueYuan`/`maxDeductYuan`);新迁移 `chengyinhub-admin/sql/migration_20261023_points_value_config_seed.sql` + `ci/migration-dependencies.json`;小程序 `pages/activity/baoming/baoming.js`(删本地 0.02/50 估算) | `PointsValuePolicyTest`(8 条,含报名价格缺省不变、落种迁移契约);`CartSettlementTransactionalH2Test` 新增 2 条(超上限零写入拒绝、恰好上限放行);小程序 `baoming-points-server-quote-d1.test.js`(3 条) | `points.deduct.rate_yuan_per_point` = `0.02`(1 积分 = 0.02 元,= 原 `CmsConstants.POINT_DEDUCT_RATE`);`points.deduct.max_yuan_per_order` = `50.00`(= 原 `MAX_POINT_DEDUCT_AMOUNT`)。键缺失 / 空 / 非正数 → 按缺省值走并记 error 日志 | 报名价格在缺省值下逐分不变(测试钉住)。积分商城商品本来就按「积分」标价(后台列名「积分售价」),所以商城的扣分数额不变;统一的是:商城用同一汇率把花费折成元,并受同一单笔上限约束 —— **缺省下商城单笔最多 2500 积分(¥50),超过的下单会被拒**,这是 owner 要求的「商城加上限」带来的行为变化;要放宽改 `points.deduct.max_yuan_per_order` 即可。没有改支付、退款、结算流程;没有给 `oms_order.points_amount` 写值(写侧未动)。小程序里没有积分商城客户端页面,所以端上只改了报名下单页。 |
| D2 推荐权重(P0-33/P0-77) | `RecommendationServiceImpl.java`:`payment_success` 8 → 1 | `RecommendationInterestWeightTest`(3 条,含打分任务仍以 status='1' 暂停登记) | — | 打分任务 `recommendationScoreDailyTask.run()` 未启用,无任何迁移改它的状态。 |
| D3 后台漏斗窗口(P0-48) | `AnalyticsController.java`:都不传 = 最近 30 天(含今天,Asia/Shanghai);只传一端 = 从该端起算 30 天;窗口(含首尾)> 90 天拒绝「查询窗口不能超过90天」;响应里的 `startDate/endDate` 改为实际生效日期 | `AnalyticsControllerTest` 改写旧合同(17 条:缺省窗口、上海日界、单端、90 天边界、91 天/超长拒绝、派生日期越界) | 代码常量 `FUNNEL_MAX_WINDOW_DAYS=90`、`FUNNEL_DEFAULT_WINDOW_DAYS=30` | 后台页面不需要改:报错由请求拦截器弹出。 |
| D4 零分母(P1-094) | 新增 `chengyinhub-common/.../utils/SafeRatio.java`;商家漏斗 `MerchantAggregateReadServiceImpl`(环比)、店铺参谋 `MerchantInsightServiceImpl`(核销率/复购率/配额使用率)、俱乐部看板 `ApiStatsController`(完成率,原来零参与者回 0,现回 null)都走它;小程序 `pages/merchant/marketing/ai-insight/index.js` 比率位 null 显示「—」 | `SafeRatioTest`(4 条,含舍入与原实现一致);小程序 `club-p1-state-contract`(+1)、`merchant-ai-insight-contract`、`uiux-merchant-secondary-state-contract` 改写 | — | 只动读侧。写侧唯一的「比率」列是后台漏斗 `biz_funnel_daily.conversion_rate`,它是 `NOT NULL DEFAULT 0`,不可空,本次不改写路径、不加 DDL。店铺参谋 09-19「数字位没有值写 0」的裁决只在比率位被 D4 取代,计数位仍写 0(测试仍钉住)。俱乐部看板端上本来就在参与人数为 0 时显示「—」,null 也照常渲染(新增测试)。性别占比 `sexRatio` 是分布不是比率,未改。 |
| D5 模板同名(U-036) | `CmsTemplateLibraryMapper.java/.xml` 新增 `selectAuthorLiveTitles`;`TemplatePublishServiceImpl.submitTemplateToMarket` 在写投影之前判重 | `TemplateLibraryReviewVisibilityMyBatisTest` +1(H2 上跑真 XML);`TemplatePublishAuditQueueTest` +2(同名拒绝零写入;大小写不同不拦) | — | 比对池 = 同作者、`del_flag=0`、不是本模板自己的广场投影;判等在 Java 里按 trim 后逐字比较(库的 `utf8mb4_general_ci` 大小写不敏感,不交给库)。返回 400「你已经有同名模板了」;小程序两个提交入口(我的项目、我的模板)本来就 toast 服务端 `msg`,无需改 UI。 |
| D6 隐藏创作者比例(U-161) | `chengyinhub-ui/src/views/business/settlementrule/index.vue` 删掉弹窗里的「创作者比例」输入 | `chengyinhub-ui/tests/unit/settlementrule-creator-rate-hidden-contract.test.js`(2 条) | — | 列(`creator_rate`)和两道服务端闸(写入拒非 0、入账拒非 0)不动;新增仍提交 `creatorRate: 0`,编辑沿用行内既有值。列表里的只读列未动。 |
| D7 探店瞬移(U-112) | `ApiRoamController.shopVisit`:到点校验通过后算隐含速度;`RoamMapper.java/.xml` 新增 `selectLastShopVisitLoc` | `ApiRoamShopVisitTeleportAuditTest`(5 条:超速记 warn 仍入账、正常不记、取较新的上一点且兼容 LocalDateTime、脏坐标不记、审计异常吞掉仍入账) | 代码常量 `SHOP_VISIT_TELEPORT_WARN_MPS=50.0` | 只记不拦。上一点 = 该会员最近一次 POI 发现 / 探店里较新的那个,坐标都取来源表(不落玩家原始定位)。现有审计表 `play_arrive_guard_log` 的 `node_id NOT NULL` 等字段是给 /play/arrive 用的,不合适,所以只写日志:logger `com.chengyinhub.web.controller.api.ApiRoamController.teleportAudit`,行首 `event=ROAM_SHOP_VISIT_TELEPORT_SUSPECT action=ACCEPTED`,带 member/session/source/prevKind/distanceM/elapsedSec/speedMps。 |
| D8 主题规模(U-170) | 新增 `chengyinhub-system/.../service/support/TopicSizeLimits.java`(`MAX_CHAPTERS=30`、`MAX_NODES=300`,唯一出处);`CmsTopicServiceImpl`:整包保存的两条写路径(`saveChaptersAndNodes`、`compileChapterFlow`)在任何删除/插入之前校验;发布口 `publishDraftInternal` 按库里实际章节/节点数再校验 | `TopicSizeLimitsTest`(4 条:边界 30/300 通过、31/301 拒绝、两条保存路径先于任何 mapper 拦截、发布按库内计数拦截) | — | 保存超限 → 400「主题最多 30 章,当前 31 章,请合并或删减章节」/「主题最多 300 个节点,当前 301 个,请删减节点」;发布超限 →「发布前请先调整:…」。逐章/逐节点的增量写入(后台章节节点 CRUD、商家补节点)不在保存时拦,由发布口兜住。 |

## 迁移(③)

- 新增 `chengyinhub-admin/sql/migration_20261023_points_value_config_seed.sql`:只在键缺失时 INSERT 两行 `sys_config`,值 = 代码缺省,可重复执行,带回读断言;已登记 `ci/migration-dependencies.json`(`"20261023_points_value_config_seed": []`)。
- 不执行这份迁移也不影响行为(代码按缺省值走);执行后 owner 可以在后台「参数设置」里改两个键。生产执行前走 chengyin-db-migrate 只读预检 + owner 确认。

## UI 改动(只有「—」显示与报错文案相关)

- 小程序 `pages/merchant/marketing/ai-insight/index.js` `_pct`:null → 「—」。
- 小程序 `pages/activity/baoming/baoming.js`:删掉本地积分抵扣估算(JS 逻辑,不改样式/结构)。
- 后台 `settlementrule/index.vue`:删掉「创作者比例」一行 `el-form-item`。
- 没有改任何 wxml / wxss。

## 动作台账

`node chengyinhub-xcx/scripts/uiaudit/build-action-ledger.js --write` 重建:控件 5109 → 5109,tap 2491 → 2491,事件合同 2618 → 2618(只有源码 digest 变化),`action-ledger.test.js` 冻结计数无需改动。

## 验证

- 本地:相关 Java 测试 88/88 通过(`PointsValuePolicyTest`、`CartSettlementTransactionalH2Test`、`RecommendationInterestWeightTest`、`AnalyticsControllerTest`、`SafeRatioTest`、`TemplateLibraryReviewVisibilityMyBatisTest`、`TemplatePublishAuditQueueTest`、`ApiRoamShopVisitTeleportAuditTest`、`ApiRoamCoordinateBoundaryTest`、`TopicSizeLimitsTest`);小程序单测 13877 条,唯一的失败是台账过期,重建后 `action-ledger.test.js` 46/46 通过;后台 UI 新测试通过(其余 15 条失败在 master 上同样存在,原因是本地没装 UI 依赖)。
- 本地门禁:migration sql / dep-order / safety / layout、api-existence、silent-error、forbidden-hosts、secret-file、roam-redline 均通过。
- CI 运行号:见 PR 评论 / 下方补记。

## CI

(推送后补记)
