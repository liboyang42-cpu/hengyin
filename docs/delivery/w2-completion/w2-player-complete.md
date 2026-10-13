# W2 player completion — NPC/AI、discovery、coop-social

当前已批准的源码恢复、实现与相关验证已交付；三线整体业务验收仍未通过，逐编号缺口保留如下。没有引入暂停的 22 项工具，没有推送、合并、发布或启动应用后端。

## 版本与边界

- 公共基线：`0f0c32f`；保留其祖先 `9c5d903`、`806abb8` 的通行证到期约束，未修改 `ApiAiNpcController`、`PlayTeamMapper` 或其鉴权规则。
- 代码提交：`27fa88a7817af93bc3cd2a8dfed724c3d6c245e7`。
- 精确测试 tree：`5ef031aabefd6a9ad8336ade641f2f4060811845`。
- V4 后端 gzip：6,890,729 bytes；SHA-256 `3fffb59fd32547b7f7a03ed537473099239227a5e04ba97e9cd931301e78b2c2`，远端回读一致。
- 独立公共工作树上的新提交；只读参考原报告与逐份源码差异，未导入私有 history、refs、整包文档或日志。未改共享主工作区。
- 未改 app.json、全局 token、公共配置、共享 action ledger 或门禁 baseline；play/index.js 只改 NPC/录音/ASR/TTS 与完赛推荐段。

## 关键实现合同

1. NPC `NpcChatService.chat/chatWithMerchantTarget`：新请求以当前用户、场景、对象、商家及规范化文字的 SHA-256 绑定；重复插入竞争也比对对象/hash；同键异参返回 `REQUEST_CONFLICT`；旧 hash=NULL 不伪造指纹，只能恢复原对象。内容原文没有因此新增入库。
2. `AiCreatorServiceImpl.themeDraft`：旧无 requestId 调用兼容；新请求先持久 PROCESSING 后执行原角色、网关、额度及失败释放流程；唯一键 `(user_id,request_id)`、hash 冲突拒绝、结果 CAS。成功结果落库失败保持 PROCESSING，原 ID 后续不会再次调用模型。status 只读当前用户；客户端跨页面只存 requestId/mode，并回查原结果，不保存原 idea。
3. 收藏 `topicLike/activityLike(id,type,target)`：target=0/1 为显式关系，省略 target 保留旧 toggle；新旧分支共用当前会员行锁；按原 ID 删除，回读 `/like-state` 的真实布尔值，隔离迟到的另一身份结果。历史重复关系未自动清理，其他后台 CRUD 写源未重构。
4. 动态 `postLike`：保留原写范围；现 club 行锁后用 `selectByPostAndMemberForUpdate` 当前读，避免 REPEATABLE-READ 旧快照导致重复计数；新 `postLikeState` 沿 `canViewClub` 校验可见性且只读当前会员关系。原 postList/postLike 私密团限制仍待批准，没有顺带修改。
5. 搜索：主题/活动根据真实 total 查询后续页；过滤后当前页无匹配时继续查下一页，命中后可继续加载；追加去重、查询 epoch、错误与空分开；拒绝非整数/无穷/字符串 total。未知日期/展示价沿原保留语义。实读各列表 SQL 后将选项改为“默认顺序”，没有把 ID 顺序宣称为发布时间排序。
6. 门店评价：新增 service overload `publicList(...,String filter)` / `manageList(...,String filter)`，原签名兼容；count 和 SQL 分页共用 merchantRowId/manage/filter；all/pending/low/photo 沿现筛选定义，平均分及统计口径不改。服务端回显 filter，客户端拒绝无确认的旧响应，快速切筛选/身份的旧回调不覆盖，空筛选保持切换入口可达。
7. 活动署名：`CmsActivityServiceImpl.publishActivity/updateActivityByOwner/saveActivityCollaborators` 与 `CmsCollaboratorsMapper.deleteActivityCollaboratorsByOwner` 恢复原审阅修复：保存所选 memberId、去重、无效成员/失败写入拒绝；null 保留、[] 清空；删除同时限定 owner 与活动类型；编辑回填按 memberId。未给予署名成员编辑权限。
8. 合作评价：沿原 isCoopParties 资格，唯一竞争转明确重复结果；未知写先查原评价，读失败不能重新打开一次性写入口，保留账号/邀约隔离；不新增履约认定、奖励或评价资格。

## 逐编号状态

