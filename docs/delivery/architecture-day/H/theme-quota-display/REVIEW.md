# H：Theme Draft 额度展示与实际消费契约窄审

结论：**有代码证据的契约风险，业务口径待定；不据 min 这个词直接裁定哪条额度政策正确。** 当前 GET-like quota 查询（实际为 POST）会受 gen 剩余额度限制；同一 player 实际主题起草只消费独立 player-draft，不消费或先检查 gen 剩余额度。Simple Page 把展示的0作为禁止新起草的条件。因此“既往合法 club 创作耗尽 gen → 合法降为 player → player 独立额仍3 → 查询显示0、Page不发；直接调用执行链却可能通过额度准入”的证据链成立于所审源码及明确前提。**后端 Java/Redis/真实角色变更未运行，不声称已实际生成、扣额或绕过授权。**

## 身份与交付

- 公共基线：2ec68899099a1e250df0949a8383bf85b5d3ffe0。
- 主审源码：**91962c94af463cbfe9b4d4406f9d88d269b097e4**，tree cba1815abaae3077103b9a9b01d27e0e9e4c81d0。
- 后续源 **2b4024cdcb185c56ee05126e02b9a0c79fcff34a** 的 Creator/Gateway/Controller/原Creator测试/RoleService/Page 相关 blob 与919完全一致，未拿新增 journal 逻辑当额度修复。
- A 尚未应用候选 **40fdffaa345c4c239553f54f36a4ae9147191a1b**（公开 codex/architecture-day-a-theme-prepared）：已读其 README、实际 Creator/Gateway 文件及原测试；不是只引用协调者转述。A 的 late gen marker 新源不在此窄审范围。
- H 开工 /workspace/hengyin-h-review，codex/architecture-day-h-review，HEAD 8277684d21b39726ac2d8714eff1d450c77af1f9，clean。仅新增本目录报告、证据及独占 Node 场景；不改 A/root/Page/旧测试，不重跑 H12。
- SOURCE.json 记录原文件、ref/blob、919/2b绑定及原37测试保存情况。无实现、schema、配置、平台或额度政策变更。

## 实际调用链：展示和执行读的不是同一约束

**展示：**

1. ApiAiController.themeDraftQuota:260–275 解析认证 member、调用 gateThemeDraftRole，然后直接调用 Creator.themeDraftQuota；不在 Controller 新增共享每日余额检查。
2. AiCreatorServiceImpl.themeDraftQuota:168–188：
   - club/merchant：limit=dailyQuota(theme_draft)，remaining=Gateway gen 剩余。
   - player：limit=3；playerRemaining=max(0,3-playerUsed)；gatewayRemaining=max(0,Gateway.remainingQuota(member,theme_draft))；remaining=min 两者。
3. AiGatewayServiceImpl.remainingQuota:281–318、rateGroupOf:451–468：theme_draft 默认归 gen，读取 `ai:gen:limit:gen:<member>:<BJday>`，dailyLimit 默认配置20。key 不含当前 role；这不是 player 独立 counter。
4. Simple Page loadAiQuota:99–110 把 limited/remaining 写进 aiQuotaLoaded/aiRemaining；generateAiPlan:124–130 在**无须先恢复的 pending**时，已加载 remaining<=0 就返回、不发送新起草。这一分支已实际跑窄 Node 用例，见下文。已有 pending 的只读恢复分支先于零额检查，不把“0禁止新动作”扩大成“0禁止回读”。

**当前实际执行：**

1. ApiAiController.themeDraft:154–175 的 gateThemeDraftRole:100–115 允许有效 player；它不要求 gen daily remaining>0。原 requestId 路径经 AiThemeDraftRequestService.submit 再到同一 Creator，没有 gen quota 汇总闸。
2. Creator.themeDraft:102–161 对 player 只调用 reservePlayerThemeDraftQuota；其 :601–633 使用 `ai:theme:draft:player:<member>:<BJday>`，独立上限3、独立原 nonce/receipt。成功 resp.remainingQuota 直接取本次 player reservation.remaining。
3. Gateway.generate:127–132 明确 `playerThemeDraft ? null : consumeQuota(...)`；player/theme_draft 跳过 gen。后续审核、模型、stage/retain 不增加另一个 gen reservation。实际有效非 mock 稿、审核和其它前提都满足时，gen=0不会在这条分支挡住模型额度准入。
4. Creator.role 从当前持久 member 解析，不是用前端 debug_user_view 改头冒充。本文不涉及缺会员 fallback 或其它已另行治理的角色问题。

**A40fd 原计划路径：**

