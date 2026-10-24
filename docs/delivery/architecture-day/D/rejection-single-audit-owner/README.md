# 投诉拒绝审计归回单一表 owner

Parent `0d32d888e29c6d5ef51322b10d7aca736be856ff`。BusinessAuditEventMapper 新增唯一窄口 insertComplaintCloseNoticeRejectionIgnore，原拒绝 INSERT SQL body 原样迁入；ComplaintCloseReporterNotice 改用它及已有 selectByDomainActionRequestForUpdate(PLATFORM,complaintId,COMPLAINT_CLOSE_NOTICE_REJECTED,key)。已有FOR UPDATE查询仅加useCache=false/flushCache=true，谓词和原普通insert/其他方法不变。

删除 `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/CommittedNoticeRejectionMapper.java` 与 `chengyinhub-system/src/main/resources/mapper/business/CommittedNoticeRejectionMapper.xml`；不保留转发namespace，不豁免新writer。原审计/拒绝/IM/WX数据不删；无生产DDL、依赖、架构baseline或C/A/支付代码修改。

原6项case/断言逐字保留，仅attach夹具真实mapper名称/资源装配适配。新增第7项用受控临时库trigger强制真实审计INSERT SQL失败，验证原关闭/扣划/明细/意图/唤醒全部回滚；trigger只在测试源码，未执行任何DDL。不可因INSERT IGNORE或装配迁移吞故障、伪造已登记。原bc5状态与UNKNOWN合同不变。

实际：XML绑定、原SQL body/谓词与原6业务case字节保护、引用/删除namespace静态检查及hash/空白/秘密模式检查；Java/MySQL/DDL/真实架构全门禁/CI均NOT_RUN，由root组合验收。当前review/withdraw工作区保留，收尾也复用同一BusinessAuditEventMapper表owner，不新增审计writer。
