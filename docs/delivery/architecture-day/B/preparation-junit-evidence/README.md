# 准备阶段固定 JUnit 失败投影 SOURCE

父提交：`93a18f655bdf124eaddc46e91a0cd91ac8f31f50`。只修当前准备失败的可观测接缝；不记录实际运行/回执/日志，不重做编译/provider/内存或业务修补。没有旧原XML，不能补造旧失败方法、类型或原因。

## 真实调用与身份前提

`warm_cache` 观察真实非零Maven退出后，以原source/POM/profile输入及 `fixed_test_compilation_outputs` 冻结失败时的编译后像；该捕获失败只保留未知，不覆盖原Maven首因，也不作为测试成功证明。

原 finally 在精确清理前独立取得同一活owner的phase，调用 `preparation_test_evidence`。它核验 sourceSha/runId/attempt/class、明确ONLINE_PROVIDER_RUNTIME、实际非零退出、CID/name/nonce标签/image/硬预算、固定Cmd/entrypoint/user、固定source/run环境及原core hash；再重核原clean checkout/POM、21源编译输入、40最低class与全实际输出后像。三份源和class逐一匹配这份编译证明后才读XML。即使raw Docker日志捕获失败也走该独立接缝；不另起资源、重跑测试或读取任意文件。

固定列表只来自原常量：

| suite | 原固定方法数 | 固定报告 |
| --- | ---: | --- |
| PROVIDER_PROBE | 1 | TEST-com.chengyinhub.business.it.CombinedSurefireRuntimeProbeTest.xml |
| CONFIGURATION_UNIT | 9 | TEST-com.chengyinhub.business.it.CombinedContextConfigurationTest.xml |
| PHOTO_UNIT | 23 | TEST-com.chengyinhub.business.service.ai.PhotoCheckIntentServiceTest.xml |

它们只位于原 module target/surefire-reports，原ONLINE命令精确rm三文件及单次执行安排不变。OFFLINE原probe或任何未证phase不混用这三份结果。原始Surefire XML允许任务owner创建的0644/0600普通文件，禁止symlink、路径别名、共享可写或超过2MiB；复用有界XML解码，拒DOCTYPE/ENTITY。原私有acceptance副本仍要求私有权限，原exact_test_xml零failure/error/skip/retry合同不变。

## cache JSON 合同

新增 `preparationTests`，固定 purpose=PREPARATION_JUNIT_DIAGNOSTIC_ONLY、businessAccepted=false；state为OBSERVED / PARTIAL_OR_UNKNOWN / UNKNOWN。已证身份时附原source/run/attempt/class、phase、observed Maven exit、编译后像SHA256，以及固定三suite的源/class哈希。每个合法suite只投影XML哈希、tests/failures/errors/skipped/retries数量与nonPassing；不输出properties、system-out/err、message、SQL/URL、环境、argv、路径或raw stack。

suite与每个case的class/method、完整方法集合、唯一case和终态数量必须一致。未知方法、mixed/nested suite、重复case、不一致计数、坏/缺/超大XML均UNKNOWN，不从部分错误XML编造失败；其它独立合法suite可保留结果。skipped保持SKIPPED，retry单列RETRY与次数，不能把无失败方法标FAILURE。未知异常type只写UNKNOWN。类型只允许本源码有限公开异常白名单；源frame只接受同一固定case方法、固定测试类/文件、当前源行数内的明确reported stack frame，不暴露异常正文，也不声称它证明底层经济根因。

原 `firstFailure`、退出码、failures和cleanup判定不由该投影改写；诊断失败也不追加acceptance failure。即使所有unit报告全零，非零Maven仍失败，配置/Photo unit绝不作为业务或HTTP240通过。原accepted/verified_cache表达式、ONLINE三suite一次/OFFLINE原probe、21输入/40最低与全树/原14运行身份、生产reactor/JAR、预算/只读/离线/网络和原清理围栏不变。

## 控制及验证边界

原108个方法和全部断言保留，transport helper仅新增默认不变的phase/log-error输入。新增6个控制SOURCE（现114声明）：真实JUnit形状的FAILURE/ERROR及精确源frame、安全输出；缺/坏/mixed/未知/重复/过大/不安全报告；foreign source/class/快照/owner/phase拒绝；skip/retry不冒充failure；未知type与越界frame不外传；真实finally接线在日志失败时仍先收集后cleanup，并保留原首因/非零结果。所有合成class/XML/transport doubles仅证明协议控制，不冒充编译、Maven或业务证据。

本包仅两Python与本文。cache JSON原已上传，不需workflow、registry、POM、Java或新artifact接线。解析/import/控制/测试、Java/Maven/编译、Docker/DB/HTTP/CI全部 **NOT_RUN**；仅源码读取/编辑/diff/apply-check/hash。具体运行原因仍须root按新候选下一次实际结果回读，不能转签旧成绩或恢复旧未知内层日志。
