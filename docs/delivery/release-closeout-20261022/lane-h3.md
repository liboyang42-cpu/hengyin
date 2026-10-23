# Lane H3 — 产品代码已在 master 的旧 PR：剩余差异与补测（2026-10-22）

> 城瘾小程序实施文档一 §6.1。范围：#314、#31、#72、#294。只补回归测试，不改生产代码。
> 对照基准：live `origin/master` = `063a6c3cd7c1f131618148c385174037f1d2786e`（与任务给定参考一致）。
> 分支：`feature/lane-h3-oldpr-tests-20261022`。本 lane 没有关闭、评论或标记任何旧 PR。

## 方法

- 每个 PR 都按它**真实的 base**（GitHub PR 元数据里的 base sha）取自身改动集：`git diff --name-status <base> pr/<n>`。
  不用 `origin/master...pr/<n>`。#31/#72/#294 是从 `8b7eb814`（Java 8/Boot 2.5）分叉的 lineage，三点 diff 会把
  上游 lineage 的几百个文件算进来。
- 对改动集里的每个文件比较 `pr/<n>` 与 `origin/master` 的 blob。不一致的文件看 `git diff pr/<n> origin/master`，
  并逐个比对 `@Test` 方法名，区分两种情况：PR 独有的内容，或者 master 更新/更严。
- 判断"已有覆盖"时只认 master 上实际存在的测试类和方法。新增测试在本地跑过（命令和结果见下文），
  还要在本 PR 的 GitHub-hosted CI 上跑。

| PR | 真实 base | head | 改动文件 | 与 master blob 一致 | 剩余差异 |
|---|---|---|---|---|---|
| #314 | `b90b44b2`（#69 头） | `d74c53d1` | 8（全为新增测试） | 0 | 8 个测试文件 master 上没有 → **本 PR 移植** |
| #31 | `1e403b13` | `362f8a6b` | 35 | 34 | 1 个测试多 2 行，master 更新 |
| #72 | `9f96f7a8` | `429aff0c` | 10 | 6 | 1 个 IT 是 master 的旧版子集；3 个协作过程文档 |
| #294 | `429aff0c`（叠在 #72 上） | `604b8c66` | 10 | 4 | 6 个文件全部是 master 更新；缺"入口落点"契约测试 → **本 PR 补** |

## #314 test: PR #69 小程序修复第三轮定向验收（V3）

**剩余差异**：#314 只有一个提交 `d74c53d1`，新增 8 个文件，master 上都没有，也没有同义改名的测试
（按 `Pr69V3` / `pr69-v3` / `CL-xx` 搜过）。

**本 PR 处理**：从 `d74c53d1` 原样带入 8 个文件，其中 7 个一字未改，CL-24 一个文件按 master 现有文案对齐。

| 文件 | 移植方式 | 本地结果（master 063a6c3c + 本分支） |
|---|---|---|
| `chengyinhub-admin/.../service/ai/Pr69V3PhotoCheckCrossAccountTest.java`（CL-41） | 原样 | 2/2 pass |
| `chengyinhub-admin/.../web/controller/api/Pr69V3UploadRejectRetryTest.java`（CL-39/40） | 原样 | 2/2 pass |
| `chengyinhub-xcx/tests/unit/pr69-v3-cl03-template-reject-reason.test.js` | 原样 | 2/2 pass |
| `chengyinhub-xcx/tests/unit/pr69-v3-cl11-nickname-propagation.test.js` | 原样 | 2/2 pass |
| `chengyinhub-xcx/tests/unit/pr69-v3-cl18-badge-wall-guest-copy.test.js` | 原样 | 2/2 pass |
| `chengyinhub-xcx/tests/unit/pr69-v3-cl24-home-privacy-gate-text.test.js` | **对齐 master 文案** | 原样 1/2 → 对齐后 2/2 |
| `chengyinhub-xcx/tests/unit/pr69-v3-cl25-consent-login-failure-resubmit.test.js` | 原样 | 4/4 pass |
| `chengyinhub-xcx/tests/unit/pr69-v3-cl26-home-location-revoke-inflight.test.js` | 原样 | 2/2 pass |

**CL-24 为什么改**：#293（`d787467d`、`e724c869`）把首屏隐私弹窗的用途说明压成了一句
`pg__lead`：「我们只在你使用相关功能时处理必要信息。」隐私页的设置态改成了分组列表，只有待决策态还保留同一句
`privacy-lead`。#314 原断言写的是旧句子「为提供城市定位和漫游服务…」，以及 `pg__body` ×1 / `privacy-body` ×2。
改动后的断言意图不变，仍要求：

