# C3 / C7 漫游实现与证据

基线为交接分支 e67e72a。源码由 C 单一所有者修改；C 只调用 A 所有者提供的原掉落/原来源发券接口，不编辑其生产实现。没有合并、部署、生产 DDL、启用 Quartz 或上传微信。

## 原项与落点

| 原 ID | 本次已完成的已有底层修补 | 可执行证据与保留断点 |
|---|---|---|
| M0113 | 格子写入序列化副本并回读核对；仅对应 revision 和写代次未变且写入成功才清 dirty。失败、静默拒写保 dirty 和待确认队列；确认后恢复记录保存失败重新放回原队列。 | Node 故障注入通过；真实 wx 配额/存储异常未验。 |
| M0114 | pending 保存原采集时间；本人桶与精确字符串 SID/客户端 key 恢复；服务端 FINISHED 后走独立 `/api/roam/session/records`，不执行 reveal、finish、XP 或奖励链。批次先完整校验，再锁原 owner/SID 去重归档。 | Node 丢响应/重复原 SID 补传通过；JUnit/MySQL 已写未跑；缺原时间的历史 pending 保留待核。 |
| M0107 | 有限坐标/经纬范围、精度 0–80m、GCJ02 入口约束；抖动、跳点不改距离或原 anchor；停止后 queued 回调失效。恢复定位首点重新建立 anchor，不把恢复空档拼成里程。 | Node 几何/生命周期通过；城市峡谷、锁屏及硬件采样未验。 |
| M0108 | 使用既有 3m 抖动、120m 位移与服务端 35m/s 速度边界；异常或倒序时间不累计，不污染后续基点。 | Node 越界/非有限/混入 WGS84/跳点/速度/时间边界通过；无服务端完整轨迹验真。 |
| M0109 | 现有发现/探店服务端范围、审核、owner/SID 与半径检查保持；自报坐标不升级为到场凭证。 | 原半径/坐标边界 JUnit 保留未执行；真到店/设备/API 未验。 |
| M0111 | 既有定位消费者只在实际开启后采样；迟到同意/启动及已排队定位回调在 stop/account/unload 后失效。保既有 tracker 持有者与 onHide 时钟规则。 | Node 原入口、退出、时钟及迟到回调通过；省电/电量/平台停止失败未验，未新增采样档位。 |
| M0116 | finish 先原 member 锁，再原 SID/CAS/成长；冻结原 session 的徽章 snapshot 与交付意图。主 finish/XP 成功后，afterCommit 独立事务尝试徽章；失败 DUE，worker 只补原 snapshot、不重开 finish、不加 XP。原券同 source 收据，原额外 XP 同事务 log+金额快照，交付增长+DELIVERED 同事务。 | Node 结算待交付徽章不伪报到手；JUnit/真实 MyBatis IT 已写未跑。A 生产实现依赖尚未集成，Quartz 暂停，真实副作用一次未宣称通过。 |
| F0385 已有子链 | 停止与旧启动回调隔离，保既有同意和 tracker 生命周期；未更改共享定位组件或权限政策。 | Node 原停止链通过；跨消费者撤回同意、系统停止回执与真机未验。 |

另经 A/C 所有者协调，`RoamPoiApplicationServiceImpl.assertActiveQuota` 改用 `countActiveNodesByMerchantForUpdate`，WHERE 仍为原商户/type2/status1，原商户锁之后使用当前读，避免锁前申请读取产生的 RR 旧快照；没有调整原商户限额。相关旧 mocks 已更新，新增 SQL 合同 JUnit 未执行。真实双审核/并发上架旧快照矩阵待测试库验证。

## 冻结方法与文件合同

- `RoamSessionWriteService.archiveFinishedRecords(memberId,sid,key,payload)`：SID/owner 行锁，status1、key 关联、数组 1–200 条、合法 geohash7、整数采集时间位于原 startedAt–finishedAt 且不在未来；同一 tile 不允许改原采集时间。JDBC Timestamp/Date 用毫秒比较。仅写 `roam_session_record_archive`，`CLIENT_REPORTED` 标识为客户端陈述，永不计入 fog、XP、徽章阈值或原账。
- `createPlayerRoamMemory.clearLocal()`：仅当前玩家四个漫游桶 tiles/reveals/sessions/recovery。先 `invalidateRoamCache(owner)`，常驻页面清 pending、采集时间、轨迹/照片/本机足迹/意图与同步句柄，并递增写代次；四类旧回包不能写回。活动行程保已知原 SID，新采集才重新建立恢复记录；空 hide/unload 不重建。清理失败如实返回 false，不声称全部清除。未删除服务端历史或鉴权。
- `RoamRewardDeliveryService.rollOriginalDrop`：实际 A 引擎只掷骰/记日志，C 与该日志同事务冻结当次 type1 extraXp；后续只原 logId 的快照交付。旧 type1 缺冻结金额为 `UNKNOWN_NO_SNAPSHOT`，不根据当前节点/倍率猜金额。type3 由 A `grantCouponBySource(member,coupon,"reward_drop",logId)` 返回原 historyId；来源收据优先读，缺券/库存保 DUE，原 disabled 规则保留。
- `RoamBadgeDeliveryService.prepare`：finish 同事务存原 name/icon/source/obtainTime；失败不能只留日志。`deliver` 独立事务按 member→intent→原 badge 唯一键，加徽章与 DELIVERED 一起提交；不调用成长或更新 session。`RoamRecoveryService` 只读最新 badgeDelivery，原 finish_result_json 不改。首次响应在徽章 afterCommit 尝试后独立回读 badgeDelivery，仅投影响应状态；DUE 保原 SID 的只读恢复检查，重回页面查原 `/session`。小程序与 `_popMedal` 仅对明确 DELIVERED 显示原获得语义，缺收据/UNKNOWN 不当成功，无新增 UI。
- 掉落与徽章扫描各最多 500 条/次、100 条/页；DB 保存 afterId/throughId 的固定上界，跨次继续，游标 CAS 使旧扫描者停止。单项失败仍向后推进，下一完整轮回到失败原项。Quartz 唯一 bean `roamRewardDeliveryTask`，注册 SQL status1/concurrent1，保持暂停。
- 商户额度当前读与 finish 当前读分别走原商户/member 锁。finish 的 member 锁为该事务第一条 MySQL 读取且 flushCache=true；没有把“同账号每日200并发超限”作为已证实问题。

