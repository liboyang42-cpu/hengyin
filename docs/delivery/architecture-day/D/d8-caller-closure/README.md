# D8 三条写路径接线候选

Parent：`4061bac25dbe113574632dde61d4ae416185db77`；依赖此前 `5d25` 与 `4061`。已只读核对 root `06a571b1247620a9bdcea068be0ded973671d17c`：本片三个获授生产文件的预像 blob 全部相同。只交本片 delta，不覆盖 root 的 CmsTopicServiceImpl/其他 owner。保留 root 补的 NpcProfileService import。

| 实际路径 | 实现合同 |
| --- | --- |
| 章节 insert/update/delete/configure → 原 helper | scope/lifecycle/条款规则不变；topic ID 升序先锁父行，再按 chapter ID 当前锁读并复查 locator 归属；新增章节在原事务内按物理存量复核预算 |
| 节点 CRUD（含商家节点实际 caller） | 同一 topic 栅栏；更新/删除使用当前行复核父主题；跨主题更新按旧/新 topic ID 升序锁定；新增/迁入时复核物理预算，物理删除允许修复超限旧数据；不存在的节点仍返回0，不能凭旧 locator 误写后来出现的行 |
| AuditTaskServiceImpl → 原 topicSupplyAutoOnlineBlockReason → publication guard → write guard | 新决定即使 alreadyPublished/配额不足也检查预算；保留原 quota 错误优先级、原快照是否递增规则与副作用顺序。精确成功回执仍由 review 原早返回处理，不重校验后来变化的预算；举报不成立维持原状、驳回下架均不加发布预算拒绝 |

父行锁持至原事务结束。子行当前读与全量计数均禁用缓存并使用 FOR UPDATE NOWAIT；它不跳行，无法获得完整事实即异常回滚，避免与暂存的 child→topic 旧/财务锁反向等待。未删除的隐藏、待审、退出商家节点均计入300；无schema/依赖/资金/权限规则变更，无外部调用或自动重试。审核新闸位于原主题状态/快照/广场/活动壳副作用前；此前同一外层事务内的审核记录/通知沿原回滚合同，不冒充新增真库已验证。

必要旧fixture接线仅8文件：ChapterWorkPackageTest、ChapterSupplyTermsGateTest、ChapterServiceCapacityTest、TopicChapterRecruitmentGuardTest、CmsTopicChapterLifecycleGuardTest、CmsTopicChapterRecruitmentGuardTest、CmsTopicNodeCheckinCodeTest、FreeExploreNodeSupplyMySqlIT。复用原 collaborators 的真实预算 owner；真实 MyBatis mapper/spy 不替换锁查询。所有原用例名、业务/金额/异常断言保留；5处旧锁序断言改到真实 topic→当前子行锁，2处拒绝路径增加新锁未调用断言。纯读取 B02/Journey 与仅 owner 开关的 FreeTopicNoCommercialConversionTest 不抢改。

新增 unit：TopicContentCrudBudgetTest 10、AuditTopicBudgetDecisionTest 8。原 TopicContentBudgetMySqlIT 8例保留，增加6例成为14：真实章节反向锁序、节点共用父锁、并发节点插入300上限、RR旧 locator 与当前归属、物理删除修复、反向子锁 NOWAIT/3572 整体回滚。两处 RR 场景显式指定 REPEATABLE_READ。保留原32/62的夹具/SQL/receipt/comparator；不改registry/harness/CI。

实际仅执行公共预像/差异、原用例清单、Java括号与Mapper XML/唯一statement/cache/锁读静态检查。**Java/MySQL/HTTP/CI：NOT_RUN。** root 的集中CI40074201165停在旧导入编译错误，未进Java测试；本片不借用历史绿证明完成。

下一项是统一回归本片实际CRUD与审核回滚（尤其 task/IM/审计/公开/壳的完整真库原子性，新增资源IT没有冒充完整审核mapper链）。恢复部署、生产DDL、资金锁算法均不在本片。回退须按依赖整组撤销接线及核心，不能单留caller后撤掉其预算owner/Mapper。
