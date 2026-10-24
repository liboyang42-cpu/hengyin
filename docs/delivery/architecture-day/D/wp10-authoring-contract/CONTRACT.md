# D / WP10 下一片：内容图编写与重建合同（仅文档，未授生产写权）

建议实施一个真实的内容图 owner：拥有章节/节点生成、替换及 template/story/runtime/route 身份修复；原门面在既有事务内按原顺序协调协作者、XP、票务、商业校验、审核和活动壳。拆分为必要的领域操作，不用单入口重新排序，不接 Controller/门面业务 lambda，不创建 pipeline/策略平台。本片完成口径是这段内容图写权闭合，不是全部 create/edit、发布、销售、结算或整个 WP10 完成。

## 1. 基线、隔离和证据

- 唯一公共仓库 `liboyang42-cpu/hengyin`。读取基线 **864e708e2f38130e21780485e462f8dc147bb76f**，tree **4f3bfd77f199992c84cdf7d538d4554f5a712e7f**。`CmsTopicServiceImpl` blob **e7b9f22805e8d28292b46e36c68c62e5d5a1d41a**。下文行号全指该源，不套用 ad7 行号。
- 本地仅新增本 D 专属目录；原 `/workspace/architecture-day-d-wp10-copy` 分支仍 `codex/architecture-day-d-wp10-copy`、本地 HEAD 仍公共旧基线 `2ec68899099a1e250df0949a8383bf85b5d3ffe0`，原暂存复制候选保留。分析使用逐 blob 匹配的 864e 文件，不能把本地 HEAD 当当前分析基线。冻结远端复制源码 `0aa3c8a11989230bc7167c3afd5972981c3eeea5` 不改。
- 本轮 GitHub 只读取得并逐 Git blob 校验 15 个文件（九个测试类在内），见 `SOURCES.json`；25 个拟迁方法定义/23 个名称及调用点见 `METHODS.json`/`.md`；141 个旧 case 全名单、原 body SHA256、接线要求见 `TEST_MATRIX.json`/`.md`。扫描是词法分析，不是 Java 类型绑定或编译证明。
- root 已报告：旧/新 copy 各 25/25、零失败/错误/跳过、CLEAN，25 对严格比对相等；fac5/full40047105810 的 D 原92+新2单测通过，原 XML/JAR/startup/jdeps 已回读核验。以上仅引用 root 实证，本轮不重复执行，不外推为本片新图实现通过。864e/full40048901603 当时仍在执行，不记作整体通过。
- 本轮实际只做 Python 源码检索/哈希及文档校验；未执行 Java、MySQL、Spring、HTTP、DDL、CI。无生产源码/schema/依赖/runner 改动。root 另持有 SQL 精确 TEST_FIXTURE 分类与 Node 源位置修复，不重复触碰。

## 2. 文件写权提案与依赖方向

| 拟定文件 | 真正职责 | 边界 |
|---|---|---|
| 新 `service/ITopicContentGraphAuthoringService.java`、`service/impl/TopicContentGraphAuthoringServiceImpl.java` | 搬入 METHODS 中 graph 的 17 个定义（含 saveNodes 两重载）；实际执行图 mapper/模板身份补丁、story/runtime/route 写入 | 不持有 ICmsTopicService、销售/资金/审核/通知/Controller/ApplicationContext；不接 callback |
| 新 `service/support/TopicContentWriteGuard.java` | 搬入 7 个既有 guard 定义；内容可编写资格与章节形状，含原 REVSHARE/人头费/ClubDirect 规则 | 窄依赖 Topic/Chapter/MemberTemplate mapper、Category、ProductRole；不取得收付款、退款、报价或销售生命周期写权 |
| 新 `service/support/TopicRouteOutcomeReader.java` | 搬入 declaredRouteOutcomes 的真实内容读取/解析算法 | Node/MemberTemplate mapper + JSON，只读；publish 保留薄委托，不反向依赖 graph app |
| 新 `service/support/TopicAuthoredGraphIds.java` | 不可变的生成身份值；内部 builder 归 graph owner | 不含 Mapper/Service/DTO 回调、交易或 publish 状态 |
| `CmsTopicServiceImpl` 精确 hunks | 原位置替换为上述 owner 调用；两段 runtime 写入迁入 graph；保留共享 publish 的 reader/guard delegate | 六个入口、事务、权限、编辑窗口、商业/票务/发布/transfer/资金算法不迁；helper 的剩余编排不得重排 |
| 九个旧测试类及专属 test wiring/新 graph IT/fixture/schema | 真实 owner 装配及旧新表征 | 141 个原 case/断言保留；生产 schema 不改；fixture 缺列先报 root |