“代码”指源码与定向检查；“DB”指隔离 MySQL/H2 夹具，不能代替合法真实账户验收。所有正常 UI 与设备/渠道验收均待协调者补证。

### NPC / AI

| 编号 | 已实现 | 缺实现 | 代码 / DB | 正常 UI | 外部验收 | 真正阻塞 |
|---|---|---|---|---|---|---|
| F0231 | 原 ID/原对象恢复、迟到隔离、内容 hash、异参拒绝及竞争检查 | 玩家 NPC 跨进程原问题恢复、主动新生成费用入口；旧记录无文字指纹 | 代码通过；新 hash 真实 SQL 入库/异主读不到；模型调用次数仅模拟网关 | 未验 | 真实模型与费用未验 | 主动重生成费用未定；跨进程 NPC 恢复仍有实现缺口 |
| F0233 | 本人持久请求结果、同键异参拒绝、原额度流程、跨页面重启查询、结果保存失败未知锁 | PROCESSING 在进程中断/结果保存失败后缺供应商原任务回查；旧无 ID 调用不具新恢复合同 | 代码通过；唯一键/CAS/结果/hash/本人 SQL 回读通过 | 未验 | 真实生成与账单未验 | 供应商任务恢复能力与人工消解未知流程缺口；不能自动再次消费 |
| F0228 | 原真实开关/游玩资格与公共到期闸保留，预览/演示不新增对话 | 真实身份与运营开关链补验 | 代码通过；到期/续期/相等边界/同 Session 新鲜度实 MySQL 通过 | 未验 | 真实模型未验 | 运营/设备验收条件；没有新增全局 NPC |
| F0227 | 核实当前 merchant-chat 是店铺主页端点；保持原资格与费用 | 独立商家测试作用域/准入/费用及消费者 | 缺专用测试合同，未造绕过入口 | 未验 | 未验 | 需要明确谁可测试、使用哪一额度；不能拿玩家入口代替 |
| F0229 | ASR 真实返回才补玩家文字，文字与音频终态分开；拒录音重入、离场撤监听、松手后不启动；未知不重复上传 | ASR 原任务持久回查、音频版本/过期元数据、重合成合同 | 代码通过；录音及上传为夹具 | 未验 | 麦克风/拒权/ASR/TTS 真机未验 | 原 ASR/供应商任务查询缺口；重合成费用未定 |
| F0226 | 原录音 URL 匹配解除未知锁，旧样本不能冒充新训练完成；缺状态仍未知，声明与权属核验分开 | 训练 requestId/hash/CAS、稳定 vendor task 回查、重启样本任务恢复与权属回执 | 代码通过；无供应商写入/训练 DB 验收 | 未验 | 真机/训练未验 | 训练身份与权属证明、供应商恢复字段未交付；后台仍有实现缺口 |
| F0476 | 事实窗口/生成时间/样本与未知分列，档案变化标旧版，不冒充版本号 | 服务端档案版本与缓存版本失效/回读 | 代码通过；经营数据为夹具 | 未验 | 真实商家事实未验 | 后端版本合同缺口，不能用测试事实代替经营验收 |

### Discovery

