# E 341b 后 fixture / 查询快照 / voice 日志最小增量

parent `341bfc2e7e0167e84f65fa9084d439605c267ff3`，分支 `codex/architecture-day-e-voice-fixtures`，工作树 `/workspace/hengyin-e-voice-fixtures`。341b 与 STT3856 原 ref/源码冻结不改。本增量不改 schema、quota、请求身份/指纹/claim/UNKNOWN/NOT_SENT 判据、授权或收费政策，不增加依赖/线程池/重建路径。

旧三类手工 new caller 现在均注入实际 NpcVoiceEnrollmentService。专属 NpcVoiceEnrollmentFixture 只建测试用 mapper/事务双：原 provider/profile mocks 仍由原测试配置；profile 查询保持原 scope 检查和 detached reads，exact rowcount 仍由实际 service 判断，成功 commit 才把原 UPDATE 后状态应用到原 fixture 行。intent 持久映射/claim/rollback 独立模拟，不以 mock DB 充当 MySQL 实证。媒体 mock 改为 canonical 引用→原受控 provider 输入，真实 AppMediaReferenceGuard 场景仍保留；未放宽任何生产 guard。

| 旧 suite | 原→现测试源码条数 | 逐项收口 |
| --- | --- | --- |
| ApiMerchantNpcVoiceTest | 18→19 | 18 场景都保留，新增可信 NOT_SENT 的 FAILED/无新音色/不重发；补 scope_type=2、canonical fixture，实际持久 service 接线 |
| NodeNpcServiceTest | 25→26 | 保存/审核/冻结/权限/独立 consent/媒体归属断言保留；新增同上 NOT_SENT 场景；实际持久 service 接线 |
| NpcVoiceVersionRaceTest | 11→11 | 原所有并发/reset/旧写覆盖/媒体/consent 场景保留；实际持久 service 接线，未 skip/Disabled/删场景 |

必要的原断言迁移逐条说明，不能宣称所有原 assert 文字未变：

* 原 generic AiServiceException→清 ID/FAILED/“录音太短”原样返回，与已批准 UNKNOWN 合同互斥。保留同一异常触发，改验原 identity/UNKNOWN/PENDING/原样本/仅一次；原 FAILED、无新 ID 的合法结论转到新增**可信 NOT_SENT** 场景。不按 message/cause 深层猜测未派发。
* READY 以前两次 profile 写，现按批准合同必须先独立存 known-id 再 query 再写 READY：原 first-PENDING/last-READY/同原 voiceId/外呼在 PENDING 后断言保留，次数与最终下标改为三次，并新增中间 PENDING+known-id 断言；版本2→3是该额外真实写入，不降低校验。
* `noPersistedPending...` 的 exact rowcount=0 现在是 StateUnavailableException，原直接方法 Ajax500 改验此明确异常和 provider=0；不伪造“无行”或在 fixture 吞成成功。
* reset 后旧结果现在返回当前 profile + 已保存原结果事实/PROFILE_CHANGED，不能仍要求丢失原结果后报错。原 NONE/null-ID/不得复活断言保留。
* 同录音 reset 后再次 enroll 制造 voice-2 会违反已批准原身份重放且可能重复收费，明确不恢复此不安全触发。旧“不得覆盖已提交 replacement”的 fence 断言保留，用明确标注的**对抗 DB profile fixture**作为 replacement（无第二个 provider 调用，新增实际 enroll count=1）；旧失败仍不得清该原 ID/status，同时验旧 intent=UNKNOWN。不是新准入或额外扣额路径。

复核旧例还找出真实生产回归：原 `failedReadinessStillReturnsTheResetState` 的 body 和 NONE/null-ID 断言**逐字保留**。341b 查询 catch/false 返回 query 前快照，期间 reset 时 API 会假回 PENDING。现只在实际 query 失败/未就绪后用原 current locking read 回读当前 profile/原 intent，不重调 provider。原 enroll/inspect/finish/identity/provenNotSent 等方法体均未改。新增 service unit 一例覆盖 durable known-id 查询期间 reset 的异常/false 两种交错、原号不丢、replay 不重新查询/复刻。

日志只改共享 DashScopeVoiceClient 的六个错误输出点：去掉 raw/body/供应商 message 与 Throwable 堆栈参数，保留固定 category、action、failureType 与 requestId。provider request_id 仅接受严格 UUID，其余用本次本地 UUID 做**日志关联**，不是供应商查证凭据/新的业务请求号。私有 reply 携关联字段，不改 public VoiceCloneClient API、wire JSON、原解析/异常/cause/NOT_SENT 边界。新增一例真实 parser/logger 测试用受控 common transport、实际 admission、ListAppender 验正文/签名输入标记/异常消息均不出日志，分类/UUID仍在，原 cause 保留。

**root 必须串行应用 `node-status.review.patch`**：ApiChapterMerchantNodeController.npcVoiceStatus 手工 whitelist 只投原两字段，会丢新 UNKNOWN 元数据；精确补 voiceIntentId/voiceIntentState/voiceReasonCode 三个 put，其余方法与原 guard/body 字节不动。越界共享 controller 本体未编辑。已在临时原副本 apply --check/apply 并验证唯一方法差异。新增 NpcVoiceNodeStatusProjectionTest 四例源码覆 UNKNOWN whitelist、无 NPC 不伪造身份、DB错不假 NONE、enroll原Profile元数据；不应用 patch 时 UNKNOWN 投影例应红，不能只取本 commit 就称装配完成。

额外 caller/fixture 只读回核：ApiChapterMerchantNodeNpcAccessTest/BindingTest 与 NodeNpcManageRoleAcceptanceTest 使用 mock NodeNpcService，签名未变；NodeNpcStationScopeAcceptanceTest 的实际 voiceEnroll 只测原写闸拒绝，停在新服务前；MerchantStaffRevocationAcceptanceTest 没有这四个声音调用。NpcChatService.attachVoice 原仍要求 voice_status=2 AND 非空 ID，UNKNOWN/PENDING 保留旧 known-ID 不会被当成可合成。

现源码总计：旧三类56 + service15 + 既有 caller4 + logging1 + node projection4 = **80 unit 源码场景**；341b 的 **3 MySQL IT** 未改。相对341b新增8例，不减少旧场景，不把静态结果称测试执行。

已执行：verify-static.py、临时 patch apply、git diff --check、文件门禁、有限六模式扫描。静态证明 guard/原身份方法保持、原54场景存在、原三类无 skip、wire/parser/outcome 在仅去日志/私有reply shim后相同、共享投影 patch 可应用。**Java/JUnit、MySQL、真实 provider、DDL、CI、完整 gitleaks 全部 NOT_RUN**。root 应同源应用 shared patch、完整 secret 扫描后统一真实回归；本片不派发 CI、不部署，不开放待人类决定的另起收费路径。
