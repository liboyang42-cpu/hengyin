# 小程序底层 A/B/C 集成验证（集成者）

分支：`claude/mini-foundation-abc-integration-20261013`（公开仓库，非 master，未建 PR、未合并、未部署）。
起点：交接 `e67e72a`（业务基线 `0f0c32f`）。三包原样合入：A `f997452`（PR #8）、B `aaf6a7b`（PR #7）、C `e65d5f7`（PR #9），合并提交 `8a8bed7` / `9f48a6d` / `b39cd30`。

本报告只记录集成快照的实测证据与剩余断点；**不把任何原 ID 标为整项完成**。三包各自目录下的合同、逐项台账与 UNKNOWN 保持原样，共享 EXECUTION/ITEMS 阶段框未改。

## 1. 合入核对

- 三个 PR 头提交与交接指定 SHA 一致。
- 重复先导提交 patch-id 完全相同：B 通知接口 `1ce1f97/d52c765` = A 的 `e87fbd7/3391b71`；A 奖励接口 `e1c8984/f109fae` = C 的 `6a9f28f/8b18e8e`；迁移依赖 `4db987a` 三包各一份（`173b869`/`8d5882a`/`b254e1c`）。
- 五个共享文件（`ISystemNotificationIntentService`、`IRewardDropService`、`ISmsCouponHistoryService`、`ExploreTicketUsePolicy`、`ci/migration-dependencies.json`）在三包头字节相同；合并无冲突、无手工取舍。合并树中每个包改动文件的 blob 与该包头逐一相同。
- 所有权：B、C 无越界文件；A 改了两个 `READ_ONLY_SHARED`（`OfficialEventServiceImpl.java`、`ClubEventOpsMapper.xml`），A 合同记载为协调者单独指派，三包无他人改动。
- 跨包合同静态核对：A 的 `grantCouponBySource/findOriginalDrop` 正式实现覆盖默认 throw；B 的 `ImServiceImpl` 实现通知意图接口；六条新迁移均已在依赖声明登记；三个新 Quartz 任务 bean 名与注册 SQL 一致（均 status=1 暂停）。

## 2. 集成中发现并修补的实际失败

每项均先取得失败证据，再做最小修补后复跑。

| # | 归属 | 失败（证据） | 修补 |
|---|---|---|---|
| 1 | B×C | 服务端确认登出 → `clearStorageSync` → `reLaunch` 卸载漫游页，把旧账号 `roam_memory_v1:*:tiles/reveals` 写回存储（新增集成用例先红） | 登出前取原账号并 `invalidateRoamCache`，触发漫游页放弃本范围本机记录；用例转绿 |
| 2 | A | CI `testCompile` 失败：`RegistrationFundsMybatisIntegrationTest` 受检异常进 lambda；`ClubWaitlistMaintenanceIsolationTransactionTest` 泛型实参误选 `queryForList` 重载 | 事务内回读改走同一事务连接（JdbcTemplate），显式 `Object[]` 实参；期望值不变 |
| 3 | B | `silent-error-gate`：IM 聊天页实际 5 > 登记 3 | 按门禁设计登记两处只读请求及理由（见第 4 节「需复核」） |
| 4 | A/C | 资金真库 IT 门自测：`RegistrationFunds` 源码 94 ≠ 登记 92，`RoamSessionWrite` 13 ≠ 10 | 按源码实数登记（只增，实跑数要求随之提高） |
| 5 | C | 测试 VPS：`AiQuotaRecoveryMySqlRedisIT` 7/7 通过但 Redis db7 残留 4 个前序用例收据（运行前 0） | 用例的 `forceDue`/`REFUND_DUE` 更新限定本用例 `receipt_key`；复跑 7/7、运行后 DBSIZE=0 |
| 6 | B/C | `lint:ui`：B/C 行号漂移使 17 条基线锚点失配 | 交接基线→集成树逐条映射，前后 3 行逐字一致后等量换 key，digest 按门禁算法重算 |
| 7 | C | 控制器把「未报名」改成「当前报名资格已失效」（原「请先报名并完成支付」分支不可达）；路线新使用把有效报名收窄为第一张，原「任一成功报名可授权受限候选」回到兜底节点 | 无候选行恢复原文案；新增 `CurrentPlayRegistration.requireAll`（同一锁读规则返回全部有效报名），`require` 行为与锁序不变 |
| 8 | C | **真库**：`RoamBadgeDeliveryService` 把 Map 中 DATETIME 强转 `Date`，Connector/J 8 返回 `LocalDateTime` → 徽章补交付必抛 `ClassCastException`；`archiveFinishedRecords` 的 `instanceof Date` 使重复补传恒报「原采集记录不一致」 | 兼容 `Date/LocalDateTime` 按 JVM 默认时区还原；真库 IT 23/25 → 25/25 |
| 9 | A | 资金真库 IT：按章核销对非探索票先按 `owner_id`（自玩票为主题 ID）锁 `cms_activity` 同号无关行 | 先确认活动场次探索票再锁活动 |
| 10 | 集成 | 生成台账 `action-ledger.json` 失配 | 统一重建：4432 控件集合不变；257 条仅源位置；漫游 4 个控件因 C 包恢复/结算重试会重启定位，被生成器改判 external（真机门） |
| 11 | A/C | 45 个后端测试类因新锁读/新依赖未接夹具、Mockito 默认值（Long→0L、Map→空 Map）、嵌套 stub、mock 忽略 member/owner 条件而失败 | 按真实行/真实合同补夹具（控制器委托夹具、`CurrentPlayTestFixtures.installOn/delegateLocks` 等）；见第 4 节断言审计 |

