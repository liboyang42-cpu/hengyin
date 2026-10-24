# E / Theme Draft v2：prepare → execute 接口合同候选

结论：按 root 已批准方向固定为“服务端新签发身份 + MySQL 原计划 + 独立 durable claim + 一次事务外执行”。旧号/无号不能执行；正文仅保留 24h，业务身份不随正文删除。本轮只新增此合同文档，没有 Java/SQL/Page/配置改动。以下是供 owner 对齐的完整源级签名，尚未经过 Java 编译，不能称为实现或测试通过。

## 基线与本轮交付边界

公共仓库 `liboyang42-cpu/hengyin`。实际 HEAD/parent `8495c0c834e8b540df1e9ff734bcbe4fbee447a0`，tree `d048185ceec7d9e48b4718e162a17be41fca24e1`；独立目录 `/workspace/hengyin-e-theme-contract`，分支 `codex/architecture-day-e-theme-durability-contract`。8495 的 PROPOSAL 原文不改；本合同落实协调者后续裁决，替代其中尚未裁决的准入、正文保留及接口部分。旧 74de/8495/e359 等候选保持冻结。

源码接线核对基于 8495 的生产 parent 26f：现有 Gateway 已有 `planQuotaReservation/consumePlannedQuota`；Creator 的玩家 nonce 仍在私有预占方法内生成。没有擅自把 owner 后续联合源当作本候选基线。G 的 schema CHECK/partial-DDL 风险由协调者通报，本轮未独立重演 G 的反例，以下明确列入迁移验收。

## 1. 三种身份与入口

- `prepareKey`：客户端为一次**显式新起草动作**生成的 UUID，trim 后 ASCII 小写；新协议独立字段/入口。唯一 `(member_id, prepare_key)`。同 key/同规范 payload 永远找回原 prepare；同 key/不同 payload 409，不新建原计划或 nonce。不能把遗留 pending rid 自动当成一次新 prepare。
- `requestId`：服务端在首次 prepare 生成 `td2_` + 32 个小写 hex（共 36 字符），全局唯一。客户端不能指定它，prepareKey、server requestId、quota nonce 是三个不同的身份。
- `quota nonce`：prepare 内由服务端生成的独立 32hex，连同原 member/pool/day/counter/reservation/receipt 持久化。prepare 不调用 quota recovery.prepare、Redis 配额 Lua、审核或模型；上述经济步骤只可能在 execute 的 durable claim 提交后发生。

execute **只 lookup 已提交的新 namespace 记录并 CAS**，绝不 INSERT 身份。即使伪造一个 `td2_...`，无行/非 owner 也没有执行许可证。旧 UUID/无 rid 不进入执行；旧 Redis miss 不能证明旧号未发送，不能触发新 prepare。

固定接口如下，code 指 `AjaxResult.code`，不是声称 Spring 已改变实际 HTTP transport status：

| 接口 | 输入 / 输出 | owner 行为 |
| --- | --- | --- |
| `POST /api/ai/theme/draft/prepare` | 下述 PrepareReq；成功 code=200，data=IntentView | 新建并提交原计划后保存短期 context，再返回 server requestId。重复 key 只回原身份及其当前事实，不换计划/延长正文 TTL |
| `POST /api/ai/theme/draft/execute` | 只有下述 ExecuteReq，不再接受 idea/role/pool/nonce/hash | 只有 PREPARED 的 durable CAS 赢家可执行一次；先提交 claim 再出事务。其余请求回原事实 |
| `GET /api/ai/theme/draft/request?requestId=...` 或 `?prepareKey=...` | 两参数必须且只能有一个；新 server id、旧 UUID 或新 prepareKey；新路径 data=IntentView | 登录 member + 原身份回读，无 quota/审核/模型/新 nonce；prepareKey 恢复丢失的 prepare 回包，旧 UUID 只走保守 legacy read |
| 原 `POST /api/ai/theme/draft` | 所有旧 body，包括有旧 UUID、无 rid | 关闭直调 Creator；code=400 / `AI_DRAFT_PREPARE_REQUIRED`，零经济/外部效果；不自动代理到 prepare/execute |

