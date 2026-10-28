# B：固定 payment-gap 诊断调用收口

公共父提交 `347bcd0226c3bed71616e38ea7c1391fc76efe5c`。本增量保留root已应用的B诊断包 `643a71fb35fe795f65a320d978776f719473c7b29e7b86457ac054a5cdc86bc7`；其中host逻辑不改，host控制前像为该包后像 `6d0dbf2113ac1a9028a5fac46b4ebc0174398ac38a499da5c5aa76c37dded8a8`。不重交或覆盖原6条新控制。

## 唯一选择与真实入口

原已注册backend-jar使用同候选ref和原完整40位source_sha。新增固定choice `restore_scope`：缺省 `full-seven`，唯一诊断值 `payment-gap-diagnostic`。诊断必须同时显式 `run_restore_fixtures=true,run_http_fixtures=false`；选择诊断却只build/只HTTP/双选/未知scope均由原admission真实失败，不能全skip签绿。原full-seven时的普通build与HTTP默认合同保留。

caller继续相对uses原isolated-restore-business-reconciliation同commit，传source_sha和restore_scope。callee原manual入口同样提供这两个固定选择，workflow_call为有default的string，原build前壳层再拒绝所有其它scope。真实source/workflow SHA相等、repo/manual/job/run/attempt约束不变，不新增恢复批准flag或通用cohort过滤。

## 诊断不等于完整验收

- 原一次build/同JAR artifact不改。默认matrix仍是原7项原顺序；只有payment-gap-diagnostic选择单项payment-gap。max-parallel20、资源参数、原host/probe/cleanup/三份public JSON保留，不复制runner或改生产。
- wrapper和cohort job名明确DIAGNOSTIC_PARTIAL。诊断job即使失败也尽力向GITHUB_STEP_SUMMARY写NOT_FULL，声明其余六项和七receipt/三positive verifier未执行；原host.json acceptance的NOT_FULL标记保持。
- 完整summary job仅full-seven运行，原needs、下载7份、同一次build摘要及严格全部正负例汇总正文不改。诊断不调用--summarize，不制造完整summary.json或借缺六份签full green。诊断成功最多是固定payment-gap子链的证据；root之后仍须按真实首因修补并收齐完整7项验收。

## 源码交付与限制

owned为两份原workflow、`ci/test_restore_business_reconciliation_build.py`、`ci/test_restore_business_reconciliation_host.py`及本README。原HTTP ManualRouterTest无需改动，单EOF/LF边界及原正负控保留。build控制原11条保留，新增2条（13声明）覆盖真实admission诊断真值表/非法scope、callee固定输入、部分job名和full-summary隔离；host控制18条保留，仅把原matrix读取改为分别精确核单项/完整7项，并明确full-summary条件。计数均为SOURCE，不是PASS。

仅源码读取/编辑、git diff/applycheck及摘要。所有测试、YAML/JSON解析控制、Python导入/执行、编译、CI、Docker、DB/HTTP及故障均 **NOT_RUN**。本片没有dispatch、缩减完整验收或修改C/F/A/G/D/原host；root唯一集成及后续真实执行。
