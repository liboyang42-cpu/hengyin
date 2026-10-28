# D / WP10 内容图闭环实现候选

六生产文件的机械迁移、原九类及root补授的必要额外21文件接线、真实Builder私有seam接线、32项真库夹具均已编写。修改的旧测试共31类366个@Test，另1个共享夹具无@Test；全部原case名与整文件业务内容可按限定装配表达式还原。**Java/MySQL未运行，尚不可验收或正式集成，不把源码清单当运行覆盖。**

## 基线、工作区、冻结保护

- 唯一仓库 `liboyang42-cpu/hengyin`。候选以公开 **91962c94af463cbfe9b4d4406f9d88d269b097e4** / tree **cba1815abaae3077103b9a9b01d27e0e9e4c81d0** 为parent/base tree组装；不从私有仓历史构建。
- `CmsTopicServiceImpl` 基线blob **e7b9f22805e8d28292b46e36c68c62e5d5a1d41a**。刷新原15文件指纹在 `base-sources.json`；九类仍141个case，`ChapterFlowWriteServiceTest` 使用 **1a14092e4aa90fa93addad0b7503fc4484ae4a74**，C M1的两条真实access装配完整保留，未覆盖成864版本。
- 独立工作目录 `/workspace/architecture-day-d-wp10-graph` / 本地分支 `codex/architecture-day-d-wp10-graph`。本地隔离Git壳HEAD是公共 **2ec68899099a1e250df0949a8383bf85b5d3ffe0**；仅授权文件从919逐blob恢复后编辑。**远端候选的真实parent是919，不是这个本地壳HEAD**；不得把相对2ec的本地全部diff用于集成。最终导出严格使用明确文件列表和完整919 base tree。
- 原copy/W19/signup/其他工作区全部保留。冻结copy实现 `0aa3c8a11989230bc7167c3afd5972981c3eeea5` 不改；冻结copy/B1状态的其它生产文件通过候选base tree继承。不切换、reset、删除他人分支/工作区，不派CI。

## 生产文件与实际写权

仅以下六文件：

1. `service/impl/CmsTopicServiceImpl.java`：三项必要注入；创建/重建/旧入口guard/删除章节helper精确委托；shared publish两个helper薄delegate；移除原25定义中不再归门面的23定义以及旧私有 `$RouteNodeMaps`。六公开create/edit入口、事务与原锁序不动。
2. 新 `service/ITopicContentGraphAuthoringService.java`：原明确图领域操作端口，无通用pipeline/callback。
3. 新 `service/impl/TopicContentGraphAuthoringServiceImpl.java`：17原定义及原两段inline runtime mapper写入；真实chapter/node/template/story/runtime/route写权和唯一实际Builder。
4. 新 `service/support/TopicContentWriteGuard.java`：7原定义，直接窄依赖Topic/Chapter/MemberTemplate mapper、Category、ProductRole；REVSHARE/ClubDirect/fee形状规则原样。
5. 新 `service/support/TopicRouteOutcomeReader.java`：declaredRouteOutcomes原读取算法及按原序冻结返回集合；publish与graph同一真实读取owner。
6. 新 `service/support/TopicAuthoredGraphIds.java`：不可变有序身份快照、ending JSON防御性复制，无DTO缓存、可执行动作或持久化状态。

路径均相对 `chengyinhub-system/src/main/java/com/chengyinhub/business/`。未修改模板服务/复制服务、资金/销售/发布/transfer算法、ProductRole规则、Mapper XML、entity、DTO、Controller、生产SQL、根pom、CI、registry、scheduler或依赖。

## 调用链和修复合同

ApiTopicController原认证/merchant scope/内容安全 → ICmsTopicService原六入口 → 原事务/owner/edit-window/商业编排 → 新graph真实写口。graph → content guard / outcome reader / 现有copyForContentOwner / 现有runtime编译器 / 内容mapper。新owner无反向ICmsTopicService、Controller、资金/销售/微信/通知服务引用。

