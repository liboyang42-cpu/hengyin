# WP04 A：支付／退款 topic 首锁接线候选

已实现 A 负责的支付完成、零元付款、退款审核、支付／退款 inbox mutation 的 topic→activity（如有）→同父单报名→退款锁序；退款终态与 inbox DONE 原子提交，并修正旧 RR 快照下的同终态重放判定。**仅源码与静态检查完成；Java、MySQL、CI 均未运行。外层锁序和 G 冻结分项冲回尚待成组集成，不能单独宣称 WP04 闭合。**

基线为公共 `06a571b1247620a9bdcea068be0ded973671d17c`；独立目录 `/workspace/hengyin-a-wp04-topic-fence`、分支 `codex/architecture-day-a-wp04-topic-fence`。不含已冻结提现、Quartz 或玩法批次。无生产 DDL、依赖、CI/registry、鉴权政策变更。

## 实际调用与修改

| 实际入口 → owner | 本片触点 |
| --- | --- |
| B 验签／查单 → `handleWechatPayCallback` → 原 inbox `process` → `WxPaymentCallbackMutationService.apply` → `applyWechatPayCallback` | inbox owner/fence 原锁不变；第一把业务写锁先取真实 topic，再锁活动／当前父单报名，最后才走原 `lockBusinessTarget` 和业务 mutation。SUCCESS、REFUND、CLOSED 等原分支均在同一栅栏内；不改 SDK。 |
| `RegistrationCheckoutServiceImpl.finishCheckout` → `completeZeroAmountPayment` → `completePaymentSuccess` | 当前锁读后再检验原零金额；付款 CAS、历史半成品修复及原通知 winner 判据不变。同父单使用已按 ID 锁定的当前行集，保留付款原首位 registrationId，不按排序改通知身份。 |
| `TeamGroupServiceImpl.settlePayingRegistrationsBeforeCancel` → MANDATORY `applyConfirmedWechatPaymentWithinTransaction` | 内层已有同栅栏；外层必须先改首锁，见下节。B 的已证实外部 SUCCESS 原事实不能因 topic 已收口而删除／换号；本片不新增“已结算即拒付”的政策。 |
| 后台 `RefundController.review` → `RefundServiceImpl.reviewRefund`；后台建单 `saveBackOfficeRefund/saveRefund` | `lockRefundRegistration` 先解析真实父 topic，锁后重新核对原报名 ID、member、owner、parentOrderNo、registrationNo／topicId。审核使用当前退款历史，核对原 refund 身份／金额／maker，原退款资格、双审、审核 CAS 和旧 DIRECT 计算体不变。 |
| B `refreshPayoutFromWx`／退款通知 → `RefundServiceImpl.handleRefundNotify` | 原来仅 register inbox 后直接写 payout；现复用原 `process`，在 mutation 事务内以当前锁定行重验原退款及金额，再写 payout 与 DONE。异常整笔回滚，原 claim 服务按 owner/token 释放待重试。 |

`RegistrationTopicFence` 是无 Spring bean、无状态的同域小协作类，复用既有 Mapper 和 `OrderRefundCash.assertOrderScope`，没有新锁表或通用状态机。owner_type=1 取真实 owner topic；owner_type=2 只从 `cms_activity.topic_id` 溯源。独立活动保持 activity→registration，绝不借用相等数字的 topic。topic 不校验当前售卖状态来丢弃已成立的付款；活动父关系、报名身份在锁下不一致就回滚并保留原事件供查证。父单原 XML `order by id for update` 全量锁定，异常归属不静默过滤。

退款终态 CAS 失配后使用调用方已锁住的当前退款行确认同终态；不再普通 SELECT 读回进入 topic 等待前的 RR 旧快照。通知仍仅首次终态提交后发送，精确重放不重复通知。PROCESSING／未知事件没有被改写成 SUCCESS；审核批准仍不代表渠道到账。

## 必须由 root 协调的外层锁序（未修改他人源）

`outer-owner.review.patch` 给出 TeamGroup 三个精确接线 hunk，未应用：

1. `cancelPaidRegistration` 和 `cancelPaidRegistrationByOwner`：在原资格分支可能取得报名／父单锁之前进入同栅栏。
2. 私有 `cancelActivity`：把 activity 首锁改为先解析真实 topic，再沿用 activity 当前锁读。

**此 patch 本身仍不足以覆盖俱乐部外层。** `ClubEventOpsServiceImpl.cancelOccurrence` 在 `selectSeriesByActivityAnyStatusForUpdate`、独立活动 `selectActivityTopicForUpdate` 后，最终调用 `TeamGroupService.cancelActivityByClubOperator`；`cancelActivityWithClubWrapUp` 也先锁 series/occurrence/club/activity，再进 TeamGroup。它们须由实际 owner 将同一真实 topic 栅栏提到第一把业务锁之前，并按既有当前权限规则复验。仅修 TeamGroup，不能撤销 ClubEventOps 已持有的 activity 锁。

