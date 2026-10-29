# 玩法完成事实：集合与消费者合同

结论：到达、题目验证、节点完成、主题完成、权益核销是五个不同事实；正常与兜底完成、主题达成与奖励资格还需分别保留。已有 play_instance_id、source outcome、路线榜及探索核销分流必须保留。证据 E13–E24，测试源定位见 `source-evidence.json`/`caller-inventory.txt`。

## 粒度与权威来源

统一比较记录使用 `(source, actorType, actorId, topic/activity, session/instance, frozenVersion, objectId, factType)`；这是本报告的对账投影规格，**没有新增事件框架或生产表**。只在明确映射同一事实时归并，绝不把不同源行数直接相加成人数。

| 事实 | 权威事实/身份 | 正常/兜底、回执与消费者 |
| --- | --- | --- |
| ARRIVAL | `player_node_progress.checkin_time` 的服务端受控到达凭证；generic 有 `topic_route_action` TASK_ARRIVAL。actor=member，activity/topic/node/play_instance | 进店可 status=0，不等于完成；经典某些入口到达同时合法完成，需独立投影两个事实。FREE_EXPLORE 商家进店认静态扫码，不用 GPS；不能把任意 checkin 时间当受信定位。消费者：地图、队列/到场统计、任务准入。 |
| TASK_VERIFIED | generic 路线 `state_json.themeRuntime.verifiedNodes` 或 activeOperation.verified，受冻结任务/attempt 约束；game submission APPROVED 可构成验证 | `TASK_FACT/OPERATION_FACT` 服务端 action receipt；PENDING/REJECTED 不成立。照片 RECORDED 只有冻结 completionPolicy=EVIDENCE_ONLY 才可由 outcomeForSubmission 产生 COMPLETED，不泛称“RECORDED 永不完成”。消费者：QUEST_RESOLVE 准入/任务 effects，不自动触发旧节点奖。 |
| NODE_COMPLETED | classic `player_node_progress.status=1`、del_flag=0，键 member/topic/node/activity/play_instance；generic 由冻结任务 resolve 后的状态/动作判断，不强造 progress=1；game `game_player_node_outcome` | outcome 唯一 `(game_session_id,member_id,node_id)`，带 teamId/stationRunId、sourceSubmissionId/sourceReceiptId。COMPLETED 来源 AUTO_HASH/MERCHANT_APPROVAL/EVIDENCE_ONLY；FALLBACK_COMPLETED 来源 ANSWER_REVEAL/FALLBACK_PLAN，附 plan code/version；首次终态不覆盖。消费者：线性榜、节点解锁、任务/站点看板、奖励候选。 |
| THEME_COMPLETED | 经典线性按 complete_rule_json 与合法完成节点；branch/generic 看当前 `topic_route_session.status=1`（2 ENDED 不同）；game_story_state 需明确 ownerType/Id、status=ENDING_RESOLVED/endingCode 与冻结结局意义 | route 带 configVersion/configSnapshotJson、playInstance、CAS version、completedAt 和 topic_route_action；game 带 gameSession.module_config_snapshot_json、story owner/revision 及 game_command_receipt。TEAM 剧情终态不是每位成员的个人完成行；WORLD/控制状态不可误算玩家。消费者：榜、已完成列表、分享资格、结束页；结局种类和奖励资格另判。 |
| ENTITLEMENT_REDEEMED | `registration_entitlement.status=1`，唯一 `(registration_id,chapter_id)`；经 `PerkLedgerServiceImpl.redeemChapter` 同事务 CAS、配额、ACTIVE redemption 和 payable/claim | 权利持有人从 registration.memberId 取，核销操作员/商家是 actor 的另一个角色；不按 play_instance 重置权益。退款失效只把 pending(0)→2，已得(1)保留事实；撤销需保留流水，恢复 pending 还要求订单 paid。消费者：商家供给型探索票通关、容量、章节账本；纯内容分支不制造商家核销。 |

