# E Photo MySQL IT Spring 夹具最小修正

结论：仅修正 PhotoCheckIntentMySqlIT.connect 中已完成依赖的注册方式；保留真实 IntentService 构造器注入、真实 Spring AOP/事务管理、真实 Mapper/MySQL。生产实现、schema、10 个业务用例、16 个 unit 用例和所有拒绝断言逐字不变。

冻结 parent：`493c7dead4a88ece75d3163c1726a175ce862020`。本次另建 codex/architecture-day-e-photo-spring-fixture 分支，未移动旧 ref。联合候选 `361ef3b23a94c6be2f73f74134a40d827f2ee3da` 的源码编译已由根报告通过；本次不怀疑或修改接口。

根报告的真实 CI40022376557：C AdvancedFingerprint5 PASS；E Photo10 在 @BeforeAll connect:97 初始化失败，业务实际 0/10，Surefire 为初始化 1 error、无 skip，不能计为 10 个业务断言已跑。原因是 registerBean(Class, Supplier) 仍会让 Spring populate Mockito 具体 class 上的 @Autowired 字段：Vision → Media mock → 不存在的 AppMediaAssetMapper。原 supplier 返回 mock，并不代表跳过该 bean 的 Spring 初始化。

修正沿用已有 RouteGameRuntimeContextTest.registerDependency：对已经构造的 manager、真实 SqlSessionTemplate Mapper，以及 Gateway/Vision/Consent/Media 四个 mock 使用 context.getBeanFactory().registerSingleton。由此它们以原对象提供给容器，不再对具体 mock 做字段注入。只有实际 PhotoCheckIntentService 通过普通 registerBean 交由构造器注入及事务代理创建；@EnableTransactionManagement 仍保留。没有手工 new IntentService 替代真实事务代理，也没有增加假可用 AppMediaAssetMapper 或替换测试所断言的对象。

没有把生产 @Autowired 改成可选、没有允许空依赖、没有增加默认凭据或 skip；沿用 493 明确的 AI MySQL PORT/USER/PASSWORD、随机独占 scratch schema 和回读/清理规则。没有改变业务状态机、receipt 校验、provider/额度零影响断言。独立 RR 风险与 scope 审查仍见 E/PHOTO-REVIEW（公开报告 79fec29c），本补丁不冒称已修复这些问题。

实际仅运行 Python 检查：除 connect 注册块外测试文件逐字相同、@Test 仍为 10、16unit/其余 E source逐字相同、4 个 mock 均以原变量直接 registerSingleton、IntentService 仍 registerBean；secret-file gate、git diff --check 及有限敏感模式扫描。未运行 Java/MySQL，未执行 DDL，完整 gitleaks 待根复扫。

根应将本单独 fixture delta 应用到联合依赖候选，重新执行 Photo10；必须 10 条实际业务用例、failures/errors/skipped=0，并保留实际 surefire/独立真库回读。16unit、11unit、Spring8及最终 C Photo 编排另按根计划验证，不能由本次夹具修正或原初始化失败替代。
