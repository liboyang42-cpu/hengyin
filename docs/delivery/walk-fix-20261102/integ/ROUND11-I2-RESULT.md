# 第十一轮 i2-real-storage 结果

分支 `integ/launch-test-20261103`。起点 `573c463b3`(Real Storage IT `extra-storage` 运行 [41950279540](https://github.com/liboyang42-cpu/hengyin/actions/runs/41950279540) 红 4 类 + Batch summary)。
对照基线 master `c6eac3b8c`。本 lane 只改测试夹具/断言,**未改任何生产代码、未改任何金额规则、未跳过/删除/放宽任何测试**。

本 lane 提交(均已 pull --rebase 后普通 push,无 force):

| 提交 | 内容 |
|---|---|
| `474e81e9` | B04 / E01_10 / GameplayReskin / CoopServiceRealDb 四类夹具 |
| `45c47543` | ClubLeadChapterIntentMySqlIT 夹具、TopicContentGraphMySqlIT 文案断言 |
| `a6ddb292` | TopicContentGraphMySqlFixture 补 cancellation_epoch 迁移(AuditTopicCurrentPublicationMySqlIT) |
| `82bf5112` | ThemeStateKeyDeclarationMySqlIT、TopicContentGraphMySqlFixture 补 theme_attribute_registry_version 迁移 |
| `4fda6410` | CrossDomainStorageFixture 补官方广播协议合并迁移(最终代码提交) |

分类口径:**A = 真 bug**;**B = 近期 lane 弄坏的测试/夹具**(master 绿、本分支红);**C = master 上已经红**(在 master `c6eac3b8c` 上同 workflow 跑出同样失败)。

## 一、任务点名的四类(extra-storage)

| 测试 | 原因 | 分类 | 修复 | 证据 |
|---|---|---|---|---|
| B04ScheduleReadbackMySqlIT | C-05(`0fb39346`,本分支)给 `club_role_assignment` 加 `customer_phone_visible`,`ClubRoleAssignmentMapper` 读该列;本 IT 临时库只建 20260823 原表 → `Unknown column 'customer_phone_visible'`,权限拒绝路径变成 BadSqlGrammar | B | 临时库按 `migration_20261102_club_checkin_phone_visible.sql` 补列(与 `ClubRoleHttpMySqlIT` 的 `52ea4332` 同法) | master 绿:[41953109055](https://github.com/liboyang42-cpu/hengyin/actions/runs/41953109055);修后绿:[41953604641](https://github.com/liboyang42-cpu/hengyin/actions/runs/41953604641) |
| E01_10Test | `f301b763`(已在 master)把系列读取 `listSeries/getSeries/listSeriesOccurrences` 从 `readOnly=true` 改为读写 RR(授权行锁 `FOR UPDATE`,只读事务会被 MySQL 拒绝;B04 同提交已写负控)。E01_10 仍断言 `isCurrentTransactionReadOnly()==true`,3/3 红 | C | 断言改为跟随生产合同:事务仍须 active、RR,且**非只读**(`assertFalse`),快照/刷新断言全部保留 | master 红:[41953109055](https://github.com/liboyang42-cpu/hengyin/actions/runs/41953109055);修后绿:[41953604641](https://github.com/liboyang42-cpu/hengyin/actions/runs/41953604641) |
| GameplayReskinMySqlIT | `ThemeStateKeyService.lockRegistry` 读写 `theme_attribute_registry_version`(`migration_20261026_*`,已在 master),夹具没跑这条迁移 → 表不存在 | C | `applyMigrations` 补 `migration_20261026_theme_attribute_registry_version.sql` | master 红:[41953109055](https://github.com/liboyang42-cpu/hengyin/actions/runs/41953109055);修后绿:[41953604641](https://github.com/liboyang42-cpu/hengyin/actions/runs/41953604641) |
| CoopServiceRealDbIT | `250c6c69`(已在 master)起 `createCoopOrder` 先 `lockInviteTopic`,主题不存在即「关联主题不存在」(fail-closed,符合意图)。夹具的邀约指向 `topic_id=500` 却从未插这行主题 | C | 夹具插入在架、招募期内的 `cms_topic` 500 行,并注入真实 `ClubCoopWindowPolicy`(与 `CoopInviteOriginalRequestMySqlIT` 同法,走真实 `RecruitmentWindowPolicy`)。定价断言(P_min 29.42 等)一字未动,**未碰金额规则** | master 红:[41953109055](https://github.com/liboyang42-cpu/hengyin/actions/runs/41953109055);修后绿:[41953604641](https://github.com/liboyang42-cpu/hengyin/actions/runs/41953604641) |
| Batch summary | 上面四类红导致 | — | 随四类修复 | `extra-storage` 全批 35 类绿:[41957498540](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957498540)(@`a6ddb292`) |

## 二、其余批次首跑发现的问题

首跑 60 个批次 @`474e81e9`(运行 #330–#389),再 @`a6ddb292` 全量重跑(#394–#453)。

| 测试(批次) | 原因 | 分类 | 修复 | 证据 |
|---|---|---|---|---|
| ClubLeadChapterIntentMySqlIT(architecture-day-batch1 / integrated-changes) | 同 B04:C-05 新列,临时库缺列 | B | 临时库补 `customer_phone_visible` 列 | master 绿:[41956735402](https://github.com/liboyang42-cpu/hengyin/actions/runs/41956735402);修后绿:batch1 @`a6ddb292` [41957537120](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957537120) |
| TopicContentGraphMySqlIT(topic-authoring / integrated-changes) | P-15(`89ab0186`,本分支)把模板未过审拒绝文案改成「玩法…还在审核中，审核通过后才能用于发布」(用户裁决的可读文案;`702e41cd` 已同步单测),真库 IT 仍断言旧文案「尚未通过审核」 | B | 两处 `contains` 跟随新文案,仍断言具体拒绝原因 | master 绿:[41956728479](https://github.com/liboyang42-cpu/hengyin/actions/runs/41956728479);修后绿 @`82bf5112`:[41960466151](https://github.com/liboyang42-cpu/hengyin/actions/runs/41960466151) |
| AuditTopicCurrentPublicationMySqlIT(topic-content-budget / integrated-changes) | `casUserStatus` 推进 `cms_topic.cancellation_epoch`(`migration_20261029_*`,已在 master),共享夹具 `TopicContentGraphMySqlFixture` 未跑该迁移 | C | 夹具补 `migration_20261029_topic_cancellation_receipt.sql` | master 红:[41956738957](https://github.com/liboyang42-cpu/hengyin/actions/runs/41956738957);修后绿 @`82bf5112`:[41960469874](https://github.com/liboyang42-cpu/hengyin/actions/runs/41960469874) |
| ThemeStateKeyDeclarationMySqlIT(integrated-changes) | 同 GameplayReskin:缺 `theme_attribute_registry_version` | C | 补 20261026 迁移 | master 红:[41960406788](https://github.com/liboyang42-cpu/hengyin/actions/runs/41960406788);修后绿 @`4fda6410`:[41962200241](https://github.com/liboyang42-cpu/hengyin/actions/runs/41962200241) |
| TopicPublicationBindingMySqlIT(integrated-changes) | 同上,经 `TopicContentGraphMySqlFixture` | C | 夹具补 20261026 迁移 | master 红:[41960406788](https://github.com/liboyang42-cpu/hengyin/actions/runs/41960406788);修后绿:[41962200241](https://github.com/liboyang42-cpu/hengyin/actions/runs/41962200241) |
| CrossDomainFaultIsolationMySqlIT(integrated-changes) | 第九轮 m2 补充第 7 项(本分支)让官方订阅消息按广播行取落地页:发送前 `selectOfficialBroadcastById` 读全行(含 `client_request_id` 等 20261028 列)。夹具只跑 20261023 迁移 → BadSqlGrammar 在发送 try 内被记为「微信结果未知」,订阅从未发出,毒丸闸观测不到 | B | 按 `OfficialBroadcastDeliveryMySqlIT` 同顺序补 `migration_20261028_official_broadcast_protocol_union.sql`(生产部署本就需要这条迁移) | master 绿:[41960406788](https://github.com/liboyang42-cpu/hengyin/actions/runs/41960406788);本分支红:[41957672629](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957672629)、[41960462983](https://github.com/liboyang42-cpu/hengyin/actions/runs/41960462983);修后绿 @`4fda6410`:[41962200241](https://github.com/liboyang42-cpu/hengyin/actions/runs/41962200241) |
| **PlayLeadAdmissionFailureMySqlIT**(lead-admission / integrated-changes) | 4/4 红:把 `club_lead_state` 改名制造带领存储故障后,`unlockHint` / `answer` / `submitPreference` 不再抛 503「带领状态暂时不可用」(GET `preference` 仍抛)。master 上同样 4/4 红,签名一致 | C | **未修**。需追查哪条玩法入口在带领闸之前就返回(候选:`PlayBasicIntentApplication.executeBasicIntent` 的原局身份/版本闸先返回 409/400 而不抛;分支上另有 P-13 `c6b2f8db` 让自由探索不读带领态,hint/answer 夹具用的正是 product_type=2)。可能是夹具漂移,也可能是带领故障被绕过的真问题,未能在本 lane 内定性,不敢动断言 | master 红:[41956731939](https://github.com/liboyang42-cpu/hengyin/actions/runs/41956731939);本分支红:[41957583233](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957583233)、[41962200241](https://github.com/liboyang42-cpu/hengyin/actions/runs/41962200241) |

没有发现需要改生产代码的真 bug(A 类);没有任何修复涉及金额规则。

## 三、全部批次在最终提交上的结论

最终代码提交 `4fda6410`。`a6ddb292` 之后的三个提交只改测试夹具,影响面(按 `ci/real-storage-it-registry.json` 求类交集)只落在 topic-authoring、topic-content-budget、funds-closure、integrated-changes 四个批次,这四批已在 `82bf5112` / `4fda6410` 上重跑;其余 56 批的类与源码在 `a6ddb292`→`4fda6410` 之间未变,结论取 `a6ddb292` 全量运行。**严格说,并非 60 批都以 `4fda6410` 为 source_sha 重跑过**;差异只有上述测试文件。

| 批次 | run | 结论 |
|---|---|---|
| payment-intent-correctness | [41957474519](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957474519) @a6ddb292 | success |
| core-funds | [41957477866](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957477866) @a6ddb292 | success |
| ai-quota | [41957481437](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957481437) @a6ddb292 | success |
| e04-07-coupon | [41957484701](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957484701) @a6ddb292 | success |
| community-e02 | [41957488470](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957488470) @a6ddb292 | success |
| b05-visibility | [41957491862](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957491862) @a6ddb292 | success |
| f3-growth | [41957495128](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957495128) @a6ddb292 | success |
| extra-storage | [41957498540](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957498540) @a6ddb292 | success |
| port-core-funds | [41957501868](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957501868) @a6ddb292 | success |
| port-ai-quota | [41957505415](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957505415) @a6ddb292 | success |
| port-f3-growth | [41957508362](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957508362) @a6ddb292 | success |
| port-f3-evidence | [41957511650](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957511650) @a6ddb292 | success |
| port-e04-07-coupon | [41957514936](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957514936) @a6ddb292 | success |
| port-community-e02 | [41957518072](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957518072) @a6ddb292 | success |
| port-b05-visibility | [41957521241](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957521241) @a6ddb292 | success |
| port-extra-storage | [41957524436](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957524436) @a6ddb292 | success |
| funnel-max-date | [41957527365](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957527365) @a6ddb292 | success |
| port-prelaunch-69 | [41957530679](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957530679) @a6ddb292 | success |
| p0-acceptance-ac1 | [41957534011](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957534011) @a6ddb292 | success |
| architecture-day-batch1 | [41957537120](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957537120) @a6ddb292 | success |
| architecture-day-wave2 | [41957545473](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957545473) @a6ddb292 | success |
| architecture-day-photo | [41957548597](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957548597) @a6ddb292 | success |
| architecture-day-broadcast-migration | [41957551638](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957551638) @a6ddb292 | success |
| architecture-day-settlement | [41957554625](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957554625) @a6ddb292 | success |
| architecture-day-state-recovery | [41957557389](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957557389) @a6ddb292 | success |
| architecture-day-runtime-governance | [41957560300](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957560300) @a6ddb292 | success |
| architecture-day-registration-write | [41957563102](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957563102) @a6ddb292 | success |
| architecture-day-im-revocation | [41957566320](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957566320) @a6ddb292 | success |
| architecture-day-w19-owner-notice | [41957569642](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957569642) @a6ddb292 | success |
| architecture-day-play-scheduler | [41957573007](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957573007) @a6ddb292 | success |
| architecture-day-cash-expand | [41957576493](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957576493) @a6ddb292 | success |
| architecture-day-ai-not-sent | [41957579924](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957579924) @a6ddb292 | success |
| architecture-day-lead-admission | [41957583233](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957583233) @a6ddb292 | failure(仅 PlayLeadAdmissionFailureMySqlIT,C 类;PlayHintUnlockMySqlIT 绿) |
| architecture-day-topic-copy | [41957586626](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957586626) @a6ddb292 | success |
| architecture-day-topic-authoring | [41960466151](https://github.com/liboyang42-cpu/hengyin/actions/runs/41960466151) @82bf5112 | success |
| architecture-day-theme-intent | [41957593952](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957593952) @a6ddb292 | success |
| architecture-day-deposit-attempt | [41957597158](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957597158) @a6ddb292 | success |
| architecture-day-payment-inbox | [41957600190](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957600190) @a6ddb292 | success |
| architecture-day-verified-payment-facts | [41957603023](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957603023) @a6ddb292 | success |
| architecture-day-registration-seats | [41957605523](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957605523) @a6ddb292 | success |
| architecture-day-original-refund | [41957608314](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957608314) @a6ddb292 | success |
| architecture-day-topic-content-budget | [41960469874](https://github.com/liboyang42-cpu/hengyin/actions/runs/41960469874) @82bf5112 | success |
| architecture-day-committed-official-notice | [41957622002](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957622002) @a6ddb292 | success |
| architecture-day-complaint-committed-notice | [41957624751](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957624751) @a6ddb292 | success |
| architecture-day-complaint-notice-rejection | [41957627869](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957627869) @a6ddb292 | success |
| architecture-day-chapter-application-round | [41957630929](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957630929) @a6ddb292 | success |
| architecture-day-citynode-original-receipt | [41957633797](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957633797) @a6ddb292 | success |
| architecture-day-registration-topic-fence | [41957636580](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957636580) @a6ddb292 | success |
| architecture-day-topic-cash-fence | [41957640036](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957640036) @a6ddb292 | success |
| architecture-day-scheduler-two-process | [41957643506](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957643506) @a6ddb292 | success |
| architecture-day-payment-intent | [41957646778](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957646778) @a6ddb292 | success |
| architecture-day-cash-recovery | [41957649552](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957649552) @a6ddb292 | success |
| architecture-day-npc-voice-asr | [41957652408](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957652408) @a6ddb292 | success |
| architecture-day-clm-commerce-proof | [41957655666](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957655666) @a6ddb292 | success |
| architecture-day-media-retry-budget | [41957658066](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957658066) @a6ddb292 | success |
| architecture-day-before-start-notice | [41957660537](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957660537) @a6ddb292 | success |
| architecture-day-commerce-generic-guard | [41957663212](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957663212) @a6ddb292 | success |
| architecture-day-funds-closure | [41960473325](https://github.com/liboyang42-cpu/hengyin/actions/runs/41960473325) @82bf5112 | success |
| architecture-day-refund-lifecycle | [41957669651](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957669651) @a6ddb292 | success |
| architecture-day-integrated-changes | [41962200241](https://github.com/liboyang42-cpu/hengyin/actions/runs/41962200241) @4fda6410 | failure(仅 PlayLeadAdmissionFailureMySqlIT,C 类;76 类绿) |

主线 CI:`82bf5112` 的 GitHub CI 绿([41960473679](https://github.com/liboyang42-cpu/hengyin/actions/runs/41960473679));`4fda6410` 的 GitHub CI 被其后其它 lane 的推送(`748f6519`、`94ce33a6`)按并发组取消,未单独出结论,`4fda6410` 只改一个测试夹具。

对照 master 运行一览:[41953109055](https://github.com/liboyang42-cpu/hengyin/actions/runs/41953109055)(extra-storage 四类)、[41956728479](https://github.com/liboyang42-cpu/hengyin/actions/runs/41956728479)(topic-authoring)、[41956731939](https://github.com/liboyang42-cpu/hengyin/actions/runs/41956731939)(lead-admission)、[41956735402](https://github.com/liboyang42-cpu/hengyin/actions/runs/41956735402)(ClubLeadChapterIntent)、[41956738957](https://github.com/liboyang42-cpu/hengyin/actions/runs/41956738957)(AuditTopic)、[41960406788](https://github.com/liboyang42-cpu/hengyin/actions/runs/41960406788)(integrated-changes 三类)。另有一次误填 SHA 的 master 派发 41953086998 已立即取消,无结论。

STATUS: PARTIAL - PlayLeadAdmissionFailureMySqlIT 在 master c6eac3b8c 上同样 4/4 红,带领存储故障未在 hint/answer/submitPreference 入口抛 503,未能在本 lane 内判定是夹具漂移还是带领闸被绕过,未修;且仅 4 个受影响批次以最终提交 4fda6410/82bf5112 重跑,其余 56 批结论来自仅测试文件不同的 a6ddb292。
