Parent: `8490765f0572dae8ec3468f4e75d0ff6f4760b12`。仅用户已批准的 ASR/NPC 共享额度接线；未执行测试、Java、DB、Redis、CI、供应商或 DDL。

`voiceChat` 原格式/大小/本人资格闸 → 原 member 锁下持久 ASR 身份及 quota reservation → 提交确认 → 原 gen 池 `consumePlannedQuota(npc_shop_chat)` → 持久 `ASR_STARTED` → 单次原 STT。原文本回执若已在相同 member 围栏内登记，ASR 不得再预占。

转写仍独立提交。原 `beginHandoff` → `admitShop` 同一短事务核对冻结的 user/node/shop/转写、CAS 永久 `text_admitted` 与原文本 lease → `generateNpcAsrHandoff` 回读原 ASR/额度事实，只执行原文本审核/模型算法，不再扣额。原文本资格、成功回执优先、48h 清理后不能重入模型、删除/过期正文墓碑和 GET 无副作用均保留。

用户的一次功能额度与供应商计费不是同一事实：ASR 与模型可能分别产生费用。本片不宣称供应商只计一笔。ASR UNKNOWN/提交不明/保存失败保留原 reservation；`ASR_STARTED` 不被后续阶段覆盖，租约过期由原恢复任务记录 UNCERTAIN，不能凭超时释放。用户 UNKNOWN 投影保留原 pending/errorCode，并显示“额度状态处理中”。

只有本次调用尚未执行 STT 的本地证明或原 typed NOT_SENT 才写 `NOT_SENT + quota_refund_pending`；该证明与 ASR 终态同事务提交。提交后唤醒原 quota owner，漏唤醒由既有 `AiQuotaRecoveryService.runDue` 扫原待办。补偿复用原 receipt/generation/owner/fence，不换号；Redis/SQL 不明保持原待办。ASR 已执行后，文本自己的未发/审核/业务失败不退这次共享占位。

单份 additive SQL：`chengyinhub-admin/sql/migration_20261024_d_npc_asr_shared_quota.sql`，新增 nullable `quota_reservation`、默认0的 `quota_refund_pending` 及其扫描索引。旧行保持 NULL，不补造旧 quota；只能只读核实，不能重发/新扣。SQL 内含只读预检与验证。回退先停用调用者，保留列、身份、回执和补偿待办；不能退回旧无永久 ASR 身份实现。原 quota 表无需结构变化，原 gen/NPC/photo 分池、上限与 Redis 计数脚本未更改。

原测试源码/断言保留，仅按新必需依赖与真实 shop admission 接线。新增源码覆盖预占/阶段失败不外呼、UNKNOWN 不退款、空转写保留占位、删除后 NOT_SENT 原号补偿、文本先赢/冻结对象冲突、handoff 不二扣、原恢复 fence/Redis SQL 故障及只读 quota 投影。原 MySQL ASR 7→10，quota recovery MySQL/Redis 7→9；这些是源码用例数，不是实跑结果。原 ASR MySQL suite 的 quota/provider 是受控 double；quota recovery suite 使用真实 SQL/Redis 与 ASR 事实 adapter，不冒充完整真实 HTTP/Spring/进程 kill 或供应商计费验收。

root 负责串行整合、SQL/registry 必要登记和最后统一验收；本片不修改工作流/registry、任务暂停状态或 C 的 voice enrollment 文件。
