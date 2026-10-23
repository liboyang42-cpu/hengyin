# Photo 下一批编排接入合同（提案，待根合并共享接口）

结论：第一批候选 `9a90123b4a3735eb9303f4b38b72e81e51694be5` 冻结。本文仅定义下一批接口，不表示 Photo 已修复。C 负责高级玩法编排、原请求指纹与玩法终态；根负责照片意图、供应商调用及配额身份持久化，避免重复建立业务回执。核对来源为该候选中的公共仓库代码，未覆盖根尚未集成的 Photo 修改。

## 实际可复用设施与调用者

- `ApiAdvancedPlayController.action` 是主代码唯一 `IAdvancedGameRuntimeService.act` 调用者；HTTP 为 `POST /api/play/advanced/action`，现有参数为 sessionId/version/idempotencyKey/action/payload。控制器在 act 返回后执行 `finishPendingCardMint`。保留此接口参数和提交后铸卡边界。
- `AdvancedGameRuntimeServiceImpl.act` 当前带外层事务；`SUBMIT_PHOTO_CHECK` → `submitPhotoCheck` → `PhotoCheckVisionService.review(memberId,imageUrl,requirement)` → 模型，再 CAS/路线事实/`logAction`。CAS/后续回执失败会回滚玩法，却不能撤销供应商调用。
- `PlayAttemptLog` 的 attemptType=8、唯一 `idempotency_key`、resultJson 已是高级玩法终态回执。新增 request_fingerprint 绑定原动作；不再把高级动作同时写 game_command_receipt。现有 `replay` 只比较 actor/session，缺输入绑定。
- `GameRuntimeSessionMapper.selectByIdForUpdate`、`updateStateCas`、`updateFeedbackStateCas` 可供短事务使用；`selectAccessibleById` 提供读取授权。`configSnapshotJson`、playInstanceId、stateJson 中原 runtimeAuthorityEpoch/原 route/game session 是原局资格快照来源，不允许自动换局。
- `AiGatewayService.consumeQuota(Long,String)` 当前随机生成 nonce；`stageQuota/retainQuota/refundQuota` 使用原凭证。`AiQuotaRecoveryService` 已以 REQUIRES_NEW 保存原日/nonce/generation/阶段，UNKNOWN 不因租约过期退款。其 `AiQuotaReservationRecord` 没有 command identity、请求指纹、不可变照片输入或视觉结果，不能当作 Photo 业务意图。
- `AppMediaReferenceGuard.processingUrl(reference,memberId,"IMAGE")` 已验证审核状态、上传者和媒体类型；短签名 URL 不是持久身份。`PhotoCheckVisionResult` 可复用 concluded 结果载荷，但其 degraded 把无调用、超时、解析失败混在一起，不能表达可重调证据。
- `photoEvidenceVerifier` 实际为 `FileOssUploadUtils::verifyUploadedPhoto` 的 BiConsumer，非独立 PhotoEvidenceVerifier service。现有 r5 校验可复用，不能代替所有照片的上传者审核检查。

## 根侧精确共享接口提案

以下位于 `com.chengyinhub.business.service.ai`，根统一命名/合并。C 不预先写另一套 interface/DTO。

```java
interface PhotoCheckIntentService {
    PhotoCheckIntentView prepare(PhotoCheckIntentCommand command);
    PhotoCheckIntentView executeOnce(Long memberId, Long intentId, String requestFingerprint);
    PhotoCheckIntentView read(Long memberId, Long intentId, String requestFingerprint);
    void markApplied(Long intentId, String requestFingerprint, Long attemptLogId);
}
```

`PhotoCheckIntentCommand` 是不可变 DTO，精确字段：`Long memberId, sessionId, activityId, topicId, nodeId, ownerId, originalGameSessionId, originalRouteSessionId; Integer ownerType, expectedVersion, authorityEpoch; String playInstanceId, actionKey, requestFingerprint, imageReference, requirement, configSnapshotHash`。actionKey 是现有高级 scope 算法后的原 key；requestFingerprint 是 C 在任何 payload helper 前计算的完整原请求指纹。imageReference 必须绑定已验证的原资产，不能以短签名 URL 的变化产生新意图。根在准备中补充其不可变 assetId/objectKey/contentDigest 和模型/提示词/编码版本快照，不让 C 猜模型配置。没有现存内容摘要时需根明确实现或阻塞，不能声称 URL 就证明字节不变。

`PhotoCheckIntentView` 是不可变 DTO，精确字段：`Long intentId, memberId, sessionId, attemptLogId; String actionKey, requestFingerprint; PhotoCheckIntentState state; PhotoCheckVisionResult result; String reasonCode`。result 仅 RESULT_READY/APPLIED 可有值；不向客户端泄露 quota token、lease token、原图字节或模型原文。

