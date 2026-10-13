# A 包方法、锁序与调用合同

起点：交接提交 e67e72a（业务基线 0f0c32f）。不合并、不部署。

- A1：保留 PAYING 查渠道状态及已有游标。新增 unpaid 的固定本轮 MAX(id)、固定本轮 now、id > afterId AND id <= maxId、每页 200、每轮 500、单项 finally 保存游标。结束归零；失败下轮重试。关单与唯一释放继续走既有条件更新及单笔事务。
- A2：新增纯策略 `ExploreTicketUsePolicy.rejection(Integer purchaseKind, Date endDate, Date now)`：仅 purchaseKind=3 使用日期闸，now >= end 拒绝，缺 end 暂禁；不改非探索票规则。
- A2：`ICmsRegistrationService.requireCurrentUsableRegistration(CmsRegistration snapshot)` 事务内回读原 ID；对探索票先 activity 当前行锁，再 registration 当前行锁。原 memberId/ownerType/ownerId 与现态必须一致；当前已付、成功及日期才可新使用。非探索票仍保留原日期规则。核销调用必须在外层事务中调用，不把 Controller 快照当当前授权。
- A2：静态出码先当前资格再返旧 URL；动态出码、扫码解析、直接/按章核销统一闸；自动扫描和最终条件更新均排除 purchase_kind=3，不把过期未用改成核销。
- C 接口合同：C 拥有玩法/NPC Controller，接纯策略或 PlayTeamMapper 现有资格真源；历史读不加新禁令。A 提供 hasPaidRegistration/hasCurrentNpcRegistration 的当前资格 SQL，保留方法签名；不得以 pay_expire_time 代替 expires_at。
- 跨包：A 负责奖励券来源 Service/Mapper、报名通知意图；C 漫游触发、B 投递层由各包接调用。维护子合同在 maintenance-contract.md；新增域文件和测试按 A 业务归属。

计划修改主执行文件：OrderCloseTask；CmsRegistrationMapper.java/xml；ICmsRegistrationService；CmsRegistrationServiceImpl；ApiVerifyController；ApiRegistrationController；PlayTeamMapper.xml；新 ExploreTicketUsePolicy 及其用例、订单扫描/当前读资格用例。本包报告仅写本目录；不改共同交接资料、C/B 文件、CI、依赖、鉴权或生产配置。

验证边界：VPS TCP grant 与凭据均未配置，HTTP 探测不可用。云端只做静态/Node；Java/MySQL/Redis 必须交给授权 VPS 或既有 GitHub CI。真实渠道/身份/设备及数据库竞争回读不得以静态结果代替。