player_node_progress 没有独立 frozenVersion 和 sourceReceiptId 字段；有路线局时可追溯该局快照/动作，历史独立行不得凭 live topic 补造冻结证据。GamePlayerNodeOutcome 没有 play_instance_id，使用 gameSessionId/站点配置隔离，不能硬 join 到 default 实例。story TEAM/WORLD 需按冻结成员/owner 关系映射，不能以操作者冒充所有受益人。

## 逐指标期望集合

令 U 为已授权且具有有效来源回执的事实集；C 为所查 current session/instance；H 为历史集。以下每项单独去重，既看期望缺失 `Expected−Actual`，也看误计 `Actual−Expected`，不只比 count。

| 指标/消费者 | 期望集合与断言 | 排除/注意 |
| --- | --- | --- |
| 当前局到达节点 A | `{(member,topic,activity,instance,node) ∈ U : ARRIVAL且属于C}` | 重放同回执仅一条；不把 status=0 算完成，不把其他 topic/activity/旧局带进来。 |
| 当前任务验证 V | `{(owner,session,taskOrOperation,attempt) : frozen rule通过且回执提交}` | attempt 变化不能借旧验证；验证成功但未 resolve 属 V，不直接属 N。 |
| 经典完成节点 Nclassic | `{(member,topic,activity,instance,node) : progress.status=1且del=0且属于C}` | replay 保留本局进度但不再派发平台积分/券/成长值/排名奖牌；全局默认首次奖励 key 不因新 instance 放开。 |
| game 正常/兜底 Nnormal/Nfallback | `{(session,member,node):outcome.status=COMPLETED}` / 同键 FALLBACK_COMPLETED | 同一节点首次终态互斥；同 member 不同节点可分别出现在正常、兜底人数集合，**两者人数不能直接相加**。来源、submission、receipt、fallbackPlanVersion 均保留。 |
| current 主题完成 Tcurrent | `{(source,ownerType,ownerId,scope,instance):受该冻结玩法定义确认完成且属于C}` | route status=2 不当成功；节点数=0 的 generic 也可路线成功；game_story 的结局已解析需保留 endingKind，不能默认为可派奖成功。 |
| 历史主题完成 Thistory | `{同上:曾产生完成终态}`，保留全部实例；人数需再投影 distinct member | 与“一主题一榜取最新局”不同问题，不应用 current 查询替代历史；历史回填零奖励。 |
| 探索权益 R | `{(registration,chapter):entitlement=1且能追 ACTIVE redemption}`；不以完成游戏代替核销 | status=2 无新增核销资格；退款已核销的历史仍留痕，不因此再次服务。 |
| 探索票通关 Tpass | 商家供给且无自定义规则时：`{registration:必选章非空且每章有ACTIVE redemption}`；有合法 complete_rule_json 时：按票满足 distinctMerchantRoles/needGroups 阈值；严格 pure-content 时走必选章服务端游戏完成集合（E23） | 按票比较，不能把两张票各半权益拼为一票；自定义规则损坏必须 false；零商家纯内容不制造 entitlement 已核销/redemption。未来冻结章节版本与 live 必选章差异需验真。合成 tickets 夹具只覆盖第一类。 |
| 奖励资格 Q | 节点：`{completion:默认首次且对应policy/来源/品类允许且奖励业务键尚未占用}`；主题：分别依勋章/积分/券/XP 发放事实与合法冲回标记判重（E24） | Q 不等于所有完成；device自报/兜底/静态码相关门按已批准 XP_BADGE_ONLY；照片不自动发模板真金券；FREE_COLLECT 不继承经典积分/券/掉落链。已实现的合法撤销冲回后补发需消费标记，不能与“历史投影不发奖”混淆。 |
| 一主题一榜 | branch/generic 选最新 route session：status=1 优先、用时、当前局节点数；线性按当前局 progress 完成数/末次时刻 | 基线已实现 D-13；不能为修显示造 status=1。game 汇总已分 ROUTE progress 与非 ROUTE outcome，且分 normal/fallback（E20）。 |
| 运营完成率 | TopicOnsiteMetrics 现有分母按已到服务窗的 paid 或开场后退款申请；完成为 redeemedCount>=required，完成率分母 arrived | 这是票/服务指标，不是任务解题成功率或剧情结局率；不能与 N/T 混为同一完成率。 |
| 团队完成 | `{(gameSession,team):冻结/获准站点集合非空且各节点有有效团队完成}`；个人贡献另列 `{team,member,node}` | 站点数当前 SQL、团队成员资格及快照时点需受控夹具核对；不能把一位 actor 的 outcome 自动复制成全队奖励/核销。 |

