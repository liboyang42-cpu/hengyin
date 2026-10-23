# E Photo 冻结候选独立静态回读

结论：493 冻结未改。E 的 receipt type/key/hash 条件与 C 真实 Mapper 对齐；发现 1 项可由代码顺序推导的 RR 重放缺口，以及 1 项待 C Photo 编排确认的跨 session/global-key 集成阻塞。没有为配合夹具放宽校验；没有运行 Java/MySQL。

## 被审身份与证据

- E 公开候选 `493c7dead4a88ece75d3163c1726a175ce862020`，tree `59b1312bbcdb8af9d99571bd4c8c985e1683709c`。隔离本地等树提交 `359223ac2bfb5641d6bf8599e31de7e788fca4c8` 工作树干净，公开 ref 回读仍指向 493。
- C 指纹提交 `83a0479d2d28987e2ec757659c9ed7655ba8c742`：真实 PlayAttemptLog.java、PlayAttemptLogMapper.xml、migration_20261023_advanced_command_fingerprint.sql 已通过公开 API 回读。
- 编排核对使用公开 C `3e25bddf554651f8956ff209d128127635a9b1c8` 的 AdvancedGameRuntimeServiceImpl.java；这是明确源码快照，不冒称覆盖 C 尚未发布的 Photo 分阶段编排。
- C 的 schema 原 key 定义来自公共基线 migration_20260820_new_city_preference_chain.sql：VARCHAR(128) 与全局 uk_play_attempt_idempotency(idempotency_key)。C 83a 迁移明确保留该全局唯一键，并增加 nullable CHAR(64) ascii_bin request_fingerprint；历史 NULL 不回填。

## 已对齐项

1. C 的 ATTEMPT_TYPE_ADVANCED=8；logAction 将 type=8、原 scoped key、原小写 64hex fingerprint 写到 PlayAttemptLog，真实 Mapper INSERT 包含 request_fingerprint，useGeneratedKeys=true/keyProperty=id。E countMatchingReceipt 同时检查 receiptId/member/key/hash/type=8，未把普通 photo type=4 混入高级回执。
2. C 的 theme key 为 theme:<sessionId>:<SHA256前16字节hex>，最长 58 字符；raw client key 现有入口上限 64，均可进入既有 VARCHAR(128) 和 E VARCHAR(128)。E 不做第二次 hash，不改变 C 原 fingerprint。
3. C 83a/3e 的 replay 在版本资格分支前，比较 member/type、JSON 原 session、非 NULL fingerprint。E prepare 同 key/hash 返回原行、异 hash 拒绝；executeOnce 对 IN_FLIGHT/UNKNOWN/RESULT_READY/APPLIED 不重新抢占；结果字段 factory 还原，不重放 provider。
4. E markApplied 为 MANDATORY，并检查原 RESULT_READY 和原 receiptId；同一事务的 C effects/log/APPLIED 应共同提交，E 已独立提交的 RESULT_READY 不受最终事务回滚影响。具体 C 新编排和真实日志 ID 回传仍待联合验证。

## P2：锁定读后普通 receipt SELECT 会误拒绝旧 RR 快照中的合法重复应用

位置：PhotoCheckIntentService.java:128-130，PhotoCheckIntentMapper.xml:78-82。

可复现顺序：

1. 原 intent 已 RESULT_READY、终态 receipt 尚不存在。
2. T_reader 开启 MySQL REPEATABLE READ，先普通 SELECT 建立一个不含该 receipt 的 read view；不持有 intent 写锁。
3. T_writer 在另一事务插入合法 receipt、markApplied 并提交。
4. T_reader 调用 markApplied(原 intentId, 原 hash, 同 receiptId)。selectForUpdate 是 current read，看到 APPLIED/原 receiptId；紧随其后的 countMatchingReceipt 是普通 SELECT，仍使用第 2 步的旧 read view，因此看不到 receipt，返回 0。第 129 行先抛冲突，第 130 行的合法重复分支无法到达。

后果是合法同回执重复被误拒绝，保持 fail-closed，没有证据表明产生了重复权益或额外 provider。现有第 7 个真实 MySQL 用例在新事务中重复应用，不覆盖旧 read view；本项是静态事务语义推导，未冒称已实跑红测。