| 编号 | 已实现 | 缺实现 | 代码 / DB | 正常 UI | 外部验收 | 真正阻塞 |
|---|---|---|---|---|---|---|
| F0013 | 首页编辑精选、来源解释、去掉开始时间推定进行中 | 当前城市/时间/资格与个人推荐版本 | 代码通过；仍消费运营 is_recommend | 未验 | 真实推荐未验 | 个人推荐消费者/版本合同缺口 |
| F0003 | 后续页检出、空页继续、继续加载、去重与旧查询隔离；未知日期明示 | 服务端日期筛选及固定快照/游标 | 代码通过；分页回调为夹具 | 未验 | 真实跨页数据未验 | 日期时区/未知日期政策未定；活数据页码漂移仍可能漏项 |
| F0004 | 未知/非法/负价不转零，null 价格边界不转 0；跨页过滤、展示价与现票价分名 | 当前可买库存价格、积分/现金统一筛选 | 代码通过；票价链未做真实 DB 商品验收 | 未验 | 真实票种未验 | 当前可买价及积分/现金口径未定 |
| F0005 | 显示真实“默认顺序”，不错误映射为附近排序 | 共用排序规则、活动同时间稳定 tie-break、版本游标 | 代码通过；实读默认 SQL，未新增排序 | 未验 | 活数据次序未验 | 缺共用排序/游标合同，不声称“最近发布” |
| F0008 | 复用推荐搜索固定运营词 | 当期词命中核对 | 代码通过 | 未验 | 当前公开内容命中未验 | 环境只读补证，无新增热度规则 |
| M0012 | 完赛参考直线距离与位置未知分开，null 不变零点 | 当前交通来源、城市/时长预算 | 代码通过 | 未验 | 真位置/交通未验 | 交通来源与预算合同缺口 |
| F0357 | 完赛加载/错误/空/位置未知分开，失败重新查找，查看参加条件 | 历史已玩排除、体力/时间预算合格补齐；默认闸未改 | 代码通过 | 未验 | 真实候选资格未验 | 推荐合格链/功能运营闸待交付 |
| F0466 | 道路/节点分章绘线，坏点整段降级，直线不换算步行分钟 | 实际步行用时、开放/闭店、安全路网 | 代码通过 | 未验 | 真机路网未验 | 交通/营业事实来源缺口 |
| F0018 | 显式 target、原 toggle 兼容、当前会员锁、目标关系回读、弱网未知与异主隔离 | 全写源唯一关系约束及历史重复处理 | 代码通过；MySQL 两连接同目标与旧/新并发、异主不受影响 | 未验 | 跨设备真实账户未验 | 历史关系不自动清理；真实业务补验 |
| F0104 | target 与旧 toggle，当前锁读和计数，scope 受控本人读回 | 原 postList/postLike 私密团可见性统一 | 代码通过；真实 MySQL 并发/计数与旧 toggle；私密新读负控 | 未验 | 合法跨身份未验 | 原读/写入口鉴权修补待用户批准 |
| M0018 | 明示排除/恢复不可用，保留帖文既有机制 | 跨场景负反馈存储/消费者 | 代码通过；只读 | 未验 | 未验 | 对象粒度与生效规则未定 |
| M0019 | 收藏与兴趣撤回、订单与游玩记录分开 | 画像历史重算与评分版本 | 代码通过；未写偏好 | 未验 | 未验 | 历史重算/生效版本规则未定 |
| M0020 | 解释编辑精选，不制造个人推荐理由 | 首页个人推荐消费者及 reasonCodes 词典/版本 | 代码通过 | 未验 | 未验 | 云端解释合同缺口 |
| N001 | 收藏页推荐偏好只读入口，当前生效状态未知 | 排除/恢复保存、来源列表、本人版本回读 | 代码通过；无偏好写入 | 未验 | 未验 | 偏好规则与持久合同未定 |
| N002 | 为什么看到 → 真实编辑来源 → 现有偏好入口 | 调整保存与新推荐版本解释 | 代码通过；只读 | 未验 | 未验 | 同 N001/M0020 |

### Coop / social

| 编号 | 已实现 | 缺实现 | 代码 / DB | 正常 UI | 外部验收 | 真正阻塞 |
|---|---|---|---|---|---|---|
| F0431 | 微信授权与逐笔发送分开，未知不沿用旧授权，收入通知结果未确认 | 逐笔通知 intent/status/渠道回执与恢复 | 代码通过；未新增发送 | 未验 | 微信渠道未验 | 当前资金接口无逐笔通知结果；频控/渠道未交付 |
| F0219 | 已接受与履约分开；已发未知只查原评价；身份/邀约隔离；唯一冲突明确返回 | 真实完成事实、真实多方评价并发验收 | 代码通过；资格未改，SQL 唯一约束沿原；客户端原评价夹具 | 未验 | 真实合作双方未验 | 真实履约事实缺口，不能把接受等同完成 |
| F0469 | 普通意见与体验未核验、奖励实际记录分开 | 逐评完成事实与独立已体验计数 | 代码通过；无新增奖励 | 未验 | 真实体验资格未验 | 后端完成事实投影缺口；奖励不造新规则 |
| F0148 | 全库条件查询、count/分页统一、公开评分与管理统计分开、切筛选/身份隔离、空筛选可切换 | 真实角色/门店端到端筛选验收 | 代码通过；MySQL photo/low/pending、跨门店与分页后空通过 | 未验 | 真实角色未验 | 正常 UI/真实账户补证，无新权限规则 |
| F0206 | 拟议条款与冻结合同分开，当前身份选项未知说明，不改已售/已结算 | allowedModes/version 消费与身份能力回读 | 代码通过；模式准入和比例未改 | 未验 | 商务条款未验 | 身份模式合同未交付 |
| F0258 | 保存所选成员、去重/校验，owner/type 删除，null/[] 语义与编辑回填 | 错误历史署名不可推断；真实编辑与事务回滚补验 | 代码通过；H2 真 SQL owner/type 隔离与相关角色测试 | 未验 | 真实创建/编辑未验 | 历史数据不猜修；设备/业务 DB 补证 |
| F0053 | 活动核验团码与玩家组队邀请码分名，沿原场次/资格/二维码失败闸 | 跨场次/误码扫描与实体码失败实跑 | 代码通过；未引入新组队工具 | 未验 | 实体码未验 | 合法扫码身份/设备条件 |
| F0064 | 进月历/切月重新读现目录，时间/错误/加载分开，旧响应隔离，空不推定空月 | 完整月份场次查询、新鲜度/时区合同 | 代码通过；仍是公开主题目录 | 未验 | 真月份未验 | 现接口不是完整场次源，仍有后端实现缺口 |
| F0463 | 自助体验记录明确不扣报名票权益，不代表商家确认 | 真权益消费账与商家核验 | 代码通过；没有改账 | 未验 | 真实核销未验 | 需决定仅记录还是权益扣减；不造商业规则 |

