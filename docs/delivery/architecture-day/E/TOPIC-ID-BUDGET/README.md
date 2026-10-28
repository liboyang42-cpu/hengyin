# E 查询 IN 参数预算

parent：`4efef4db85ee94bfd4908c84faaadff8759e8c09`；独立分支 `codex/architecture-day-e-topic-id-budget`，工作树 `/workspace/hengyin-e-topic-id-budget`。仅 Query、专属测试与本目录；无需新增共享 facade/fixture patch，前序候选及 policy 均不改。无新 SQL/接口/cache/依赖/DDL。

先核对节点批读：公共集成 `de7f432ec6f0c1c6225168ecf2e4808210de42e0`，检查286个生产 Mapper 的节点类型/批读声明（本地273、补读13）。CmsTopicNodeMapper 只有单id/条件列表、全主题FOR UPDATE及新内容写侧锁读；TopicEditionReopenMapper.selectSourceNodes 是复制源全主题读取。未发现能按已授权章节ID集合、原商家审核/撤回门槛和顺序读取的等价批接口。当前节点列表C次保留，不扩D/G Mapper。

具体权限反例：ApiTopicController.topicInfo 只准予第1章，或 BRANCH_GRAPH 仅白名单本人中标节点；主题还存在第2章已审核节点。全topic条件读仍会读到第2章；selectByTopicForUpdate 更是只按topic_id且锁行，缺默认 merchant_member_id/node_audit_status 与 withdrawn 过滤，会读到未审核/撤回节点。它们不能用作只读、已授权子树批读。原代码应在章节/节点准入后才取数据，本片不切这条消费者。

实际改动：

- Query.refreshPublicNodeCounts 原把全部不同主题id一次传入 CmsTopicMapper.selectPublicNodeCountsByTopicIds；模板货架不分页，故该IN无项数上限。现在每次≤300个Long绑定，继续原status/商家审核/撤回过滤和group by，全部批成功后才回填，原List/Page对象、顺序、分页metadata和缺行零计数均保留。
- Query.buildTopicTemplateListVO 原把全货架id一次传 CmsTopicTemplateMetaMapper.selectByTopicIds；现在私有 helper 同样分块≤300，结果按原id映射，再走原推荐过滤与官方稳定排序。元数据无行仍留空，card输出不截断，DB异常不catch/不当空成功。
- 新MAX_IDS_PER_QUERY=300仅为本owner物理参数预算，不等同主题/节点数量规则，未改TopicSizeLimits或它的既有引用。每条IN最多300个Long参数（数字文本至多20字符/项；驱动内存/耗时未测）；每批计数group输出≤300行，元数据行数边界依赖canonical uk_meta_topic(topic_id)，不冒充生产约束已核验。

调用数量：不同主题id数U、货架元数据id数M，原各1次无界IN改为 `ceil(U/300)` / `ceil(M/300)`，空集合0次。这里增加大列表的受控分块次数，不声称降低总SQL或全链O(1)。601项变3批；现有章节/节点N+1及最终列表整体大小仍未解决，也未擅加分页/输出截断。root de7 的原计数SELECT与父片相同；Mapper/XML完全不改，scope仅来自原已准予列表。

读点：原一个IN读点拆成多个批读点，保留既有事务/隔离/缓存，不新增RR/锁。并发更新时不同批统计/元数据可能来自不同语句时点，不宣称跨批统一快照或严格撤权截止；原资格SELECT和推荐规则未变。没有新增未授权章节读、字段或业务外部效果。

新增 TopicQueryIdBudgetTest 五例源码：601项/重复/null ID的Page身份/参数上限/缺行；后批计数错误不回填部分结果；601货架的乱序批结果、缺元数据、推荐、官方稳定排序及无截断；后批元数据错误不假空；空列表无空IN/子树读。旧测试/fixture均不改，原小页1/5/20/24仍各1批。

实际执行：本目录 verify-static.py、方法机械对照、diff/精确文件门禁/有限秘密模式扫描 PASS；23个其他原方法体（含统计/模板批读）不变。**Java/JUnit/MySQL/Redis/CI/DevTools/完整gitleaks均NOT_RUN**。SQL次数是源码和待执行断言，不是运行性能。root统一同源回归；无迁移，回退只撤本Query/test增量，不整旧文件覆盖其他owner。
