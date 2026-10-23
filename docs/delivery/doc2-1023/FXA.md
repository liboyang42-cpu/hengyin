# FXA：主题通关奖同口径封顶 + 三个传感器组件补传 attemptId + 同意接口 409 换号重发（2026-10-23）

- master 基线：`f099c5be`（#541 BF4 合入后）
- 依据：`docs/delivery/release-closeout-20261022/BF4.md`「未修的项」；W48R（PR #538 分支 `feature/lane-w48r-20261023` 上的 `W48R.md`）SHOULD-FIX #472-2、#478-3
- 分支：`feature/doc2-fxa-20261023`（Draft PR → master）
- owner 2026-10-23 已批准本任务三项，其中第 (3) 项明确批准改动 C00 保留文件 `chengyinhub-xcx/utils/transport/request-client.js`。
- 不改 DDL，不加 migration，不加依赖。金额与资金口径不变，只收窄「哪些情况能拿到通关积分和通关券」。

## (1) 主题通关奖同口径封顶（#478-3）

### 规则

节点完成时，`rewardCappedToXpBadge` 已经判定过：只靠设备自报成绩、拍照兜底放行、或扫码静态码完成的节点，只发探索值、徽章和名次奖牌。现在把这条规则扩展到整部主题的通关奖：

> 本人在本场次、本主题里**已完成的节点**中，只要有一站按上述口径封顶，通关奖就以 `XP_BADGE_ONLY` 发放：通关勋章、通关成长徽章、通关探索值照发，**通关积分（13）和通关券不发**。

判据复用 `rewardCappedToXpBadge`，没有另写一套。新增的接口默认方法 `IAdvancedGameRuntimeService.anyNodeRewardCappedToXpBadge(member, activity, topic, completedNodeIds)` 对每个已完成节点调用一次这个判据。由于读的是 `game_runtime_session` 里冻结的配置快照和本局 `state_json`，主题后来改版，不会改变已经玩过的那几局的判定结果。

### 改动

| 出口 | 文件 | 改法 |
|---|---|---|
| 节点完成触发通关（主路径） | `ApiPlayProgressController.advanceProgress` | 通关判定时，用本次统计 `done` 的同一批 `status=1` 进度行（同一 member/activity/topic 口径）收集已完成节点 id，交给 `anyNodeRewardCappedToXpBadge`；本节点已经封顶时直接短路。只要有一站封顶，`grantOnce(..., XP_BADGE_ONLY)`，回包加 `topicRewardCeiling: "XP_BADGE_ONLY"`（与节点的 `rewardCeiling` 并列，只在封顶时出现） |
| 序章出发触发通关 | `TopicRouteRuntimeServiceImpl.grantOpeningAward` | 用本局路线 state 的 `completedNodeIds` 做同样判定，并与原有的「滤镜拍照 ⇒ XP_BADGE_ONLY」取并集。`IAdvancedGameRuntimeService` 依赖本类，所以这里用 `@Autowired(required=false) @Lazy` 注入，打破循环依赖（与 `CmsRegistrationServiceImpl` 注入 `topicCompletionAwardService` 的写法相同）；手工 new 的单测不注入时，行为与旧版一致 |
| ③ 自由收集 · 商家核销触发通关 | `CmsRegistrationServiceImpl`（**未改**） | ③ 的通关只认商家核销（`perkLedgerService.isExploreCompleted`），这是服务端可以证明的事实，不依赖设备自报。所以 `advanceProgress` 在 `shopDay` 时也不按节点封顶，两条出口口径一致，结果不会因为「最后一步是玩还是核销」而不同 |

碎片文（`fragmentText`）仍按调用方原口径处理，不在本项范围内。

### 奖励表（更新后，覆盖 #478 PR 正文 / W48R 复审表）