`PhotoCheckIntentState` 最小集合：PREPARED、IN_FLIGHT、UNKNOWN、RESULT_READY、APPLIED、NOT_SENT。NOT_SENT 必须有可证明未发送的固定内部原因码，不能由超时推断。未知/未解析结果不能返回 concluded(false) 冒充模型拒绝，也不能走现有 fallbackPass。无结果如何反馈不扩张现行业务权益；若根要调整既有 fallback 语义，先明确合同。

方法事务合同：

- `prepare` 为 MANDATORY，参与 C 的资格短事务；对 actionKey 做唯一绑定，原指纹相同返回原行，异指纹冲突。资格拒绝先发生，不扣配额、不发外部调用、不留业务效果。原意图先于任何可收费外部请求提交。
- `executeOnce` 的入口与模型 HTTP 均不得有活动业务事务；可使用内部独立短事务推进 PREPARED→IN_FLIGHT 和持久化结果。必须先保存原日/nonce/配额凭证身份再执行 Redis/模型；若现有 consumeQuota 无法完成此绑定，根需提供其受控扩展，不能调用随机 consumeQuota 后才补写关联。IN_FLIGHT/UNKNOWN 永不因租约过期新领 nonce 或自动重发。并发第二调用仅返回原状态；不假设供应商支持幂等或查单。
- `read` 必须只读、不续调用、不扣额；核对 memberId/原 session/指纹。C 在调用前仍做 session 读取授权。
- `markApplied` 为 MANDATORY，和 C 的本地效果、PlayAttemptLog 插入在同一事务完成；仅 RESULT_READY→APPLIED（同 attemptLogId 的已完成可重入），检查指纹，更新条数异常必须回滚。照片表只保存到既有 PlayAttemptLog 的关联，不再保存一份玩法 responseJson。

## C 的三阶段编排

1. `act` 改为无外层事务的调度入口；通过 TransactionTemplate 的显式短事务承载原普通动作和 Photo 准备/最终提交，不使用同类 @Transactional 自调用。若上游意外已有事务，照片分支在任何外部调用前明确拒绝，不能暂停后继续持锁 HTTP。普通动作事务语义保持。
2. Photo 准备短事务：先读取授权与原指纹终态回执（合法旧回执先于当前版本冲突），然后锁当前局并复核资格、expectedVersion、owner/scope/原 epoch、config、照片审核归属与原输入；调用 prepare 并提交。不得增加 tries、积分、次数、奖励或路线事实。重复原意图先读其现有状态，不用最新版本重绑原命令。
3. 事务外 executeOnce：仅原意图首个执行者发送；UNKNOWN 返回原意图状态。进程在发送前持久标记后崩溃，也只能保留 UNKNOWN，不能猜测从未发送。结果到达先独立落 RESULT_READY，玩法事务失败不丢失结果。
4. 最终短事务：重新授权、锁原 session，先读原终态，再校验原期望/资格；只消费已保存结果，把既有 submitPhotoCheck 拆成无供应商调用的结果应用 helper，保留明确的成败计数/奖励规则；CAS、路线事实、指纹终态 PlayAttemptLog 与 markApplied 一起提交。CAS/回执失败则本地效果全部回滚，根的 RESULT_READY 保留；回包明确外部结果已存在，不返回 intentNotExecuted、不自动换 expected/new key 再调用。
5. 提交后再执行既有 finishPendingCardMint。卡片外部动作自己的持久意图问题不以本照片合同宣称修复。

## 文件所有权与最小测试

C：`AdvancedGameRuntimeServiceImpl.java`、`PlayAttemptLog.java`、`PlayAttemptLogMapper.java/xml`、`ApiAdvancedPlayController.java`、高级 fingerprint/编排专属测试、C 报告。共享 `IAdvancedGameRuntimeService` 如需新增只读回执签名由根合并。指纹迁移为 C 单独文件/单独提交，与 Photo 分开。

根：`PhotoCheckVisionService.java` 及已声明独占夹具、上述 Photo intent service/DTO/domain/mapper/XML/schema、必要的 quota 原身份扩展与相应恢复测试；共享签名由根一次合并。C 不编辑根的这些文件。

先做红测：同 key 改 action/payload/expected 拒绝；合法旧结果先于当前版本冲突；历史 NULL 拒绝；提交后丢响应重放；模型已调用后玩法 CAS/logAction 故障时原意图和结果仍存在；并发同意图供应商一次；IN_FLIGHT/UNKNOWN 过期/重启不重领；结果已到但旧 epoch 不获本地权益。真实事务/唯一键/回滚用批准环境 MySQL 测试；Node/静态检查不能替代。本文阶段没有执行 Java/MySQL/Redis/供应商调用，没有执行任何 DDL。
