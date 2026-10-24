# A：主题草稿 v2 Creator / Gateway 原计划切片

结论：**指定5个生产文件的原计划规划/校验/消费已编码，新增两套专属unit源码，原Creator37项回归文件逐字保留。Java未编译、unit未运行；不能称通过。** E的Plan/意图、root Controller/Page/维护与正式联合验收仍须成组接入；本候选单独不关闭旧直调、不证明耐久claim或全链路至多一次。无真实模型、DB、Redis、DDL、CI dispatch或部署。

## 基线与独占范围

- 公共仓库 `https://github.com/liboyang42-cpu/hengyin.git`；生产parent **`e683b5a0ebc44cc217ac75e378c271fd96c80ae0`**，已从origin显式fetch并建立独立工作树。
- `/workspace/hengyin-a-theme-prepared`，分支 `codex/architecture-day-a-theme-prepared`；创建时干净，未改其它工作树/冻结候选。
- 全文读 E `ee96ffe45bab6126136e1c5582be53601c4f6fc3:docs/delivery/architecture-day/E/THEME-DURABILITY-02/CONTRACT.md` 及其parent8495的PROPOSAL；本实现按最终CONTRACT，旧提案正文不改。
- 仅五个生产文件：`IAiCreatorService.java`、`impl/AiCreatorServiceImpl.java`、`ai/AiThemeDraftPlanRejectedException.java`、`ai/AiGatewayService.java`、`ai/impl/AiGatewayServiceImpl.java`（前缀 `chengyinhub-system/src/main/java/com/chengyinhub/business/service/`）。
- 专属unit：`chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/AiCreatorPreparedThemeDraftTest.java`、`.../service/ai/impl/AiGatewayPreparedThemeDraftTest.java`。仅本A交付目录。
- E Plan/DTO/持久意图、root Controller/Page、F transport/client/admission、C其它玩法、CI/config、依赖与schema均未改。`source-evidence.json`记录原/新文件hash、受保护调用者及旧测试hash。

## 失败机制与调用者

原Creator在私有预占方法内随机nonce并取当日key；Gateway通用generate也会自行消费/选模型。若durable执行者调用这些旧入口，不能保证消费DB冻结的原号、原池、原日。若校验配置后再重新选client/额度上限，则可能校验A执行B。若玩家已预占之后把迟到检查故障包装成local preflight，则会虚报零效果NOT_SENT。原Gateway日志还会摘要保存输入/输出，不能用于v2短期正文合同。

全生产Java调用者搜索原文见`baseline-callers.txt`；不把声明计为额外入口：

| 现有调用链 | 处理 |
| --- | --- |
| `ApiAiController.themeDraft`旧无号分支 → Creator.themeDraft；`AiThemeDraftRequestService.submit`旧rid路径 → Creator.themeDraft | 本片不抢改；E/root必须关闭旧执行许可，再接新prepare/execute。保留旧方法是为既有调用者/37回归成组切换，不授权新v2绕过durable claim |
| `PhotoCheckIntentService.prepare` → Gateway.planQuotaReservation；`PhotoCheckVisionService` → consumePlannedQuota | 原公有签名保留，额度/原号规则不改；public消费与prepared复用同一私有消费实现 |
| 新Creator.planThemeDraft / themeDraftPrepared | 本基线暂无E Plan或v2持久caller；按合同新增接口，未来只有E已提交claim赢家调用prepared |
| 新Creator.themeDraftPrepared → Gateway.generateThemeDraftPrepared | 原计划一路传递；不调用旧随机themeDraft/generate或consumeQuota |

只找到一个IAiCreatorService实现、一个AiGatewayService实现；现有测试含Gateway子类/Mockito替身，但没有发现手写implements接口的第二个实现。新接口需与E Plan联合编译，不提供假Plan或兼容缺字段fallback。

## 精确合同与实现