## 验证与已处理失败

- 最终 Node：57 文件 **478/478**，失败/取消/跳过均 0；并发上限 4。范围包括原三线相关消费者与新分页、原请求恢复、筛选与异主负控；全量 Node、UI lint、action ledger 由协调者集成执行。
- 最终 VPS：18 类 **171/171**，Failures/Errors/Skipped 全 0，退出 0、BUILD SUCCESS；正常编译全部 backend，并 testCompile 1281 份存量测试源码。没有本机 Java、Docker VM、Redis 或应用后端。
- 真实隔离 MySQL：`W2PlayerRecoveryMyBatisIT` 4 项覆盖 target 同目标双连接、新旧并发兼容、原 toggle、计数、其他会员不受影响、hash/唯一键/CAS/本人回读及 photo/low/pending 全库分页。`NpcCurrentRegistrationMySqlIT` 实测到期、续期、NULL 旧规则、ASR 跨到期、相等边界与同 Session 不缓存。均为测试夹具，不是合法账户/生产业务验收。
- 原 V2 145 项出现 2 个新测试夹具问题：Mockito stubbing 触发已有 Answer 的 null 参数，以及 IT 误用旧环境键。已改 doThrow 和项目现有 MysqlIntegrationTestConfig；V4 正常重跑，没有修改 POM 或压制测试。
- V3 中旧到期 IT 默认 opt-in 导致 1 skip，随后显式开关补验 1/1；V4 同时带显式开关，最终 skip=0。
- 自动抽取原报告的广回归曾误含协调者负责的 action-ledger.test；该文件挂起后仅终止本任务已核验命令/cwd 的子进程，未改台账或 baseline。最终 57 文件排除该集成门禁；不将中断测试称为通过。
- 正常提交使用协调者批准的临时 hook：只把 Java 执行位置改为同一专属 VPS 容器、gzip 精确暂存 tree、SHA 校验与 flock；原 endpoint/Linux、暂存 JS/JSON、既有 migration/prod 保护、DS 门禁全部保留。没有 --no-verify，没有改共享 hook/config；精确暂存 tree 的 hook 编译及全部门禁退出 0。临时 hook SHA-256 `cf9e3ffae8e8f8b7468a9c200f9d5f5d1aad0e5f68b13162baaca058e9c85f7a`；原 hook `74a873bf05614e1fcd4acdc6462b2bbd84bb069010ed35c57707832d64e5aa5d`。
- 编译/测试原日志仅本机任务临时目录，未作为公开日志或整包文档提交。报告保留可复核摘要与精确版本。

Java 命令：在专属测试 VPS /w2/player，以 `flock /tmp/w2-java.lock` 串行执行、MAVEN_OPTS=-Xmx512m、Surefire=-Xmx384m，正常 `mvn -B -pl chengyinhub-admin -am test`；`-Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Xmx384m`，`-Dtest` 指定：