后续最小测试应严格保留上述两个真实事务和 read view，再验证同 ID 重复成功、错误 ID/owner/hash/type/key 全部继续拒绝。修复应让回执验证也使用真实 current locking read（例如选择具体 receipt 行），保留完整校验；具体锁顺序需和 C 的 session→receipt→intent 顺序联合验证，不通过删校验或吞异常修绿。493 本轮不改。

## P1 条件性集成阻塞：E command scope 与 C 全局 receipt key 的并发 admission 尚未统一

位置：E migration_photo_check_intent_20261023.sql 的 uk_photo_command(member_id,session_id,action_key)；C 3e act:382-383 的条件 themeActionKey 分支；原 uk_play_attempt_idempotency(idempotency_key) 全局唯一。

E 严格执行协调者冻结合同的 (member,session,scoped actionKey) 唯一，不能自行改成其他业务身份。C 当前只有 zeroChallenge/themeScoped 路径将 key 绑定 session，其他路径保留 raw key。对 C 尚未发布的 Photo 编排必须确认它是否确保每个 Photo actionKey 都已覆盖业务 scope。

若一个可达 Photo 路径保留 raw key，则两个不同 session（或不同 actor）的同 raw key 在没有旧终态 receipt 时，可各自通过其 E 唯一身份 prepare、各自 CAS 并执行 provider；第一个终态提交后，另一个才因 C 全局 receipt 唯一键冲突回滚。当前 prepare 短事务即便读锁不存在的 receipt，也会在调用 provider 前提交并释放锁，不能代替持久 admission。每个 intent 内的 once 测试不能证明跨这两个 scope 也 only-once。

需要 C/root 明确是否始终提供真正 scoped key，或在全局 receipt 身份上进行一致的持久 admission。不同成员/会话使用同 raw key 到底是两条独立命令还是冲突命令，属于共享身份合同；E 不猜规则、不加额外全局唯一键、不改 C 算法。应新增可达 Photo 路径的跨 session/actor 并发真实编排测试：若判定冲突，败者必须在 provider/额度前拒绝；若判定独立，两条合法结果都必须能提交原终态。

## 其他必须保留的边界

- C 3e 的 act 仍带外层 @Transactional，尚无 E 四阶段调用，直接嵌入 executeOnce 必须被 NEVER/显式 guard 拒绝。不能凭根 adapter CI 成功称 Photo 闭环已验。
- C 当前 logAction 是 private void；新编排需取得真实 insert 生成的 PlayAttemptLog.id（或原 key 精确回读），不能把 sessionId/intentId 当 receiptId。缺失/错 ID 被 E 拒绝属于有效保护，不是要为 fixture 放松的条件。
- read/prepare 的 owner/hash 不代替 C 原 session/epoch/route/game session 资格授权；当前方法只保证原身份与结果，不新增授权语义。
- 结果写库失败会抛错并保留 IN_FLIGHT，不会新 nonce 或重发；C 不得将其表述为确定未执行。这个失败没有声称已保存原结果。
- 根告知 bb4 独立 CI 66 用例成功，以及 IM 765/run40020471166 的 4+7 MySQL PASS/CLEAN；这些是协调者报告的运行证据。E 本轮只执行源码搜索、公开 API 回读、状态/树身份核对，无 Java/MySQL。IM 群/route 仍 mock，不扩展为全真实权限验收。

## 联合候选 361 的独立回读

根联合候选为 `361ef3b23a94c6be2f73f74134a40d827f2ee3da`（codex/architecture-day-photo-intent-ci-20261023）。从该精确 SHA 读取 IntentService、IntentMapper XML、Photo migration，与冻结 E 源码逐字相同，SHA256 分别为：

- service：dc40a6f5f7ee4438d63f161ced55339bdaca6af6a9dcd88a20572b0350de0bca
- XML：e2b2bb181415e0083037bb5d7aea41793fe51176478a3f559726d302905b63d5
- migration：8671f805d52071ce1067838b21581ff53d278be34b8fd4a744b4b14183cbad6e