- `planThemeDraft(memberId, nonce)`：当前会员必须存在、ID一致、status=1/delFlag=0（沿用现有RoleService会员有效性条件），角色由既有roleService解析，不能缺会员降为player；异常映射CHECK_UNAVAILABLE而不猜撤权。只做身份/本地配置规划；不碰配额计数、quotaRecovery、审核或模型。
- **Plan.pool逐字是`player-draft` / `gen`**，与现有quotaRecovery池名一致。玩家原counter=`ai:theme:draft:player:<member>:<BJday>`；原receipt=`ai:player:theme:draft:reservation:<counter>:<nonce>`。其他角色复用gateway.planQuotaReservation原token，严格校验counter=`ai:gen:limit:gen:<member>:<BJday>`、receipt=`ai:gen:reservation:<counter>:<nonce>`。两池reservation都为`counter|nonce`。
- nonce严格32位小写hex；两个配置hash严格64位小写hex；member/role/pool/day/counter/nonce/receipt/reservation/截止逐项校验。截止不晚于原BJ日次日零点，原日不能在未来；已过原日/截止拒绝。没有重选池、换nonce或换到新日。
- `themeDraftPrepared(req,memberId,plan)`：原请求不可缺，先检查不处于ambient事务，拒绝仍绑定MyBatis SqlSession的会员读取，再核对完整计划、当前会员/角色及两份配置指纹。**原角色只用于比较，不用于重选池**。所有local preflight code都在第一次quota/审核/model之前；成功准入后复用原生成/解析/旧收费finally。
- 玩家`consumePlayerThemeDraftPlan`只用原key/receipt，quotaRecovery.prepare→原Lua→reserved；不消费gen。**每日counter TTL始终到原BJ日午夜，不用可能更早的executeBefore缩短TTL而重置每日次数**。gen复用consumePlannedQuota的原恢复/Lua/补偿实现，显式传入已验snapshot.limit，避免执行时重新读另一个上限。
- Gateway只为theme_draft新增prepared：当前本地路由只解析一次、保存实际client引用和limit，校验后实际调用同一个引用，不在审核后重新选client。生产OpenAiCompatible client配置字段为final，模型指纹读取其实际`configuredModel()`；受控其它LlmClient替身用`client:<name>`，不冒充真实模型身份/HTTP验证。原player receipt的INPUT_REVIEW_STARTED/MODEL_STARTED阶段也沿原号保存，UNKNOWN不留成可自动误补的PRE_RESERVE。
- 在准入后发生撤权/跨日/配置变化，不声称已实现严格inflight截止；本次只用已准入的原身份和已验快照，不切池/换号/重发。E的durable claim负责首次执行许可证，Creator/Gateway本身不构造新的业务幂等平台。

### 配置与日志

Creator指纹：版本、SYS_THEME_DRAFT的hash、解析政策版本、玩家池/3次上限、BJ日口径、既有退额政策及原Lua指纹。Gateway指纹：路由模式/实际route、client类及name、真实configuredModel（或受控client:name）、非秘密装配baseUrl/enabled/timeout、gen上限、审核分段参数、quota/typed disposition政策版本及原Lua指纹。

只白名单读取非秘密字段，**不调用getApiKey、不序列化config/providerProps，不保存credential或credential hash**；带userinfo/query/fragment的base配置不能进入指纹。现有client.requireReady只做本地就绪检查，不发请求。qwen/deepseek实际文本配置沿现有LlmClientConfig的AiModelConfig装配来源，F文件不改。快照保留选中client及quota limit，后续配置/bean表变化不重新选择；本片不声称支持任意运行时可变自定义LlmClient内部配置。

prepared的AiGenerationLog仅存`sha256:<digest>` inputBrief/outputBrief及trace/固定元数据；模型未知错误固定码，不永久落原idea/prompt/output或异常message。Creator prepared JSON错误只打trace/固定码。共享配额补偿失败诊断也去掉raw Throwable，只保原nonce/固定码，防恢复异常把新路径正文带回日志；原历史审计不删除。旧通用路径的输入/输出审计既有行为保留，待root关闭旧入口，不把它冒充新v2正文合同。

## 故障 / 经济效果边界

