# 原通知 caller 的精确共享接缝（SOURCE / NOT_RUN）

观察 source 为公共 fa5c；A/C/G/H 后续 production hunk 只由 root 串行接，F 不以本表覆盖其工作树。

| caller | 已有业务/通知身份 | 是否有可直接复用的持久来源 | 最小接线/限制 |
|---|---|---|---|
| RefundServiceImpl.notifyRefundAfterCommit（fa5c约541–571） | `refund.id`、memberId、refundAmount；从合法退款完成的原方法调用，回调 afterCommit 中调用 `notifyService.notifyRefund` | 原 refund_application 持久业务事实在；此方法没有 noticeId/eventKey/channel attempt，也没有通知 outbox insert。`NotifyService.fanout → InAppNotifyChannel/WxSubscribeChannel` 本身不能证明该退款 WX 已有持久投递身份 | A/root 保留原已核业务提交，不能因本 `Blocked` 改回退款失败/重派；原 catch 已在 afterCommit，F 未动它。若本轮必须补持久退款通知，须 A 明确以**该原退款 ID**为唯一事件身份，在原事务内登记既有适用 outbox，再在提交后只 wake；不能以 member+文本永久去重或拿 `enqueueOfficial` 偷换退款模板。没有已有适用 WX outbox/授权证据时本片不猜造，留原 refund.id 人工待核，不签完整自动通知恢复 |
| CmsRegistrationServiceImpl.registerPaymentAfterCommit（fa5c约2692–2714） | 原 registrationId；`registration-qrcode` 与 `registration-signup` 两个执行标签；原 signup 逻辑中的持久系统通知 intent 属原 source | 原报名/付款消费/已核事实与 signup IM intent；但 CommittedBusinessNotice 的 Supplier/Runnable 自身没有独立持久通用队列 | C/root 在已核回调中保留原事实和原 enqueue 后，restore 时暂停 wake；不要让 helper 的 Blocked 冒充付款 NOT_SENT 或 callback rejected。F `dispatchInNewTransaction`/`deliverInNewTransaction` 在入口抛 Blocked，不调用 Supplier、不返回 false/true 欺骗 caller。正常行为不变 |
| CommittedOfficialNoticeService.enqueueOfficial | 原事件键由 caller 明确提供，原 recipient/title/body/requestHash 与 IM intent ID；wx表原唯一键 | 有：`im_notification_intent` 和 `committed_official_notice_wx`；业务写事务同提交。原 API严格 official广播，不能泛化为资金/退款通知 | 已在 F owned code 完成：允许 enqueue，wake 在恢复态不排队，deliver/reconcilePending 不枚举/claim；既有行原 state/hash/owner 不变。原 BEFORE_START 只读原 key，不缺行新建 |
| CommunityPostServiceImpl.runMaintenance | 原 outbox ID、erasure job ID、post/media IDs | 有：原 outbox/erasure queue，原重试/删除身份 | 已在 F owned code 完成：入口拒绝，位于 claim/deletePostNotifications/erase/外部 delete 之前，不能把临时 guard 被 catch 后写成 RETRY/完成 |
| OfficialBroadcast/ClubEvent/MerchantCrm/RoamRecall 自动入口 | 原 broadcast delivery ID / club campaign delivery ID / CRM guard receipt / recall session attempt | 各原 owner 持久队列/receipt/session身份 | 已在 F owned code 完成：恢复态在枚举/claim/频次或订阅授权消耗前暂停；最终 WX 出口也拒绝。不将原 UNKNOWN 重置 READY，不重建 campaign/recipient |

对应 F 可直接应用的 hunk 已在实际生产源中（非建议 noop）：

```java
// CommittedOfficialNoticeService.wake / deliver / reconcilePending
if (RestoreDispatchGuard.isRestoreMode()) return; // 或 int 方法 return 0
// 已有 typed intent 留在原表，enqueueOfficial 不改为吞异常/跳过 insert。

// CommittedBusinessNotice.dispatchInNewTransaction / deliverInNewTransaction
RestoreDispatchGuard.requireDispatchAllowed(); // 在排队、事务与 catch(Throwable) 之前
```

上面的 void/0 是暂停已有持久源的 worker，不是“投递成功”；对没有持久源的 Supplier 入口则明确抛 Blocked，不靠静默丢 Runnable 冒充闭合。通知恢复不能授权任何退款/收费重发。F没有改 RefundServiceImpl、CmsRegistrationServiceImpl、资金 callback、旧SQL或新notice_type。
