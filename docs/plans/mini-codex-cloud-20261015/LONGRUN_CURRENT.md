# 长期源码滚动：当前恢复入口

CY-HANDOFF: CODEX。此页及LONGRUN_CORE_50.json覆盖本目录较早checkpoint文字。

- CORE公共源码：`bda1c4e8db08210b573cac1ee02b0d18c19962b7`，PR44，分支`codex/mini-longrun-core-20261015`；基线PR28/4a943501。每项保留小提交，当前所有新增回归NOT_RUN，SOURCE_DELIVERED_UNVERIFIED。
- CORE初始50项保留历史，累计168具体源切片；当前状态计数：`{"SOURCE_DELIVERED_UNVERIFIED": 126, "NO_CHANGE_SOURCE_REVIEW": 38, "BLOCKED_DECISION": 3, "SUPERSEDED_DUPLICATE": 1}`。目标维持50可行动项，实际不足50时如实披露；不能把完成历史当剩余数，也不能把审查数当bug修复数。
- 必要回归源码修正单独保留：C06-25 domain imports补齐已公共（精确SHA见该项source_corrections），C05-23夹具schemaVersion在首次公共交付前由原作者修正，NOT_RUN，不称编译/测试通过。
- EXTRA6独立PR36，最后实际读回e43477b7的持久队列仍87历史项=36源码或回归交付/29反证/7阻塞/9READY+6在途；其中若干claim已在后续源码提交交付，队列尚待原协调者刷新，不能把该checkpoint称当前实时运行数。独立队列仍其唯一维护者；不能假称全已集成CORE。
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

## 最新跨队写权与补位事实

CORE六个原writer均已报告本切片无WIP/无新的已证实独立缺陷；不是六线持续运行。初始50及后续具体项已按小提交交付，另保留反证/政策阻塞。目标仍50未完成，但实际为0时标EXHAUSTED，不能用重复审查/空任务伪称补满。新真实故障/producer缺口由C00精确派回原owner，完成后立即取有证据的下一项。C03-24已有topic的update写前3文本拒绝、C02-26同revision UNKNOWN明确采用恢复、C01-50结束渲染与关闭意图绑定均已公共；新增验证全NOT_RUN。

EXTRA6新增独立后端写权和旧fixture已公开：控制2f050fd7389f36653a07f742ec7f698687a959ac及cc5b7deaad4a37cf1c24fc8ff22d724a1b3f1809的 LONGRUN_EXTRA_EXPANSION.json/.md、SOURCE_OWNERS。E05独占Encounter+分享隐私归一化，E06独占分享快照controller/service/mapperJava/XML；E01追加ClubEventOpsMapper.java，E04追加4个核销旧fixture。原writer已静止，精确转交生效。已直接PR16 comments6194858995/6194881490/6194944388通知，尚未读到对新增扩权的正式ACK，不假定已启动。EXTRA可独立域内补真实项，无需Mac或再次general批准；逐文件新基线和不允许旧文件回灌规则见扩权文档。

共享当前局producer公共5162ef45bb42486575619871ff24b4b694941f09，旧find不变；EXTRA E05/E03按当前端点合同选择新findCurrent，历史选择器不能自动替换。Encounter present-null/错类型fail-closed合同已冻：复用现有“玩法配置不可用”，缺失key保留旧fallback，合法空结构/graph merge保留。E01新OFFERED仅对齐原maintenance kind3活动可用合同，旧hold释放不挡；E04仅Service内身份锁定，不冒称token全窗口关闭。全部DRAFT/CONTRACT_FROZEN/SOURCE_DELIVERED_UNVERIFIED分开，TEST_DEPLOYED=NO，合并/两端验收另列未完成。

下一步C00主动读PR16最新评论、EXTRA PR36队列/CLAIMS及两项补权ACK；优先处理确切文件申领、producer/consumer依赖，不重做原所有者修复。EXTRA源码独立候选未整体集成CORE，只有逐项记录的精确公共patch可算已提取。Claude/iOS/Flutter边界保持，遇额外实质政策改动只记录BLOCKED，取下一个真项。