- Creator.planThemeDraft:114–137 依据真实当前角色选择 player-draft/gen；themeDraftPrepared:142–164 校验当前角色/原计划等，executeThemeDraft:166–189 对 player 仍只消费 consumePlayerThemeDraftPlan。
- Gateway.generateThemeDraftPrepared:138–181 只有非 player 才 consumePlannedQuota(gen,原reservation)；player 的每日 gen 剩余没有另一个检查。
- themeDraftQuota:252–272 仍保留旧 min，README:60 明确本片不调整。配置指纹包含 dailyLimit 配置不等于检查 gen 当前余额。
- **先前 club 身份规划的旧 plan 在降为 player 后应按角色不一致拒绝**；本反例必须是降级完成后的新 player 动作/新计划，不借旧 club plan 跨角色重放。
- A片尚未应用，E/root durable claim/feature/Controller未在本轮联合执行；不能把此源码推导称“v2线上准入已实证”。

## 上游共享限制、恢复、角色变更的排查

找到了真正存在的其它限制，但没有把它们误称为这条共享每日总额：

- Controller 原 theme/draft 的 @RateLimiter 是 MEMBER **60秒20次**，不是每日 gen20；场景选择已有请求均终态，并处于该短窗之外。F 的并发/超时 admission、模型启用/配置/审核、player本身3额仍会约束执行，不能因此宣称任意请求都成功。
- gen 默认20是配置默认示例，不是生产观测。以下将 gen 日上限记为 L；只要求原 gen counter 已达实际 L、同一北京时间日期且未过期。
- AiQuotaRecoveryService 按原行 counter_key/generation/receipt 进行保留或补偿，没有按角色切换把 gen 转成 player-draft 或做跨池每日总额汇总。场景使用已终态 RETAINED 的既往 gen 用量，排除后台待退款带来的随后余额变化。两池分别按原身份恢复，不授权为本问题清零或迁移任何计数。
- RoleServiceImpl.changeRole:53–85 保留原 memberId，只更新 canonical role/userType 及能力投影，无 Redis AI counter 清理。合法 club→player 真实 caller 包括 ClubController.audit:345–390（有后台审核权限、驳回后无其它在营 club）和 ClubGovernanceServiceImpl.reconcileFormerOwnerIdentity:297–312（合法转让后无其它在营 club）。
- 因此不需要假设“普通用户任意切身份”：**一条已授权且业务条件满足的 club 降级**就足以形成同 member/day 的合法前置状态。CapabilityServiceImpl.revoke:35–45 亦可回收当前角色为 player；本文不以任意公开 revoke 权限作保证。
- 对商家：gen也按同member计数，RoleService依法变更后相同算式适用；但所读 MmsMerchantServiceImpl 的资料状态变更并不当然降为 player，**不把商家停用/前端切视角当成真实角色变更**。已有club路径已经足以成立场景。

## 现有材料为何不足以直接选择政策

存在双向的、需要一起收口的历史证据：

- 原 AiCreatorServiceImplTest.playerThemeDraftQuotaReportsZeroWhenSharedGatewayPoolIsExhausted:338–353 用 mocked Gateway remaining=0、playerUsed=0，明确要求 limit3/remaining0。这只固定展示结果，没有执行真实 Gateway 或证明共享总额准入。
- 同文件 playerThemeDraftCountsOnlyCompleteRealDraft:220–252 使用独立 player reservation，配合 Gateway 模拟成功；第四次 player 请求在模型前拒绝有原正控。它也没有把 gen耗尽与真实Gateway组合。
- AiGatewayServiceImplTest.playerThemeDraftSkipsGenericQuotaUntilDraftIsValidated:155–163 明确要求 player/theme_draft 成功时 reserved.size=0；真实 generate 方法结束后没有补扣 gen。“UntilDraftIsValidated”测试名不证明存在一个事后 gen 扣款步骤。
- 原 Creator 文件是35个 @Test + 参数化 false/true两例 = **37 authored cases**；919与A40fd原文件逐字相同，SHA256 `cfe5a151b39ce394531b6eb6fe5856cec60d57cc98060a633617a90ed571638c` 与 A README:65 一致。本轮没有运行37、没有改变其成功预期。
- 919 的 docs/delivery/bugfix-20261023/BUG-STORY.md:29 将 B:P2-042 的 min记为修复，并描述“gen耗尽时玩家实际生成不了”；源码中的展示注释也这样写。**这段因果描述与目前 Gateway player分支不符**，不能反过来把文字当作已经实现的闸。
- AiModelConfig.dailyLimit 注释将 theme_draft 等列为“创作类共池”；Gateway 明确注释“玩家草稿由Creator三次独立池计费”。A README:60 明说旧展示与实际不扣gen均保留，政策仍待root/用户裁决。
- GitHub搜索只用来发现上述历史材料（部分搜索索引绑定公共2ec），随后关键文档按919读取核对；未找到可消解矛盾的明确新产品决定。没有声称穷尽所有线下决定。

