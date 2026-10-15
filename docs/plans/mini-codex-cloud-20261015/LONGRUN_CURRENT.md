# 长期源码滚动：当前恢复入口

CY-HANDOFF: CODEX。此页及LONGRUN_CORE_50.json覆盖本目录较早checkpoint文字。

- CORE公共源码：`adc7c68e9398a61476bbfd57cab7696094d2b61d`，PR44，分支`codex/mini-longrun-core-20261015`；基线PR28/4a943501。每项保留小提交，当前所有新增回归NOT_RUN，SOURCE_DELIVERED_UNVERIFIED。
- CORE初始50项保留历史，累计140具体源切片；当前状态计数：`{"SOURCE_DELIVERED_UNVERIFIED": 86, "NO_CHANGE_SOURCE_REVIEW": 38, "BLOCKED_DEPENDENCY": 1, "BLOCKED_DECISION": 2, "SUPERSEDED_DUPLICATE": 1, "FROZEN_SNAPSHOT_PENDING_PUBLICATION": 8, "READY_SOURCE_ONLY": 4}`。目标维持50可行动项，实际不足50时如实披露；不能把完成历史当剩余数，也不能把审查数当bug修复数。
- EXTRA6独立PR36，最后实际读回ecb5f418，84历史项=20源码/11反证/3阻塞/44READY+6在途。独立队列仍其唯一维护者；不能假称全已集成CORE。
- 两队各最多6 worker、gpt-6.1-sol；完成或阻塞后取本lane下一具体项，现域耗尽如实记录并等待明确新线索，不能以测试/泛审计凑修复任务。

## 精确依赖与剩余边界

E03四参freeze公开7cef70bf已精确集成，CORE原session调用已接。C05 typed RATE_LIMITED、内部provider-result-received marker及shop NPC既有bizType路由均已公共生产/消费，回归仍未执行。C06当前局scope、剧情写竞争恢复、线性旧局保序均未验证；保序仅旧node仍可用场景，不声称作者重建/删除ID也能完整兼容。21之后空集合fallback补点另23，不能覆盖旧快照或抹掉风险。

JourneyStory节点重映E03-11内部合同已冻，等待EXTRA/E03实际公共producer，CORE/C03-05保持依赖阻塞。raw树保未知字段；storedStory先重映再chapter/opening/withEndingChapters；sys.asked形状是sys.asked.<nodeId>.<questionId>（仅中间nodeId变），outcome/mistakes/passed才末尾ID。缺映射沿既有fail-closed，不全树二次映射。

仍需实质决策的记录保留：NPC同requestId不同message持久指纹/旧回执兼容；协作者null/重复输入语义；photo既有429退款与网关UNKNOWN保留的跨池政策差异。Creator完整有效稿独立计次也不能机械套网关。未知usage缺能力只标估算/null，暂停job/语音日池属于合同或部署边界。未审店NPC gate差异只读记录，未擅改鉴权。

活动发布page仍原Claude文件锁，已直接交PR27 comment6193824961：首次create丢响应/提交后媒体500不能盲重发，同步回调也不反盖loading。无冷启动身份既有合同则不得伪称跨重启恢复；C00/CORE未抢写。

## 验证和恢复

本轮不手动编译/测试/Node/Java/Maven/DB/lint/DevTools/业务验证，不直连VPS，不手动重跑CI；既有自动CI自然运行。原1e403b13的231项VPS PASS只覆盖原冻结SHA，69c失败保留历史，不沿用本PR。4a旧Node结果也不覆盖本轮新代码；共享动作摘要/UI行号待本地统一重建验证。未合并、未部署、未迁移、未设备/真实身份HTTP验收，TEST_DEPLOYED=NO。

恢复先读本页、LONGRUN_ROLLING、LONGRUN_SOURCE_OWNERS、CORE池和EXTRA队列/CLAIMS、PR16最新CY-HANDOFF；核公共head与工作树，不覆盖WIP。每任务唯一源作者；表外只读。不可变快照`/tmp/longrun-snapshots/<ID>/manifest.json`按原交付顺序小提交，C01-16仅-r2、C03-17仅-r1；CORE/C02顺序12→11→13…，不可按ID反灌旧文件。已公共commit_sha是恢复真值，只有local_commit_sha的项尚未交付GitHub。

Git CLI写凭据失效时使用已连接GitHub Git Data接口发布相同文件树/小提交，公开SHA以实际ref为准；不提取/打印凭据。源码及队列定期公共checkpoint，两队直接PR16对账不依赖Mac。平台时长/额度终止时留下SHA/未发布快照/依赖/下一项；不承诺永久运行。