`ActivityCollaboratorSaveTest,ActivityCollaboratorScopeMyBatisTest,FreeClubPublisherIdentityTest,OmsTicketPublicCreationInvariantTest,ActivityPublishRoleGateTest,MerchantReviewServiceImplTest,MerchantReviewMapperContractTest,MerchantReviewMerchantRowMyBatisTest,NpcChatServiceTest,AiCreatorServiceImplTest,AiThemeDraftRecoveryTest,W2PlayerRecoveryMyBatisIT,ClubPostWriteLifecycleTest,NpcChatFlagGateTest,MerchantNpcChatFlagGateTest,ApiAiNpcPassExpiryTest,ApiAiNpcVoiceChatFormatTest,NpcCurrentRegistrationMySqlIT`。

到期 IT 带 `-Dchengyin.npc.expiry.mysql.it=true`，只把协调者注入的测试连接映射到它已有环境键；不记录凭据。

## 接管前置与决策

1. 集成应用前先对目标开发/测试库评审并应用新增 `sql/migration_npc_chat_request_hash_20261013.sql` 和 `sql/migration_ai_theme_draft_request_20261013.sql`；本轮只在隔离测试库执行。前后端需成套部署：旧 backend 不确认 target/filter/requestId 时客户端保留未知/错误状态。
2. 私密俱乐部原 postList/postLike 是否按既有 canViewClub 收紧非成员访问，仍待明确批准。新 like-state 已有该边界；不把现状当作跨身份闭环通过。
3. 商家测试资格/额度、主动重生成与音频重合成费用、声音权属证明、偏好对象粒度/历史重算/生效版本，仍需具体合同；本轮不新增默认规则。
4. ASR/训练/生成供应商原任务恢复、经营版本、完成事实、完整月份场次、逐笔通知回执，是具体接口/实现缺口。当前安全未知态能阻止重复消费，不能自动取得缺失终态。
5. 正常 UI 截图、真实身份/异常闭环、真机音频、真实渠道/经营事实由协调者接管；未做微信上传、付费模型、OSS 写入、生产写入或上线宣称。

## Node 精确相关文件

在小程序目录执行 `node --test --test-concurrency=4 --test-reporter=tap`，附下列 `tests/unit/` 文件：

`activity-edit-time-window-contract.test.js`, `c01-home-search-state-contract.test.js`, `c78-group-code-qrcode-truth-contract.test.js`, `circle-receipt-contract.test.js`, `club-event-calendar.test.js`, `club-post-like-readback.test.js`, `coop-accepted-card-actions-contract.test.js`, `coop-invite-detail-speaker-contract.test.js`, `coop-invite-edit-exit-contract.test.js`, `coop-invite-entry-type-contract.test.js`, `coop-invite-exact-id.test.js`, `coop-invite-flow-contract.test.js`, `coop-invite-sheet.test.js`, `coop-invited-partner-project-view.test.js`, `discover-search.test.js`, `discovery-editorial-source.test.js`, `group-code-session.test.js`, `home-feed-figma-contract.test.js`, `home-hero-placeholder.test.js`, `home-list-retry-feedback.test.js`, `home-primary-task-viewport.test.js`, `home-private-data-epoch.test.js`, `merchant-ai-insight-contract.test.js`, `merchant-ledger-detail-state-contract.test.js`, `merchant-ledger-display-contract.test.js`, `merchant-ledger-filter-motion-contract.test.js`, `merchant-ledger-record-scope-title-contract.test.js`, `merchant-ledger-wxml-contract.test.js`, `merchant-ledger.test.js`, `merchant-npc-voice-size-contract.test.js`, `merchant-review-entry-contract.test.js`, `merchant-review-pages-contract.test.js`, `merchant-review-public-reply-label-contract.test.js`, `merchant-review-reply-edit-contract.test.js`, `mylike-card-name-contract.test.js`, `mylike-refresh-state.test.js`, `mylike-unlike-inflight-lock-contract.test.js`, `nav-polish-single-title-contract.test.js`, `npc-chat-component.test.js`, `play-finish-route-recommendation.test.js`, `play-free-explore-detail-contract.test.js`, `play-request-recovery.test.js`, `post-favorite-contract.test.js`, `publish-activity-edit.test.js`, `remaining-scene-state-contract.test.js`, `search-discovery-contract.test.js`, `search-sort-and-hot-words-contract.test.js`, `simple-ai.test.js`, `subscribe-recipient-contract.test.js`, `topic-route-preview-truth.test.js`, `ui-final-gap-zero-0830-contract.test.js`, `w2-circle-record.test.js`, `w2-coop-calendar.test.js`, `w2-coop-social.test.js`, `w2-group-code-names.test.js`, `w2-review-query-recovery.test.js`, `w2-search-pagination.test.js`.
