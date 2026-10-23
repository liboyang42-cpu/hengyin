# Photo 六参数接口：C admission/commit 拆分清单

结论：接受根给定的 `reviewOriginalAction(Long memberId, Long sessionId, String originalActionId, String canonicalRequestFingerprint, String imageUrl, String requirement) -> PhotoCheckVisionResult`，替代前一版文档中建议的四个共享方法。本文是实际方法级实施清单，尚未实现 Photo 编排，也不声称 Java 已验证。当前 HEAD 为 `5236c0d87efa01e7817fc0285cee2b6694ab5f75`；指纹代码候选仍单独保留为 `83a0479d2d28987e2ec757659c9ed7655ba8c742`。

## 接口固定值与结果合同

- originalActionId 传客户端原 idempotencyKey，逐字保留，不重新 UUID、不换 key、不用哈希后的 theme key代替原号；根的唯一域已含 member/session。玩法 PlayAttemptLog 仍使用现有 scoped key。
- canonicalRequestFingerprint 只由 C 的 commandFingerprint 生成，admission 冻结后原样用于根的 Photo 意图和最终 PlayAttemptLog。根不解析 payload 重新猜语义。
- 根持久 claim、quota 原身份与 DECIDED 结果；C 不建立第二张外部意图表，也不额外建立 game_command_receipt。玩法业务效果与既有 PlayAttemptLog 终态同事务提交。
- RUNNING/UNKNOWN 抛 AiProviderOutcomeUnknownException；C 原样传播，不 catch 转 degraded。结果落库失败同样不能生成新 nonce。DECIDED 返回根已持久保存的原 PhotoCheckVisionResult，包含首次实际 degraded；C 沿用既有 fallback 配置，不把 UNKNOWN 当 degraded。
- 根服务依赖尚未在此工作树中出现；C 后续提交编译依赖根合并该准确签名，不会在根拥有的 PhotoCheckVisionService 中临时加另一份实现。

## C 具体方法清单

1. `public Map<String,Object> act(Long memberId, Long sessionId, Integer expectedVersion, String idempotencyKey, String action, Map<String,Object> payload)`：保留公共签名，去掉覆盖整趟调用的事务。普通动作由 TransactionTemplate 执行原事务逻辑；Photo 分支执行下面三个阶段。入口发现已有业务事务即拒绝 Photo，不能用 NOT_SUPPORTED 暂停一个可能仍持锁的上游事务后继续 HTTP。
2. `private PhotoAdmission admitPhotoAction(Long memberId, Long sessionId, Integer expectedVersion, String clientKey, Map<String,Object> payload)`：在第一短事务中调用。先读取授权与原终态回执，合法原回执先于实体当前版本/epoch；锁原 GameRuntimeSession 后用 current read 复核回执、归属、固定 instance/原 authority、报名/活动/路线资格、原 expected、玩法开启/未通过、输入长度与冻结配置。此阶段不增加 tries/score、不写路线事实、不调用 Photo/OSS HTTP。返回已经完成的原回执，或不可变 PhotoAdmission。
3. `private PhotoCheckVisionResult reviewAdmittedPhoto(PhotoAdmission admission)`：严格在事务外调用。先断言没有活动事务，使用被冻结的 member/session/clientKey/hash/imageUrl/requirement 调用根的六参数 reviewOriginalAction；不回退三参数 review，不补新 nonce，不把空结果或 UNKNOWN 当成 degraded。r5 的现有 OSS 校验位置见下节待对齐项。
4. `private Map<String,Object> commitPhotoAction(PhotoAdmission admission, PhotoCheckVisionResult review)`：第三短事务。重新授权，锁原 session，以 current read 先读本人原终态；没有终态才复核冻结 scope/authority/config/expected，调用原动作效果核心。CAS、tries/score/路线事实和 logAction 同生共死。失败保留根的 DECIDED/UNKNOWN，不换 expected、不标为 intentNotExecuted；同 key 在资格仍满足时可复用根原结果恢复。
5. `private Map<String,Object> actInTransaction(FrozenAction command, PhotoCheckVisionResult photoReview)`：从现有 act 中保留的动作规则/CAS/路线事实/回执核心。普通动作完整沿用原事务；Photo 只接受由 admission 生成的 FrozenAction 和已保存的原结果。不得在这里发 Photo/OSS HTTP。超时挑战原本不走 provider 的本地结算分支继续在本地事务内执行，不为了统一流程额外调用模型。
6. `private CardMintRequest applyPhotoCheckResult(JsonNode config, ObjectNode state, Map<String,Object> payload, GameRuntimeSession session, Long memberId, PhotoCheckVisionResult review)`：从 submitPhotoCheck 提取纯本地结果应用，保留 tries、pass/reject、minConfidence、degraded/fallback、score 与待铸卡规则，移除三参数 review 和网络校验。待铸卡仍由 controller 在最终提交后调用 finishPendingCardMint。
7. `private String commandFingerprint(..., JsonNode originalAuthority)`：现有唯一 canonical helper 的版本化扩展，不能在 admission、Photo service、commit 三处各造一个 hash。commit 接收 admission 已冻结的值，不能重算为当前版本。

