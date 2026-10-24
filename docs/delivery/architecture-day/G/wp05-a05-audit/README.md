# G：WP05 / A05 三 runtime 完成事实与消费闭环只读切片

结论：**不能报告“三种 runtime 全部未镜像、所以统计全部漏算”**。Legacy 写 progress=1；Game非ROUTE写正常/兜底 outcome，运营SQL已有分源分支；Generic个人/TEAM任务完成主要在本局owner状态与原动作回执，基础到达还会写 progress=0。已有my-completed/俱乐部主题看板读取route session，商家到店/核销metrics本来读取arrival/redemption，不能改成解题人数。当前仍有具体兼容读取/重玩隔离缺口，下表区分源码能证明什么与尚未真验什么。**本批不报WP05完成**。

## 1. 实际基线、边界与证据等级

- 唯一公共仓库 `liboyang42-cpu/hengyin`。实际工作树 `/workspace/hengyin-g-wp05-audit`、分支 `codex/architecture-day-g-wp05-audit-20261024`；父提交/被审源 `91962c94af463cbfe9b4d4406f9d88d269b097e4`，创建时干净。
- 已fetch `2b4024cdcb185c56ee05126e02b9a0c79fcff34a`，实际 `git diff --name-only ... -- '*.java'` 为空，仅证明该范围主Java一致，不将未读的新metadata当执行结果。
- 复用A `748ad71bd1de141396fd8e2d9685c35706fd5b84` 对0c0d源的写事实与语义边界；定向抽取原4条Progress写statement，原PlayerNodeProgressMapper/GameSessionRuntimeMapper两文件相较91962c无差异。A清单是源码能力观察，不是新授权baseline；未生成/扩大全仓扫描框架或gate白名单。
- 仅新增 `docs/delivery/architecture-day/G/wp05-a05-audit/`。C拥有play生产/Mapper，D拥有CmsTopic authoring；均未改。未创建Reader、未改SQL/schema/政策/消费者/旧runtime，未新增Agent。
- 本次实际：Git/rg、定向XML解析/include来源展开、调用点/页面字面量检索、hash、review patch `git apply --check`。**Java编译0、Java/MySQL/Node测试0、CI dispatch0、生产访问/DDL0**。SQL展开文本含if/choose的备选分支，只供源码定位，绝非可执行/已执行SQL。
- root通报G现金修后1e4852/run40049540229真实45/45 CLEAN与首次9d79的43pass2fixtureerror各自保留；它们不覆盖本玩法切片，不关闭资金共同fence/latefact/退款P1。

## 2. 有限穷举范围与文件导航

定向搜索production main目录对player_node_progress的字面引用，并展开这些XML中的include：**12个XML、28个SELECT、4个写statement**。对应SELECT方法的**72处Java点调用、28个Java文件**全部记录。接口/包装/重复调用分别保留，不能把72当72个API。另核对方法引用与直接Java表名引用；当前直接Java SQL未见player_node_progress字面DML，出现表名多是说明注释。动态运行时/外部classpath不由文本搜索证明。

- `RUNTIMES.md`：真实入口、事务/回执、原事实和镜像、时间/一次性奖/范围/冻结版本。
- `CONSUMERS.md`：source→statement→Java→API/页面及各指标含义，包含全部28条SELECT。
- `CONTRACT.md`：单一完成写owner、兼容读取端口、逐指标期望集合、最小施工/不触碰面/测试与迁移风险。
- `progress-statements.json` / `progress-java-call-sites.json`：逐statement行号/namespace/XML与全部点调用位置；Java method_candidate为词法最近声明，不冒充javac调用图。
- `A748-progress-write-facts.json`：A原观察4条，保留旧source身份；`client-call-sites.json`：定向页面字面量/共享client范围。
- `replay-arrival.review.patch`：复用ThemeRuntimeMySqlIT原夹具的两条未应用反例源码。只验证patch能应用；没有编译/运行，更未取得JUnit红例。
- `JAVA-CALL-SITES.md`：全部72处Java调用位置附录；`VALIDATION.md`：实际静态命令与明确未跑项。
- `source-identity.json` / `artifact-sha256.json`：实际读到的源码blob及交付物指纹。序列化XML-node hash和真实file hash明确分开。

