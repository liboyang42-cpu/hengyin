# 固定内容安全stub准备期生成 SOURCE

公共父：`92210eab634d81a8d4c1c550d37fd9e3dacb65f0`。本增量从已交C1后像继续，不重复应用C1：输入Python SHA256分别 `dce098f855bfb0d4b3dba8093ee0bc9b70e51d0c5a1bf7fbddd959796f718495`、`b185cd4fa2dc20e79b956a7ea20d8166393bc9043eb44fa79463b3327203c9b3`，原C1 README仍为 `521fd797c047ec6917d418f7b7841c0b11749478260022d5b63d60547d5369e2`，不修改。本包仅两个Python及本文；C三Java由root另行集成，不含内部runtime资料。

## 固定消费合同

使用原 `scripts/e2e/E2eWxContentSecurityStub.java.stub`（本父源2059bytes，SHA256 `4b4bb11e6bd4a211fc07164f90f9310d34a0bf6cad3e2eb415722d4d2c9a66af`），FQN `com.chengyinhub.e2e.E2eWxContentSecurityStub`、bean `httpE2eWxContentSecurityStub`不变。只改变固定组合的生成时机；不改生产内容安全、权限或其它Generic范围。

原固定探针每phase导出私有 `target/provider-runtime/{online,offline}.classpath`：UTF-8原property字节，无尾LF，0<n<1048576，保留顺序与重复项，dir0700/file0600。phase JSON严格新增 `testClasspath={fileName,sha256,byteLength,entryCount}`。Python逐项核绝对canonical路径、实际file/dir、原cache映射和六reactor module target边界，沿原booter和C导出源的路径合同，仍必须有admin classes/test-classes两个精确目录；拒空项、远程/未知路径、别名、symlink或漂移，不扫描cache重建近似classpath。不输出原classpath；ONLINE/OFFLINE必须有相同字节摘要/长度/条数，各自fileName不同。

准备顺序固定为：ONLINE原probe成功退出及原XML → 同一pinned容器固定stub生成/证明 → Config11+原Photo23各一次 → OFFLINE原probe及原XML → 原offlinepreflight。三个Maven/fork调用点都沿同一原runtime C1参数/预算；原package/compiler准备仍不变。原probe方法仍唯一 `bootsActualProjectPlatformRuntime`；Config原9方法保留，新增仅 `precompiledContentSecurityFixtureKeepsOriginalChecksAndRejectsUnprovenOutputs` 与 `providerClasspathCapturePreservesExactBytesAndRejectsUnownedEntries`。

`fixed_cache_command` 在ONLINE原Maven返回0并保存provider XML后，停在有限 `CONTENT_SECURITY_COMPILE` 边界；`warm_cache` 在原1200秒deadline内核同一CID/owner/source/POM/profile和实际编译输出，读取原完整ONLINE cp。`compile_controlled_content` 在该容器同JDK执行固定 `javac -J-Xmx128m -proc:none -classpath <完整原cp> -d <固定classes>`，只复制原stub到本owner新建的固定/tmp源目录，退出后删除该临时源。新编译进程不与业务Surefire并存；没有新容器、依赖、通用命令/路径选择器或源码清单修改。

生成目录为 `chengyinhub-admin/target/controlled-content-security`。唯一descendants为classes、classes/com、classes/com/chengyinhub、classes/com/chengyinhub/e2e、该固定class和proof.properties。proof限1024字节，严格三条非空hex键 `sourceSha/sourceSha256/classSha256`，不容重复/额外键/注释/空行；原source/class/proof皆canonical普通文件。固定class须为非空有效magic且不超过1MiB。不留.java、临时文件或额外输出，不进入test-classes、扫描或原21源/40最低class集合。每次warm在原精确admin test-state reset中清旧成品，生成前还要求目录不存在；不采用旧class。失败产物不签证明，后续不回退actual Surefire编译。

## 原退出、证明和只读边界

`controlledContentSecurityCompiler`单列 `purpose=PREPARATION_COMPILER_ONLY/exitObserved/exit/testClasspath`，成功时附成品proof。通过独立原owner文件记录javac退出；javac非零时保留ONLINE Maven实际0与本次compiler非零，原overall结果仍FAIL，不能把javac退出写为Maven退出。缺少实际compiler退出或生成/校验失败保持失败，不补造成功；原firstFailure、精确cleanup与OOM拒绝仍在。

cache报告与 `verified_cache` 都必须读回 `controlledContentSecurity`：purpose、sourceSha、源/class/proof的SHA256；同时核compiler真实0、同ONLINE testClasspath及现成成品一致。原source/run/attempt/class、core/settings/image、完整cache、双fork/实际runtimeJAR、所有原编译树/配置XML和cleanup仍逐一校验。旧报告/缺证明不准复用。

`source_manifest` 前后都加入该固定源/成品/proof摘要，保留全部原reactor class树/JAR/source git.properties及原14运行身份；成品不被混进这些树，也不能在运行间漂移。实际app在原target bind之上，再对成品目录加精确只读bind；`validate_app_inputs`拒缺失或可写映射，源仍只读、依赖cache仍只读、实际仍离线/internal network。最终subject要求缓存成品证明与实际前后source证明一致，原HTTP240/并发/混合故障/真实Spring和所有经济断言不减。

原 `preparationTests` 投影仅接受已证非零实际Maven退出的精确当前阶段：ONLINE_PROVIDER_RUNTIME只投影原probe，ONLINE_CONFIGURATION_UNITS只投影Config/Photo。仍核同源/当前完整编译输出/owner/run/attempt；缺失或不匹配UNKNOWN，不挪用其它阶段的报告编造本次失败，不改变任何acceptance boolean或输出原XML/日志/路径。

## SOURCE控制和限制

原117控制保留；必要接线改为三次相同C1/fork参数、Config11、精确分阶段选择，并保原断言意义。新增8个控制SOURCE，覆盖完整cp与phase/hash、缺/漂移/不安全路径，旧/假/额外输出或proof拒绝，固定javac传完整cp及独立失败退出，javac失败不冒充Maven失败且原清理仍执行，cache/subject共同绑定，只读mount，完整source manifest前后等价及成品漂移。全部synthetic class/JAR/transport输入仅是协议控制，不冒充真实编译。C新增Config为自足文件/类型拒绝控制，Config11不证明成功stub加载或业务；真正正向语义须由组合consumer context refresh后原assertContentSecurityFixture核实，不能fake class、在unit另编译或skip来替代。

未执行解析/import/测试/编译、Java/Maven、Docker/DB/HTTP/CI。仅源码读取/编辑/diff与运输摘要；本包全部 **NOT_RUN**。它消除actual组合进程中的原动态JavaCompiler工作集，但节省字节和最终是否在预算内完成未知，不能借旧绿转签。已只读C最终公开commit `9a8c0265f029908129e8fb8c0f217b8480b054a2` 的三Java和contract，按实际精确scope、源2059/hash、六项成品、proof格式及testClasspath字段接齐；未修改这些Java。最终集成及集中验收由root核定。
