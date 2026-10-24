# E TopicQuery 源码候选

基线/parent：`d586c143eb0a4499aeb15cbb3d173496f18b83e2`，公共集成分支读取时 HEAD 与协调者指定一致。独立工作树 `/workspace/hengyin-e-topic-query`，分支 `codex/architecture-day-e-topic-query`。地图两个候选未改。

实际新增 `TopicQueryService`（666 行，7 个明确读侧依赖）：公开/商家可见详情、列表节点计数投影、俱乐部 upcoming 计数、模板货架与白名单详情。21 个方法体逐字迁移，另一个相同的 nullToEmpty 小 helper 保留在两侧，以免写侧反向依赖查询 owner；没有复制业务规则。新 owner 无 CmsTopicService/发布/结算/runtime 回调、网络、缓存、锁读或状态修补。

**共享大类未修改**。root 串行应用 `review.patch` 后，13 个原 public API 仅委托新 owner，4 个仅投影使用的依赖移除，原 4150 行降至 3606 行；8 个私有闭包迁出。其余 128 个方法体保持原文，包括发布、章节节点写入、结算/退款、ensure、原始实体读取和统计写入。controller、ICmsTopicService、mapper/XML、响应类型、排序和分页不改；新增 bean 接线生效前不能声称生产消费者已切换。

读链与边界：

| 原入口/下游 | 保留合同 |
| --- | --- |
| ApiTopicController 详情→selectCmsTopicDetailById | 原 viewer/member、clubMemberOnlyGate、圈子/作者可见 SQL 先行，拒绝不查节点计数 |
| 商家详情→selectMerchantVisible/ManagedCmsTopicById | 原公开商家门槛及生效申请+供给+时效；不新增 viewer/角色推断 |
| 普通/公开列表→原列表 SQL→批量节点计数 | 返回同一个 List/Page 对象及原顺序；只改 transient 计数，不写数据库 |
| 模板 /info→selectPublicTopicTemplateById→组装 | 同一资格快照；原 facade 与新 owner 均保留 readOnly + REPEATABLE_READ/默认 REQUIRED，先资格后子树 |
| 模板详情→章节/节点/玩法/元数据 | 原逐字段白名单，不出答案/提示/绝对坐标；排他与合作方、effort 与 recruitStatus 同闸；原路线归一化不改 |
| 招商统计→原 getRecruitmentStats | 已追至申请 pending/approved 与供给三条 SELECT 及纯去重 helper，无 ensure/维护写入 |

当前货架 SQL 是“所有已发布模板”，推荐才按 VERIFIED 过滤；迁移体内提及 VERIFIED-only 的旧注释保持原文，但未据此改 SQL/策略。官方置顶的稳定排序、章节/玩法查询次数及 Date 获取位置均原样；本片不是 N+1 优化，没有新增批读/索引或性能结论。

`caller-evidence.json`：本基线全部生产 Java 补读缺失的 100 个 blob 后，记录 18 个非 mapper/interface/facade 的消费者文件、33 个调用点；原 caller 源码均不修改，scope 的 controller 判定及隐藏字段处理继续原链。`migration-evidence.json` 给出每个迁移方法的原 body SHA256，使用数组而非以方法名作秘密样式 key。

`test-wiring.review.patch` 仅供 root 同时迁移 8 个手工 new fixture 到实际新 owner，删除对旧写侧 supplyReviewService 的无关装配；44 个原案例及断言/SQL fixture/cleanup 原文保留，无 skip。新增 TopicQueryServiceTest 六例：拒绝零下游查询、Page 元数据/同对象与批量顺序、敏感字段负控、货架原排序/推荐、DB 错不假空、RR 注解。

实际执行：`python docs/delivery/architecture-day/E/TOPIC-QUERY/verify-migration.py` PASS（21 原体、128 余体、13 委托、8 fixture/44 场景机械对照，两个 patch 在临时原基线副本 apply）；git diff 检查、精确文件门禁与有限秘密模式扫描 PASS。**Java/JUnit/DB/CI/DevTools/完整 gitleaks 均 NOT_RUN**；44 原案例与6新案例只是源码，需 root 合成后统一回归，不能挪用其他候选回执。

无迁移/依赖/配置/缓存变化。撤回时新 owner 与两个接线 patch 必须一致撤回；不得整旧文件覆盖其他 owner 的后续修改。候选只纳新 owner/测试及本交付目录，root 按 hunk 应用共享接线。
