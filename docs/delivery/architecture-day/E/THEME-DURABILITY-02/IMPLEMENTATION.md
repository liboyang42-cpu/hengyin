# E Theme Draft v2 实现候选交付

结论：已写完独占源码、单一 canonical migration、测试与迁移矩阵，等待 root 与 A/root caller delta 联合编译和真实存储验收。默认 flag=false；没有运行 Java/DB/HTTP/CI/模型或 DDL，不可据本交付启用、合并部署。旧 Redis-only submit 已关闭，不保留执行 fallback。

## 基线、隔离和依赖

- 唯一公共仓库 `https://github.com/liboyang42-cpu/hengyin`。
- 实际 HEAD/parent `e683b5a0ebc44cc217ac75e378c271fd96c80ae0`，tree `da143928ad36e05eedd1851048fb2c9c785849d2`；独立目录 `/workspace/hengyin-e-theme-v2`，分支 `codex/architecture-day-e-theme-intent-v2`。初始干净，没有切换或回退其他工作树。
- 基线已含 01 UNKNOWN catch 与 F transport 更新；按 ee96 冻结合同新增 v2，旧候选不改。协调者后续授权转换旧 Service 执行测试并关闭 submit，优先于之前“原测试不改”的表述。
- **编译依赖**：A 独占 `IAiCreatorService/AiCreatorServiceImpl/AiThemeDraftPlanRejectedException/AiGatewayService/AiGatewayServiceImpl` 的冻结接口适配，已只读核对公开 `40fdffaa345c4c239553f54f36a4ae9147191a1b` 的实际签名/Code/字段/原receipt。pool始终player-draft或gen（player只作role）；IT会员夹具按实际guard设status=1L/delFlag=0并保持member.role与受控RoleService一致，未放松生产guard。本基线不含这些新方法/新异常，不能声称 E delta 单独可编译。E 不创建或覆盖 A 的文件。root 另有 Controller/Page、flag/maintenance/registry/CI delta，必须联合验收。
- root 指定的 common ServiceException 实为 final；已按明确裁决使用 nested RuntimeException + Integer getCode，未去掉公共 final。构造器 `(int,String,AiThemeDraftIntentView)`、getErrorCode/getIntent 保持接口。

## 文件清单与调用链

只改 `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/AiThemeDraftRequestService.java` 和原专属 `AiThemeDraftRequestServiceTest.java`。新增：

