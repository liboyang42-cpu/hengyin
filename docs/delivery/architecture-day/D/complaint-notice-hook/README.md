# 真实投诉垫付通知接线

Parent `974f6af8a25f76baa31c39f8f4e48ffcf31f7846`；本片直接修改已补授的 `CoopComplaintServiceImpl.advance` 通知符号及必要注入，不是待应用业务 patch。`CoopComplaintController /business/coopcomplaint/advance → ICoopComplaintService.advance` 原 Spring 事务：原 ACCEPTED CAS→原余额/明细→原冷静期→MANDATORY `enqueueOfficial(complaint-advance:<id>)`→原业务提交→原 ID wake。无支付/退款外呼；所有资金判断、金额、CAS、冷静期以及 close 和其余方法原字节保持。

原内存 sendAfterCommit 唯一一处已移除，冻结原 recipient/title/body；业务失败/意图冲突整体回滚，成功返回1仍只表示垫付业务完成。IM 沿原 imNotificationDeliveryTask 和原消费者恢复，原消息/未读/DELIVERED同事务；WX沿既有新owner原ID，UNKNOWN不自动重投。未新建生产表/迁移/依赖/线程池；G白名单仍仅review.patch（已收窄为G一处），新任务保持默认暂停。

`CoopComplaintCommittedNoticeMySqlIT` 7项普通@Test：真实Controller提交与冻结、外层后失败原子回滚、意图冲突回滚真实余额/明细、重复拒绝、双真实事务CAS、漏唤醒/丢任务ACK、WX UNKNOWN。复用原金融夹具的建库/mapper装配而不调用/冒充其旧测试执行；同DataSource/同事务manager接原生产投诉/会员/余额明细 mapper和IM mapper/消费者，非JDBC业务fact adapter。冷静期/债务/鉴权filter为原边界替身；直接Controller调用不是HTTP或RBAC验证，重建owner不是进程kill，真实WX=0。

旧投诉2类全部case/经济/异常断言保留，仅新增owner注入、将原通知断言迁至真实新合同并加强原冻结文案/禁止旧双轨。原基础设施13项未改case/断言；其fixture新增attach重用原金融DataSource，open保留原JDBC fact测试。缺PORT/USER/PASSWORD硬失败，无skip/放宽expected。

实际仅执行公共预像/范围/字节保护、XML和patch静态检查；Java/MySQL/CI/HTTP/迁移DDL **NOT_RUN**。原共享checkout与所有冻结候选保留。回退不得删永久意图/UNKNOWN或恢复同事件双轨；沿974原身份保全。