- 弹窗只渲染一段用途正文，且只有一个句末标点；
- 长篇告知（DeepSeek/CosyVoice/锁屏/附近用户）不回到弹窗；
- 弹窗这句与隐私页待决策态完全一致；
- 弹窗里有 `openPrivacyContract` 入口，`aria-label` 为「查看用户隐私保护指引」。

第一个用例（真实 app.js `onNeedPrivacyAuthorization` → 首页 `showPrivacyGate` → `cy-privacy-gate`）没有改。
产品代码没有改动。

**命令**（Node 22.22.0，JDK 21）：
- `cd chengyinhub-xcx && node --test tests/unit/pr69-v3-*.test.js` → tests 14, pass 14, fail 0
- `LC_ALL=C.UTF-8 mvn -B -q -pl chengyinhub-admin -am -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest='Pr69V3*' test` → 2 个类 4/4 pass
- `node ci/xcx-unit-manifest.js` → 1423 files（按 glob 自动发现，不需要登记）
- `python3 ci/secret-file-gate.py` → PASS

**仍需真机**（与 #314 原结论一致，本 lane 不处理）：CL-24 的微信原生隐私弹窗、CL-39 真实内容安全 provider。

**建议处置**：本 PR 合入后，把 #314 作为已被取代关闭（close as superseded after this PR merges）。

## #31 [E03] 结局冻结会话与旧快照兼容修复

**剩余差异**：35 个文件里 34 个与 master blob 一致，包括全部 5 个产品文件和其余测试/文档。只有一个文件不同：

```
chengyinhub-admin/src/test/java/com/chengyinhub/business/service/support/SeventhPierFixtureTest.java
PR e12f3373 → master 068fd576
@@ -337,6 +337,8 @@
         when(sessionMapper.insertAction(any())).thenReturn(1);
         when(sessionMapper.updateStateCas(any())).thenReturn(1);
+        // CORE OWN-C06-25：动作回执总以最终结算值回写原动作。
+        when(sessionMapper.updateActionResponse(any(), any())).thenReturn(1);
```

这两行是 master 有、PR 没有的。master 的运行时在 `updateActionResponse(...) != 1` 时会抛异常，所以如果改成 PR 那一侧，
这个夹具会失败。

**本 PR 处理**：无需移植。这里没有 PR 独有的测试方法。

**E03 / D-04**：结局兼容行为（四参 `freezeEnding`、损坏终态拒绝伪成功等）的产品代码已经与 master 一致。
决策 D-04 仍未定，本 lane **不改任何结局行为**，也不为它新增断言。

**建议处置**：已被取代，可以直接关闭，不依赖本 PR。D-04 单独跟踪，与是否关闭 #31 无关。

## #72 第三Cloud统计日期与玩家成长并发修复

**剩余差异**：

- `AnalyticsController`、`PlayerGrowthMapper.java/.xml`、`PlayerProgressionServiceImpl`、`AnalyticsControllerTest`、
  `PlayerProgressionServiceImplTest`：6 个文件与 master blob 一致。
- `PlayerGrowthConcurrencyMySqlIT.java`：PR 版（11 个 `@Test`）的方法集合是 master 版（12 个）的真子集。
  master 多了负控 `sameTopicDuplicateWithoutRetryBoundaryIsDeadlockVictim`。PR 独有的 11 行都被 master 的更严版本替换了：
  - `delete from` 换成 `truncate`；
  - 同主题重复通关用例改走重试边界，断言 `markerInserts==3`，并且仍断言 `10.75` 和单个 `TOPIC_CLEAR`。
- `docs/verification/mini-forward-cloud-20261015/{CLAIMS.json,DELIVERY.md,QUEUE.json}`：协作排队/认领记录，没有可执行证据。
  它们提出的"现有入口覆盖缺口"已经由 `ci/real-storage-it-registry.json` 的 `port-f3-growth`（12）登记解决。

**本 PR 处理**：无需移植。没有 PR 独有的测试方法，也没有 PR 独有的断言。

**建议处置**：已被取代，可以直接关闭，不依赖本 PR。如果 owner 想留审计轨迹，旧分支本身就是引用。

## #294 同主题并发通关死锁在最外层事务边界整笔重试一次

**剩余差异**（PR 自身改动是 `429aff0c..604b8c66`）：

