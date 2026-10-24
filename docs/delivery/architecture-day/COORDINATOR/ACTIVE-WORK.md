# 当前八 Agent 施工与验收状态

更新：2026-10-24T07:10:50.220713+00:00。源码父提交 `06a571b1247620a9bdcea068be0ded973671d17c`。**全范围未完成，未部署。**

本轮最初回读：E执行中，其余7个已交付后空闲。7个已发送新任务；再次逐一回读，8个均active。下表是最新所有权；active仅证明任务执行，不能作为业务验收。

| Agent | 当前独占任务 | 写入边界 |
|---|---|---|
| A | 支付/退款首个业务锁统一topic，锁下归属复核；保留交易、CAS和回放 | CmsRegistrationServiceImpl/RefundServiceImpl/WxPaymentCallbackMutationService的栅栏与接线；G账本正文不改 |
| B | 持久化已验证的原支付/退款事实，复用现有inbox/attempt | SDK/router/intent/inbox事实层及最小additive SQL源码；不改A/G领域正文 |
| C | 复用现有票码建立稳定逐席位身份和实际核销事实 | 席位/票码/核销事实；共享下单/退款文件只给review.patch |
| D | 补齐章节、节点CRUD和审核alreadyPublished写入的topic栅栏 | 三服务上述精确符号已补授；CmsTopic共享只给patch |
| E | 地图外呼、缓存及城市地理查询容量和失败语义 | TencentMapClient/ApiMapService/MapGeocodeServiceImpl；不改公共传输 |
| F | 为新真实类型补既有架构owner/direction约束 | 既有policy/控制例；不改root CI refs或新造scanner |
| G | 原冻结逐订单分项、实际核销席位固定费、成功退款冲回和debt | 结算/claim/adjustment/debt和additive SQL源码；旧claim约束保留 |
| H | 审查新B路由、A金额边界及root正式下单封口组合 | 只读独立审查，未运行单列 |

root负责统一集成、共享合同、CI与实际业务验收，不新增第9个会话。先做复杂高收益代码，整批组合后统一回归。每10分钟巡查沿用现有自动化，已更新新分工。

## 已批准的业务规则

- 关闭后台通用新增报名，统一正式下单；已纳06a源码。
- 退款仅按该订单原冻结分项冲回，其他订单不重算。
- 固定履约费按每个实际核销席位一次，未核销不计费；必须有稳定席位身份和实际核销事实，不能按购票数推定履约。

## 本轮集成

A afcabd的两个文件逐字节纳入：建单/扣款前拒绝超过渠道整数分上限的金额，原参数化场景等价展开，53个普通@Test；未执行Java/真库。既有旧回执保留其原SHA和数量，不能当作新候选绿项。

## 待统一验收

完整WP00–WP17 / Q1–Q10 / A01–A15 / T01–T22 / G0–G5仍保留。支付路由、topic资金锁、逐席位履约和原分项退款尚在组合施工；Java/数据库/编译后架构/故障恢复与业务持久读回未闭合。缺少验证证据本身不登记为业务BUG。

## 最新组合批次

已组合A5c269调度准入复验、B0ff支付SDK/意图/唯一owner路由、Cf8玩法瞬态故障传播、F1fb请求/异步/Quartz关联及G2457主题资金锁序。两处A/B测试binding、原主题装配、共享CmsTopic字段及资金方法、F/A执行器已串行合成。96个已登记类严格源码预检无错；15个测试入口/登记合同自测通过。Java/数据库/双JVM/业务读回仍NOT_RUN。详见batch-ABCFG-composition-20261024.json。

宾果内层补丁：topicRule和grantCellRewards先透传原TransientDataAccessException，与Cf8外层回滚闭合；普通旁路失败仍best-effort。新增4个普通测试源码，原6例不改，Java/真库未运行。偏好固定回执fingerprint问题仍待治理，不归入本修复。

## 集中CI实际失败与后续修复

run40074201165 / e4e9f6042整体FAIL：发布守卫缺NpcProfileService导入导致Java测试未执行；公共主干新增迁移声明未同步；小程序若干源码契约仍引用已迁移成员及选项弹层旧形式。原失败保留，不计作通过。当前窄修复已补导入、共享扫描/原子领取资格和G8真库夹具的新SDK接线；36项相关Python门禁自测PASS。Java/真库待新批次集中运行，不重复派旧已知失败候选。
