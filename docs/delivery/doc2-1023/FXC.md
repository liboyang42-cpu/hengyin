# FXC — Doc 2 O-06 part 1：券库存守恒

- 基线：master `f099c5be9eb3ef22214ce81e80303eb851f417dc`
- 分支：`feature/doc2-fxc-20261023`（Draft PR → master）
- 范围：并发领券永不超过库存。不改积分定价规则（D-01 未拍板），不碰鉴权/RBAC/JWT、支付链路，无新依赖、无 DDL。

## 1. 只读盘点：所有发券 / 动库存的代码路径

库存口径：`sms_coupon.publish_count`（≤0 或 NULL = 不限量）与 `sms_coupon.receive_count`（已领计数）；
库存真源是同一行上的条件 UPDATE，没有单独的 stock 列。

| # | 入口 | 走到的库存语句 | 原子性 / 同事务 | 业务键幂等 | 结论 |
|---|---|---|---|---|---|
| 1 | `SmsCouponHistoryServiceImpl.grantCouponWithReceipt`（所有发券的公共底座） | `tryIncrementIssuableReceiveCount`：`WHERE id=? AND 状态/发放期 AND (不限量 OR receive_count < publish_count)`，0 行即拒 | ✅ 先占库存后写 history，同一 `@Transactional`；history 写失败抛错连带回滚 | 由调用方负责（见下） | 已守恒 |
| 2 | `grantCouponById` ← 玩法节点券/彩蛋掉落（`ApiPlayProgressController`）、宾果格子（`PlayBingoServiceImpl`）、竞猜结算（`PlayPredictServiceImpl`）、官方活动结算 V1/V2（`OfficialSettlementHelper` / `OfficialEventV2SettlementHelper`）、据点扫码核销（`ApiVerifyController#redeemCityNodeCode`） | 同 #1 | ✅ | 各自先 CAS 业务行：`markRewarded` / `markBetSettled` / `claimReward` / `claimPendingRewardIssuance` / `redeemCityNodeReward`（0→1 只成功一次），节点进度走 progress uk | 已守恒 |
| 3 | `grantCouponBySource`（奖励掉落 `reward_drop`，`RoamRewardDeliveryService`） | `selectForUpdate(coupon_source_grant)` → `selectCouponForUpdate` → #1 → `markDelivered` | ✅ | ✅ `coupon_source_grant` 主键 (source_type, source_id)，已交付返回原 history_id | 已守恒 |
| 4 | `TopicCompletionCouponRewardServiceImpl.grantOnce`（主题通关券） | `insertIgnore` 占业务键 → #1 → `bindHistoryId` | ✅ | ✅ (topic, member) 唯一 | 已守恒 |
| 5 | `MerchantCrmCouponGrantAdapter.grant`（商家 CRM 发券） | `requireOwnedActive` 归属校验 → #1 | ✅ | 投递回执由 CRM 投递层负责 | 已守恒 |
| 6 | **后台手工补录** `SmsCouponHistoryController#add` → `insertSmsCouponHistory` | **原：先 INSERT history，再无条件 `incrementReceiveCount`** | ❌ 不看 publish_count，并发/重复补录可越过库存 | 无（后台人工操作） | **本次修复** |
| 7 | 积分兑换券 | — | — | — | 仓库内不存在该路径（`grep` 无积分换券入口；`/api/roamstamp/exchange` 是印章互换，与券无关） |
| 8 | 退款/退货返还库存 | — | — | — | 无任何路径回补 `receive_count`。作废 `voidUnusedCoupon`（核销冲回）、删除领取记录、撤销核销（只回退 `use_count`）都**不**还库存 —— 只会少发、不会超发，保持现状 |
| 9 | 发布/编辑券模板 | `ApiCouponController#publish` 置 `receive_count=0`；后台 `SmsCouponController` 编辑时把 `publish_count/receive_count` 置 NULL 不让改 | — | — | 不能经编辑改计数 |

## 2. 修复（#6）

- `SmsCouponMapper.xml` 新增 `tryIncrementReceiveCountWithinStock`：
  `UPDATE sms_coupon SET receive_count=ifnull(receive_count,0)+1 WHERE id=? AND (publish_count IS NULL OR publish_count<=0 OR ifnull(receive_count,0)<publish_count)`。
  只核库存，不加发放期/停发闸 —— 后台补录历来允许对停发券登记（`E04_09Test` 的既有口径），本次不改这层语义。
- `insertSmsCouponHistory` 改为：**先条件占库存（0 行抛 `ServiceException("优惠券不存在或库存不足")`，不写记录）→ 再写 history（≠1 行抛错，事务回滚已占库存）**，与 #1 相同的锁序与事务边界。
- 旧的无条件 `incrementReceiveCount` 不再有生产调用方；保留语句是因为既有测试（`E04_03Test`、负控）仍在用它。

