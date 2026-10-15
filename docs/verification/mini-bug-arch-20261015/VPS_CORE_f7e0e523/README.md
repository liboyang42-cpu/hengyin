# CORE f7e0e523：VPS 编译通过，普通回归未通过

公共候选：`f7e0e52346d0aec248e6288f1f5ed4aaae66ac4c`，[PR44](https://github.com/liboyang42-cpu/hengyin/pull/44)。本地验证方在专用测试 VPS 实际执行，使用预算 212 守卫；这是 VPS 证据，不引用 Cloud 本机诊断作为通过结果。

- 主源码与测试源码编译通过：7 个 reactor 模块 SUCCESS，admin 编译 1392 个测试源文件，守卫退出 0。
- 普通/离线回归：83 份新鲜 Surefire XML，精确类集合与选择清单一致；483 项，470 通过、5 失败、8 错误、0 跳过；守卫退出 1（child_command_failed）。因此本候选回归 **FAIL**。
- 13 个失败/错误分布在 8 个类，包含夹具/Mockito 问题与待诊断的行为差异；这不是“已确认 13 个业务 bug”。完整方法、异常及相关项目栈帧见 [summary.json](summary.json)。
- 8 个含环境门禁/JDBC 的类另列待测，不计入 483；首次选择保守排除 JDBC，后续须区分 H2 与真实 MySQL。MySQL/Redis 集成、实际 HTTP 业务状态、设备、合并、部署及迁移均未验收。

## 复现与资源

源码只从该公共完整 SHA 导出，导出包 SHA-256、编译/回归日志 SHA-256、每份 XML SHA-256 和新鲜度检查均在 summary 中；原日志/XML 保留于本任务 VPS 证据目录。未加载业务凭据或连接业务数据库。

入口 `/home/ubuntu/.local/bin/chengyin-build-budget-212`；独占 source/cache 均在同一 task 目录下。1536 MiB 内存及同额 memory-swap（无额外 swap）、1 CPU、256 PIDs、无网络/发布端口；共享 java-build.lock 贯穿进程。准入 MemAvailable ≥2560 MiB，运行低于 768 MiB 由守卫仅中止自身容器。两次精确构建容器均已守卫清理；未更改其他服务、资源、网络或预览。

编译命令为 `mvn -o -B -ntp -l <owned-compile-log> -pl chengyinhub-admin -am -Dmaven.test.skip=false -DskipTests test-compile`。回归命令为 `mvn -o -B -ntp -l <owned-unit-log> -pl chengyinhub-admin -am -Dmaven.test.skip=false -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -DforkCount=1 -Dtest=<summary中83个精确报告类名逗号串> test`。仅上游没有选中类时容许空选择，admin 实跑类集和数量已独立核对，不把未发现/跳过当通过。

## 已反馈原队修复

本地已将完整失败包直接交 C00；其实际回执已确认按 **1 条修复线串行处理、其余 5 条独立开发继续**。对应原作者先区分夹具与生产逻辑根因，保留原断言和负控；换写者先正式转交。新修复 SHA 回传后针对失败及受影响类复测，不把新源码加入旧版本成绩。

| 类 | 方法 | 结果 |
|---|---|---|
| `OWN_C03_22Test` | `nodeInsertMustSucceedAndGenerateIdentityBeforeNpcOrRuleWrite` | failure |
| `OWN_C03_22Test` | `chapterInsertMustSucceedAndGenerateIdentityBeforeNodes` | failure |
| `OWN_C03_24Test` | `mediaFailureAfterWriteRemainsUncertain` | error |
| `OWN_C04_21Test` | `bountyFailureRollsBackItsNestedBoundaryAfterMemberThenBountyLock` | failure |
| `OWN_C04_22Test` | `lateAcceptedAndAmbiguousReturnOriginalTerminalOrFrozenFacts` | error |
| `OWN_C04_22Test` | `unchangedProcessingReceiptCanLegitimatelyReportZeroAffectedRows` | error |
| `OWN_C04_22Test` | `staleUnknownQueryCannotWriteTerminalReceiptOrEscalateFromOldSnapshot` | error |
| `OWN_C06_25Test` | `journeyReceiptWriteFailureStillThrows` | error |
| `OWN_C06_25Test` | `checkReceiptWriteFailureStillThrows` | error |
| `OWN_C06_29Test` | `emptyFrozenObjectPreservesDefaultRulesInsteadOfLiveCheckEnabled` | error |
| `BothWithdrawalLegsRejectPlayerCooldownTest` | `bothLegsHandTheCombinedFrozenAmountToTheAtomicDeduction` | failure |
| `GameSessionRuntimeServiceImplTest` | `existingPhotoSubmissionReplayDoesNotReverifyStorage` | error |
| `GameSessionRuntimeServiceImplTest` | `concurrentRoleInsertCollisionReadsActiveWinnerAndCurrentRevision` | failure |
