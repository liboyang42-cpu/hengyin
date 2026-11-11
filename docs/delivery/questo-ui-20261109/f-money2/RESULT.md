# f-money2 结果：rig 真实 HTTP 资金链、合单关时商户间分账失败关闭、P0-23 真库 IT

分支 `claude/f-money2-20261111`，从 `origin/launch/questo-20261110`（`afa90ffd`）切出。依据：f-money RESULT.md 第四、五、七节，LAUNCH.md 的 Money 规则。

## 一、先看结论

1. **rig 链路夹具已换成现在在售的产品形态，没有放宽任何闸。** 改了四处：
   - 场次由主题所属俱乐部 7001 自己开。跨俱乐部执行场次已经停售，见 `assertSessionClub`。
   - 开售草稿的完成规则改为 `ALL_REQUIRED_CHAPTERS`。四店固定补偿已经停售。
   - 俱乐部 owner 种成 `club` 角色。会费的 owner 写接口要求这个角色。
   - 补了活动结算费率和结算配置两行。缺这两行，结算任务会整批拒结。

   改完后，`chains.py` 的 platform、merchant_club、sale_close 三段都通过。f-money 会费脚本也跑到了「已支付」。
2. **四类订单的真实 HTTP 资金链在 rig 上各跑了两轮：** 新开关全关一轮，卖家收款路由 + 分账开、合单关一轮。每条链都走「下单 → 支付回调 → 结算 / T+14 → 退款 → 对账」。每个流程都对 37 张资金表做了前后全量差分，并记下模拟通道里的每一笔资金移动，见 `evidence/`。两轮的资金都守恒，逐笔核对见第三节。
3. **待你决定 2 已实现：** 合单关时，只要分账要分给卖家以外的二级商户，整单就在提交前转人工，原因码 `SUB_MERCHANT_SHARE_UNSUPPORTED`。不向微信提交任何分账，平台佣金也不分，钱全部留在卖家二级商户。单测和真库 IT 都通过。rig 里的订单都没有第三方份额（PERK 供给不带钱），所以这道闸在 rig 里没有被触发。
4. **P0-23 已有真 MySQL IT：** `P0FreeExploreSupplyRecoveryMySqlIT` 共 4 条，已登记进真库 IT 批次 `p0-acceptance-ac1`（13→17）和 BT。
5. **发现两个资金问题，都没有改代码，需要你决定：**
   - **场次结算两套比例不一致：** 余额按当前 `settlement_rule` 入账，结算明细按报名时冻结的 `fee_platform_rate` 记。结算后退款按明细冲回，主办方在整单已退的情况下欠款 4.83 元。这是上线前就有的结算路径，按 Money 规则不改。
   - **自由探索主题上的自玩票没有结算出口：** 开关全关时，钱留在平台，不给俱乐部记余额。开关开时，分账转人工，39.90 元一直冻结在卖家二级商户。

   详见第五节。

