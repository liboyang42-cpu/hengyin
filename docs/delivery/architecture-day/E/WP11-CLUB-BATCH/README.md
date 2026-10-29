# E WP11 club 会话列表批量资格与展示

结论：最小业务补丁与回归源码已完成，未在云 VM 执行 Java/JUnit/MySQL/CI。只对 club 群列表消除当前逐项 SQL；不是全部 IM 查询 O(1) 或运行性能验收。原 cache/IM/Photo/theme 候选冻结，不借用任何旧绿回执覆盖本增量。

## 基线、范围与调用链

公共基线 `4197f6a6b11d62cb0a742aae73d7167c93114585`，tree `2808646e339058941f289f47d7d7549cd4bbe8d7`；独立目录 `/workspace/hengyin-e-wp11-clubs`、分支 `codex/architecture-day-e-wp11-club-batch`。开工时公共基线与本目录 HEAD 一致，初始 clean；没有切换/回退其它工作树。

本增量9文件：`ImServiceImpl.java`、`ImConversationMemberMapper.java/XML`，既有专属 `ImServiceImplTest`、`ImConversationBatchReadTest`、`ImConversationQueryScopeMySqlIT`，新增 `ImClubConversationBatchMySqlIT`、scratch SQL fixture、本报告。无 A/F/C/root/CI 修改、无 schema migration、无新依赖或缓存。现有摘要批量 SELECT 与 unread SUM 的 SQL/实现不重做。

已核对当前 SHA 的2460个生产 Java blob：2446个本地 exact blob 搜索，加14个公共 API 只读补查；`listConversations` 实际生产 caller 只有 `ApiImController.conversations`，接口 `IImService`，实现内部 `selectMyConversationSummaries` 唯一生产调用点也在这里。链路为认证 member → POST `/api/im/conversations` → Service → MemberMapper 批量摘要。小程序 IM 列表、聊天页拉黑前定位对端、商家首页均调用该端点；三文件与本公共 SHA blob 相等。测试 caller 搜索另覆盖现有 batch/scope/route/revocation tests；不修改 REST、客户端、排序或游标协议。

## 失败机制、等价边界与最小修复

原4197每个 club 群走 `hasActiveGroupAccess`：`ClubMapper.selectClubById` 查询 `id/del_flag=0`；Java要求 `status=1`、`delFlag=0`，然后 owner 或 `ClubMemberMapper.selectByClubAndMember` 当前会员。后者 SQL 是同一 club `del_flag=0` JOIN，加 `cm.club_id/member_id/status=1/del_flag=0`。允许后，`summaryCounterparty → counterpartyOf → clubOfBizKey` 再查同一club以展示名称/logo/memberCount。稳定数据下 owner=2 SQL/群，非owner当前会员=3 SQL/群，外加原摘要1条；历史退出/申请/删除会员不能授权。

新增请求内读模型 `ClubConversationSummary extends Club`，只含上述投影字段与 SQL EXISTS 的 viewerMember。Service 先按原 `clubIdOf/Long.valueOf` 解析本次 TYPE_GROUP 摘要的 club IDs，去重，一次查询选定 club 资料及原当前会员 EXISTS；所有 IDs 来自已按认证 owner 过滤的会话列表。有效 club 仍以相同 Java status/delFlag/owner-or-member 判断，再从同一投影展示。空集合 SQL `1=0`；正常 Service 无 club IDs 就不发新 SELECT。没有全量读取 club/会员历史，没有把 JOIN 换为跨域循环调用，不跨请求保留资格。

数值解析仍保留前导零、`+`号、合法 Long（包括历史异常负值）的原语义；不以 `biz_key=CONCAT('club_',id)` 改写，不为本任务增加业务ID政策。`club_abc`、空白及溢出值沿原守卫 fail closed。缺club、停用club、非当前会员、deleted IM索引仍不得显示标题/摘要/未读；DB异常继续抛出，不转空成功或逐项 fallback。NULL nickname/memberCount、NULL logo→空串、原 bizKey 字面及全部输出字段/排序保持。

原 `hasActiveGroupAccess`、`projectRouteMessage`、`summaryCounterparty`、`counterpartyOf`、`unreadTotal`、发送/已读/投票守卫源码不改；新读模型仅列表消费，不可作为写入授权。所有既有 MemberMapper XML statement/resultMap byte-equivalent，新建一个 SELECT/resultMap。team/hangout 及所有路线卡仍走原逐项业务闸。

## 查询次数合同与剩余热点

无路线/其它业务群的稳定 club-only 列表：0群=原1/新1；K个允许非owner群=原 `1+3K`/新2；K个owner群=原 `1+2K`/新2。混合列表新 statement 数为 `1 + (有合法club ID ? 1 : 0) + 原team/hangout和路线投影查询数`。一条 SQL 内 EXISTS/最新消息相关子查询仍有与数据量相关的数据库工作；statement 少不等于常数执行成本、RPS、延迟或容量实证。未编造这些数字。

