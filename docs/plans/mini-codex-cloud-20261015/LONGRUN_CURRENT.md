## 本轮真实源码继续交付：CORE协作者输入保护

[PR44](https://github.com/liboyang42-cpu/hengyin/pull/44)新完整源码`adf597917da2654b6b753141180bbbcb6db92fc5`，直接父e0e3f517。唯一CORE/C03作者已交付停写；仅CmsTopicServiceImpl新增14行写前ID检查＋新10方法回归＋原说明。create/FULL显式名单含null/<=0沿原IllegalArgumentException/默认body500整包失败，重复正ID和顺序、list缺席/[]清空/已有行缺席保护/WHITELIST、owner与完整性校验顺序保留；不新增权限/成员存在/邀请/DDL/依赖。

源码和必要回归已push，**SOURCE_DELIVERED_UNVERIFIED／NOT_RUN**；已交本地root聚焦4类10新＋4＋31＋6=51源声明，实际执行数未知，无DB/Redis前提。旧e0普通10/0d157不继承新源码。完整FQN/命令/精确3路径及前提见[C03_14_SOURCE_DELIVERY](C03_14_SOURCE_DELIVERY.json)。真实HTTP/DB/设备/合并部署仍未验，TEST_DEPLOYED=NO。重复正ID按本轮明确指令保原行为，不另外造待决策任务。

EXTRA E02-12已原作者实际[ACK恢复](https://github.com/liboyang42-cpu/hengyin/pull/16#issuecomment-6209021853)：既有status1公共/保存上限支持仅POI选项NULL fail-closed查询对齐，分类QUERY_CONTRACT_NULL_COMPATIBILITY，生产NULL来源仍未证、不算现场新增bug；新SHA待交。E01-13售卖字段仅存读，WAITING准入政策未定义，不猜窗口拦截。第三F3-GROW-03已交`d592334abee1a379963e7b72a58eec5e0f7bd5ad`，仅原11项MySQL测试时钟和seed一致化，生产仍bde且普通两类未改；新测试源码NOT_RUN、真库11继续现有入口覆盖缺口，不重复旧28PASS。所有新状态见[恢复包](RESUME_KNOWN_BLOCKERS.json)。

## 恢复具体旧项：先按证据解除阻塞

已恢复[本轮精确派工](RESUME_KNOWN_BLOCKERS.json)：CORE/C03 `OWN-C03-14`拆开处理null/非正ID输入与重复ID，前者由现有edit-detail正ID形状及草稿恢复/保存链确定写前拒绝；重复正ID原样保留，不新增成员/协作权限规则。原C03已冻结停写，精确CmsTopicServiceImpl方法边界及新测试/原说明正式转`/root/topic_like_cancel_sol`，发布grant后开始源码，未假称已运行。

EXTRA原E02领取E02-12仅POI选项status与既有保存snapshot的status=1一致；原E01领取E01-13追实际创建/售卖/候补join合同，可证实即修，未知截止政策不猜、已有offer清理/付款不误伤。已向PR36发布具体包及依据，真实child ACK未回前不计活动writer。第三队仅原日期/成长范围，当前本包没有新增已确认READY，不泛扫或占满六线。

真实合同阻塞已细化为[旧帖权威身份桥决定包](BLOCKER_E02_23_DECISION.json)和[原奖励historyId决定包](BLOCKER_E04_07_DECISION.json)：缺的是创建/编辑/审核/删除权威与可信映射、原券receipt持久字段/旧NULL处理，不是等待测试资源。交本地root决定；未授权DDL/切源/双写不执行。Cloud只源码NOT_RUN、本地集中验证，入口仍保持现有不新增。

## 当前有效约束：保持现有入口，不新增

用户最新明确决定：**“保持现有入口，不新增”**。新增隔离MySQL/Redis集成入口方案已关闭，不再处于等待批准状态，也不再催批同一方案。现有VPS212和CI配置保持原样；现有自动CI自然运行，不手动触发或重复重跑。普通回归只在新真实源码候选或未解失败时由本地按现有212验证，已通过且未变的候选不补跑。

现有入口未覆盖的真实MySQL/Redis项继续为 **NOT_RUN／现有入口覆盖缺口**，包括E02_25MySqlIT2、第三Growth真MySQL11等；不改称通过，不以普通/mock/skip结果代替。已有精确SHA的PASS和失败/锁拒绝历史均保留；真实HTTP/设备业务/集成合并部署仍未验收。此为约束更新，无新增开发或补跑任务。详见[持久决定](VALIDATION_ENTRY_DECISION.json)。C00只维护全局账，各队只维护自有交付账。

# 当前恢复入口

## 已完成的新源码交付

E01-19（EXTRA旧packet，不另造bugID）已由正式移交的CORE/C03 writer完成。公共源码 **`e0e3f517b8e6deb26ed3697a00d38ae14f015e76` / PR44**，直接父`0d63043ad06775bc3dc52fb925620ef36d1081a7`。生产仅ApiTopicController.topicLike一处条件：显式type0取消DELETE0也成功；legacy空type toggle、负数失败、异常传播、member/topic过滤及选中ID不变。新增5个有界读屏障/负控回归源码，旧测试/Service/Mapper不改。

状态 **源码已交；本地普通2类10实际PASS，[公开报告已核读](https://github.com/liboyang42-cpu/hengyin/blob/5a90a1e81db8274960c3d5dee9d6646aa73311be/docs/verification/mini-bug-arch-20261015/VPS_CORE_e0e3f517/README.md)，真实数据库/HTTP/业务未验**；作者已停写、锁保留，实际源码writer0，无新已证实READY项，不凑50/6线。根因、精确3路径与正式旧作者停写/新作者移交见[E01_19_SOURCE_CLAIM](E01_19_SOURCE_CLAIM.json)。

## 三队新候选与后续派工

最新公共源码分别CORE e0e3f517（E01-19，普通10实际PASS）、EXTRA最新29fe9236（E02-25 RR当前读，普通64实际PASS；旧faef普通60PASS保留）、第三bde356f3（STAT02年份/GROW02午夜时点，普通28实际PASS）；前批CORE/旧EXTRAfaef/第三已获各自普通PASS，EXTRA新29fe普通64已实际PASS，真库2未验，旧PASS只覆盖表中旧SHA。EXTRA/第三新交付只在原owner范围，未向CORE整枝灌入。

E02-25已由原EXTRA/E02交新冻结源码`29fe9236ede15a8f1bbd3aaf2b93e3fc25409726`，原作者已停写/锁保留、writer0。C00核精确7路径符合原授权，见[E02_25_SOURCE_DELIVERY](E02_25_SOURCE_DELIVERY.json)。本地已按212执行普通3类64实际PASS/F0/E0/S0，fresh精确3XML、guard0及精确清理已核公开证据；真MySQL RR2仍NOT_RUN/现有入口覆盖缺口。旧faef60PASS不得继承；docs e9b9e191仅3账本文档，不是新生产修复。

本地要求的真库/Redis现有入口映射已在[REAL_STORAGE_ENTRY_MAP](REAL_STORAGE_ENTRY_MAP.md)：固定CI名单只覆盖明确列类；其余属现有真存储入口覆盖缺口，212不能替代；不新增入口。Cloud不运行/改CI/增网例外。本地另已发布[入口缺口与候选清单](https://github.com/liboyang42-cpu/hengyin/blob/cae72e05ee668271d79ea9c95e505d7e8971d3a5/docs/verification/mini-bug-arch-20261015/VPS_DB_ENTRY_GAP_20261016/README.md)，用户已明确“保持现有入口，不新增”：新方案关闭、无需等待批准，不再催批。现有入口未覆盖的项继续NOT_RUN，212/CI不改。

## 三队普通定向及新29fe普通64已结束／真库仍待验

本地三批已结束：CORE10、EXTRA60、第三28普通定向全部PASS/F0/E0/S0，PR24@cae72e05ee668271d79ea9c95e505d7e8971d3a5公开README/summary已核读。EXTRA首次03:40:30Z拒绝0项历史保留，重试03:44:19Z—03:47:03Z；第三03:47:03Z—03:49:49Z。新RR源码29fe已另实跑普通64PASS，[公开证据](https://github.com/liboyang42-cpu/hengyin/blob/0d3b6195203b0b963e0dd77df8412fde70b3923e/docs/verification/mini-bug-arch-20261015/VPS_EXTRA_29fe9236/README.md)已核；实际RR真库2仍未验，不继承旧批通过。旧源码任务EXHAUSTED不代表本地验证停止，也不关闭未验层级。

## CORE本次验证范围

唯一实际验证者：本地`01a13eed-e4d9-77f3-b875-c72d26ea7d59`。新候选普通套件TopicLikeExplicitCancelConcurrencyTest5＋ApiTopicLikeDirectionTest5，已由本地实际执行10/10通过，0失败/错误/跳过，fresh精确2 XML、212guard0及清理已回报；C00已核读PR24@5a90a1e81db8274960c3d5dee9d6646aa73311be的公开README和结构化summary；原始XML/日志由本地保留，未声称重新执行。命令/前提见[移交JSON](BACKEND_VALIDATION_VPS_HANDOFF.json)的core_latest_delta_validation；只按已批准212准入，Cloud不执行，不重跑全483/157。无新DB/Redis/DDL前提；真实SQL交错与运行HTTP另验。

## 各候选结果边界

| 公共源SHA | 已签收范围 | 未覆盖 |
|---|---|---|
| 1e403b13 | 原冻结231项VPS自动化PASS | 后续任何源码与运行业务 |
| 0d63043a | 原8失败类157项针对性PASS | 新E01-19、其他类新SHA实跑、真库/HTTP |
| e72eff7e | EXTRA原2失败类56项针对性PASS | 后续源码、新305全跑 |
| b45664de | EXTRA E06单类5项针对性PASS | 新76全跑、真库/HTTP/业务 |
| 2babbe28 | 第三队编译＋普通2类24项PASS | 11真MySQL仍NOT_RUN、真实并发/HTTP |
| 29fe9236 | 普通3类64实际PASS，[公开证据](https://github.com/liboyang42-cpu/hengyin/blob/0d3b6195203b0b963e0dd77df8412fde70b3923e/docs/verification/mini-bug-arch-20261015/VPS_EXTRA_29fe9236/README.md) | 真MySQL RR2/HTTP/业务未验 |
| bde356f3 | 本地普通2类28实际PASS，[公开证据](https://github.com/liboyang42-cpu/hengyin/blob/cae72e05ee668271d79ea9c95e505d7e8971d3a5/docs/verification/mini-bug-arch-20261015/VPS_FORWARD_bde356f3/README.md) | 真MySQL11/HTTP/业务未验 |
| faefbaf8 | 本地普通2类60实际PASS，[公开证据](https://github.com/liboyang42-cpu/hengyin/blob/cae72e05ee668271d79ea9c95e505d7e8971d3a5/docs/verification/mini-bug-arch-20261015/VPS_EXTRA_faefbaf8/README.md) | 后续RR候选、真MySQL/Redis/HTTP未验 |
| e0e3f517 | 本地普通2类10实际PASS、[公开报告已核读](https://github.com/liboyang42-cpu/hengyin/blob/5a90a1e81db8274960c3d5dee9d6646aa73311be/docs/verification/mini-bug-arch-20261015/VPS_CORE_e0e3f517/README.md) | 真SQL/HTTP/业务均未验 |

原69c初始化失败、f7的483失败、474的305失败、ed598的76项1失败，以及两次212锁拒绝均保留独立证据；对应失败已按各自针对性复测关闭，不改写原失败为PASS。[VPS_REPAIR_ROUND_1](VPS_REPAIR_ROUND_1.json)及PR24保留原报告/哈希。

## 当前真实阻塞

- E04-07：consumer已EXTRA/E04持有，旧BLOCKED_OWNERSHIP已纠正。通关producer未持久原historyId，时间窗同模板猜券不可用；已有receipt helper不等于已获持久/旧日志未知身份合同。BLOCKED_CONTRACT，不猜ASC/DESC或奖励政策。
- E02-23：一次离线legacy迁移不提供后续旧帖的权威新帖映射；仅数值ID会错收藏，client_request_id字符串本身亦可由新客户端提供。完整切源/桥投影及旧内容更新审核删除责任须原owner冻结；不擅自双写/DDL，BLOCKED_CONTRACT。
- 其他已登记政策/审核版本依赖继续保留，不因“继续”猜权限/收费/产品决定。两项只读结论见[EXISTING_BLOCKERS_REFRESH](EXISTING_BLOCKERS_REFRESH.json)，未增加确认bug数。
- 裸SQL疑点仍NEEDS_REACHABILITY/NO_SOURCE_CHANGE；无真实业务可达证据不改共享脱敏器，不计新已确认bug。

## 协作与未验收

最多6名gpt-6.1-sol；每队反馈失败只占一原作者修复槽，其他真实独立源码继续。C00唯一全局源账，EXTRA/第三队维护自己的交付账；Claude UI/新功能、iOS各保留原锁，Flutter排除。未转交文件只读，换writer必须有原作者停写和精确基线/路径证据。

Cloud只写源码/必要回归并push，NOT_RUN如实记录；不编译/Java/Maven/Node/lint/CI触发/数据库/VPS或替代环境。真实HTTP/身份业务、真MySQL/Redis、设备、合并、部署/业务迁移仍未验收，TEST_DEPLOYED=NO。E06分享HTTP503合同与本次取消ACK兼容口径见[BACKEND_CHANGES](BACKEND_CHANGES.md)/[IOS_HANDOFF](IOS_HANDOFF.md)，未收到对端ACK不假定已通知个人。

[源表](LONGRUN_SOURCE_OWNERS.json) / [CORE队列](LONGRUN_CORE_50.json) / [余量](LONGRUN_REMAINING.json) / [旧checkpoint历史归档](LONGRUN_CURRENT_HISTORY_PRE_E01_19.md)。恢复核公共head与WIP，不覆盖工作树；平台终止时依此公共记录恢复，不承诺永久运行。