prepare 通过服务端可信会员/角色解析规划；execute 的**重放**只回原记录，不按当前角色重选池。首次 claim 后、首个配额/审核/模型效果之前由 Creator 再校验当前权限；当前角色与原角色不一致、会员不存在/角色无法解析，不使用旧 `member == null → player` fallback。GET 仍按认证 owner 查询，不因角色变化篡改历史事实。

v2 成功和已有事实的 data 都是 IntentView，草稿位于 `view.result`，不沿用旧 POST 的 data 直接是 AiThemeDraftResp 形状；Page owner 必须联动。新错误码/映射供 root 本轮逐字复核：

| 条件 | AjaxResult.code / errorCode（或 data.reasonCode） |
| --- | --- |
| prepareKey/GET 参数无效、GET 双参数或均无参数 | 400 / AI_DRAFT_INVALID_REQUEST |
| 同 member/prepareKey 的 payload 指纹冲突 | 409 / AI_DRAFT_REQUEST_CONFLICT；不新规划，不执行 |
| feature 未启用的新 prepare/execute | 503 / AI_DRAFT_DISABLED；read/清理仍可用 |
| execute 旧/无/非新 namespace id；原 POST | 400 / AI_DRAFT_PREPARE_REQUIRED |
| 格式正确的新 server id 无行或非 owner | 同一 404 / AI_DRAFT_INTENT_UNAVAILABLE，不泄露是否存在 |
| 已有 PROCESSING / UNKNOWN 的 execute replay | 409 / AI_DRAFT_IN_PROGRESS 或 AI_DRAFT_OUTCOME_UNKNOWN，data 保留原 view |
| 首次执行已知权限变化 / 原计划到期 / 配置变化 | 403 / AI_DRAFT_AUTHORITY_CHANGED；409 / AI_DRAFT_PLAN_EXPIRED；409 / AI_DRAFT_CONFIG_CHANGED，保存相应 FAILED/NOT_SENT 事实 |
| 原计划格式/完整性异常或 DB/角色服务异常 | 500 / AI_DRAFT_STATE_UNAVAILABLE；不把异常猜成权限撤销、不换计划、不继续效果 |
| SUCCEEDED 且原 body 可用 / FAILED 既有故障 | execute 为 200 / 无 errorCode，或原失败 500 / AI_DRAFT_FAILED；data 均是原 view，固定失败原因放 reasonCode |
| 已知 SUCCEEDED 且正文到期 / 到期前不可用 | execute 为 409 / AI_DRAFT_RESULT_EXPIRED，或 503 / AI_DRAFT_RESULT_UNAVAILABLE；state 仍 SUCCEEDED |
| GET 已找到事实，包括过期/UNKNOWN；prepare 重放已存在行 | 200 / data=原 view，reasonCode 明确相应事实；prepareKey 尚未查证则 UNKNOWN/AI_DRAFT_PREPARE_UNCONFIRMED，不据此执行 |

read Redis 故障时，若 MySQL 原事实已可确认，view 保留该事实并给 RESULT_UNAVAILABLE；若连 DB 事实都无法确认，则 500/STATE_UNAVAILABLE，不返回 NOT_FOUND。暂时缺 context 不赋予执行权；首次 context 写入失败可保存已知未执行失败，不能通过 execute 上传新正文修复。

## 2. 最小新增 Java 类型和既有类方法

新增 DTO 各自一个文件，package `com.chengyinhub.business.domain.dto.ai`：

```java
package com.chengyinhub.business.domain.dto.ai;
import java.util.List;
public record AiThemeDraftPrepareReq(String prepareKey, String idea,
        Integer productType, List<String> existingNodeNames) {}
```

```java
package com.chengyinhub.business.domain.dto.ai;
public record AiThemeDraftExecuteReq(String requestId) {}
```