## 已运行验证

`node --test chengyinhub-xcx/tests/unit/roam-*.test.js`：**390 tests / 390 pass / 0 fail / 0 skipped**。原全量首次运行 385/387，通过修正两处真实定位旧夹具（账号 API 与实际 realOn/采样时钟）后重跑为绿。最终补足徽章交付状态、获得弹层与缺收据负控后 390/390。完整输出见 [roam/node-tests.log](./roam/node-tests.log)。

新增故障验证涵盖保存抛错/静默失败/写期间版本变化，四种请求飞行中清缓存与迟到回包，活动原 SID 新采集，FINISHED 原记录丢响应/去重，缺原采集时间不伪造，错误 SID 关联拒绝，停止后迟到同意与 queued 位置，坐标/时间/精度/速度/恢复首点，主结算 XP 与徽章 DUE/DELIVERED 分离，原获得弹层及缺交付状态负控。Node 使用隔离测试请求及存储夹具，不代表真实 API 或 wx 已通过。

`python ci/migration_gates.py sql` 对三个新 roam migration：分句/runner scalar-true 回读绑定通过；`python3 ci/migration_gates.py beans`：新增 bean 注册检查通过。参见 [migration-syntax.log](./roam/migration-syntax.log)、[task-registration.log](./roam/task-registration.log)。所有新增/修改 roam Mapper XML 已用 Python XML parser 解析；这不是 MyBatis 编译/真实 SQL 执行。

新增 Java 单元测试源码 **22 个 @Test**：Archive 6、RewardDelivery 10、BadgeDelivery 5、QuotaCurrentReadContract 1；旧原消费者测试已更新。**未运行 Java，不计为通过**。`RoamSessionWriteMybatisIntegrationTest` 增加本人归档并发/整批拒绝/原账独立回读、原 XP 完成失败回滚与两 worker、主结算成功且徽章失败 DUE 后补发不加 XP；数据库只为随机 localhost scratch schema，CREATE 成功才允许 cleanup DROP，CREATE 失败不会 drop 已有 schema。**该 IT 未运行**。

源文件 SHA256 见 [source-sha256.json](./roam/source-sha256.json)。报告不继承交接资料的历史通过数。

## 必须继续验证的断点

1. Java 编译/JUnit 实际执行数、MyBatis XML 与真实 MySQL RR/锁/事务/断连/两 worker，均待批准 VPS 或现有 CI；云端未启动 Java/MySQL/Redis。测试库独立连接应回读 session 全字段、fog 数、member_growth、player_badge、各 intent、A coupon source 与 history，前后对比。
2. A 原发券生产实现/原掉落 logId 与 type3 pending 收据需随 A PR 集成。独立 C 只带 A 所有者提交的接口合同。历史 type3 缺 A 预存意图返回 null/UNKNOWN，不能据 C DUE 推断缺库存或应重发；旧 type1 无原金额不补发；旧已成功 finish 的失败徽章无当次意图不根据当前定义猜权利。
3. 客户端采集时间不是可信采集证明。归档仅保陈述；缺时间、时钟漂移、原首次离线采集早于服务端 startedAt 的条目会保留待核，不伪造新时间。不支持以这些记录发现金/到店奖励。
4. 真机断网/杀进程/重进/原 SID、wx 存储配额、清缓存失败/飞行中、多账号、GCJ02 返回实际类型、GPS 长空档恢复、平台停止失败、跨消费者撤回、锁屏及耗电均未验。
5. 新 badge/reward 表与暂停任务没有在任何生产库落地，任务没有启用。持续故障的 DUE/UNKNOWN 与暂停调度会保留待办，不能宣称最终交付已验。
