# E 模板详情节点模板批读

源码基线：`9e14a73e50c9afd5c8f38027c3ecd059967a51d3`，独立分支 `codex/architecture-day-e-topic-template-batch`；此前 TopicQuery/地图候选不改。仅 TopicQuery、专属测试及本目录；无需追加 CmsTopic 接线，继续沿用父片两个 review.patch，由 root 串行合成。没有新 namespace/接口/SQL/缓存/依赖：复用现有 `com.chengyinhub.business.mapper.CmsMemberTemplateMapper.selectCmsMemberTemplateByIds`，Mapper.java/XML 均不改，不涉及 D/G 写侧。

已证实路径与逻辑 SQL 数（不是运行性能）：

- ApiTopicController.topicInfo → CmsTopic facade/query 可见性 SELECT 后，Cᵤ 个准予读取章节各查节点；K 个准予展示且带正 templateId 的节点分别查模板、报名商家；R 条报名行又分别查商家、模板，另有条件券读取。模板服务的单条方法直接委托 mapper，故这个子树的模板 SQL 为 K+R 次。分支 teaser/已解锁章节/本人承接节点闸在前，不能全主题预取绕过它们。
- ApiTopicController.topicInfoToMerchant 的已准予章节子树逐章查节点、逐个带模板节点查模板，模板 SQL 为 T 次。普通/管理可见性闸、审核隐藏字段与来源投影在 controller 原链，均不改。
- 模板 /info → CmsTopic facade → TopicQuery.buildPublicTopicTemplateDetail → selectPublicTopicTemplateById → 原计数/章节 → 每章节点 → 每个带正 templateId 的节点调用单条模板 SELECT。重复 templateId 在 gameMap 去重前仍会重查；这是本次实际修复点。C 个章节、T 个带正 templateId 节点、Q 次受控品类查询、A 个实际招商统计章节时，已准予的公开详情原 mapper 数为 `4+C+T+I(C>0)+Q+3A`；非公开 buildTopicTemplateDetail 少原节点计数一次。这个式子仅覆盖上述 Query 及已核对的统计 SELECT，不泛化整个 HTTP 链。

实现按章收集节点已绑定的正 templateId（原 encounter 顺序、去重），每批最多现有 `TopicSizeLimits.MAX_NODES=300`，复用原 batch mapper；按 id 回填，不依赖 SQL 返回顺序。模板 SQL 从 T 改为 `B=Σceil(U章/300)`，U章=本章不同正 templateId 数。无 ID 不发空 IN；旧大章节分块而不截断任何节点/输出；没有宣称全请求/输出已硬限额。其余章节/招商 N+1 保留。

等价边界：单条与批量 XML 共用 selectCmsMemberTemplateVo/CmsMemberTemplateResult，差别仅 `id = ?` / 参数化 `id IN (...)`，无新增 owner/状态/审核谓词。原所有展示 setter、游戏首次出现排序、节点顺序、缺失模板时节点仍保留、正 ID 判定、匿名/合作方/发布者闸及异常传播均保留；不调用 strip 的替代路径，不返回原模板实体。每章 IN 的大小有界，不缓存模板行。

读点变化：公开详情保留原只读 RR，模板数据仍来自同一事务快照。原非事务详情在一章内由逐节点读点变成每批一个读点，超大历史章可能多批；没有升级其隔离级别，也不声称严格并发撤权快照。两个 mapper 原本仅按已绑定 ID 查行，未以模板状态/owner 作读取授权；白名单仍为原七个玩法字段/节点投影，不增加可见字段。controller 的更细 teaser/scope 路径此次不切消费者。

测试源码：原 TopicQueryServiceTest 六例场景/断言不变，仅一个 mapper stub 接到批读；父片 44 旧案例/fixture 不改。新增 TopicQueryTemplateBatchTest 五例覆盖：乱序 SQL 行/重复/缺失模板和完整七字段影子、3章夹具原14→预期10次 mapper调用、300/601节点 IN 分块与无截断、空/null/非正 ID 不查、DB 异常不假空与新请求恢复、真实生产 XML 的 MyBatis BoundSql/同结果映射检查（部分边界同一例）。公开拒绝零下游与敏感字段负控沿原六例。

实际执行：本目录 verify-static.py、git diff/原方法机械对照、精确文件门禁及有限秘密模式扫描 PASS。**Java/JUnit/MySQL/Redis/CI/DevTools/完整 gitleaks 均 NOT_RUN**；五例及上述次数是待 root 同源执行的断言，不是数据库测量。父片逐字迁移校验绑定父片冻结源码，本次有意改变一个方法取行点，使用本目录对照增量，不挪用旧测试回执。首片 `28d179ff` 后追加影子测试表示修正：比较实际序列化 JSON，避免 valueToTree 的 LongNode 与文字 JSON 的 IntNode 差异；不改生产字节、原断言字段或案例数，不改写首片。