## 已有保护、可核验差异与优先级

| 发现 | 静态证据与影响 | 门槛 |
| --- | --- | --- |
| 已修：多主题/多实例隔离 | progress domain/DDL/service/mapper 有 play_instance_id，当前读选最新 instance；自玩 topic 收窄；不能声称字段全部缺失 | 保留，重放与跨局夹具回归。 |
| 已修：四种经典完成入口 | scan/photo/answer/advanced 都能经 advanceProgress 写 status=1；ApiPlayProgressCompletionTruthTest 有专门四项证明 | 仅存在测试源码，本轮没运行，不重做补丁。 |
| 已有：分源统计/榜 | GameSessionRuntimeMapper 正常/兜底、ROUTE/non-ROUTE 分支；LeaderboardRankPolicy generic 走会话榜 | 不直接当“全部统计已完备”；每消费者仍比较集合。 |
| 显示候选缺口：纯 route 无 progress=1 | myCompleted 先由 status=1 progress 和探索票构造候选，再为这些候选查路线状态；generic 仅 status=0 凭证且无探索票时，已完成会话可能进不了候选。已有 selfplay 分支，不能误报“所有自玩被排除” | 先补只读候选夹具。如果只用于列表，可后续查询归位；若该列表承担分享/成就可领奖授权，必须上线前修消费者合同。G 不改共享 Controller。 |
| 危险 fallback：FREE_EXPLORE 无 explore 订单 | isTopicCompletedFor(explore=null) 回落 live 节点规则；isCompletedCompletionFact 对线性 FREE_EXPLORE 也可调用该回落。少字段/无票不能成为核销或通关奖励资格；这不同于有 paid 活动票且经严格 isPureContent 判定的合法纯内容分支 | 上线前核实全部调用路径；可达经济/权益/奖励出口时必须 fail-closed。尚无用户不要求兼容；G 不越权动奖励入口。 |
| scope/跨源差异进入结算 | completion、submission、story ending 均不能代替订单绑定扫码/团核验/redemption | 所有影响受益人/金额/核销的差异上线前修，禁止只投影补行消除差集。 |
| game ROUTE 统计未显式 instance | 汇总 progress 按 activity/topic/station/member 过滤，与个人 current-instance 口径不同 | 若是全场历史展示可能有意；若承载竞赛/奖励资格须上线前明确并验证不能把旧局完成拼成当局全站完成。 |
| 冻结版本/成员和 live 配置 | progress 无独立版本，线性通关/必选章查询可能读 live；story 团队成员展开不可自行决定 | 对现金/奖励/核销必须明确冻结来源并加负控；仅历史报表的版本归位可后续处理并保留 unknown。 |

## 原 D5–D6 已由既有规则收敛

- **D5：不再提问。** 既有 docs/theme-runtime/README.md 明确 success=false 的 ENDED 不计主题通关；终局发放按冻结 endingId/reasons/success，默认成功结束发放。保持首次正常/兜底结果及奖励上限，不因到过结局重新评估或补奖。
- **D6：本轮不新增个人展开语义。** 原 game 依据冻结 runtimeScope/TEAM/WORLD 记录，个人路线按 member/session/instance。新查询仅返回本人路线事实；团队不会被复制成个人经济资格。未来若明确要求新投影再列具体需求，不阻塞本轮来源查询。

`implementation.md` 已提供独立 reader/mapper 和来源差集 Java 测试源码；C 的候选并集迁移与无票 fallback 修复仍未实施。14 项 Java 测试未运行，不能把源码预期称为实际验证。

只读重算的安全边界：先生成差集及来源引用，保留 unknown/重复映射冲突，再由独立消费者读取。历史回填不调用玩法写入口、不发奖、不核销、不结算、不补造 source receipt，不删除或改写首个完成终态。