361 中真实 PlayAttemptLogMapper 再次确认 generated ID、原 key、request_fingerprint 写入与读取。AdvancedGameRuntimeServiceImpl 在该联合 SHA 中仍是外层事务、条件 scoped key、private void logAction，无 executeOnce/markApplied 新编排。因此上面两个问题均未被这次联合依赖集成本身消除；scope 项仍属于后续 C 可达路径合同核对，RR 项需独立真实红测。

## 挂起与恢复边界

| 持久状态或窗口 | 当前可做的恢复 | 不可据此推断/执行 |
| --- | --- | --- |
| PREPARED 已提交、尚未 claim | 原 owner/hash 可继续第一次 CAS，沿用原 canonical 输入/日/nonce/配额计划；Vision 仍重新校验 consent/media | 不换新 key/nonce/当前日，不将已撤回 consent 降级通过 |
| claim 已提交、快照/HTTP 前崩溃 | 保留 IN_FLIGHT，read 返回原挂起状态；原身份仍在 DB | 没有快照也不能证明未调用，不因时间/重启重发 |
| 快照已保存、无结果 | 通过 intent、原 quota 凭证/nonce 和原快照做受控查证 | exact data URL hash 不证明 provider 未执行，也不是 provider 幂等键；不能自动补发 |
| review 抛异常或安全拒绝 | 原异常传播，尝试独立写 UNKNOWN；读只见原挂起身份，不合成结果 | 即使实际是发送前拒绝，当前服务也没有自动重新领号/执行恢复路径 |
| 首次真实 review 正常返回 degraded | 本地 RESULT_READY 与 providerState(NOT_SENT/UNKNOWN/RECEIVED) 分列，可重放原 degraded 结果，由 C 现有配置规则应用 | providerState=UNKNOWN 不等于 intent.state=UNKNOWN；并发等待者不能生成替代结果 |
| result 独立保存后 HTTP 回包丢失 | read/executeOnce 返回保存结果，不再调用 provider | 不重新编码/调用/领号 |
| result 已保存，玩法 CAS/log/APPLIED 事务失败 | 原 RESULT_READY 保留，C 用原命令资格重做最终短事务 | 不自动换 version/epoch/session/key 重新做 provider，不发放旧 epoch 权益 |
| result 写库失败 | 抛错并保留 IN_FLIGHT，原 nonce 不释放；需查证 | 不能声称原结果已持久化，也不能告诉客户端确定未执行 |
| APPLIED | 只读原结果和 attemptLogId；同 receipt 重复受 RR 缺口影响，须补测修正 | 不在新事务重发 effects、换 receiptId 或把响应 TTL 当业务身份释放 |

当前实现没有 Photo pending 扫描任务、provider 查询 API 或自动解除 UNKNOWN 的机制；这一可用性边界明确保留。quota recovery 仅恢复原配额证据，不是重发照片审核的授权。数据库 intent 和原 nonce 能保留查证身份，但不能把它描述为所有挂起都会自动完成。

`markApplied` 的零影响拒绝依赖 C 把 effects、PlayAttemptLog 和该调用放在同一个实际可写事务，并让异常回滚整个事务。它不是对方结果的查单接口，不验证新的 gameplay 资格，也不代替 C 的原 session/owner/epoch 复核。C replay 还检查 resultJson 中 sessionId/retrySourceSessionId；E 的 receipt JOIN 不解析这份业务 JSON，而是依赖由 C 原指纹绑定的 session identity，因此必须继续验证 C 的真实日志生成调用链，不能用 E 最小表 fixture 代替完整玩法验收。

根最新报告：联合候选 gitleaks0、realstorage 44 个自测与 15 条注册 plan 通过，Photo10+Fingerprint5 已 dispatch；未提供本轮这 15 个用例的最终结果。16 Intent unit、11 fingerprint unit、Spring8 未运行，本报告不将其计入通过，也不将联合依赖等同 C Photo 最终编排验收。编译/运行结论仅以联合依赖实际 CI 为准，不把 parent bb4 单独叙述为本 Intent 的已验证编译环境。

本轮未编辑已冻结源码/分支/ref、未新增 Agent、未改共享 CI/Mapper/入口、未运行 DDL、未合并部署。
