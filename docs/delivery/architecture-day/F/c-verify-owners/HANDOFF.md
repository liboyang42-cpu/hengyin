# F：先收口C两个已冻结核销应用（C-only）

parent `8a5a781edeaec0dc7e24a460a0fdba1394252ded`，承接RegistrationTicketApplication owner；本增量仅追加C_CITY_NODE_REDEMPTION_APPLICATION → 精确Admin类型 `com.chengyinhub.web.service.verify.CityNodeRedemptionApplication`。公共来源 `322875cf84cce61161876e12fd37bfb68b568a1e`，parent d2d09；Ticket应用blob与d2逐字一致。完整源码和caller已读，原blob/hash/构造依赖/事务公开方法证据见source-evidence.json。

仅owners.json、专属test_collect_jdeps.py、本目录；D/E类型不纳入。本地工作树沿原准备名f-citynode-notice-owner保留，公开候选分支codex/architecture-day-f-c-verify-owners。原4bb/e9/615及86fb Bean候选不重写，root本轮可信ref不动。

两个应用各禁止反调ApiVerifyController/BaseController；保留CityNode实际四port IMerchantAccessService/RoamMapper/ISmsCouponHistoryService/BusinessAuditEventMapper，Ticket五port也不改。不猜allowed edge、不给未登记接口/Mapper造business owner，故allowed_directions零新增；现61类型/50职责/60方向，原3Mapper/2facts/2ecbaseline和所有既有规则不变。C public@Transactional是否经实际proxy生效、VERIFY/锁/CAS/券/审计顺序和回滚只能由C场景及root实际运行证明，不据classgraph签业务验收。

新增3个NOT_COMPILED控制：真实四port/Controller入向正控；反调Controller/Base硬红；CityNode全部raw source行缺失即使同Admin有输出仍UNKNOWN。Ticket原正反/缺证4项保留；原93producer方法AST逐个未变，全Python212/212、0skip、18.762s（python-tests.txt）。Python3.9 grammar/diff/范围扫描通过；假class字节的协议fixture不是实际JAR或编译负控。

六可信hash见trusted-files.sha256，仅owner字节较8a变化；candidate不能自行改policy批准。独立policy父树没有C生产两个应用，实际artifact/raw缺类仍UNKNOWN；必须root下一批与C322真实源组合审定，而非本轮替换e9/615或拿旧图当覆盖。Java/javac/jdeps/Spring/JAR/DB/CI均NOT_RUN。无新scanner/框架/依赖/job/本地Java或生产改动。

D/bc5审计writer及新notice留下一小片，不混scope、基线或额外fixture。现SupportedStack fixture没有真实CoopComplaintServiceImpl，不以inert声明投诉闭环已启动；若后续纳入它，须同时接真实ReporterNotice及审计Mapper边界，不能mock掩环。E语音及Llm第四配置参数接线仍root所有。
