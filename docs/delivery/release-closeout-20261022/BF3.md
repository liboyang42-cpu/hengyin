# BF3:RV4 SHOULD-FIX 修复(#531 / #532 / #533)

- 依据:RV4 独立评审(#534,`docs/delivery/release-closeout-20261022/RV4.md`,分支 `feature/lane-rv4-review-20261022`)
- 方式:直接在被评分支上追加修复提交,每项都带回归测试。每个新测试都验证过:去掉修复会变红。新行为全部在既有的默认关开关之后。
- 未做:没有合并、没有改既有迁移、没有新 DDL、没有新依赖、没有改小程序(`chengyinhub-xcx/`)。各 PR 正文只追加了「BF3 修复」一节。
- RV4 列出的两处纯追加型文本冲突留给集成方:#530×#532 的 `ci/migration-dependencies.json`,#531×#532 的 `AuditTaskServiceImpl` `@Value` 块。
- NIT 项均未处理。

## 总览

| PR | 分支 | 修复前 head | 修复后 head | GitHub CI | Funds MySQL IT | Redis recovery | Mini followup |
|---|---|---|---|---|---|---|---|
| #531 O5b | `feature/doc2-o5b-audit-20261022` | `d15579c7` | `45f80ba8` | 39870204404 ✅ | 39870204433 ✅ | 39870204394 ✅ | 39870204399 ✅ |
| #532 O5a | `feature/doc2-o5a-audit-20261022` | `0ffc541d` | `a41cb501` | 39869166354 ✅ | 39869166263 ✅ | 39869166816 ✅ | 39869166796 ✅ |
| #533 F6 | `feature/doc2-f6-rpg-p1-20261022` | `0efa1015` | `563f5581` | 39869612930 ✅ | 39869612935 ✅ | 39869612945 ✅ | 39869612934 ✅ |

每个 head 各有 35 项检查,全部为 success 或 skipped(skipped 的是按改动范围判定不涉及的 Admin UI / SBOM 门)。

## #531 O5b

| 项 | 提交 | 修复 | 回归测试 |
|---|---|---|---|
| S531-1 轻改分级 | `5cd1be58` | `ContentEditClassifier` 有两处改动:<br>- 数字之间的标点不再剥离,所以 `10.00`≠`1000`、`8:30`≠`830`;<br>- 下面两种情况一律判为 SUBSTANTIVE:数字序列(含全角、中文数字)有任何变化;否定词(不/非/无/没/未/别/勿/禁/拒/免…,覆盖 不得/不可/严禁/禁止,以及英文 no/not/never/without)的个数变化。<br>先审模式下这类编辑会回到人工复审 | `ContentEditClassifierTest#anyNumberChangeIsSubstantive`<br>`ContentEditClassifierTest#addingOrRemovingNegationIsSubstantive`<br>`ActivityUpdateLightEditTest#switchOnAmountOrNegationEditStillReentersPreReview` |
| S531-2 系列 CLOSED | `ae753fa0` | - event-ops 的 `cancelOccurrence` 和可读校验接受 ACTIVE 与 CLOSED。<br>- 候补的策略、报价、认领、到期递补、维护扫描 SQL 接受 CLOSED。<br>- CLOSED 系列禁止新加入候补;排新场次仍只限 ACTIVE。<br>- 运营列表仍展示「有未取消未来场次」的 CLOSED 系列 | `ClubOccurrenceCancellationServiceTest#scheduledOccurrenceOfClosedSeriesCanStillBeCancelled`<br>`ClubOccurrenceCancellationServiceTest#occurrenceOfUnknownSeriesStatusIsStillRejectedBeforeRefund`<br>`ClubWaitlistServiceImplTest#closedSeriesRejectsNewWaitlistJoin`<br>`ClubWaitlistServiceImplTest#closedSeriesOfferedEntryCanStillBeQuotedAndClaimed`<br>`ClubWaitlistServiceImplTest#closedSeriesWaitlistCancelAndPromotionStillWork`<br>`ClubWaitlistServiceImplTest#closedSeriesStatusBlocksJoinButKeepsOfferTokenForQueuedMember`<br>`ClubWaitlistServiceImplTest#waitlistAndOpsReadSqlAcceptClosedSeries` |
| S531-3 申诉附件 | `45f80ba8` | - 新增 `AppealAttachmentRefs`,兼容两种 `evidence_json` 形状。<br>- 审核详情只对申诉单且确有附件时追加 `appealAttachments`。<br>- 预览地址需要 `business:mediaReview:query`,复用 5 分钟短签名;无权限时只给附件编号;单个附件失败不影响整单详情。<br>- 后台 `audit/index.vue` 渲染附件 | `AppealAttachmentRefsTest`(4 例)<br>`AuditAppealAttachmentDetailTest`(3 例)<br>`chengyinhub-ui/tests/unit/audit-appeal-attachments-contract.test.js`(3 例) |

保守取舍(待 owner 定):
- 轻改阈值没有改。数字和否定词的变化已经强制判为实质改动。
- 以下两项没做,渐进改写的风险仍在:
  - 与「最近一次过审快照」比对(目前没有现成的过审基线);
  - 先审模式下给轻改挂抽检单。
- CLOSED 系列的候补选了「照常收尾」,没有选「关闭时批量结束并通知」。后者要在 `closeSeries` 里批量归还库存,风险更高。

## #532 O5a(提交 `a41cb501`)

| 项 | 修复 | 回归测试(都在 `CommunityPostLimitProducerTest`) |
|---|---|---|
| S532-1 LIMITED 可举报 | 举报和 detail 用同一口径 `readableById`:`selectById` 读不到时,再按直达口径用 `selectLimitedById` 读 | `S532_1_开关打开_能直达看到的LIMITED帖可以举报`<br>`S532_1_开关关闭_已LIMITED帖同样可以举报`<br>`S532_1_直达口也读不到的帖子仍不可举报` |
| S532-2 恢复不卡在 LIMITED | `limitedLifecycle`:恢复值是 LIMITED 时,只有开关开着且 DOWNRANK/SEARCH_EXCLUDE 仍生效才保留 LIMITED,否则回到 PUBLISHED | `S532_2_降权_隐藏_降权到期_隐藏到期后回到PUBLISHED`<br>`S532_2_隐藏到期时分发处置仍生效_恢复为LIMITED`<br>`S532_2_开关已关_恢复值为LIMITED时回到PUBLISHED` |
| S532-3 开关关回后仍可读 | 直达读取 LIMITED 不再看开关。LIMITED 行只可能在开关打开期间产生 | `S532_3_开关开后又关_已LIMITED帖对受众内读者仍可读`<br>`开关关闭_从未产生LIMITED的库_非作者读不到仍是404`(替换原用例 `开关关闭_非作者读不到LIMITED帖_不走直达读取`:原用例断言的正是本次要改的行为,404 断言保留) |

保守取舍(待 owner 定):
- 只开放了举报。互动、评论、分享对 LIMITED 帖仍返回 404。
- 开关关闭时,detail 和举报在 `selectById` 没读到时会多一次查询;从未产生过 LIMITED 的库,结果不变。
- 开关关回后,LIMITED 帖可以按直达链接阅读和举报,但要回到 feed 仍需要逐帖 reconcile。批量回滚作业没有做,需要写进 runbook。

## #533 F6(提交 `563f5581`)

| 项 | 修复 | 回归测试 |
|---|---|---|
| S533-1 交易先校验 requires | `bargain()` 付代价之前,按 `roll()` 的同一口径校验 `requires`。拒绝时不扣代价、不做 CAS、不写动作 | `TopicRouteRpgP1ServiceImplTest#bargainWithUnmetRequiresIsRefusedBeforePayingCost` |
| S533-2 天气查询限时 | - 天气源调用放进有界守护线程池(4 线程 / 16 队列,满了直接拒绝)。<br>- 最多等 `chengyin.rpg.weather.timeout-ms`,默认 800ms。<br>- 超时、池满、异常都回落为 `unknown`。<br>- `TopicWeatherProvider` 的 javadoc 写明了超时契约 | `JourneyRpgP1Test#slowWeatherProviderTimesOutToUnknownWithinBound` |
| S533-3 交易报价按规则下发 | 投影只在本局规则(冻结的优先)开启 `rpg.bargain` 时才下发 `check.bargain`。`/api/play/nodes` 经只读的 `bargainOffered` 取规则 | `JourneyRpgP1Test#bargainOfferIsProjectedOnlyWhenThemeBargainEnabled`<br>`TopicRouteRpgP1ServiceImplTest#bargainOfferedFollowsSessionRules` |

保守取舍(待 owner 定):手上没有本局规则的投影一律不下发 `check.bargain`。这包括模板库、商家/报名视角、`/api/topic/info-to-user`、遭遇预载。

## 本地验证

所有命令都带 `LC_ALL=C.UTF-8`,用 reactor `-pl … -am test` 运行,没有执行 `install`。

| PR | 运行范围 | 结果 |
|---|---|---|
| #531 | 涉及的 39 个测试类 | 254 例,0 失败,12 个 skipped(E01 的条件跳过) |
| #532 | 30 个相关套件 | 261 例,0 失败 |
| #533 | 134 个相关测试类 | 1799 例,0 失败 |