## 3. 实测结果（集成头）

- 小程序 Node（`TZ=Asia/Shanghai`，`tests/unit/**/*.test.js`）：本机与 GitHub CI 均 **8254 tests / 8252 pass / 0 fail / 2 skip**。修补前集成树为 3 fail。
- 后端（GitHub CI `mvn -B clean test`，Java 11）：**10014 tests**，失败从首轮 37 F + 257 E（编译通过后）降到 7 F + 2 E，仅剩第 5 节两类设计冲突。
- 资金真库 IT（GitHub CI `funds-mysql-it.yml`，MySQL 8.0.45 scratch）：**562 tests**，见 `results.json` 最终轮。
- 测试 VPS（自建 `cy-mfabc-20261013-*` 容器：MySQL 8.0.45 / Redis 7.4.11 DB7，回环同网络命名空间，非生产）：
  - A `OfficialV1CapacityMySqlTest` 2/2
  - B `ImFoundationBMybatisIntegrationTest` 4/4（readTo/连续增量、原事务回滚、双 worker 迟到重放、同 key 异内容；用例内独立 JdbcTemplate 回读）
  - C `AiQuotaReservationRedisIT` 15/15、`AiQuotaRecoveryMySqlRedisIT` 7/7（修补后 Redis 运行前后 DBSIZE 0→0）
  - C `RoamSessionWriteMybatisIntegrationTest` 25/25（JVM 与 MySQL 同为 UTC，与 CI 一致）
  - A `StoreVerifyGrowthDeliveryTransactionTest` 3/3（第 2 节 #9 修补后）
  - CI `ci/backend-test.sh` 点名 MyBatis 真库 IT 全表 27 类：GitHub CI 因前一步 `mvn clean test` 失败未执行该步，改在测试 VPS 按同表、同 `CHENGYIN_IM_SEND_MYSQL_IT=1` 分批执行：**96 tests / 94 pass / 0 skip**，仅 `JourneyFullFlowMySqlIT` 2 条失败（第 5 节 #1）；`PredictLateBetRaceMySqlIT` 需接 C 非资格夹具后 2/2。
  - 独立连接回读：包内新 IT 的 scratch 库均在 CREATE 成功后自删；CI 既有 `MybatisItDatabase` 只在首用时重建、不在结束时删除，留下 24 个带本任务后缀 `_mfabcm4/_mfabcm5` 的库（无一不带后缀），随本任务容器整体删除清理；Redis 所有库 0 键。
- 迁移安全门、迁移布局门、SBOM、Admin UI、Fast gates（含 silentError）：CI 通过。

## 4. 需要复核的集成者改动（断言审计）

`git diff b39cd30..HEAD` 中被删除的含 `assert/verify` 的测试行共 4 处：