`existingNodeNames` 实现时须防御性复制，保留 null/顺序/重复项/null 元素的原 DTO 语义，不用 `List.copyOf` 意外改变它。服务端版本化 SHA-256 输入固定为标准 JSON 数组 `["theme_draft.request.v1", idea, productType, existingNodeNames]` 的 UTF-8 字节；不 trim idea、不把 null 当空数组，不接受客户端 hash。

新增 VO 各自一个文件，package `com.chengyinhub.business.domain.vo.ai`。PREPARED 是**新 v2 视图**的明确阶段，不能误标为模型在途；不改旧 `AiThemeDraftRequestState` 的编码来兼容旧调用者：

```java
package com.chengyinhub.business.domain.vo.ai;
public enum AiThemeDraftIntentState {
    PREPARED, PROCESSING, SUCCEEDED, FAILED, UNKNOWN
}
```

```java
package com.chengyinhub.business.domain.vo.ai;
import java.time.Instant;
public record AiThemeDraftIntentView(String requestId, AiThemeDraftIntentState state,
        boolean replayed, boolean contextAvailable, boolean resultAvailable,
        AiThemeDraftResp result, String reasonCode, Instant executeBefore,
        Instant resultExpiresAt) {}
```

view 不包含配额 token/nonce、claim、原输入/配置、内部 ID。`contextAvailable` 只是短期原输入存在的事实，不是执行授权；新 namespace 的 `resultAvailable=true` 必须同时满足 DB 为 SUCCEEDED、未过期、body owner/hash 匹配且可解析，result 才非 null。body 缺失/到期不能改掉 DB 的 SUCCEEDED。legacy 可回读仍可核对的原 Redis 成功结果，但不得伪称它已有 v2 DB/hash/nonce 绑定；原记录缺失则保守 UNKNOWN。

新增内部 plan 文件（不作 Controller request body），package `com.chengyinhub.business.service.ai`：

```java
package com.chengyinhub.business.service.ai;
import java.time.Instant;
public record AiThemeDraftPlan(Long memberId, String role, String pool,
        String originalDay, String counterKey, String nonce, String receiptKey,
        String reservation, String creatorConfigHash, String gatewayConfigHash,
        Instant executeBefore) {}
```

所有 plan 字段非 null，使用实际 DB 原行构造；两个 hash 均为 64 位小写 hex。`reservation` 对两个池均固定 `counterKey + "|" + nonce`，但 player token **不传给** Gateway 的 gen/photo consume 方法。严格验证 member、role/pool 映射、BJ day、counter 前缀、receipt 构成及 nonce 一致，不能只信 record 的调用者。

在**既有** `IAiCreatorService` 增加以下方法，由 root 实现，E 本轮不写：

```java
com.chengyinhub.business.service.ai.AiThemeDraftPlan planThemeDraft(
        Long memberId, String nonce);
com.chengyinhub.business.domain.vo.ai.AiThemeDraftResp themeDraftPrepared(
        com.chengyinhub.business.domain.dto.ai.AiThemeDraftReq req, Long memberId,
        com.chengyinhub.business.service.ai.AiThemeDraftPlan plan);
```

`planThemeDraft` 只做可信角色/本地配置读取和身份纯规划；不读取/写入额度计数，不做额度 recovery.prepare、审核或模型调用。玩家规划原 `ai:theme:draft:player:<member>:<BJday>` 与 `ai:player:theme:draft:reservation:<counter>:<nonce>`。非玩家直接复用现有 `gateway.planQuotaReservation(memberId,"theme_draft",nonce)`，从其原 token 校验/构成 gen plan，不能自己另算第二个当天 nonce。

`themeDraftPrepared` 仅供持久 claim 赢家调用：校验原 plan，使用原 req snapshot；player 进入下面的私有 helper，gen 交给 Gateway 消费原计划。它不重新 resolve 角色来选择另一个池，不调用旧的随机规划 `themeDraft`。返回值仍为既有 AiThemeDraftResp；既有有效稿/无效稿/退额政策不变。

