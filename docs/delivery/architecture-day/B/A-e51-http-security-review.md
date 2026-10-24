# B 对 A e51a81af 的独立静态审查

结论：未发现“安全链未挂载、JWT/AppToken被误mock、从body/query替换actor、已确定新增构造器错配”的源码证据。35条展开正确，可进入root受控CI作为HTTP principal边界门槛；实际编译/上下文/35请求执行尚未验证，不能据此业务结案。没有直接修改A测试，无必要的review.patch；本交付只新增此报告。

审查对象：公共仓库 `liboyang42-cpu/hengyin`，A SHA `e51a81af4d8604e8fcc8d49fbb4d6232e6fa5080`，parent `4197f6a6b11d62cb0a742aae73d7167c93114585`，tree `89beb9a89929c8f4d5abd593a0119d41ba8fb2a5`。该增量仅新增一个测试及A自己的三份交付文件。测试 `ApiAiThemeDraftHttpSecurityTest.java` SHA256独立核对为 `10d14b141fee5463e580cd5f572691c55bc3484e70833b41e7e654312ad88b69`，与A清单一致。B报告工作树基线为已冻结 `4ffa600cbb89abfc7e1e5a77d5d99c8aa8b10e3d`；未重做v8、未改A/C/D/root或生产文件。

以下行号均引用A e51a81af原文件，使用该SHA而非B本地旧源码。

## 实际装配与请求路径

| 检查 | 静态证据及判断 |
| --- | --- |
| 项目Security链 | 测试100–104显式加载真实SecurityConfig、PermitAllUrlProperties、JwtAuthenticationTokenFilter、入口/退出handler、AppTokenService、TokenService及ApiAiController；162–168获取springSecurityFilterChain并用webAppContextSetup.addFilters挂载。不是独立MVC绕开filter。 |
| filter/token非替身 | 164–165明确assert非mock；355–367的singleton mocks只替换cache/epoch registry、admin资料查询及业务边界，没有注册filter、token service或SecurityConfig替身。没有WithMockUser、request user后处理器、getAppUserId override或手工setAuthentication。 |
| URI与App分支 | 306–318产生下表四条真实mapping，显式servletPath；四路getRequestURI均以Constants.APP_URI=/api/开头。JwtAuthenticationTokenFilter:45–50因而走真实AppTokenService.getLoginAppUser/verifyToken，随后真实设置LoginAppUser principal。 |
| Security授权分支 | 生产SecurityConfig:166为anyRequest.authenticated；四路未列入permitAll，ApiAiController无Anonymous标注，171装JWT filter。App入口handler对/api/失败明确HTTP401。匿名/坏凭据用例同时要求HTTP401及body401；若遗漏filter，仅Controller的“请先登录”HTTP200错误不能过关。 |
| 身份来源 | Controller:172/188/217/237经resolveMemberId→BaseController.getAppUserId→实际SecurityContext内LoginAppUser；业务调用第一个参数不取请求memberId/ownerId。测试296–303在真实service invocation中核对authenticated LoginAppUser以及actor=principal.userId=principal.appUser.userId。 |
| cache及admin错入口 | 139–142按完整key存取，login_app_tokens:/login_tokens:分开；209–223先由真实admin parser证明admin JWT有效，再验证App路401及业务零调用。它证明admin parser有效及App隔离，没有执行后端URI的完整HTTP filter分支。 |
| 业务拒绝与正控 | 每个拒绝都verifyNoInteractions(requests,creator,insights,merchants)；八个规范DTO的A/B正控必须200/body200并只调用所属service方法，防止全拒夹具假绿。业务服务本身是mock，所以证明的是调用边界，非物理SQL/模型/额度账本。 |

| HTTP | URI | 下游方法/身份覆盖 |
| --- | --- | --- |
| POST | /api/ai/theme/draft/prepare | prepare(tokenActor,record DTO)；query memberId/ownerId伪装B；额外JSON memberId=B另有2选1安全断言 |
| POST | /api/ai/theme/draft/execute | execute(tokenActor,record DTO)；同上 |
| GET | /api/ai/theme/draft/request | readIntent(tokenActor,RID)，另1条prepareKey分支readPrepared(A,原key)；query/GET body伪装另账号 |
| GET | /api/ai/theme/draft/recovery | readRecovery(tokenActor,777)；beforeId仅游标，query/GET body不能改actor |