1. `ExploreRedeemRecordTest`：`verify(...grantOnce(reg()...))` 改为先取局部变量再 verify（`reg()` 内含 stub，嵌在 verify 里触发 Mockito 误用）；被验证调用与取值不变。
2. `RegistrationFundsMybatisIntegrationTest`：`assertEquals(2, number(...))` 期望值不变，改经事务连接回读（原写法无法编译，且独立连接看不到未提交数据）。
3. `action-ledger.test.js` local tap 合计 **1781 → 1779**；
4. 同文件 event-contract 合计 **2321 → 2319**。

3、4 是按惯例「重建实测」的台账分母：减少的 4 个 ID 已逐个列在提交说明（漫游 `recoverRoam`×2、`retryScreenFog`、`retrySettle` 改判 external 真机门），无新增。若不同意改判，应回到 C 包恢复链而非台账。

另：`ci/silent-error-allowlist.txt` 为 B 的 IM 聊天页 3→5（`send-receipt` 原请求只读回读、轮询内路线卡隐私重核，失败均保持页内原状态）；`ci/funds_it_gate.py` 及其自测按源码实数 92→94、10→13。均未改门禁逻辑或工作流。

## 5. 剩余断点（不得宣称整项完成）

1. **C 设计与旧断言冲突（未改断言，需 C/用户决定）**
   - `ApiPlayProgressCompletionTruthTest` 7 条：6 条 `verifyNoInteractions/NoMoreInteractions(registrationMapper)`，C 的资格锁读每次都经同一 mapper；1 条期望扫码中途取消抛 `ServiceException`，现由更早的资格闸返回错误回包。写入均未发生，但断言形态不再成立。
   - `JourneyFullFlowMySqlIT` 2 条（真库）：C 把 `requireCheckSession`（roll/settle）改为新使用锁读，settle 开头即 `FOR UPDATE` 锁 activity/registration。用例让后到事务停在「读到空动作」握手点等先到者，先到者被同一报名行锁挡住，5 秒握手超时。生产语义为串行化后重放原动作（更严格、不死锁），但用例要求的交错已不可能出现，摘唯一键负控也无法再变红。
   - `CouponNotIssuableSkipContractTest` 2 条：断言漫游掉落直接调用 `grantCouponById`；C 已改为经 `RoamRewardDeliveryService` 原来源交付（停发跳过由 `RoamRewardDeliveryTest.disabledNewGrantSkips…` 覆盖）。
2. **Gitleaks 历史扫描**：B 提交 `d54cf2d` 的红日志含 6 个随机测试 `clientMessageId`（非密钥，后续提交已脱敏，HEAD 树无命中），但 CI 按 `origin/master..HEAD` 扫历史必红；需 B 改写自身历史或合入时 squash，集成者未强推、未加白名单。
3. **`lint:ui`**：唯一剩余项 `im/list/index.js:352` 动态 setData 未登记，交接基线即如此（基线 CI 9/10 的失败门）；未擅自扩棘轮。
4. **免费自玩真库证明缺失**：`FreeSelfPlayCreateRealDbIT` 的 schema 夹具早于 0930/1004 等迁移（交接基线 mapper 已读 `first_successful_scan_at`、`theme_runtime_config_json`），与 A 改动无关但未能运行；免费资格仅有单测证据（`CurrentPlayRegistrationTest` 11/11 含 payment=0、`ApiPlayCurrentUseBoundaryTest` 4/4、`ApiPlayRunSessionControllerTest` 4/4）。
5. **待用户决定的范围问题**：C 在已有对局的 `act/completeGate` 也加了当前资格锁读，自玩通行证在局中途过期会阻止继续本局；探索票按已定规则合理，普通自玩是否属于「新使用」未有既定规则。集成未回退 C 设计。
6. **观察**：`ApiRoamController` 第 177 行对 Map 中 `foundAt` 的 `(Date)` 强转为交接基线既有代码，与第 2 节 #8 同类风险，未改。`RoamRewardDeliveryService` 对无意图旧掉落且券已停发时记 `SKIPPED_DISABLED`（不发券，仅标签与 UNKNOWN 不同）。
7. **未验**：真实双方身份、微信支付/渠道回调与 UNKNOWN、AI provider、微信开发者工具与真机、生产 DDL、Quartz 启用、上传微信。测试 VPS 隧道期间多次中断，均重试后取得结果；VPS 内存紧张（约 600MB 可用），本人容器加硬上限，未影响他人容器。
