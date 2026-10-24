# D / WP10 首片：完整内容复制 owner 迁移候选

结论：已实现完整 copy 闭包迁出，而非仅包装 Controller/门面。主题、章节、节点、NPC、运行图/剧情/模板引用与协作者的复制事务由新 `TopicContentCopyServiceImpl` 执行；既有模板域承接私有模板复制原语；两类共享规则各回到独立 owner。候选供 root 受控编译、真库旧新对照和逐 hunk 整合。**D 未运行 Java/MySQL/HTTP，不能据此宣布行为等价或正式集成通过。**

## 1. 基线、工作区、授权

- 唯一来源公共仓库 `liboyang42-cpu/hengyin`；候选 parent/观察基线 `ad7e59fbf6a20e85e741318afe654c5411bf1101`，tree `7426dd108301e3dbe7d8c1a4623e6209dfbcc8d1`。
- 已审方案 `af251abc3a137e50a744bbd503af9d1ef0989912`，依赖其边界决定，不改写该 doc-only 候选。W19、signup、原广播及所有旧工作区保持冻结。
- 新目录 `/workspace/architecture-day-d-wp10-copy`，分支 `codex/architecture-day-d-wp10-copy`。本地 worktree 初始 HEAD 为公共 `2ec68899099a1e250df0949a8383bf85b5d3ffe0`；实现所据为已逐文件校验的 ad7e 只读快照，不用旧 checkout 的生产文件替换新基线。远端候选从 ad7e tree 只覆盖本次列出的文件，并独立核对 parent/tree/文件范围。
- 原云执行器 running/connected，本轮未恢复、删除、重建或清理旧区。未创建 Agent/会话，未触发 CI，未使用私有 chengyin 历史/秘密。
- root 本轮明确授权完整复制方法、两入口、三类共享 helper 的精确变动以及测试装配；共享 `CmsTopicServiceImpl` 最终仍由 root 逐 hunk 整合。没有取得其他资金/销售/发布/transfer 算法、ProductRole 规则/schema 的变更权。

## 2. 根因与实际文件 owner

原 `CmsTopicServiceImpl` 同时拥有复制图、编辑重建、发布、销售和资金编排。复制私有模板、运行身份重映射和 PLATFORM 内容槽读回还被其他编写路径使用；将它们全搬入 copy 并让编辑回调 copy，会形成错误依赖。

生产改动恰好七文件：

| 文件（`chengyinhub-system/src/main/java/com/chengyinhub/business/` 下） | 变化与owner |
|---|---|
| 新 `service/ITopicContentCopyService.java` | 两个内部应用命令，不新增 HTTP API/DTO |
| 新 `service/impl/TopicContentCopyServiceImpl.java` | 两个入口、十个复制专用方法、局部cache/IDs，完整内容图事务owner |
| `service/impl/CmsTopicServiceImpl.java` | 两入口保留原事务注解并委托；十个复制私有方法迁出；三共享helper委托；仅增加两项必要注入 |
| `service/ICmsMemberTemplateService.java` | 单一 `copyForContentOwner(templateId, ownerMemberId, forceCopy)` 原语 |
| `service/impl/CmsMemberTemplateServiceImpl.java` | 移入原私有模板 BeanUtils/归属/来源/时间/ID逻辑，沿用真实 mapper，不借普通 insert/update 重置另一套时间 |
| 新 `service/support/TopicTemplateRuleIdentityRemapper.java` | 无DB纯JSON转换，供新建/重建/复制共同使用 |
| 新 `service/support/TopicPlatformContentSlotGuard.java` | 仅调用既有公开role声明+读回合同，供官方新建/复制使用；不直写role mapper |

原模板 cache 仍为每次操作局部 Map；新模板原语不另开事务。新copy不引用 `ICmsTopicService`/原大服务、Controller callback、ApplicationContext或销售/资金/通知服务。原 `$RouteNodeMaps` 和原编写/编辑算法保持；copy使用仅含两映射的私有 `CopiedContentIds`。

调用链：原三条生产入口仍为 `/api/template/topic-template/use`（明确作用域会员、copyType=3）、后台官方copy（来源/参数限制由新应用owner执行）、`transferToClub`（原外层编排、copyType=2）。原 `ICmsTopicService` 58方法签名和全部 Controller、domain/DTO/VO、mapper XML、生产SQL、根pom、CI、ScheduleUtils均未改。完整调用者基线清单仍见 af251 的192调用点/55文件与7处传递；本片不重排授权。

