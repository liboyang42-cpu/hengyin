# RV4 独立评审:Doc2 #531 / #532 / #533(只读)

- 评审基线:master `38dc1bb74a98bff669763096f0cb76547e102720`
- 被评 head:#531 `d15579c7`、#532 `0ffc541d`、#533 `0efa1015`(F6 自身提交;F2 #524、F5 #521 随带)
- 对照:batch-4 集成 #530 head `ce043852`
- 方式:读 diff + master 相关代码。所有列出的问题都已对照代码逐条确认。#531 跑了 7 个新测试类(49 例,0 失败);#533 跑了 13 个相关套件(307 例,0 失败);#532 本地 Maven 无缓存,没有跑测试。
- 本评审对被评 PR 只读:不推它们的分支、不评论、不打标签、不合并。

## 总结

| PR | BLOCKER | SHOULD-FIX | NIT | 默认关时合入 |
|---|---|---|---|---|
| #531 O5b | 0 | 3(O5b-1 / O5b-7 / O5b-2) | 6 | 安全 |
| #532 O5a | 0 | 3(都在 LIMITED 开关下) | 7 | 安全 |
| #533 F6 | 0 | 3(rpg 灰度 / 接真实天气源前) | 5 | 安全 |

三个 PR 都没有 BLOCKER。所有新行为都在开关后面,开关默认关。问题集中在**开关打开以后**的语义缺口,需要在打开对应开关之前修好。

## 横向检查(三个 PR 共同)

- **C00 保留文件**:三个 PR 都没有改 `chengyinhub-xcx/` 下的任何文件,包括 app.js、app.json、uiaudit json、ui-integrity-baseline、danger-actions、request-client。
- **密钥 / 域名**:新增行里只有 MyBatis DTD 的 URL。没有 IP、密钥或真实域名。
- **测试**:没有 `@Disabled`、`@Ignore` 或 assume;没有放宽断言、上调 baseline 或扩充白名单。
- **资金 / 结算语义**:三个 PR 都没有改。
- **迁移**:
  - #531、#533(F6 本身)没有新 DDL。
  - #532 新增 `migration_audit_problem_20261022.sql`,写法是 `CREATE TABLE IF NOT EXISTS`。
  - #533 随带 F2 的两个迁移,做了 information_schema 前后校验,用 INSTANT 加可空列。
  - 都是新文件,没有改既有迁移,没有对有数据的列或键做 DROP(回滚语句都是注释)。

### 与 #530 及彼此之间的冲突(`git merge-tree --write-tree`)

| 组合 | 结果 |
|---|---|
| #530 × #531 | 无冲突。#530 在 `AuditTaskServiceImpl` 只加了 `@CriticalPath` 注解 |
| #530 × #532 | **文本冲突**:`ci/migration-dependencies.json`,两边都在 `dependencies` 开头插入条目。两边都保留即可 |
| #530 × #533 | 无冲突。#530 已含 #524、#521;合入后 #533 实际只增加 F6 |
| #531 × #532 | **文本冲突**:`AuditTaskServiceImpl.java`,`@Value` 字段块(head 约 116–130 行),两边各加开关字段。两边都保留即可。语义上兼容:O5b-3 改 `deadline_at`,O5a-2 改 `risk_level`,都用原子条件 SQL |
| #531 × #533、#532 × #533 | 无冲突 |

另外核对了 #533 携带的 F2/F5 内容:`merge-tree(pr524, pr521)` 得到的树与 `2cb24117`(F6 的父提交)完全一致,没有陈旧改动,也没有夹带。

---

## #531 O5b 审核遗留 B 组

### BLOCKER
无。

### SHOULD-FIX(打开对应开关前)

**S531-1 O5b-1 轻改分级能放过改变语义的编辑;先审模式下轻改完全跳过人工复审**
- 位置:
  - `chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/ContentEditClassifier.java:50-75`
  - `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiActivityController.java:954-970`