| 条件 | local/provider事实及原额政策 |
| --- | --- |
| Creator准入前权限/截止/config/完整性或读取异常 | 精确AiThemeDraftPlanRejectedException.Code：AUTHORITY_CHANGED / PLAN_EXPIRED / CONFIG_CHANGED / PLAN_INVALID / CHECK_UNAVAILABLE；quota/审核/model零调用；E可保存本地NOT_SENT事实 |
| gen Gateway自己的effects前检查失败 | 同上述窄preflight；未进quota/审核/model |
| player已预占后，Gateway第二次配置/会员/截止检查失败 | 普通AiServiceException，不冒充零效果PlanRejected；Creator按原未生成失败政策补偿原receipt。post-effect普通异常不包装CHECK/NOT_SENT；E不得从原因链/文字猜provider阶段 |
| SDK专用AiProviderNotSentException | 原gateway已审受信catch，gen只补原receipt；player仍由Creator补其独立原receipt；不释放durable request身份 |
| 普通异常/包装NOT_SENT/超时/空模型回复 | 保持AiProviderOutcomeUnknownException，原receipt不退款、不重发；不按异常名字/HTTP自行重判 |
| 已收到模型结果后的审核/retain故障 | 保持AiProviderResultReceivedException；gen原不退额。**player仍沿旧Creator政策：无有效稿且非明确输入拦截则退原receipt**，没有擅改成按结果一律收费 |
| 有效稿已收到后Creator自身retain失败（包括错误抛出NOT_SENT/PlanRejected类） | prepared明确包成RECEIVED，保留原占位，不退款、不伪报local NOT_SENT；输入拦截的retain故障只给普通故障，不包装零效果事实 |
| 有效player稿 / 明确输入拦截 | 原player有效稿/INPUT_BLOCKED保留占位；无效稿/原普通失败退额；generic provider UNKNOWN例外保留。原政策不变 |

旧themeDraftQuota展示口的历史min(player,gen)逻辑本片不改；实际player不消费gen在原Gateway已存在，不是本轮新增计费方向。任何产品计费调整仍待root/用户裁决，本候选没有替其拍板。

## 专属测试与实际验证

- **新增源码预期66个展开用例**：Creator43、Gateway23；实际执行0。含纯规划、原token/原nonce、完整性11项、当前角色/会员变化及读取异常、原日/截止、两份config、ambient事务/已绑定旧SqlSession拒绝、原player每日TTL、合法首次消费、NOT_SENT/UNKNOWN/RECEIVED/输入拦截/解析原政策、配置在quota/审核间变化仍用原client/limit、player不扣gen、post-effect不伪装preflight、日志无正文/Throwable。
- 原`AiCreatorServiceImplTest.java`全文SHA256=`cfe5a151b39ce394531b6eb6fe5856cec60d57cc98060a633617a90ed571638c`，35个@Test+参数化false/true两项=原37；本候选未删、未skip、未修改其fixture/正文/预期。旧Gateway suites及F transport原文件同样未改。root此前37结果不能当成本候选37已通过。
- 实际只运行Git/rg/Python静态核对、接口/Plan合同文本核对、Java词法括号检查（**不是Java语法/类型编译**）、原方法/旧测试保存核对、diff/有限秘密格式扫描。脚本输出`source-evidence.json`及`verification.json`，不生成假JUnit回执。
- 本源**缺少E拥有的AiThemeDraftPlan.java**，故本片独立源码不是可单独编译/部署的完整v2。需E按合同创建record（11个字段，精确顺序/类型）；未私建stub或修改其目录来绕编译。所有Java/真实MySQL+Redis/HTTP/模型/TTL/并发claim及回滚由root组合后统一验证，无本地Java/DB/CI。

## 集成余项与回退

root先组合E Plan/DTO/intent与本7个源码文件，核对pool逐字一致，跑联合生产/测试compile、原37+新66及既有Gateway/typed suites。E提供MySQL claim/首结果事实/24h Redis正文/清理与真实故障并发证据；root关闭所有旧直调入口，接Controller/Page/feature与维护caller，F transport原审核保证不能由本片静态检查代替。未有这些证据不能启用或宣布v2闭合。

本片无schema/依赖/配置迁移，不执行DDL；只撤未启用代码可回退本切片。只要E已有durable受理行，不能回到会释放身份的Redis-only执行、删原计划/回执或绕原号补偿。A95后台裸POST仍P1未修，资金政策/退款算法/多人固定费等原待决事项均不因本AI切片关闭。

静态脚本仅用于本固定A隔离候选；联合E/root源的合法变动需按其真实父提交独立核对，不能修改冻结脚本豁免，也不能把其基线不匹配当作Java业务断言失败。

完整7源码/测试delta以`production-and-tests.patch.gz`原字节gzip交付（标准库、mtime=0），解压后可对生产基线`git apply --check`；也是本分支相对e683的同一源码delta。打包初版raw patch的空白上下文被diff检查视为尾随空白，最终改用gzip保留原字节并重新检查，不放宽gate或改生产源码。
