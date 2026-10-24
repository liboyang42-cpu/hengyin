# E 主题草稿 prepare 输入边界最小增量

结论：已写完源码与4条新 unit，未运行 Java/JUnit/DB/CI。仅将当前 simple 页已有 idea 规则补到服务端，不改原计划、额度或角色政策。

- 公共 parent `78559677c472fe58e7583158482d17d0aa138dba`，tree `fe4bb6da480b05ffb3166f7be31f428b20da5e0e`；独立工作目录 `/workspace/hengyin-e-theme-input`、分支 `codex/architecture-day-e-theme-input-boundary`。原785及A/root文件不改。
- 规则来源：`chengyinhub-xcx/pages/publish/simple/index.wxml` 的 aiIdea textarea maxlength=300；对应 index.js generateAiPlan 先 trim，再拒绝空 idea。没有新增已有共享 helper 覆盖此校验的反证；AiThemeDraftPrepareReq 和 AiThemeDraftReq 均无该约束，A最终prepared入口只判断req非null。
- 失败机制：原 prepare 仅检查prepareKey，null/空白/任意长 idea 可进入Mapper、原计划及后续额度/审核/模型。补丁在 prepare 的最前沿拒绝 req/idea null、原 String.length>300、idea.trim().isEmpty，固定400/AI_DRAFT_INVALID_REQUEST，未调用Mapper/Creator/Redis/事务管理器。不将trim结果赋回payload，不改hash定义。
- 校验优先于feature flag：无效输入固定400；有效请求仍遵循默认disabled=503。read/purge/recovery、执行claim、原nonce/day/pool/receipt和正文绝对TTL均不改。
- productType既有nullable语义不改；existingNodeNames缺少明确限制，保留为已知风险，不凭空设置数量/长度或新增业务规则。

本候选只改3文件：AiThemeDraftRequestService.java；AiThemeDraftIntentServiceTest.java；本文。新增4条：prepareRejectsNullIdeaBeforeAnyDependencyAccess、prepareRejectsEmptyOrWhitespaceIdeaBeforeAnyDependencyAccess、prepareRejectsRawIdeaOver300EvenIfTrimmedLengthWouldFit、prepareAcceptsExactly300AndPreservesOriginalWhitespacePayloadAndHash。

负例验证400、固定机器码、Mapper/Creator/事务管理器零访问、Redis读写计数0；正例验证恰300保留原空白、nullable productType/nodes、同原输入重放同id，trim后换payload触发原hash冲突，不能再次规划或执行模型。仅unit源码，结果未跑。

原legacy7与真库IT19不改；v2原24条全文保留，仅在类末尾追加4条，v2变28、总声明数54。没有删例/skip。先前只读review发现的 `failedTerminalCommitKeepsOriginalClaimWithoutResend` restub NPE 仍在原24中，本增量未混入该独立fixture修复；root须协调该已报阻塞，不能据本候选声称整类可绿。A ef080及root caller/maintenance/registry仍是联合编译验收依赖。

实际执行仅Git/Python静态范围、原24字节保留、guard顺序、词法/声明数、有限秘密扫描和secret-file-gate；无Java编译/JUnit/数据库/CI/真实模型/生产操作。环境未安装gitleaks，最终commit需root固定版本复扫。无schema、依赖、配置或迁移变化；撤回补丁会重新接受非法idea，不能把回退视为缺口已闭合。

候选最终commit/tree/blob发布后单独核对回报；不将本地/API不同commit伪称同一身份，不挪用785/其它来源的测试回执。
