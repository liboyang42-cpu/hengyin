# 原 attempt 关单期迟到 SUCCESS 收口

本增量 parent `d9031fd2286ac8c3c6adf199ead08513cecaacc1`，完整包共同 parent 仍为 `347bcd0226c3bed71616e38ea7c1391fc76efe5c`。d903 七 fixture / 共享 helper 交付冻结不重写；本片另加 CmsTopic 私有方法 7 行及分派 IT 的原一个场景，不改 B SDK、E 原 attempt 接线、A owner、schema 或 CI。

已读取 job121252563040 原日志：11 test / 1 failure / 0 error，失败为 `lateSuccessDuringCloseCannotDeductAndRetryPersistsOriginalPaidFact` 首次 assertThrows 没有异常。该结果不是 gate/建库失败。修后所有验证 NOT_RUN。

真实链：`executeTopicSettlement -> reconcileTopicPendingDepositPayments` 在业务交易/行锁外执行；`resolvePendingDepositPaymentForSettlement` 冻结 business+wx attempt，close 后再查原 attempt。E7b 的精确归属检查、锁外查证、短 TX、typed Reference、current intent、成功状态读回全部保留。原代码在这一回读变 SUCCESS 后直接进入本地已付 CAS，违反旧强负控的“本轮关单/未付决定必须停止、重试才消费”时序。并非已观察到实际发生二扣；已批准自动抵扣并不授权把变更观察当成未缴。

现 readback 归属校验后若 SUCCESS，抛 `IllegalStateException(DEPOSIT_PAYMENT_SUCCESS_DURING_CLOSE)`，在开启短业务 TX / status1→5 / setoff 前停止。实际 gateway `queryOrderStatus` 在返回 SUCCESS 前已用 B real fact writer 独立提交原事实，故没有回滚/清除该事实；首次本地 invite 逐列不变。下一轮首次查单直接 SUCCESS，仍沿原 `applyObservedDepositPaymentForSettlement -> markDepositPaid(..., Reference)` 同一原号/交易/金额消费并读回 status2；不会进 closable/unpaid 分支。初始已 SUCCESS 的合法路径保持可消费，不永久拒绝正额原款。

保留原 11 case 与原其他10场景全部字节；修改原迟到成功场景时保留每个原断言（首次 IllegalStateException、原 row 不变、重试返回 false、status2、原交易/50元、原 CLOSED 为空）。该场景采用现成自有 UUID 子库和 `installRealFactRecoveryStorage`，避免新增真实事实表污染 PER_CLASS 共享库/重复建表；原 finally 只清自己实际创建的库。新增断言：原 fact 在首次本地拒绝后真实持久、business/wxNo/transaction/5000分/原 requestHash 等值、重试引用与 hash 不漂移、仅一事实、再次重放本地 row 不变、只 close 一次、从未走 closable fence。只 SDK 外呼仍为原 double，current/fact reader 为既有真实 SQL。未造 VERIFIED/fact、未将真实 writer 换 mock，未新增调整/经济假表。

本 IT 不冒充完整自动 setoff 资金链：它证明（待 root 实跑）原查证/业务消费窗口，d903 的原 Refund/Scope 场景保留真实调整/净额/回滚覆盖。当前生产 applyUnpaidDepositSetoffs 及其不可变凭证算法零改；status1 本来就受锁内未明拒绝，status2 不在应缴集合。无部分退款/责任/费用新政策。

只执行源码/原日志读取、Git diff 与文件 SHA；parse、tests、compile、Java、DB、HTTP、CI 全部 NOT_RUN。root 串行合7行 production hunk和该 IT，再集中同源验证；不可把本源码交付当原11修后PASS。
