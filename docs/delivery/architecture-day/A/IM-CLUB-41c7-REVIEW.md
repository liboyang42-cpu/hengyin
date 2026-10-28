# A 独立审查：E IM club 批量读 41c7

结论：**未发现本增量新增的可确认 P1/P2。** 新投影在稳定数据下保留旧 club 资格及列表字段；没有将批量读资格接入写授权。新增5条IT源码确实加载真实XML并计数MyBatis prepare，但本轮没有运行Java/MySQL/CI，不能宣称其通过、实测SQL预算或性能提升。此结论限于下述源码范围。

观察源 `41c7e1caef9fdd3548585f1646e267565ca252d7`，parent `4197f6a6b11d62cb0a742aae73d7167c93114585`，tree `982e685f3f175cf590fee6939b862358b43968a0`。独立工作树 `/workspace/hengyin-a-im-club-review`、分支 `codex/architecture-day-a-im-club-review`；本交付仅新增本报告，不改E实现/测试、生产、CI或其它交付。

## 逐项核对

| 关注点 | 精确源码与结论 |
| --- | --- |
| owner/current member | `ImServiceImpl:195–199` 与旧 `hasActiveGroupAccess:1180–1189` 一致：club存在、member非null、club.status Long1、delFlag Integer0，再以owner相等或当前成员放行。owner不要求额外ClubMember行；历史申请/退出/删除行不能代替当前成员。 |
| SQL/NULL | 原 `ClubMapper.xml#selectClubById:77` 按id且del_flag=0；原 `ClubMemberMapper.xml#selectByClubAndMember:29` JOIN相同club且del_flag=0，会员status=1/del_flag=0。新 `ImConversationMemberMapper.xml:131–143` 把club过滤放在外层、会员谓词放在EXISTS，Java仍检查club.status/delFlag；NULL不被coalesce成允许，EXISTS不会因多行扇出。字段仍按同一Club属性类型映射。 |
| 大小写/ID解析 | `ImServiceImpl:183/1047` 保留Java大小写敏感的 `startsWith("club_")` 与 `Long.valueOf`；未改成受MySQL collation影响的字符串JOIN。`club_+0012`、`club_0012` 与原12指向同一数值ID；负值沿原行为，空白/溢出/坏数字仍由旧守卫拒绝；大写前缀不会被新SQL额外认作club。SQL中的club/member IDs及status/del_flag是数值比较，不以数据库字符串大小写推翻Java前缀判断。 |
| scope/重复引用 | 第一条摘要SQL原 `m.member_id=#{memberId} AND m.deleted=0 AND c.status=0` 保持；仅从该摘要的TYPE_GROUP解析ID。`listClubSummaries:177–192` LinkedHashSet去重查询、Map按clubID回填，但外层仍逐个原会话输出，不合并重复club引用。无合法ID不发新查询；mapper空/null IDs明确1=0，不全表返回。 |
| 标题/顺序/未读 | `clubSummaryCounterparty:202–208` 的id=0、原bizKey、name、logo null→空串、memberCount、clubId与旧counterpartyOf club分支相同。nickname/memberCount null保持null。原列表字段、可见消息/路线卡投影、visibleOrderAt再messageID倒序、unread/muted均保留；无资格先continue，不把私有消息/标题/未读放进响应。未改unreadTotal或其SUM SQL。 |
| 撤权后的写授权 | 新clubs Map只在listConversations请求内使用，`hasActiveClubSummaryAccess`没有其它调用者。`send:867–878` 仍走治理guard与旧 `hasActiveGroupAccess(...,true)`，随后锁会话和发送者索引；`markRead:1194/1214` 仍经 `lockReadMember:1247` 检查旧实时群资格；投票仍调用原requireActiveGroupMembership/isActiveGroupMember。上述方法原文未改，未把本次列表投影作为发送、已读或投票凭据。原并发撤权/锁序是否全覆盖不由“源码未改”证明。 |

## 新5条IT是否真实、能测什么

`ImClubConversationBatchMySqlIT:29–48` 调用既有独占MySQL fixture，执行专属scratch club表SQL，再创建实际SqlSessionFactoryBean，明确加载IM member/conversation/message与Club/ClubMember五个生产XML，用实际SqlSessionTemplate Mapper注入Service；没有用mock ClubMapper生成影子expected。

计数插件是未改的 `ImConversationBatchReadMySqlIT.StatementCounter:294–304`，拦截 `StatementHandler.prepare(Connection,Integer)` 后 `invocation.proceed()`。因此具备测量这些真实MyBatis语句准备次数的装配；不计JdbcTemplate的seed/snapshot/EXPLAIN，不是全请求所有SQL、数据库内部扫描工作或延迟/RPS。

- `:61` 资格/完整行：原单项守卫和counterparty helper组成影子，比较完整List顺序/Map/未读；包含同club的三个数值bizKey引用、owner/当前会员/其它索引、停用/删除/NULL club字段及脏ID。
- `:91` 0/1/64/257群与64 owner群：断言旧1+3K或1+2K、新1/2；这些只是待执行预期。
- `:107` 在真库执行会员DELETE、member软删、club停用/软删后回读；snapshot比较五张表的读零写。更新通过JdbcTemplate无外层测试事务，装配意图为已提交后的重新读取，不是实际调用退出编排。
- `:123` 空/null/指定clubIds范围；从真实MappedStatement BoundSql绑定参数执行EXPLAIN FORMAT=JSON。仅解析/打印计划，没有预先宣称选定索引或性能门槛。
- `:139` RENAME club后必须抛错，finally复原，不以空列表或逐项fallback掩盖DB失败。

## 实际证据及未覆盖

实际只运行Git/Python/XML静态检查：解析ImServiceImpl的58个原方法组，仅listConversations变化，新增3个专属helper；send两重载、markRead两重载、lockReadMember、当前资格、ID解析、路线/对端投影及unread等原文一致。原member XML的20个statement/resultMap/sql条目逐字相同，新增1个resultMap和1个SELECT。XML可解析、diff --check通过、新IT为5个@Test。没有运行或借用E宣称的5184组合/旧绿回执。

未验证窗口：真实编译/五条IT及cleanup；实际MySQL Boolean/NULL映射、默认collation与EXPLAIN计划；新增IT未专门插入大写前缀、NULL memberId/owner或NULL cm.status（scratch与canonical会员列NOT NULL），这些没有被冒称实测；没有调用真实quit编排后再send/markRead/poll，也没有在途撤权并发测试。原守卫未改不等于该并发性质已验收。club-only声明的statement预算不覆盖team/hangout/路线跨域查询，也不证明无界列表容量或吞吐。未发现可据此直接定性的新增业务错误，不以缺测试代替P1/P2。

源SHA256（供root核对，均绑定观察源）：

- ImServiceImpl.java：`1cc9dca974971b256733ac8940f5ef171e6fd4f4af1b947410580318d5f57b03`
- ImConversationMemberMapper.xml：`10e80eda1322d27d0325659bd71f21ceedbf103a119a2ddd88dff288c0be41b9`
- ImClubConversationBatchMySqlIT.java：`26707d6d57bead81e12a17070c8050c82fbbe29c3a10307dc80151866c4d8c7f`

没有生产修复、迁移或回退操作；本报告不触发部署/CI，也不关闭其它IM权限/并发验收项。