依赖方向：原门面 → graph app → guard / outcome reader / 现有模板复制原语 / route runtime / 内容 mapper；publish → guard 或 reader。guard/reader/模板域均不得反调原门面。`ChapterFlowCompiler` 留在原 impl 包，以既有纯编译器复用；新 graph impl 同包，不把它公开成通用平台。

graph 的 `copyTemplateForOwner` 仍使用原每次操作的 cache 和既有 `ICmsMemberTemplateService.copyForContentOwner`，不调用冻结 copy app。`remapRebuiltTemplateNodes` 原来更新新克隆模板 advancedConfig 的 mapper 补丁原样保留，不用通用模板 update 替代而悄悄新增 updateTime/其他副作用。本片不宣称已收拢模板域全部列写权；若要再将该补丁归模板域，须另授最小专用原语并表征，不顺手扩大此片。

## 3. 公开签名与值合同（拟定，不是已存在 API）

以下均为内部领域端口，无新增 HTTP。请求参数保留原 DTO 及 null/空/重复语义，不把 mutable DTO/List 冒充不可变 command，不复制 DTO 后丢失 `preserveOmittedAuthorConfig`/opening 的原请求内修改。新返回值才承诺不可变。禁止调用方传 executable、Supplier、Consumer、Strategy 或业务 lambda。

```java
public interface ITopicContentGraphAuthoringService {
    boolean shouldCompileChapterFlow(boolean chapterFlowV2, Integer productType,
                                    List<ChapterDTO> chapters);
    TopicAuthoredGraphIds saveTopicChapters(List<ChapterDTO> chapters, Long topicId,
        String username, Long memberId, Integer productType,
        boolean chapterFlowV2, boolean allowBundledDraft);
    TopicAuthoredGraphIds compileChapterFlow(List<ChapterDTO> chapters, Long topicId,
        String username, Long memberId, boolean replaceExisting, boolean allowBundledDraft);
    void deleteTopicChapterFlow(Long topicId);
    Map<Long, Long> existingNodeUnlocks(Long topicId);
    Set<Long> selectHiddenNodeIds(Long topicId);
    void restoreHiddenNodes(Set<Long> hiddenNodeIds, Map<Long, Long> oldToNew);
    void remapRebuiltTemplateNodes(Long topicId, Map<Long, Long> nodeMap, Long ownerId);
    void restoreNodeUnlocks(Map<Long, Long> existingUnlocks, TopicAuthoredGraphIds ids);
    void persistJourneyStoryAfterChapterRebuild(Long topicId, String storedStory,
                                              TopicAuthoredGraphIds ids, String username);
    void persistCreatedRuntimeBindings(Long topicId, String runtimeRaw, TopicAuthoredGraphIds ids);
    void persistRebuiltRuntimeBindings(Long topicId, String runtimeRaw, TopicAuthoredGraphIds ids);
    void persistTemplateInteractionRules(Long topicId);
    void saveRouteDefinition(Long topicId, String requestedMode, String rawGraph,
        TopicAuthoredGraphIds ids, Long requestExpectedVersion,
        Long expectedVersion, Long targetVersion, Integer productType);
}

public final class TopicContentWriteGuard {
    public void assertLegacyChapterFlowWritable(Long topicId);
    public void assertChapterShapeForTopic(ChapterDTO chapter, Long topicId);
    public void assertTemplateOwned(Long templateId, Long memberId, boolean allowBundledDraft);
    public void assertBranchGraphOnlyForOrienteering(String routeMode, Integer productType);
    // 两个 assertChapterShape 重载、assertAllowedValidationMethods 留为本 owner 私有算法。
}

public final class TopicRouteOutcomeReader {
    public Map<Long, Set<String>> declaredRouteOutcomes(Long topicId);
}
```

