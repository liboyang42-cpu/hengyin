# f-money 结果：资金验收、P0 角色卡、收益明细幂等键、快提开关

分支 `claude/f-money-20261111`，从 `origin/launch/questo-20261110`（`2256f79e`，已含 l-payout / l-payout2 / l-payout3 / l-pass）切出。依据：AUDIT-GAPS.md 的 A 段与缺口第 2 组、LAUNCH.md 的 Money 规则、BRIEF.md、四条资金线的 RESULT.md、rig 分支 RIG.md。

## 一、先看结论

1. **开关全关 = 上线前行为。** 同一组 24 个支付 / 退款 / 结算测试类（213 条，含 l-payout2 的三条金标准：报名下单请求、原路退款请求、T+14 入余额）在 master `000a48f6b` 和本分支上逐类条数相同、全部通过（`evidence/off-vs-master-tests.txt`）。rig 上 launch jar 在默认开关下跑完真实 HTTP 流量后，16 张新资金表全部 0 行（通行证套餐表 2 行是迁移自带、未上架）。
2. **「没有二清」目前不成立，分三种情况：**
   - **开关全关（就是今天线上的做法）：** 主题门票、活动票、俱乐部会费的钱全部进平台商户号，T+14 再记到商家 / 俱乐部 / 发起人的会员余额，由他们提现。这就是「平台代收再分发」。这是上线前就有的行为，本次上线没有改变它。
   - **目标开关（电商收付通全开 + 合单开）：** 卖家是唯一第三方收款方的主题门票，钱直接进卖家二级商户号，只把平台佣金分给平台，**没有二清**。但只要账本里还有别的商家或俱乐部的份额（核销费、带队费），这部分钱都会先进平台，再由平台按「供应商应付」付出去。合单多出来的预留（SURPLUS）同样如此，而这笔钱本来就是卖家的。这些都是平台代收再分发，是否算二清要由你和法务认定。
   - **只开 l-payout2、不开合单：** 第三方份额会从卖家二级商户直接分给另一个二级商户。这不是二清，但你在 11-11 说过微信不支持这种分账。
3. **建议的上线开关：** 电商收付通只对「卖家是唯一第三方收款方」的主题打开。合单（`combined-enabled`）先保持关闭，等你和法务对供应商应付这条路定性以后再开。详见「待你决定 1、2」。代码默认已经全关，不需要改代码。
4. **真机 / 微信沙箱验收没做。** 本线没有真机，也连不上微信支付沙箱，官方文档域名也被网络策略拦了。rig 的真实 HTTP 链路在开售这一步就断了：四店固定补偿产品已停止新售票，master 上也一样。所以真实下单 → 回调 → 退款的 HTTP 端到端没跑通。资金流向是用「微信支付通道模拟器 + 真实分账 / 合单结算服务」跑出来的，下单和回调靠已有的适配器测试覆盖。见第四节。

