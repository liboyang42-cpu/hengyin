# E 章节招商统计批读

公共 parent：`b9b894b170d75b4e6315f2bde41ede8f551522c6`；独立工作树 `/workspace/hengyin-e-topic-recruit`，分支 `codex/architecture-day-e-topic-recruitment-batch`。前序 Query/地图候选不改。生产仅改 TopicQueryService；无共享 CmsTopic/API/Mapper/XML/写侧改动，无新依赖/缓存/框架，无新增 facade 接线。root 继续原13个委托即可消费本增量。

真实调用：模板详情 controller → ICmsTopicService 原 facade → TopicQuery.buildPublicTopicTemplateDetail 或 buildTopicTemplateDetail → buildTopicTemplateDetailFromSnapshot。原每个通过角色、发布/模式、章节招商开关、条款/容量、受控品类校验的章节调用一次 IMerchantChapterApplicationService.getRecruitmentStats，每次 pending/approved/live-offer 三条 SELECT；R章=3R条统计SQL。

现改同一 Query 中的统计装配：原品类查询与所有门槛逐字保留，已准予章节的 id/maxMerchant/view 组成私有 typed record；按现有 TopicSizeLimits.MAX_CHAPTERS=30 分块调用 getRecruitmentStatsByChapters。原状态/计数字段投影移入一个 helper，规则未复制；在 applyTemplateMeta 及返回前补齐，匿名/不合资格章节不进入统计，不提前加载全主题/历史。既有私有空chapterId分支沿原纯零计数方法，不发SQL。null/缺key统计结果抛错，不补成可用零占用；真实服务无行时本来会为每个chapter构造合法零计数，不受影响。

已有批读依赖（均只读复用、无新namespace/方法/SQL）：

- IMerchantChapterApplicationService.getRecruitmentStatsByChapters；实现与单章方法共用 buildReservedMerchantIds，approved/live merchant去重、pending计数、remaining的null/0不限额口径均原样。
- MerchantChapterApplicationMapper.selectByChapterIdsAndStatus，status/del_flag过滤及id排序与单章相同。
- MerchantChapterOfferMapper.selectLiveByChapterIds，与单章复用 liveOffer SQL片段：status=1、valid_end为空或晚于now()。

统计SQL由3R变为 `3*ceil(R/30)`（正常DB非空主键），单批IN≤30，不截断超大历史输出。节点/模板/受控品类等查询不改，不能称全详情O(1)。新增测试的3章夹具原9条→预期3条；61章原183条→预期9条。以上为源码与待执行断言，不是MySQL测量/RPS。

时序边界：原 DateUtils 获取、recruitmentWindowOpen、生命周期/商家与发布者判据不变，白名单/effort/排他展示仍由原门槛决定。统计由逐章读变为所有门槛后按块读；既有RR数据快照不变，但MySQL now()不被冻结为事务起点，liveOffer到期边界会落在新的块查询时点。非事务内部详情也不增加隔离/锁。不能声称严格并发撤权/容量截止语义等价；此处只投影统计，未改变申请/写入时真实授权、容量与截止重检，也不把统计当资金/权益事实。

专属 TopicQueryRecruitmentBatchTest 六例源码：真实 Query+真实统计服务以受控mapper对照原单章结果/商家去重与OPEN/FULL/不限额，30章分块和61章无截断，匿名/未发布/关闭/品类撤销/不匹配零统计读取，真实统计存储异常传播，批量契约缺失不能假零，原null章节纯零计数分支。不是MySQL验证。

legacy-fixture.review.patch 供 root 在原9e test-wiring.review.patch 后串行应用，仅把两类 mock/verify 端口转到 batch，正向/拒绝的chapter参数保留；15+5个原案例及业务assert逐字保留，未直接编辑共享旧测试。父片原6+5专属测试和其余旧案例不改，无skip。

实际执行：verify-static.py PASS（22余体、准入判据/投影原文、实际SQL共用过滤、20旧案例），diff/文件门禁/有限秘密模式扫描 PASS。**Java/JUnit/MySQL/Redis/CI/DevTools/完整gitleaks均NOT_RUN**，由root统一合成后验收。无schema/迁移影响；撤回需同时撤 Query 统计装配和旧fixture端口patch，不整旧文件覆盖其他owner。
