# W17 非现金奖励承诺库存流程（拟议合同）

本文件冻结本包的入口、状态和失败恢复。生产库不执行候选 SQL。没有获批配置行时，对应动作拒绝，不使用 30 天、每季一份或其他产品默认值。现金、随机、保证型不在本包发放。

可售 SKU 库存的统一预留函数不在本包。`IPmsSkuService.deductStock` / `deductStockForProduct` 是订单即时扣减，调用者是购物车结算（`UmsMemberCartServiceImpl`），owner 是商品 SKU 服务。本包不调用它，不建第二份可售库存。承诺数量只记在本承诺版本的分配账上，`supply_ref` 只标识已有券模板。

## 入口

玩家、商户、审核人、员工都从已登录 `LoginAppUser` 取 actor。客户端不能提交 `approved=true` 或 `complete=true` 作为结论。

| 方法 | 路径 | 谁 |
| --- | --- | --- |
| POST | `/api/reward-pledge/drafts` | 本店 `merchant:coupon:manage` |
| POST | `/api/reward-pledge/{pledgeId}/submit` | 同一本店授权 |
| POST | `/api/reward-pledge/{pledgeId}/revise` | 本店授权。新草稿版本，旧冻结版本保留 |
| POST | `/api/reward-pledge/{pledgeId}/confirm` | 商家确认，主体与审核人分开 |
| POST | `/api/reward-pledge/{pledgeId}/freeze` | 审核人。须有获批 `REVIEWER` 配置且不是商家确认人 |
| POST | `/api/reward-pledge/versions/{versionId}/withdraw` | 本店授权。不删除已授券 |
| POST | `/api/reward-pledge/versions/{versionId}/hold` | 获批 `STORE_HOLD` 后，剩余可用划入 `frozen_units` |
| POST | `/api/reward-pledge/awards/{awardId}/return-to-pool` | 获批 `RETURN_TO_POOL` 且承诺仍有效、券未核销、仍在服务窗口 |
| POST | `/api/reward-pledge/compensations` | 获批 `COMPENSATION`。新冻结供给 + 补偿键，受益人沿用原 award |
| GET | `/api/reward-pledge/{pledgeId}` | 本店读回，含版本与库存桶 |
| GET | `/api/reward-pledge/{pledgeId}/stats` | 状态计数、`versionNo`、`asOf`。没有因果指标 |
| POST | `/api/rewards/noncash/claims` | 玩家领取意图 |
| POST | `/api/rewards/noncash/claims/recover` | 只按原 `owner + requestId` 查回，不接受新 requestId |
| GET | `/api/rewards/noncash` | 本人列表 |
| GET | `/api/rewards/noncash/{awardId}` | 本人详情，冻结条款与券状态 |
| POST | `/api/reward-pledge/fulfilment/preview` | 本店 `merchant:verify`，只读 |
| POST | `/api/reward-pledge/fulfilment/redeem` | 同一权限，显式确认后走既有 `redeemCoupon` |
| POST | `/api/reward-pledge/fulfilment/expire` | 到期与核销抢同一券 CAS |
| POST | `/api/rewards/noncash/cases` | 玩家拒兑案件，关联原 award。不退现金。缺货由领取事务写同一张案件表 |

动态核销凭证仍只由既有 `POST /api/coupon/qr-token` 签发。本包返回 `historyId`，不另建凭证真源。核销状态回读走既有 `POST /api/coupon/status`。

## 承诺版本

草稿保存调用方给出的数量、服务期、兑换期和条款。任一期限缺失即拒绝，服务端不补期限。修改生成新版本，旧冻结版本保留。冻结同时要求：商家确认的主体和 hash、审核人的主体和 hash、以及获批的 `RULE` 配置行上的 `rule_version`。撤回把剩余 `available` 划入 `withdrawn`，已有 award 和券历史保留。

## 领取事务

业务唯一键是 `rule_version + beneficiary + reward_scope + fact_ref`。`requestId` 只标识这次意图。