- 问题有三点:
  - `normalize()` 去掉全部 `OTHER_PUNCTUATION`,包括数字之间的 `.`、`:`、`,`。
  - 编辑距离阈值是 `max(1, 10%)`,封顶 4。
  - 比对基线是当前库行 `exist`,不是最近一次过审的快照。
- 失败场景:
  - 简介「费用10.00元」改成「费用1000元」,归一化后相同,判为 LIGHT。
  - 「可退款」改成「不可退款」,编辑距离 1,判为 LIGHT。
  - 开关打开后,先审模式(高风险品类、未认证商家)下的这类编辑不再退回待审,只走机审就直接上线。
  - 多次轻改可以把标题或简介逐步改写成完全不同的内容,全程没有人工复审。
- 修复建议:
  - 和最近一次过审快照比对;
  - 数字发生任何变化一律判为 SUBSTANTIVE;
  - 先审模式下,轻改至少挂一张不阻断的抽检单。

**S531-2 O5b-7 关闭系列后,已排场次的 event-ops 取消通道和候补都断了**
- 位置:
  - `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ClubEventOpsServiceImpl.java:484-486`:`cancelOccurrence` 要求 `series.status == ACTIVE`。
  - `ClubWaitlistServiceImpl.java:112, 533, 545`:系列策略要求 ACTIVE。
  - `ClubEventOpsMapper.xml`:列表 / 详情只读 ACTIVE 系列。
- 失败场景:
  - 主理人关闭系列后,想取消一个已售票的未来场次,`/api/club/event-ops/cancel` 返回「场次已失效,无法取消」。只剩 `/api/activity/cancel` 一条路(退款仍能走到),但运营视图里这些场次已经看不到。
  - 持有 OFFERED 候补的用户无法认领;offer 到期后不会递补下一位;WAITING 条目永远不会结束,也不会通知。
- PR 正文承认了前两点。`ClubEventSeriesCloseTest` 只断言 `never().cancelOccurrenceCas`,没有覆盖「关闭后取消」和候补。
- 修复建议:CLOSED 系列的场次仍然允许取消和候补收尾;或者关闭时批量结束候补并通知。

**S531-3 O5b-2 审核员看不到申诉附件**
- 位置:`AuditTaskServiceImpl.java:1464-1478`(pr531 行号)把附件以 `media-asset:{id}` 写进 `evidence_json` 和 SUBMIT 快照。
- 失败场景:
  - 后台没有任何代码把这个引用解析成可预览的 URL。`chengyinhub-ui/src/views/business/audit/index.vue` 只显示「附有当时内容快照」。PR 正文说「可经 /business/media-review 预览」,但审核员需要拿 id 去另一个页面手工查。
  - 有附件时 `evidence_json` 变成 `{"evidence":…,"attachments":[…]}`,和原来的纯文本 evidence 并存两种形状,读取方要兼容。
- 修复建议:给后台加附件解析和展示,或者在此之前保持开关关闭。

### NIT
- **N531-1**:附件只校验了归属是申诉人、`ownerType=APP`、`APPROVED`,没有校验 `mediaType`。音频或 GLB 也能当「图片证据」提交(`AuditTaskServiceImpl.java:1445-1457`)。
- **N531-2**:开关关闭时,带 `attachments` 的请求返回 400 `APPEAL_ATTACHMENTS_DISABLED`;master 是静默忽略这个字段。这是有意设计,需要确认小程序当前不会发这个字段。
- **N531-3**:附件校验排在作者资格校验之前(`:1255` 对 `:1261`),非作者拿到的是 `ATTACHMENT_INVALID` 而不是 `FORBIDDEN`,无害。
- **N531-4**:O5b-3 下,任何举报人都可以选首响时限最短的原因,把开放举报单的 deadline 收紧。这是设计如此(只收紧),受既有举报限流约束。
- **N531-5**:O5b-1 在先发后审、已驳回活动的实质改动路径上,`flagForSpotCheck` 用只来自 request 的快照覆盖了刚写入的完整快照。master 的 flagged 路径本来就是这样,不是新回归。
- **N531-6**:`AuditStatusTruthConsistencyTest` 按现状钉住了已知分叉 D-1,修好 D-1 时这个测试会变红,而且它扫描的是源码文本。这是有意的棘轮,后续修复时记得同步更新。