`compileChapterFlow` 的 replaceExisting 不是新增业务选项：原 create 传 false，原 V2 块化编辑传 true；仅迁既有两个调用点。`saveNodes` 五参重载无生产 caller，是原反射 seam；随实现移到真实 owner，相关测试只改反射目标，不制造运行期 fallback。`assertChapterShape` 一参重载同样无生产 caller。确切定义和每个 caller 在 METHODS 中。

`TopicAuthoredGraphIds` 必须包含以下字段，均按原记录顺序生成；公开只读 accessor，不提供 record/mutate：

| 字段 | 类型/要求 |
|---|---|
| openingChapterId | nullable Long，不推断、不补默认 |
| endingChapters | 按章节顺序的不可变 List<EndingChapterSnapshot> |
| clientToNew / createdClientToNew | 不可变、保留 LinkedHashMap 迭代序的 Map<String,Long>；二者不可混用 |
| oldToNew / chapterOldToNew | 不可变、保留原序的 Map<Long,Long> |
| newNodeIds | 不可变 LinkedHashSet 顺序的 Set<Long> |

EndingChapterSnapshot 仅含 `Long chapterId, String title, String summary, JsonNode ending`；ending 存入时 deepCopy，读取时返回 deepCopy，List 不暴露可写树；不通过 serialize/parse 引入新异常时点或重写 JSON。转为既有 `JourneyStory.EndingChapter` 在原 story 调用位置完成。builder 保留原 `$RouteNodeMaps.record/recordChapter` 正 ID、重复 oldId/clientKey、key 长度等检查的执行位置与异常；生成完毕才冻结快照，不按 sorted map 重排，不丢未知值/重复错误。`Collections.unmodifiableMap(new LinkedHashMap<>(...))` 等保留顺序；不靠 Map.copyOf 承诺迭代序。

`existingNodeUnlocks`、`selectHiddenNodeIds`、reader 返回集合也做原序的不可变快照（reader 值 Set 亦不可变）；当前 validator 对 outcomes 只读取 get/contains，不修改传入集合。guard 输入仍是原 ChapterDTO。原 `$RouteNodeMaps` 在此片暂保留原字节作为既有反射 seam；新运行写入仅使用新 owner 的 builder/返回值，绝不保留两条生产图写路径；清理旧测试 seam 单列后续工作。

两个 runtime 方法分别搬原 inline mapper 写段：创建原异常 `主题运行配置保存失败`，重建原异常 `主题运行配置重映射失败`。二者只拼接原 ThemeRuntimeConfig.remapForNodeSave → TopicTemplateRuleIdentityRemapper.remap 和一次 mapper 更新；if(raw/config!=null) 仍由原门面在原位置判断，发布期引用校验仍留原位置。不要加通用 mode/策略枚举。

## 4. 全部入口与必要 caller 修改

- 公开接口六方法签名/返回保持：createTopic 3491、createTopicV2 3499、createTopicV2WithReview 3505；updateTopicByOwner 3860、updateTopicByOwnerV2 3867、updateTopicByOwnerV2WithReview 3874。V1 create 的 blocks 拒绝仍先于 createTopicInternal；Long 包装入口仍调用原 review 入口。主 caller 是 ApiTopicController：create V1/V2 365/372，内部 542–549；update V1/V2 690/697，内部 758–762。Controller principal→merchant owner、文本安全、媒体、quota、草稿幂等控制、响应和异常映射不迁。
- createTopicInternal 3781/3783/3784/3785/3789/3795：只将原图写段指向新 owner；3792 的发布 runtime 校验及 3796+发布 snapshot 算法保留原门面。
- rebuildTopicContent 4158、4287/4289/4291/4295/4298/4299/4300/4301/4306/4309/4322：精确替换；deleteTopicEditableChildren 4545 仅将删除章节调用委托 graph，紧接 4546 的协作者删除保留原顺序。
- updateTopicByOwnerInternal 3912 → guard.assertLegacyChapterFlowWritable；不改此前 supplyReview.lockTopic → lockOpenTopicBundleTask → topicForUpdate 锁序、不改 owner/readback、Scope 和 keptDraft/submitDraftV2 算法。
- 共享 publishDraftInternal 2464 → 原 helper 的 guard 薄 delegate；normalizedBranchRouteForPublish 3376 → 原 helper 的 reader 薄 delegate。publish 的其余方法体和快照/审批算法字节不动，只允许这两个 helper 本体的精确委托。
- graph 内的 chapter/template guard、route outcome reader 调用按 METHODS 改成对应 owner；containsNodeKey、copyTemplateForOwner 为 graph 内部实现；原 remapTemplateRuleIdentities 私有 delegate 可由图内直接调用既有纯 remapper，原门面该 helper 暂留，不借机清理。nullToEmpty 为原地局部纯 helper 副本，不创建公共框架。

