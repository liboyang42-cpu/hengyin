# BUG-STORY 上线前修复交付（story engine / endings / templates / game runtime）

分支：`fix/bug-story-20261023`（自 master `b84ed673` 切出）。所有结论都按当前 master 代码重新核对过。

## 汇总

| 分类 | 数量 | ID |
|---|---|---|
| FIX | 6 | B:P1-071, B:P2-033, B:P2-042, C:U-151, C:U-165, C:U-173 |
| OWNER（已做安全的兜底部分） | 4 | A:P0-43, C:U-054, C:U-019, C:U-170 |
| ALREADY_FIXED | 3 | A:P0-18, B:P1-024, C:U-034 |
| NOT_REPRODUCIBLE | 4 | A:P0-25, C:U-064, C:U-040, C:U-018 |
| DEFER | 13 | B:P1-032, B:P1-035, B:P2-023, B:P2-024, C:U-033, C:U-046, C:U-057, C:U-070, C:U-079, C:U-152, C:U-166, C:U-169, C:U-175 |

## 明细

| id | triage | what changed (file) | test | notes |
|---|---|---|---|---|
| A:P0-18 | ALREADY_FIXED | — | `AppMediaPublicationGuardTest`（现有） | 模板的 imgUrl/storyImg/audioUrl/questionImg/questionAudio/medalImg 在发布、草稿、据点模板保存时都会经过 `AppMediaPublicationGuard.requireTemplate`（`TemplatePublishServiceImpl.java:114,417,642`），任意外部 URL 解析不到本人或已审核的媒体资产会被拒（`AppMediaReferenceGuard.requireAttachment`）；只有站内 `/images/`、`/assets/` 和内置音频可直接通过。等于已经有一份白名单。 |
| A:P0-25 | NOT_REPRODUCIBLE | — | — | 小程序的 requestId 按动作加前缀生成（`pg-role-`/`pg-`/`pg-submit-` 加时间戳，`pages/play/index.js:2417,2738,2797`），不同动作不会撞同一个 id。服务端对同 id 不同动作返回结构化的 `GAME_IDEMPOTENCY_CONFLICT`，这是正确的幂等行为；如果把 action 加进唯一键，同一个 request_id 就能执行两次不同的命令，幂等反而变弱。不做迁移。 |
| A:P0-43 | OWNER | `TemplatePublishServiceImpl#updateTemplateFromLibraryProjection` 忽略表单带来的 `useNum` | `TemplatePublishAuditQueueTest.libraryEditResetsOnlyProjectionThenResubmitsMarketAudit`（新增断言） | 已做的安全部分：后台编辑广场投影时不再能用表单旧值覆盖首次采用自增出来的采用数。需要产品决定：是否增加独立的 ranking_hint/行为计数列，以及广场排序公式（运营权重和行为分各占多少）。 |
| B:P1-024 | ALREADY_FIXED | — | — | 转让/解散已在 `selectClubByIdForUpdate` 行锁内执行，转让是条件 CAS（`ClubGovernanceMapper.xml:174-179`，按预期 owner 加 status/del_flag 判断）；并发场景下不会出现双写。club 表加 version 列只是给客户端做过期视图检测的增强，没有用户可见的缺陷，放到上线后做。 |
| B:P1-032 | DEFER | — | — | 纯代码整洁问题。已核对快照字段清单里的每个字段，至少在 CmsMemberTemplate 或 CmsTemplateLibrary 其中之一上有 getter；某一侧缺字段时写成 null，两边比较口径一致，不影响审核比对。 |
| B:P1-035 | DEFER | — | — | 作者看审核结果、发起申诉的功能已经在 master 上（通用 AuditAppeal）；剩下的只是快照常量的写法，同 P1-032。 |
| B:P1-071 | FIX | `TopicChapterVO#setNodes` 按下发的节点现算 `locationCount`/`templateCount` | `TopicChapterVOCountTest`（新） | 原来章节列只在整题保存时写入，单独增删节点后会过期，主题详情里「N 个玩法」显示错误。现在三个主题详情接口在 `setNodes` 时统一按实际节点覆盖，隐藏节点不下发也就不计入。 |
| B:P2-023 | DEFER | — | — | 这是 CI 建库工具链的问题：CI 和生产都按「基线 + 迁移台账」建库，player_node_progress 的列由迁移补齐，运行时不会漂移。重新生成基线、做启动期列对比属于工程整洁项。 |
| B:P2-024 | DEFER | — | — | 两条对话链路的 prompt 已经分开，单个 prompt 里不再重复；统一边界文案只是措辞整理。 |
| B:P2-033 | FIX | `AiGatewayServiceImpl`（日键）、`AiCreatorServiceImpl`（玩家专项额度日键/TTL）、`NpcChatService#secondsUntilTomorrow` 固定按 `Asia/Shanghai` 计算 | `AiGatewayServiceImplTest.dailyQuotaKeyUsesBeijingDateEvenWhenJvmDefaultZoneDiffers`（新）；`AiCreatorServiceImplTest.playerThemeDraftCountsOnlyCompleteRealDraft` 的 TTL 期望改为按北京时间计算 | 不再依赖部署时的 `-Duser.timezone` 参数。生产环境本来就带这个参数，所以对生产行为没有变化。 |
| B:P2-042 | FIX | `AiCreatorServiceImpl#themeDraftQuota` 玩家分支剩余次数取 min(专项额度, 网关 gen 池) | `AiCreatorServiceImplTest.playerThemeDraftQuotaReportsZeroWhenSharedGatewayPoolIsExhausted`（新） | 原来 gen 池已经被其它创作用完时，玩家仍会看到「还剩 N 次」，点了才报额度用完。 |
| C:U-018 | NOT_REPRODUCIBLE | — | — | 广播的投递对象由 game_broadcast_delivery 按 ALL/TEAM/ROLE 写行控制（`insertBroadcastDeliveries`），内容在服务端限制为 200 字并过安全审核（`GameSessionRuntimeServiceImpl#broadcast`）。表上没有可见性列不会造成越权展示或写入失败。 |
| C:U-019 | OWNER | — | — | 「作废某个节点成绩并从排行中剔除」是新的产品规则（谁能作废、作废后积分/奖励怎么处理），需要产品确认后再建表。现有的 outcome 只记录完成状态，不涉及资金。 |
| C:U-033 | DEFER | — | — | 只影响「未开始/进行中/已完成」这个展示标签。退款资格和核销都以服务端字段为准（`refundInfo.refundable`、`verificationStatus`）；手机自动校时的情况下标签是对的。要用服务端时间需要改保留文件 request-client 和订单详情，放到上线后。 |
| C:U-034 | ALREADY_FIXED | — | — | `cancelOrder` 进入时已经统一做 `Number(dataset.status)`（`scene-member-order-history/index.js:305`），文案 key 和 URL 用的是同一个数值，不会出现分叉。 |
| C:U-040 | NOT_REPRODUCIBLE | — | 现有 `PlayJourneyStoryServiceImplTest` | 保存时要求结局恰好有一个 fallback，且结局列表不能为空（`JourneyStoryValidator.java:287-317`），所以 null 结局走不到。命中规则是按配置顺序取第一个，这个顺序是确定的，等于已经有 tie-break。启动期审计属于增强项。 |
| C:U-046 | DEFER | — | — | 两个入口已经共用 `utils/template-market.js#marketView` 推导状态，不存在两套口径；改成服务端下发 marketState 枚举属于重构。 |
| C:U-054 | OWNER | 同 P0-43（投影编辑不再能覆盖 use_num） | 同 P0-43 | 已有限流加「每个会员首次采用才计数」去重；是否单独建 real_use_num、排序是否改用它，需要产品决定。 |
| C:U-057 | DEFER | — | — | 16 条是旅程故事（journey story）的上限，100 条是玩法运行时 storyEndings 的上限，两者是不同的配置链，各自在保存时校验，不会互相导入。只需要补文档。 |
| C:U-064 | NOT_REPRODUCIBLE | — | — | 同 P0-25。 |
| C:U-070 | DEFER | — | — | 已核对：KIT_PRIORITY、TYPE_OF、KIT_TYPES 三张表一一对应（没有缺项），并由 playkit 合同测试守住。改由服务端下发段名是重构。 |
| C:U-079 | DEFER | — | — | 同 U-070。目录数和表项数不同是因为有辅助组件和别名（例如 steps 对应 walk），不是缺玩法。 |
| C:U-151 | FIX | `GameSessionRuntimeServiceImpl`：准备/保存校验时要求 `story.variables` 和旧写法（对象）的 `effects` 的值都是整数，并禁止写保留键 `__tags` | `GameSessionRuntimeServiceImplTest.storyVariablesAndObjectStyleEffectsMustBeIntegersSoRouteConditionsSeeThem`、`prepareRejectsNonIntegerStoryVariableBeforeCreatingSession`（新） | 原来旧写法可以把变量写成 true 或 "1"，数组写法的结局条件读取时会静默丢掉这些值，结果走错结局。现在这种配置在保存/开局时就以 `GAME_CONFIG_INVALID` 拒绝，运行时的变量一定是数值。 |
| C:U-165 | FIX | 同 U-151 | 同 U-151 | 同 U-151：非数值变量在配置期就被拒绝，运行期不会再静默丢弃。 |
| C:U-152 | DEFER | — | — | 主题运行时已经把终止原因写进 state_json；单独加一个可查询列是运维观测需求，玩家看不到。 |
| C:U-166 | DEFER | — | — | 作者写错 thought key 时，内心独白不会出现，不会报错也不会卡流程；全题静态检查属于创作期的 lint 增强。 |
| C:U-169 | DEFER | — | — | 三条链路各自在保存时校验「恰好一个兜底」，结果一致；合并成一个 EndingSelector 是重构。 |
| C:U-170 | OWNER | — | — | 主题级的章数/节点数上限是产品数值（多少算合理），需要产品给出；现有的单章、文本、字节上限仍然生效。 |
| C:U-173 | FIX | `ApiMedalController#wall` 成就徽章按 badge_code 去重 | `ApiMedalControllerTest.wall_shows_one_achievement_per_badge_code_even_when_granted_for_several_sources`（新） | TOPIC_CLEAR 按主题发放，通关 3 个主题时勋章墙会出现 3 枚一样的「通关」，计数也虚高。现在和徽章墙 v2（`BadgeReadModelServiceImpl` 按 code 去重）口径一致。 |
| C:U-175 | DEFER | — | — | 两种条件写法（对象/数组）都有保存期校验；U-151 修复后两种写法对变量类型的理解一致。统一成数组写法需要数据迁移，属于重构。 |

## 验证

- 本地：`mvn -pl chengyinhub-admin -am test -Dtest=TopicChapterVOCountTest,TemplatePublishAuditQueueTest,AiGatewayServiceImplTest,AiCreatorServiceImplTest,GameSessionRuntimeServiceImplTest,ApiMedalControllerTest`，共 234 个测试全部通过。
- 没有新增迁移，没有改动小程序文件，也没有改动 C00 保留文件，所以没有 CY-HANDOFF。台账没有重新生成。
- CI 运行记录：见 PR 检查（下文补充 run ID）。
