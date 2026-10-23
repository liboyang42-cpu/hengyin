# WP11：活动队伍退出后的真实跨域资格回归候选

结论：新增两个 MySQL IT；不修改生产代码、原 IM11 或 Photo E87。当前仅完成代码及轻量静态检查，**Java 编译、两例业务执行和数据库 CLEAN 均未运行**，由协调者统一点名执行，不能用既有 IM11/Photo17 的通过记录代替本候选证据。

## 基线与独占范围

- 仓库：`liboyang42-cpu/hengyin`，公共基线 `2ec68899099a1e250df0949a8383bf85b5d3ffe0`。
- 本地起点：`4516029348b12dc7b784b03f1cb640a06bfb7488`，与公共 IM 候选 `3c48996106c63ee75d4d1c93403396a3d7a8bf15` 的 tree 同为 `0d72d6e273d4aacd8f6560676aac0f9c21b58a2e`。
- 工作树 `/workspace/hengyin-e-im-revocation`，分支 `codex/architecture-day-e-im-team-revocation`。公共候选以 `3c489961...` 为 parent，只增加本 IT 与本报告；本地提交以等树的 `45160293...` 为 parent，两种提交身份必须分别记录。
- 唯一新增 Java 文件：`chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/ImActivityTeamRevocationMySqlIT.java`，夹具包含在该类中。本目录为专属交付报告。不改 A/B/C/G 文件、PlayTeam/IM 生产服务或 mapper、现有测试、根 pom/CI/注册表/迁移。
- Photo 生产候选冻结 `87c27bcd6e8e1e0e84cc75574428bfe3f7430c31`，本次不依赖、不覆盖、不修改 Photo 协议。

## 现有规则与失败机制

资格归属 `play_team_member.status=1`，由 `PlayTeamServiceImpl` 与真实 `PlayTeamMapper` 维护；IM 行仅为会话索引。`ImServiceImpl.hasActiveGroupAccess` 的现有注释及队伍分支明确要求真实队伍未结束/解散且当前成员 JOINED，不能只信 IM 索引。

活动群采用现有 `type=4,biz_key=team_31`，队伍 `owner_type=2,owner_id=9`。生产 `PlayTeamServiceImpl.create` 当前仅接受活动 owner_type=2；本夹具预置既有队伍，不测试报名或建队，不虚构 `activity_` 会话类型，也不定义退款与退出的关系。

普通成员真实退出顺序（两个活跃成员，退出者非队长）：

1. `PlayTeamServiceImpl.quit` 开 REQUIRED 事务，通过 `selectPlayTeamByIdForUpdate` 锁队伍。
2. 读真实活跃成员/人数，将退出者 JOINED→QUIT，`releaseSlot` 将人数 2→1。
3. 调真实 `ImServiceImpl.leaveTeamGroup`，查原会话并 `markDeleted` 隐藏索引，与退出同事务提交。
4. 返回后独立 JDBC 断言资格为 QUIT、人数为 1、队伍仍招募、IM 索引 deleted=1，再发新的请求。

此非队长/非末位分支不调用通知、审核或新的授权入口。若以后真实退出被新增授权顺序阻断，正向退出断言会先红；必须由协调者分析真实锁序，不以弱化授权或只改夹具状态使绿。

风险模型：退出者持有旧 conversationId、旧原始收据标识及原消息正文，继续读/写；或退出后 IM 索引因旧数据仍 deleted=0。后者不能复活真实资格，也不能通过原标识重放绕过撤权。

代码引用以本候选生产父树为准：`PlayTeamServiceImpl.quit`、`ImServiceImpl.leaveTeamGroup/hasActiveGroupAccess`，以及现有 mock 单测 `formerTeamMemberCannotSendThroughStaleImMembership`。协调者 source `dde91d5c03a85c769e17c2a8c6e88364f4b0d27c` 的这些生产文件与父树逐字一致；原批量 IT 有协调者已记录的环境开关 harness delta，本次不修改该文件。

## 两个真实用例与入口矩阵

1. `committedRealQuitRevokesHistoryIncrementReceiptSendReadMarksAndList`：真实退出提交后，使用原会话与收据请求所有下列入口。
2. `staleVisibleImIndexCannotRestoreRevokedRealTeamQualification`：同样真实退出，再**仅在独占测试库**恢复退出者 IM deleted=0，确认真实队伍成员仍 QUIT，重复完整矩阵。该 UPDATE 是对抗夹具，绝不是生产修复或新资格策略。

每例先正向证明原成员能进群、通过共用资格闸、看到标题/历史/增量，真实发送生成 request_hash 与原收据，原请求重放返回原行，两种已读确实能推进。随后将未读与已读位置设置成可检测的非零夹具，再真实退出。这样拒绝后的“无变更”不会因空消息/零未读而虚假通过。

| 入口 | 退出后既有规则与断言 |
| --- | --- |
| listConversations | 返回空列表，不能回传本群会话标识/标题/摘要 |
| listMessages 四参数历史、五参数历史/游标 | deleted=1 时 ServiceException 403；过期可见索引时现有域异常“已退出队伍，无权查看群消息”；不强改 HTTP 码 |
| listMessages 增量 after=0 与 after=10 | 与历史同样拒绝，不返回消息或 nextAfter payload |
| readSentMessage 原收据/不存在的收据 | 均 403、data=null；收据是否存在不改变撤权结果 |
| send 新标识、原标识原内容、原标识变内容、无标识重载、图片 | 均 403、data=null；撤权先于原收据查询/409 内容冲突/审核，不产生消息、摘要或未读 |
| markRead 两参数/显式原消息 readTo | 均 403，不清未读或推进任何截止点 |
| requireActiveGroupMembership / isActiveGroupMember | 前者 403，后者 false；真实资格查询，不 mock |
| getOrCreateTeamGroup | 现有“加入队伍后才能进群聊”拒绝，不复活索引/创建会话 |
| unreadTotal | 仍为 0；现有 SQL 排除所有群，此项不冒充资格验证 |