Creator 最小私有消费 helper（返回现有私有 reservation 类型，不新增一层 service）：

```java
private PlayerThemeDraftQuotaReservation consumePlayerThemeDraftPlan(
        Long memberId, com.chengyinhub.business.service.ai.AiThemeDraftPlan plan);
```

此 helper 使用 plan 的原 counter/nonce/receipt，先 `quotaRecovery.prepare` 再复用原 Lua/代际/退额流程；没有 `simpleUUID()` 或新 day。`executeBefore` 固定为 prepare 原 BJ day 的下一日零点，与 context 的固定 24h deadline 取更早者；首次执行已到期就已知未执行拒绝，绝不重规划。

在**既有** `AiGatewayService` 增加以下方法，由 root 实现：

```java
String themeDraftConfigurationHash();
com.chengyinhub.business.service.ai.AiGatewayResult generateThemeDraftPrepared(
        Long memberId, com.chengyinhub.business.service.ai.AiThemeDraftPlan plan,
        String systemPrompt, String userPrompt);
```

配置指纹只读取服务端本地/已装配配置，固定覆盖 theme_draft 实际路由、client/model、额度政策版本和相关执行配置；不调用模型，不含 credential 或其 hash。Creator 指纹覆盖其模板/解析政策版本、系统 prompt hash、player 池政策；永久 metadata 不保存 prompt/idea 字节。配置不匹配时拒绝旧 plan，不默默使用当下新配置。生产 OpenAI-compatible client 已有 `configuredModel()` 可读实际 model，不改 F 的 LlmClient/transport；受控 `client:<name>` 测试替身不能冒充实际 HTTP 模型验证。

Gateway 新入口仅服务 theme_draft：gen 复用 `consumePlannedQuota(memberId,"theme_draft",plan.reservation())`；player 不双扣 gen，由 Creator 持有原 player receipt。stage/fence/补偿均绑定原 receipt，原 trace 与该 nonce 可关联。沿用已冻结 typed NOT_SENT / UNKNOWN / RECEIVED 政策；不在业务层重判 IOException/status/timeout/cancel。实际调用须使用与已校验指纹一致的本次配置快照，不能校验 A 后再选另一个 B。

新增本地 preflight 拒绝类型由 root 持有（与 F 的 transport NOT_SENT 不是同一个类型）：

```java
package com.chengyinhub.business.service.ai;
public final class AiThemeDraftPlanRejectedException extends AiServiceException {
    public enum Code { AUTHORITY_CHANGED, PLAN_EXPIRED, CONFIG_CHANGED,
        PLAN_INVALID, CHECK_UNAVAILABLE }
    private final Code code;
    public AiThemeDraftPlanRejectedException(Code code) {
        super(Reason.UPSTREAM_UNAVAILABLE, "AI 草稿原计划不可执行");
        this.code = java.util.Objects.requireNonNull(code);
    }
    public Code getCode() { return code; }
}
```

仅在进入任何 quota/审核/模型效果前允许抛出此类型；E 可据此保存 FAILED + NOT_SENT 与固定 reason。数据库/角色服务检查错误由该零效果 preflight 窄边界标 CHECK_UNAVAILABLE，500/STATE_UNAVAILABLE，不猜成权限已撤销或无行；PLAN_INVALID 同样阻断并报告完整性错误。不能在已进入效果后把普通 generic 异常包装成这个类型。权限检查保证“撤权已在该次可信当前校验前提交”时拒绝，不能把两次读夸大为所有 inflight 撤权截止的线性化证明。

在**现有具体** `AiThemeDraftRequestService` 增加这些 public 方法，不新增 interface+impl 层：