所有拟迁方法原为 private，生产调用点都在该类；METHODS 按同名重载参数个数区分（5/7、1/2），附原 body SHA256，供 root 授权后逐 body/hunk 核对；没有把纯编译器 ChapterFlowCompiler.compileChapterFlow 错认成迁移的持久化同名方法。外部调用清单限定已核对公共源码，不外推外部 HTTP 客户端。

## 5. V1/V2 创建：原逐步时序

下表是必须保持的调用顺序；任何 `!saveDraft/simple/V2` 条件也保留，不为了单入口跨越其分支。

| 顺序/原位置 | V1 | V2 | owner/纯值 |
|---|---|---|---|
| C0 3492 | 拒绝 blocks，再入原内部方法 | 不走 V1 blocks 闸 | 原门面 |
| C1 3584–3607 | member/name/collaborator形状、ai_simple、券归属 | 相同 | 原门面；无新授权规则 |
| C2 3610–3751 | 统计节点展开，字段/媒体/玩法校验、产品/俱乐部/池/招募/default/route初值 | 有 blocks 时在统计位置第一次调用原纯 compiler；其余同原分支 | 纯编译只用于原统计，不缓存到后续写入省掉第二次编译 |
| C3 3753–3767 | 插 topic→syncInitialAttributeKeys→内容槽→草稿标记 | 相同 | 原门面/既有域服务；零行检查现状不收紧 |
| C4 3770–3777 | pro非草稿先尝试旧 auditTask，原 catch 语义 | 不走此旧审分支 | 原门面 |
| C5 3781 | 非块化逐章 shape→insert chapter→逐node template guard→insert node | 经典定向或自由探索显式blocks：compile→逐章shape/逐node模板及非地点到达校验→写章/node/blocks；其他走原legacy | graph 返回不可变 ids；allowBundledDraft 原表达式 `V2 && (saveDraft || !simple)` |
| C6 3783–3786 | template重映射→story→route | 相同 | graph 依次调用，不把 route 后移 |
| C7 3787–3795 | 有runtime才写bindings；simple非草稿随即原assertThemeRuntimeReferences；再 interaction rules | 相同 | graph写，门面发布校验仍夹在原两步之间 |
| C8 3796–3806 | 适用simple分支发布 runtime/route snapshot | 相同 | 原发布算法，不迁 |
| C9 3809–3811 | XP autoAllocate，异常记录后继续 | 相同 | 原 XP 服务/原 catch |
| C10 3816–3817 | saveCollaborators→saveTickets | 相同 | 原门面；不得移到 C5/C6 前，票务业务不迁 |
| C11 3820–3838 | 非草稿媒体发布闸→projectReview；selfHost 非草稿债务闸→活动壳 | 相同原分支 | 原商业/资金/活动 owner |
| C12 3841–3847 | 统计更新→pro非草稿 publishBeforeReview→原 result | V2 pro非草稿 submitTopicBundle；否则原 result | 原门面；提交/审核/发布状态不由 graph 宣称 |

## 6. V1/V2 编辑：原逐步时序