路线仍是 `projectRouteMessage → CmsTopicMapper.selectCmsTopicDetailById(topic,viewer)`，带发布/删除、所属club、主圈与clubMemberOnly可见性碎片；本次不复制/削弱这些复杂谓词。team每群资格team+当前成员、展示再读team；hangout资格状态+IM当前索引、展示含计数仍存在。没有用缓存替代正确性，也不称所有查询已经O(1)。

## 用例与真实执行状态

原 `ImServiceImplTest` 53条场景和业务断言全部保留，只适配3处列表club fixture为新读模型；原 `ImConversationBatchReadTest` 7条保留，群场景保留拒绝/授权/输出断言并改验证新batch调用，新增2条（64群/原资格；数字bizKey/NULL字段/DB故障）后为9。原 `ImConversationQueryScopeMySqlIT` 7条场景全保留，club场景原两读者输出集合不变，原mock改真实club/会员表，IM-only计数1改真实完整计数2；unread仍1。fixture用独占scratch库，不把mock跨域计数当真SQL。

新增 `ImClubConversationBatchMySqlIT` **5条**，均使用真实 IM/Club/ClubMember Mapper、真实 SELECT 和 StatementHandler.prepare 计数：

1. 当前owner/会员/申请/已退出/删除/停用/NULL/缺行、重复业务club引用和脏bizKey，完整输出集合与4197原列表影子逐字段对照；加入无关历史会员、非owner IM索引、deleted/closed索引和NULL展示字段，read零写。
2. 0/1/64/257个club群真实SQL预算：非owner原 `1+3K` 对新1/2；另64个owner原129对新2；空账号只有1。是待执行断言，不是实测数字。
3. 当前关系在真库提交 member行DELETE、member del_flag=2、club停用/删除后回读，原闸/新批量均隐藏且基于提交后快照零写。退出原事实来自 `ClubMemberServiceImpl.quitMemberAndLeaveGroup → ClubMemberMapper.deleteByClubAndMember` 的DELETE，不把未定义status=3当业务退出状态；申请状态0/1/2沿原domain。仅验证既有当前谓词，不声称调用了club退出编排、严格in-flight截止或HTTP/JWT。
4. 空/NULL/指定ID不全量加载，真实 BoundSql 的 EXPLAIN JSON输出留证；不捏造耗时/索引必选阈值。
5. 真库短暂 RENAME club 模拟数据库不可用，必须抛错，恢复后原行可读；finally复原、元数据/未读无写入。

影子使用原 `selectMyConversationSummaries` + 原未改的 private 当前资格、route投影和counterparty helper，逐行调用真实旧Club/ClubMember SQL；完整输出包括原null、群bizKey、最后消息、未读、muted、时间与可见序/id排序，未通过新batch生成expected。

本次变更直接涉及 **53+9 unit、7+5 MySQLIT=74条声明**；新增unit2+IT5=7。另应复跑未改的旧BatchMySQL4、ActivityTeamRevocation2及RouteProjection专属类。原team两条真实退出回执仍只属于其旧源；不是本补丁验收。没有Disabled/Assumption/新增环境跳过开关。

实际只执行Git/Python静态：独占范围、原全部XML条目/守卫冻结比较、原业务断言与case名字保留、词法括号/XML/fixture字段结构、5184组合含NULL谓词真值表、diff check、secret-file-gate与有限token/private-key模式。它们不等于Java编译/MySQL运行。未运行Java/JUnit、MySQL/Redis、EXPLAIN、CI/模型/生产；固定版本gitleaks由root对最终组合源复扫，有限扫描不冒充全扫描0。

## 迁移、清理、回退与未验证

无生产DDL/数据迁移，无旧历史DDL修改。新增SQL仅 `src/test/resources/fixtures/im-club-conversation-batch.sql`，在既有显式PORT/USER/PASSWORD、loopback、exclusive UUID测试schema创建。Club读取投影包含真实mapper所需列；不是生产club迁移一致性验收。club_member沿公共canonical已有 `(club_id,member_id)` 唯一索引及club/member索引，不凭新增生产覆盖索引宣称性能。既有fixture已获root真实清理验证，本新增类继续委托其owned schema DROP+information_schema读回；本源清理仍待实际CI确认。

回退只撤本候选三个生产文件及配套tests，不回退已验摘要/unread或其它owner代码，不需清Redis/数据。未验证项包括本源编译/74条业务、EXPLAIN实际计划/清理、全部真实group/route域权限、严格并发撤权截止、HTTP/JWT与吞吐。没有新增业务策略或权限决定；未来若原Club/ClubMember谓词改变，须同步本专属projection并重跑影子对照。
