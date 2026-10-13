# B 包实现与验收证据

B 从交接 e67e72a 分支执行，只改消息/审计/事件/既有广场域和协调者归属的兼容接口、测试夹具；不合并/部署。源文件逐个原摘要与当前 SHA 见 source-manifest.json，白名单见 whitelist.txt，255 原ID逐个去向见 items-status.json。原项全部保留，任何局部代码/单测不代表整个原产品项完成。

## 已实现及实际调用方

- NPC：应用已核 B-ci 候选，26项静态契约验证当前登录主体/付款/报名现态/主题/有效期/拒绝先于provider。生产 NPC/资格查询仍分别归C/A，B未修改权限。
- IM：原 list/index 会话刷新、删除、当前账号和群type4排除已保留。原列表行已读、全部已读仍调用旧markRead签名（主动全部确认）；chat/index 首屏、历史cursor、after增量、轮询、文本/图片/位置/路线发送/重试、卡投影、删除及退出逐一接当前账号/会话/页与凭据守卫。原read锁序 conversation→member→当前资格→message保持，新增read_to只确认当前展示连续增量水位，旧水位不倒退，较新消息保持未读。
- 原请求：新增 /api/im/send-receipt 按认证本人、原会话和原clientMessageId只读；无当前资格拒绝。新本地工具发送前保存原ID并回读落盘，存储失败保草稿不发请求；未知先回读，found=false后用户重试仍用原ID及原payload，found=true合并原消息不重发。重进查同账号/会话原ID，不自动重发。清缓存/登出/删除代次失效，迟到回包不重建记录；本地不存聊天原文、图片、位置坐标。
- 历史路线：核B-route合同与manifest后应用，列表摘要/消息读取/发送及同请求回执统一投影；当前失效/下架/删除/私密隐藏名字保ID，不改原行content/extraJson/requestHash/摘要。私密作者/成员也隐藏历史卡；正常详情权限不改。chat路线缓存只用当前消息投影，不调用普通详情回填私密名称；轮询重核当前可见旧卡，待核/失败不保旧名，迟到公开回包不覆盖较新私密结果。公开卡只用当前安全封面/里程/点位数，不带历史扩展字段。
- 审核证据：既有CommunityTrust normalizeEvidence仍拒未接附件；成员报告/公开时间线不投reporter、原描述、evidence、internal、operator。原enforcement锁/指纹receipt/原申诉CAS保持，不猜多人举报或处罚政策。CommunityPost原soft delete、媒体orphan与证据snapshot留存已只读核对，无新留存政策。审计query只给既有platformAdmin；增加递归unionid/bank/idcard/identity/address/email/location/lat/lng及原文本联系方式投影，保持原数据库证据。
- 事件/指标/广场：见 analytics-verification.md。所有实际报名producer由A沿原registrationId提供eventKey别名，后端校验认证主体与原付款事实、归一原报名计权；不将客户端互动当现金原账。实际 creativesquare offset追加去重、原分页推进、失败保留和账号切换隔离已实现。专用推荐API没有已核小程序消费者的原项保留待核，不新开入口。
- 原报名通知：ISystemNotificationIntentService接口由B先导commit 1ce1f97/d52c765，A原样cherry-pick编译依赖。A在原首次建单事务固化registration-signup:<primaryId>原事件；B写PENDING intent，同key异内容拒绝。锁intent→conversation→member→原message，message和DELIVERED收据同事务；同intent重复回play原message不重复未读。固定最大ID、稳定游标、逐条事务，单项失败保持PENDING不饿死后页。新增migration位于chengyinhub-admin/sql第一层；Quartz原任务注册默认status=1暂停。未启用、没有外部渠道。

跨域旧write-action-workflow真实宿主已查：merchant/index扫描、merchant/game-node节点验证（C）、club/detail扫描、subpackageMember profile核销、participation-detail核销（A/共享）。本轮不越界改这些宿主或创建新动作；现有unknown锁/原receipt回读合同保持，由所属A/C包继续覆盖其资源链。

## 已执行证据

