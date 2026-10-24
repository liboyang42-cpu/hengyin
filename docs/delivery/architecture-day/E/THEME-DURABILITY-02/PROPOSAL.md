# E / H-QB453-02：主题草稿原请求身份耐久性提案（未实现）

结论：现有配额恢复账本可复用，但没有可直接承载主题草稿原请求身份的持久记录。建议新增一张专属 intent 表，将 member/rid/payload 指纹与原配额计划在首次执行前独立提交；Redis 只缓存响应，过期、丢失、崩溃和租约到期均不释放业务身份。本文只供 root 决策，没有新增 SQL 文件、生产代码或运行 DDL。历史 rid 切换、跨 owner 计划接口和结果保留策略尚未裁决，不能宣称 02 已修复。

## 身份、范围与已核对源码

- 公共仓库 `liboyang42-cpu/hengyin`；parent `26f3764ce0d81594be02c0eeace04aacdd483e58`，tree `0ef0b436292cb36a81088285a69a4da0dab9a97b`。
- 独立目录 `/workspace/hengyin-e-theme-durability`；分支 `codex/architecture-day-e-theme-durability-02`，仅新增本文，不以 01 为 parent，不混入 01 代码。
- H 来源 `60fc464b01fcb4cca25a5f0b0fd932444bc113df` 的 `docs/delivery/architecture-day/H/theme-quota-b453/REVIEW.md`、`request-unknown.review.patch` 第二条失效负例。
- 01 独立候选 `74de8fa737204618c9765b6ad6c31628155c5814` 已将专用 provider UNKNOWN 保留为 UNKNOWN；没有延长 Redis TTL 或修复本项。

本次只读核对的 Java 表内路径相对 `chengyinhub-system/src/main/java/com/chengyinhub/`，XML 表内路径相对 `chengyinhub-system/src/main/resources/`；入口为 `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiAiController.java`：

| 对象 | 可复用事实 | 不能直接复用的原因 |
| --- | --- | --- |
| `business/service/impl/AiThemeDraftRequestService.java` | member/rid 的 Redis 占位、现有四种受理状态及回读 | TTL 24 小时；没有 payload hash、原 nonce、receipt 绑定；失去 Redis 后同 rid 可再次 claim |
| `business/service/ai/AiQuotaRecoveryService.java`、`mapper/business/AiQuotaReservationMapper.xml`、`business/domain/AiQuotaReservationRecord.java` | 原 member/pool/day/counter/nonce/receipt、代际补偿及恢复 fencing；nonce 与 receipt 唯一 | 无客户端 rid、payload 指纹或完整响应；不能从某个 member/day 的余额反推出一次请求身份 |
| `business/service/impl/NpcChatService.java`、`mapper/business/NpcChatRequestMapper.xml` | NPC 专属 user/request 唯一身份 | chat session 非空、active-session 唯一槽、safe_text 及 48 小时清理语义；伪造 session 或借用 chat 记录会混淆域和清理策略 |
| `mapper/business/GameSessionRuntimeMapper.xml` 的 `game_command_receipt` | 游戏活动命令指纹与回执 | 唯一键含 activity，actor perspective/action 有游戏语义；主题草稿没有该活动身份，不能用 0 或假活动绕过 |
| `business/service/ai/PhotoCheckIntentService.java`、`mapper/business/PhotoCheckIntentMapper.xml` | 照片原意图/原结果持久化方式可作实现参考 | 绑定 session/scoped action/media/照片配额；不是主题草稿表，不应混存 |
| `business/service/ai/impl/AiGatewayServiceImpl.java` 的 `saveLog` / `AiGenerationLog` | 审计摘要、trace | 事后 best-effort 写入，异常被捕获，正文被截短至摘要；没有 member/rid 唯一边界，player 路径 trace 也不能代替 Creator 的配额 nonce |

表约束来源：`chengyinhub-admin/sql/migration_ai_quota_reservation_recovery_20261013.sql`、`migration_npc_chat_request_20260722.sql`、`migration_20260823_game_session_runtime.sql`、`migration_photo_check_intent_20261023.sql`。没有为本提案修改这些既有迁移。

当前调用者搜索：带 rid 的 POST → request service → Creator；GET → request service.read；无 rid 的 POST 仍直调 Creator。小程序 `pages/publish/simple/index.js` POST 携带 rid，未知后 GET 原 rid；单测、审计清单也引用这些 URL。公开仓源码未发现第二个生产 `themeDraft` Java 调用入口，但不能由此证明没有外部 HTTP 调用者。无 rid 分支也是未来完整接入的阻塞项，不能保留它再宣称全链路耐久；关闭方式由 ApiAi owner 统一改，本文和 01 不改协议或 Page。

