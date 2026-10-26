# H：原语音恢复夹具 SOURCE 合同

仅源码交付。parse、import、测试、编译、Java/DB/Docker/HTTP、CI、真实 provider 均 **NOT_RUN**。本文只记录静态代码合同，不包含内部运行报告、日志或观测状态，不构成业务/恢复/清理通过结论。

## 基线及 ownership

- Parent：`c0355527bd2082ea957b08fd684a4a8887470858`
- Parent tree：`8341bde4a097ab915d59333ab07333a291290f28`
- 独立源码目录：`/workspace/hengyin-h-restore-voice-c035`，未切换或回退共享 checkout。
- 实际 owned 仅原 `RestoreVoiceReconciliationFixture.java` 和直接新增 `RestoreVoiceReconciliationFixtureTest.java`。
- 共享诊断/reader/控制仅交 [shared-diagnostics.review.patch](shared-diagnostics.review.patch)，供 root 在当前共享源串行合入；本提交未改这些共享路径。
- 未改生产、auth/session、小程序/台账、付款退款、共享 host/runner/workflow、schema/migration 或原其它 fixture。

完整 owned delta：[owned.review.patch](owned.review.patch)。owned 后像：[POSTIMAGE.sha256](POSTIMAGE.sha256)。共享 hunk 预期后像：[SHARED-POSTIMAGE.sha256](SHARED-POSTIMAGE.sha256)。补丁总 hash：[PATCH.sha256](PATCH.sha256)。

## 静态失败接缝与边界

原夹具的 create Answer 从整个 intent 表取最后一条 IN_FLIGHT，query Answer 又执行可失败的 SQL/journal 证明。真实 owner 按原合同将普通提交异常转为 UNKNOWN、查询异常保留已持久化的 PENDING；夹具自己的证明异常因此可能只表现为外围 READY 断言失败。

这是可由源码确定的夹具接缝。本包没有原动态返回态或具体被捕获异常的运行证据，**不能认定其已是某个状态、某个生产缺陷，亦不能声称本包已经运行闭合**。身份排序/精确读回的改动不冒充对原运行首因的动态证明。

## 完整原消费链

原 fixture enroll → 真实 NpcVoiceEnrollmentService：

1. 仍以 actor=88、scopeType=2/scopeId=31、原 NPC、canonical `media-asset:73` 调旧 overload；没有另起 request、previous intent 或 duplicate-charge consent。
2. 原 owner 的 claim、REQUIRES_NEW、profile CAS、原 request/sample hash、PENDING/READY/UNKNOWN 写入和 status/重放都保持真实 Mapper/SQL/事务。
3. 新的 test-only FixtureVoiceProvider 仅控制外部边界，不 mock owner、事务、回执或持久化成功。approved supplier 保留原测试介质语义，只增加固定 NPC 对应的私有测试 input，不能接受任意 URL/新 NPC。
4. submit 先精确读原 NPC/actor/scope/request/sample 对应的已提交 IN_FLIGHT，才向原 RestoreProviderJournal 追加真实 fixture SUBMIT/RESULT；没有 broad latest-row 关联。
5. query 仅收原已知 voiceId，先独立读到持久 PENDING 和原 voiceId，再核 journal 原身份、原 sample/request、原 admission version +1 与已存在 READY 外部事实，最后才记录 QUERY/返回 ready。
6. identity 使用稳定 TreeMap 字段序，保留原全部字段及原 admission version；不以 PENDING/READY 的后续版本覆盖外部原身份。新增源码控制覆盖原 journal JSON 写入/读回形状。
7. fixture 内部 SQL/journal 自检异常单独保留；不能把该夹具失败当成合法 gap UNKNOWN。原明确模拟的响应未知仍走真实 AiServiceException 路径，保留 UNKNOWN、不查询前缀、不补造 voiceId 或成功事实。

## 保留的原断言及补齐

