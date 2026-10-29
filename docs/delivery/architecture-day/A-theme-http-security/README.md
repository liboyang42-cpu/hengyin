# A：主题起草四路 HTTP 身份边界测试候选

结论：新增独立 `ApiAiThemeDraftHttpSecurityTest.java`，装配真实 MockMvc + Spring Security filter chain、真实 JWT/App/Admin token service、真实 ApiAiController。35 authored cases，**Java编译/JUnit/HTTP实际执行全部 NOT_RUN**。本环境没有启动后端、数据库、CI；不以静态检查宣称35通过。

## 身份、范围与现有 fixture

- 公共基线/开工 HEAD：`4197f6a6b11d62cb0a742aae73d7167c93114585`。独立工作树 `/workspace/hengyin-a-theme-http-security`，分支 `codex/architecture-day-a-theme-http-security`。
- 唯一新增测试：`chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiAiThemeDraftHttpSecurityTest.java`；其余新增文件仅本目录。全部生产、配置、依赖、既有测试、root/E/F/H文件和 A quota 冻结候选均未修改。可独立取 delta 后与 quota 组合，无源码重叠。
- 主要复用依据：`framework/config/AppSessionSecurityChainTest.java` 的 `@EnableWebMvc/@EnableWebSecurity`、真实 SecurityConfig/JwtAuthenticationTokenFilter/AuthenticationEntryPointImpl、真实 AppTokenService、受控 RedisCache/session registry，以及 `webAppContextSetup(...).addFilters(springSecurityFilterChain)`。
- `upgrade/UpgradeAuthorizationBoundaryTest.java` 已解释 MockMvc 显式 servletPath 对 LegacyAntRequestMatcher 的必要性；本片四路 builder 均设置真实 path/servletPath，不改 matcher。
- 另读 `framework/web/service/AuthenticationLogPrivacyTest.java`、实际 AppTokenService/TokenService/LoginAppUser/BaseController 以确认 JWT claims、session key、有效期与 principal 提取。仓库没有独立名为 AppTokenFilter 的类；App token 的真实过滤器就是 `JwtAuthenticationTokenFilter`。

## 最小真实装配

context 显式加载生产 SecurityConfig、PermitAllUrlProperties、JwtAuthenticationTokenFilter、AuthenticationEntryPointImpl、LogoutSuccessHandlerImpl、AppTokenService、TokenService、ApiAiController。没有用 standalone MVC、假的安全链或过滤器替身；没有 `getAppUserId` override、`@WithMockUser`、request user postprocessor 或手动设置 Authentication。

仅使用以下窄边界 doubles：

- RedisCache 用**按完整 key 区分 App/Admin namespace**的内存 map；session registry 提供受控 epoch。TokenService 的 SysUserMapper/SysPermissionService 返回有效 admin 资料。真实 token service 自己签名/解析/查会话/验到期/组装 principal；不 mock token service 或 filter。
- 业务请求服务 AiThemeDraftRequestService、Creator 接口及 Controller 其它必需业务依赖用窄 mock。受理时在实际 service invocation 中断言 SecurityContext 内确为认证 LoginAppUser，参数 actor 同时等于其 userId 和 AppUser.userId。拒绝时 verifyNoInteractions 覆盖全部业务边界。
- 完成的 mocks 通过静态 BeanFactoryPostProcessor 注册 singleton，避免 Spring 再向 mocked concrete RequestService 自动注入真实 mapper/事务管理器等。本测试不需要或注册数据库实例。此为局部测试装配，不新增生产抽象/依赖。
- UserDetailsService 是既有 fixture 的未使用用户名登录边界；JWT 请求不调用该 provider。真实 CorsFilter 与真实 LogoutSuccessHandler 仍装入链，测试不访问 logout。

所有测试签名材料是明确的 synthetic unit key，不读取外部秘密。mint token 时只绑定本地登录 request（remoteAddr=127.0.0.1，避免 IP 地址网络查询），完成后立即清理 request attributes；每例前后清理 SecurityContext/RequestContext。没有把伪造 principal 直接放进安全上下文。

## 用例矩阵（35 authored / 0 executed）

| 场景 | 展开数 | 边界断言 |
| --- | ---: | --- |
| 匿名 × prepare/execute/request/recovery | 4 | HTTP401 + body401，全部业务零调用 |
| malformed / 签名不符 / JWT exp过期 / App绝对会话过期 × 四路 | 16 | 同上；不是通过篡改过滤器返回值拒绝 |
| 有效 admin token 错入四路 | 4 | 先用真实 admin parser 证明该 token 本来有效、仅有 admin namespace 会话；App路HTTP401，业务零调用 |
| 有效A/B token × 四路，query memberId/ownerId及GET body伪装另一个账号 | 8 | 必须HTTP200/body200，service actor只能取实际token principal；只调用该路唯一业务方法，无Creator/其它调用 |
| A token，prepare/execute body额外memberId=B | 2 | JSON严格拒绝400且业务零调用，或正常解析200但actor仍A；其它状态不接受 |
| A token按prepareKey回读，query/GET body owner=B | 1 | readPrepared(A,原prepareKey)，不能变B；无执行/写入口调用 |

body memberId 不属于任一输入 record DTO。测试允许真实 MVC converter **拒绝未知字段400**或**忽略字段后仍使用A**这两种安全行为：400必须有 HttpMessageNotReadableException 且业务零调用；200必须 body200 且严格验证actorA。没有为测试修改 JSON/鉴权规则。相同四路的8个规范DTO正控必须成功，因此“所有合法请求都拒绝”不能让该安全测试整体通过。

prepare/execute 使用实际 POST；request/recovery 使用实际 GET。recovery 验证 beforeId 只是游标，owner来自实际principal。request 同时覆盖 requestId 与 prepareKey 两个选择分支。

“拒绝零写/额度/模型”的证明限于**通往这些效果的业务服务入口没有被调用**，不是新增与调用链无关的 mock mapper/model 来冒充物理观察。真实 token 签发、过期会话清理属于认证控制面，允许其受控 cache 记录变化；不混作业务写。未运行真实 Creator/quota/模型/SQL。业务角色校验、RateLimiter aspect、E22实际SQL owner隔离、完整Boot应用和微信链路不在本片验收范围，不能复用本片数字冒称已验收。

## 实际检查、编译前置风险与交付

`python3 docs/delivery/architecture-day/A-theme-http-security/verify_static.py` 只运行 Git/Python：范围/受保护文件 hash、35例来源展开、实际组件装配、禁止认证替身/override、词法括号配对（非Java语法/类型检查）、签名测试key长度、git diff --check。输出 `verification.json`。

只读发现基线已有编译适配风险：`AiThemeDraftIntentView` canonical constructor 现为10字段，而旧 `ApiAiControllerTest` 仍有9参数构造；没有9参数 overload。已向root报告，本片不改旧测试。新测试全部按当前10字段契约编写。需root统一处理原测试适配后编译/运行，不能将此已有风险归作新35用例断言失败或靠改生产constructor造绿。

所有Java/JUnit、MockMvc实际请求、JWT实际运行、Redis/MySQL、CI都未执行。静态源码装配不保证上下文已成功启动，真实执行门槛由root完成；没有 dispatch、启动服务、部署或改安全规则。

交付仅新文件，无迁移/数据影响；技术回退为移除独立测试与本目录，不触碰生产。静态脚本绑定本独立候选的4197父源，并非root联合分支的全仓gate。
