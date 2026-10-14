# B 事件、指标与实际广场证据

## 已实现与可执行证据

- M0024 / M0133：队列严格合法整批 accepted，部分或缺失回执保留原批；按原完整对象匹配出队；同步 transport 异常、读/写存储失败保留恢复能力；账号、代次与仅内存凭据快照隔离；重复回调守卫；容量满不挤掉在途事件。失败原批本轮只尝试一次，后批可继续。
- M0014 / M0026 的现有成交子链：B 只读原报名/member/owner/payment_time，客户端任意金额与新 key 不制造成交事实、不重复画像权重；A 修实际报名ID producer，B eventKey 别名已接。原评分权重、退款和历史投影政策保持。其他客户端互动仍不是机器人/作弊过滤系统。
- M0017：实际 creativesquare offset 页追加按持久 id 去重，同页及数字/字符串同ID去重；整页重复仍按原 total/pageNum 推进。刷新失败保留旧卡，成功刷新替换旧卡；旧追加/换账号响应不复活旧内容。前台换号清本人待审/驳回及个人态缓存。保持既有列表 API 和权限、卡型。
- M0135 / M0014：聚合单日故障不阻断后日，调度仍报告失败。普通漏斗仍按既有 source/city/用户与起始事件时间连接；没有虚称客户端事件是现金原账。

[node-green.txt](./analytics-evidence/node-green.txt)：59 项、59 通过、0 失败；覆盖 analytics+privacy+跨栈白名单及广场新增/既有恢复测试。

[node-consumers.txt](./analytics-evidence/node-consumers.txt)：28 项、28 通过、0 失败；既有完赛推荐默认关闭/晚回包、无消费者推荐请求未复活、商家经营页原聚合响应与失败语义。

红证据取交接 `e67e72a` 原业务源码放入隔离 fixture，执行相同新增断言，未改工作树源码：[analytics 26 项中 18 失败](./analytics-evidence/node-baseline-red-analytics.txt)、[广场 4 项中 3 失败](./analytics-evidence/node-baseline-red-square.txt)。这证明断言能辨别原缺陷；不将红进程或 fixture 当实际账号验收。负控 logs 去除临时执行路径。

复跑命令：

```sh
node --test chengyinhub-xcx/tests/unit/analytics.test.js chengyinhub-xcx/tests/unit/analytics-privacy.test.js chengyinhub-xcx/tests/unit/analytics-whitelist-crossstack.test.js chengyinhub-xcx/tests/unit/square-feed-pagination-b.test.js chengyinhub-xcx/tests/unit/square-reliability-state-contract.test.js chengyinhub-xcx/tests/unit/square-list-refresh-on-detail-change-contract.test.js chengyinhub-xcx/tests/unit/square-unload-contract.test.js
node --test chengyinhub-xcx/tests/unit/play-finish-route-recommendation.test.js chengyinhub-xcx/tests/unit/show-path-redundancy-contract.test.js chengyinhub-xcx/tests/unit/merchant-marketing-state-contract.test.js
```

## Java 已写测试与未验断点

Selectors `ApiAnalyticsControllerTest,AnalyticsEventServiceImplTest,BizEventLogIdempotencyMapperContractTest,AnalyticsAggregationTaskTest`。新增测试覆盖：同键同内容重复确认、同键异内容拒绝、前批已提交/后批新事件的完整accepted、qualified user/post/day、账号隔离、ignored INSERT 无回读不确认、JSON键序与数据库秒精度、原报名缺失/越账号/错目标、换key同成交只更新一次画像、实际Mapper解析/唯一身份FOR UPDATE与原付款事实SQL、单日失败后页继续。JUnit/Java 未在本环境执行；不能把编写数量当通过数量。

专用 VPS 在本轮子任务工作时未配置/不可达。遵守交接约束，未在 cloud 自建或使用其预置 Java/MySQL/Redis 替代。待 VPS / 既有 GitHub CI 执行编译及明确 selectors，并核非零真实 JUnit 用例数。Mapper 合同测试不是数据库运行。

待同固定源码 MySQL独立连接验证：两个连接同时重放原事件仅一条/一份画像；同键异内容整批回滚；业务画像失败事件也回滚；丢响应后原事件重试全部 accepted；新事件与旧事件同批；unique index 为 event_name/actor_scope/idempotency_key；两批相反锁序的死锁仅重试原对象，不制造新对象。核 DB 时区与 Asia/Shanghai 自然日一致。原报名须真实受控测试身份，核免费/付费/后退款的原 payment_time 与事件；独立回读 cms_registration、biz_event_log、member_interest_profile 与聚合，而非 HTTP200。

未落盘事件只保同进程内存重试，进程杀死前仍没有落盘崩溃保证。满100条新事件返回false，既有调用方不依赖 analytics 布尔值；没有宣称无限/无损采集。无明细部分 accepted 保留全原批，依赖后端幂等收据；单批业务拒绝可能仍包含其他未确认事件，不擅自猜哪些成功。旧历史成交计数/画像不追溯纠正。

真机/微信开发者工具、账号真实性、真实订单回读、列表实际分页动态删改与截图未验；本轮仅改数据/恢复逻辑，无新UI。A producer 补丁在 A 分支，待集成同源码验证；未合并/部署。

