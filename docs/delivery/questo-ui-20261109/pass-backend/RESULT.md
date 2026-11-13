# 通行证收费落地 · 后端 lane 结果（2026-11-13）

分支 `fix/pass-backend-20261113`，PR #818。**未做**：生产部署、账号配置（OfferID/AppKey/商品）、上架、开售开关——全部仍归业主。
`platform-pass.enabled` 与各渠道开关默认仍关闭，套餐仍 `status=0`。本 lane 按仓库规则没有在本地跑 Java 构建/测试，验证全部以 PR 的 CI 为准。

文件：`S` = `chengyinhub-system/.../platformpass/PlatformPassService.java`，`T` = `chengyinhub-admin/src/test/java/com/chengyinhub/business/platformpass/PlatformPassServiceTest.java`。

| 项 | 状态 | 证据 / 测试 |
|---|---|---|
| T01 新购拒 JSAPI | 完成 | S:288（createOrder）、preparePayment 对 JSAPI 单拒绝、channels() 报 jsapi 关闭；`newPassPurchaseNeverTrustsClientChannelAndRejectsJsapi`、`pendingJsapiOrderCanNoLongerBePaidButStaysQueryable`。旧 JSAPI 的回调/退款路径未动：`lateJsapiCallbackForAnOldOrderStillActivatesWhateverTheSalesSwitch` |
| T02 意图冻结 | 完成（见下"渠道字段"） | S:323 建单写 product/offer/env；S:380 签名只用冻结值，旧单首次签名前补冻结；回查校验 order_id/金额/（有则）env；`createOrderFreezesChannelIdentityCoverageAndRefundTermsOnTheOrder`、`signingUsesTheFrozenProductEvenAfterThePlanProductChanged`、`signingRefusesAnOrderFrozenForAnotherEnvironmentOrOffer`、`legacyUnfrozenOrderIsFrozenBeforeFirstSigning`、`remoteEnvironmentOrOrderMismatchIsRejectedWithoutActivation`、`XpaySignerTest.clientParamsSignTheFrozenOfferAndEnvironmentNotTheLiveConfig` |
| T03 发货确认补偿 | 完成 | S:553 开通与 `delivery_status=1` 同事务；S:630 事务外确认，失败只记待办退避；S:658 补偿入口；`PlatformPassDeliveryTask`（bean `platformPassDeliveryTask`）。测试：`failedDeliveryConfirmationKeepsEntitlementAndLeavesARetryTodo`、`pushOnlyConfirmationActivatesOnceAndReportsDelivery`、`duplicatePushOnPaidOrderRetriesPendingDeliveryWithoutASecondSegment`、`channelAlreadyMarkedDeliveredCountsAsConfirmedWhateverTheErrorCode`、`compensationJobDrivesPendingDeliveriesAndIsIdempotent` |
| T04 渠道发起退款 | 完成（iOS 询问/投诉事件未接） | S:807：已付单凭回查退款事实关单，严格匹配单号/微信单号/金额；S:869 同事务撤段并前移；重复通知、迟到支付、伪造通知见测试：`channelInitiatedFullRefundClosesPaidOrderAndRevokesAndShiftsSegments`、`duplicateRefundNoticeOnRefundedOrderChangesNothing`、`forgedRefundNoticeCannotRevokeWhenChannelShowsNoRefund`、`channelRefundFactMustMatchOrderTransactionAndAmount`、`refundBeforeLatePaymentNeverOpensTheEntitlement`。推送入口仍是明文 JSON，未切安全模式；内容安全回调未改动 |
| T05 派生票 | 完成 | S:254 recordClaim 事务内按 订单行→会员锁→段 重验（退款同序）；S:910 退款后停用被撤段派生票、其余按覆盖链夹紧；S:921 续买后顺延被上一段截断的票（不新增票/奖励/库存）。SQL 只经 claim 关联派生 ¥0 票，已核销保留。`refundStopsDerivedTicketsOfTheRevokedSegmentAndClampsTheLaterOnes`、`renewalExtendsTicketsCutAtThePreviousSegmentEndWithoutNewClaims`、`recordClaimRefusesASegmentThatRefundAlreadyRevoked`、`recordClaimLocksOrderThenMemberThenSegmentLikeRefund`、`PlatformPassSelfPlayCoverageTest` |
| T06 退款与复核 | 后端+后台页完成 | 去掉"领取即全拒"；S:692 用过的整单退款须复核通过；S:735 玩家申请（`POST /api/platform-pass/orders/{no}/refund-apply`，进度在订单 `refund` 字段）；S:748 后台通过/驳回（`GET /business/platformPass/orders`、`POST .../review`）；渠道回执=退款单号；渠道部分退款记真实金额并转待复核。后台：退款处理台新增"通行证退款复核"队列（`PlatformPassRefundQueue.vue`）。测试：`usedPassRefundIsRefusedUntilManualReviewApproves`、`approvedReviewRunsTheWholeOrderRefundAndRecordsTheChannelReceipt`、`rejectedReviewRecordsTheDecisionAndNeverRefunds`、`reviewCanOnlyDecideAPendingApplication`、`playerRefundApplicationRecordsUsageFactsAndShowsProgress`、`playerCannotApplyForAnotherMembersOrderOrAnUnpaidOne`、`channelPartialRefundIsRecordedWithRealAmountAndKeepsTheEntitlement` |
| T07 停售分离 | 完成 | S:1020 `xpayHandlingReason` 只看凭据；回调/回查/发货确认/退款/订单查询不再要求 enabled/xpay.enabled；新购与签名要求套餐 status=1 且开关开。`callbacksAndRefundsKeepWorkingWhenTheSalesSwitchesAreOff`、`stoppedPlanBlocksSigningAnOldPendingOrder`。有效持有人权益仍由总开关控制（默认关即无 ¥0），未改 |
| T08 同一口径 | 完成 | S:226 `claimableTopics` 供 coverage/批量覆盖/下单共用；库返回 `passIncluded`（含）与 `passCovered`（对我生效）；`libraryCount` 不含停售/过期/非自玩；订单存 `coverage_snapshot`（S:1005）与 `refund_terms_ref`。空配置=全部已发布不变。`libraryDistinguishesIncludedFromCoveredForMe`、`configuredAllowlistIsSharedBySingleBatchCatalogAndOverviewCoverage` |
| T10 起点/并发/展示 | 完成 | S:611 渠道 `paid_time` 可信才用，否则沿用现行为；S:849 activate 先会员锁再取末段，起点不早于渠道支付时刻且接在已有段后；`beijing()` 与 `*Text` 字段。`segmentStartsAtTheVerifiableChannelPaymentTimeAndLocksThePerMember`、`implausibleChannelPaymentTimeFallsBackToTheCurrentBehaviour`、`instantsAreStoredAndDisplayedInBeijingTime`、`consecutiveSegmentsFormOneCoverageChainButAGapBreaksIt` |

