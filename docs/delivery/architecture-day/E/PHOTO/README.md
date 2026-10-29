# E Photo intent 持久化候选

结论：本批实现具体 `@Service PhotoCheckIntentService`、原命令存储与单次执行状态机；新增 16 个单元用例和 10 个真实 MySQL 用例。仅运行静态检查，Java、MySQL、Redis、供应商与 C 端玩法闭环均未运行，不能据此合并或部署。

## 基线与独占范围

- 公共基线：`2ec68899099a1e250df0949a8383bf85b5d3ffe0`。
- 根适配依赖及本批 parent：`bb4a1852018238b9280602714f4358863d7da9e2`；依赖 tree `d45f445b00cf7d0e7dde043faa61a5001365e83d`。通过公开 Git API 取 14 个 blob，逐个校验 Git SHA，重建 tree/commit 与公开 SHA 严格匹配后导入隔离分支。
- 工作树 `/workspace/hengyin-e-photo`；分支 `codex/architecture-day-e-photo-intent`。不含缓存/IM 候选的改变。
- 本批新增 7 个代码/SQL/测试文件，清单及 SHA256 见 `source-manifest.json`。没有修改 Gateway/Vision、AdvancedRuntime、Controller、PlayAttemptLog 或共享 CI/pom。DTO/enum 直接继承根适配，无重复定义。
- source-only tree：`c1bc76bfc3f42b52896658d5db2fe6a15ad2d65f`；完整候选包括本目录文档，最终完整 tree/commit 单独回传。

## 失败机制、调用链及合同

原调用链为 `ApiAdvancedPlayController.action → AdvancedGameRuntimeServiceImpl.act → submitPhotoCheck → PhotoCheckVisionService.review`，外层事务里执行供应商请求；随后 gameplay CAS/日志失败不能撤销已执行模型。根与 C 已分别拥有适配及编排，E 不改入口。C 合同来源为公开 `5236c0d` 的 `docs/delivery/architecture-day/C/photo-orchestration-contract.md`；以协调者后续冻结的精确 API 和允许原真实 review 的 degraded 结果落盘为准。

1. `prepare`：Spring MANDATORY，另有活动、可写事务检查。先按 member/session/scoped actionKey 查原命令；同 hash 返回原身份，不使用新参数重绑。首次准备先执行 consent、requireAttachment(publish=true)、processingUrl(IMAGE) 的 owned/APPROVED/type 检查。签名 URL 丢弃；完整 command JSON 的 imageReference 替换为 canonical `media-asset:id`，C 的原请求 fingerprint 保持不变。保存原上下文、原配额 token/day/32hex nonce 后提交；只调用根的纯计划函数，绝不消费配额。
2. `executeOnce`：Spring NEVER，加活动事务显式检查。独立短事务 CAS `PREPARED → IN_FLIGHT` 提交后，才事务外调用根 `reviewPrepared`，透传原 canonical 引用、requirement 和 reservation。CAS 败者只读原状态。`IN_FLIGHT/UNKNOWN` 永不因时间、重启或租约过期重发，也不新领 nonce。
3. 根的 encode 后、MODEL_STARTED/HTTP 前 callback：独立短事务保存 exact data URL 的 SHA256、实际模型/提示词/编码版本；CAS 要求原 fingerprint、IN_FLIGHT、快照尚未存在。失败抛出，根适配确保该路径不执行 HTTP。该摘要不是原文件摘要，不声称 canonical ID 或签名 URL 证明图像字节。
4. 首次实际 review 返回后，独立事务将明确 pass/confidence/reason/degraded 字段落为 `RESULT_READY`；providerState 与本地结果分列，允许原 degraded 结果供现行配置 fallback。并发者没有结果，不生成替代 degraded。恢复结果仅用静态 factory，不直接反序列化 PhotoCheckVisionResult。
5. 抛出的安全拒绝原样传播，不转成 fallback。抛出后原执行身份保守记 UNKNOWN；失败或进程崩溃也不会释放业务身份。结果提交失败保留 IN_FLIGHT，抛错且不重发；没有声称持久层不可用时可以凭空恢复未保存结果。
6. `read`：只查原 owner/id/hash；进一步比对 JSON 中 member/session/key/hash/canonical 引用与行身份。没有 nonce、配额、授权写操作或网络副作用。
7. `markApplied`：MANDATORY，与 C 既有 gameplay/PlayAttemptLog 同一事务。锁原 intent，核对真实 receiptId 对应日志的 member/scoped key/fingerprint/attemptType=8，再从原 RESULT_READY CAS 为 APPLIED；同 receipt 可重复，其他 receipt 拒绝。无第二份 gameplay responseJson。任何异常使外层业务事务回滚，独立存好的结果保留。