投票的 create/vote/removeVote/result 等真实调用者使用 `requireActiveGroupMembership/isActiveGroupMember`；本例覆盖真实共用闸，不声称投票表/投票服务端到端已执行。`deleteConversation/setMuted` 的现有语义不在本撤权零写入合同中，不能扩称所有 IM 公共写操作都拒绝。

## 真实装配、夹具与零效果边界

- 同一个真实 DataSource、DataSourceTransactionManager、SqlSessionTemplate；两类真实 service 使用 `ProxyFactory + TransactionInterceptor + AnnotationTransactionAttributeSource`，保留生产事务注解。真实 PlayTeamMapper 及 IM Conversation/Member/Message/Block 四 mapper，直接加载现有 XML，均非 Mockito。
- 仅内容审核、媒体回调 inbox、业务审核、通知、资料展示边界是 mock。正向真实发送允许资料/文本审核调用（受控不联网），退出前清空调用记录；真实 quit 及每个拒绝请求后都 `verifyNoInteractions`，审核/通知边界不能被误调用。资格所有者、退出、IM 服务、拉黑与全部实际访问的 mapper 无 mock。
- 独占七表：play_team、play_team_member、im_conversation、im_conversation_member、im_message、im_block、ums_member。ums_member 仅建立 mapper LEFT JOIN 所需展示列；不建资金/订单表、不调用相应服务。
- 复用六份现有 canonical migration：migration_im.sql、migration_im_send_idempotency_20260913.sql、migration_20261012_im_media_visibility.sql、migration_20260820_play_team_core.sql、migration_20260822_play_team_invite_code.sql、migration_20260915_play_team_public_apply.sql。只在随机 scratch schema 执行；无新生产 DDL。
- 每个请求前/后确认无调用者业务事务；退出已提交后才开始请求，不承诺与退出重叠的旧事务截止语义。快照通过不在事务中的独立 JDBC 读取七张表全部行（包括消息 client_message_id/request_hash、summary、unread、last_read_msg_id、last_read_visible_at、muted、资格、人数、时间戳），每次拒绝后逐表严格相等。stale-index 用例仅在对抗 UPDATE 后另取快照，不把合法 quit 或对抗夹具本身当成拒绝副作用。
- 对 ServiceException 拒绝断言 code=403、data=null，且异常消息不含群标题、消息 canary 或旧正文；过期索引历史路径锁定现有域拒绝文本，避免因 NPE/DB 错误伪装成授权拒绝。异常不被转换成空收据或不存在。
- 快照不含安全审计表/日志；合同允许必要的脱敏拒绝审计。这里断言的 `IAuditTaskService` 零调用是现有业务审核边界，不表示禁止安全日志，也不把所有日志零写入作为安全要求。

## 执行与验收

显式点名 `ImActivityTeamRevocationMySqlIT`。连接沿用 `CHENGYIN_MYBATIS_IT_PORT`（合法端口）、`CHENGYIN_MYBATIS_IT_USER`（非空）、`CHENGYIN_MYBATIS_IT_PASSWORD`（必须存在，允许显式空值）；无端口/用户名/密码默认值、无 enable gate、无 skip、无 H2。需 MySQL >=8。

创建 `cy_im_revoke_<随机32hex>` 专属库；AfterAll 仅 drop 本类创建的库并查 information_schema 证实删除，初始化后失败也保留 cleanup 路径。获准运行端需记录 source SHA、2 tests 实际 counts 和 CLEAN；初始化失败必须记业务 0/2，不得算成通过或跳过。

已实际执行的轻量验证：`git diff --cached --check` 无输出且 exit 0；`python ci/secret-file-gate.py` 输出 `SECRET-FILE-GATE PASS`；新增两文件有限 token/私钥正则扫描 PASS；两用例/五真实 mapper XML/六现有 migration/无 skip 与默认凭据的静态夹具检查 PASS；相关生产与原批量 IT 逐字未变检查 PASS。静态括号检查不是 Java 编译。当前无已安装 gitleaks，既有固定版本下载曾被 proxy:8080 连接阻断，不改网络/代理，不加依赖；协调者须按项目固定版本复扫候选差异。

候选 SHA/tree/parent/ref 通过公开 Git 数据接口发布后单独回报，报告不自引用提交 SHA。本报告不将代码审查当 Java/MySQL 结果。不 dispatch、不在云 VM 编译或运行 Java。

## UNKNOWN 与回退

尚未知：与退出并发的已开始请求是否具备严格撤权截止（含 RR 旧快照/锁序/TOCTOU）、退款/报名资格变化、真实活动建队及支付、HTTP/JWT、club/hangout/路线权限与投票全链路。本例不定义这些新策略，不声称所有查询 O(1) 或容量/性能结论。

仅新增测试及报告；回退删除本 delta，不回退生产授权或既有候选。若真实 MySQL 暴露生产授权/事务缺口，保留红例和原状态，交协调者分配生产 owner 修复。
