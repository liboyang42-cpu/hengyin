# 当前最高优先执行状态

- CORE最新公共源码 `f7e0e52346d0aec248e6288f1f5ed4aaae66ac4c`（15测试fixture最小修复，NOT_RUN）；EXTRA最新 `a37ce191756ab11896faf4e61e8a06b4279f0303`（E03/E05及5旧fixture已公共，NOT_RUN）。原2b/25ede验证冻结记录保留历史，不覆盖旧结果。
- 后端实际复测仅由本地 `01a13eed-e4d9-77f3-b875-c72d26ea7d59` 按最新212/批准真库入口执行；C00与两队不再手动编译、测试、触发CI或新增专用验证配置，不尝试VPS/Cloud替代。
- 每队最多6名6.1-sol，1条修复线串行接失败，另5条继续独立源码、完成即push、不等CI/本地签收；原写者优先，换人先精确移交。无真实任务不凑数，实际运行数照实。
- EXTRA恢复ACK6198215180已收到，之前“五fixture待授权”阻塞已解除；E03/E05已有新公共小提交，不再列未ACK。E02当时已派，后续独立交付另SHA。CORE池仍178历史/实际可行动0，fixture补丁不另算新bug。
- 立即交本地：[后端移交包](BACKEND_VALIDATION_VPS_HANDOFF.md) / 同名JSON，以及CORE/EXTRA逐类矩阵。原Cloud诊断不作VPS验收；本地最新回报f7已按212完成COMPILE_PASS（02:35、7模块、1392测试源）；公开原日志待其发布，业务/真库测试仍未执行；a37未签收。

以下旧checkpoint是历史，不覆盖上述状态。

# 长期源码滚动：当前恢复入口

CY-HANDOFF: CODEX。此页及LONGRUN_CORE_50.json覆盖本目录较早checkpoint文字。

- CORE公共源码：`f7e0e52346d0aec248e6288f1f5ed4aaae66ac4c`，PR44，分支`codex/mini-longrun-core-20261015`；基线PR28/4a943501。每项保留小提交，当前所有新增回归NOT_RUN，SOURCE_DELIVERED_UNVERIFIED。
- CORE初始50项保留历史，累计178具体源切片；当前状态计数：`{"SOURCE_DELIVERED_UNVERIFIED": 135, "NO_CHANGE_SOURCE_REVIEW": 38, "BLOCKED_DECISION": 3, "SUPERSEDED_DUPLICATE": 1, "DELEGATED_SOURCE_IMPLEMENTATION": 1}`。目标维持50可行动项，实际不足50时如实披露；不能把完成历史当剩余数，也不能把审查数当bug修复数。
- 必要回归源码修正单独保留：C06-25 domain imports补齐已公共（精确SHA见该项source_corrections），C05-23夹具schemaVersion在首次公共交付前由原作者修正，NOT_RUN，不称编译/测试通过。
- EXTRA6独立PR36，最后实际读回25ede658的持久队列105历史项=52源码或回归交付/40反证/10阻塞/3委派或他队解决，READY0/实际执行0。后来五旧fixture及LIKE合同已公开授权，尚未读到恢复ACK，不能假称六路在跑。独立队列仍其唯一维护者；不能假称全已集成CORE。
- 两队各最多6 worker、gpt-6.1-sol；完成或阻塞后取本lane下一具体项，现域耗尽如实记录并等待明确新线索，不能以测试/泛审计凑修复任务。

## 精确依赖与剩余边界

E03四参freeze公开7cef70bf已精确集成，CORE原session调用已接。C05 typed RATE_LIMITED、内部provider-result-received marker及shop NPC既有bizType路由均已公共生产/消费，回归仍未执行。C06当前局scope、剧情写竞争恢复、线性旧局保序均未验证；保序仅旧node仍可用场景，不声称作者重建/删除ID也能完整兼容。21之后空集合fallback补点另23，不能覆盖旧快照或抹掉风险。

JourneyStory节点重映E03-11最终公共producer c9185cb6 已精确集成，CORE/C03-05-r2消费者已公共交付；原676bee2f依赖阻塞保留历史，当前不能再标等待helper。同源复制23和场景子树C05-23也已公共交付，均NOT_RUN。raw树保未知字段；storedStory先重映再chapter/opening/withEndingChapters；sys.asked形状是sys.asked.<nodeId>.<questionId>（仅中间nodeId变），outcome/mistakes/passed才末尾ID。缺映射沿既有fail-closed，不全树二次映射。

