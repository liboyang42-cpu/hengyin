# H / A-e6 父从单恢复增量

本片 parent 是已单独发布的 `24b570a27f014714ea0fb29aa1672080f5be6e51`。继续同一独占工作区；保留原6df、24b与独立4d6 producer，不整并producer、不改A/B文件或资金算法。

实际实现：`CoopClosedPaymentRecovery` 清掉已有L1定位缓存后，复用 A 的 `RegistrationTopicFence.lock`（topic→真实活动→原完整父从单，含scope/身份重验），不再只查付款父行。按Ae6对每个原订单行核对2/2和原transaction；任一从单半态也形成原父单ID+原fact ID/hash 的MANUAL_REQUIRED。没有改传从单ID，没有新SDK动作、金额计算、支付/退款CAS或DONE写入。afterCompletion后自身代理REQUIRES_NEW逻辑保留，不在持topic锁时另开新事务。

精确 A patch 已在原位置 [a-callback-admission.review.patch](../paid-half-state/a-callback-admission.review.patch) 替换：以实际 `e6dc689acd16f8354096fa6017cec85b36171c4e` 为基底；只用其真实 `checkedVerifiedPayment(resultMap).reference()`；内部G准入在Ae6已关账早返回之前，普通回调失败且原事务释放后再调用保留。保持原registerVerified/processVerified、A拒绝保护与原ACK/inbox身份。不再使用旧不存在的checkedVerifiedPaymentReference，也不叠加6df旧patch。

inline同走SUCCESS分支内部G准入；回滚后只保留已存在B原fact/hash的手工身份。没有普通inbox/sidecar的测试不制造它们，更不造DONE。A的payment-fact窄绑定尚未交，本片不声称已完成该绑定或已满足freezeOriginals的原PAYMENT/DONE证据要求；实际晚成功资金补入、最终受控恢复仍缺，不把手工队列等同资金闭环。源码未发现生产claimForRetry drainer；不声称已有自动恢复。

测试源码：保留原P1四条原断言，新增H父2/2+从2/1+已关账+已有现金头反例，P1 suite共5普通声明；24b guard四条保留，新加无inbox/sidecar的inline回滚待办，approved-refund suite共5普通声明。新增两条实际0执行。B事实由真实B存储owner持久化typed fixture，不证明SDK验证。两suite复用同UUID测试helper和精确gate；单独登记由root完成，G不改CI。

静态：A-e6临时公共源码副本patch dry-run fuzz=0；原A helper与当前公共源字节核对；旧四P1及四guard case body保留，新的Java分隔符/fixture列/XML/调用位置检查。不运行Java/MySQL/Redis/CI，无DDL执行。guard-source-sha256是24b当时源码清单；本delta以recovery-source-sha256为准。三个财务政策仍未答，不作选择。