## 二、条目

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机未验 |
|---|---|---|---|---|---|
| FM2-1a rig 链路夹具换成现售形态 | fixed | 场次和对公收款人落在内容所属俱乐部 7001；完成规则改为 `ALL_REQUIRED_CHAPTERS`；俱乐部 owner 9003/9008 种成 `club` 角色（同 `RoleServiceImpl.changeRole`）；玩家二号加入 7001；玩家链走真实入口 `/api/user/address/action` 补参与人姓名；补 `settlement_rule(activity)` 和 `cms_setting(1)` | `scripts/e2e/chain_sale_close.py`、`chain_player.py`、`canonical_draft.py`、`seed_fixtures.py`；`f-money/rig/club_dues_flow.py` 的方案枚举 | rig 上 `chains.py sale_close`：platform、merchant_club、sale_close 都是 PASS，开售快照为现售自由探索形态。`chains.py player` 已付款、四章核销、徽章都落库；通关接口要对象存储，在 rig 里另有缝。`scripts/e2e` 的 7 个单测文件全部通过。f-money 会费脚本：账单 PAID，回调 204 | — |
| FM2-1b 真实 HTTP 资金链（开关全关 / 路由 + 分账开） | fixed（rig）／ needs owner（第五节） | 新增 rig 专用通道模拟器：直连退款受理和查单、电商收付通下单 / 通知 / 查单 / 分账 / 回退 / 退款 / 进件。还有 `RigPartnerBridge`，把桩顶掉的「先问电商适配器」分支原样接回产品适配器。四类订单资金链脚本 | `f-money2/rig/E2eWechatPayStub.java.stub`、`RigPartnerBridge.java`、`install-channel.sh`、`run-money.sh`、`money_chains.py` | 第三节逐流程资金表。`evidence/money-off.json`、`money-on.json` 是全量库行差分；`channel-ledger-*.jsonl` 是逐笔通道移动 | 微信沙箱、真机支付都没跑 |
| FM2-2 合单关时商户间分账提交前失败关闭 | fixed | 计划时：合单关且有份额要分给卖家以外的二级商户，整单记 MANUAL + `SUB_MERCHANT_SHARE_UNSUPPORTED`，一笔接收方都不留。提交前：改动前已落库、含二级商户接收方的待提交计划，同样先转 MANUAL，不调网关。重跑幂等。后台失败重试页加了原因文案，可重新计划 | `ProfitSharePlanner.java`、`EcommerceProfitShareService.java`、`chengyinhub-ui/.../ecommerce-payout/index.vue`；测试 `SubMerchantShareFailClosedTest`（新，8 条），以及 `MoneyFlowLedgerAcceptanceTest`、`CombinedPayoutTest`、`ProfitSharePlannerTest`、`EcommercePayoutServicesTest`、`EcomPayoutMapperMySqlIT` | 16 个资金类 140 条，0 失败（支线回报）。本线复跑 5 个类 64 条，全部通过。真 MySQL 8 上 `EcomPayoutMapperMySqlIT` 9 条、`EcomPayoutWiringMySqlIT` 6 条通过 | 微信侧「拒绝商户间分账」没有在沙箱复现，按你 11-11 的确认实现 |
| FM2-3 P0-23 需补供给恢复真库 IT | fixed | 新增 IT，走生产代码：商家退出、补位入驻、主题上下架 CAS、`/api/topic/update_user_status` 归属校验。按现在的基线补了 `city_code` 和地点合同两个 cms_topic 迁移 | `chengyinhub-admin/src/test/java/com/chengyinhub/business/it/P0FreeExploreSupplyRecoveryMySqlIT.java`、`ci/real-storage-it-registry.json`、`ci/test_real_storage_it.py`、`ci/backend-test.sh`、runbook P0-23 行 | 真 MySQL 8.0.45 上 4/4 通过；`P0FreeExploreSupplyGateMySqlIT` 2 条回归通过；`python3 -m unittest ci.test_real_storage_it` 92 条通过；`ci/test_ci_cost_contract` 通过 | 卡的人工半边（后台截图）没跑 |

本线发现的缺陷：
- **没有改的两个资金问题：** 见第五节 1、2。都属于上线前就有的结算路径，或者需要你定规则，按 Money 规则不改代码。
- **rig 基线 schema 缺一列（HARNESS）：** `sql/base_schema_20260818.sql` 声称已经包含 `migration_20260815_tandianri_h8_publisher_points_funds.sql`，实际缺 `cms_points_setting.rule_version`，活动结算读积分规则时报 1054。rig 里按原迁移文件补跑了一次，见 `run-money.sh`。基线 schema 本身没改，交给 schema 原 owner。

## 三、分流程资金表

- **PAYER** 是玩家。**平台直连** 是平台商户号 `E2EMCH0001`（rig 夹具值）。**卖家二级商户** 是俱乐部 7001 在 rig 里进件得到的 `1901023704`。**9003** 是俱乐部主理人会员余额，**9001** 是平台账户会员余额。
- 金额单位：元。
- 每行的库行变化都来自 `evidence/money-*.json` 的差分。「+n」表示新增 n 行，最终状态写在括号里。

### 3.1 开关全关（今天线上的做法）

