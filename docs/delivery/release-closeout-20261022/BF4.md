# BF4：修 W48R 的 SHOULD-FIX（同意记录 IT 接入 CI、传感器挑战身份按成员分槽）（2026-10-23）

- master 基线：`cc74f89b`
- 依据：`docs/delivery/release-closeout-20261022/W48R.md`（PR #538，分支 `feature/lane-w48r-20261023`）
- 分支：`feature/lane-bf4-20261023`（Draft PR → master）
- 无 DDL，无新 migration，无新依赖。奖励金额和资金口径都没有改。

## (1) `ConsentRecordIdempotencyMySqlIT` 接入 CI（W48R #472 SHOULD-FIX 1）

| 改动 | 说明 |
|---|---|
| `ci/backend-test.sh` 的 `MYBATIS_ITS` 加上 `ConsentRecordIdempotencyMySqlIT` | 测试按名字点名执行。`ci/assert_it_ran.py` 读回报告，报告缺失、`tests=0`、`skipped≠0` 或有失败，都会判红 |
| 同一条 `mvn -pl chengyinhub-admin -am test` 行加上 `CHENGYIN_CONSENT_IDEMPOTENCY_MYSQL_IT=1` | 这个类用 `@EnabledIfEnvironmentVariable` 门控。不设置这个变量，5 条用例都会 skip，回读就红，所以变量和清单必须一起改 |
| `ci/test_ci_cost_contract.py` | 正向断言里加上新变量和清单项，并新增两条负控：去掉变量、去掉清单项，两种情况都必须判红。这是**加严**，原有断言一条没删 |

- 连接配置：这个 IT 读 `CHENGYIN_MYBATIS_IT_PORT/USER/PASSWORD`。CI 的 backend job 已经注入了这三个变量（动态端口、root/root）。
- 建库方式：它自己建随机名库 `cy_consent_replay_<uuid>`，跑完删除，不和其他 IT 共享库。
- 本地验证：Docker 起 `mysql:8.0.45`，用 CI 同样的变量跑 `-Dtest=ConsentRecordIdempotencyMySqlIT`，结果 5/5，skipped=0。`python3 ci/assert_it_ran.py … ConsentRecordIdempotencyMySqlIT` 输出「✅ 1 套均真跑」。
- CI 结果以 PR 上的「Backend tests」job 为准。

## (2) 归零挑战分成员 attempt 路径补测（W48R #478 SHOULD-FIX 1）

新增 `SensorAttemptMemberSlotTest`（chengyinhub-admin，纯单测）：

| 用例 | 覆盖 |
|---|---|
| `zeroChallengeIssuesAttemptIntoTheStartersOwnSlot` | **签发**：开表后 `zeroChallengeAttempts[成员][stopwatch]` 里有 id、memberId 和过期时间，下发的 attemptId 与之一致 |
| `zeroChallengeAttemptIsBoundToItsMemberAndSurvivesTeammateStart` | **绑本人**：B 后开表，A 的投影仍是 A 的号。A 交 B 的号会被拒，B 交 A 的号也会被拒。各交各的都能结算，交完后槽位被移除 |
| `zeroChallengeAttemptIsSingleUse` | **单次消费**：交过的号不再下发，重放会被拒 |
| `zeroChallengeAttemptExpires` | **过期**：超过「配置时长 + 10 分钟」后提交会被拒，过期的号也不再下发 |
| `zeroChallengeLegacyClientWithoutAttemptIdStillPlaysButEvidenceStaysCapped` | **存量客户端**：不带 attemptId 照样能交、能判过。`rewardCeiling=XP_BADGE_ONLY`，`untrustedCompletionEvidence` 返回 `stopwatch:SELF_REPORTED`（封顶口径）。归零挑战本身只写恢复回执，不走节点发奖 |

补测时顺带发现并修了一处投影问题：归零挑战里，B 后开表会把共享段上的 `attempt` 换成 B 的号。原来 `putAttempt` 只读共享段，所以 A 刷新后**拿不到自己的 attemptId**。现在投影先读本人的槽位（`zeroChallengeAttempts`，以及下面新增的 `teamMemberChallenges`），本人没有槽位时才回落到共享段。原有的「本人、未用、未过期」三条检查都保留。

## (3) 队伍共享局的 attempt 槽位按成员分开（W48R #478 SHOULD-FIX 2）