- SOURCE 仍严格要求原已知任务 READY、gap 原任务 UNKNOWN，BACKUP_READY 仍在全部事实检查之后。
- 新增独立 SQL 验证原 intent ID/request、原 sample、profile/intent version、selected 原请求、原 voiceId 或不可查 null、profile voice_status、UNKNOWN reason。
- 原商家更新会置 audit_status=0；该安全复审围栏保留并断言，provider READY 不被解释为内容已审核或 TTS 可越权使用。
- 每原声音只准一次 submit；UNKNOWN 零 query；已知任务仅完成原一次 query。
- 恢复模式对新 enroll 的原 Blocked 类型/消息、原 phase 限制和原 intent 全行不变断言均保留；另加 profile 全行不变。
- fresh NORMAL_READBACK 必须通过真实 owner 读回原 READY，原 enroll 重放不得再发；新 provider 实例不能靠原内存状态假成功。
- 源录音/同意/费用规则、真实调用者的授权及媒体守卫未改变；本 fixture 证明范围是原 owner 的 actor/scope/request 边界，不能替代 HTTP/RBAC、真实录音审核或供应商验收。
- 原 migration 和清理流程不改；不能以资源退出推定 schema 清理已证。

## root 必须串行接的窄共享 hunk

共享 patch 包含三文件，共同完成有限故障观察合同：

1. `RestoreChildDiagnostics.java`：仅对 final 的 VoiceFixtureFailure 导出六个 enum 字段；该异常映射为原 IllegalStateException 类型。仍不输出 message、raw cause、provider 文本、URL、音频引用、任意 payload 或 ID。
2. `ci/restore_business_reconciliation_probe.py`：只接受精确 voice child、两个 voice cohort、对应原 phase/stage 的六个白名单 enum 字段。额外字段、任意文本、错误 child/phase/cohort、callback+voice 混合全部拒绝。原 source/scope/boot/type/SQL/预算约束不动。
3. 原直接 Python 控制：新增一条静态协议正反控，覆盖枚举/字段/child/phase 拒绝，原断言全部保留。

六字段固定为 operation、expected、intentState、profileState、selection、providerStep。expected 只接受 READY/UNKNOWN；缺失或未审字段只映射固定 MISSING/UNREVIEWED，不能成为成功条件。

**请将共享 emitter + reader + 控制作为同一源码合同串行采用；不整类覆盖他人修改。** 未采用 reader/emitter hunk 时，原严格 reader 不会凭新字段自动放行，本包也不宣称诊断接线已完成。

## 直接 unit SOURCE

新增五个方法，复用现有 NpcVoiceEnrollmentServiceTest.Fixture 的真实 owner + 原 unit 事务/Mapper 模型；该模型不是 MySQL/持久化验收：

- known：已提交 claim → 原 ID PENDING → READY；身份/版本、actor/scope 拒绝和 fresh normal replay 无二次 provider；
- gap：原 UNKNOWN、null voiceId、无 query/重投；
- query 自检失败：保原 PENDING/known ID，夹具错误不得伪装为合法未知；
- claim 读失败：没有外部事实，不得假 READY；
- journal identity 稳定读回及有限观察不泄漏任意字段。

原 NpcVoiceEnrollmentServiceTest / NpcVoiceEnrollmentMySqlIT 源码均未改。新增 Java 五方法及共享 Python 一方法全部 NOT_RUN；语法/类型只手工对照当前接口/bean/Mapper/caller，未启动任何 parser/import/build。

## 验收限制

旧动态状态仍未知；此包只交可运行候选 SOURCE，不能承诺两种恢复目的已通过，也不能以新控制存在替代实际执行。root 源齐后统一核对编译、同候选 unit、实际 SQL/两种恢复目的、原 ID/hash、禁派发与精确清理；不新增 runner 或真实 provider 调用。

无新表、依赖、费用、权限或生产政策；回退只撤本 owned 增量及 root 串行采用的对应窄诊断 hunk，不能删原 intent/回执/外部证据。