```java
public com.chengyinhub.business.domain.vo.ai.AiThemeDraftIntentView prepare(
        Long memberId, com.chengyinhub.business.domain.dto.ai.AiThemeDraftPrepareReq req);
public com.chengyinhub.business.domain.vo.ai.AiThemeDraftIntentView execute(
        Long memberId, com.chengyinhub.business.domain.dto.ai.AiThemeDraftExecuteReq req);
public com.chengyinhub.business.domain.vo.ai.AiThemeDraftIntentView readIntent(
        Long memberId, String requestId);
public com.chengyinhub.business.domain.vo.ai.AiThemeDraftIntentView readPrepared(
        Long memberId, String prepareKey);
public int purgeExpiredBodies(int limit);
```

以上是既有类的方法签名清单，并非可直接放进具体类的无 body Java 实现，也不另建 abstract API；上方新类型则给出完整 package/import 定义。使用 `readIntent/readPrepared` 避免与旧 `read(Long,String)` 返回类型冲突；旧 submit 不再是执行许可入口，只可保守回读/拒绝 PREPARE_REQUIRED，不再调用旧 Creator。旧 read 可作为 legacy 读取 helper。仅靠 Controller 不调用旧 submit、却让旧 public submit 仍可生成，不算关闭执行路径。`readPrepared` 不需要客户端重新上传正文；查无行只表示尚未查证，返回 UNKNOWN/AI_DRAFT_PREPARE_UNCONFIRMED（requestId 可 null），绝不借回读创建身份。

## 2a. 当前 Page 的最小联动合同（E 不写 Page）

首次明确“起草”动作先保存 prepareKey 与 PREPARING 阶段，再 POST prepare。拿到 server requestId 后先保存该 id，再 POST execute；不能反过来先发请求后记身份，本地身份保存失败时停止该发送并明确报错。持久客户端记录只需身份/阶段/时间，不新增无限期 idea/原 prompt 存储。

prepare 网络结果未知：只 GET 原 prepareKey，或在原输入仍可准确取得时 POST 同 key/同 payload；不能自动生成新 key。GET 找回 PREPARED 且 contextAvailable 后可继续原动作的 execute，始终使用原 server id。execute 结果未知：只回读该 server id；PROCESSING/UNKNOWN 不能再次执行或新 prepare。若原 id 仍 PREPARED，只能继续同一 id 的 execute，不生成另一身份。

重启时仅有 prepareKey 也能 GET 恢复，无需把 idea 长期存进客户端 storage。遗留 UUID 仅允许 legacy GET；miss/UNKNOWN 不自动迁移为 prepareKey。已知 SUCCEEDED+RESULT_EXPIRED 明示原生成已成功、正文已过期；只有另一次明确“新起草”用户动作才生成新 prepareKey，不由错误处理器、onLoad 或额度刷新自动生成。

## 3. 持久化与结果时序

prepare：先验证 member/key/payload；同 prepareKey 先找原行并核对 hash，不因配置/角色/日期变化重规划已存在身份。新行先由 Creator 纯规划，短 REQUIRES_NEW INSERT 完整 plan + PREPARED，唯一约束决定 prepare 赢家。冲突退出原事务后 current read 找赢家。成功提交后写固定 deadline 的短期 context，才回包；回包丢失重复 prepareKey 找同一行、同一 requestId/nonce。进程在 DB commit 后 context 写前退出也不能再造身份；没有可用原 context 时不执行，不能由 execute body 补填输入。

execute：先 owner/current lookup；无行拒绝，不 INSERT。PREPARED 以 DB 条件 CAS → PROCESSING + 唯一 claim token/claimedAt，在 REQUIRES_NEW 内提交。只有“CAS=1 且提交确定成功”的线程可事务外调用 `themeDraftPrepared`。并发者只读 PROCESSING，不能造另一份 degraded/UNKNOWN 结果覆盖首结果。commit 返回不明没有执行许可证；新鲜回读只用于查证，不因租约/年龄把 PROCESSING/UNKNOWN 重新置 PREPARED。