故本项记录为 **H-QVIEW-01：有代码证据的契约风险 / 业务口径待定**，不定为已发生多收费/双花或未经授权越权，也不将任一旧测试当成产品裁决。

## 最小场景与可核验边界

所有后端行均为源码推导，未运行 Java/Redis/模型；无未决旧请求，角色和会员均合法，时间/配置/审核等前提满足。

| 场景 | gen剩余 / player剩余（执行前） | 当前quota展示 | 当前执行链的额度行为 |
|---|---|---|---|
| club正常耗尽日额 | 0 / 尚未用player池 | 0（club走gen） | club继续起草被gen日额拒绝 |
| 同member合法降为player，同BJ日 | 0 / 3 | limit3/remaining0 | player独立池尚可占第1次；gen不因该调用新增扣额；有效稿会回remainingQuota=2 |
| gen尚余1后合法降player | 1 / 3 | remaining1 | 首次有效player稿回remainingQuota=2；gen仍余1；再次查询又回min(2,1)=1 |
| player已独立用满 | 任意 / 0 | 0 | 独立player第4次在模型前拒绝；该防线不受本项质疑 |
| 再依法变为club | 仍0 / 任意 | 0 | gen原计数未重置，仍拒绝club消费 |
| player原plan与当前role不符 | 任意 | 无关 | A40fd应AUTHORITY_CHANGED；不属于本反例的合法执行 |
| Page无pending且已收到limited/remaining0 | 响应已给定 | aiRemaining=0 | **Node已实证不发新起草请求**，不是后端准入实证 |

第三行说明不仅是0边界：GET的“剩1”和成功回包“剩2”也可能在没有退款/新日的情况下来自两种不同口径。只加 `genRemaining>0` 检查仍不足以使 min代表可实际完成的次数：若 player不消耗gen，gen余1不会随其三次player消费递减。

供 root 获准环境组合的**非政策断言 witness**（本轮不写入旧suite、不运行）：

1. 建同一有效member，先在合法club角色完成 L 次gen计数并确认原gen记录终态；走真实授权降级 caller，读回role=player/memberId不变。
2. 同BJ日、playerUsed=0，调用真实 Creator.themeDraftQuota，记录 limit/remaining及两个原 counter。
3. 以新原动作/新player计划经真实Creator+Gateway执行，在本地受控LlmClient给合法非mock输出；记录是否进入模型、原player nonce/receipt和gen/player计数读回。没有真实供应商调用。
4. 同时核对剩1例，成功响应remainingQuota及紧接quota查询的差异；分别给两种政策的真实验收预期，由root/用户先选择，不能改旧断言造绿。

不把 Redis mocked getters、Python算式或Node响应注入冒充这个组合验证。本轮只独立运行下面的Page窄场景。

## 必须一次收口的决策，不在此实施

root/用户需要明确：player主题起草的“每日可用次数”究竟是**独立3额**，还是**共享gen总额+player上限**，或另有明确只展示估算但不作为许可的语义。H不替其选择。

- 如确认独立池，展示/成功回包/新Page禁用条件应与实际独立池口径一致；不能继续让无关旧gen计数假锁新player。
- 如确认共享总额，需要具体定义player是否同时消耗gen、原预占/失败补偿/UNKNOWN/跨日/角色切换如何保持原身份；不能仅改展示或加非原子余额检查，更不能临时双扣、清旧gen或放松恢复fence。
- 若保留min作某种保守估算，必须明确它是否允许硬禁用Page及如何解释成功后剩余额度增加；目前“剩余可用次数”的文字/分支不能自行解释出该产品合同。
- 决定后同时修改真实执行准入、quota响应、成功remainingQuota、Page以及旧37/新prepared相关测试，保留所有原UNKNOWN/补偿/身份负例。当前A只保留旧逻辑，不代表已批准新计费政策。

## 本轮实际执行、交付与限制

独占 quota-zero-page.review.test.cjs 加载真实Page（blob377072bfc7e1fb1f19d01e95fe5aa5cc24b63b2f，919/2b一致），使用本地 wx/app doubles 注入limited=true/limit3/remaining0：**Node v24.19.0，1 test PASS，0 fail/skip/cancelled**。它只证明Page新动作分支的阻断；不计算或执行后端quota，不登录/上传/访问微信或网络。RUNTIME.json记录命令、输出hash和边界。**未重跑H12。**

H只做源码/静态身份与上述1条轻量Node；Java编译、原37/JUnit、Redis/MySQL、真实角色降级、A原计划完整服务、CI及供应商均未执行。没有生产修改、数据清理或迁移；回退本目录只影响审查材料。本报告不以单个min表达式定Bug、不宣布新计费方向获批，也不关闭已有AI身份/资金/B支付等未决项。