### 已核查无问题
- **归属**:
  - `/series/close` 要求主理人或联合主理人加 ACTIVITY_MANAGE;行锁后复核权限;CAS 的 WHERE 带 `club_id`、`status='ACTIVE'`、`version`;重复关闭幂等。
  - 申诉仍然只限内容作者。附件必须是申诉人本人上传且已过审的 `app_media_asset`,不接受外链,所以没有 SSRF 或钓鱼链接风险;最多 6 个,有去重和长度上限。
- **开关**:五个开关默认都是 false,都在任何新增 mapper 调用之前判断。模板相似度只在有提示时附带 `warnings`,读取失败只记日志。
- **事务**:
  - `closeSeries` 是 `@Transactional`,加锁顺序与 `updateFuture` 一致。
  - `tightenOpenDeadline` 只作用于 status 0/3,只收紧,不改 version,在 `@Transactional` 的 `reportContent` 内执行。
- **Schema**:用到的列都已在既有迁移中存在;`club_event_series` 的 CHECK 已包含 CLOSED。

---

## #532 O5a 审核遗留 A 组

### BLOCKER
无。

### SHOULD-FIX(打开 `chengyin.community.post-limit.enabled` 前)

**S532-1 能看到 LIMITED 帖的人无法举报、互动、评论或分享**
- 位置:`chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CommunityPostServiceImpl.java`(pr532 行号)
  - `detail()` 在 `:390` 回退到 `selectLimitedById`;
  - `act` `:516`、评论 `:584`、`recordShare` `:749`、举报 `:912`/`:918` 仍然只用 `mapper.selectById`,它只对非作者放行 PUBLISHED。
- 失败场景:读者按直达链接打开一条被降分发(可能有害)的 LIMITED 帖,能正常看到内容,点举报却返回 404「帖文不存在或不可见」。
- 修复建议:至少让举报路径接受 LIMITED;互动、评论、分享是否开放请 owner 定。

**S532-2 帖子会停留在 LIMITED,而此时已经没有任何分发处置生效**
- 位置:`CommunityPostServiceImpl.java:1568-1593`(`reconcileEnforcements` / `limitedLifecycle`)。
- 原因:当帖子处于 HIDDEN/REMOVED 时,恢复值取存下来的 `previous_lifecycle`。而「LIMITED → PUBLISHED」的放回逻辑在三元表达式的另一个分支里;`limitedLifecycle` 只处理输入为 PUBLISHED 的情况。
- 失败场景:
  1. DOWNRANK 生效,帖子变为 LIMITED;
  2. 施加 HIDE_PENDING_REVIEW,previous 记为 LIMITED;
  3. DOWNRANK 到期;
  4. HIDE 到期,reconcile 恢复为 LIMITED。此时没有任何处置生效,帖子却长期不出现在 feed 里。
- 修复建议:开关打开时,如果 lifecycle 是 LIMITED 且 DOWNRANK、SEARCH_EXCLUDE 都不在生效,就返回 PUBLISHED。

**S532-3 开关打开后再关闭,已经是 LIMITED 的帖子除作者外无人可读**
- 位置:`CommunityPostServiceImpl.java:390`。开关关闭后不再走 `selectLimitedById`;LIMITED 行只有在被再次 reconcile 时才会回到 PUBLISHED。
- 失败场景:回滚开关后,所有已降分发的帖子对读者一律 404,直到它们各自偶然被重新 reconcile。
- 修复建议:补一个一次性的 reconcile 或批量回滚作业,或者写进 runbook。