## 二、条目

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机未验 |
|---|---|---|---|---|---|
| FM-1 资金端到端验收（开关全关 vs master） | fixed（测试级）／ not done（rig HTTP） | 不改生产代码。把 l-payout2 的金标准特征测试原样拷进 master 工作树，和 21 个原有支付 / 退款 / 结算测试类、`SelfPlayPassIdempotencyTest`、`MoneyPathContractTest` 一起，分别在 master 和本分支上跑 | `evidence/off-vs-master-tests.txt` | master 与 launch 都是 24 类 213 条，全部通过，逐类条数 diff 为空。rig（launch jar、真 MySQL 8 / Redis 7.4、默认开关）：新资金表全部 0 行 | 真实下单 / 回调 / 退款没有在真机或沙箱上跑 |
| FM-2 资金端到端验收（目标开关）与二清判定 | fixed（测试）／ needs owner（二清定性） | 新增 `MoneyFlowLedgerAcceptanceTest`。微信支付通道模拟器按商户号记余额，记录每一笔分账、完结、回退、退款，驱动真实的 `EcommerceProfitShareService` 和 `EcommerceCombinedSettlementService`。覆盖：开关全关；单商户；单商户含第三方份额（合单关 / 开）；合单（预留够付；补差后整单退款）。每个流程都核对守恒，并把平台付给商家或会员的移动标成 `REDISTRIBUTION` | `chengyinhub-admin/src/test/java/com/chengyinhub/business/payout/MoneyFlowLedgerAcceptanceTest.java`、`evidence/money-ledger.md` | 6 条全部通过。另外在 rig 的 MySQL 上跑了资金线已有测试：`EcomPayoutMapperMySqlIT` 9、`EcomPayoutWiringMySqlIT` 6、`EcomCombineMapperMySqlIT` 9、`PlatformPassMySqlIT` 7；单测：适配器 13、合单 15、分账服务 20、合单策略 19、结算接线 4、两组特征测试 3 + 5，全部通过（`evidence/p0-and-money-tests.txt`） | 全部微信接口（服务商 JSAPI、合单、分账、回退、电商退款、虚拟支付）都没有连沙箱 |
| FM-3 25 张 P0 真实角色卡 | 部分 | 不改代码。在 rig 的 MySQL / Redis 上跑每张卡「已自动证明」那一列的测试类 | `evidence/p0-and-money-tests.txt` | 见第五节：P0-01 到 P0-23 的后端半边全部通过。P0-24、P0-25 没有对上号 | 每张卡的人工 / 真机半边都没跑（扫码摄像头、两台真机、订阅消息、录屏） |
| FM-4 `creator_income_detail` 幂等唯一键 | fixed | 新迁移：加可空列 `idempotency_key` 和唯一键 `uk_creator_income_idempotency`，可以重复执行，存量行不回填。已登记依赖（排在建表迁移 `p3_phase_b_player_world_creator` 之后），真库 IT 登记了新批次 `launch-f-money`。新测试断言**现在没有任何写入路径**：所有模块的 Java 和 MyBatis XML 里，对这张表的 INSERT / REPLACE / UPDATE / DELETE 都是 0，唯一的 mapper 只有 select | `chengyinhub-admin/sql/migration_20261111_creator_income_detail_idempotency.sql`、`ci/migration-dependencies.json`、`CreatorIncomeDetailNoWritePathTest.java`、`it/CreatorIncomeDetailIdempotencyMySqlIT.java`、`ci/real-storage-it-registry.json`、`ci/test_real_storage_it.py`、`.github/workflows/real-storage-it.yml` | 单测 4 条、原有守卫 2 条通过。真 MySQL 8 上 IT 2 条通过：迁移原样执行两次；同一键第二次插入被唯一键拒绝；`INSERT IGNORE` 不生效；两行存量 NULL 键不冲突。`migration-layout-check`、`migration-safety-check`、`python3 -m unittest ci.test_real_storage_it`（92 条）通过 | 生产库没有执行（由你走 chengyin-db-migrate） |
| FM-5 `withdrawal.fast-withdraw.enabled` 默认关闭 | fixed | 新测试从三层锁住默认关闭：① 钱包和俱乐部结算两个控制器读开关的 `@Value` 默认值必须是 `false`，没有外部配置时解析为 false；② 随 jar 打包的配置里不允许出现这个开关；③ 默认值下钱包提现在进 service 之前就被拒绝 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/FastWithdrawDefaultOffTest.java` | 5 条通过。原 `ApiWalletControllerSwitchTest` 通过 | — |
| FM-6 新依赖报备 | needs owner | 不改代码。`com.apple.itunes.storekit:app-store-server-library:5.2.0`（`chengyinhub-system/pom.xml`，提交 `5d7cdf82f`）实际服务的是**玩法模板工坊的苹果内购验付**（`appleiap` 包，开关 `apple-iap.enabled`），不是通行证。通行证 iOS 走的是小程序虚拟支付（xpay），不用这个库。已在 PR 说明里报备 | — | `grep` 出依赖和使用方 | — |

本线发现的缺陷：没有发现需要改生产代码的缺陷。二清问题属于设计定性，见待你决定 1、2。rig 链路断在开售这一步，是夹具过期（四店固定补偿产品停售），master 上也一样，不是本次上线引入的。

## 三、分流程资金表

每一行一个「订单类型 × 开关状态」。PAYER 是玩家；SP 是平台在电商收付通里的平台商户号；平台商户号（直连）是现在线上收款用的那个。

| 订单类型 | 开关 | 下单收到哪里 | 回调写什么 | 结算 / 分账 | 退款 | 对账 | 平台代收再分发？ | 证据 |
|---|---|---|---|---|---|---|---|---|
| 单主题门票（官方主题、平台商品、模板、保证金） | 全关 | 平台商户号（直连） | 不可变 PAYMENT 事实 → 报名状态机 | 平台自己是卖家，没有第三方份额 | 平台商户号原路退回 PAYER | 平台收入 = 实付 − 退款 | 否（平台本来就是卖家） | 金标准 `GOLDEN_PREPAY` / `GOLDEN_REFUND`，master 与 launch 一致 |
| 协作主题门票（有商家、俱乐部、发起人份额） | 全关 | 平台商户号（直连） | 同上 | T+14 把各方份额记进会员余额（金标准：会员 300 余额 +30.00），会员再提现 | 平台原路退款；结算后退款按原有规则冲回会员钱包或记欠款 | 账本 `coop_settlement_original_component` | **是（上线前就有）**：钱先进平台，再以余额和提现的方式付给商家 / 俱乐部 | 金标准 `GOLDEN_SETTLEMENT`；`CoopSettlementTest` 等，master 与 launch 一致 |
| 活动场次票、俱乐部会费 | 全关 / 目标 | 平台商户号（直连） | 同上 | 记会员余额 | 平台原路退款 | 同上 | **是（上线前就有，本次没改）**：l-payout2 说明这两类没有分账接点 | l-payout2 RESULT「待你决定 3」 |
| 主题门票，卖家是唯一第三方收款方（单商户电商收付通） | 目标 | PAYER → 卖家二级商户号（冻结，`profit_sharing=true`） | 电商回调查原单逐字段核对后写 PAYMENT 事实 | T+14 到期：卖家 → SP 分平台佣金（例：100 元单分 15 元），完结后解冻给卖家；T+14 不再给卖家记余额 | 先按比例把佣金从 SP 原路回退给卖家（例：退 40 元先回退 6 元），回退全部成功后，再从卖家二级商户退给 PAYER | 卖家 51.00 = 85% × 60；平台 9.00 = 15% × 60；PAYER 净付 60.00；总和守恒 | **否** | `MoneyFlowLedgerAcceptanceTest.singleMerchant…`；适配器测试 13 条 |
| 主题门票，卖家 + 其他商家份额，合单关（只开 l-payout2） | 只开 l-payout2 | 同上 | 同上 | 卖家 → SP 15 元；卖家 → 另一个二级商户 10 元（二级商户之间直接分账） | 按比例向每个接收方回退 | 守恒 | 否。但你说过微信不支持二级商户之间直接分账 | `…CombinedOffSplitsSubMerchantToSubMerchant` |
| 同上，合单开（转付 RELAY） | 目标 | 同上 | 同上 | 卖家 → SP 25 元（15 元佣金 + 10 元核销费）；运营「标记已付」后，平台在微信通道外付核销商家 10 元（供应商应付） | 退款时还没付的应付会被挂起（HOLD_REFUND） | 守恒 | **是**：核销商家的 10 元先进平台，再由平台付出 | `…CombinedOnRelaysThroughThePlatform` |
| 多商户合单（发起方 + 核销商家 / 带队俱乐部），预留够付 | 目标 | PAYER → 卖家二级商户 59 元 + 平台自营二级商户 41 元（佣金 15 + 预留 26） | 合单回调查合单逐笔子单核对后，写一条 PAYMENT 事实 | 卖家子单完结；平台自营子单留 15 元佣金；核销商家 10 元、多出的预留 16 元（属于卖家）都由平台按供应商应付在通道外付出 | 按两笔子单金额比例拆退款；两笔都成功才写 REFUND 事实 | 守恒 | **是**：26 元（含卖家自己的 16 元）先进平台，再由平台付出 | `…CombinedOrderReserveIsHeldByThePlatform…`；合单测试 15 条 |
| 多商户合单，下单后才加入合作（补差） | 目标 | 同上 | 同上 | 卖家 → SP 补差 4 元；核销商家 30 元走供应商应付 | 结算后整单退款：先把 4 元补差从 SP 回退给卖家，两笔子单再各自原路退款 | 退完各账户都回到 0 | 应付付出之前是否，付出以后是 | `…ShortfallIsSharedToPlatformThenReturnedBeforeAFullRefund` |
| 通行证 JSAPI（安卓 / 开发者工具） | 目标（`platform-pass.*`） | 平台商户号（直连），单号段 `PPO` | 回调路由只认 PPO，在消费已验证支付事实的同一事务里开通，金额逐项核对 | 平台是卖家；持有者领官方主题按 ¥0 单处理，商家不分成 | 只有没领过主题的才能整单退；原路退款，退款号段 `RPP` | 平台收入 = 实付 − 退款 | 否 | `PlatformPassServiceTest` 19、`PlatformPassRouteTest` 3、`PlatformPassMySqlIT` 7（rig MySQL） |
| 通行证 iOS 虚拟支付 | 目标（`platform-pass.xpay.*`） | 小程序虚拟支付，结算给平台主体 | 只认服务端 `/xpay/query_order` 回查的已付结果；消息推送只当作「去回查」的信号 | 同上 | `/xpay/refund_order`；渠道拒绝时恢复为已付，结果未知时保持「退款中」 | 同上 | 否 | `XpaySignerTest` 5、`PlatformPassServiceTest` |
| 新通道（开关全关时） | 全关 | — | 三个电商回调端点都回 FAIL | 不读新表，不调电商网关 | — | 0 笔移动 | — | `…allSwitchesOffMovesNothing…`、`PayoutCombineSwitchOffCharacterizationTest` 5 条 |

逐笔移动明细（从、到、金额、通道、判定）见 `evidence/money-ledger.md`，由测试运行时生成。

## 四、rig 跑到了哪里（如实）

- **环境：** rig 分支 `claude/cloud-walk-20261101-rig`。打了 `staging/rig/rig-up-sha-param.diff`、`l-pass/rig/rig-extra-args.diff`（里面已经带了 OSS 接缝）、`cloud-walk-20261101-club` 的 `harness-patches.diff`。候选是 launch 头 `2256f79e` 的 jar，真 MySQL 8.0 和 Redis 7.4 都在 Docker 里，默认开关。
- **链路：** 用 rig 的 `chains.py finance` 跑真实 HTTP 链路，`platform`、`merchant_club` 通过；`sale_close` 在开场次这一步被拒，提示「自由探索只开放内容所属俱乐部的场次，不能跨俱乐部售票」。即使绕过这一步，后面售票还会遇到「四店固定补偿产品已停止新售票」（`ExploreSaleSnapshotGate`）。这道闸 master 上也有，说明是链路夹具过期了，不是本次上线引入的。所以真实下单 → 回调 → 结算 → 退款的 HTTP 链路没跑通。
- **俱乐部会费（CLM）：** 我试过另一条真实 HTTP 链路，脚本 `rig/club_dues_flow.py` 和 `rig/enable-club-membership.sh`，只写 rig 库。打开了既有功能闸，也补了主理人成员行，主理人接口仍然返回「俱乐部身份无效或权限不足」。夹具缺俱乐部角色授权，没跑到付款这一步。脚本留在仓库里，方便下一条线补夹具后接着跑。
- **所以：** 「master 与 launch 的库行对比」改成了在同一组测试上做逐类对比（第一节第 1 条），并在 rig 库里核对了新表在默认开关下为 0 行。目标开关下的资金流向用通道模拟器跑。下单和回调这一段（意图冻结、验签、查单核对、写不可变事实）由适配器 13 条和合单 15 条测试覆盖，没有经过真实 HTTP。

## 五、25 张 P0 真实角色卡（实施文档一 §9.1，见 `docs/delivery/release-closeout-20261022/manual-acceptance-runbook.md` §2.2）

后端半边都在 rig 的 MySQL 8 上用本分支代码跑。环境变量和 `ci/real_storage_it.py` 的 `class_env` 一致，scratch 库只给本次运行用。资金类 IT 需要的 DDL 审批开关，按 `real-storage-it.yml` 对 scratch 库的同一口径打开。人工 / 真机半边一张也没跑，原因是本线没有真机、两台手机、摄像头和订阅消息通道；rig 里也没有可售的收费主题夹具（见第四节）。

| 卡 | 后端半边（本次在本分支上跑） | 结果 | 人工 / 真机半边 |
|---|---|---|---|
| P0-01 合法码核销 | `P0RedemptionChainMySqlIT` 5、`GameSessionScanLegalMySqlIT` 2、`ExploreAllianceRedeemRealDbIT` 4 | 通过 | 未跑：真机摄像头扫码、动态码节奏 |
| P0-02 错码 / 伪造 / 过期 | 同上 + `ApiVerifyGroupCodeTest` 23 | 通过 | 未跑：过期码文案截图 |
| P0-03 已取消订单 / 场次不可核销 | `P0RedemptionChainMySqlIT`（整单、团码已取消） | 通过 | 未跑 |
| P0-04 撤销核销 | `P0RedemptionChainMySqlIT`、`ExploreScanReplayRealDbIT` 4 | 通过 | 未跑：后台二次确认、玩家端刷新 |
| P0-05 两名店员同时核销 | `P0RedemptionChainMySqlIT`（20 轮屏障并发） | 通过 | 未跑：两台真机 |
| P0-06 B 读改 A 的订单 / 票 | `P0CrossAccountMySqlIT` 3 | 通过 | 未跑：录屏「不闪现」 |
| P0-07 B 读 A 的核销记录 | `P0CrossAccountMySqlIT` | 通过 | 未跑 |
| P0-08 B 操作 A 的草稿 | `P0CrossAccountMySqlIT` | 通过 | 未跑 |
| P0-09 B 操作 A 的俱乐部数据 | `ClubRoleHttpMySqlIT` 3 | 通过 | 未跑 |
| P0-10 活动删除 / 发布状态 IDOR | `ActivityPublishRoleGateTest` 28（只有单测） | 通过 | 未跑 |
| P0-11 城市跑恢复竞态 | `P0CityRunChainMySqlIT` 2 | 通过 | 未跑：两台终端 |
| P0-12 NPC 节点 | `NodeNpcMapperMyBatisIT` 9、`NodeNpcStationScopeAcceptanceTest` 4 | 通过 | 未跑：语音 / 形象加载 |
| P0-13 冻结后无状态变化 | `P0CityRunChainMySqlIT`（`finishedSessionIsFrozenAgainstPlayerAndStaffWrites`） | 通过 | 未跑 |
| P0-14 锁价 / 需补供给 | `P0FreeExploreSupplyGateMySqlIT` 2 | 通过 | 未跑 |
| P0-15 结账（到 prepay） | `RegistrationFundsMybatisIntegrationTest` 114 | 通过 | 未跑：真实支付与回调（runbook EXT-03） |
| P0-16 退出 / 0 元 | `ActivityCancelFreeOrderMybatisIT` 8 | 通过 | 未跑：玩家侧完整走一遍 |
| P0-17 取消通知 | `RefundNotificationMybatisIntegrationTest` 9、`ClubEventTransferCancellationMySqlIT` 15 | 通过 | 未跑：订阅消息到达 |
| P0-18 部分章节售后 | `PartialChapterAftercareTest` 3、`RefundRetryAndRemainderMySqlIT` 10 | 通过 | 未跑：售后建单 |
| P0-19 替换锁定 | `LockedSupplyReplacementAcceptanceTest` 4、`LockedSupplyReplacementPolicyAcceptanceTest` 8 | 通过 | 未跑 |
| P0-20 注销只清本人 | `P0DeregistrationMySqlIT` 1、`AccountDeregistrationRaceMySqlIT` 1 | 通过 | 未跑：冷静期展示与撤回、旧会话被踢 |
| P0-21 财务记录保留 | `P0DeregistrationMySqlIT`（同一用例） | 通过 | 未跑 |
| P0-22 重新注册隔离 | `P0DeregistrationMySqlIT`（同一用例） | 通过 | 未跑 |
| P0-23 需补供给恢复 | 只有 Mockito（runbook 原注），本次没有新增 | 未覆盖真库 | 未跑 |
| P0-24 | 私有 §9.1 里的卡义没有对上号 | 未跑 | 未跑 |
| P0-25 | 同上 | 未跑 | 未跑 |

注：第一次跑时有 4 个类没按登记的环境变量运行，结果是 3 个失败、1 个跳过，原因是没打开 DDL 审批开关和 `CHENGYIN_MARKETING_DB_IT_APPROVED`。按 CI 口径打开后重跑，全部通过。上表是重跑的结果。

## 六、门禁

| 门禁 | 结果 |
|---|---|
| `mvn -q -DskipTests install` | 通过 |
| 本线新增测试 | `CreatorIncomeDetailNoWritePathTest` 4、`CreatorIncomeDetailIdempotencyMySqlIT` 2（真 MySQL 8）、`FastWithdrawDefaultOffTest` 5、`MoneyFlowLedgerAcceptanceTest` 6，全部通过 |
| 开关全关对照 | master 与 launch 都是 24 类 213 条，逐类一致 |
| 资金线与 P0 卡测试（rig MySQL / Redis） | 见 `evidence/p0-and-money-tests.txt`，0 失败、0 错误、0 跳过 |
| `ci/migration-layout-check.sh`、`ci/migration-safety-check.sh` | 通过 |
| `python3 -m unittest ci.test_real_storage_it` | 92 条通过 |
| 没有运行 | `ci/xcx-check.sh`、`ds-hardcode-gate-selftest.sh`（按规定）。小程序和后台 Vue 本线都没改，没有截图 |
| 密钥 | diff 已检查，没有密钥。rig 的临时密钥只在 `$RIG_HOME/secrets.env`，不在仓库里 |

## 七、待你决定（括号里是现在的默认）

1. **合单与转付算不算二清？** 现在的设计里，核销费、带队费、合单多出来的预留（属于卖家）都是先进平台（平台自营二级商户或电商平台商户号），再由平台按供应商结算付出去。只有平台确实是在采购对方的服务（签供应商合同、对方开票）时，这才站得住。（默认：代码保持 `combined-enabled=false`。建议上线时不要开，等法务定性以后再开。）
2. **只开 l-payout2、不开合单时，第三方份额会在二级商户之间直接分账**，而你说过微信不支持。（默认：不改代码。上线时请不要只开 `seller-routing-enabled` + `profit-sharing-enabled` 去卖有合作单的主题。如果你确认微信确实拒绝这种分账，下一条线可以把这种情况改成在提交前就转人工，即失败关闭。）
3. **开关全关时，协作主题、活动票、会费仍然是平台代收、T+14 记余额、再由对方提现**，这是上线前就有的做法。（默认：保持不变。要彻底去掉二清，需要把活动票和会费也接到电商收付通，并给活动结算加分账接点。）
4. **`app-store-server-library` 新依赖**服务的是玩法模板工坊的苹果内购验付，开关 `apple-iap.enabled`，不是通行证。（默认：按 AUDIT 记录，在 PR 里报备，请你确认。）
5. **收益明细幂等键的格式**：将来接写入时，建议用 `<来源类型>:<来源单号>:<创作者 id>`，例如 `TOPIC_SETTLEMENT:42:9`。（默认：迁移只建列和唯一键，不规定格式；写入路径不做。）
6. **真机 / 沙箱资金验收**：需要你开通电商收付通沙箱或测试二级商户、虚拟支付沙箱 AppKey，再用一张小额主题门票真机走完付款 → 结算 → 分账 → 退款。开关全关和目标开关各走一遍。（默认：不做，本线没有设备也没有沙箱。）
7. **rig 链路夹具**：`scripts/e2e/chain_*.py` 还是按已经停售的四店固定补偿产品写的，链路验收在开售这一步就断。（默认：本线不改链路，交给链路原 owner 换成现在在售的产品形态。）

## 八、Cross-lane

- **qx-final：**
  - 部署清单加迁移 `migration_20261111_creator_income_detail_idempotency.sql`，无顺序要求，只依赖建表迁移。
  - Real Storage IT 加批次 `launch-f-money`。
  - 第七节的开关建议写进部署清单。
- **链路验收 owner：** 见待你决定 7。
- **共享文件：** `ci/migration-dependencies.json` +1 行；`ci/real-storage-it-registry.json` +1 批次 +1 类；`ci/test_real_storage_it.py` 批次计数 +1；`.github/workflows/real-storage-it.yml` +1 个批次选项。

STATUS: PARTIAL - 收益明细幂等键迁移与无写入测试、快提默认关闭测试、开关全关与 master 逐类一致、目标开关资金流向与二清判定、P0-01 至 P0-22 后端半边已完成；rig 真实 HTTP 资金链路被过期夹具挡在开售一步，没跑通；所有卡的真机半边、P0-23 真库、P0-24/25 卡义以及微信沙箱联调都没做；合单与转付的二清定性需要你和法务决定
