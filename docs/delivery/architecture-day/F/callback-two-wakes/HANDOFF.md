# Registration callback fixture：两次提交后唤醒

结论：0cb 单笔新付款成功应唤醒 **QR + signup 各一次，共 2 次**。不是 DONE 探针执行两次；DONE 仍一次。重复回包仍累计 2 次，回滚/未提交仍 0 次。

供体/parent：`0cb8115ee34e4647a76c76bda608fae62bbd51e1`。唯一源码改动：`chengyinhub-admin/src/test/java/com/chengyinhub/business/it/RegistrationCallbackIntentMySqlIT.java`。独立分支 `codex/architecture-day-f-callback-two-wakes`。root 正接的通知 intent/schema 未修改；本片须与它合成后才运行。

| 实际路径（0cb source） | 必需 collaborator / fixture 绑定 | 判定及边界 |
|---|---|---|
| handleWechatPayCallback → processVerified | registration Mapper；real verified facts；real inbox/claim/mutation + DataSourceTx proxy | 已绑定。事实 reader 由 realFacts(source) 绑定原 Mapper + DataSourceTx；不是 inert reader |
| mutation.apply → RegistrationTopicFence.lock | inbox / registration / topic 原 XML Mapper | 已绑定。owner_type=1；activityMapper、refundMapper 本样例不经过，不代表其它分支装配齐 |
| applyWechatPayCallback SUCCESS | verifiedPaymentFacts；coopSettlementClaimService | reader 真实；claim 是显式 mock，仅此 fixture 未覆盖闭场资金准入/恢复 owner |
| completePaymentSuccess → claim/reload/freeze | registration Mapper | 原 XML；本样例 purchase_kind=1，不走 EXPLORE_PASS 冻结支路 |
| completePaymentSuccess → recordOnPayment | real SettlementEntryServiceImpl + transaction proxy | owner_type=1 原实现直接返回，不消费其 activity/rule/detail/claim 依赖；不能签完整结算装配 |
| completePaymentSuccess → awards/scans | real points setting service/Mapper；member、points detail、scan Mapper | 原 XML + 同一 DataSourceTx 已绑定；无 inviter，不走邀请奖励额外分支 |
| registerPaymentAfterCommit → find/enqueue | systemNotificationIntents + 原通知 schema | 0cb 缺失，**root 已认领，不重复补丁**。必须返回原 signup intent 才有第二个 wake |
| registerPaymentAfterCommit → resolveSellerTopic | registration / topic Mapper | owner_type=1 均已绑定；activity 不经过 |
| recordPaymentConsumption（注册 afterCommit 后、原事务提交前） | real facts；registration / registration payment fact Mapper | 已绑定；原消费 receipt 写入/readback 在原事务。markDone 探针仍验证同物理连接 |
| afterCommit → 两次 dispatchInNewTransaction | real CommittedBusinessNotice（本片仅 spy 观察）；原 DataSourceTx；mock ScheduledExecutorService；mock Alarm/INotifyService | 每次 schedule 均原样执行 assertVisibleOutside：独立物理连接可见 paid/DONE/95积分/2流水/1扫描/1消费 receipt；通过后才累计 |
| 已排队 QR worker（本 fixture 不执行） | registration Mapper / usable-registration 路径 / qrcodeService | qrcodeService 未绑；不声称二维码生成/保存通过。执行 worker 要另补真实受控文件边界与对应装配 |
| 已排队 signup worker（本 fixture 不执行） | imService.deliverSystemNotification(originalIntentId) | imService 未绑；不声称 IM 送达通过。执行 worker 要与 root 的真实通知 owner/IM 闭包一致 |
| afterCommit 可选订阅/候补桥 | registration.notifyService / clubWaitlistOrderBridge | 原代码可选分支、本 fixture 未绑定；本片没有新增 optional/null 绕过 |

本片把全部 **12 处**成功计数断言替换成共享断言：真实派发两个精确 eventKey、原 registrationId=11 各一次，加实际 schedule 次数=2。保留原 rollback/未提交的 0 次断言和所有资金/回执/锁/fence/DONE 断言。只把计数改为 2 会遗漏“同事件重复提交”的错误，因此附两条 event 身份校验。独立读回原来位于 schedule Answer 中，两种 wake 均走同一 Answer；异常被生产 dispatch 的 Throwable catch 捕获时，计数未增加，最终断言仍红。

执行：`git diff --check`、限定 diff 的 Python 静态核对、限定补丁秘密扫描。Java/该 MySQL IT/worker 执行：**NOT_RUN**（按授权交 root 同源统一 CI）。此核对仅证明上述样例实际可达链的 fixture 接线；没有把未执行分支/整个 Spring 装配说成通过。
