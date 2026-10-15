# EXTRA b45664de：测试夹具修复已交，VPS复测待准入

[PR36](https://github.com/liboyang42-cpu/hengyin/pull/36) 冻结完整SHA：`b45664de6136eb97c46ed0c86e95fc25686dc1b7`。相对原失败候选ed598，仅修改E06_17Test及交付说明；生产代码不变，无新增依赖。原作者和C00均已确认完整交付及文件范围。

隔离负控的模拟异常改为既有脱敏器支持的MyBatis查询诊断形状，保留通用错误断言，增加两条SQL/表名不回送断言；Share503、GONE、隐私及成功路径的断言没有放松。原裸SQL文本未脱敏疑点继续交C00登记NEEDS_REACHABILITY / NO_SOURCE_CHANGE，尚无真实业务可达证据，不作为已确认新增BUG，也未改共享UserSafeMessage/GlobalExceptionHandler。

本地按差异只选择 `com.chengyinhub.business.longrun.E06_17Test` 全部5个方法，不无理由重跑76项。源码归档已传专用VPS并验证哈希、解包，源码/缓存位于同一独占任务目录。2026-10-15T20:58:53Z，212守卫因 `another_build_holds_lock` 拒绝（exit75，containerId null）：**本次执行0项、未编译/测试，修复仍NOT_RUN，不是新源码测试失败，也不是通过。** 回执见 [admission-refused.json](admission-refused.json)。

原ed598的76项/1失败报告继续保留，不因源码交付关闭。等待正常准入后保留本拒绝回执，按此冻结SHA针对性复测并另附fresh XML/实际计数/守卫结果。未停止、修改其他任务或绕过共享锁；没有本机Java/Cloud替代执行。

预算保持1536MiB memory且同额memory-swap无额外swap、1CPU、256PIDs、network none/无端口，共享锁持至容器结束；准入内存至少2560MiB、运行低于768MiB仅守卫中止自有容器。真实数据库、运行中接口、设备及业务验收、合并部署仍未完成。