## 3. 保持的业务与事务合同

两个新应用入口与原门面均 REQUIRED、`rollbackFor=Exception.class`。原 transfer 自调用门面后进入新代理，加入原外层事务；无 REQUIRES_NEW、afterCommit写入、外部动作或通用outbox。内容写入顺序仍是：来源/参数检查→新主题→内容槽→逐章校验/插入→节点/私有模板/NPC→unlock/模板引用→运行配置/路线/blocks→剧情→同作者通关规则→协作者→返回ID。源查询仍普通读，不新增资格/owner/version fence。

复制的白名单、显式重置与动态SQL默认值保持方案第5节原合同：生成未审核/未上架内容，不等同发布；`is_draft`没有偷偷补成1；票、资金快照、费用、库存、注册、通知、玩家运行记录不复制。REVSHARE→PERK及费用上限清空仍按原代码，包含官方复制；不会猜新的资金资格。

原异常类型/消息、ID校验、行数判定和不判定的现状保持。静态校验核对了12个入口/移动方法体（只允许查询接收者、局部映射类型和两个已批准helper owner替换），两份共享规则方法体保持原字节。原大服务其余 **192个方法体逐字节相同**，包括transfer/发布/销售/资金。既有模板服务的原方法体全部相同。

新内容返回ID的准确含义：所属调用事务提交后，才可确认本地复制创建；不是已审核、已发布、开售或已通知。若在外层事务中调用，ID不是提前提交证据。重复调用仍创建不同副本，没有新增服务端幂等或“丢响应可盲重试”的保证。

## 4. 测试装配与原有计数

`existing-test-counts.json` 固定受影响15个旧类 **92个原@Test**；名称/顺序及原断言不删、不改成功预期。仅调整真实owner装配、两项copy源码归属、复制日志owner：

- 12个copy表征类直接调用真实新应用服务：JourneyRules/NpcRemap/RecruitmentGuard/TemplateOwnership/JourneyStoryCarry/ChapterFlow/CopyTypeNpe/OfficialBoundary，以及OWN_C03_19/20/22/23。
- `TopicPublisherSlotTest` 只装配共享PLATFORM guard，保持原创建/拒绝断言。
- `ProductSourceWiringTest` 两个copy字段断言和 `RevshareGateWiringTest.copyDeclaresContentSlot` 改为新owner源码；非copy资金/产品断言不改。`TopicLifecycleWiringTest` 分界和 `$RouteNodeMaps` 反射测试无需改动。
- 新 `TopicCopyTestWiring` 是测试装配，不是复制替身，也不为生产加入空字段fallback。模板原语使用真实服务+原mock mapper。新 `TopicContentCopyFacadeTest` **2个unit** 验证同参数/ID委托与异常原样传播。

因此本候选相关unit入口计数为 **旧92＋新2＝94**，不是声称全仓只有94项。原其他unit/IT/前端case不变；原 `TopicTransferToClubTest` 的H2/spy测试完整保留，但不当作新真库复制证明。

## 5. 新25项真库表征：尚未执行

`TopicContentCopyMySqlIT`（完整FQN与25个case名见 `mysql-cases.json`）及 `TopicContentCopyMySqlFixture` 加专属scratch SQL。不是H2：真实7个生产 mapper XML、真实 `CmsMemberTemplateServiceImpl` 和 `ProductRoleServiceImpl`，Spring `TransactionInterceptor`/JDK代理使用同一个 `DataSourceTransactionManager`。每条生产mapper写在拦截器断言实际事务活跃，并限定可写七张内容/角色表；部分失败是在真实SQL成功后抛出，回读全部表检查原子回滚。

覆盖：

- 完整copyType3提交：新主题/两章同sort/三节点、共享模板只克隆一次、templateBindings/digest、两NPC与READY/PENDING声音字段、blocks/graph/story重映射、协作者、MEMBER槽、源行逐列不变；费用/published字段不泄漏。
- 同作者规则与独立/共享模板、官方PLATFORM源判据、明确member拒绝、非PLATFORM/缺失/软删来源拒绝、默认/畸形copyType保留现状、重复调用独立图。
- 主题零行、第2章、节点、NPC、运行配置、图、blocks、剧情、官方槽读回失败的原子回滚。
- 真实完整copy加入caller事务；真实transfer在原主题下架后抛错、创意帖协作失败、正常移交。**没有spy替换copy**。
- MEMBER槽零行、协作者零行仍可能返回成功的现状作为明确风险表征，不把它们包装成已修正确性。

