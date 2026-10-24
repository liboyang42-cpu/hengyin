# E 复刻原意图 / UNKNOWN 闭环候选

基线公共 `0d32d888e29c6d5ef51322b10d7aca736be856ff`，独立工作树 `/workspace/hengyin-e-voice-intent`，分支 `codex/architecture-day-e-voice-intent`。STT 已另冻 `3856fde6adec5534e69516f1170c0d576c92f81e`；本片不混其 common patch，也不改 HttpUtils、transport、配额、付款、内容或鉴权。

真实根因：ApiMerchantController.npcVoiceEnroll 与 NodeNpcService.voiceEnroll 在 enroll 异常后清 voice_id 并写 FAILED。供应商创建成功而回包丢失后，原样本会被再次复刻。原 voice_version 只防旧写入覆盖，并不保留外部执行身份。现有 Photo/Theme/Payment/NPC-chat receipt 均有各自绑定的业务身份/结果/补偿，不能冒充录音复刻身份；本片复用其短 REQUIRES_NEW / durable claim / 外呼事务外 / current-read 规则，不修改或泛化这些核心。

两条真实 caller 保留原授权、scope、consent 与规范附件闸，只将 enroll/status 四个方法的执行簇委托给 NpcVoiceEnrollmentService。其余方法（含 reset）、构造器及原 NpcProfile getter/setter 方法体不变。NpcProfile 新增 voiceIntentId/voiceIntentState/voiceReasonCode 三个仅回包字段，不入旧 SQL；merchant 沿原 AjaxResult 显式投影，node 沿原 NpcProfile 回包。无新路由/状态数字；原 voice_status 0/1/2/3 不变，UNKNOWN 以独立 intent state 呈现，不能把该结果当 READY。

锁顺序 NPC→intent，均为 current FOR UPDATE，useCache=false/flushCache=true。canonical 样本引用的 SHA-256 + NPC 唯一（签名 URL 续期不换身份）；原 actor、scope、样本、指纹永久保留。首次 INSERT/PENDING 的旧版本 CAS/claim 同短事务提交，提交后只有赢家执行一次 enroll。并发者、IN_FLIGHT、UNKNOWN、NOT_SENT 和终态重放均不重新执行；没有 lease、TTL、定时补发或显式另起收费端点。未知旧 FAILED/清除后遗留样本没有持久身份时返回 VOICE_LEGACY_UNBOUND 或拒绝新创建，不猜“未执行”、不迁移历史。

安全前检 processingUrl 仍由原 caller 使用实际 actor 做严格 owned/APPROVED/AUDIO，发生于首次写入前；只给新赢家生成的短期 URL 在本次内存使用，不持久化。业务事务内调用在任何 mapper/provider 操作前拒绝。原样本不同的新请求也不能绕过此 NPC 的 IN_FLIGHT/UNKNOWN（包括 reset 后）。相同 canonical asset 表示同一原输入；不同上传 asset 不被伪称同一文件哈希，本片不做音频内容去重。

只有精确 AiProviderNotSentException 或精确 HttpRequestException 的受信 NOT_SENT disposition（直接或 VoiceCloneClient 原 AiServiceException 一层 cause）才存 NOT_SENT；HTTP status/timeout/IOException/任意运行错误/文字/cause 深层猜测皆 UNKNOWN。NOT_SENT 也保留身份，不自动重试。UNKNOWN 不清原样本/已知 profile voiceId，不写 FAILED。provider 返回的新 voiceId 必须先独立存为 PENDING 后才查询；查询失败只保留原号，后续仅查原号。READY/PENDING 的 voiceId 永不换号。profile CAS 仍按原 voice_version；reset/修改后迟到结果保留在原 intent，但不挂回 profile，并显式标 VOICE_PROFILE_CHANGED。外部动作无法与 DB 物理原子提交；存结果失败/进程崩溃后 IN_FLIGHT 保守不重发，可能需供应商人工查证，无自动“解挂”。

候选 schema 仅 `chengyinhub-admin/sql/migration_npc_voice_enrollment_intent_20261024.sql`：全 schema CHECK 名冲突/版本/严格模式/原 voice_version 预检→单条完整 CREATE→精确列数/四 CHECK/唯一及查询索引回读。不修改旧 migration，无生产 DDL 执行。若 CREATE 已成功但后检失败，不重跑 DROP/IF NOT EXISTS 掩盖差异；先清点结构/约束，已写身份永不清表。回退不得恢复旧异常即 FAILED 的重发路径；保留此身份表和 gate，或关闭复刻受理，不能以回滚旧二进制释放身份。

新增测试源码：14 service unit（受控 mapper/事务双，非 MySQL 证明）、4 两个实际 caller→实际 service/media guard 的 unit（provider/DB 双）、3 真 MySQL IT（原 NpcProfile SQL、新 XML、两个真实 service 实例/独立连接、独占 scratch、barrier）。MySQL IT 显式 CHENGYIN_AI_MYSQL_IT_PORT/USER/PASSWORD，严格 CREATE 精确随机 schema 成功后才标 owned，AfterAll 只 DROP 本名并 readback=0；无 skip/mock DB/H2。provider 全受控，不调用真实模型。

实际只执行 verify-static.py / diff check / 文件门禁 / 有限六模式扫描。**全部 Java/JUnit、3 MySQL、CI、完整 gitleaks、HTTP JWT/真实模型 NOT_RUN**。未把旧 voice 或 AI 验收回执挪作本片证据。

旧 fixture 不删除、不 skip，原 blob 身份留存：ApiMerchantNpcVoiceTest `16d73c1b95759ca5335a5d0099101275ae52e387`；NodeNpcServiceTest `16c59b9601129d99ee6beceb772451e1306d0f32`；NpcVoiceVersionRaceTest `eb56a345dbddc98cc75efb325039ea99012f8423`。它们手工 new 原 caller，尚未注入新持久 service；root 需在统一回归前串行适配旧 fixture，不能声称旧 suite 已绿。原“generic 上游异常应清 ID/FAILED 可重发”断言与本次批准合同冲突，明确退休，由新 dispatchedTimeout/statusHttpFailure/historicalFailed/caller lostEnrollmentResponse 取代；其余边界迁移对应：

| 原场景 | 本片对应源码 |
| --- | --- |
| 先落任务再外呼 / 同录音并发一次 | readyResultReplays / concurrentSameSample / MySQL twoInstances |
| 原成功回读、查询失败保原号 | knownVoiceIsDurable / caller queryFailure / MySQL knownId |
| reset 后旧成功/旧失败不能复活或清新状态 | resetDuringCreate / resetAndDifferentSample / MySQL knownId |
| 旧同录音换任务应再次外呼 | 该不安全预期退休，原身份 replay + UNKNOWN 阻止另起 |
| 原 scope/consent/媒体审核与上传者隔离 | caller reviewedButForeignAudio / missingConsentAndHeldAudio + 原 caller guard 逐字保留 |
| 失败库不可伪装无行 | databaseFailure / resultCommitFailure |
| 本地未派发与不确定执行 | onlyTrustedNotSent / statusHttpFailure / MySQL dispatchedUnknownAndTrustedNotSent |

余项：root 决定并接 UI 的 UNKNOWN/PROFILE_CHANGED 展示及旧 fixture 适配后统一真实验收；“明确另起”可能再次耗供应商额度的政策未答，因此本片无重建授权/取消/收费功能。跨授权撤销严格 inflight 截止、供应商原号查证接口缺失、音频同内容跨 asset 去重不在本片证明范围。
