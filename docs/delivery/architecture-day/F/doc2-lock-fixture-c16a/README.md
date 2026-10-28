# F：Doc2 registry parent-lock fixture 接缝

父源：c16aeef5c32fb04603a7e0546dc25abca4e0d05f。仅修实际 testCompile 首因与它的直接控制 SOURCE；生产 Mapper/SQL/Service、CI/POM、其他 owner 文件均未修改。

- 原错误：Doc2KeyRegistryFakes.Registry implements ThemeStateKeyMapper，但缺 Long lockTopicForStateKeyWrite(Long)。没有新增生产方法或 default/no-op。
- 真实合同：ThemeStateKeyMapper.java:26；XML:56 的 lockTopicForStateKeyWrite 和既有 lockTopic 都锁 cms_topic 原行。生产 ThemeStateKeyService.lockRegistry 为 parent → ensure registry version → lock registry version；updateDefinition 随后 current-read 单键，再更新定义/推进版本。FOR UPDATE/cache属性与真实事务合同原样保留。
- 唯一 fixture 消费者：ThemeKeyNamingServiceTest（原7方法）和 ThemeAttributeServiceTest（原8方法），均 new World、原 topic=7；两文件正文/全部断言逐字不动。Registry 没有其他外部构造调用者。
- Fake 新旧 parent API 共用固定自有 topic7，外来/null topic返回null，不再任意反射返回请求ID；current-read/version/write调用必须先有该 topic 的 parent fence。调用轨迹可证明被测生产方法的原次序。普通读不暴露其他topic；声明防御副本保留 topicId。
- add 是原测试种子入口，直接存同一自有topic行，不伪造一次业务锁/写调用。真实 Service 写仍必须走 Mapper 的 fence 检查。原 id/upsert、版本推进、键/属性标记、业务校验和场景不删不改。

新增 Doc2KeyRegistryFakesTest 三个 SOURCE 控制：

1. realRegistryWriterUsesParentBeforeVersionAndCurrentDefinition：真实 ThemeStateKeyService.register/updateDefinition 的完整锁序、原topic、标签及版本推进。
2. missingParentFenceRejectsCurrentReadsAndMutationsWithoutEffects：无parent时版本/current读/写均拒绝，rows/marks/version/ID零变化。
3. bothParentPortsShareOnlyTheOwnedTopicAndForeignWriterCannotMutateIt：两旧/新锁端口同scope、原声明副本、外来topic读隔离、即使已锁7也不能写8、真实Service外来注册拒绝且原行版本保持。

这是单主题有序调用 double，不是事务管理器/物理锁实现，不证明 MySQL 并发或跨事务释放；原真实并发/锁测试未改、未运行。

source.patch.b64 解码完整两测试文件增量：12838 bytes；SHA256 251fee633b328b21a6c15f3604d558f0ef0e5b0b4320c838ed6e43363dcbb969。IMAGES.tsv 含完整前后像；UNCHANGED.sha256 含两原消费者与相关生产文件后像。

仅 Git读源/diff/apply-check/摘要及有限敏感模式检查。项目parse/import、所有测试、编译/Java/Maven、DB/Redis/Docker、CI：全部 NOT_RUN。由root统一集成并必要同源验证；不挪用之前原源的PASS。
