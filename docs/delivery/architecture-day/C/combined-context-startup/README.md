# 组合 Spring context 装配窄修复与诊断合同（SOURCE / NOT_RUN）

父提交 `f3c231e43b62ac7b7c9725fa533da05099fa48bd`。本轮实际内层 Bean/配置异常没有原始证据，故 **actualPriorStartupCause=UNKNOWN**。不把本包的静态配置矛盾写成原运行已确认首因，也不重做已通过的 provider/booter/cgroup/cleanup 接线。

## 实施的可证必要修复

原组合 `Initializer:43-50` 复用 Generic initializer 后，仅设置全局 Druid `maxActive=3`。原 `application.yml` 的 active profile为druid；`application-druid.yml:20-24` 仍是 `initialSize=5/minIdle=10/maxActive=20`。`DruidProperties:15-22,54-59` 把这三个实际配置按顺序装入真实 DruidDataSource；不是一个未被引用的配置字段。

项目 `pom.xml:22` 固定Druid1.2.28。已读取同版本官方 primary源：

- [DruidDataSource.java](https://github.com/alibaba/druid/blob/1.2.28/core/src/main/java/com/alibaba/druid/pool/DruidDataSource.java)：`:718-727` 在init拒绝 `maxActive<minIdle` 和 `initialSize>maxActive`。SHA256 `9389786151c762e99b60b1bd35e592965a37aa9299990c4384219b0236128ddc`。
- [DruidAbstractDataSource.java](https://github.com/alibaba/druid/blob/1.2.28/core/src/main/java/com/alibaba/druid/pool/DruidAbstractDataSource.java)：`:1119-1132` 的minIdle setter校验依赖inited状态，`:1149-1158` 的initialSize setter也区分初始化状态。因此没有私有栈不能断言原错误一定在setter或一定在init的某一行。SHA256 `decb46f5062725e502368668ba48111c996aa1197b844bd4543015b48968f4c2`。

只在**本组合 initializer**补 `initialSize=0/minIdle=0`，保留 `maxActive=3/maxWait=5000/socketTimeout=5000`。这五项放入同一package内的 `Initializer.configureBoundedPool` 以供直接控制调用。原Generic入口、生产Druid配置、slaves条件、Redis15/原端口、密钥、scope/actor及所有真实业务owner不改。

原唯一组合case增加两条实际 `masterDataSource` 读回断言，原3连接断言仍在。原业务case数量仍1，原150秒预算、支付事实/错误金额零变化/原回执重放、UNKNOWN、不重复外派、通知正向、全部240HTTP及清理断言保留。`CombinedControlledProviders`、Transport、Storage、原Generic/Freeze36正文未修改。

## 源码与装配/投影清单

`context-contract.json` 已给出真实Bean名、声明/必需类型、配置类/方法、file:line、依赖类型、键名、profile与显式primary注册规则。`source-sha256.txt` 覆盖这些实际直接来源；只存公开源码摘要，不导出配置值/私有异常或环境。

重点边界：

- 应用从 `RuoYiApplication` 启动真实SpringBoot context，先用原 `GenericGameplayHttpE2eIT.LocalInitializer` 做网络/完整schema报告/列预检、动态内容安全stub及媒体设置，再加组合有限属性。
- 原 `httpE2eWxContentSecurityStub` 是动态JDK编译后显式注册的distinct primary Bean，未启用override或禁止其他真实Bean。原完整mapper/domain扫描、test资源和只读源码保留。
- `masterDataSource` 经原 `DruidConfig` 和 `DruidProperties` 生成；原 `dynamicDataSource`仍为Primary；`sqlSessionFactory`、`redisTemplate` 和LLM四个具名客户端继续走真实配置。
- `PhotoCheckIntentService` 的应用Bean仍是原constructor依赖；Storage另外以其自身真实SQL/manager构造同类型服务，它们不是同一个对象或同一schema。机器清单把二者分开。
- `CombinedControlledProviders`、`CrossDomainStorageFixture` 及其人工组装的typed gateway只在case正文创建，**不是Spring启动Bean**；不能拿它们的失败帧一律标startup，也不向root推荐禁用这些原业务场景。
- 第19个已选编译输入 `CombinedSurefireRuntimeProbeTest` 只验provider/runtime，不启动Spring；原19清单不增删。新直接unit源正常标准测试发现即可，不加入原组合profile或更改其唯一case/XML计数。

root独占共享XML诊断；本包没有共享CI修改。机器合同仅提供固定异常类型候选和精确源码class/method/file的phase对照；root可复用已核Spring7类型。未知帧、未知Bean和未知cause继续UNKNOWN，不按任意message推断，也不为generated-class新增泛前缀豁免。phase区分Generic预检、DataSource/MyBatis/Redis/LLM工厂与case正文，不代表任何阶段已通过。

## 配套源码与验证边界

新 `CombinedContextConfigurationTest` 有2个普通@Test：

1. 重现原5/10/20继承配置，调用实际组合属性入口后检查0/0/3及原等待/超时，并保留原URL、actor配置和guard字段。
2. 将该实际环境值传入**原DruidProperties.dataSource**，读回真实但未初始化的DruidDataSource，断言三连接上限、合法floors、零active/pooling且未init。没有调用数据库、没有mock一个成功DataSource或启动空应用代替原case。

这2个测试源码不构成Spring/数据库执行证据；原组合case中的真实context读回仍必须由root统一同源验证。全部测试、import/parse、编译、Maven、Java/DB/Redis/Docker/HTTP/CI均 **NOT_RUN**。只进行源文本/Git/摘要/秘密扫描；没有声称原startup错误已实际修通或业务验收通过。
