# A：玩家专用起草额度展示同步

结论：按人类明确裁决“玩家只受专用起草额度限制；展示同步它，保留当前实际扣减规则”，移除 player quota 投影中的 gen 剩余额度 min。仅一个生产方法变化；成功回包原本就取专用预占 remaining，保持原实现并增加回归断言。原实际扣减、补偿、UNKNOWN、nonce/day、role/prepared 校验及非 player 准入全部保留。

## 候选与范围

- 公共生产基线：`4197f6a6b11d62cb0a742aae73d7167c93114585`；开工 HEAD 同此。独立分支 `codex/architecture-day-a-player-draft-display`，工作树 `/workspace/hengyin-a-player-draft-display`。没有切换/回退任何其它工作树。
- 生产唯一修改：`chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/AiCreatorServiceImpl.java` 的 `themeDraftQuota` player 分支。
- 原测试精准适配：`chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/AiCreatorServiceImplTest.java`。
- 新专属回归：同目录 `AiCreatorPlayerDraftQuotaTest.java`，一个 boolean 参数化方法（legacy/prepared 两个 authored cases）。
- 其它新增文件仅本目录。未修改 E/root/F、Gateway、接口、DTO、Page、角色政策、CI、pom 或依赖。基线包含的新 prepared 方法原文保留，没有从旧候选复制替换。

## 调用者、失败机制与修复合同

展示入口：`ApiAiController.themeDraftQuota:283` 先解析认证 member、既有角色 gate，再调用 `IAiCreatorService.themeDraftQuota`；小程序 `pages/publish/simple/index.js:101` 请求 `/api/ai/theme/draft/quota`。除此未找到生产 quota 方法调用者。Page 已按返回 remaining 禁止零额新动作，本片不改 Page。

执行入口：当前 `AiThemeDraftRequestService.execute:303` 调用 Creator `themeDraftPrepared`；其与旧 `themeDraft` 共用 `executeThemeDraft`。旧 Controller submit 已拒绝执行，旧 Java 方法仍有测试/内部接口引用。本片没有重新打开旧路由。player 仍只预占 `ai:theme:draft:player:<member>:<BJday>`，专用上限3；Gateway player 分支仍不消费 gen。非 player 的 gen 额度/返回规则不变。

基线展示 `min(max(0,3-playerUsed),genRemaining)` 与基线实际专用预占、成功返回不一致。具体合法前置状态是：同一会员先作为 club 耗尽原 gen，然后经合法持久角色变更成为 player，保留原 gen 使用记录且 player 专用池仍有余额。现有授权 caller 包括 `ClubController.audit:386`、`ClubGovernanceServiceImpl.reconcileFormerOwnerIdentity:311`，最终 `RoleServiceImpl.changeRole` 锁定活动会员并更新 canonical role/能力投影，不清理 AI counters。这里只记录调用链，不修改或实际运行这些授权路径。

修复后 player 的 quota limit=3，remaining=max(0,3-playerUsed)；不再读取 gen。`executeThemeDraft:207` 成功 remainingQuota 原本就使用本次专用预占结果，无需修改。没有额外查询余额做新准入，没有跨池清零、转额、补扣或放宽任何额度/身份校验。

H `f0c13b283160b98faa07c576940beaf940195b50` 报告的业务口径未决项，由本次人类决定在此限定范围收口；不篡改 H 旧证据，不关闭其它任务或 A95 裸 POST 未决事项。

## 原37与新增回归

原类仍为35个 @Test + boolean false/true 两例，共37 authored cases，全部原方法标识保留；绝不表述为本环境已通过37。

精准旧→新：`playerThemeDraftQuotaReportsZeroWhenSharedGatewayPoolIsExhausted` 保留历史方法名，并加注说明此次产品裁决。fixture 不变：playerUsed=0、Gateway remaining=0；只将 remaining 断言 **0→3**，新增 player 不读取 Gateway remaining 的断言。其 limited=true、limit=3 断言不变。另在 `playerThemeDraftCountsOnlyCompleteRealDraft` 增加成功回包 remainingQuota=2；原完整性、单预占、零退款、北京时间 TTL 断言保留。其它原测试正文逐字相同。

新参数化回归使用真实 Creator + Gateway，复用现有 `AiNotSentGatewayFixture`（未改 helper）；Redis/Lua、quota ledger、成员/角色、审核、日志、模型均为 unit doubles，无网络。先由真实 Gateway 完成 club 第20次合法 gen 用量，确认 gen 展示0；再模拟已授权 canonical club→player 提交后的会员读回。分别走 legacy/prepared player：展示3，三次成功回包2/1/0且与随后展示相等，第四次专用额度拒绝，不额外进入模型。原 gen 始终20、不再读取 gen；prepared 校验原 counter/receipt/trace nonce；只有原专用 pool 的 prepare/retain，无退款。

这两例不冒充 Redis Lua/MySQL/真实角色修改验证，不验证 HTTP 权限或 E 的 durable claim；pool counter double 特意不承担跨日模拟，nonce/day 的完整现有测试与生产 helper 原文保留。原 Gateway/Creator prepared/UNKNOWN 测试文件未动。原37 + 新2 =39 authored cases；原 prepared43等不能加作本轮实际执行数。

## 验证与限制

执行 `python3 docs/delivery/architecture-day/A-player-draft-display/verify_static.py`，输出 `verification.json`。实际检查包括唯一生产替换、原测试精确预期 delta、所有其它 tracked 文件内容保持、原 prepared 测试/helper不变、原方法和用例数量保持、新文件词法括号配对（非编译）、git diff --check。输出含原/新文件 SHA256。

**Java生产/测试编译、JUnit原37/新增2、MySQL、Redis、CI、模型/微信、真实角色变更全部 NOT_RUN。** 用户明确禁止本环境运行，由 root 在已有联合候选路径统一执行。不 dispatch，不部署。新测试仅交可编译候选，不能以 Python 静态成功宣称 JUnit 通过。

无需 schema、迁移或数据回填；原 counters、ledger、receipt 不变。技术回退只涉及展示投影/对应测试，但恢复旧 min 会再次违背本次已确认规则，不应作为正常产品行为。当前仅待 root 组合编译与实际测试验收，无新增业务政策猜测。
