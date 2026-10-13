# A3/A4/A5维护与通知交付证据

## 当前实现及可证明范围

- A3退款、已付未出票任务：固定MAX、ID稳定页、任务bean跨轮游标及同步执行；单项异常在游标前进后处理，不阻断后页。退款原租约、RF原业务号和UNKNOWN规则保留；出码最终当前资格由parent闸门负责。退款每页200/轮1000，出码每页100/轮500。
- A4原券：`RewardDropServiceImpl.rollDrop`新log和原券待交付意图同事务；`findOriginalDrop`只返回原type3及原log.id/param；`SmsCouponHistoryServiceImpl.grantCouponBySource`校验原member/template，锁来源收据和当前券，再原领取计数/history/交付回执同事务，响应丢失重放回原historyId。题目通关`grantOnce`原唯一日志+发券已同事务，本次未另改重复协议。
- A4订阅：现态查询同时过滤未来start及结束end（结束使用严格`>`）；续订接受MySQL UPSERT返回2，原0失败仍回滚。权益原金额和叠加口径保留。
- A5纯账本：`SettlementServiceImpl.generateBatch`插入/生成ID、绑定实际数量和金额回填失败抛错，不留下0金额的虚假成功；同幂等key跨商家拒绝。`confirmBatch`仍只账本、不余额打款；旧单平台费率、原应付冲正使用既有快照，未引入新银行或合作政策。
- A5微信零钱：建单和CW/CD原业务号同事务落库必须成功才扣余额（任一准备写入失败整体回滚，不留未扣款的处理中假账）；明确FAIL调用独立Spring事务bean `CashWithdrawFailureRecoveryService`，原CAS+当前原账回读+余额+原单回补终态同事务。丢终态写入/崩溃前整体回滚，UNKNOWN仍等待微信原号核实。事务异常后只CAS原处理中转人工，不覆盖并发赢家。既有人工双人复核保留。
- 报名原站内通知：A parent在首次建单事务调用`RegistrationSignupNotificationService.recordIntent`；原ownerType范围和卡payload保持，固定`registration-signup:<primaryId>`，先回读原意图，不重造live活动payload。selfplay已存在pending的内部回放不造未知通知；Checkout旧best-effort直接send删除。意图落库不表示已送达，B负责原收据及扫描。

## 实际执行证据

本子任务实际执行 `git diff --check`，退出0，无空白错误；Python XML解析4份修改mapper（CouponSourceGrantMapper、RewardDropLogMapper、RefundApplicationMapper、MerchantSubscriptionMapper），结果 `4 mapper XML well-formed (syntax only)`。

未运行Java编译、JUnit、H2、MySQL或Redis；授权VPS未配置。本文件的JUnit数量指源码用例，不是测试运行通过数。父任务的Node测试不作为本后端变化通过证据。

## 待运行用例/真实回读

| 用例文件 | 本次目的 | 状态 |
| --- | --- | --- |
| RefundReconcileTaskTest / VerificationQrcodeRepairTaskTest | 永久失败前页、跨页及达到轮额度后跨轮恢复；MAX只读一次 | 编写/旧夹具更新，未跑 |
| CouponSourceGrantTest（6例） | 原来源归属/模板、history重放、legacy UNKNOWN、disabled原政策、回执写入失败 | 编写，未跑 |
| RewardDropServiceTest | 新log ID与待交付意图同源、原payload券恢复禁止XP重放；旧测试生成ID夹具更新 | 编写/修改，未跑 |
| RegistrationSignupNotificationServiceTest（7例） | 原signup key和payload、身份、意图失败、原收据重放；事务故障回滚和MANDATORY | 编写，未跑；H2用fixture持久表，不是B生产outbox全链 |
| MerchantSubscriptionBoundaryMyBatisTest / MerchantCommerceServiceTest | 实际XML未来/结束/当前/历史读取；UPSERT rows=2 | 编写，未跑；MySQL真实行数/时钟仍待验 |
| SettlementServiceImplTest | 商家幂等scope、bind回读与总额写失败 | 修改，未跑 |
| CashWithdrawServiceTest / CashWithdrawStateMachineTest / CashWithdrawFrozenRequeryH2Test | 原账guard、既有FAIL/UNKNOWN/冻结/人工状态兼容，新事务bean接线 | 修改，未跑 |
| CashWithdrawFailureRecoveryTransactionTest（5例） | 真实H2事务fixture独立回读余额和原单：重复/双worker/终态失败rollback/金额变化/原号准备失败rollback | 编写，未跑；非真实MySQL证明 |
| SelfPlayPassIdempotencyTest / TicketSourceAttributionTrustBoundaryTest / ClubWaitlistClaimMembershipRaceTransactionTest / FreeSelfPlayCreateRealDbIT | 首次create新通知依赖手工装配 | 修改夹具，未跑；RegistrationFunds夹具由parent改 |

## 跨包与精确断点

1. A/B窄接口先导提交后A可编译，但运行须B投递实现和其测试migration；未跑B/A合成事务、两个worker投递及原消息独立回读，不声称已送达。没有外部微信通知新渠道。
2. A/C奖励接口已冻结并由C接漫游真实来源。实际首次发现/重放/库存故障、两个worker及原券数量独立回读未跑。新A测试migration未执行；部署前任何生产DDL不在本轮授权范围。
3. **legacy奖励drop log没有原coupon_history关联**：无法证明未发/已发，缺新原来源意图保持UNKNOWN，不自动盲补。这是需原数据关联证据的恢复断点，不等于原奖励全部恢复。
4. 维护游标是任务bean内存状态，重启归零；持续频繁重启时后页公平未有持久进度保证。数据库分页异常仍终止本轮（游标保持已前進部分），不会假报无待处理。
5. 退款和出票仍需真实DB竞争/终态/丢响应回读。现有订单期级结算/分成历史快照/提现资金链已核源码，真实MySQL下原账守恒、委托权限、审计身份与敏感字段投影未验。未新增运营银行流程及合作补偿政策。
6. Cash提现回补helper无渠道外部调用；正式MySQL原余额mapper与ledger事务同连接、进程kill/commit响应未知/人工路径并发仍待验证。提现查询旧`selectPendingForQuery(limit)`未新增持久游标，本次未称整个提现维护公平完成。

最后静态复核：parent自玩insert新增ID非空guard后，SelfPlayPassIdempotencyTest、ClubOperationBenefitTest、MoneyPathContractTest成功插入mock改生成ID模型；未运行。