## 实际消费者与原ID去向

生产 JS/WXML 搜索未找到 `/api/recommendation/list`、candidate_type/reasonCodes/cold_start 的现存小程序读取。旧文档曾指 components/cy/profile/index.js，当前文件已在 subpackageMember；现存 show-path-redundancy 测试钉住该死请求不得复活。广场现存 API 为 `/api/creativesquare/list`。首页“推荐”用 `/api/topic/list` is_recommend，完赛附近用 `/api/activity/list`，不能替换成专用推荐新协议。

商家现存 `pages/merchant/marketing/index.js` → `/api/merchant/marketing-home` → 既有 MerchantAggregateReadService/MerchantMarketingAnalyticsMapper：报名/支付读原 cms_registration/create_time/payment_time，核销读 ACTIVE redemption，退款读原 refund_application；均服务端商家owner范围与半开窗口。未编辑其 A/shared 文件。通用 biz_funnel_daily 是行为观测投影，不宣称等于经营原账。

X041：ApiConfigController/NpcFeatureFlags 下发真布尔，未配置默认关；roamNpcChat 固定false；finishNearbyRoute 客户端严格 `=== true`，此键控制既有完赛卡展示，其通用 activity/list 不是独立被该开关授权的付费功能。NPC/club/drops端点闸由对应既有业务所有者/主B验证，未越界修改；开关切换真环境链未验。

| 原ID | 本子任务去向 |
|---|---|
| M0001 | 跨场景画像引擎/实际消费协议待核，保留底层增强项 |
| M0002 | 长期兴趣/本次意图输入与预算合同待核 |
| M0003 | 语义标签/题材过滤生产与消费者待核 |
| M0004 | 无现存专用推荐API小程序消费者；候选权限合同与集成待核 |
| M0005 | 多内容联合推荐仍为底层增强/新编排待定，不归App |
| M0006 | 新帖冷启动试投/曝光承诺待定；当前广场分页已修 |
| M0007 | 专用冷启动回退未接小程序；资格与真实候选回读待核 |
| M0008 | 新商家试投策略待定，保留底层项 |
| M0009 | 新用户输入/问答后置；已有无位置完赛降级Node已回归 |
| M0010 | 多样性策略、对照指标待定 |
| M0011 | 专用API历史 stat_date/过期候选仍待真实消费者与生产作业合同，不声称完成 |
| M0012 | 地域/交通可达预算与真位置链待核 |
| M0013 | 探索/利用评分策略待定 |
| M0014 | 既有成交事实去重、原业务源修补；其他画像版本与退款修正政策仍待核 |
| M0015 | 训练/在线特征版本系统后置，未整项完成 |
| M0016 | 实际广场刷新失败保留/后批继续已修；专用推荐冷启动DB异常策略待核 |
| M0017 | 实际广场offset去重已修并Node回归；无专用推荐分页新协议 |
| M0021 | 创作者公平曝光策略待定，保留底层增强项 |
| M0023 | 不新增曝光交互；既有事件回执修补，不宣称有效曝光产品定义完成 |
| M0024 | 现存客户端原事件队列修补及Node红绿；Java/事务/真实DB回读待验 |
| M0026 | 原报名成交可信度已修；其他互动伪造、设备挑战/封禁及策略仍待核 |
| M0027 | 实验分组平台待核，无新开关/分组策略 |
| M0028 | 推荐质量护栏待定，不能用内容下架冒充 |
| M0029 | 推荐版本回滚机制待定，保留底层项 |
| M0130 / M0132 | 原报名通知持久意图/收据由主B与A合同实现，本子任务不越界 |
| M0133 | 小程序成功回执/账号代次已修；App/跨端平台未实施 |
| M0135 | 现有分母与原经营来源已核，日任务故障隔离已修；真实指标独立回读与新journey/UI仍未验/后置 |
| X041 | 既有开关同义读取已核、完赛默认关闭Node回归；真配置/端点全链主B/其他所有者待验 |
| F0013 | 首页现存“推荐”用topic/list精选参数；真实候选/城市/可参加底层合同待核，新卡UI后置 |
| F0357 | 完赛推荐现有activity/list严格feature闸/原ID排除与晚回包Node回归；可参加/体力/已玩及不足补齐来源待核 |
| F0551 | 当前评分查询不等于内容配置；评分日期/版本/生产作业/实验回滚底层保留待核，新增管理UI后置 |
| M0018 | 现存帖子负反馈存在；跨主题/活动/漫游负兴趣消费及解除范围政策仍待核 |
| M0020 / N002 | 推荐解释须对应真实评分原因/词典/调整反馈；数据字段存在不等于实际解释链完成，底层保留/新UI后置 |
| N003 | 本地新作者试验及回滚/合格曝光/不同作者/阅读参加指标保留待定，不新增流量池政策 |
| F0515 | 官方集体目标V1真实版本/完成事实由实际业务所有者继续核；本子任务不新增指标算法、不宣称完成 |

上述范围均保留原ID。没有消费者不等于App或删除项。来源为本项目既有代码与交接固定规则索引；本实现仅局部适配稳定请求ID、持久成功才确认、失败后页继续语义，未复制第三方完整源码、未新增依赖。
