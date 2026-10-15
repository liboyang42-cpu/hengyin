# 两队后端交本地VPS验证

唯一实际验证者：本地聊天`01a13eed-e4d9-77f3-b875-c72d26ea7d59`；C00和两队只开发/修复，不再手动编译、测试、触发CI或新增验证配置。既有自动CI自然运行。

- CORE：PR44 `f7e0e52346d0aec248e6288f1f5ed4aaae66ac4c`，完整链 `2b1984→b810b505→f7e0e523`。18个已知测试夹具诊断已由原作者源修，**新候选未复编译**。完整矩阵91类/510个@Test源码声明，不是执行数。
- EXTRA：PR36 `a37ce191756ab11896faf4e61e8a06b4279f0303`，完整链 `25ede658→767c37文档→3e1349 E03-16→a37ce191 E05-17`；新4+5方法及5旧fixture已包含；E02-22不在本次增量。先分开test-compile，不叠加两队完整分支。

JSON提供逐类FQN、静态方法数、门禁值、命令及精确路径：BACKEND_VALIDATION_CORE_MATRIX.json / BACKEND_VALIDATION_EXTRA_MATRIX.json。CORE6组：unit82/477、H2 1/6、FUNDS5/16、固定e04_scratch 1/2、session-order 1/4、C01_51外4+嵌套1。EXTRA新计数见JSON，旧49类246声明不再覆盖新a37全部。

先在适用212离线守卫/共享锁内部运行文档命令`mvn -o -B -ntp -pl chengyinhub-admin -am test-compile`；真实MySQL/Redis必须另有已核批准入口，不能把离线network-none放宽或用旧驱动。资源拒绝具体登记，禁止绕过。

C01_51 gate=true、loopback强要求，nested XML单列；C04_19门禁=1用MYBATIS_IT变量族；其他FUNDS类门禁=1、显式scratch全变量；E04必须base=e04_scratch；A03Ending独立ENDING=1/127.0.0.1/MySQL8.0/suffix<=24；E01_06需PROCESS观察锁等待。完整细节逐类见矩阵，不同时开启无关外部provider/生产上下文。

原Cloud已运行结果仅标CLOUD_EPHEMERAL，不是批准CI/VPS：原2b主源码编译成功、测试源18诊断、业务执行0；原25ede编译主动终止143（地点规则）。真实原日志/environment.json在docs/verification/mini-backend-validation-20261015，未启动DB/Redis、未触VPS或生产。

每队1修复+5开发：本地失败回原写者，多个失败串行占一个修复槽，其他独立源码完成即push，不等验证；换人需原写者停止与精确路径移交。NOT_RUN照实保留；不足真实任务不凑数、不冒称五线满载。无部署/合并/业务迁移授权。

最新本地VPS反馈：CORE f7e0e52346d0aec248e6288f1f5ed4aaae66ac4c 已按212离线test-compile退出0，02:35，7 reactor模块成功、1392测试源编译通过。此为本地验证者回报，公共原日志尚待其发布；仅COMPILE_PASS，不是业务/真实MySQL/Redis/设备/部署验收，C00不重跑验证。