### NIT
- **N532-1**:风险升级的 CAS(`AuditTaskServiceImpl.java:603-618`,条件为 `risk_level <=> expected`)本身是原子的,但输掉 CAS 的一方不重试。例如 MEDIUM 先赢、HIGH 输掉,单子会停在 MEDIUM,直到下一次事件。修复建议:CAS 返回 0 时重读并重算一次。
- **N532-2**:举报人数阈值触发的升级只作用于举报单(`:522`);同一内容的内容审核单只有在自身快照变化时才会吃到阈值。请确认这是预期行为。
- **N532-3**:升级不改 `deadline_at`,HIGH 单仍沿用 NORMAL 的 SLA。代码已有注释说明;与 #531 的 `tightenOpenDeadline` 互补。
- **N532-4**:`audit_problem` 的写入和驳回在同一个事务里。如果迁移还没执行就打开开关,所有带 problems 的驳回都会失败回滚,时间线也会返回 500。上线顺序必须是先迁移、后开关,需要写进 runbook。
- **N532-5**:`problems` 不在 review 的重放指纹里。同一个 requestId 带不同的 problems 重放时返回 1,新的 problems 被静默丢弃。
- **N532-6**:驳回被 revert 后,对应的 `audit_problem` 行仍然保留,作者时间线上依然可见。
- **N532-7**:`risk_level` 是 NOT NULL,`<=>` 可以直接写成 `=`。无害。

### 已核查无问题
- **归属**:
  - `problems` 走既有审核工作台的权限。章节和节点按 `topic_id` 收口;玩法必须被本主题的节点引用;模板只能针对自身;最多 50 条,说明 ≤200 字,编码走固定清单。
  - 校验在状态 CAS 之前完成,失败就是 400,不写任何状态。
- **时间线不泄露**:`contentTimeline` 对非作者返回 403;SQL 的 WHERE 带 `task_id` 和 `owner_member_id`。
- **开关**:
  - 没有改任何既有 feed 或 `selectById` 的 SQL。`selectLimitedById` 是新语句,只在开关打开时调用。
  - 升级逻辑在开关关闭时,在任何 mapper 调用之前就返回。
  - WARN 档关闭时,`warnings`/`results` 为 null 且不序列化。
- **迁移**:幂等,InnoDB / utf8mb4,索引 `(task_id)`、`(biz_type, biz_id)`。`migration-dependencies.json` 的登记 `audit_problem_20261022: []` 格式正确。
- **事务**:涉及的入口都是 `@Transactional`;升级是基于旧值的 CAS,只升不降,不改 version。

---

## #533 F6 RPG P1(叠在 F2 #524 + F5 #521 上)

### BLOCKER
无。

### SHOULD-FIX(打开 rpg 灰度,或接入真实天气源之前)

**S533-1 魔鬼交易在校验 `requires` 前置条件之前就扣了代价**
- 位置:`chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TopicRouteRuntimeServiceImpl.java:3324-3345`。`bargain()` 只校验了到店和力竭,就执行 `applyJourneyEffects`;`roll()` 在 `:907-911` 校验了 `blockConfig.requires`,`bargain()` 没有。
- 失败场景:玩家在前置条件没满足的节点上接受交易,代价(例如 `counter.alarm +1`、`ADD_CONDITION`)已经提交;随后掷骰被拒「本站前置条件未满足」。代价白付,重放也只会返回同一张回执。
- 修复建议:`bargain()` 加上和 roll 相同的 requires 校验。

**S533-2 天气查询在写事务里同步执行,接口没有规定超时**
- 位置:
  - `TopicRouteRuntimeServiceImpl.java` 的 `checkContext` 和两条推进路径,都在 `@Transactional` 内调用 `refreshEnvironment`;
  - 它最终同步调用 `TopicEnvironmentService.java:85` 的 `weatherProvider.currentWeather(city)`;
  - `TopicWeatherProvider` 的接口约定只说「可以抛异常」,没有约定超时。
- 失败场景:以后接入一个标了 `@Primary` 的真实天气源,上游一慢,所有开了 `environment` 的主题,在缓存未命中(每个地址缓存 30 分钟)时,每次检定或推进都会占着 DB 连接等待,有可能把 Druid 连接池耗尽。
- 现状:默认的 `FixedTopicWeatherProvider` 不联网,所以目前安全。
- 修复建议:在接口契约里规定有界超时,或者把天气查询移到事务之外。