## 原配额身份与现有政策保持

player：Creator 自行生成 32hex nonce，counter 为 `ai:theme:draft:player:<member>:<北京时间 yyyyMMdd>`，receipt 为 `ai:player:theme:draft:reservation:<counter>:<nonce>`；`quotaRecovery.prepare` 在 Redis 预占之前独立落库。其他角色的 Gateway 使用 gen 池 `ai:gen:limit:gen:<member>:<day>` 与 `ai:gen:reservation:<counter>:<nonce>`。这些原身份在未来 intent 中逐项冻结，跨日回读不重新规划，不更换池、member、day 或 nonce。

当前 Creator 的 player reservation 在私有方法内部生成，调用参数不含 rid；仅新增请求表、事后日志 join 或线程局部变量均不能原子建立可信绑定。未来需要 root 拥有的 Creator 及 Gateway 接受一份已持久化的原计划，消费这份计划；明确的 Java 签名必须由 owner 审定，本文不猜接口，也不改 root Creator。

复用 `AiQuotaRecoveryService` 的原 prepare/reserved/stage/retain/refund 和 fencing，不新增 quota source 列，不重造台账。typed NOT_SENT、generic UNKNOWN、收到有效或无效结果的收费政策沿用已审政策；不由请求层根据 IOException/status/timeout/cancel、cause 或文字重判 provider 阶段。原配额补偿完成也不释放请求业务身份，不据此自动再次调用 Creator。

## 最小表方案（待批准后另交 canonical migration）

建议名称 `ai_theme_draft_request_intent`，InnoDB。候选迁移将位于 `chengyinhub-admin/sql/migration_ai_theme_draft_request_intent_20261024.sql` 第一层；当前未创建该文件。只增加此表，不改既有 quota/NPC/game/photo 表，不使用通用 ledger 平台。

| 字段 | 建议类型/约束与用途 |
| --- | --- |
| `id` | BIGINT 自增主键 |
| `member_id`, `request_id` | BIGINT NOT NULL；CHAR(36) ASCII binary NOT NULL；唯一 `(member_id, request_id)` |
| `request_hash` | CHAR(64) ASCII binary；新请求必填，冻结规范 payload SHA-256；仅历史无绑定隔离行可 NULL |
| `context_json` | JSON；新请求冻结原业务字段、受信角色与提示词/配置版本事实；不存凭据，正文保留期另定 |
| `role_snapshot`, `quota_pool`, `original_day` | VARCHAR(32)、VARCHAR(32)、CHAR(8)；新请求必填，来源为服务端计划 |
| `counter_key`, `quota_receipt_key`, `quota_nonce` | VARCHAR(191)、VARCHAR(255)、CHAR(32) ASCII binary；新请求必填；nonce 与 receipt 分别唯一，沿用现有格式与长度 |
| `request_state` | VARCHAR(16) NOT NULL；仅现有 PROCESSING/SUCCEEDED/FAILED/UNKNOWN；NOT_FOUND 不入表 |
| `claim_token`, `dispatch_claimed_at` | CHAR(32) ASCII binary、DATETIME(3)；首次执行权独立持久化，没有可过期再抢执行的 lease |
| `provider_state`, `reason_code` | VARCHAR(16)、VARCHAR(64) 可 NULL；记录已有 NOT_SENT/UNKNOWN/RECEIVED 事实，与本地请求结果分开；未调用前无 provider 事实 |
| `result_json`, `result_hash` | JSON、CHAR(64) 可 NULL；首次验证结果与其 SHA-256，同原 rid 返回，不从余额重建 |
| `created_at`, `updated_at`, `completed_at` | DATETIME(3)，completed 可 NULL；审计/预检，无过期删除身份语义 |

必须通过 CHECK 或等价受控写入校验区分两种行：新执行行具备完整 hash/context/配额计划/claim；历史隔离行无 claim、禁止执行，保留已知原状态/原结果和 `LEGACY_UNBOUND` 原因，不伪造缺失 nonce/hash。新行 JSON 大小、idea/节点数量、输出正文上限还需与真实输入/模型输出约束及结果保留策略一起审定；DTO 当前没有完整长度边界，不能把日志 500 字摘要或 transport 限额当作数据库容量证明。未提供容量/RPS/耗时承诺。

不新增从 intent 指向尚不存在 quota 行的强制外键：intent 先独立提交，quota recovery 再按原计划 prepare。quota 的既有全局 nonce 唯一约束仍是最终重复保护；若候选 nonce 与既有账本冲突则 fail-closed，不能为已落库的同一 intent 静默换号。