| 奖励 | 节点：可信完成 | 节点：封顶（自报 / 拍照兜底 / 扫码） | 通关：所有已完成节点都可信 | 通关：**任一**已完成节点封顶（本次新增） |
|---|---|---|---|---|
| 节点打卡积分 12 | ✅ | ❌ | — | — |
| 节点模板券 / 条件奖励券 | ✅ | ❌ | — | — |
| 随机掉落（含彩蛋券、探索值翻倍） | ✅ | ❌ | — | — |
| 节点探索值 / 条件奖励探索值 | ✅ | ✅ | — | — |
| 节点成长徽章（`onNodeDone`） | ✅ | ✅ | — | — |
| 名次奖牌（`claimNodeMedal`） | ✅ | ✅ | — | — |
| 通关积分 13 | — | — | ✅ | ❌ **（新）** |
| 通关券（`completeRewardCouponId`） | — | — | ✅ | ❌ **（新）** |
| 官方活动通关证据（`recordThemeCompletion`） | — | — | ✅ | ❌（沿用 `grantOnce` 的 XP_BADGE_ONLY 口径） |
| 通关勋章 `TOPIC_FINISH_*` | — | — | ✅ | ✅ |
| 通关成长徽章（`onTopicComplete`） | — | — | ✅ | ✅ |
| 通关探索值（XP 预算余量） | — | — | ✅ | ✅ |
| 回包标记 | — | `rewardCeiling` | — | `topicRewardCeiling` |

- 封顶判据（不变）：`reaction / ballShake / quietHold / shout / stopwatch / compass` 任一项启用；`photoCheck` 启用但视觉模型没有当场判 `PASS`；`scan` 启用。
- ③ 自由收集：通关认核销，不按节点封顶（见上表说明）。
- 队伍共享局 / 通用个人任务：`completeGate` 直接 409，本来就走不到 `advanceProgress` 的发奖链；它们的结算里也没有调用 `grantOnce`（W48R 已核过）。

### 测试

- `ApiPlayProgressIdempotencyTest`（+4，用真实的 `TopicCompletionAwardServiceImpl`）：
  - `earlierSelfReportedNodeCapsTheThemeCompletionBonus`：前一站自报，本站可信。本站照发全套奖励；通关时不发积分 13 和通关券，勋章、`onTopicComplete`、通关 XP 照发，回包带 `topicRewardCeiling`。
  - `currentSelfReportedNodeCapsBothNodeAndThemeBonus`：最后一站自报，节点奖和通关奖都封顶。
  - `allTrustedNodesKeepTheFullThemeCompletionBonus`（负控）：所有站都可信时，积分 13 和通关券照发，并断言前一站也被查询过（判据覆盖全部已完成节点，不只看本站）。
  - `freeCollectThemeCompletionStaysOnRedemptionEvidence`：③ 即使节点封顶，通关奖仍是 FULL。
- `TopicRouteRuntimeServiceImplTest.openingThemeAwardIsCappedWhenACompletedNodeReliedOnSelfReport`：序章出发时，如果序章节点是自报完成，`grantOnce(..., XP_BADGE_ONLY)`，FULL 重载一次都不调用。
- 新 MySQL IT `ThemeCompletionRewardCapMySqlIT`，跑真实的 `game_runtime_session` 迁移、真实 mapper 和真实 `AdvancedGameRuntimeServiceImpl`：
  - 服务端可判的估值局、视觉当场 PASS 的拍照局、没有高级玩法的站 ⇒ 不封顶。
  - 只要有一局反应自报 ⇒ 封顶；拍照 `FALLBACK_PASS` ⇒ 封顶。
  - 别人的自报局不影响本人；别的场次的局不计入。
  - 用 `JSON_SET` 把兜底局改判为 PASS，回读后解除封顶。
  - 已加入 `ci/backend-test.sh` 的 `MYBATIS_ITS`，回读由 `assert_it_ran.py` 断言，报告缺失、`tests=0` 或 `skipped≠0` 都会判红。`ci/test_ci_cost_contract.py` 加了正向断言和一条「从清单删掉即红」的负控（**只加严**，原有断言一条没删）。
- 负控：把控制器里的封顶条件短路成 `false`，`ApiPlayProgressIdempotencyTest` 有 3 条变红（两条封顶用例，加上「前一站必须被问到」）。恢复后转绿。

## (2) 三个组件补传服务端 attemptId

BF4/W48R 点名的三个组件是 compass、reaction、ballshake。