`FrozenAction/PhotoAdmission` 是本 service 的私有不可变值对象，不扩张共享 HTTP schema：原 member/session/clientKey/scopedKey/action/expected、深拷贝 payload、scope、原 authority、configSnapshot/hash、实际 imageUrl/requirement、canonical hash；不跨阶段持有可变 mapper entity 或数据库锁。

## 必须对齐的两处差异

**原 authority 的指纹版本。** 现有 v1 绑定固定 scope 与原 expected，但明确不读 state 中 epoch。新 Photo canonical 需绑定冻结的 `runtimeAuthorityEpoch/originalGameSessionId/originalRouteSessionId/zeroChallengeInstance`（无该 scope 的值显式为空，不能从最新 runtime 补造）。采用 Photo v2 canonical；终态 result_json 的服务内部保留字段保存其 authority 快照，读回时先校验 session/actor，再按该原快照比较 hash，返回客户端时剥离内部字段。这样实体当前 epoch 推进不会破坏已执行原命令的读取。保留 v1 已有有效指纹的读取路径；历史 NULL 继续拒绝。不能给旧行猜 authority 或回填 hash。根只保存 C 传入的同一 hash，不新增 schema 来重复承载玩法终态。

**r5 OSS HEAD 校验。** 实际 `photoEvidenceVerifier` 为 FileOssUploadUtils.verifyUploadedPhoto，会创建 OSSClient 并 getObjectMetadata；它不是纯本地 helper。目前它在 submitPhotoCheck 里，必须移出 admission/commit。若 C 每次在 reviewOriginalAction 前先 HEAD，会在原结果已 DECIDED 后仍依赖图片/短签名可用性，阻断原结果读回。需要根将这项既有校验纳入其原意图首次执行路径，或提供只读 intent 状态让 C 仅对首次执行校验。六参数签名没有 r5/config 参数，本轮不自行决定“所有 Photo 都加 OSS 校验”或删除既有 r5 校验；这个位置需根明确，不能悄悄弱化校验或把 HTTP 留在锁内。

## 文件、测试与集成注意

C 生产文件：AdvancedGameRuntimeServiceImpl、PlayAttemptLogMapper.java/XML（增加终态 current read）、ApiAdvancedPlayController 仅更新提交边界注释。IAdvancedGameRuntimeService/HTTP 参数无需新增。根拥有 PhotoCheckVisionService、原意图 service/mapper/schema/quota 关联及既有独占夹具，C 不覆盖。

新专属测试覆盖：每个阶段实际事务标记；provider 调用时无事务；入站已有事务拒绝；并发 UNKNOWN 不提交 fallback；DECIDED degraded 保留原 fallback；模型成功后 CAS/logAction 失败本地全回滚而根结果保留；原 expected/authority 不被刷新；终态旧 epoch 读回；根结果保存失败不重调；r5 HEAD 无锁且已有结果不重复依赖 HEAD。Java/MySQL 仍只在获准环境运行。

TransactionTemplate 的引入会要求直接 new service 的单测显式安装事务夹具（搜索到 27 个测试文件含此构造）；不会以无 manager 时直接执行 callback 的测试 fallback 绕过生产事务。C 提供专属测试夹具；根独占 Photo 测试文件由根按该夹具接入，C 不混写。

根集成的 ApiClubLeadController.teamProgress 局部变量重命名必须保留；不覆盖、不重写第一批 9a90123。CI run 40015275768 的已知结果为 compile failed / 0 Java 用例，不记作 MySQL 断言失败。