| 文件 | 状态 |
|---|---|
| `DeadlockRetry.java`、`DeadlockRetryInterceptor.java`、`DeadlockRetryConfig.java`、`PreferencePlayService.java` | 与 master blob 一致 |
| `ApiPlayProgressController.java`、`CmsRegistrationServiceImpl.java`、`TopicRouteRuntimeServiceImpl.java`、`TopicCompletionAwardServiceImpl.java` | 都是 master 更新。12 处 `@DeadlockRetry` 在 master 上全都在（见下） |
| `DeadlockRetryInterceptorTest.java` | master 是超集：多了 `rateLimiterCountsOnceAcrossTheRetry`，`@Import` 加了 `RateLimiterAspect` |
| `PlayerGrowthConcurrencyMySqlIT.java` | 两边的 `@Test` 方法集合相同（12）。PR 用 `innodb_trx` + `trx_query like` 粗判锁等待，master 按事务连接精确查 `performance_schema.data_lock_waits`，更严 |

### 任务要求的三项核验（master 063a6c3c）

**(1) 每个外层 `@Transactional` 入口都带 `@DeadlockRetry`。** 拦截器在进入时如果已经有活动事务，就不重试，所以注解只有落在最外层才生效。
写成长和通关事实的调用链：

- `playerProgressionService.onNodeDone` 只经 `advanceProgress` 调用。`advanceProgress` 的调用方只有带请求映射的 `checkin`、`answer`、
  `revealPuzzle`、`sensorResult`、`arrive`、`photo`（各自带 `@DeadlockRetry` + `@Transactional(READ_COMMITTED)`），以及
  `submitPreference`。`submitPreference` 本身不开事务，而是交给 `PreferencePlayService.submit`（带 `@DeadlockRetry` + `@Transactional`）。
- `onTopicComplete` 只经 `TopicCompletionAwardServiceImpl.grantOnce` 调用（两个重载都带注解）。主代码里 `topicCompletionAwardService.grantOnce(` 的调用方只有三个文件：
  - `ApiPlayProgressController`：外层是上面列的端点；
  - `TopicRouteRuntimeServiceImpl`：只在私有 `grantOpeningAward` 里调，而它只被 `completeOpening` 调用。`completeOpening` 带注解，它的调用方
    `ApiPlayJourneyActionController.completeOpening` 不开事务；
  - `CmsRegistrationServiceImpl.redeemExploreChapterAndRecord`：两个重载都带注解。它的调用方 `ApiRegistrationController.doRedeemExploreChapter`
    及其 2 个上游方法都不开事务，整个 controller 也没有 `@Transactional`。
- 附带发现：`checkin`、`answer`、`revealPuzzle`、`arrive`、`photo` 各有一到两个**没有请求映射**的便捷重载，不带注解。
  它们不是 HTTP 入口，主代码里也没有调用方（只有注释提到 controller 类名），所以不算缺陷。新测试只认带映射的那个重载。

**(2) 重试上限。** `DeadlockRetryInterceptor.MAX_ATTEMPTS = 2`（重试 1 次），退避是 10–60ms 抖动，只认 `DeadlockLoserDataAccessException`（1213）和
`CannotAcquireLockException`（1205）。现有测试已覆盖：`DeadlockRetryInterceptorTest` 的
`givesUpAfterTwoAttemptsAndRethrowsTheLockFailure`、`lockWaitTimeoutIsRetriedOnceToo`、`otherFailuresAreNotRetried`、
`innerParticipantDoesNotRetryAndLeavesItToTheOuterBoundary`、`retryAdvisorIsOrderedOutsideTheDefaultTransactionAdvisor`
和 `rateLimiterCountsOnceAcrossTheRetry`（本地 8/8 pass）。

**(3) 并发落库结果。** 已由 `PlayerGrowthConcurrencyMySqlIT` 在真 MySQL 上覆盖：
- `sameTopicOuterUniqueMarkerCallsGrowthOnlyOnce`：经重试边界后，第二请求 `firstCompletion=false`，`markerInserts==3`，成长 4/10.75/2，
  只有一个 `TOPIC_CLEAR`，`TOPIC_FINISH_11` 存在；
- 负控 `sameTopicDuplicateWithoutRetryBoundaryIsDeadlockVictim`：没有重试边界时抛 `DeadlockLoserDataAccessException`，也就证明了在 Spring 7
  下 1213 仍被翻译成这个类型。