1. 同一 `owner + requestId` 且载荷 hash 相同：读回原结果，不再写。
2. 同键不同载荷：`CONFLICT`。
3. 新意图先单独提交为 `UNKNOWN`。随后的完成事务失败时，这行保持 `UNKNOWN`，库存不变。
4. 恢复只读取这行并继续同一 `requestId`。不得改成新的 requestId。
5. 完成事务内，按顺序：锁库存、若业务键已有 award 则只关联、否则 `available-1/reserved+1`、写 award、调用一次 `grantCouponWithReceipt`、写 receipt、写 outbox、`reserved-1/awarded_unredeemed+1`、意图改为 `AWARDED`。任一步失败，这一事务全部回滚。
6. 换一个新的 requestId 撞上同一业务键：不第二次扣库存，不第二次发券，意图记为 `REPLAY` 并指向原 award。

`grantCouponWithReceipt` 内部的 `tryIncrementIssuableReceiveCount` 是这一次发券记录的发行计数，不是第二份承诺库存，也不是 SKU 可售库存。承诺账的稀缺扣减只有 `available` 减一。

`grantCouponBySource` 只接受 `reward_drop` 来源，本包不调用它。`TopicCompletionCouponRewardServiceImpl.grantOnce` 写的是另一张通关唯一表，再发一次券；`RoamRewardDeliveryService` 是旧投放恢复。两条都不进本包事务，避免第二份回执或第二份扣减。生产发券、核销、冲正只走 `SmsHistoryCouponPort` 上的 `grantCouponWithReceipt`、`redeemCoupon`、`reverseRedemption`。

完成事实只读 `topic_route_session`：`status=1` 且 `completed_at` 非空，分支 `THEME`。`CITY` 与 `MAP` 没有这条权威事实，直接拒绝。`COMPENSATION` 不读主题会话，事实是 `COMPENSATION:{originalAwardId}:{compensationKey}`，并且必须落在另一份已冻结供给上。

## 库存守恒

每个冻结版本：

`available + reserved + awarded_unredeemed + redeemed + expired + frozen_units + withdrawn = quantity`

提交后 `reserved` 为 0。已核销、已过期、已冻结、已撤回都不自动回到 `available`。只有获批 `RETURN_TO_POOL` 且承诺仍有效、券未核销、仍在可履约窗口时，过期未核销的一份才可回池。没有该配置就留在 `expired`。

## 履约与终点

预览不改状态。核销调用 `SmsCouponHistoryServiceImpl.redeemCoupon`，成功后承诺桶从 `awarded_unredeemed` 到 `redeemed`。到期使用同一 `expireUnusedCoupon` CAS。核销与到期只有一边能赢。跨店、撤权和重复确认不得第二次消费。

拒兑和缺货写 `w17_reward_case`，不退现金。补发要新的承诺供给、新的补偿键，以及获批的 `COMPENSATION` 配置。冲正若被获批，调用 `reverseRedemption` 追加审计，不删除 award 或券历史。

## 配置

表 `w17_reward_policy` 没有种子行。`decision` 不是 `OPEN` 或不存在时，对应动作拒绝。

| policy_code | 打开后才允许 |
| --- | --- |
| `REVIEWER` | 指定 `actor_member_id` 冻结版本 |
| `RULE` | 把该行的 `rule_version` 写入冻结版本并允许领取 |
| `CLAIM` | 与冻结版本的 `rule_version` 一致时领取 |
| `RETURN_TO_POOL` | 未核销过期回池 |
| `COMPENSATION` | 新供给上的补发 |
| `REVERSE` | 调用既有冲正 |
| `STORE_HOLD` | 把剩余可用划入 `frozen_units` |
| `CASH` / `RANDOM` / `GUARANTEED` | 本包即使出现 `OPEN` 也不发放。保证型还缺少 W06 预留，读回不得显示保证 |

## 未接通（依赖，不是未写的本包步骤）

- 可售 SKU 预留：无统一预留函数。owner：`IPmsSkuService`。
- iOS 宿主由后续代理接。本包提供上面的 HTTP 合同。
- 不改积分、钱包、支付、现金发奖。
