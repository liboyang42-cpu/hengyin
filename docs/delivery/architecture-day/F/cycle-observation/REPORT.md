# F：27471 模块、包、类型及 Mapper 写入口依赖观察

结论：27471 的**直接 Maven 声明图为 6 节点/7 边**，已解析且正文出现同名 token 的**模块 import 子图为 6 节点/9 边**，这两张子图均为6个 singleton SCC。不能据此称系统无环：同一查询的**113 个 Java package 节点/562 边中有4个非平凡 SCC**；2445个文件主类型节点/8035边中确认2个非平凡类型 SCC。依明确传播指标选出的**3条包级环**已逐边追到真实代码位置；不把它们冒称3个类环、Spring注入环或运行时递归。

本批只交观察/证据和复用脚本，没有治理这些环、扩 owner、建立/接线新门禁或新增 baseline。没有 Java/DB/CI 运行。第三 private Mapper guard 5451672 保持冻结。

## 来源、工具复用和查询边界

- 唯一观察源：公开 `27471b00989e15f7641f4ee77c497cca2f9e53c1`，tree `51322a16c1085b4a59967b1265666a0f3b20df9d`。独立分支 `codex/architecture-day-f-cycle-observation`；开工 HEAD为该SHA、工作树干净。只新增 `docs/delivery/architecture-day/F/cycle-observation/`。
- 复用公开 `02e69111a83d19d2ad53051a44c629d5e0a5a38a` 的 `dependencies.py`：immutable Git blobs、PACKAGE/IMPORT、已有 Java lexical helper、SCC reachability。先逐字节核对依赖的4个公开 helper 文件；没有把旧候选的 policy/baseline/门禁迁入当前仓库。只调用只读 inventory/辅助函数，不调用其 gate。
- Mapper 写入口复用27471现有 `gate.inventory` / `literal_write_target` 与原 XML/include/SQL helper。`ci/dependency-audit-gate.py` 是 Grype 漏洞报告门禁，不能替代模块引用图；现有 jdeps producer 可给真实编译依赖，但本批没有27471同源原JAR/raw/index制品输入，不运行JDK或凭旧制品补图。
- 输入是6个Maven模块的生产 `src/main/java`，共2460文件：2445进入观察；13个含 unicode escape/text block、2个全注释/无法取package文件不作肯定解析。只解析可唯一定位到本仓库文件主类型的**非static显式import**，并要求去注释/字符串后的正文出现其简单名；86个无正文同名token的显式import不计入边。8059条import记录去重成8035条不同类型边。
- 这仍是 lexical 查询，不是 Java AST/编译器。正文 token 不能普遍证明符号绑定；30条 nested import按已知外部文件主类型聚合而非精确JVM嵌套class。所选关键环另有人工源码核对。没有把全仓正则匹配称作编译/方法调用证明。
- **不是用目录名生成边**：模块来自根POM及实际源码的物理归属；package来自Java声明；边必须有已解析的import+正文token。table→namespace来自原XML写语句。`package_owner_modules`明确保留 `business.util` 跨admin/system的split package，不能用聚合包图倒推Maven模块环，更不能把包名当批准business domain。

[运行说明](README.md)、[汇总数据](summary.json)、[562条包边及实际import定位](package-edges.tsv)、[Mapper字面写入口](mapper-writes.tsv)、[UNKNOWN清单](unknown.json)、[3条环的源码证据](cycle-traces.json)、[执行/内容身份](EVIDENCE.json)。完整8059条引用可由同一脚本 `--all-references` 原样重建，不把大重复清单或观察图当认可baseline。

## 三层依赖必须分开

