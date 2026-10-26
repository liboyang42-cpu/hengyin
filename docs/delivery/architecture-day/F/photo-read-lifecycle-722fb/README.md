# Photo executeOnce：方法自有读连接生命周期 SOURCE

结论：仅收口 root 已批准的三处方法自有读连接。旧 case142 prepare 实际错误的内层原因仍 UNKNOWN；本片不是其已证运行根因，也不承诺下一运行必过。

Public parent：`722fb7c8fc629df90e9537f1dfd7ec1e200746db`（parent21cbd7772ba17640c3684766abc3fc3107081039）。此基线含 C 已交三份 test-only 源码及原配置9方法；F 不回退 seed/digest/prepare 诊断或 C 原方法，不以旧整文件覆盖 root 当前树。
独立分支：`codex/architecture-day-f-photo-read-lifecycle`。

## 最终且唯一的三个源码文件

1. `chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/PhotoCheckIntentService.java`：executeOnce 首次 owned、claim 失败的 read、saveResult 独立提交后的 read，均使用构造时已有的 independent.execute（REQUIRES_NEW）。保留 NEVER 注解及实际事务拒绝。只有三个调用表达式与生命周期注释变化，无新方法/接口/构造参数/bean/schema/依赖。
2. `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/PhotoCheckIntentServiceTest.java`：原16测试方法正文逐字保留，新增3个接线/异常传播控制和4个真实代理/manager/Mapper连接生命周期控制，最终23。原 mock 交易对象从 setup 局部变量提为字段，仍是原 state-unit double；资源控制另用真实事务管理器及代理，不以 mock manager 冒充资源证明。
3. `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/PhotoCheckIntentFreshReadMySqlIT.java`：原3场景/fixture正文逐字保留，新增一个实际MySQL/原Spring代理场景，最终4；证明没有caller预绑定时方法自有读不在provider入口/结果回读后留下DataSource或SqlSession资源，原CAS、nonce、reservation和一次provider/一次计划保持。

按 root 最终明确裁决，布局固定为 ServiceTest23、Fresh4、C config9原像不动；此前临时配置13布局没有进入补丁。没有复制执行同一控制。无新顶层/嵌套类或helper source。root shared POM/Python/workflow/registry不由F修改。

## 原合同与边界

- prepare/markApplied 原 MANDATORY、同意/媒体/完整owner/member/context/hash、nonce、reservation、claim CAS、input snapshot、result/UNKNOWN、回放与返回体判定不改。
- selectOwned 原 FOR UPDATE/useCache=false/flushCache=true 不改；三处原 read/owned 校验仍在真实短事务内完成，事务完成后才 provider。provider 仍不能有实际业务事务。claim/snapshot/result/markUnknown 的原独立写事务及次序不变，无吞异常重试、自动退额或新 nonce。
- public read 两个 overload 和 owned 的其它调用语义不改：AdvancedGameRuntimeServiceImpl.admitPhoto/commitPhoto 仍先原 session 锁，再原 intent current read；read 未被全局改为独立事务。
- 没改 transaction manager SYNCHRONIZATION_ALWAYS、池大小/等待、provider/HttpUtils、global/session 配置。外层 caller 若在进入 executeOnce 前已绑定 session/连接，本片不会释放它；FreshRead 原两个 empty-sync primer 与第三 RR/锁序场景继续保留。
- 无额外caller资源时，方法自有短读结束的 SqlSession/JDBC 连接应在 provider 前释放；claim失利/最终结果/终态回放回读不向caller的空同步遗留自身资源。这是待同源运行验证的源码合同。

## 配套控制 SOURCE

精确8个新普通无参@Test见 NEW-METHODS.tsv。

ServiceTest新增4个资源控制经过真实 ProxyFactory、TransactionInterceptor、AnnotationTransactionAttributeSource、DataSourceTransactionManager。默认 ALWAYS 明确保留；实际事务下 NEVER 真正拒绝且Mapper/provider零工作。外面另保真实NEVER空同步，从而能在服务返回但caller同步尚未结束时检查连接。