| 流程 | 下单收到哪里 | 回调写什么 | 结算 / T+14 | 退款 | 对账 | 库行变化 |
|---|---|---|---|---|---|---|
| 主题门票（自玩票 39.90）→ 退款 | PAYER → 平台直连 39.90 | `wx_verified_payment_fact` +1（PAYMENT）、`cms_registration_payment_fact` +1、回调收件箱 +1 | 未到结算即退款 | 玩家取消 → `refund_application` RF74 → 通道受理 → 退款通知 → 回查写 REFUND 事实 → 平台直连 → PAYER 39.90 | 平台直连净 0 | `cms_registration` +1（payment_status=4，registration_status=3）；事实 +2；收件箱 +2；`refund_application` +1（payout_status=1 已到账） |
| 主题门票 → T+14 → 退款 | PAYER → 平台直连 39.90 | 同上 | **结算任务没有动这笔钱**：主题结算只认经典定向，自由探索主题上的自玩票不在结算范围（见第五节 2）。没有给俱乐部记余额 | 主理人清退 → 平台直连 → PAYER 39.90 | 平台直连净 0；俱乐部 0 | 同上 |
| 场次票（69.00）→ 退款 | PAYER → 平台直连 69.00 | 同上 | 开场前取消 | 平台直连 → PAYER 69.00 | 净 0 | 同上 |
| 场次票 → T+14 → 结算后退款 | PAYER → 平台直连 69.00 | 同上 | `settlementTask`：余额 9003 +62.10、9001 +6.90（按 `settlement_rule` 90/10）。结算明细记 9003 66.93、平台 2.07（按报名冻结的 `fee_platform_rate` 0.03）。认领 +2 | 主理人清退 → 平台直连 → PAYER 69.00。冲回按明细：9001 −2.07（余额剩 4.83）；9003 −62.10（余额归 0），差额 4.83 记欠款 `ums_member_settlement_debt`（refund:merchant） | PAYER 净 0；平台直连净 0；9001 余额 +4.83；9003 余额 0、欠款 4.83。**整单已退，主办方却倒欠平台 4.83**（第五节 1） | `ums_member_balance_detail` +4、`settlement_detail` +4、`settlement_claim` +2、`ums_member_settlement_debt` +1、`refund_application` +1 |
| 俱乐部会费（19.90）→ 退款申请 | PAYER → 平台直连 19.90 | PAYMENT 事实 +1 → `club_membership_invoice` PAID、`club_membership_term` +1 | 会费不进结算，现状是平台代收 | 会员申请退款，只生成 `club_membership_refund_review`（PENDING_REVIEW）。代码里没有自动原路退款，钱不动 | 平台直连 +19.90 | 账单 +1、会期 +1、退款审核 +1 |
| 通行证 JSAPI | — | — | — | — | — | 开关关：总览 `PLATFORM_PASS_DISABLED`，下单 503「通行证暂未开放」，0 行 |
| 对账（整轮） | | | | | 已验签事实汇总：平台直连 PAYMENT 237.70、REFUND 217.80，净 19.90（就是会费） | — |

### 3.2 卖家收款路由 + 分账开、合单关（另开通行证 JSAPI）

| 流程 | 下单收到哪里 | 回调写什么 | 结算 / T+14 / 分账 | 退款 | 对账 | 库行变化 |
|---|---|---|---|---|---|---|
| 俱乐部收款账户 | — | — | 后台 `/business/ecommerce-payout/sub-merchant` 登记 → 提交进件 → 刷新：FINISH，二级商户 `1901023704` | — | — | `ecom_sub_merchant` +1 |
| 主题门票（自玩票 39.90）→ 退款 | PAYER → **卖家二级商户（冻结，profit_sharing）** 39.90。下单前冻结支付意图 | 电商通知 → 产品按原二级商户号查原单、逐字段核对 → PAYMENT 事实（mchid=二级商户）→ 同一业务路由 | 未到结算即退款 | 玩家取消 → RF74 → 产品走电商退款 → 卖家二级商户 → PAYER 39.90 → 电商退款通知 → 回查 → REFUND 事实 | 二级商户净 0；平台 0 | `ecom_order_seller` +1、`wx_pay_order_intent` +1、`ecom_refund` +1（SUCCESS）、事实 +2、`refund_application` +1（payout_status=1） |
| 主题门票 → T+14 → 退款 | PAYER → 卖家二级商户（冻结）39.90 | 同上 | 结算任务：没有可结算主题（同 3.1）。后台「计划分账」与定时分账任务：`ecom_profit_share_order` 记 **MANUAL / `NO_LEDGER_COMPONENTS`**，0 条分账行，**提交 0、分账 0、完结 0**。钱一直冻结在卖家二级商户（第五节 2） | 主理人清退 → 卖家二级商户 → PAYER 39.90；分账单原因改记 `REFUNDED_BEFORE_SHARE`，没有回退 | 二级商户净 0；平台 0；没有分账移动 | `ecom_profit_share_order` +2（两单都是 MANUAL）、`ecom_refund` +1、`ecom_order_seller` +1 |
| 场次票 → 退款 | PAYER → 平台直连 69.00（场次票不走卖家收款，与全关一致） | 同 3.1 | — | 平台直连 → PAYER | 净 0 | 与 3.1 相同 |
| 场次票 → T+14 → 结算后退款 | 平台直连 69.00 | 同 3.1 | 与 3.1 **逐行一致**：9003 +62.10、9001 +6.90；明细 66.93 / 2.07 | 冲回后 9003 欠款 4.83 | 与 3.1 相同 | 与 3.1 相同 |
| 俱乐部会费 | 平台直连 19.90 | 同 3.1 | — | 只建退款审核，钱不动 | 平台直连 +19.90 | 与 3.1 相同 |
| 通行证 JSAPI（月卡 39.00） | PAYER → 平台直连 39.00（PPO 单号） | 回调交给产品真实路由 `WechatPaymentCallbackRouter` → `handleJsapiPaid`：同事务开通会员 | 平台是卖家，没有分账 | 后台退款 → RPP 退款号 → 通道受理 → 退款通知 → 回查写 REFUND 事实 → `handleJsapiRefund` → 订单 REFUNDED（status=3） | 平台直连净 0 | `platform_pass_order` +1（status=3）、`platform_pass_membership` +1、事实 +2 |
| 对账（整轮） | | | | | 已验签事实：二级商户 PAYMENT 79.80 / REFUND 79.80；平台直连 PAYMENT 196.90 / REFUND 177.00，净 19.90（会费） | — |