## 身份、提交与恢复合同

1. member 从当前认证上下文获取。rid 沿用既有 UUID 校验，trim 后按 ASCII 小写保存；不擅自改成仅 UUIDv4。payload 指纹由服务端计算，不信客户端 hash。建议固定 UTF-8 JSON 数组 `['theme_draft.request.v1', idea, productType, existingNodeNames]`，实际编码使用标准 JSON 双引号；null、空串、数组 null/空、顺序、重复项和空白均保持 DTO 的原输入语义，不随意 trim/排序/default。受信角色、提示词/配置事实另冻结在 context，重放不用当下角色重新选择池。
2. 首次请求先独立短事务 INSERT 原身份、完整计划和唯一 claim，提交成功之后才能 Redis/quota/模型。以唯一约束争取执行权，不做“先查无行所以能执行”。仅本次成功提交 INSERT 的持有者可在事务外进入 Creator 一次。提交返回不明时没有执行许可证，先回读；不能因查无行或超时重新进入发送。
3. 并发 INSERT 冲突者退出失败事务后，以新鲜只读/current read 找同 member/rid。hash 相同只回读；hash 不同拒绝，零 quota/provider/业务写效果，允许必要脱敏拒绝审计。具体 HTTP 冲突机器码由 ApiAi owner 另审，不改 01 协议。若用 FOR UPDATE，必须 `useCache=false/flushCache=true` 并验证 MyBatis 一阶 cached-null 与 MySQL RR 旧 snapshot；不能仅关二阶缓存。锁只覆盖 intent，不反向取得游戏/session 锁。
4. 原计划消费与 quota recovery 使用现有独立事务和 Redis Lua；provider 在业务事务外。进程在 durable claim 后任意位置崩溃，未来请求都保留原 row/nonce/receipt；不得因 claim 年龄、Redis TTL、lease、NOT_SENT 补偿或恢复 worker 认领而重新执行。牺牲可自动恢复的进度，不能以“看起来没发”换取重复外部动作。
5. 收到首次可验证结果后，短独立事务按 member/rid/hash/claim/nonce CAS 保存原结果/本地状态与 provider 事实，再向外返回。保存失败不产生新请求或 nonce；回读保留 UNKNOWN，原执行者仍可按同一 claim 保存同一首次事实。迟到的同次可信结果允许用明确 CAS 消除 UNKNOWN；不允许覆盖已保存的首个终态结果。接口、签名、鉴权与结果哈希核对由后续实现冻结；没有现成模型查单能力时不能编造自动查证/回调入口。
6. GET/read 和重复 submit 不创建 nonce、不规划当日额度、不联网、不调用 Creator。Redis 是响应缓存；miss/error 走 MySQL 权威回读，DB 异常必须 STATE_UNAVAILABLE/fail-closed，不能变成 NOT_FOUND 或借 Redis 另执行。若 DB 确实无行，能否首次受理还受下节历史切换边界限制。
7. 若调用者先超时而物理 provider 仍执行，记录 UNKNOWN 并保留原占位；不据超时解除身份/抢占。transport 许可释放仍只依据 F 的物理退出规则，请求层不自行释放或重判 NOT_SENT。quota recovery 的 owner/fence 与 intent claim 是不同职责，不互相伪造。

## 历史切换与响应正文保留：两项不能掩盖的缺口

新增表“无行”不能证明旧系统从未发送过该 rid：旧 Redis 可能早已 TTL 到期或丢失，quota/log 又无 rid 关联。客户端 UUID 不带可信受理时间，无法用新表创建时间、格式、额度或某天日志把历史与新请求区分开。

切换前需要 root 决定并举证以下其中一种准入方式：

- 受控停写、核对所有真实调用者及受理历史，证明该功能从未产生旧受理身份，再开启新路径。“小程序无用户”本身不能证明所有 HTTP 调用者都无历史；不得通过清空资金、回执、审计来制造空历史。
- 若存在或无法排除历史，先冻结旧入口，保留/导入仍可核对的 Redis 原 rid/原事实为不可执行的持久行；对缺失 hash/nonce 的行保留 LEGACY_UNBOUND。已丢失记录无法完整回填，仍需服务端持久签发的新请求准入凭据或明确新命名空间，使旧/无绑定身份不能进入首次执行路径。这涉及 ApiAi/Page 接口决策，须 root owner 批准后另做，不暗改 01，也不承诺仅导入现存 Redis 即覆盖全部历史。

