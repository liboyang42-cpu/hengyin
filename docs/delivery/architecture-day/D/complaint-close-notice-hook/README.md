# 关闭工单：过错方通知实际接线

Parent `1e2e62872390ed7c30a6f3d5d51c11c79481e50e`，依赖其974基础设施与advance接线。本片仅将 `CoopComplaintServiceImpl.close` 原 respondent 通知改为 `enqueueOfficial(complaint-close-respondent:<id>, 原respondent, 原title/body)`；原 if 的 respondent>0/不同于投诉人条件、CAS/扣划/欠款/状态写入与所有其他方法字节不变，无生产DDL/依赖/调度/金额或权限规则修改。

真实入口 `CoopComplaintController.close → ICoopComplaintService.close` 原事务：原 requireOpen/CAS→原实际扣划/明细及欠款→原关闭状态→原投诉人通知注册→新增过错方持久意图→原提交后wake。工单CLOSED拒绝重复close，使用独立事件名+原工单ID永久身份；原实际扣划文案和原recipient冻结。IM沿既有消费者/Quartz恢复；WX UNKNOWN不自动重发，新任务仍默认暂停，G白名单继续只给review.patch。

新增4项实际生产者MySQL回归源码（原7项逐字保持，共11）：真实Controller关闭及冻结实际扣划/收件人；外层后失败回滚真实扣款/明细/两意图；意图冲突回滚真实关闭；重复关闭+漏唤醒+UNKNOWN不重扣/不重发。复用1e同库生产财务/IM mapper夹具，冷静期/债务策略、原投诉人通知、鉴权filter和WX仍为边界替身；不冒充HTTP/完整资金/进程kill验收。

旧unit所有case与经济断言保留，只将原过错方通知断言转至新合同，并加强CAS失败无通知、余额不足时冻结实扣30而不是应扣50。第一片7项/基础设施13项未删除/skip。实际静态范围/原字节与case保护、公共预像/hash/秘密模式检查PASS；Java/MySQL/CI/DDL **NOT_RUN**。

未扩迁：close投诉人通知的原入口对空/非正recipient只有异步false/告警，不改成资金事务内新拒绝；ClubMember.requestJoin与MerchantChapterApplication.reapply/restoreWithdrawn/reinviteWithdrawn复用原ID，且review状态/时间被覆盖，现有recordJoinReview只是原行UPDATE，不提供永久单次尝试ID。禁止拼随机ID/时间或吞payload冲突冒充修复，需root先给这些合同/身份的精确方案。广播/W19/signup/AI和玩家fanout保持原范围。
