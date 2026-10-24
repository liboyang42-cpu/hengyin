# H：联合 AI 4197 关键链只读审查

结论：本轮未发现可证的新 P1/P2。下面是源码结论与必须取得的运行证据，不是上线签字。唯一生产源 `4197f6a6b11d62cb0a742aae73d7167c93114585`，tree `2808646e339058941f289f47d7d7549cd4bbe8d7`，parent `1e7a3332df68e0ad583543168528faac9d46379c`。root 告知 run40058848452 在运行；H 未执行或核验该运行，不预先声称 22 项通过。

H 开工 HEAD `e5d356447a3039e1755249a12af66f766ae8b12a`，独立目录 `/workspace/hengyin-h-review`、分支 `codex/architecture-day-h-review`，开工干净。仅新增本 H 目录，不修改 A/E/root/F 或夹具，不运行 Java/DB/CI/provider，不派任务。

以下文件简称 Request=`AiThemeDraftRequestService.java`、Creator=`AiCreatorServiceImpl.java`、Gateway=`AiGatewayServiceImpl.java`，均在 `chengyinhub-system/src/main/java/com/chengyinhub/business/service/` 的对应 impl/ai/impl 目录。所有位置对应本候选。

| 关键链 | 源码证据与结论 |
| --- | --- |
| prepare 回包丢失按原 key 恢复 | Request:214–268 先提交 owner/key/原计划，再保存正文；356–364 的 readPrepared 不受 admission 开关限制，按原 owner/key 返回原 requestId，缺失返回 PREPARE_UNCONFIRMED/UNKNOWN。相同 key 改 payload 在422–427拒绝，绝不重排原计划。INSERT 后 commit 回执异常会中断后续正文写入；永久身份仍能读回，但正文可能不存在，不据此保证可以继续执行或换号。这是需验证的故障窗口，不是新发现的重复派发。 |
| claim 提交不明不重发 | Request:293–303 只有 REQUIRES_NEW 模板明确返回 claim=true 后才调用 Creator；commit 异常由620–631转 STATE_UNAVAILABLE，调用不会越过该边界。Mapper:52–56 仅 PREPARED、空 claimToken 可抢占；没有租约过期后改回 PREPARED 或再调用 provider 的代码。PROCESSING 过期只在445–452投影 UNKNOWN。 |
| 原 quota/nonce 与 late 结果 | Creator:142–161 校验原计划；Gateway:138–189 不重选 pool/nonce。Gateway:354–389 的真正 chat 异常转 typed UNKNOWN；Creator:218–234 跳过该 UNKNOWN 的退款/retain，原 MODEL_STARTED 留给恢复 worker。Gateway 的 gen retain 失败在577–584转 RECEIVED；Creator 有效稿 accepted=true 后 retain 的任意 RuntimeException 在235–241转 RECEIVED，不走退款。Request:317–320 将两类分别记 UNKNOWN/UNKNOWN、FAILED/RECEIVED；没有把 RECEIVED 冒充 NOT_SENT。 |
| 不能扩大退款政策 | Creator:170–247 保留无效 JSON/稿件、OUTPUT_BLOCKED 的原玩家退款，以及 INPUT_BLOCKED 的原 retain 政策；对应 PreparedThemeDraftTest 有明确 received/parse/blocked 断言。它们不等同于有效稿 retain 失败。不得为修 UNKNOWN 把所有无效稿改扣费。generic/wrapped/message 中的 UNKNOWN 不被挖 cause 当作真实 typed outcome；late plan marker 在原额度已产生影响后会失去零效果证明。 |
| 永久 metadata 与24h正文 | Request:87–100 使用 Redis TIME/绝对期限、单键脚本，不因重放续期；497–524 校验 owner/id/hash/期限/正文 hash。成功正文留存失败仍记录 SUCCEEDED 的 resultHash/deadline（329–338）；445–474 的过期或缺正文仍返回已成功事实，不退回不存在。purge:395–419 只删确切 body key、写 purged 时间，不删身份/nonce/UNKNOWN；Mapper 无 DELETE。 |
| owner recovery 与原 productType | Request:367–392 一次 mapper 查询、固定21条、owner/单调 id/状态/原 productType 校验；Mapper:59–64 使用 owner+id 范围索引、DESC LIMIT21，不碰 Redis/body。ApiAiController:234–260 只返回20条及字符串 cursor/nextCursor，避免 JS BIGINT 精度损失。null productType 保留未知，不转换成1；原 UUID 仅走只读协议，缺失读成 LEGACY_UNBOUND/UNKNOWN，不能执行。新原上下文中的 null 与1有不同 hash，解码对照原 metadata。 |
| admission、maintenance 默认关闭 | Request:78、Api 配置 application.yml:309–311 都默认 admission=false；AiQuotaRecoveryTask:13 默认 maintenance=false。关闭受理仍可原 owner 读取。Task.run 先原 quota recovery，再条件 purge(100)，不吞该调用抛出的异常或返回假成功。已有 quota worker 的逐行失败记录/继续处理是原批处理合同，不当成整批全部成功。 |

读边界：metadata 通过短 REQUIRES_NEW 事务读取，相关 Mapper SELECT 同时 useCache=false/flushCache=true；provider 在事务外。完成 CAS 带 owner/hash/plan/nonce/claim，完成回执异常不释放原 claim；同 id 重试只读，不把失败日志当成功事实。审查没有发现新增删除身份或用新 nonce 恢复的路径。

必须由 root 按同一源、原 XML/清理回执裁决：

1. 已有真存储 IT 的并发 prepare/claim、claim commit ack 丢失（零 quota/review/model）、outcome commit ack 丢失、typed UNKNOWN 原预占、NOT_SENT 原凭证补偿、成功正文丢失/过期、UNKNOWN 身份 purge 后仍在，以及 owner 恢复分页/EXPLAIN/DB 故障。源码中有相应断言不等于它们已运行。
2. 额外明确窗口：用原事务 manager 包装器在 **insertPrepared 的真实 commit 后**抛异常，按原 key GET 找到唯一 requestId/原 nonce；正文若未写，重试不能生成新身份、不能调用 provider。现有 IT 的 body 写失败与 claim/outcome ack 丢失并不能替代这一 prepare commit ack 场景。最小测试无需改生产或猜 B1 新政策。
3. Gateway/Creator 组合验证 UNKNOWN、有效稿 retain 失败（包括 late NOT_SENT/plan marker/generic）不退款、不改原身份；既有 invalid/OUTPUT_BLOCKED/INPUT_BLOCKED 断言保持。当前 unit 有这类边界声明，provider/安全审核仍为 doubles，不是真供应商证据。
4. 实际 Spring/API 序列化验证原 productType=null、owner隔离、BIGINT 字符串，以及默认两个 false 和维护异常透出；Controller 直接调用测试不能当真实 HTTP/JWT。

H 实际执行仅 Python：31份读取文件 Git blob 身份校验、Mapper XML解析、recovery 单 SELECT/固定21/owner条件及缓存 flags、无 DELETE 检查（PASS）。Java、数据库、Redis脚本、CI、HTTP、供应商均 NOT_RUN；没有新增实现、DDL、依赖或业务政策。root 正在另行收口的人类玩家单池展示裁决不在本旧源重复报漏洞；本报告也不抵消 B/资金等其他未闭事项。