它登记在 `ci/real-storage-it-registry.json` 的 `port-f3-growth`（12，`CHENGYIN_FUNDS_MYSQL_IT=1`），不在普通 CI 里跑。
本 lane 没有新跑真库，这一项沿用已登记的结果。

### 缺口与补测（先证明会失败）

master 上**没有任何测试钉住"注解落在哪些入口"**。去掉某个端点的 `@DeadlockRetry`，或者给某个调用方加一层 `@Transactional`
（重试会被静默降级成内层参与者），现有测试全部仍是绿的。拦截器单测用的是测试内自带的 bean，真库 IT 直接调 `grantOnce`。

新增 `chengyinhub-admin/src/test/java/com/chengyinhub/framework/aspectj/DeadlockRetryEntryPointContractTest.java`（4 个用例，纯反射加源码扫描，不起 Spring，不连库）：

| 用例 | 钉住什么 |
|---|---|
| `outermostTransactionalEntryPointsCarryTheRetry` | 12 个入口（6 个带映射的端点 + `PreferencePlayService.submit` + `completeOpening` + 2×`redeemExploreChapterAndRecord` + 2×`grantOnce`）都同时带 `@DeadlockRetry` 和 `@Transactional`。数量不是 12 就失败，迫使新增重载重新做副作用审计 |
| `everyPlayEndpointThatAdvancesProgressOwnsTheRetryBoundary` | `ApiPlayProgressController` 里任何带映射、调用 `advanceProgress(` 的方法都必须自带两个注解。走 `preferencePlayService.submit(` 的方法不得自带事务 |
| `callersOfServiceLevelRetryBoundariesStayNonTransactional` | `completeOpening`、`redeemExploreChapterAndRecord`、`doRedeemExploreChapter` 的调用方都不带 `@Transactional`（简单名和全限定名都检查）。`grantOpeningAward` 只能被 `completeOpening` 调用 |
| `topicCompletionAwardHasNoUnreviewedCaller` | 主代码里 `topicCompletionAwardService.grantOnce(` 只出现在上述 3 个文件中。新增调用方会失败，提示先确认外层边界 |

**变异证明**：每次只改一处生产代码，跑完立即 `git checkout` 恢复，生产代码不提交。

| 变异 | 结果 |
|---|---|
| M1 删掉 `photo` 的 `@DeadlockRetry` | 2 个用例失败（`outermost…`、`everyPlayEndpoint…`） |
| M2 删掉 `redeemExploreChapterAndRecord`（7 参）的 `@DeadlockRetry` | `outermost…` 失败 |
| M3 给 `ApiPlayJourneyActionController.completeOpening` 加 `@org.springframework…Transactional` | `callersOf…` 失败 |
| M4 给 `ApiRegistrationController.doRedeemExploreChapter` 加 `@org.springframework…Transactional` | `callersOf…` 失败 |
| M5 给 `submitPreference` 加 `@Transactional` | `everyPlayEndpoint…` 失败 |
| M6 新建一个调用 `topicCompletionAwardService.grantOnce(` 的主代码类 | `topicCompletionAwardHasNoUnreviewedCaller` 失败 |
| 全部恢复后 | 4/4 pass；`DeadlockRetryInterceptorTest` 8/8 pass |

第一版测试对 M3–M5 漏检，因为它只匹配 `@Transactional` 字面量。改成同时识别简单名和全限定名后，6 个变异全部能抓到。

**命令**：`LC_ALL=C.UTF-8 mvn -B -q -o -pl chengyinhub-admin -am -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest='DeadlockRetry*' test`

**仍未处理**（#294 正文已列出，不属于本 lane，也不是 #294 引入的回归）：`logAttempt`、`refreshBingo`/`grantCellRewards`、
`MerchantReferralBountyService.onVerification`（NESTED）、`inspectAndQueue` 这几处旧 catch 会吞掉死锁异常，重试因此不会触发。
另外 `ci/real-storage-it-registry.json` 的 `port-f3-growth` 注释写着 "byte-identical to 604b8c66"，已经过时（master IT 后来又加严了）。
注册表属于 CI gate 范围，本 lane 不改。

**建议处置**：本 PR 合入后，把 #294 作为已被取代关闭（close as superseded after this PR merges）。

## 本 PR 改动清单

- 新增 8 个 #314 测试文件（7 个原样，CL-24 按 master 文案对齐）
- 新增 `DeadlockRetryEntryPointContractTest.java`
- 新增本文档

没有改动生产代码、迁移、CI 门禁、登记表或基线，也没有碰 C00 保留的小程序文件。