| 观察层 | 节点/边 | SCC结果 | 能说明什么 |
|---|---:|---|---|
| 直接 Maven 项目依赖声明 | 6 / 7（本源均compile） | 6 singleton | 仅子POM中本项目literal坐标，不是effective POM/传递/运行依赖 |
| 模块内已解析使用的import聚合 | 6 / 9 | 6 singleton | 源级引用子图；不是27471实际jdeps结论 |
| 声明Java package聚合 | 113 / 562 | 4非平凡 +74 singleton =78 SCC | 技术包耦合；没有赋予business domain owner |
| 文件主类型及外部类型聚合 | 2445 / 8035 | 2双节点 +2441 singleton =2443 SCC | 明确可见的类型子图；隐式/FQN/生成等边未齐 |
| XML首个字面写目标→namespace | 369表 /248技术writer；19表有2 writer | 不对单向write索引作“无环”推论 | 不完整写事实索引；不是批准业务owner图 |

两种模块图的实际degree（fan-in/out均为不同邻接模块数）：

| 模块 | POM in/out | 使用import in/out |
|---|---:|---:|
| admin | 0 /3 | 0 /4 |
| framework | 1 /1 | 1 /2 |
| system | 1 /1 | 2 /1 |
| common | 3 /0 | 5 /0 |
| quartz | 1 /1 | 1 /1 |
| generator | 1 /1 | 0 /1 |

POM有 admin→generator 声明，而本使用import查询没有它；使用import含admin→common/system、framework→common等传递可见引用。不能把两个图混成“直接依赖10边”。历史50f/864e真实9条jdeps模块边属于那些旧source；本批当前源码9边相同也不构成27471编译等源证明。

## 最高扇入、扇出与传播指标

类型fan-in是不同引用源文件数；文件fan-out是不同已解析项目主类型数；包fan-in/out是不同邻接包数。它们不是SQL次数、controller行数、测试case数或必须改动的文件数。

| 对象 | 当前观察值 |
|---|---:|
| 包fan-in：common.utils / common.constant / common.core.domain | 56 /44 /24 |
| 包fan-out：web.controller.api / business.service.impl / web.controller.business | 46 /42 /23 |
| 类型fan-in：AjaxResult / BaseController / ServiceException / StringUtils | 240 /225 /202 /180 |
| 业务接口fan-in：IUmsMemberService / ICmsTopicService / IWxContentSecurityService | 59 /45 /29 |
| 真实XML namespace对应Mapper fan-in：CmsTopicMapper / UmsMemberMapper | 41 /33 |
| Mapper fan-in：BusinessAuditEventMapper / ClubMapper / CmsActivityMapper | 各22 |
| 文件fan-out：ApiPlayProgressController / TopicRouteRuntimeServiceImpl / GameSessionRuntimeServiceImpl | 65 /60 /52 |
| 文件fan-out：ApiMerchantController / ApiTopicController / OfficialEventV2ServiceImpl | 49 /49 /44 |

Mapper的技术写扇出：CommunityPostMapper→22张首目标表、OfficialEventV2Mapper→11；AccountDeregistrationMapper/CommunityTrustMapper/GameSessionRuntimeMapper/RoamMapper各9。最大观察单表写fan-in为2 namespace，19表明细保留在summary，含cms_topic、cms_registration、ums_member等；**不能从同包261个business Mapper或这些计数推出谁获准跨域写**。27471批准的whole-table policy仍只有AI_QUOTA_RESERVATION/PHOTO_CHECK_INTENT；其它table/field/command业务owner未知。本批未改变两fact或private_ports。

传播排序采用可重算的窄指标：对每个互相有边的包对，数“两包外直接引用任一包内类型”的不同生产源文件，降序；同分再比双向import记录数。该指标衡量**聚合契约暴露面**，不是预测某个具体方法修改会迫使所有这些文件变化。它会受大包影响，所以同时列具体类型/调用位置，后续应从实际diff收窄回归，而非每PR要求重审全部943文件。此子图有18个双向包对，完整排序在summary。

## 三条传播暴露面最大的实际包级环

三个环均属于同一个15包business SCC；不是3个互不相交SCC。每个方向可能由不同类型形成，所以不能据此推断相同两类互调或递归。源码链接均固定27471。

### 1. business.domain ↔ business.domain.vo