回读可保留当前 Redis 24 小时缓存，但 MySQL 业务唯一身份不按此 TTL 删除。最初可保留完整 DB 结果直至批准正文保留/脱敏策略；不得以无限 Redis 墓碑冒充耐久。将来清理 result/context 正文时仍保留 member/rid/hash、原 quota 身份、provider/本地结果事实及唯一约束；不能把已知 SUCCEEDED 伪装 NOT_FOUND/UNKNOWN。当前 DTO/Page 对“SUCCEEDED 但正文已过保留期”的行为未明确，须 root 决定结果不可用的表达和保留期后才可启动正文清理，不能悄悄提供重生成。

## 预检、迁移和回退限制

实施前由获准 CI/VPS：确认 MySQL 版本/InnoDB、charset/collation/唯一键长度、表不存在或结构精确匹配、现有 quota nonce/receipt 唯一约束、所有调用者与原 rid 可追溯性；受控统计各类 legacy 身份数量但不输出原正文/凭据。Redis 仅受控遍历本业务键，不清库、不扩大凭据访问。新旧同时写的切换期间不能有未被拦截的 Redis-only 入口。

canonical migration 需单独提交评审，包含字段/约束/索引预检与只读回读；本文件不执行任何 DDL。集成真实存储计划需显式登记 suite/source/env/独占 schema/精确 Redis keys，满足现有 fail-closed 门禁后再由 root dispatch。

回退只允许停新受理、保留 intent 回读/原 quota 恢复。新表确证从未受理且为空、所有写入口已停后，才可评估空表回退；也可保留未使用空表。只要存在一条已受理行，就不得 DROP/清表/删除身份或恢复 Redis-only 写路径，否则会再次释放原 rid。不会回退或删除原 quota/资金/审计数据。

## 待实施的真实 MySQL + Redis 验证清单（当前全部未跑）

| 场景 | 必须观察的事实 |
| --- | --- |
| 成功/已知失败/typed UNKNOWN，原 rid read/replay | 首次状态与原结果一致；Creator 一次，原 nonce/receipt 不变；现有政策不变 |
| Redis TTL 到期、仅删除本 suite 原状态键、Redis 故障、服务对象重建 | DB 仍认原请求；无二次 Creator/配额预占；不得换号 |
| 同 member/rid 改 idea/productType/节点数组；不同 owner | hash 冲突或隔离拒绝，provider/审核/通知边界零调用、经济/业务零变化；必要审计单独计数 |
| 两实例 barrier 同时受理；先空读再另一连接提交后 current read | 真实唯一 INSERT 仅一赢家；L1/RR 不误判不存在；一份 plan/claim/配额/provider |
| durable claim 提交失败/返回不明 | 确定失败零 Redis/provider；不明先查证、不盲目再 INSERT 执行 |
| claim 后、quota 后、provider 进入后、provider 返回但结果保存前分别崩溃 | 持久身份仍在，后续仅回读；UNKNOWN 不换号、不自动重发 |
| 首结果 DB 提交后缓存写失败或客户端丢响应；同次迟到结果 | 原 rid 查回同一结果；迟到 CAS 不覆盖首结果，不产生新 nonce |
| 原 BJ day/跨日；退款 Redis 失败/恢复 worker/旧 fence | 复用既有恢复服务补偿原 receipt 一次；不影响新日/别人的余额，UNKNOWN 不退原占位 |
| legacy 未绑定/已丢 Redis 身份；无 rid 路径 | 按获批切换规则拒绝执行，不能用“表无行”或危险 fallback 产生新原意图 |
| 响应正文保留期清理（获批后） | 原唯一身份/收费事实仍在；不变成可执行 NOT_FOUND，不假报未知已知成功事实 |

测试使用真实 service/mapper/事务/MySQL/Redis 与受控无网络 provider；barrier 固定交错，不靠 sleep+重试假绿。每例核对 original identity、调用次数、DB 行、Redis 精确键和零效果快照，结束独占 schema/键回读 CLEAN。不会用 Mock/H2、四个冻结 E/F 经济接线用例或 Page Node 结果冒充本清单验收；没有 HTTP/JWT/真实模型/容量/灾备 T20 证据。

## 本交付实际完成与阻塞

仅完成公共源码只读核对和本文；静态 diff/有限秘密扫描见发布回执。未创建 migration、service、mapper、测试，不运行 Java/MySQL/Redis/HTTP/CI，不执行生产动作。secret-file-gate 不等于固定版本 gitleaks，最终文档 commit 需 root 同样复扫。

待 root 决策：历史身份切换准入、无 rid 入口关闭、Creator/Gateway 原计划接口与 owner、payload/结果边界及正文保留表达。批准具体方案后再交最小代码/迁移候选和真实存储回归，不能用本文或 01 冒充已实现。