- 交接NPC26 + IM现有47 + 新IM12：针对性Node最终85/85。新增12覆盖落盘失败、原收据、明确重试原payload、重进、账号/凭据切换、清缓存、删除迟到、缺段连续水位、路线缓存当前私密与旧公开回包。见 im-evidence/node-green.txt。
- 用交接原chat源在隔离fixture运行相同新测试，初11项全部红；最终追加的凭据用例同属新增守卫，不把fixture当真身份。见 im-evidence/node-baseline-red.txt。
- analytics/广场主组59/59 + 既有推荐经营消费者28/28；红版本analytics26中18失败、广场4中3失败。明细见analytics-evidence；这些不同分组不累加为历史全包193。
- 运行过工作树整套Node：8231用例/8221通过/8失败/2skip。7个失败是新增账号/落盘前置令旧fixture失效，随后按本域最小修fixture并保原assert/负控，相关91项已逐类修复；1个release inventory摘要经协调者指定B单一owner，按相同4432控制全集机器生成刷新70条既有控制（来源行号及3条原IM认证收据读targets）；未改status/分类/验收/写API或动作证据。该初轮运行期间事件最后修补仍在进行，因此不计为最终整套绿。随后修完7个fixture与共享inventory，在固定源码59b2999冻结所有Node/生产源后重跑：8233 tests、8231 pass、0 fail、2 skip、exit0；完整日志 im-evidence/node-full-frozen.txt。其后仅Java IT增加CREATE成功flag保护scratch清理，Node源码/测试字节不变，Java仍未实跑。
- JS scope lint：556个生产JS，未解析标识符0、不存在导出0。原migration layout门通过、SQL splitter两条完整语句、未注册bean清单为空、IM XML解析通过、diff check通过。更新源后相关轻量门继续复验。
- 变更内容gitleaks无新增命中；root全源基线既有4条测试夹具命中区分保留，不加allowlist。扫描不公开密钥/临时SSH文件。

## Java、MySQL、真实身份与设备断点

本次Java新增/修改用例已编写但尚未实跑：ImRouteProjectionTest、ImServiceImplTest、ImLegacyReadLockTest、ImFoundationReadRecoveryTest（6）、ImNotificationIntentTest（6）、ImNotificationDeliveryTaskTest（2）、BusinessAuditLogQueryServiceTest（5），analytics的四个selectors见其报告。不能把“测试已写”当非零JUnit通过。

ImFoundationBMybatisIntegrationTest写4个真实独立随机scratch库场景（仅测试VPS gate CHENGYIN_IM_FOUNDATION_B_MYSQL_IT=1）：readTo/连续增量、原业务事务回滚intent、双worker/迟到重放一次消息与未读、同key异内容。均另JdbcTemplate连接回读；尚未执行，所以没有数据库结果证据。原IM并发IT也待同版本回归。测试migration没有生产DDL执行。

专用测试VPS SSH22授权正在环境配置，当前实际TCP grants为空；HTTP80未监听不能推断SSH不通。本包没有启动云端预置Java/MySQL/Redis，不读写生产库。待同提交编译、非零JUnit数、真实MySQL竞争/回滚/故障/独立回读后才能勾第二阶段。GitHub既有CI的PR base触发不含交接分支，workflow_dispatch工具未可用；已推送NPC检查点但未有同提交run，不能声称CI通过。

真身份消息双方/报名原事务投递/角色资格、微信开发者工具和真机、清缓存杀进程、账户A→B→A、实际私密旧卡/摘要和动态offset删改尚未验。A报名producer和通知触发在其独立PR，依赖未合并；需同集成快照验证。没有微信上传、任务启用、数据库部署或PR合并。

本地原ID记录可找回已提交消息；对杀进程后仍not-found的原发送，因不在普通wx存储落聊天原文/坐标，不能自动重建原payload，保持未知而不声称原消息已成功。若要跨进程完整payload恢复，需另核已授权安全存储实现，当前明确保留该恢复断点。新UI、群聊type4/旧搭子局、新通知渠道/经营/处罚/推荐政策均后置，原项未删除。

最终Java IT清理只在CREATE本次UUID scratch库成功后设schemaCreated并允许DROP；CREATE失败不DROP同名已有库，清理后information_schema回读。此安全修补已编码但未实跑。