仍需实质决策的记录保留：NPC同requestId不同message持久指纹/旧回执兼容；协作者null/重复输入语义；photo既有429退款与网关UNKNOWN保留的跨池政策差异。Creator完整有效稿独立计次也不能机械套网关。未知usage缺能力只标估算/null，暂停job/语音日池属于合同或部署边界。未审店NPC gate差异只读记录，未擅改鉴权。

活动发布page仍原Claude文件锁，已直接交PR27 comment6193824961：首次create丢响应/提交后媒体500不能盲重发，同步回调也不反盖loading。无冷启动身份既有合同则不得伪称跨重启恢复；C00/CORE未抢写。

本轮额外只读证据C02_SOURCE_AUDIT_REMAINDER/C06_SKU_SOURCE_AUDIT随源码分支交付：有owner legacy按member隔离，真实页面没有空actor读取；无历史owner的恢复不猜归属。SKU现有真实cart事务/行锁/正数入口/原子库存guard完整，未发现新可行动缺陷，不重复改。旧局缺完整冻结实体是已知源能力边界；TopicEditWindow通常禁止在售运行主题重建，尚无普通在售保存必破坏旧局的可达证明，不凭此造新快照体系。

B03新静态真实线索：CORE/C04-20服务前置CONFIRMED状态，沿原SQL/controller已定状态与maker-checker，阻止读PENDING后并发确认跨过双人guard；源码及4组mock回归和2个真库交错用例已公共（realdb f9e2932ca4b14e541caba356d2c1b016332f199f），均NOT_RUN。EXTRA/E04独占clawPoints旧RR读取线索已直接交PR16 comment6194350389；原作者已公共交付E04-13 fbb7b36354f4839d40eda0ca2b32416db9dd7255，CORE精确提取为 bda1c4e8db08210b573cac1ee02b0d18c19962b7，2个真RR回归源码NOT_RUN；源码仍EXTRA/E04所有，不能称运行已修。

## 验证和恢复

本轮不手动编译/测试/Node/Java/Maven/DB/lint/DevTools/业务验证，不直连VPS，不手动重跑CI；既有自动CI自然运行。原1e403b13的231项VPS PASS只覆盖原冻结SHA，69c失败保留历史，不沿用本PR。4a旧Node结果也不覆盖本轮新代码；共享动作摘要/UI行号待本地统一重建验证。未合并、未部署、未迁移、未设备/真实身份HTTP验收，TEST_DEPLOYED=NO。

恢复先读本页、LONGRUN_ROLLING、LONGRUN_SOURCE_OWNERS、CORE池和EXTRA队列/CLAIMS、PR16最新CY-HANDOFF；核公共head与工作树，不覆盖WIP。每任务唯一源作者；表外只读。不可变快照`/tmp/longrun-snapshots/<ID>/manifest.json`按原交付顺序小提交，C01-16仅-r2、C03-17仅-r1；CORE/C02顺序12→11→13…，不可按ID反灌旧文件。已公共commit_sha是恢复真值，只有local_commit_sha的项尚未交付GitHub。

Git CLI写凭据失效时使用已连接GitHub Git Data接口发布相同文件树/小提交，公开SHA以实际ref为准；不提取/打印凭据。源码及队列定期公共checkpoint，两队直接PR16对账不依赖Mac。平台时长/额度终止时留下SHA/未发布快照/依赖/下一项；不承诺永久运行。

## 本次恢复收口

CORE六个源码作者均已交付并停止写入，锁保留；本次六路仅做Claude交付只读接收，不算额外bug、测试或持续运行的源码工位。CORE实际可行动0、目标50缺50；EXTRA最后实际队列105/READY0，5夹具和LIKE授权后尚未ACK，不声称恢复运行。按新具体线索补位，实质政策阻塞换项；没有可行动项时标EXHAUSTED，不制造任务。

Claude新接收37份精确PR快照见CLAUDE_HANDOFF_INTAKE.json/.md，保留原所有者与共享文件锁。PR21/23/25/27当前来源已更新，原测试声称不当本队验证。master同步仅待评审，无合并。

恢复：读取本页→START_PROMPT最新模式→ROLLING/源表→两队真实队列→PR16/21新CY-HANDOFF。优先收EXTRA对c79d2886授权ACK及E03-16/E05-17/E02-22公共小提交；只能原作者应用prepared_patch，先核新base，不重复写。集中验证用LONGRUN_VALIDATION_HANDOFF当前2b1984 SHA清单，仍NOT_RUN。CI自然结束不等待，禁止手动重跑。平台停止时以GitHub公共进度恢复，不承诺永久运行。