## 3. 测试与证据

| 测试 | 内容 | 结果（本地 MySQL 8.0.46 / H2） |
|---|---|---|
| `SmsCouponManualInsertStockTest`（新，单测 4 条） | 售罄拒写记录；先占库存后写记录（InOrder）；记录写失败抛错；无券 id 不动库存 | 4/4 通过 |
| `CouponStockConservationMySqlIT`（新，真 MySQL，已登记 `ci/backend-test.sh` MYBATIS_ITS） | N=20 并发、库存 K=5：`grantCouponById` 恰好 5 成功；20 个不同来源 `grantCouponBySource` 恰好 5 交付且逐个重放返回原结果、不再占库存；20 并发后台补录恰好 5 成功；每条都断言 `receive_count == history 行数 == K ≤ publish_count` | 4/4 通过 |
| 同上 · 负控 | 旧的无条件 +1 在同样 20 并发下 `receive_count=20 > 5` | 通过（证明测试看得见超发） |
| 修复回退负控 | 只把 `SmsCouponHistoryServiceImpl` 还原为 master 版本再跑新 IT | `concurrentManualInsertsOnStockKSucceedExactlyKTimes` 红：`expected: <5> but was: <20>` |
| `E04_09Test`（改） | 原用例锁定「先插记录、计数失败回滚记录」的旧顺序；改为新顺序下的同一事务边界：记录写失败回滚已占库存、计数被拒不写记录、限量售罄拒补录；停发券仍可补录的用例保留 | 4/4 通过 |
| 回归 | `*Coupon*,E04_*,*Redemption*,RegistrationEquity*` 共 250 条 + `B03CouponLifecycleMySqlIT`、`E04_07MySqlIT`、`CouponLifecycleMybatisIntegrationTest` 显式接真库 | 全绿（`E04_07MySqlIT` 在无 `CHENGYIN_TEST_MYSQL_*` 环境时按设计 fail-closed，补环境后 5/5） |
| 门禁 | `ci/test_ci_cost_contract.py`、`silent-error-gate.js`、`forbidden_hosts_gate.py`、`dup_create_table_gate.py` | 通过 |

## 4. 积分：报价积分 vs 实扣积分可能不一致的位置（只记录，不改规则）

抵扣积分数 `pointUsed` 由 `CmsRegistrationServiceImpl.computePrice` 按**会员当时的积分余额**计算
（`min(余额 × POINT_DEDUCT_RATE, MAX_POINT_DEDUCT_AMOUNT)`，再按 `ROUND_UP` 折回积分数），所以余额一变，同一订单的抵扣就变：

1. **报价校验与下单不在同一事务、余额读两次**：`RegistrationOrderServiceImpl.createOrReplay` 在事务外调
   `RegistrationOrderCreateGateImpl.validate`（`:98`）重算报价并比对 `quoteSign`（gate `:57`）；
   随后 `registrationCreationTransaction.create` 里 `CmsRegistrationServiceImpl` 再用普通读（非 `FOR UPDATE`）取余额
   （单票 `:467`、多票 `:716`）重算 `computePrice`（`:474` / `:733`），按这次的结果扣分（`:627` / `:772`）。
   两次读之间若同一会员有并发消耗积分（另一笔下单），实扣 `pointUsed` 会**小于**报价、应付金额**大于**报价，且不会触发 409。
   `deductPoints`（`UmsMemberMapper.xml`，`WHERE point >= #{point}`）只防透支，不防「与报价不一致」。
2. **主题报名（ownerType=1）不校验报价签名**：gate `:39-43` 对 ownerType=1 直接 return，不比对 `quoteSign`；该路径实扣完全以下单时重算为准。
3. **积分流水快照**：`deductPointsAndLog` 写 `currentPoints=userPoint`（`:658`）用的是事务内那次普通读的余额，
   并发时 `currentPoints/afterPoints` 可能与条件扣减后的真实余额不符（扣减本身仍正确）。

是否要「报价锁定积分数 / 下单按报价扣、不一致即 409」属于 D-01 定价规则决策，本次未改。

## 5. 残留风险 / 需拍板

- **后台编辑领取记录可改 `coupon_id`**：`updateSmsCouponHistory` 的 XML 会更新 `coupon_id`，把一条记录改挂到另一张券上时不占新券库存、也不还旧券库存。
  `redeemCoupon` 的注释表明「后台改关联」是被接受的运营操作，所以本次不擅自禁掉；建议拍板：禁止改挂，或改挂走「新券条件占库存 + 旧券回退」。
- 撤销/作废/删除不回补库存（见 #8）：保守方向，不超发；若运营希望作废后可再发，需要另立回补规则。
- 未部署、未在生产库验证；`push ≠ 部署`。