| 组件 | 原来缺什么 | 改法（照搬 stopwatch / shout 的写法） |
|---|---|---|
| reaction | 没有 `attemptId` 属性；分发器没绑定；`serverPayload` 不带号 | 加 `attemptId` 属性。第一轮开表（`triggerEvent('start')`）时记下 `_attemptAtRun`，提交时用 `withAttempt(…, freshAttempt(当前号, 开表时的号))` |
| ballshake | 同上 | 同上。`onRun` 里 `start` 之前把开表时的号记到本局 `attempt.attemptAtRun` |
| compass | 有属性、也会带号，但**从不开表**，所以服务端从没给它发过号（`issueAttempt` 只在 `START_CHALLENGE` 里调用），一直是 0 | 开跑（`onRun`）时报一次 `start`。`ACTION_OF` 加 `compass:start → START_CHALLENGE`，`CHALLENGE_GAME` 加 `compass`（服务端 `challengeKey` 本来就认 `compass`，注释写明「开表只为领挑战身份」）。提交也改用 `freshAttempt` |

- `pickPlayKit` 给 reaction / ballShake 也加上 `sensorContract.kitFields(seg)`；分发器 `playkit/index.wxml` 给两者绑 `attempt-id`（写在原有那一行上），给 compass 绑 `bind:start`。
- 新号还没回来，或者手里只有开表前的旧号时，不带号，按存量口径复核，奖励照样由服务端封顶，绝不编造号。
- **没有改任何界面**：不新增文案、不新增样式，三个组件的 wxml/wxss 一字未动。reaction 和 ballshake 没有接 `reward-ceiling`，因为接了就要在界面上多一行说明，超出本任务范围。
- 测试（node）：
  - `reaction-recovery` / `ballshake-recovery` / `compass-sensor-recovery` 各加 1 条：开表后服务端刷新出新号，这个新号会随成绩提交；只有开表前的旧号时不带号；`serverPayload` 映射正确。
  - `sensor-games-trust-boundary-c1` 加 1 条：两种 kit 都原样搬运 `attemptId`（伪造值归 0）；三个组件的 `attempt-id` 和 `bind:start` 都已绑定；`compass:start → START_CHALLENGE {game:'compass'}`；伪造号不提交。
  - 两个测试宿主原来把 `require` 一律桩成 `{haptic}`，现在按 quiet/shout 宿主的写法，把 `sensor-contract.js` 指向真实模块。
  - `compass-sensor-recovery` 原有用例断言「不发 start」，这是旧协议。现在改成「恰好发一次 start」，提交仍然只带 `bearing`（没有新号时）。这是协议变更，不是放宽断言。
- 负控：只回退 5 个生产文件、保留测试时，4 个测试文件各有新增用例变红。

## (3) #472-2：同意接口 409 换号重发一次（owner 批准改 C00）

`utils/transport/request-client.js` 的成功分支里，426、401 两种情况之后、通用的「HTTP 非 200」处理之前，新增一项：

- **触发条件**（四条同时满足）：
  1. 路径是 `/api/agreement/consent`（用正则比对，忽略 query。写成正则而不是 `'/api/…'` 字面量，是因为动作台账的静态分析会把字面量当成「本模块调用了这个接口」，进而给全仓 736 个控件误挂这个 API）；
  2. 响应体 `errorCode === 'CONSENT_REQUEST_CONFLICT'`；
  3. HTTP 409，或 HTTP 200 包着 `code: 409`（`ApiAgreementController` 实际返回的是后者）；
  4. 原请求能读出 `requestId`（对象，或 JSON 字符串）。
- **处理方式**：生成新的 requestId（可注入 `deps.newRequestId`，默认 `consent-<时间36进制>-<随机>`，最长 64），只替换 `requestId`，其余字段和原来的 data 形态（对象或字符串）保持不变，用共享的 `state` 重发。`state.consentRetried` 保证**最多重发一次**。第二次仍冲突时如实交给调用方（`success` / `successStatusAbnormal`），不再循环。`complete` 只在最后一跳触发一次。
- **不触发的情况**：`/api/compliance/consents` 定位同意的 409（状态已变，没有这个 errorCode）照旧原样返回，`consent-client` 对它的处理不受影响；其他接口带同一个 errorCode 也不触发；`code: 500`、urlencoded 字符串 data、没有 requestId 都不触发，也不会生成新号。
- 现状说明：本仓库的小程序目前**没有**页面调用 `/api/agreement/consent`（同意留痕走的是 `/api/compliance/consents`）。这一项属于传输层兜底，等首个调用方接入后立即生效。
- 测试：`request-client.test.js` 加 4 条：
  - 200 包 409 时，换号重发一次，成功只回调一次；
  - 重发后仍冲突时如实交给调用方、不再重发，只生成一个新号；
  - HTTP 409 加 JSON 字符串 body 时同样重发，第二次走 `successStatusAbnormal(409)`；
  - 负控 6 种情况都不重发、不生成新号。
