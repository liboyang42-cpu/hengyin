# 关闭工单通知：无效收件人持久拒绝

Parent `9a193f95430f503b198cb4f984edebce4ef5793c`。真实入口 `CoopComplaintController.close → CoopComplaintServiceImpl.close` 原CAS/余额/明细/欠款/关闭状态次序保留；只将原投诉人通知提交点和必要注入换为 ComplaintCloseReporterNotice。原规则来自 CommittedBusinessNotice：recipient为null/非正时不会调用任何通道，只返回false/告警，业务仍已完成。本片保留该业务结果，停止仅内存记错。

无效收件人：同原事务向现有 business_audit_event 追加 `PLATFORM/<complaintId>/COMPLAINT_CLOSE_NOTICE_REJECTED/complaint-close-reporter:<id>`，结果 REJECTED_INVALID_RECIPIENT。窄adapter INSERT IGNORE→完整当前锁读校验原摘要/状态/actor，不覆盖不同内容；固定白名单包含事件键、输入摘要、recipientKind、schemaVersion，不存title/body/token/手机号。owner=0使用既有PLATFORM无归属约定，SYS_USER/UNKNOWN区分操作员命名空间，不把后台用户伪作会员。

拒绝的是这次通知提交，不能据此推断旧外部动作未执行：既有IM消息/原WX ACCEPTED或UNKNOWN一字不改、不重发。新输入没有原IM/WX意图、没有假messageId或“送达”终态。业务提交后复用原scheduler和AlarmService告警；告警队列拒绝/告警失败不回滚已关闭工单，审计ID仍可按target=COOP_COMPLAINT/id读回。审计数据库写故障仍随原事务失败，不能无持久证据吞错宣称提交。无生产新表/列/DDL/依赖/线程池/调度修改。

有效收件人：冻结原title/body/recipient，转原CommittedOfficialNoticeService及ISystemNotificationIntentService；返回SUBMITTED仅表示已登记。原已拒绝身份不因修正入参而覆盖/改投。原Quartz/IM消费者继续恢复，WX UNKNOWN仍原号人工核实。原API返回1只表示关闭业务成功，不表示通知送达。

必要测试接线：旧11unit、原金融MySQL类及原11项通知生产者case全部保留；旧内存通知double只改为新投诉人提交owner/同原参数，原财务/异常/冻结文案断言不删。新增6项 ComplaintCloseReporterNoticeMySqlIT：null、0/-7、原事务回滚、告警队列拒绝、原UNKNOWN不改投、有效收件人冻结；null项还验证原拒绝收据重放及异payload冲突。用原生产财务/IM mapper、原Spring事务和真实审计adapter；债务/冷静期/鉴权过滤器/告警/WX为既有边界替身，无真实外呼。

审计scratch复用已有canonical migration_business_audit_event_20260823.sql及migration_approval_governance_20260828.sql中既有business_audit_event ALTER原句，不添加/放宽生产schema；未在本执行器执行。缺三项受控连接配置硬失败，无skip/Assumptions。实际仅静态预像/范围/原case与断言保护、XML和hash检查；Java/MySQL/CI/HTTP/DDL均NOT_RUN，由root统一登记6项并执行。

回退不能删审计/IM/WX永久身份或重置UNKNOWN。原关闭财务事实、原拒绝事实均保全，不能因通知拒绝要求重新扣款/重开工单。前两份974/1e2/9a冻结来源和共享工作区均保留。