**两轮对照：**
- **相同：** 场次票、会费在两种开关下的库行与资金移动逐行一致（结算金额、欠款都一样），新开关不改变这两类订单。
- **不同：** 只有主题门票的收款主体从平台直连换成卖家二级商户，退款也从卖家二级商户原路退。通行证只在开关开时可售。

### 3.3 证据里每一处夹具（rig 测试库，带 `[FIXTURE]` 标记）

- **已验签支付事实：** 直连商户号单用的，因为 rig 没有平台商户私钥，同 `chain_player.store_trusted_payment_fact`。电商收付通单不用这个夹具，事实由产品自己查单写入。
- **退款回查后写的 REFUND 事实：** 由通道模拟器写，与真实网关 `queryRefundFactsForOriginal` 同口径。电商单由产品适配器自己写。
- **时间压缩：** 支付时间、场次档期整体拨到 15–21 天前，让 T+14 的真实谓词能选中。
- **自玩价：** 后台入口拒改非官方主题，这是产品规则，所以直接写 `self_play=1`、`self_play_price=39.90`。
- **会费功能闸：** 打开上线前就有的 `feature.flag.clubMembership`。
- **通行证套餐上架：** 迁移自带的套餐默认未上架，这里把它上架。
- **基线 schema 补跑：** 补跑一次 h8 积分迁移（HARNESS，见第二节）。
- **电商进件：** 走后台真实入口，由通道模拟器回 FINISH 和二级商户号。

## 四、rig 跑到了哪里（如实）

- **环境：**
  - rig 分支 `claude/cloud-walk-20261101-rig`，打了 `rig-up-sha-param.diff`、`rig-extra-args.diff` 和 club 的 `harness-patches.diff`。
  - 候选是本分支 `8be3756e` 的 jar（含 FM2-2）。MySQL 8.0.45 和 Redis 7.4 用登记的镜像摘要。Docker Hub 限流时，Redis 改从 `mirror.gcr.io` 拉了同一摘要。
- **一键复现：** `RIG_SWITCHES=off|on bash docs/delivery/questo-ui-20261109/f-money2/rig/run-money.sh`。流程是新库 → `chains.py sale_close` → 编入通道模拟器并重启 → `money_chains.py`。每轮约 5 分钟。
- **没有经过真实外部的部分：**
  - 微信支付通道本身：直连和电商收付通都用模拟器，没有接入沙箱。
  - 真机调起支付。
  - 订阅消息与内容安全：access_token 拿不到，日志里有告警，不影响资金。
  - 对象存储：通关图鉴和二维码要签 URL，rig 给的是假桶配置。
- **没有跑到的：**
  - 合单开的一轮，按任务只要合单关。
  - 通行证 iOS 虚拟支付。
  - 「卖家 + 第三方份额」的主题门票：rig 里的供给是 PERK，不带钱，所以失败关闭闸只有单测和真库 IT 证明。

## 五、待你决定（括号里是现在的默认）

