# H：C 带领准入 / E 可信 NOT_SENT 独立复审

结论：在本次限定源码及已核验运行证据中，未发现新增可证 P1/P2。C 的依赖读取异常不再降为合法 nonlead，偏好 GET/POST 在可能建局的路线准入前检查带领状态；E 仅将当前模型调用直接抛出的可信 NOT_SENT 标记用于补偿当前原凭证，UNKNOWN 和收到答案后的异常不走该补偿。此结论不等于全系统或部署验收，既有资金共同 fence、B 旧 attempt、创作请求身份问题不因此关闭。

## 身份及独占范围

- 公共参考基线：`2ec68899099a1e250df0949a8383bf85b5d3ffe0`。
- 本轮开工 H 本地 HEAD：`1320c972d21cf70cd683f77a7e3e485aa521a90d`；工作目录 `/workspace/hengyin-h-review`，分支 `codex/architecture-day-h-review`，开工 clean。
- 仅新增本目录报告和脱敏证据索引；没有编辑 C/E/F/G 实现、已有测试、DDL、CI；未新增回归补丁，因为本轮未找到可证新问题。
- C 候选：`76b107c090134d540ef7fe0efcc8b891e37b0df7`，相对 `26f3764ce0d81594be02c0eeace04aacdd483e58` 的两个提交（中间 `dfe083a89cd5538b7df77c6d407da3908e9a1cc8`）。
- E 候选：`e359fa5526a32d670012ff1d2c22e8885cdafc84`，parent 为上述 26f。首字母 e 是 SHA 的一部分。
- 联合源码：`6ecaea01e0e1a3f3182189615bc00cf9f791fd47`，tree `92c091832648ef379fe8e1af2c8b250e20acb8e7`。它相对 parent `9a2dcf21aef4d7aec7a14065a2601bd86aaad805` 只改真库登记 JSON。
- C/E 各 8 个候选交付文件与联合源码逐 blob 相等；E 真库源码 a0fbc 的相关恢复、意图、传输及测试依赖另逐 blob 比对一致。详见 SOURCE.json。相同 blob 证明相关源码一致，不把不同 source 的运行改称同一次运行。

## C：真实调用和拒绝边界

位置均绑定联合源码：

1. `ApiPlayProgressController.leadGateError:3034–3060`：自玩 actId<=0 仍先返回；runtime、ClubLead、章节读取都在 try 内。ServiceException 原对象先行重抛，保留旧 code/data（含 403）；其余 RuntimeException 变为稳定 503 / data.outcome=NOT_RUN。成功查无带领记录、已结算及原章节规则保持原合同，没有以读取失败代替 nonlead。
2. `GameSessionRuntimeServiceImpl.routeSessionStatus:2489` 直接调用 mapper，无内部 catch 吞掉存储故障。这里不建局。
3. 搜索全部 helper 调用：checkin:1585、answer:1716、reveal:1832、hint:1891、preference helper:2011（GET/POST 共用）、sensor:2138、arrive:2232、photo filter:2425、普通 photo:2465、unlockHint:3813。相应 IllegalArgumentException / IllegalStateException catch 不接住 ServiceException；FREE_COLLECT photo filter 的局部 IllegalStateException catch 亦然。ServiceException 直接继承 RuntimeException，非 IllegalStateException。
4. `executeBasicIntent:1429–1472`：原终态回执优先；聚合锁后重读；PENDING claim 后直接 execute.get()，没有将 503 转成正常 AjaxResult 或 FAILED 终态。异常越过 RC/rollbackFor=Exception 外层事务，已有 claim 回滚。DeadlockRetry 只捕获两种锁异常，不重试这个 ServiceException。全局异常处理器在事务返回后保留 code/data。
5. `resolvePreferencePlayCtx:2000–2037`：节点/当前报名归属检查 → 带领检查 → unlock/routeAccess（可能建 branch/journey session）→ 模板/偏好业务。两个偏好 HTTP 方法在业务 try/catch 外调用此 helper，建局前的异常不能变成偏好成功。没有声称这两个 Controller 自带外层事务；恰因后续 canPlay 能独立提交，前移检查是必要根因修复。
6. 普通 checkin/arrive 的既有 FREE_COLLECT 到店证明分支不等于推进带领玩法，本补丁没有统一改写其政策；本报告不声称所有旧分支、所有 route 权限都已全面验收。合法 nonlead、自玩、旧 403 的相关单元测试实际执行，仍非 HTTP/JWT 证明。

