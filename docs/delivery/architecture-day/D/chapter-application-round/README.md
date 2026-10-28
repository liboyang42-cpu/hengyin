# 章节申请持久轮次与邀请通知

公共 parent `251dbdd8c28ac2e05c815e044afb02c584fd7908`，隔离目录 `/workspace/architecture-day-d-application-round`。bc5/9a及原共享checkout均未改。本片实际闭合 `ApiMerchantChapterApplicationController.invite → MerchantChapterApplicationServiceImpl.invite → ChapterApplicationInviteNotice → 原CommittedOfficialNoticeService/IM intent → afterCommit原ID唤醒/原Quartz恢复`，不是待接线patch。

原Mapper三个CAS WHERE逐字保留：reapply(status=2,del_flag=0)、restoreWithdrawn(status=0,del_flag=2)、reinviteWithdrawn(原id/topic/chapter/merchant,status=0,del_flag=2)。withdrawn是原业务允许恢复的逻辑终态，不新放开任何状态。仅这些CAS成功才在同一UPDATE递增application_round；原NULL只在本次真实新申请成功时起1。首INSERT在SQL固定写1，API输入不能指定轮次；客户端传999不会写成999。PENDING下重复apply/invite保留原拒绝行为，不增轮；审核、撤回、W2批量拒绝、供给退出均不增轮。原apply忽略reapply零行已修成原Exists异常，未赢CAS不得伪造PENDING结果。

邀请两原发送点(首次插入/原撤回重邀)已移除内存通知，原事务冻结 `chapter-app:<topicId>:<原applicationId>:<持久round>:invite`、原merchant recipient、原title/body。每轮不同永久身份；同轮冲突由原IM/微信伴随账本拒绝，不能覆写旧通知；新轮不重置旧UNKNOWN，旧轮仅原号核实。原task→topic→chapter→topic重入→application顺序保持；CAS后当前锁读只读本次已持写锁的round，不先持application再取父锁。无资金/商业资格/名额/节点审核/权限规则修改；UI业务success只表示申请事务完成/通知已登记，不表示送达。

迁移候选仅ADD可空BIGINT及正数CHECK，不回填/删除/合并任何历史。旧行NULL=不可核实，绝不默认完整round1；旧待审邀请不会因迁移/恢复任务自动补发。只有新INSERT或新的原合法重邀命令产生该轮通知。只读预检：MySQL8.0.16+、表InnoDB、原uk_chapter_application_merchant(chapter_id,merchant_id)完整、application_round尚不存在；统计旧NULL规模/状态分布用于审阅。表/列已存在时停止比对，不静默覆盖。DDL未执行，由root串行登记/迁移验收。旧数据和所有已提交轮次/通知永久保留。

回退保留列/轮次/账本，不DROP或把round清NULL/减回/重发UNKNOWN。不能退回不维护轮次的旧写者并继续重申/重邀：须停止这些入口或保留兼容轮次写补丁后再恢复；混合旧写者不在本候选已证范围。不能清历史换取可回退。

范围边界：本片只闭合invite事件。其余审核/撤回通知仍原路径，未声称整个WP08通知域已恢复。Controller现有请求不含requestId/expectedRound；轮次是原业务转换身份，不冒充跨终态API请求幂等/陈旧审核防护。入会域尚未改。旧行其他事件的NULL轮次处置需后续明确，不能拿本片给历史自动补通知。

测试源码：ChapterApplicationRoundMySqlIT 10项普通@Test，覆盖首轮/PENDING重放、原ID撤回重邀且保留旧UNKNOWN、拒绝重申、零CAS、撤回恢复、legacy NULL、首次/重邀回滚、双重邀、只读round/冻结payload冲突。实际生产application Mapper/CAS、服务Spring事务及原IM账本/消费者；父行锁为canonical表JDBC适配，不证明完整审核task交错；资格/节点操作/其他通知/WX为边界double，不冒充完整资质/财务/HTTP/process-kill验收。旧36+5单测和FreeExplore8case/全部断言保留，仅注入typed通知/补成功CAS后round读回/为真实application mapper安装本迁移。另两无执行该Mapper的共享scratch未改。

实际仅静态XML/原WHERE及不相关方法字节保护、原case/断言保护、逐文件SHA256/秘密模式检查；Java/MySQL/CI/HTTP/DDL全部 **NOT_RUN**。无新依赖、任务、线程池、registry或G/A文件修改。原暂停任务不自动开启。