Creator 在首个副作用前重新核对会员/角色、day/deadline、config、plan。已变更/过期已知 NOT_SENT，保存拒绝事实；若在准入校验后发生撤权/跨日，不宣称现有非锁定检查已经实现严格 inflight 截止，仍沿原身份处理，不切池/换号。DB 异常不按不存在处理。所有锁读双属性 `useCache=false/flushCache=true`，锁后是真正 current read；防 MyBatis L1 cached-null/旧状态及 RR snapshot。意图锁内不运行 quota Redis、审核或模型，也不取得 C session 锁。不能只靠 `@Transactional(NEVER)` 推断没有同步绑定旧 SqlSession；新鲜内部短事务与 mapper cache 策略必须真库回归。

结果：原执行者计算完整结果字节/hash，写短期 body，再以独立短事务按 member/request/hash/claim/nonce CAS 保存首结果事实/hash/expiry。body 写失败也保存已知事实，不能重新调用模型；metadata 保存失败保留原 claim/nonce，后来只能保存同次可信事实或回读 UNKNOWN。并发/迟到结果不能覆盖首终态，超时不等于物理 worker 退出。generic provider UNKNOWN 保留原 quota 占位；typed NOT_SENT 补偿原额但不释放 request 身份；收到结果的原收费政策不改。

provider_state 只记录可证明的事实：专用 provider UNKNOWN/RECEIVED/transport NOT_SENT 及上述零效果 preflight。普通 AiServiceException 的 FAILED 不能靠 reason/cause/文字猜 provider 阶段；无明确事实时该列可 NULL。尤其无效稿失败不能一律写 NOT_SENT 或改变旧退额政策。

## 4. 24h 正文存储与可验证清理

最小方案选择 **MySQL 仅保存 metadata；context/result 正文放专属 Redis 24h TTL**，不在 MySQL 放永久或待删的 prompt/idea/result JSON。因此 Redis 丢失会损失可展示正文，但不会损失 identity/原计划/已知结果事实或允许再次执行。既有日志的新 prepared 路径也只记录输入/输出 hash 与固定事实，不能借 AiGenerationLog.inputBrief/outputBrief 或异常日志永久复制原 idea/prompt/结果正文；root Gateway owner 一并接线，既有历史审计不删除。

- context key：`ai:theme:draft:v2:context:<member>:<requestId>`；包含固定原业务字段、owner/hash 和绝对 `contextExpiresAt=首次prepare时间+24h`。
- result key：`ai:theme:draft:v2:result:<member>:<requestId>`；包含首次原结果、owner/hash 和绝对 `resultExpiresAt=首次结果事实时间+24h`。
- key/value 必须一次原子写入并带绝对 deadline；重试、回读、重放、补写同次事实均不延长原 deadline。禁止先 SET 再另行 EXPIRE 的失效窗口。具体绝对过期命令需在批准 Redis 版本实测；不假设新的服务器能力已验证。
- 读取先查 owner DB，再查其 body；校验 hash/owner/deadline。到期即不返回正文，并清理该精确 key；不靠“隐藏响应”冒充清除。
- `purgeExpiredBodies(limit)` 是具体、无外部经济效果的内部维护入口：按 metadata 的到期索引分别取 context/result 固定小批，仅 DEL 这些行推导的精确 keys，回读不存在后记 purgedAt。TTL 为主，入口负责验证/补清理 TTL 异常或残留；可重复调用，不 SCAN 全 Redis、不 FLUSH、不删除 metadata/hash/原计划。
- 新 feature flag `ai.themeDraftIntent.enabled` 默认 false；启用前必须有“原子固定 TTL、到期不可读、purge 入口及已批准维护调用者”的真实 CI/运行接线证据，不能只有方法没 caller。维护 wiring/准入由 root 协调；不改 G paused admission、ScheduleUtils，不启动新 runner 或绕准入。关闭新受理时也必须保留 read/清理/原 quota 恢复。

已知 SUCCEEDED、时钟达到 resultExpiresAt：仍 SUCCEEDED，`resultAvailable=false/result=null/reasonCode=AI_DRAFT_RESULT_EXPIRED`。GET 返回已知事实；execute replay 用 code=409/errorCode=AI_DRAFT_RESULT_EXPIRED，并携带该 view，不降格 NOT_FOUND/UNKNOWN。只有新的明确用户动作才能新 prepareKey 申请新 intent，不自动重生成。