- 创建原顺序保留：统计处原纯compile（适用时）→topic/属性key/角色槽/草稿flag→适用V1审核尝试→写章节点→template→story→route→runtime→原发布runtime校验→interaction→原适用发布snapshot→XP原catch→collab保存→ticket→原review/活动壳/统计/响应。
- 编辑原顺序保留：owner/三步V2锁/Scope→原商业/钱闸/完整payload→旧unlock快照→字段/媒体/原DTO修改→topic patch→hidden行锁读取。V1/非compiled：删flow→删collab→写flow；V2 compiled：删collab→compile与完整guard→删flow→写flow。之后hidden→template→unlock→story→runtime→interaction→route→XP→collab→ticket→商业/审核/活动壳。route expected/request/target版本表达式保留原字节。
- 图写口 `@Transactional(rollbackFor=Exception.class)`，默认REQUIRED加入原manager事务；guard/reader无独立事务。无afterCommit图写、REQUIRES_NEW、重试或新fence。
- 非REVSHARE不预读Topic/slot；REVSHARE按原左右顺序读Topic、slot，再ClubDirect；Category只在recruitEnabled=1时读取。legacy逐章写与compiled完整预检的差异保留。
- 纯编译器不跨阶段缓存；原可变DTO语义保留。只返回原顺序的不可变映射、集合与JSON快照。Builder的record/recordChapter检查在真实写入路径执行；旧算法副本已移除。
- 不收紧旧零行/可选依赖/XP或audit捕获语义，不把机械等价证明写成旧风险正确；不补历史记录、不新增幂等或财务规则。

## 测试接线与完整原用例清单

实际已改：九类141case仅在原调用处接真实 `TopicGraphTestWiring`，全文件可反向还原为919原字节；`old-test-matrix.json`列明全部case。另按root明确私有seam授权适配 `OWN_C03_05Test` 3、`OWN_C03_25Test` 6：同一断言和用例名，指向真实graph.Builder并freeze；ending构造类型随真实owner调整，无旧 `$RouteNodeMaps` 副本。新 `TopicAuthoredGraphIdsTest` 3case验证不可变、有序、JSON隔离、真实Builder检查，未运行。

发现范围缺口的依据：root本轮明确“9相关旧tests”，同时要求“若实际另有caller先报root，不猜删”。当前919另有21个测试/夹具文件涉及216个原@Test（夹具本身0），详见 `additional-test-callers.json`。六个直接反射/reader调用文件是：TopicProductTypeTest、NodeTemplateOwnershipTest、OldClientUpgradeRequiredContractTest、OWN_C03_13Test、PersonalGameplayRuntimeServiceTest、CmsTopicRoutePublishPolicyTest；其余为直接new原门面的create/edit/publish夹具。另读到SettlementRateConvergenceTest唯一create调用为null member在图操作前拒绝，该22case类不需要接线，已排除，不触碰该资金测试。

root随后明确补授“迁移所必需旧unit装配”范围，本轮已先生成 `additional-test-wiring-audit.md` 与JSON，逐文件记原blob/SHA256、实际行号调用链及必要性，再应用21文件最小改动；`additional-test-wiring.patch`现为实际改动diff。216个case/断言/金额/异常/顺序均保留。create/edit及已迁私有写seam接真实graph；publish仅在走已迁shared guard/reader时用shared装配，不构造无关graph，未迁publish算法不改。原collaborator doubles和service原样复用，无mock图服务直接成功、旧算法副本、skip或expected放宽。


完整清单在 `complete-old-case-inventory.json`：原九类141 + Builder两类9 + 额外文件216 = 修改文件内366个旧@Test，31个测试类和1个共享fixture；新增snapshot unit3独列，新MySQL32/62场景独列。`old-unit-selectors.txt`列出31个原测试类供root定位，D不改runner/registry。另只读核对GameplayReskinFixture的5个现有消费者15case：其中1个纯静态资源比对、1个现有MySQL IT，其余为间接编写夹具消费者，源文件未改；不把它们算入已改366或声称执行。所有Java清单均待root受控验证。

## 同夹具旧新32项真库设计与执行边界