可复现的组合反序：T1 ClubEventOps 持 activity 锁后进入取消，等待 topic；T2 支付回调持同 topic 锁，等待该 activity。应先协调外层 owner，不能只应用内层后声称无死锁。review.patch 不修改 RBAC；原主理人／俱乐部授权在等待后的当前事实复核和其他 series 写侧的完整锁图，还须外层 owner 审核，不能由本片推断。

## G 原冻结分项终态入口（精确提议，未应用）

`g-terminal-hook.review.patch` 仅是接口／A 调用点提议：现有 `ICoopSettlementClaimService` 增加 `void reverseCompletedRegistrationRefund(Long refundId)`；G 实现必须 MANDATORY 同事务、按原退款 ID 幂等，读取原冻结订单分项及原受益头，不重算其他订单，固定费只取实际核销席位。现有 `appendExecutedAdjustment(claimId,...)` 不能假造平台／发起人 claim 代用。

A 调用点是渠道到账 `updatePayoutTerminal(...PAYOUT_SUCCESS...)` 的首次及同终态当前行重放、人工双审 `verifyManualPayout` 的原完成 CAS。人工入口也须先取同一 topic／registration／refund 栅栏。**不在 reviewRefund 批准时调用，不使用 SDK“受理”布尔值当到账。** G 必须验证当前 PAYOUT_SUCCESS／人工双审完成的原凭证及原分配，缺证据不能猜数；失败须与本地终态/DONE 一起回滚，B 已成立的原渠道事实仍须独立保留供恢复。

该 review.patch 的抽象方法还没有 G 实现，因此不可单独应用或宣称可编译；本源码候选没有修改 G 接口／文件。历史已 DONE 而无新分项冲回凭证的事件，不能靠 inbox DONE 快速重放补出凭证，须由 G 的原事实恢复入口调查。迟到支付 SUCCESS 仍沿既有 `settlementEntryService.recordOnPayment` 同事务调用，G 须处理已收口原头／现金贡献归属；本片只有共同锁，没有声称解决该算法闭包。

B 侧不要求本片新增 SDK API：退款 facts 继续绑定 outRefundNo／原 outTradeNo／refund 与 total 精确整数分／CNY，入口须已经由 B 的验签／原请求身份及持久事实验证。A 在锁内重新核对这些原字段；外部未知不改原号、不编造退款 SUCCESS，也不将 A 本地 rollback 等同于渠道未执行。

## 测试源码、静态结果和边界

- 新 `RegistrationTopicFenceTest`：13 固定 `@Test`，验证首锁顺序、别名无关的真实归属、父单、错误 owner/refund、失去 inbox owner、零元入口及审核 CAS 接线。
- 新 `RegistrationTopicFenceMySqlIT`：9 固定 `@Test`。真实生产 Topic/Activity/Registration/Refund/Inbox XML、Spring 事务和独立连接；包含 topic 持锁时 registration/refund `FOR UPDATE NOWAIT` 仍可取锁、合法零元整单、实际退款通知及同号重放、DONE 写失败整笔回滚/恢复、父活动/会员交错变化、独立活动同数值 topic 不串锁，以及 RR 旧快照终态重放。外部通知／积分配置／G 记账为窄 mock，不声称真实支付、分项金额或 G payout 验收。
- 现有 `RegistrationFundsMybatisIntegrationTest` 在此基线实际 **96** 个固定入口；原全部测试正文 suffix 字节保持，只在共用夹具增加原 parent topic99 行，并让已有窄 topic adapter 执行真实 `FOR UPDATE`。不是把旧 94 的结果转作本批证明。其他旧测试若手工 new mutation 或未提供原 owner/父活动/退款历史，须按新必需真实身份补装配，不能在生产加缺字段 fallback。
- `verify_static.py`／`static-result.json`：仅候选源码检查，复用冻结 `scan_java`，新 13+9 与原96 的固定计数、原正文保留、G/B/D 保护文件／方法字节不变、实际 XML 所需列与独占 fixture 覆盖、两份 review.patch 可定位。没有改静态／计数门禁。

本次实际执行：Python 静态检查、`git diff --check`、两份 `git apply --check`。**实际 Java/JUnit/MySQL 执行数为 0**，没有新 dispatch、生产访问或真实付款。新 fixture 仅在显式 GitHub-hosted loopback scratch 连接下自建随机库，结束只删自建库并读回；清理结果也未验证。

剩余下一项：先让 root 协调 TeamGroup／ClubEventOps 外层首锁与 G 终态 API，成组完成后才统一验证完整支付／退款／payout 的两种先后、迟到 SUCCESS、重复、失败回滚和原分项读回。WP14 执行中失联仍保留原证据，不挤占这一资金闭包。
