# 声音 UNKNOWN 后明确另起请求

基线 `8490765f0572dae8ec3468f4e75d0ff6f4760b12`；独立分支 `codex/architecture-day-c-voice-confirmed-new-request`。只交本分支 voice delta，不覆盖 root/E 工作树。

## 实现与调用者

- 商家 `ApiMerchantController.npcVoiceEnroll/npcVoiceStatus`、节点 `ApiChapterMerchantNodeController` → `NodeNpcService` → 原 `NpcVoiceEnrollmentService`。两条原权限/归属、独立同意、APPROVED AUDIO 校验均保留；请求 actor 取真实登录主体。
- 新请求携带 `voiceRequestId` UUID、`voicePreviousIntentId` 和明确风险确认位 `voiceDuplicateChargeConsent`。该字段名是协议确认位，不表示所有模型收费，不读取或假设线上运行模型。先查原任务；如有原 voiceId，沿原状态查询恢复，不按共享 prefix 归认。
- NPC 行锁 → 原请求当前读 → 原终态/同键语义校验 → 最新 UNKNOWN/IN_FLIGHT 前驱校验 → 短事务占位和原 profile CAS → 事务外 provider → 原请求终态事务。重复确认用新键也不能再次指向已被取代的原前驱；同键异样本/前驱/actor 拒绝。
- 原意图 id、sample_sha256、sample_reference、state、provider voice_id、占用记录不删除、不重置、不释放。迟到原结果只按原 requestId 完成原行，原 profile_version CAS 不允许替换后选定的声音。
- status 支持原 requestId；actor/scope 校验后读回。恢复列表最多20项，仅身份/状态/前驱/是否当前选择，不含样本、provider id、签名 URL 或 token。历史选择只查看原结果，不重新选择音色。
- 真实小程序调用者：`pages/merchant/decor/ai-npc`、`pages/topic/components/cy/node-npc-form`。复用既有 session revision/登录代次守卫；每账号×门店/节点一个有界 journal，最多20条，写后严格读回；不保存音频、路径、URL、token。原 UNKNOWN 不覆盖，刷新/重进只查询，确认新建才发送。页面/表单/登录代次变化和迟到旧回执均不更新另一当前意图。
- 中性提示：原结果仍可能完成；新建可能产生重复音色或占用额度，费用以实际模型为准。没有硬编码免费/收费判断或新计价平台。模型说明依据协调者给定的阿里云公开页面：https://help.aliyun.com/zh/model-studio/voice-cloning-and-voice-design 。未读取运行配置、未外呼。

## 迁移与限制

新增 `migration_npc_voice_confirmed_attempt_20261024.sql`，只扩展原表：request_id/previous_intent_id；原行确定性别名 `sample:<原哈希>`，原哈希不变；唯一身份改为 npc_id+request_id。保留原迁移，补预检/回读和新的唯一/CHECK约束；未执行 DDL。root 需将候选迁移串行登记并在受控环境验证。

已有多独立请求后不能回退旧 sample 唯一键、删行/清 pending 或用旧程序直接覆盖；应停新建或向前修复。无可核对原意图的历史未绑定 UNKNOWN 继续拒绝，不能伪造前驱。客户端20条上限满时明确拒绝新建，不驱逐 UNKNOWN；服务器原身份继续保留。

## 配套测试源码（全部 NOT_RUN）

- `NpcVoiceConfirmedNewRequestTest` 新6例：确认正常/原占用保留；缺确认/错前驱/重复新键；同键异样本/前驱；原状态恢复不重派/历史无PII；异actor拒绝；原 IN_FLIGHT 迟到完成不覆盖新声音。两 scope 正向均保留。
- `NpcVoiceEnrollmentCallerTest` 加1例：实际两 caller+媒体 guard 的确认拒绝/允许及原记录保留。
- `NpcVoiceEnrollmentMySqlIT` 加1例：真实原 mapper/新迁移下同样本两身份、重复原键、异输入拒绝、原 UNKNOWN/新声音共存；原用例保留，重复身份负控改带原 request_id，继续命中真实唯一约束。
- `voice-confirmed-new-request.test.js` 新15例：两真实 VM 调用者各7例（取消、确认/重复、拒绝独立同意、真实角色revision及ABA、磁盘读回失败、重进仅查询、迟到旧历史回执）；另1例账号/节点隔离、无敏感输入、满20条保留UNKNOWN。
- 旧 Java 交易/mapper doubles补原 request/latest/history 装配；3个节点API测试迁真实签名；原白名单5字段→9字段并加新字段断言，仍禁止voiceId。4个旧Node夹具补真实journal/session、合法资产ID和原请求回执；保留原场景，声音上传断言从旧URL改为精确canonical asset引用。

## 实际执行证据

只执行 `git diff --check` 及 Node `--check` 静态语法（journal、两生产调用者、新Node用例、配套fixture helper）；无错误。未运行任何 Node 测试、Java 编译/JUnit、MySQL、CI、DevTools 或 provider；没有截图/真机或部署验收。费用/ASR/NPC额度、AI起草额度与金融源码均未改。最终秘密扫描随提交完成。
