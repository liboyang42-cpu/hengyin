# route-card 影子验证施工清单（尚未编码/执行）

结论：先验证新增窄读与原详情，在稳定状态/固定statement时钟下的集合与九字段相等；另以确定时序暴露跨时刻/跨提交差异。当前review.patch只有Mapper候选，无生产消费者切换，无以下测试实现或运行回执。

## 最小fixture与owner

候选专属测试类：`ImRouteCardBatchShadowMySqlIT`，放admin现IM同package，另专属scratch SQL fixture；原 `ImRouteProjectionTest` 9条、原scope7及所有club/IM旧类保留。需要真实 `CmsTopicMapper.xml`（原详情+候选新SELECT）和真实MyBatis/Oracle MySQL，不用mock主题/club/报名/主圈数据来证明权限。

只复用既有 `TopicDraftVisibilityMyBatisTest.recreate` / `PublicTopicStationCountMySqlIT.schema` 中**已存在的投影表列形状**作DDL起点；补充真实圈层数据，不沿用它们“圈码全NULL只要求表存在”的简化。旧fixture的连接/建库helper可能有默认值/先DROP行为，**不直接调用**；沿E现IM显式 `CHENGYIN_MYBATIS_IT_PORT/USER/PASSWORD`、loopback、随机UUID exclusive CREATE成功才记ownedSchema、AfterAll只DROP该owned名并information_schema读回0。无skip、无环境fallback、无新生产DDL/依赖；fixture只在root批准测试端执行。

Mapper阶段必需8张表；消息投影阶段可另加im_message，完整列表影子再复用IM三表（共11张），不以只有topic表的假fixture代替关联权限：

| 表 | 最小参与事实 |
| --- | --- |
| cms_topic | 原selectCmsTopicVo全部投影列保证旧oracle真SQL可执行；gate另需member_id/club_id/circle_theme_code/is_template/circle_reviewed_at；候选核对九列 |
| club | id/member_id/del_flag；不得为原route gate新增status筛选 |
| club_member | club_id/member_id/status/del_flag |
| cms_registration | member_id/owner_type/owner_id/topic_id/payment_status/registration_status；不以虚构软删gate替代原谓词 |
| cms_activity | id/topic_id，供owner_type=2回溯 |
| merchant_chapter_offer | id/topic_id/chapter_id/merchant_id/status/valid_end |
| merchant_circle_supply_profile | offer_id/is_current/checked_at |
| cms_topic_chapter | id/circle_enabled |
| im_message（消息投影影子阶段） | 原消息/extra/hash/status，核对不写与原输出；仅Mapper阶段无需此表 |

先比较九列tuple，不能比较整个CmsTopic JSON（窄读其它列按设计为空）；按id map，不依赖SQL返回排序。真正列表消费者施工时再复用im_conversation/im_conversation_member/im_message及真实IM三mapper/原摘要与原群闸，按原可见消息序/id逐字段影子对照全列表。

## 第一阶段：可直接审阅的7个用例目标

