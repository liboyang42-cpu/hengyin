# 审核 RR 当前发布状态修复候选

- Parent: `563264faf08ebc7318850e84ebbfd62a613c24ba`；只改 AuditTaskServiceImpl 的主题通过状态读取块。此前 RR 窗口是既存问题。
- 入口：`review → applyModeration(BIZ_TOPIC)`；`post_review_flag` 也可到达。旧 task 普通读建立 RR 视图，作者 `ApiTopicController.topicUpdateUserStatus → casUserStatus(1,0)` 提交后，旧 exist 仍可能 userStatus=1。预算预检的锁读曾被丢弃，旧判断会重新上架。
- 修复：普通读仅定位会员；保持原审核任务锁、会员配额锁→主题锁→内容预算顺序。主题完整 `selectCmsTopicByIdForUpdate` 结果决定 alreadyPublished、留置、在线、Beta 和快照分支。归属变化拒绝，避免在主题锁后换会员锁。
- `review` 整段（含精确回执早返回）和其余生产代码字节不变；无 Query/Mapper/结算规则改动，无生产 schema、依赖或调度变动。
- 旧 9 个主题审核测试夹具各补 1 条当前读 stub；全部原 case/断言保持。AuditTopicBudgetDecisionTest 原 8 项保持，新增 5 项：RR 旧上架行、旧留置已清、当前留置、当前发布版本、归属变化。
- 新 `AuditTopicCurrentPublicationMySqlIT`：实际生产 CmsTopicMapper + 作者 Controller/CAS + Spring 审核事务；旧 RR 读发生后，另一事务实际下架并独立读证明提交，再审核通过。严格断言仍下架、版本7不变、Beta不重置、审核事实完成、广场不恢复。旧实现应失败 user_status=0 断言。
- 真库夹具复用现有受控 scratch 配置，缺 PORT/USER/PASSWORD 硬失败，无跳过。仅本次独立临时库添加既存 production 窄写列 review_flag/content_safety_hold 及 JDBC audit_rr_task_fact；close 删除本夹具唯一库并校验 CLEAN。未执行这些 DDL。
- 明确边界：审核任务 JDBC fact adapter；配额/权限、媒体、IM、广场、活动壳为边界替身；不是完整审核 mapper/HTTP/资金/进程 kill 验收。无真实 WX。
- 实际执行：Python 源码范围/旧方法与逐字节保护检查 PASS；git diff --no-index --check 无空白错误。Java、MySQL、全 Spring、CI 均 **NOT_RUN**，交 root 统一验证；不把源码用例当已通过。
- 回退仅本候选读取块和测试装配增量，保留父候选的规模/锁及既有安全修复。原窗口会随回退恢复；不得以回退重开发布绕过。