未到期但 Redis 丢失/读取不可用/结果 hash 不符：保留 SUCCEEDED，`resultAvailable=false/reasonCode=AI_DRAFT_RESULT_UNAVAILABLE`，只提示回读原请求；不能谎称已过期，也不能自动新 prepare。DB 故障是 `AI_DRAFT_STATE_UNAVAILABLE`，不能返回无行。FAILED 的固定结果原因不因短期友好消息丢失改变。旧 UUID 保留可核对的 Redis 原事实；缺失/解析不明只给 UNKNOWN/LEGACY_UNBOUND，不导入猜测 nonce，不新建可执行行。

## 5. 最小 schema 候选约束（不执行 DDL）

只拟新增 `ai_theme_draft_request_intent`，canonical 文件 `chengyinhub-admin/sql/migration_ai_theme_draft_request_intent_20261024.sql`；本轮没有创建 SQL。相较旧提案：新增 prepare_key/服务端 namespace/PREPARED；删除所有 context_json/result_json/原 prompt 字段；不导入 legacy 行，因而新计划字段均非空。

永久列：id、member_id、prepare_key CHAR(36)、request_id CHAR(36)、request_hash CHAR(64)、plan_hash CHAR(64)、role/pool/original_day、counter_key VARCHAR(191)、nonce CHAR(32)、receipt_key VARCHAR(255)、reservation VARCHAR(255)、creator/gateway_config_hash、state/provider_state/reason_code、claim_token/claimed_at、result_hash/outcome_hash、created/updated/completed_at、execute_before、context/result_expires_at、context/result_purged_at。标识/hash 字符使用 ASCII binary；metadata 不含原输入/输出/异常文字。`result_hash` 绑定成功结果字节；`outcome_hash` 绑定版本化终态事实，不把缺失模型输出伪造为其 hash。

唯一键精确为 `(member_id,prepare_key)`、`request_id`、`nonce`、`receipt_key`；到期维护索引按各自 `purged_at,expires_at,id`，实际执行计划由真库 EXPLAIN 验证。quota 原全局唯一约束仍复用；intent nonce 冲突 fail-closed，不静默换号。不新增 quota source 列或假外键，不改旧 migration。

CHECK 使用专属 schema 名称前缀 `chk_atdi_v2_`，至少检查新 id namespace、nonce/hash 格式、五种 state/provider 事实、PREPARED 无 claim、PROCESSING/UNKNOWN 有原 claim、SUCCEEDED 有 claim/result_hash/结果期限；FAILED 允许本地 preflight 拒绝而无 quota row，不把所有 FAILED 伪标 NOT_SENT。精确长度/表达式/列序/索引名/CHECK 名须在 migration delta 中完整提交，不以本文代替 SQL 审核。

预检必须查 `information_schema`：目标表是否存在、列/类型/NULL/default/collation/约束表达式精确匹配；index 按 schema+table+name+有序列核对，CHECK 名在整个 schema 检查冲突及 64 字符限制，不能只查目标表。MySQL DDL 不因外层事务自动整体回退；不以 `CREATE IF NOT EXISTS` 掩盖旧表或前次 partial DDL。尽量单条 CREATE 定义完整表/索引/CHECK，失败后仍独立回读实际落地情况。

partial-DDL 恢复：preflight 名冲突时零 DDL；CREATE 成功但后验/后续步骤失败，标记候选未完成、保持新入口关闭，逐项盘点后按独立具体修复评审继续。不能自动 DROP/ALTER 陌生或非空表、删 CHECK 名、删除回执、降低门禁再跑。空表回退也要停止写入并验证实际 0 行与独占归属；有受理行就只能停新写保留身份/read/清理/恢复，不回 Redis-only。

## 6. 文件 owner 与后续交付拆分（均待具体源授写）

