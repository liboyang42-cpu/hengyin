# 组合 fixture 重复 Redis owner 收口（SOURCE）

Parent: `d2da356261166c3059e4bf594c4923e9241b6647`；保留其 `c04ab229` 首波准备/完整事实、
`10f4ef6d` UTF-8 和此前 fault/Future 合同，不重做、不改生产/POM/共享 CI。

可证冗余：完整 Spring HTTP 应用已持有实际 LettuceConnectionFactory；组合 initializer 明确
同 task-owned loopback 端口、DB15。旧 CrossDomainStorageFixture.installRedis 却仍调用
new LettuceConnectionFactory(configuration).afterPropertiesSet，再由自己的模板发真实 quota
脚本。Spring Data Redis 4.1.1 createBasicClient 在未传 ClientResources 时调用
RedisClient.create(uri)；Lettuce 7.5.2 AbstractRedisClient:122–125 为此创建独立的
DefaultClientResources。它不是第二 Redis 服务，却是第二套不必要的客户端资源生命周期。
此结论来自源码，不是本次 OOM 分配/PID/同刻 RSS 的归因，也不估算节省字节或线程数。

实际接线：组合 caller 从完整 context 取真实 LettuceConnectionFactory，显式传给新增的
storage.open(applicationRedis)。在创建任何 fixture SQL/通知资源前核实已 running、原
standalone TCP127.0.0.1、显式端口和 DB15、原 default 认证 identity/password。认证失败
仅固定提示，不输出值。不同 endpoint/DB/认证/未运行/cluster/sentinel/socket 均拒绝，
不会自动回落新建、重启或改写应用配置。使用同 factory 构造原两类真实 RedisTemplate，
保原生产序列化器/原 Lua/原 quota service、nonce/counter/generation/receipt 与真实读回。
这是复用真实资源，不是替换整个 Spring/业务 owner 或返回 mock 成功。

生命周期：组合借用者不 afterPropertiesSet/start/stop/destroy 应用 factory；fixture.close
仍只根据原随机 MEMBER 的持久 quota 行删原 exact keys 并验证 absence，不 FLUSH/扫描
他人 key，不动 AppLogin 会话。完整 context 仍由原 @DirtiesContext 关闭其 Redis owner。
独立 CrossDomainFaultIsolationMySqlIT 继续调用无参 open()，原显式 DB0..15/自建资源/关闭
路径保留；ownsRedisFactory 在自建初始化前标记，初始化失败亦沿原失败收集销毁自有资源。
MySQL 独立 schema/真实 mapper/两个池与原上限、经济事实隔离都不变。

现有 originalDruidPropertiesAssemblesAValidUninitializedThreeConnectionPool 方法追加有限
metadata/生命周期 doubles：同 factory 返回、空/显式原认证等价、错端点/DB/认证/状态/
transport/ACL 拒绝、错误不泄认证、借用者不调用任何关闭/启动、自有资源 destroy 一次。
不启动 Redis/原生资源；不将 doubles 当作实际资源或业务通过。配置仍 11 方法，原所有
语句/断言保留；真实 case 身份、四路16 MiB held、双调度进程故障并发、72意图/HTTP240、
2/5/6/60秒、150/1200秒、1CPU/1GiB/no-swap/256PID、128/384/64MiB、pool3均不改。
本片不改变 GC/线程/JIT 参数，不提前释放 held，不改源码身份、预算/清理收据或门禁。

原调度两个 test-only child 的现有启动/真实 Druid/Wall/锁/原 receipt 路径未改；没有证据
支持删组件或修改其参数，也没有就它们给 speculative patch。Core/transport 未改。
精确运行内存类别/被杀 PID/原 case 进度、修改后能否满足完整预算仍 UNKNOWN。
所有解析/import/测试/编译/Java/Maven/DB/Redis/Docker/HTTP/CI 均 NOT_RUN；仅源码读取、
差分/空白、限定敏感词和摘要。primary 文件只读下载，无执行；不公开运行材料。