范围限制：类别查询因fixture禁招商为mock；transfer外围canonical失效和创意帖协作为受控mock，不能声称真实商业冻结/创意帖持久化/完整Spring容器或资金/审核/HTTP/进程kill验证。没有微信/语音/付款真实调用；graph/blocks/story/bindings走真库，玩法设施/计划provenance仍由保留的既有unit覆盖，尚未实跑。

### 同一fixture在旧源运行

旧源只需复制以下三个新增测试文件/资源，**不复制任何新生产类或新owner unit装配类**：

1. `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/TopicContentCopyMySqlIT.java`
2. 同目录 `TopicContentCopyMySqlFixture.java`
3. `chengyinhub-admin/src/test/resources/it-schema/wp10-content-copy-scratch.sql`

它们不以新接口/实现为编译期类型；通过明确mode装配：

- ad7旧源设置 `-Dchengyin.wp10.copy.owner=legacy`（或 `CHENGYIN_WP10_COPY_OWNER=legacy`）；fixture硬检查新owner类不存在，实际调用原门面原实现。
- 新源设置 `extracted`（默认）；新owner/guard缺失或反射装配错误硬失败，不降级旧实现。实际原门面代理→新应用代理→真实模板/角色/mapper。

root用现有受控scratch runner显式 `-Dtest=com.chengyinhub.business.service.impl.TopicContentCopyMySqlIT` 登记，**registry gates={}**，预期25 tests/0failure/error/skip。无新增条件注解/Assumptions/自定义boolean opt-in flag；PORT/USER/PASSWORD仍必须显式由受控runner提供，缺失硬红。owner枚举仅选择比较对象，不是跳过开关。

各对象类型使用不同自增ID区间，避免节点ID和NPC/template ID巧合相同掩盖映射错误。每个case独立scratch库，结束drop并查information_schema确认0，输出 `WP10_COPY_CLEAN`。root需独立逐XML/25个case/cleanup/sourceSHA核对。默认比较资料路径 `target/wp10-copy-equivalence`，可用 `-Dchengyin.wp10.copy.evidenceDir=...` 指定两个独立目录；通过25＋25真实执行后再运行：

```bash
python docs/delivery/architecture-day/D/wp10-copy/compare_equivalence.py legacy-receipts extracted-receipts
```

比较器要求精确25个case名、真实owner标签、非UNSET结果；完整行集合、字段、响应/根异常、mapper写顺序相等。fixture仅归一化本次操作create/update/created_at时间与新checkinCode随机字节（另断言格式/新值/唯一）；**连surrogate IDs也严格比较**（每例相同起始ID/计数器），不归一化owner/角色主体、金额、日期、谱系、flags或图引用，不过滤多余/缺失行。

## 6. Schema、迁移、回退

生产schema/DDL影响 **零**。scratch复用既有 `cms-topic-it-schema.sql`，只在新资源中建立Chapter/Node/MemberTemplate/Collaborators/NPC/ProductRole和节点查询依赖的空offer表；包含本用例mapper所需列。该SQL不是生产DDL，也不声称这些简化约束/数据类型证明生产schema兼容。静态列覆盖通过，实际MySQL列/约束仍待root执行；缺列失败应按真实输出审查，不能改生产schema给测试让路。

没有历史回填/补槽/通知补发/数据删除或调度变更。代码回退恢复原实现/委托即可，保留已合法提交的新内容、模板、槽、审计及资金记录；不能清库或删回执。不能以旧暂存哈希代替新候选。

## 7. 未修风险与待验收

按root机械迁移授权显式保留：通用缺memberId的配置账号但无内容槽分支；MEMBER role声明吞异常/不读回；协作者insert不查行数；模板只查generatedId；模板资格检查在copy事务外；源图读一致性/软删并发无新增fence；copyType越界及parse默认；isDraft依赖schema默认；源REVSHARE在官方复制也降级。没有改ProductRole规则、猜票款主体或借此减少验证；后续正确性修复由root分别决定。

本片不等于整体WP10完成。T14旧/新真库表征、94项unit、发布/编辑/销售/结算交叉回归、Q5/T15三类真实变更演练、Spring完整容器/生产schema等尚待受控验证；root未提供通过回执前，不把静态PASS写成这些门禁通过。