迁移：`chengyinhub-admin/sql/migration_20261113_platform_pass_hardening.sql`（新增列 + `platform_pass_member_lock` 表，幂等；已登记 `ci/migration-dependencies.json`）。`PlatformPassMySqlIT` 夹具加载新迁移与 `cms_registration`，未增加/删减 @Test 数量。

## 需要业主确认 / 处理
1. **新权限：无**。复核沿用既有 `business:platformPass:refund`；但退款处理台路由本身要求 `business:refund:list|review`，复核人需同时具备，是否要给运营补菜单/权限请业主定。
2. **渠道字段未对账号实测**：`paid_time`（unix 秒）、`env`、`wx_order_id`、`refund_fee` 按文档字段使用；`env`/`paid_time` 缺失时自动退回现行为。query_order 不回传商品ID/数量/币种，这三项无法服务端二次核验（币种按 CNY 冻结）。请用沙箱真实回包核对字段名。
3. **iOS 退款询问/投诉事件**：账号实际推送样例未见，故未接线；沿用明文 JSON 入口。
4. **sys_job 登记**：`platformPassDeliveryTask.run()` 仅提供入口（与现有任务约定一致），未写 sys_job 行；启用虚拟支付前请业主登记。此前靠"再次确认/重复推送"也会重试。
5. **退款条款文案**：订单只存引用 `PASS-REFUND-V1`，条款正文、"用过/争议"的判定口径与复核时限由业主定。
6. 续买顺延派生票时未重算主题自身销售窗（仅受购后 90 天与新覆盖链限制）。
7. 旧 JSAPI 待支付单现在不可再支付（按 T01）；若线上有此类单，会显示待支付直至取消。

STATUS: PARTIAL - 代码与单测已全部写齐，但本 lane 按仓库规则未在本地编译/运行，CI 结果待确认；渠道字段待账号实测，其余为业主项