C 真库 4 例使用独立 scratch schema、真实 mapper/事务代理；故障通过重命名自己测试库的 lead 表制造。两种偏好路线分别证明两个入口故障时不调用建局、不生成 session/actions，恢复后原路线可建局；answer 例确认真实 claim INSERT 已发生一次且整笔回滚、无终态回执；hint 例检查拒绝零经济变化，恢复 nonlead 可真实扣分。业务响应部分仍用 fixture，不能扩写为全 Spring HTTP、所有并发玩法或实际用户链路通过。

## E：标记必须属于当次 chat

1. `AiGatewayServiceImpl.generate:196–225`：stage 写失败属于未进入 chat 的旧补偿；专用 catch 仅包围 client.chat 及空结果检查。直接 final/no-arg/no-cause AiProviderNotSentException 才退本次原 reservation；普通超时、取消、429、文本 NOT_SENT、wrapped marker 不解包、不退、不重发，变 typed UNKNOWN。player_theme_draft 的独立池仍由 Creator 拥有，网关不重复预占或补偿。
2. `generate:235–267 / retainQuotaAfterResult:411`：输出审核及结果留存处在上述 chat catch 之外。其后即使抛同类 marker，也不能成为未发送证明；留存异常转换 ResultReceived，既有有效/无效稿政策不在本次修改中扩大。
3. `PhotoCheckVisionService.reviewOnce:98–196`：编码/配置/原输入快照/stage 未发送失败保留原补偿；直接 chatVision marker 补偿当前 photo 原凭证并返回 degraded、NOT_SENT/PROVIDER_NOT_SENT。generic/wrapped 异常为 UNKNOWN。retainQuota(PHOTO_RESULT) 独立 try，答案后的 marker 为 UNKNOWN、不退。降级结果仍保持原业务 fallback，未改成 pass。
4. `chargeQuota:208` 的 planned token 必须原样相等；`AiGatewayServiceImpl.refundQuota:386 / cancelQuota:439` 按原 member/pool/day/nonce 重建原 receipt，不按今日 key 或当前 generation 猜补。存在 recovery 时，先独立提交 REFUND_DUE；其 CAS/基础设施失败不能绕过为直接 Redis 退款。
5. `AiQuotaRecoveryService:74–132` 与 mapper recordOutcome/claim/complete：过期请求 lease 不授权新退款决定；worker 的 owner/fence/claimUntil 仍校验。MODEL_STARTED 无明确 REFUND_DUE 进入 UNCERTAIN，不退款或供应商重投。Redis 补偿复用原 generation/receipt，并保留幂等 tombstone。
6. `PhotoCheckIntentService.executeOnce:80–120` 只 PREPARED→IN_FLIGHT 可获得一次执行；结果可存 RESULT_READY/provider_state=NOT_SENT，保留原 nonce/hash/day/reservation；读回或重试原意图不生成新号、不重调模型。旧 UNKNOWN 不因新的标记重启。fresh/current mapper 的 flushCache/FOR UPDATE 保持，不以 mock 当持久化证明。
7. `NpcChatService:355–366,455–486`：可信未发映射 FAILED/NOT_RUN；typed UNKNOWN/ResultReceived 映射 PROCESSING/UNKNOWN；原 requestId 的重放/读取不重调。2 例单测真实组合 NpcChat 与 Gateway，存储仍是测试 doubles，不能称 NPC 真库验收。