- 两包外直接依赖源 **943**；domain→vo有2条、vo→domain有77条使用import记录。
- 正向：[CmsTopic.java:354](https://github.com/liboyang42-cpu/hengyin/blob/27471b00989e15f7641f4ee77c497cca2f9e53c1/chengyinhub-system/src/main/java/com/chengyinhub/business/domain/CmsTopic.java#L354) 的 `List<TopicChapterVO> chaptersList`，公开 getter/setter也携带该投影类型。
- 反向：[TopicChapterVO.java:10](https://github.com/liboyang42-cpu/hengyin/blob/27471b00989e15f7641f4ee77c497cca2f9e53c1/chengyinhub-system/src/main/java/com/chengyinhub/business/domain/vo/TopicChapterVO.java#L10) `extends CmsTopicChapter`。这条返回的是另一个domain类型，**不是TopicChapterVO→CmsTopic的类环**。
- 实际传播入口：[ApiTopicController.java:2489](https://github.com/liboyang42-cpu/hengyin/blob/27471b00989e15f7641f4ee77c497cca2f9e53c1/chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiTopicController.java#L2489) `topic.setChaptersList(chapterVOList)`，2527又将其投影到view。持久domain混有返回投影时，章节形状/序列化变动易扩散到查询、审核、接口响应；仅凭目录分层不能认为已经分开。
- 下一步最小审查切口：先表征该字段的实际读写/序列化caller，再决定只读projection是否由现有查询合同承接。这里只提出耦合点，不改JSON、持久字段、SQL或新增owner。

### 2. business.domain ↔ business.service.support

- 两包外直接依赖源 **899**；双向记录2 /111。
- 正向：[JourneyRules.java:76](https://github.com/liboyang42-cpu/hengyin/blob/27471b00989e15f7641f4ee77c497cca2f9e53c1/chengyinhub-system/src/main/java/com/chengyinhub/business/domain/JourneyRules.java#L76) `new JourneyRulesValidator().validateAndNormalize(rawJson)`，是domain.parse到support校验的实际方法调用；JourneyStory也有同方向。
- 反向：[ThemeCharacterService.java:118](https://github.com/liboyang42-cpu/hengyin/blob/27471b00989e15f7641f4ee77c497cca2f9e53c1/chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/ThemeCharacterService.java#L118) `JourneyRules.parse(journeyRulesJson)`，是support到domain的实际调用。不是validator反调ThemeCharacterService，**没有据此推断无限递归**。
- 实际玩法使用：[GameSessionRuntimeServiceImpl.java:239](https://github.com/liboyang42-cpu/hengyin/blob/27471b00989e15f7641f4ee77c497cca2f9e53c1/chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/GameSessionRuntimeServiceImpl.java#L239) 从sessionConfig的journeyRules解析；作者规范化与运行时快照解释容易共享同一变更面。
- 下一步最小审查切口：表征原normalize/parse/坏配置效果后，确认纯规则codec/validator的稳定契约归属；不能以移动目录代替业务等价测试，不能猜规则或把support整包视作一个获准领域。

### 3. business.access.merchant ↔ business.service

- 两包外直接依赖源 **522**；双向记录1 /1。
- 正向：[MerchantAccessServiceImpl.java:46](https://github.com/liboyang42-cpu/hengyin/blob/27471b00989e15f7641f4ee77c497cca2f9e53c1/chengyinhub-system/src/main/java/com/chengyinhub/business/access/merchant/MerchantAccessServiceImpl.java#L46) 通过字段 `IMmsMerchantService merchantService` 调用 `selectMmsMerchantList(ownerQuery)`，是access到业务service的实际调用。
- 反向：[IMerchantDeskService.java:17](https://github.com/liboyang42-cpu/hengyin/blob/27471b00989e15f7641f4ee77c497cca2f9e53c1/chengyinhub-system/src/main/java/com/chengyinhub/business/service/IMerchantDeskService.java#L17) `receipt(MerchantAccessContext access, String requestId)` 等公开方法携带access上下文。**这是参数类型依赖，不是假称业务方法调用回access实现。**
- 真正入口：[ApiMerchantDeskController.java:184](https://github.com/liboyang42-cpu/hengyin/blob/27471b00989e15f7641f4ee77c497cca2f9e53c1/chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiMerchantDeskController.java#L184) 先 `merchantAccessService.require(principal, permission)` 再调用业务action。此环可能反映合理的上下文契约，不能自动判权限缺陷或直接删除边。
- 下一步最小审查切口：独立确认AccessContext作为稳定叶子契约的边界及实际授权/归属正反控；不改require、不弱化merchant范围、不授权其它mapper写，也不因包环要求泛化新框架。

## 另外两种环与完整性边界

包级4个非平凡SCC：business 15包、common 17包、quartz 5包、framework 2包，成员全列summary。包共享/聚合可能放大这些组的外观，不能仅凭包名分派业务owner或认定装配失败。

类型子图只找到两对：Sensitive↔SensitiveJsonSerializer（注解/serializer契约），StringUtils↔StrFormatter。后者有可核对实际调用：[StringUtils.java:357](https://github.com/liboyang42-cpu/hengyin/blob/27471b00989e15f7641f4ee77c497cca2f9e53c1/chengyinhub-common/src/main/java/com/chengyinhub/common/utils/StringUtils.java#L357) format→StrFormatter.format，[StrFormatter.java:32](https://github.com/liboyang42-cpu/hengyin/blob/27471b00989e15f7641f4ee77c497cca2f9e53c1/chengyinhub-common/src/main/java/com/chengyinhub/common/core/text/StrFormatter.java#L32) 再用StringUtils.isEmpty；不同方法互依，不是无限format递归。StringUtils的180个引用源说明common叶子契约值得关注，但本批不顺手重构。

未知清单不是可忽略项：183文件有FQN正文未展开；101条static/wildcard import未展开；91条本地import不能在支持的文件主类型中解析；30条nested import只聚合到外部主类型；13文件unicode/text block不肯定解析，2文件全注释/无package。这些计数可重叠，不能求和当独立缺陷数。隐式同包引用、额外顶级类型、生成/Lombok、继承/泛型解析、Spring代理/注入、反射、XML wiring与MyBatis resultMap/动态provider/SQL调用均不完整。

XML方面，56个写statement首目标无法确定；另383个首目标已识别但完整写效果仍UNKNOWN（动态字段/include/复杂shape等）。首表识别不证明只改该表/字段，不证明作用域/金额/权限安全。注解SQL、JDBC及把DML塞select标签等并未被这个写索引穷尽。观察出的多writer不等于全部违规，也不等于获准共享owner。

## 实际执行、未验与后续验证建议

本批实际执行：只读脚本、同输入第二次重建并逐字节比较、图/degree/SCC及所选引用证据一致性核对、Python语法均通过；12个F独占交付文件scope检查、原secret-file与原配置gitleaks 8.21.2扫描通过，0 findings，不添加豁免。实际输出和内容hash放EVIDENCE。不是Java/DB测试，不借root的204 Node、AI22真库或旧source9边jdeps为本次图签字。

**未执行** Java/javac/jdeps、MySQL/Redis、HTTP/provider、CI dispatch、微信；没有修改生产、已有guard/policy/baseline、工作流、全局配置或依赖。没有治理/批准存量环，也不设置“任意Java改动都阻断”的新审批。

若root随后提供同源已验证JAR/raw jdeps/class-index，优先复用原producer及parser回读实际内部class边，再把当前来源、嵌套类、行/类字节和缺边逐一对照；不能用空图宣称无环。方法/注入/运行权限仍需相应业务和装配证据。后续自动约束只能在root确认少量真实business owner/公开契约后限制**新增确定违规**，不能将本观察自生成认可baseline，或让所有PR重审存量全图。
