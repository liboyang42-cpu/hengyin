基线：477696c8eb2fa24bd3d7febc896415bf0d1b67c0；隔离源码候选，Java/MySQL/HTTP/供应商/DDL/CI 均 NOT_RUN。

必须串行应用本目录三份 review.patch（未擅改共享正文）：
1. controller-and-fixtures：voiceChat 与 /chat/request 合读、shopChat 防原ASR键作为新文本请求，及原3类测试真实ASR owner装配。
2. text-admission：在原 NpcChatWriteService.admit 的同一 member→receipt 短事务内，永久消费一次 text_admitted。必须接此 hunk，才能证明原文本48h清理后所有入口不得重进模型。
3. retention：复用原 cleanNpcChatRequests 批量任务清除到期转写正文，保留原返回计数；无新任务/线程池。附原 retention test 的最小注入。
以上 patch 均 git apply --check 实际通过；root应用后才可验实际生产调用链，不能把未接线源码当闭环通过。

必须保留 E 3856fde6adec5534e69516f1170c0d576c92f81e 的 STT 及 common-and-wiring.review.patch。已读两者；本片不修改 STT/common。477 的 service/admin/framework 源树未找到 NpcVoiceEnrollmentService，未猜接口；实际复用 NpcChatWriteService 的独立短Tx及同一 ums_member 栅栏模式。
调用链：原文本回执优先 → 原格式/大小/本人资格 gate → ASR admission 提交确认 → 事务外原 STT 一次 → 独立保存 RECEIVED(含空)/NOT_SENT/UNKNOWN → 原资格重读 → 原ASR handoff提交 → chatWithShop(rid) → 原admit一次性登记及原模型/额度/安全算法。
未知提交、异常、超时或进程退出不再授外呼；IN_FLIGHT 无租约回收。异音频SHA256/格式/节点/商家冲突。GET只合读阶段，不外呼/不启动handoff。成功正文缺失、到期或本人删除保留原身份并明确不可恢复；原UNKNOWN/无voiceId不升级、不查询供应商、不重发。

测试源码：原8类72个case及全部原断言保留（仅必要装配）；新增11 owner unit +4 Controller +7 MySQL。原 provider/额度算法未改。静态检查实际通过：原case/断言、8个Mapper绑定、token/state/delete fence、claim→provider及handoff→text顺序；不能替代Java编译、真实并发、完整模型/额度或进程kill验收。
迁移仅一份 canonical additive CREATE，无历史补写、无生产执行。只读预检：information_schema.tables 中 npc_asr_intent 必须不存在；columns/statistics/table_constraints 核对本文列、唯一(user_id,request_id)、两expiry索引及3个CHECK；确认 MySQL8/InnoDB、原 ums_member 锁和 Mapper 可用。占位/结果/文本墓碑不得TTL删除。
回退先停新ASR入口及协调消费者；保留新表、已知/UNKNOWN事实和handoff/text_admitted标记。不得删历史或恢复旧直发代码绕过这些身份。