- system Java：`business/domain/AiThemeDraftIntentRow.java`；`business/mapper/AiThemeDraftIntentMapper.java`；`business/domain/dto/ai/AiThemeDraftPrepareReq.java`、`AiThemeDraftExecuteReq.java`；`business/domain/vo/ai/AiThemeDraftIntentView.java`、`AiThemeDraftIntentState.java`、`AiThemeDraftRecoveryItem.java`；`business/service/ai/AiThemeDraftPlan.java`。
- `chengyinhub-system/src/main/resources/mapper/business/AiThemeDraftIntentMapper.xml`。
- `chengyinhub-admin/sql/migration_ai_theme_draft_request_intent_20261024.sql`（唯一新增 canonical，未执行）。
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/AiThemeDraftIntentServiceTest.java`；`.../service/ai/AiThemeDraftIntentMySqlRedisIT.java`。
- 本目录 IMPLEMENTATION.md、CASE-MAPPING.md；最终发布身份/manifest 单独回报，不在文档内伪造未知 commit。

原生产 caller 搜索与协调者复核：旧 submit 唯一生产 caller 为 ApiAiController，旧 Creator.themeDraft 的生产 caller 是旧 Controller 和 submit。E 关闭 submit；root 关闭 HTTP 旧 POST、联动新 Page。E 不改 Controller、Creator/Gateway、鉴权、Photo/NPC、transport、C/G、公共配置、CI 或旧 migration。单独 E delta 不能声称 HTTP 旧直调已全部关闭。

## 修复合同

1. 新 prepareKey（owner+规范 payload hash）唯一映射服务端 `td2_32hex` requestId；先纯规划，再 REQUIRES_NEW INSERT 完整原 member/role/pool/BJday/counter/nonce/receipt/reservation/config hash/期限，提交后写 context。prepare 不做 quota recovery.prepare、配额 Lua、审核或模型。并发 INSERT 输家只 current read 原身份，nonce 碰撞不重规划。丢回包可 readPrepared 或同 key/hash 找回。
2. execute 不 INSERT；仅新 namespace 的原 owner/原 plan hash 可 PREPARED→PROCESSING CAS。CAS=1 且独立提交确定成功才事务外进 A.themeDraftPrepared。commit 回包不明不能启动 Creator。PROCESSING/UNKNOWN 不因租约/TTL/restart 重发。
3. E 拒绝缺失/到期/corrupt context；A 在首效果前检验当前权限、原 day/config/plan，消费原计划。E 只信专用 preflight、provider UNKNOWN/RECEIVED 类型，不按普通 reason/cause/文字重判阶段或退款。generic 输入/无效稿保持 FAILED 原政策；结果状态与 provider fact 分开，未知 fact 可 NULL。
4. 原结果 body/hash 先计算，再独立 CAS 首结果事实/hash/expiry；缓存写失败也保存已知 SUCCEEDED。结果 DB 提交失败保留原 claim/nonce，不能重发；已提交但回包丢失可按原 id 恢复。没有后台租约重发或伪造 provider 查单接口。
5. read 与 purge 在 default-off 下可用，读不新建身份/配额或调用 Creator。旧 UUID 只读 legacy；miss 为 UNKNOWN/LEGACY_UNBOUND。new id 非 owner/无行同 404，不泄露存在性；DB 错误 StateUnavailable，不当无行。
6. 全部短 DB 操作 REQUIRES_NEW，无 Redis/审核/模型在锁内；锁读有 useCache=false/flushCache=true，读取也在新事务防 L1/旧 RR snapshot。时间写入/到期比较用同一 Java Instant 绑定，不混用数据库 UTC_TIMESTAMP 和 JDBC 时区解释；CI fixture 固定 UTC，未宣称所有时区已实测。

## 正文保留与清理

MySQL 30 列只有原身份/计划/状态/哈希/时间，没有原 idea/prompt/result/异常文本 JSON/TEXT。原输入仅在 `ai:theme:draft:v2:context:<member>:<id>`；原结果仅在对应 result key。原子 Lua 写入固定绝对 deadline，Redis TIME 计算剩余 TTL，重复写不能延长旧期限；context 首 prepare+24h，result 首结果事实+24h。

已知 SUCCEEDED 到期后仍保留 SUCCEEDED/resultHash，resultAvailable=false、RESULT_EXPIRED；到期前 Redis 丢失/读取失败/正文损坏是 RESULT_UNAVAILABLE，不伪报 NOT_FOUND/UNKNOWN，也不自动新 prepare。只有新显式用户动作可申请新 intent。正文可丢失不等于发送未发生。

purgeExpiredBodies(limit=1..100) 的总预算按尝试的精确 body key 计；以原 DB expiry 找固定小批，Redis 校验当前时间达到原 deadline 后 DEL 并原子 EXISTS=0 回读，再按原 id/expiry 标 purgedAt。保留所有业务唯一身份/hash/原计划，零 SCAN/FLUSH，不清全 Redis。root 还须接获准真实 maintenance caller 并验收后才可启用 flag；没有该 caller 的 E 源不是可启用版本。

新 prepared Gateway 日志须按冻结合同由 A/root 对齐为 hash/事实，不能通过旧日志摘要永久复制正文；E 不修改其源，也没有独立验收该 owner 接线。旧历史资金/回执/审计不删除，不导入猜测 nonce。

## H235 owner 冷恢复最终只读增量

先前尚未交付的pending名称/三状态过滤/升序合同已由root纠正，源码完全替换为 `AiThemeDraftRecoveryItem(Long cursor,String prepareKey,String requestId,AiThemeDraftIntentState state,String reasonCode,Instant createdAt,Instant executeBefore,Instant resultExpiresAt)` 与 `readRecovery(Long memberId,Long beforeId)`。null从最新开始，非正cursor400；同owner全部v2状态，id<beforeId（首次无条件）、id DESC、固定LIMIT21。只读metadata，不返回原body/member/nonce/hash/plan/claim，不读Redis/Creator或修改状态/期限；DB错StateUnavailable，不假空。root拥有GET recovery及Page选择，E没有Controller写权。

mapper一条源表SELECT，FORCE INDEX(idx_atdi_v2_recovery)，索引(member_id,id)，不作状态/过期过滤，不count、不接任意limit、不做列表N+1正文查询。真库IT从实际mapped BoundSql对首次及带游标两种SQL执行EXPLAIN FORMAT=JSON，要求源表idx_atdi_v2_recovery与range/ref访问；目前未运行，不宣称真实性能/RPS/耗时。

root展示前20，nextCursor为第20展示项，不能用未展示的21项；按该cursor向较旧记录翻页。稳定夹具验证降序跨页不重不漏、完整终态及过期事实、非owner隔离，真实成功且丢失客户端身份后仅凭owner列表找到原id和result期限，再由原GET取回原结果。列表本身不读body。

它不是跨页数据库快照：cursor只用于本轮遍历，下一次冷恢复从null重新开始；并发新提交/迟交不能据游标形成永久“没有旧未确认意图”的结论。不是legacy无原id恢复，不据新DB列表空推断旧请求没执行。只剩prepareKey且输入丢失时UNCONFIRMED仍不猜未执行、不自动改payload/新key。UNKNOWN能否另起一次的客户端政策由root询问人类，本片不改后端distinct prepareKey/经济规则，不新增CANCELLED/tombstone或取消接口。

## 测试交接（全部待执行）

精确旧 case→新 case 见 CASE-MAPPING.md；legacy 7、v2 unit 24、真实 MySQL+Redis IT 19，共50条声明（H235冷恢复增6条）。无 skip/Assumptions。unit 的 mapper/Redis/Creator 为边界 doubles，不能当真库证明。

IT 使用真实 E Service Spring 装配、A Creator/Gateway、MyBatis mapper、DataSourceTransactionManager、MySQL8、生产 Redis serializer、真实 Lua 和 quota recovery；模型 LlmClient、会员/角色/审核/日志外围为 doubles，无 HTTP。不 mock Gateway 或用全拒绝平台。真实角色撤销的 owner 数据库/HTTP/JWT 不在本 IT 证据范围。

IT 必填沿用已批准 env：CHENGYIN_AI_MYSQL_IT_PORT/USER/PASSWORD、CHENGYIN_AI_REDIS_IT_PORT/DATABASE；Redis PASSWORD 可按原模式选填。未配置直接失败不 skip。localhost 指显式受控 scratch 服务，不访问生产。exclusive CREATE 库名 `cy_td2_main_<uuid>`，迁移反例另建 `cy_td2_conflict_<uuid>` / `cy_td2_partial_<uuid>`；成功 CREATE 后才记 ownedSchemas。AfterAll 只 DROP 已证明独占的这些名字并独立 information_schema 确认0；Redis 从本任务身份/原 quota 行列出精确 owned keys，逐项 DEL/EXISTS 回读，不全库清理。每例随后只删除本独占测试schema内 owned member 的 intent/quota 夹具，隔离过期数据；不触及任何旧业务数据库。

barrier 固定并发 prepare/claim；真实 RR 先空读、另一连接提交、锁读找到原行；claim 和 outcome 提交后模拟 ack 丢失；真实 gen/player 原计划、typed NOT_SENT 补偿一次及 UNKNOWN 保占；角色/配置变化零 quota/review/model；TTL 固定/结果过期/精确 purge；schema 全局 CHECK 名冲突、NULL CHECK 绕过、CREATE 后验失败与重跑阻断。过期 plan/context 以独占测试库中完整 backdated fixture 验证，未等真实24h；TTL实际用 PTTL 核对，不能把时钟夹具称为24h墙钟测试。

root 应同源显式运行两 unit 类（7+24）和本 IT（19），校验逐 casename、0failure/error/skip、scratch/Redis CLEAN 后再认定实际通过。没有执行过 Java 编译、JUnit、MySQL/Redis、HTTP、CI或真实模型；历史 a0f/E4 等回执均不覆盖新源。

## 迁移预检、partial-DDL 与回退

候选采用 G 已提示的 NULL/名称风险边界：第一条 SELECT 全 schema 查所有7个 CHECK 名冲突、目标表不存在、MySQL>=8.0.16/strict；失败用硬错误阻断，先于唯一完整 CREATE。所有 CHECK 用 CASE WHEN…ELSE0，防 NULL unknown 放行。完整 CREATE 定义30列/4唯一索引/2维护索引/1 owner recovery索引/7 CHECK；postcheck按 schema+table+index+顺序列核对，不只凭名字。

没有 IF NOT EXISTS 掩盖 drift，没有旧历史 DML，未执行DDL。CREATE 后postcheck失败仍保留实际落地状态并阻断重跑；不得用外层事务假称整迁移回滚。预检/后验必须由 root canonical registry 接入后获准执行；异常时先盘点表/列/索引/全 schema CHECK/是否已有受理行，再另审具体恢复，不自动DROP/ALTER陌生或非空表、不删约束让绿。

回退只能停新受理并保留 read/purge/原 quota 恢复。仅在写入关闭、结构独占且表真实0行时可评估空回退；一旦受理即不删除表/身份，不退回 Redis-only。迁移增加存储但没有真实容量/RPS/耗时测量；不编造容量或性能验收。

## 实际执行的静态检查与环境事件

执行了 Python XML/interface/resultMap 对齐、全锁读双flag、SQL 单 CREATE/全schema先行预检/7个NULL安全CHECK/30个metadata列、Java括号词法和测试声明/无skip检查；git diff --check 通过。它们不是 Java 编译或业务测试。secret-file-gate、有限 token/private-key 扫描及最终文件 hash 在发布前复核；环境未安装 gitleaks，不新增依赖，固定版本最终commit复扫由 root 完成。

期间 exec_command 一次返回 No usable execution environment；上报环境 ccarenv_b64_Y2NhcmVudl85Yzc3NjJkODRiZjg4MTkxOWVkOTNjNjkwOWZlOGNmYw。等待同一环境 ready 后，原目录/HEAD/改动身份仍在，未重建环境/工作区、未回退他人修改。没有借机本机运行 Java/DB。

剩余门禁：A五文件与root caller/maintenance/flag/registry联合源码，真实编译与50条实际回执，canonical迁移结构/错误/cleanup实测，固定gitleaks；所有门禁尚未由E实跑。源码完成不等于完整功能启用或HTTP/授权/外部灾备验收。
