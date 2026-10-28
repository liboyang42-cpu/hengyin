# H：prepare COMMIT 已成功、回执丢失的单例补丁

交付 test-only `review.patch`，供 root 应用到 `27471b00989e15f7641f4ee77c497cca2f9e53c1`。只在原 `AiThemeDraftIntentMySqlRedisIT` 末尾增加一个方法：`committedPrepareAckLossKeepsOriginalIdentityWithoutContextOrExternalEffects`。原22个方法、断言、夹具、生产 SQL/Lua、清理逻辑逐字保留；预期登记22→23，由 root 统一修改 registry 并运行。

目标文件：`chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/AiThemeDraftIntentMySqlRedisIT.java`。基线 Git blob `6a2a266b741c8a4342782d4e46a398ed894a16fc`；应用后 Git blob `1e21e7a8cad5eece7f01468046eadc53c579f2df`，SHA256 `e5a3361966d38f53cd11938234ed4ec4fd201ea44ad716ea8b5254b2b7222dec`。补丁 SHA256 `25a0edd96068b596dcf6e9e88cc051bb68bf4ea5ff81908242e0b77ce1314051`。

场景：测试边界委托真实 MyBatis mapper 完成 INSERT，随后在同一真实事务注册 `TransactionSynchronization.afterCommit`，只在 COMMIT 已成功后抛一次异常。不是 INSERT 后、尚未提交就抛错。原真实事务 manager、独占 schema、Redis 和既有 fixture 均复用；无新框架/依赖或生产恢复逻辑。

检查链：

1. prepare 向外返回 STATE_UNAVAILABLE，确认来自 afterCommit 的指定异常。用 `DriverManagerDataSource.getConnection()` 打开的独立、autocommit JDBC 连接先读到唯一 owner/key 行，核对原 RID、生成 id、mode=2、原计划 hash、pool/day/counter/nonce/receipt/reservation/config/deadline。
2. 原 key GET 找回同一 PREPARED/RID/mode，context/result 均不存在；重复 prepare 仍用同身份，不再规划或 INSERT。
3. 原 id execute 因原正文缺失变为 FAILED/CONTEXT_UNAVAILABLE/NOT_SENT，不返回草稿，不进入 Creator 执行、quota、input review 或 model。后续原 key GET/prepare 保留同身份；原 metadata19列不变，两池 counter/generation、原 receipt、context/result 都不存在。

H 实际仅执行：下载文件 Git blob 核对、补丁移除新增方法后还原原文件的逐字比较、普通 @Test 22→23 清点、`git apply --check`。尚未编译或执行 Java/MySQL/Redis/CI，不能把此补丁称为 PASS 或已复现缺陷。H 开工 HEAD `f90c54d30e865457beaa9707b478d584089a5ade`，独立工作树 `/workspace/hengyin-h-review`、分支 `codex/architecture-day-h-review`；仅本 H 目录新增交付文件。

root 验证清单：核对上述 base blob 后应用补丁；保持原22项不删/不改，registry只增至23；按同一最终 source 运行，逐 XML 读取新增 casename 与原22项、failure/error/skip、scratch/Redis CLEAN 及 artifact hash。此例是实际存储提交窗口测试设计，仍使用原 model/security doubles，不证明真实供应商或 HTTP/JWT。生产配置和部署授权不变。
