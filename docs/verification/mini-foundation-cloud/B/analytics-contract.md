# B analytics / 实际广场窄合同

基线 e67e72a；先读 README/COMMON/OWNERSHIP/tasks B/EXECUTION C5/C9。无新增入口、卡型、配额、曝光或付费政策；仅本包文件。

## 事件

实际消费者 `utils/analytics.js` → `POST /api/analytics/events/batch` → ApiAnalyticsController → AnalyticsEventServiceImpl → BizEventLogMapper → 既有画像更新与聚合任务。所有调用点沿用 track/flush/setSessionContext；A/C 的生产调用文件不编辑。原事件发生时间与幂等键在重试中保持。

`data.accepted` 为本事务**已持久化或已存在且同内容**的请求事件数量，不是 INSERT 新增行数。事务任一冲突回滚；同键异内容不能假回执。保留 community qualified view 已确定 user/post/自然日的归一身份；重复阅读只增加一次画像。依赖现有唯一索引 event_name/actor_scope/idempotency_key，不改 schema、鉴权配置或依赖。INSERT IGNORE 后以唯一身份锁定回读；回读不到不确认。服务签名仍 `int recordBatch(Long, AnalyticsBatchDTO)`，无跨包 API 变化。

客户端只接受 code 数字 200 且 data.accepted 为整数并等于本批长度；无明细的部分 accepted 无法判定哪些事件成功，原批保留重试。出队按原批完整快照匹配，不按最新队列长度切前缀。单 worker、重复回调守卫；账号发生变化后旧回执不能写旧/新队列。事件携带服务端已支持的 actorScope。wx 落盘失败不宣称成功、不发送未落盘事件；保留内存待写以便同进程重试，进程被杀前的未落盘无崩溃保证。满 100 条拒绝新事件，不能挤掉未确认旧事件。

## 实际广场

实际入口 pages/square/list/index.js 继续 `/api/creativesquare/list` offset/pageNum 分页；后端现有公开/本人待审/删除/主题门禁保留。追加按持久 id 去重，页码与 hasMore 仍按服务端原始页推进；刷新失败保留旧内容，迟到账号/刷新响应丢弃。既有 onShow 重算临近活动；无推荐 API 消费者时不接新接口。

## 文件与验证

计划修改 analytics.js、既有/新增 analytics Node 测试、ApiAnalyticsController 及测试、AnalyticsEventServiceImpl 及测试、BizEventLogMapper Java/XML，square/list/index.js 与独立 Node 测试，以及本目录 analytics-* 证据。原漏斗、经营数据与开关先核实际调用链；没有确定规则/小程序消费者的机制保留原 ID 待核，不做算法引入。

Node 在工作环境执行；Java/MySQL/Redis 仅批准 VPS / 既有 CI。VPS 不可用，禁止以本地 Java/数据库冒充验收；编写 Java 测试并留待验断点，真实身份/微信开发者工具另留断点。