**S533-3 主题没有开交易,交易报价仍会下发给客户端**
- 位置:`chengyinhub-system/src/main/java/com/chengyinhub/business/util/AdvancedGamePublicProjection.java:516-521`。只要节点配了 `check.bargain` 就投影出 label 和 bonus,不看 `journeyRules.rpg.bargain.enabled`。
- 失败场景:作者写了 `check.bargain`,但主题的 bargain 没开,或者冻结的规则里 bargain 是关的。客户端照样展示报价,点了以后永远返回 `RPG_BARGAIN_DISABLED`。服务端会拒绝,不写任何数据,所以不是安全问题,但违背了「默认关」在 UI 上的含义。
- 修复建议:投影按规则判断,或者由校验器在主题未开 bargain 时拒收 `check.bargain`。

### NIT
- **N533-1**:动量用过之后再调 `/reroll`,`RPG_MOMENTUM_USED` 是 `ServiceException`,但 `ApiPlayCheckController.java:46-56` 只捕获 IllegalArgument/IllegalState,结果返回里丢了 `errorCode`。
- **N533-2**:`PlayJourneyReadServiceImpl` 的 `kindOf` 不认识 `CHECK_BARGAIN`、`CHECK_MOMENTUM`,交易代价和动量花费不会出现在属性日志里。
- **N533-3**:`GET /api/play/rpg/status` 不受开关约束,老主题也会返回 running/hp/luck,只是 `rpg.enabled=false`。返回的是本人数据,不泄露,但和 bargain/momentum 端点的拒绝口径不一致。
- **N533-4**:角色卡的 `rpg` 块读的是主题当前的规则(`ThemeCharacterService.java:110-111`),而状态栏和运行时读的是本局冻结的规则。作者在局中途改了主题后,两边显示会不一致。
- **N533-5**:天气按主题完整的 `address_name` 查询并缓存。接入真实天气源后会把完整场地地址发出去,缓存命中率也低。PR 已在 C-F6-5 中列为待定,建议改为传城市关键词或 city_code(O-14)。

### 已核查无问题
- **归属与重放**:
  - bargain 和 momentum 都经过 `requireCheckSession`(当前会员 + 主题 + 活动 + scope);动作查询按 `(session.id, memberId)`;动作 id 由服务端生成,玩家无法操作别人的局。
  - 动量花费量由服务端计算(`dc - total` 并封顶),客户端没法传数量。
  - 重复提交由固定动作 id、重复键重放和 `updateStateCas` 兜住,和 roll/reroll/settle 同一模式。
- **默认关**:新字段为 null 时都不序列化;老主题不 seed、不查天气、不多查库。有字面量测试锁住老规则的规范化结果和回执形状。
- **`sys.momentum` 只读**:效果层拒绝写 `sys.*`;交易代价只允许 SET/INC/ADD_TAG/ADD_CONDITION/TICK_CLOCK,XP、物品、luck 都拒收。以上都有测试。
- **边界**:bargain bonus 1–3,效果 1–4;momentum max ≤10;clocks ≤6;aspects ≤12;ENV_IN 只接受 4 个键,未知值 fail-open。
- **CI 改动**:
  - `backend-test.sh` 只新增了一个 IT。
  - HTTP e2e 用例计数 +1,对应一条真实的新用例。
  - 三个既有 MySQL IT 只是把 attribute_def 迁移加进了 schema 准备步骤,不是弱化。

---

## 建议合入顺序

1. #530 先合。
2. 再合 #533:与 #530 无冲突,合入后 diff 只剩 F6。
3. #531 和 #532 各自 rebase 或合入 master 时,解决上表的两处纯追加型文本冲突。

所有 SHOULD-FIX 都在默认关闭的开关后面,不阻塞合入,但必须在打开对应开关之前修完。