Mapper/XML/SqlSessionTemplate 与绑定/挂起/提交/关闭机制走真实框架路径；JDBC connection/statement/cursor 是有限 doubles。selectOwned 确实调用原 XML 路径以建立资源，再用明确的 fresh row model；写路径也委托原 mapped statement 路径，按固定受控状态建模。这不是MySQL隔离/CAS并发、真实渠道或经济配额交易证明。

四控制分别验证：正常结果的provider前/snapshot后自有连接0及无实际TX；claim失利后当前回读原IN_FLIGHT；返回degraded/UNKNOWN后原身份回放不重发；抛RuntimeException原对象传播、原markUnknown独立提交、UNKNOWN回读不重发/不saveResult。每借一连接观测框架close，opened==closed、live0、每连接一次close。固定串行单位场景peak1只描述这些方法自有连接，不声称全应用池/并发/整机容量。

另3个接线控制：失利路径精确3个 REQUIRES_NEW 边界而public read不增加边界；首次owned失败原异常/rollback及零claim/provider；最终回读失败不抹独立已存结果，后续回放不再调用provider。原16场景及全部断言保留。

Fresh新增场景使用既有创建/清理的真实task-owned schema、原实际Spring代理/manager/Mapper。provider入口及snapshot回调后核无实际TX/无本DataSource/SqlSession绑定；独立连接NOWAIT核无本intent行锁，核实际IN_FLIGHT及原reservation；最终回读/回放见原RESULT_READY，nonce/reservation不变，一次provider与原一次计划。它的provider仍为受控double，不冒充真实外呼。旧三个Fresh场景、schema/DDL/任务清理不改，无新增表或环境门。

## root 唯一 shared 接线 / caller

root已明确负责原已存在ServiceTest加入同一ONLINE一次source/class/XML证明：21inputs/最低40为root待接计划，不是本片运行结果；ONLINE配置9+PhotoUnit23逐方法精确核验，OFFLINE仍原probe。实际组合业务仍唯一case/HTTP240/1200秒/1CPU1GiB/原JVM预算。四个受影响Photo IT总声明按root原18→19，仅Fresh3→4；本F不修改registry/POM/Python。

生产直接caller只有AdvancedGameRuntimeServiceImpl.actPhoto；admitPhoto/commitPhoto的其它read/prepare/markApplied锁序不改。全部服务引用fixture分类见CALLERS.tsv。构造参数不变；实际构造者使用既有DataSourceTransactionManager/真实Spring wiring，RecoveryReadTest只调用未改public read，RestoreDispatchConsumersTest先guard拒绝。其它state/runtime单位persisted-result double不是资源证明。

## 原像 / 交付

C整个CombinedContextConfigurationTest.java逐字保留，SHA256 `c182d5dde6b9f5e29083f99557da9c90569a183e7cd65e3f02b50b57a22ec0b0`。
ServiceTest原16方法body120行逐字cmp，SHA256 `415c4aeace43f7639335f27b3685d44e32df0efef0c7f1cc47ed7ec22a7517c7`。
Fresh原3场景及fixture前154行逐字cmp，SHA256 `c361b70b96f5ee6ccb5531045a5ecb56258a996622d20efcb35df9f3f23e826e`。
作者后像见SOURCE-POSTIMAGES.sha256；owned-source.patch.b64含全部3源码增量。公共commit还含本说明与manifest；勿重复cherry-pick和apply同一增量。

仅Git diff/apply-check、逐字cmp、摘要、base64回读、有限新增行秘密模式检查。所有测试、parse/import、Java/Maven/DB/Redis/Docker、编译、HTTP及CI均NOT_RUN。旧case142实际内层首因仍UNKNOWN，不能把源码风险或C未运行的负控当原运行复现或通过证据。