## 3. 差异裁决：已知机制、技术债、需要真实数据的项

| ID | 源码事实与当前裁决 | 影响 / 下一验证 |
| --- | --- | --- |
| G05-01 | **可证写读实例不一致**：ShopDayProofService直接Mapper插到达，不设置playInstance；DDL默认default。读取在有member/activity时筛最新route实例。初局default正常；replay产生replay:<oldSessionId>后，可能写入旧实例而当前局看不到，或撞旧default键后当前读空而抛错 | 基础到达/后续验证/照片会受影响，不能用“加统计查询”掩盖。两条真MySQL反例patch已备，NOT_RUN；C应先核定并修原arrival写owner的实例绑定，保持status0、不发奖 |
| G05-02 | **可证事实源不一致，运行影响待原链验真**：TEAM Generic QUEST_RESOLVE由GameplayTaskEngine写completedNodeIds与slot，再保存game_story_state；不写progress1。Game.clubView对全部ROUTE走旧progress1团队投影/榜；recap同样ROUTE走progress1。clubTeams只拼角色，不从owner状态覆盖完成数 | 同ROUTE下普通经典和Generic TEAM不能用一个来源分支。ANY_N/排他组的合法完成也不等于“全部station有progress1”。应以现有GenericRoute真库夹具完成任务后读真实CLUB view/recap，不能把静态差异写成真实DB少算比例 |
| G05-03 | **确定的查询集合差异，合法重玩可达范围待验证**：纯内容探索单票Java按当前instance的progress集合判断；batch SQL selectPureContentExploreCompletion不分instance，可把不同局的各一半节点拼成完成 | 当前batch入口在PerkLedger.exploreCompletion→Stats.club，不直接发奖。补“没有任何一局全完、历史行合起来全完”的夹具；不得让显示true成为核销/领奖资格 |
| G05-04 | **可证时间字段含义不同**：markShopDayGameDone只改status，checkin_time仍到达时刻；photo机器CAS会改checkin_time=now；outcome有completed_at；Generic node完成靠首个slot/action，不能把任意action时间当首次完成 | leaderboard/Orient since/用时/medal.getTime需要明确timeKind，不能造统一completed_at值。真实排序/历史差异仍须DB夹具；本批不改旧口径 |
| G05-05 | **当前/历史口径技术债**：my-completed与route榜取latest session；Stats.club的route participant/completed SQL为历史任意session distinct member；ClubMember.duration按activity不分instance；funnel按progress行次含0/1和重玩 | 这些源码差异不自动等于错；需在兼容端口显式区分CURRENT_RUN、EVER、ARRIVAL_ROWS等。不能把所有读取换latest而抹掉历史；不猜全域唯一“完成率” |
| G05-06 | **局部旧阈值漂移但未观察到生产入口**：NpcMemoryBuilder.build以status>=2称完成，而主progress终态1。现NpcChatService只调用buildRoam；未找到build(member,activity)的生产点调用 | 记录为未消费旧helper/潜在未来调用风险，不能报当前NPC已经展示0完成或全域漏算。若后续启用先补状态1/0正负控 |
| G05-07 | **有意分域，不判少发奖**：Generic局内MEDAL/物资与Game outcome不是player_badge或模板勋章；ApiMedal.wall模板章只读progress1+live模板，成就章另读player_badge | 没有批准合同就不能把TEAM完成复制给每人、补发旧积分/券/XP/徽章；旧奖励与重玩抑制继续保留 |
| G05-08 | **没有“因新表而少算”的证据**：商家insight/roster与onsite metrics按paid探索票、到达和ACTIVE redemption聚合；Generic基础到达本来有progress0镜像；服务完成是核销章数 | 不引入Game解题outcome代替redemption；是否有历史缺到达、重复票、跨局时间、真实付费人口差异需真实DB，现无样本/占比 |

当前必须优先验证并修的技术边界是G05-01（写读隔离）和G05-02在Generic TEAM来源的真实投影；任何修补一旦影响奖励/核销/结算都要保留源回执及既有资格门槛。其余项目按明确consumer目的裁决，不能因读了旧表就扩大写面或删除旧runtime。