**是不是有意为之**：W48R 认为这个行为「安全，但对玩家不友好」。`putAttempt` 的注释写的是「别人的（队伍共享局）……一律不给」，说明设计本意是只让本人用自己的号，没找到任何代码或文档要求「队友开表要作废本人的号」。所以判断为非有意，予以修复。

**改法**（`AdvancedGameRuntimeServiceImpl.act`）：

- 范围：只对 `OWNER_TEAM` 且非归零挑战的会话生效。
- 存储：在 `state.teamMemberChallenges[memberId][game]` 里按成员各存一份 `{startedAtMs, attempt}`。
- START_CHALLENGE：照原逻辑签发后，把本人这次的开表时间和号存进本人的槽位。
- SUBMIT_*（带挑战身份的六个动作）：先把本人槽位里的开表时间和号摆回共享段，再走原有的 `requireCurrentAttempt` 和复核。提交后把本人这一份（已消费的号、开表时间是否仍在）存回本人槽位。写回时只保存 memberId 等于本人的号。
- 开表时间也要按成员分开：只分号不够。A 没停准时，原逻辑会删掉共享段的 `startedAtMs`，B 就会被拒（「请先开始」），没法完成自己的那一次。
- 仍是全队共享口径（没改）：`attempts` 次数、`submitted`、`passed` 和 `awarded`。一人停准，全队这一段就算交过。
- 升级前已开的表：会话里没有本人槽位时，按原共享段口径处理，不会因为发版把在途的号作废。共享段上如果是别人的号，仍会被 memberId 检查挡住。
- 不涉及数据库：所有改动都在会话的 `state_json` 里，无 DDL。

回归测试：

- `teamSharedSessionGivesEachMemberTheirOwnAttemptSlot`：A、B 在同一队伍共享局里先后开表。两人各自只看得到自己的号，互相用对方的号都会被拒。A 交一次后不能重放，B 也不能拿 A 用过的号。B 的号和开表时间没受 A 影响，照样能交一次；之后 B 也不能重放。次数仍是全队累计（2）。
- `teamSessionStartedBeforeUpgradeKeepsTheSharedSlotBehaviour`：升级前的会话状态仍可正常结算。

**负控**：去掉槽位恢复和投影优先读本人槽位后，`teamSharedSessionGivesEachMemberTheirOwnAttemptSlot` 与 `zeroChallengeAttemptIsBoundToItsMemberAndSurvivesTeammateStart` 都变红（A 拿不到自己的 attemptId）。恢复修复后转绿。

## 验证

- 本地（`LANG=C.UTF-8`）：
  - `SensorAttemptMemberSlotTest` 7/7、`SensorGameTrustBoundaryTest` 14/14、`ZeroChallengeReentryTimeoutV2Test` 2/2。
  - `*AdvancedGame*Test,*ZeroChallenge*Test,Sensor*Test,*SharedRoute*Test,*TeamGame*Test,*GameRuntime*Test` 共 14 套 389 条，0 失败、0 跳过。
- `python3 ci/test_ci_cost_contract.py` PASS（含两条新负控），`ci/test_wechat_registration_ci_contract.py` 和 `ci/test_assert_it_ran.py` 都 OK。
- 引用该服务类的小程序契约单测（7 个）通过。
- 全量结果以 PR 的 GitHub CI 为准。

## 未修的项

- NIT（W48R）：`ConsentRecordServiceImpl` 的 import 顺序（`HttpStatus` 等插在 `StringUtils` 之后）。
- NIT（W48R）：`AdvancedGameRuntimeServiceImpl` 在 #478 新增的 import（`RewardPolicy`、`HashMap`）没有按字母序排列。
- NIT（本 lane 发现）：队伍共享局的 playKit 里，`started` 仍按共享段的 `startedAtMs` 显示（最后一个操作者的状态），不是本人的。提交判定已经按本人槽位处理，这里只影响显示。要改得连同小程序一起看，这次没动。
- 不属于本 lane 范围的 W48R SHOULD-FIX：
  - #472-2：小程序收到 409 时是否换新 requestId 重试，需要前端确认。
  - #478-3：主题通关奖是否一并封顶，需要 owner 决策；compass、reaction、ballshake 组件还没有传号，属于小程序侧。