唯一身份不设 TTL、不按响应过期删除。没有新缓存、后台补发器、租约接管或 quota source column；根的 consumePlannedQuota/AiQuotaRecovery 继续使用同一原 nonce，原 recovery 的全局 nonce 唯一键保留。

## 实际检查及未运行

实际执行：

- `python docs/delivery/architecture-day/E/PHOTO/check_static.py`：PASS，7 文件精确 scope/SHA256/source tree；XML 11 个语句与 Mapper 方法名对应、resultMap 属性存在；用例声明数 16+10；有限敏感模式扫描 PASS。
- `python ci/secret-file-gate.py`：`SECRET-FILE-GATE PASS: 当前 Git tree 无密钥/证书文件或私钥正文`。
- `git diff --cached --check`：exit 0。

以上不编译 Java、不执行 SQL。本环境没有 gitleaks；之前已知固定 v8.24.3 下载路径被 proxy:8080 阻断，本批没有改代理、安装依赖或申请凭据。有限模式扫描不能代替 gitleaks，需根对候选差异复扫。本次仅按用户批准的隔离 CI 候选例外提交/发布。

根 CI 待执行：

- Java21 编译及 `PhotoCheckIntentServiceTest`（16），含安全零额度/零 provider、canonical 原上下文、重放及指纹拒绝、抢占败者、UNKNOWN 不重发、快照失败、原 degraded、回执匹配/CAS 异常。
- `PhotoCheckIntentMySqlIT`（10）：真实 MySQL 8+、生产 XML/Mapper、真实 Spring 事务代理；独立 JDBC 回读持久状态。含准备回滚、全局 nonce 唯一键、明确 REPEATABLE READ 并发 upsert、单执行者、无业务事务 HTTP 边界、独立快照、终态事务 rollback 后原结果、receipt 属性匹配、重启/旧时间 IN_FLIGHT 不重发、读 owner/hash/损坏 session 拒绝。
- 根 CI 显式点名 `PhotoCheckIntentMySqlIT`，必须给 `CHENGYIN_PHOTO_MYSQL_IT_PORT/USER/PASSWORD`。没有新增条件 annotation 或 skip gate，显式执行而缺少配置必须失败；普通 Surefire 默认命名规则不自动执行 `*IT`。使用受控 localhost MySQL 的随机 scratch schema，仅清理自建库。
- Provider/consent/media 在这些用例中受控 stub；没有把它们冒充真实授权表、Redis、HTTP 或完整 gameplay 链路。根适配测试、C 实际 PlayAttemptLog migration/编排/epoch/资格/CAS/权益零变化/真实 Redis 原 quota 链路须联合验收。

## 迁移与回退

唯一新 canonical 文件为 `chengyinhub-admin/sql/migration_photo_check_intent_20261023.sql` 第一层，未改旧 migration、根 sql 或 docs sql。仅候选，未执行 DDL。新增 photo_check_intent 表；member/session/key、nonce、attempt_log_id 分别唯一；完整上下文和原结果不随业务回包过期清除。

预检需确认 MySQL8+、目标表尚不存在或先人工比对漂移、C 的 play_attempt_log.request_fingerprint 迁移和既有 scoped key 唯一约束；回读 SHOW CREATE TABLE 核对字段及 3 个唯一索引。未用 IF NOT EXISTS 掩盖现存不同表。CHECK 约束、DDL 与真实事务行为都待 MySQL 实测。

回退应先停用新入口，再回退协调过的应用代码，保留意图、原结果、quota recovery、回执及审计。不 DROP/TRUNCATE，不用旧代码自动重发 IN_FLIGHT/UNKNOWN，不重用 nonce/key。无历史小程序 fallback 或旧无 fingerprint 兼容路径。

仍阻塞：根受控 Java/MySQL/Redis/联合链路验证、完整 gitleaks 复扫、C 指纹迁移与调用编排。没有合并、部署、生产访问或真实模型/付款调用。