1. `stableViewerAndPublicationMatrixMatchesDetailNineFields`：viewer=null/0/陌生人/作者；缺topic、del_flag=NULL/0/2、status/user_status未发布/已发布，club无/0/存在未删/缺行/已删；固定同一MySQL statement时钟和无并发写。新返回ID集合等于逐项原selectCmsTopicDetailById非null集合，九字段逐一相同。不要把原作者预览seam删掉，再用Java原isPublicRouteTopic核对作者草稿仍不可投影。
2. `memberOnlyAndPaidActivityExceptionsRemainDetailOnly`：club_member_only=NULL/0/1及历史异常其它值，当前/申请/已拒绝/已删除/缺会员；topic作者、club owner、直接topic票(owner_id命中及topic_id命中)、activity票回源；支付/报名状态2→退款或取消。详情能放行不等于IM可显示：flag=1仍统一不可用，不论作者/会员/owner/买家。club停用但未删的route沿旧gate，不借club群status=1改变结果。
3. `primaryCircleReviewAndSupplyBoundariesMatchAtOneClock`：五主圈逐一、NULL/未知圈、模板；审查/checked_at恰30天和前后、is_current=0、chapter关闭、offer状态/valid_end=NULL/恰NOW/前后；有效distinct merchant=2/3/4/5，多offer同merchant不凑数。固定时钟下旧新集合和字段相等；每次参数场景清L1或新SqlSession，不能让旧cached-null冒充新SQL。
4. `routeProjectionShapeAndStoredMessageStayExact`：原msgType、cardType或`[路线]`兜底、坏JSON/非数字/非正topicId、重复引用/缺行、STATUS_NORMAL/RECALLED/BLOCKED、其它卡片、NULL name/img/mileage/count。保留原9条unit。Mapper阶段只证明原projector所需九字段；未来consumer影子helper必须钉原projectRouteMessage源码，只把单条查询替换测试map lookup，比较content和safe extra及完整list字段/序，不反写原message/hash/会话摘要。
5. `selectedIdScopeEmptyAndActualStatementBudget`：0/1/64/257条route候选、重复topicId（R个引用/U个原正ID）、全部无关主题。空ID/NULL返回空且无全表装载；新只包含所选可见ID，query statement一条或collector无ID时零条，旧REST式新SqlSession逐项R条。用StatementHandler.prepare真计数、真实BoundSql参数和EXPLAIN JSON留证；不用mock次数/响应耗时冒充SQL性能。一次IN仍可能U次子查询工作，不能声称执行成本O(1)。
6. `databaseFailureDoesNotBecomeUnavailableTopicOrPerItemFallback`：仅owned测试库使某个必需gate表临时不可用，旧/新SQL应throw，不能转空集合/不可用topic或逐项兜底；finally复原并核对零写。重复请求在提交的下架/私密变更后必须重新查新事实，无跨viewer/请求缓存；在RR外层事务保持的旧snapshot明确记录，缓存flag不能伪装current read保证。
7. `perStatementClockAndCommittedChangeDemonstrateUnresolvedCutoff`：下面两种确定交错记录真实差异，不要求本不存在的任意时序等价，不改expected强行绿，不切消费者。该例是合同边界验证，不能并入前6例相等统计冒充全部等价。

这些是施工目标数，不是新增JUnit声明或已跑7例。实现前root需确定具体source/owner/registry，不在E当前会话dispatch。

## 确定时序反例，不使用sleep/重试

时钟反例：viewer不是作者，B为公开FITNESS、非private，reviewed/profile.checked_at都新鲜，恰3个合格distinct merchant；其中一个offer.valid_end=T+1秒。A为另一普通公开路线。用owned同一物理连接、每调用新SqlSession（或显式清L1），通过测试专属 `SET timestamp` 固定NOW，finally恢复timestamp=0并关连接：

1. NOW=T时批读[A,B]，B有3家，返回B；原第一项A也在T读。
2. barrier确认第一项完成，测试将该连接statement时钟设为T+1，无需等待墙钟。
3. 原第二项单读B因strict `valid_end>NOW()`只剩2家而返回null；原投影不可用，而先前批读持有B，若直接消费会显示其名字。

这是当前SQL的可复现源码推演，**尚未执行**。计划应记录原/批NOW值、ID/九字段与SQL数，不输出隐私正文。`circle_reviewed_at/checked_at`30天边界也会有同类差异；作者seam会绕圈层，所以该负控必须使用非作者，不能由错误fixture得到假相等。

提交反例：两个真实连接，batch在C1先读[A,B]；C2在barrier后提交B的club_member_only=1（或user_status=0），保持B原id；再让原逐项oracle在新SqlSession读B并投影。记录batch早读与legacy晚读差异，校验所有读无写、原记录不删除。不要用RR旧snapshot掩盖C2提交，也不能以lease/缓存TTL假定权限仍有效。

在root明确是否允许“本请求统一SQL快照/时钟”之前，这两例不能被解释成已授权的新可见性截止规则。即使固定状态下前6组全部相等，也不足以授权消费者切换；本轮交付保持原逐项读取。