- `TopicContentGraphMySqlIT` 32个普通@Test、62个明确scenario；`mysql-cases.json`为确切顶层名单，comparator独立要求每项scenario集合完整。无EnabledIf/Disabled/Assumptions/skip。类名IT，显式-Dtest opt-in；PORT/USER/PASSWORD三项硬前提，MySQL8硬检查，不加自定义冗余flag。
- `TopicContentGraphMySqlFixture`同一源码支持legacy/extracted测试装配，**生产无模式flag**。旧源固定919且新四个owner类必须不存在；新源必须真实实例化graph/guard/reader并把graph的Spring事务代理接入原门面同一manager。新旧业务body/schema相同；旧侧仅owner默认literal extracted→legacy，差异显式记录，不冒充字节完全相同。
- 真实生产Topic/Chapter/Node/MemberTemplate/Collaborators/ProductRole mapper、真实角色/模板复制服务、真实runtime编译器；graph主用例经过真实门面与Spring代理。NPC行/merchant offer空依赖表亦全量读回，authoring原算法不新建NPC。Category、media审核资产、注册钱闸、票务、XP、商业/审核/活动/provider边界是受控夹具；不能声称完整真实资金/审核/Spring/HTTP/进程kill验收。G27前两scenario运行真实runtime编译器，changed/zero行分支使用明确受控spy返回，缺依赖return也明确表征，不称该分支证明了新runtime算法。
- 新SQL `chengyinhub-admin/src/test/resources/it-schema/wp10-content-graph-scratch.sql` 从已实证copy scratch结构复用，加载原cms-topic-it-schema后创建独立图表，仅scratch夹具；不同表ID区间、同sort、隐藏节点/unlock、opening/ending/blocks、template/sysvar、runtime bindings、route CAS、跨域后续失败均覆盖。SQL未执行；该新路径必须由root精确登记TEST_FIXTURE，D不改registry/harness。
- 收据默认 `target/surefire-reports/wp10-authoring-equivalence`，沿现有runner递归artifact保存。每case保存完整before/final表行、原保护行验证、返回/根异常、生产mapper写序、含读的领域/mapper顺序与事务事实；CASE/END区分业务动作和后续观察查询。拒绝/回滚case断言所有真实行恢复。新checkin另验格式/唯一性，旧行另验原值。
- `compare_equivalence.py`要求两侧全部32 JSON/62scenario、准确owner、全部字段严格相等，surrogate IDs精确相等；只在fixture规范化本次audit time/新checkin字节。数据库JSON文本仍是原始字符串，不把其空格/字段顺序解析丢失；比较器保留对象键序、数值类型与小数原字面，不能让true等同1或把小数静默舍入。无静默剔除其他行/字段/异常/顺序；缺scenario、错owner、改行/异常/mapper顺序/事务记录均拒绝。**先由root独立核对两源/逐XML32零fail-error-skip/CLEAN/所有文件hash，再运行比较器**；receipt在cleanup前写，单独JSON存在不证明执行成功或清理成功。

## 本轮实际验证 / 未验证

已实际执行：

- Python scope校验PASS：15个固定源文件；168个未授权门面方法体逐字不变；25个迁移方法按限定owner/不可变值替换匹配；4处门面编排可还原原字节；原九类141、额外216和9个Builder seam case共366保留；旧Builder类已移除。
- Python比较器合成数据拒绝自测11/11 PASS。这仅证明比较器对指定坏输入硬拒绝，不是Java/MySQL或业务等价结果。
- 32个普通@Test/62scenario清单静态匹配；未发现skip条件。原冻结copy工作区保存不变；候选仅允许六生产文件、已授测试和本D交付目录。

未执行：Java编译/新3unit/原366case、MySQL32旧新/事务/cleanup、全Spring启动、HTTP、完整CI、T14新图等价、T15演练。没有调用真实WX/付款/退款、没有生产访问/DDL、没有dispatch。root此前copy25对/94unit/启动绿证据不复用为新图验收。

## 迁移与回退

无生产schema/DDL/数据回填，无历史重放；新snapshot仅内存值，不改接口JSON。整片代码回退必须连同注入/门面委托/新owner/测试一并回到919已修copy/B1状态；不恢复早期fail-open，不删除已提交内容、资金、回执或审计。新旧夹具只有受控scratch创建/清理，D没有实际运行。除待受控验证外，不新增业务决策；后续root审核真实结果后再决定是否集成。

## 历史导出阻塞与本轮交付

上一33文件本地候选c2f2f57的公共tree创建曾被自动审批两次拒绝，理由将授权识别为只读冻结；未创建远端commit/分支。其原patch/identity已另存保留，既有copy/W19冻结不改。root本轮已补授必要fixture接线并要求完整可fetch候选，本轮将依据这份新授权导出独立codex候选；实际远端身份以最终工具回读/交付回执为准，不以旧阻塞或离线tree冒充已push。D不派CI、不merge/deploy。