两个POST DTO本身均无memberId。241–254对额外字段只允许严格JSON拒绝400（须HttpMessageNotReadableException及业务零调用）或忽略字段后200且actor仍A；不是放行任意失败。GET DTO不接收body身份字段。prepare/execute记录构造分别为4/1字段，响应IntentView为10字段，RecoveryItem为9字段，与e51生产record一致。root已声明6c11修旧ApiAiControllerTest的9→10适配，本审查不重复修改、不将旧风险当新缺陷。

## 必须限定的验收范围（未认定生产漏洞）

1. **配置开关不在35条证明内。** AiThemeDraftRequestService在78声明 `ai.themeDraftIntent.enabled:false`；214/272的prepare/execute经644的requireIntentAdmission，禁用应拒绝AI_DRAFT_DISABLED；343/356/368的原事实读取/恢复不经该准入开关。此HTTP fixture把整个RequestService注册为完成的mock（364），且仅配置token属性，故没有执行enabled=true/false的真实业务分支。不能仅给这个mock fixture加一条property就宣称开关验收；需由A已有真实service/配置门禁或另行组合证据覆盖，保持“禁新增、原事实仍可读”的既定语义。本片可限定为HTTP principal gate。
2. **认证生命周期只覆盖所列分支。** registry固定epoch1/current=true（143–144）；内存cache没有TTL或序列化。EXPIRED_APP_SESSION实际修改同一缓存对象的absoluteExpireTime，确会经过真实verifyToken绝对过期拒绝；没有覆盖epoch撤销、有效JWT但cache缺失、真实TTL/空闲过期/序列化或registry故障。可引用已有AppTokenServiceSessionTest/JwtAuthenticationTokenFilterAppSessionTest各自证据，不把本片35扩大为这些验收。
3. **配置与运行环境范围。** 使用Authorization/Bearer、空contextPath、规范/api/URI及测试token时长；无代理前缀/其他header、CORS预检、RateLimiter aspect或完整Boot应用验证。EnableWebMvc/EnableWebSecurity真实装配不等于完整生产上下文。当前唯一catch-all链可静态确认选中JWT；166只assert“某条链含JWT”，若以后加多条链，可由A加强逐URI选中链断言，目前未见确证漏挂载。
4. **owner与零效果的证明边界。** A/B用相同RID证明传入actor不被替换，不证明SQL拒绝跨owner RID；所有业务效果服务都被mock，不证明真实quota/provider/SQL事务。身份拒绝时认证cache清理/epoch控制面调用允许发生，不属于业务零效果。README已大体限定这些范围，验收汇总也应保留限定。

## 35条与待CI

独立展开：匿名4；malformed/签名不符/JWT exp过期/App绝对会话过期×4路=16；有效admin错入口4；有效A/B×4路=8；POST body成员伪装2；prepareKey回读1，总计35（5个ParameterizedTest和1个Test方法）。错误签名使用不同的64字节synthetic key，JWT exp用真实过期claim；App/Admin token由真实service签发/解析，不依赖预放认证上下文。

本轮实际只读GitHub精确SHA、做Python hash/用例计数/符号与路径/DTO字段核对，写B报告并做git diff --check。没有运行Java/JUnit/MockMvc/JWT/DB/Redis/CI/模型或后端；没有完整类型编译结果。因此这里只能说“静态未见确证误装配/符号错配”，不能说上下文已启动或35已通过。

root下一步在含6c11和e51的受控同SHA上点名 `ApiAiThemeDraftHttpSecurityTest`，回读Surefire XML实际35/0/0/0、无context/JSON converter错误，并核对8个规范正控均成功及2个额外body字段用例的实际分支。若既有其他测试修复导致source变化，按最终组合SHA回读，不引用本报告替代CI。配置、会话撤销、SQL owner、quota等继续使用各自真实验收证据，不重做既有v8。

关键原文：[测试挂载](https://github.com/liboyang42-cpu/hengyin/blob/e51a81af4d8604e8fcc8d49fbb4d6232e6fa5080/chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiAiThemeDraftHttpSecurityTest.java#L162)；[JWT App分支](https://github.com/liboyang42-cpu/hengyin/blob/e51a81af4d8604e8fcc8d49fbb4d6232e6fa5080/chengyinhub-framework/src/main/java/com/chengyinhub/framework/security/filter/JwtAuthenticationTokenFilter.java#L45)；[真实准入开关](https://github.com/liboyang42-cpu/hengyin/blob/e51a81af4d8604e8fcc8d49fbb4d6232e6fa5080/chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/AiThemeDraftRequestService.java#L644)。