## 实际执行证据矩阵

H 没有在云 VM 执行 Java/数据库/供应商或派 CI。以下为 H 独立下载 GitHub 原 artifact、解析原 XML、核对 case 名/失败节点/source/run/attempt/hash 的结果；RUNTIME.json 保留索引，未提交原 XML、凭据或签名下载地址。

| 运行源码 / run | 范围 | 实际裁决 | 限制 |
|---|---|---|---|
| 6ecaea / 40044336447 attempt1 | C ClubLeadGate 10 + AdmissionFailure 16 + CurrentUseBoundary 6 | 32 PASS，0 failure/error/skip | 单元 fixture，不是 HTTP/RBAC |
| 同一 6ecaea / 同一 run | E Gateway 4 + Photo 5 + NPC 2 | 11 PASS，0 failure/error/skip | 含 wrapped/普通异常及答案后 marker 负例；mock 边界如上 |
| 同一 6ecaea / 同一 run | 已审 F 相关 49（包括 H timeout+cleanup+真实 worker 组合 1 例） | 49 PASS，0 failure/error/skip | 此处补运行证据，不重做 F 源码审查、不证明真实供应商 |
| 9a2dcf / 40044166369 attempt1 | C 带领故障真 MySQL 4 | 4 PASS，0 failure/error/skip；CLEAN | 联合 6ec 的 registry-only parent |
| 同一 9a2dcf / 同一 run | 原 hint 真 MySQL 10 | 10 PASS，0 failure/error/skip；CLEAN | 原经济/资格断言保留 |
| a0fbc4 / 40043910446 attempt1 | E quota 真 MySQL+Redis 4 | 4 PASS，0 failure/error/skip；CLEAN，Redis DBSIZE=0 | 与 6ec 对应依赖 blob 相等；不同运行 source |

联合源选取的 14 份 XML 共 92 条，每份 SHA256 都与 artifact 内 backend-test-identity.json 的 reports_sha256 项一致；source/run/attempt 同时一致。没有把后两种源码的 18 条真库结果拼成“6ec 同源 110 PASS”，也未据局部 XML 宣称整个 full workflow 终态通过。

E 真库四条实际覆盖：REFUND_DUE 后受控 Redis serializer 故障→新 worker 原凭证补偿一次；generic 故障保留 MODEL_STARTED→UNCERTAIN；过期补偿决策 CAS 不绕过 Redis；跨北京日期原 Photo PREPARED fixture 的原 nonce 结果留存、读回、再次 execute 不再 provider。这里的 Redis 故障是受控注入，非真实实例宕机；Photo 的 prior-day PREPARED 是恢复夹具，不代表全 C admission/HTTP/设备流程已串行通过。

## 未验证、迁移和回退

- 本轮未测 HTTP/JWT/实际方法权限、设备/页面全链、真实供应商、生产定时 worker、进程 kill、全部 route 并发授权及所有经济路径；不能以本轮局部通过抵消既有未关闭 P1。
- 当前 C/E 这组变更没有新增 schema/依赖/配置/接口；本轮没有执行 DDL。需要现有 quota recovery / Photo intent 迁移和实际 Spring bean，真库测试的显式组装不等于生产装配核验。
- 回退 C 会重新引入读取故障放行/偏好提前建局；单独回退 E 会使可信未发送也保守保留额度。不得删除已有 quota receipt/intent/审计，不能通过回滚代码重发 UNKNOWN。
- G34 的最新结果由 root 核验：97b823e5c8bb86675825476a07c9cdf671063f2a /40044883897 为旧30 PASS+H4 FAILURE、0 error/skip、CLEAN。四个触发点与 H 既有报告相符；本轮未重复下载该组 XML，不冒称 H 已独立二次核验。证据分支不合并/部署，仍待 G 授权修复新源。