- 回退 `request-client.js` 后，新增用例有 3 条变红。

## 验证

本地均在 `LANG=C.UTF-8` 下运行：

- 后端：
  - `ApiPlayProgressIdempotencyTest` 30/30；
  - `TopicRouteRuntimeServiceImplTest` 92/92（含新增 1 条）；
  - `SensorGameTrustBoundaryTest` 14/14；
  - `MysqlItSchemaIsolationTest` 通过；
  - `ThemeCompletionRewardCapMySqlIT` 1/1，skipped=0。本机用 Docker 起 `mysql:8.0.45`，变量与 CI 相同（`CHENGYIN_MYBATIS_IT_PORT/USER/PASSWORD`）。
  - 全量 `mvn -B test`（没有导出 MySQL 变量）：13711 条，Failures 0，Errors 29，Skipped 631。29 个 Errors 都来自 12 个 `*MyBatisTest`，原因全是连不上默认 3306 的 MySQL（`Communications link failure`）。把这 12 个类指向本机 `mysql:8.0.45` 重跑后全部通过。CI 的 backend job 自带 MySQL。
  - Spring 全量启动：`@Lazy` 循环依赖的整机装配只有手动触发的 `gameplay-http-e2e` 工作流（`RuoYiApplication` 全量上下文）能覆盖。这个脚本按设计拒绝在 GitHub 托管机或专用 VPS 以外运行，本 lane 没有伪造环境标志去跑它，建议集成时手动触发一次。
- CI 合同：`python3 ci/test_ci_cost_contract.py` PASS（含新负控）；`ci/test_wechat_registration_ci_contract.py` OK。
- 小程序：
  - `node --test tests/unit/*.test.js`：13744 条里只有 2 条失败，都在 `action-ledger.test.js`，是台账过期，见下节；
  - `npm run lint:ui`、`lint:scope` PASS，`lint:requestbody` 0 error；
  - `component-attr-lint`、`wxml-handler-lint`、`js-orphan-handler-lint`、`setdata-dead-field-lint`、`ci/xcx-unit-manifest.js` 全部通过。
  - 按要求没有运行 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`。
- 最终结果以 PR 上的 GitHub CI 为准。

## 台账（C00，未重建，交集成方）

按规则**没有**重建或提交 `scripts/uiaudit/*` 台账。下面的变化量是在临时副本里跑 `build-action-ledger.js --write` 实测的，仓库内文件未改动：

- `action-ledger.json` 的 `controlCount`：**4997 → 4998**。净增 1 个，即 `playkit/index.wxml` 上 `cy-playkit-compass` 的 `bind:start="onStart"`（event-contract）。
- 按 (wxml, 事件, handler) 签名比对，净增只有上面这 1 个，没有删除。
- 同文件里，排在它之后的 69 个分发器控件序号整体后移，id 重新编号（不是新增或删除控件）。reaction、ballshake、compass 三个组件的 handler 行号移动，产生 10 条内容变化（ballshake 5、reaction 3、compass 2）。其他页面零变化。
- 因此 `tests/unit/action-ledger.test.js` 的两条用例（`合并后可达动作 variant…`、`release 前重建 inventory…`）会在本 PR 的 CI 上报「台账过期」。这与 integration-batch3 记录的 L21 情况相同。集成方重建台账后，把 `action-ledger.test.js` 的 `controlCount` 4997 → 4998 一并落地即可。

## 未做 / 留给后续

- reaction、ballshake 没有接 `reward-ceiling` 提示行。接上会改动界面，按「不改既有小程序 UI」的约束没有做。
- 碎片文是否也要跟随封顶，没有决定，仍按原口径。
- 由于有 `@Lazy` 注入，`TopicRouteRuntimeServiceImpl` 在 Spring 上下文里会持有一个惰性代理；首次调用时才解析。
