# EXTRA 474e54ae：VPS 编译通过，普通回归未通过

公共完整候选：`474e54aea6351856e7e4dd23ab4561932958522d`，[PR36](https://github.com/liboyang42-cpu/hengyin/pull/36)。使用专用测试 VPS 预算 212 守卫串行执行，无 Cloud 本机替代。

- 后端主/测试源码编译通过并进入 Surefire。
- 精确选择 34 个普通/离线测试类，34 份新鲜 XML，类集合与选择清单一致；**305 项，302 通过、1 失败、2 错误、0 跳过，回归 FAIL**。守卫退出 1，原因 child_command_failed。
- 初始简单 @Test 静态声明计数为304，不能替代实际305；实际执行只以XML计数。
- 23个含环境门禁/JDBC的类未计入。首次选择保守，后续区分H2与真实MySQL；不把普通回归称真库/运行时业务验收。

受控异常、项目栈帧、完整源包/日志/XML哈希及报告新鲜度见 [summary.json](summary.json)。原始日志与XML仍在本任务VPS目录。没有加载业务凭据或连接业务数据库；两个账号/生产环境均未接触。

## 失败反馈

3个失败/错误集中于E02两个测试类。已直接叫醒EXTRA并交原E02作者，仅占一条修复线，其余独立任务继续；原作者回执已确认修复并push，等待本地对完整新SHA针对性复测。原断言/403权限负控/请求身份绑定不应因失败被放松；未运行新版本不能继承旧通过结果。

| 类 | 方法 | 结果 |
|---|---|---|
| `CommunityPostServiceImplTest` | `concurrentCommentLikeReplayDoesNotEmitDuplicateOutbox` | error |
| `CommunityPostServiceImplTest` | `commentLikeAndUnlikeWritePostScopedOutboxEvents` | error |
| `E02_14Test` | `publishingExistingCommunityDraftRechecksMembershipBeforeSafetyOrWrites` | failure |

## 执行边界

命令：`mvn -o -B -ntp -l <owned-log> -pl chengyinhub-admin -am -Dmaven.test.skip=false -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -DforkCount=1 -Dtest=<summary中34个精确类名逗号串> test`。上游无所选类可以空选择；admin实跑集合已独立比对，不能用空选择报通过。

仅使用 `/home/ubuntu/.local/bin/chengyin-build-budget-212`，独占source/cache同一task根；1536MiB内存且memory-swap同额无额外swap、1CPU、256PIDs、network none、无发布端口，启动≥2560MiB/运行低于768MiB由守卫仅终止自身，java-build.lock贯穿。精确测试容器已守卫清理，未改变其他服务/网络/资源；未部署、合并、迁移、发布或执行设备与真实身份HTTP业务验收。