| owner | 精确目标文件/范围 | 本轮状态 |
| --- | --- | --- |
| E | `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/AiThemeDraftRequestService.java`；新增 `business/domain/AiThemeDraftIntentRow.java`、`business/mapper/AiThemeDraftIntentMapper.java`、`src/main/resources/mapper/business/AiThemeDraftIntentMapper.xml` | 只给合同；实现需后续授权 |
| root 逐字冻结，E 可在授写后同字创建 DTO/VO | `business/domain/dto/ai/AiThemeDraftPrepareReq.java`、`AiThemeDraftExecuteReq.java`；`business/domain/vo/ai/AiThemeDraftIntentView.java`、`AiThemeDraftIntentState.java`；`business/service/ai/AiThemeDraftPlan.java` | system Java 前缀同上一行；未创建 |
| root | `business/service/IAiCreatorService.java`、`business/service/impl/AiCreatorServiceImpl.java`、`business/service/ai/AiThemeDraftPlanRejectedException.java` | E 不改；root 实现规划/消费/preflight |
| root | `business/service/ai/AiGatewayService.java`、`business/service/ai/impl/AiGatewayServiceImpl.java` | E 不改；复用 plan/consume、冻结配置、短期正文日志脱敏；不重复覆盖冻结 E typed catches |
| root/其指定 caller owner | `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiAiController.java` 与专属 Controller tests；`chengyinhub-xcx/pages/publish/simple/index.js` 与专属 Node tests | E 未获 Page 授写；精确遵循 prepare/execute/read/expired；关闭旧直调 |
| E 候选/root 统一 schema 门禁 | 上述唯一 canonical 新 migration；专属 unit/真实 MySQL+Redis IT 与 `docs/delivery/architecture-day/E/THEME-DURABILITY-02/` | 不生产 DDL；不修改旧 migration |
| root 统一接线 | feature flag、已有获准维护 caller、测试 registry/CI/workflow | E 不改共享配置/CI，不 dispatch；无清理接线证据不得启用 |
| F/C/G及其他 owner | transport/client/admission、advanced/Photo/NPC/调度/资金核心 | 全部冻结，本片不修改 |

01 的旧 17 条测试证明旧合同下精确 UNKNOWN 分类；v2 关闭旧直调后，`legacyClientWithoutRequestIdKeepsDirectPath` 等旧执行预期必须由 root 按正式新合同替换，typed UNKNOWN/负控通过 v2 保留对应回归，不能删例/skip 来遮掩差异，更不能用 01 回执验 v2。

## 7. 对齐后必须点名的验证（全部未执行）

签名联合 compile（Creator/Gateway/E DTO），prepare 同 key 丢回包/并发/hash 冲突，prepare 无 quota/审核/模型；两实例 execute barrier 仅 durable claim 一次；L1 cached-null/RR current read；提交返回不明/进程退出不重发；撤权在首次校验前已提交、原 day/config/context 过期均已知拒绝零效果；typed NOT_SENT 原额一次补偿、generic UNKNOWN 保留原占位；gen/player 原计划不双扣、不跨日换号；首结果/迟到 CAS；Redis TTL/丢失后持久事实不变；精确 24h 到期/不可延长/清理入口实际 caller 与残留回读；SUCCEEDED+RESULT_EXPIRED 非 NOT_FOUND、非自动新 prepare；旧/无/伪造 server id 不可执行；同源真实 MySQL+Redis 表/keys CLEAN；migration schema-global CHECK 冲突零 DDL与 partial-DDL 预检恢复。

只允许 root 的获准 CI/VPS Java/MySQL/Redis 验证，无新依赖/真实模型/生产动作。本轮实际仅源码只读和文档静态检查；未创建类型或测试，未运行 Java/DB/HTTP/CI。secret-file-gate/有限扫描不等于固定 gitleaks，root 对最终文档 commit 复扫。以上签名、caller 分工、Redis-only 短期 body 与新增 v2 阶段/机器码均作为具体 review 合同交 root；授写后再分别生成最小候选，不把提案当已实施。