1. **场次结算两套比例不一致。** 余额入账用当前 `settlement_rule(activity)` 的平台比例，结算明细和退款冲回用报名时冻结的 `fee_platform_rate`。两者不等时，整单退款后主办方会倒欠平台差额。rig 里是 rig 种的 10% 对冻结的 3%，主办方欠 4.83 元。请核对生产 `settlement_rule(biz_type=activity)` 的 `platform_commission_rate` 是否与主题的 `fee_platform_rate` 相同；如果不同，生产上每笔「结算后退款」都会出现这个差额。（默认：不改代码。这是上线前就有的结算路径，要改需要你批准，改成余额也按冻结比例入账。）
2. **自由探索主题上的收费自玩票没有结算出口。** 主题结算只认经典定向，场次结算只认场次票，所以这类钱在开关全关时一直留在平台，不给俱乐部记余额。开关开时，分账单转人工（`NO_LEDGER_COMPONENTS`），39.90 元一直冻结在卖家二级商户，要运营手工处理。生产里能否给自由探索主题设自玩价，需要你确认：rig 里后台入口拒绝了，是夹具直接写的。（默认：不改。如果生产不允许这种组合，可以在发布侧加闸；如果允许，需要定结算规则。）
3. **俱乐部会费退款只建审核单，没有原路退款动作。** 会员申请后状态是 PENDING_REVIEW，钱不动。（默认：保持人工。是否要接自动原路退款，请你定。）
4. **rig 基线 schema 缺 `cms_points_setting.rule_version`。**（默认：rig 里补跑迁移；基线 schema 交给 schema 原 owner 修。）
5. **待你决定 2 的后续：** 合单打开以后，被转人工的单可以在后台「重新计划」，改走「分给平台再按供应商应付付出」。合单仍关时，重新计划的结果还是人工。（默认：如此。）

## 六、门禁

| 门禁 | 结果 |
|---|---|
| `mvn -q -DskipTests install` | 通过 |
| rig 候选 jar `mvn -B -q -DskipTests package` | 通过（`8be3756e`） |
| 资金类单测 | `SubMerchantShareFailClosedTest`、`ProfitSharePlannerTest`、`EcommercePayoutServicesTest`、`CombinedPayoutTest`、`MoneyFlowLedgerAcceptanceTest` 共 64 条通过；支线全量 16 类 140 条通过 |
| 真 MySQL 8 IT | `EcomPayoutMapperMySqlIT` 9、`EcomPayoutWiringMySqlIT` 6、`P0FreeExploreSupplyRecoveryMySqlIT` 4、`P0FreeExploreSupplyGateMySqlIT` 2，全部通过；scratch 库已清理 |
| `python3 -m unittest ci.test_real_storage_it` | 92 条通过 |
| `ci/test_ci_cost_contract`、`ci.test_chain_acceptance`、`scripts/e2e/test_*.py`（7 个） | 通过 |
| 新迁移 | 无 |
| 没有运行 | `ci/xcx-check.sh`、`ds-hardcode-gate-selftest.sh`（按规定）；后台 Vue 只改一个数组项和一条文案，没跑 build |
| 密钥 | 没有。rig 临时密钥只在 `$RIG_HOME/secrets.env`；证据里的手机号、姓名是 rig 加密后的密文 |

## 七、Cross-lane

- **qx-final：**
  - Real Storage IT 批次 `p0-acceptance-ac1` 现在是 17 条。
  - 部署清单加一条：上线不要只开 `seller-routing-enabled` + `profit-sharing-enabled` 去卖有合作份额的主题。现在会转人工，不会出错，但钱会冻结在卖家二级商户，等人工处理。
- **链路验收 owner：** `scripts/e2e` 已换成现售形态。`ci/chain_acceptance.py` 的 finance 段还是按四店期级 FINAL 写的，现售形态没有期级 FINAL，所以没有跑 finance 段。
- **schema owner：** 第五节 4。
- **共享文件：**
  - `scripts/e2e/seed_fixtures.py`：角色、7001 成员、结算费率、结算配置。
  - `ci/real-storage-it-registry.json`：+1 类。
  - `ci/test_real_storage_it.py`：计数 13→17。
  - `ci/backend-test.sh`：+1 类。

STATUS: PARTIAL - 链路夹具已换现售形态；四类订单真实 HTTP 资金链在开关全关、路由加分账开两轮都跑完并逐行记录；合单关时商户间分账提交前失败关闭已实现并有单测和真库 IT；P0-23 真库 IT 已登记；没有接入微信沙箱和真机，场次结算比例不一致与自由探索自玩票无结算出口需要你决定