| 顺序/原位置 | 原效果 | owner/返回值 |
|---|---|---|
| E0 3882–3925 | id/存在/owner；V2按原三步锁定并重读；name；V1 blocks拒绝+legacyWritable；读isDraft/Scope，SELLING拒绝，open bundle simple切换拒绝 | 原门面；只legacyWritable委托guard |
| E1 3926–3952 | WHITELIST仍只原白名单更新/适用审核刷新；REBUILD才进入以下步骤。submitDraftV2/keepDraft/后续审核/草稿标志/返回保持原算法 | 原门面，禁止把WHITELIST送入图重建 |
| E2 4144–4156 | saleTermsBefore→保留缺省作者字段→免费转商业保护→注册钱闸→完整payload→署名形状 | 原门面，顺序和DTO内修改不变 |
| E3 4158–4166 | 读取existingUnlocks；从旧story恢复opening缺省；V1原blocks闸 | graph只返回不可变unlock Map；opening处理保留原位置，不能与hidden读取合并 |
| E4 4168–4282 | ai/coupon/统计/媒体/字段/club/default/招募/拒绝状态→topic patch→syncInitialAttributeKeys | 原门面，不合并创建/编辑DTO映射 |
| E5 4284–4287 | 算allowBundledDraft；selectByTopicForUpdate读取hidden节点 | graph只返回hidden Set，读取必须在topic patch之后 |
| E6a 4289–4292 | shouldCompile=true：先删协作者→compile/完整shape和template guard→删旧node/chapter→插章/node/blocks | 协作者删除仍门面；graph.compileChapterFlow(...,true,...）。不能提前compile或先删章节 |
| E6b 4293–4296 | shouldCompile=false：先删旧node/chapter→删协作者→saveTopicChapters原legacy/分流 | deleteTopicEditableChildren仍门面协调两个调用；graph只做flow删除与后续写入，不能统一成E6a顺序 |
| E7 4298–4303 | hidden恢复→template引用重映射→unlock恢复→story重映射 | 四次明确graph操作，消费同一ids，不交换先后 |
| E8 4304–4323 | 有runtime才写bindings→interaction rules→按原expected/version/legacy BRANCH_GRAPH回填→route CAS | graph写；expected/request/target三个版本值由原门面原表达式给出，不推断新版本 |
| E9 4324–4332 | XP（原catch继续）→saveCollaborators→saveTickets→commercial conversion复核→非草稿媒体发布闸 | 原门面/既有域服务，不进图owner |
| E10 4337–4366 | saleTerms比较→canonical失效或pendingReview刷新→适用projectReview→原V1驳回audit尝试→非草稿壳同步 | 原门面；不移动、更改或抽象这些算法 |
| E11 3940–3952 | 回外层：适用draftFlag CAS→bundle或publishBeforeReview→原结果 | 同一外层事务，图失败/后续失败均按原传播 |

这些公开小操作是为保存原编排缝隙，不是通用执行步骤注册。纯值只有：shouldCompile的boolean、旧unlock/hidden快照、写后ids、reader outcomes；不返回“后续要执行的业务动作列表”。既有 CompiledFlow/CompiledChapter 含原mutable DTO，留在 graph impl 内；统计处的原纯compiler结果也留原方法，不跨owner公开，更不拿一次预编译替代原多次调用改变异常时点。

## 7. guard 短路、读取顺序和事务

`assertChapterShapeForTopic` 原式必须原样：仅 termsMode=REVSHARE 才按 Java 左到右读取 topic，再 activeContentSlot，再调用 ClubDirectMerchantPolicy.payerClubId；非REVSHARE不读这两项。之后进入shape：audio长度→recruitEnabled/maxMerchant→仅启用招商才查Category并校验→termsMode/ClubDirect→perkMinValue→REVSHARE正数且两位人头费或其他档拒绝fee→validationMethods→maxNodeXp。不得预取Category/slot，不给null添加新默认，不吞异常。原shape(c)转shape(c,false)的行为保留。

块化路径先纯compile，再完整遍历所有章的shape和所有模板资格，及非地点节点到达方式4/5拒绝；仅之后（replaceExisting）删除旧flow，再写入。legacy路径每章guard后立即写章再逐node guard/写node；不能把它“优化”为全量预检。同作者私有草稿模板四字段判据、无/负templateId放行、原异常类别/文字不改。

六个原入口保留 `@Transactional(rollbackFor=Exception.class)`、REQUIRED、原隔离。新graph写口使用同一manager的REQUIRED加入外层，不用REQUIRES_NEW，不在afterCommit写内容；guard/reader不新建异步/独立事务。原edit锁顺序、hidden/node锁读取位置、club锁时点都保留。此端口是原已验证owner/Scope后的内部图参与者，不宣称它新增了一套完整授权；无新增HTTP暴露。unit直接new的装配不冒充Spring传播。

## 8. 原测试接线与旧新真库验收

九类合计141个原@Test：ChapterFlowWriteServiceTest44、ChapterFlowTransactionTest2、CmsTopicDraftBindingsTest9、TopicUpdateByOwnerTest38、TopicDraftPartialSaveGateTest19、TopicDraftLegacyFieldPreservationTest5、TopicJourneyStorySaveTest4、TopicEditActivityShellTicketSyncChainTest18、TopicDraftSubmittedOrderTest2。`TEST_MATRIX`逐case保留原body SHA256。这里只计这九类，不把fac5已过的copy94加成新图覆盖率，也不宣称这141已过。

专属test wiring要装真实graph/guard/reader并共享旧fixture的同一mapper/category/role/runtime/template服务，不能让通用“所有field mock”自动把新增owner mock掉。必要反射目标移到真实owner，不改异常、行数、顺序、资金/票务断言；copy相关仍接冻结copy owner。原ChapterFlowTransactionTest是H2+JDBC adapter，只保留其原有意义。测试中调用其他票/活动controller的case仍原装配，不替换成图服务成功stub。

计划32项真库对照见 `GRAPH_CASES.md`（仅验收设计，不是已实现/执行的32测试）。旧侧固定本基线原门面，生产无新owner类；新侧固定后续root集成源码，实际新owner必须存在并经Spring代理接入。两侧只复制同一fixture/SQL与显式legacy/extracted默认差异；业务body/schema逐hash相同。不得保留生产双轨开关、skip/Assumptions或只跑新服务自证。

存储使用独立UUID scratch、真实生产 Topic/Chapter/Node/MemberTemplate/Npc/Collaborators/ProductRole及图所需属性key mapper；需要列时仅专属scratch夹具，不改生产DDL。每次不同对象ID区间，源节点/章节有相同sort，模板跨节点复用，BRANCH_GRAPH/clientKey/hidden/unlock/opening/ending/story/runtime/NPC引用齐全。注册、票务、审核、XP、活动等边界若使用受控stub/JDBC事实adapter，分别记明：真实mapper图闭环，不称完整真实资金/审核/销售验收；provider动作受控为0。

每case采集：返回或根异常class/message、完整相关表行、原行前后值、原生产mapper statement执行序、关键领域调用序及事务参与事实；不只统计count。trace须能区分C/E各步、模板/角色/Category读取短路和协作者delete/insert交错。调用真实门面后在外层抛错的case必须使整个图与原协作者/模板补丁回滚；受控票务/审核失败不能只stub copy/graph。

新旧各自逐source/expected case names/XML零fail-error-skip/独立CLEAN成立后才比对32对receipt；输出放 `target/surefire-reports/wp10-authoring-equivalence`，复用现有runner递归artifact，不改harness。ID按相同隔离夹具要求精确相等；只允许operation audit time/新checkin随机字节规范化，原行时间/原checkin另断言。异常消息、flags/金额/条款、读写顺序、图/模板/NPC原值不可归一化。实际API封装和完整Spring启动另由root full验证，不用机械图等价证明旧缺陷正确。

## 9. 未解决风险、迁移及回退

没有新增schema/DDL/历史回填/数据迁移；返回值不落库、不改序列化字段。copyType与旧MEMBER槽等冻结问题不触碰。此片不新增并发fence，不证明既有外部HTTP授权完整或并发编辑安全，不消除原XP/audit catch、协作者零行未检查、runtime依赖缺失时原return。shared reader/guard移动只是owner变化，不以等价证明这些行为正确。

回退按root独立候选整片撤回owner+门面+hunks+tests，须同包构建；不能只撤新类而保留注入/委托。无数据schema回退，但已经提交的业务内容不能用代码回退删除/重放。后续授写前请root按上述新文件、25定义、两段runtime、共享delegate和原编排缝隙分配精确生产权限；当前只有文档，不实施生产修改。
