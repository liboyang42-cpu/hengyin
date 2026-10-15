# EXTRA b45664de：E06五项针对性复测通过

[PR36](https://github.com/liboyang42-cpu/hengyin/pull/36) 冻结完整SHA：`b45664de6136eb97c46ed0c86e95fc25686dc1b7`。相对原失败候选ed598，仅修改E06_17Test及交付说明；生产代码不变，无新增依赖。原作者和C00已确认完整交付及文件范围。

专用测试VPS在212守卫正常准入后完成编译及原失败类 `com.chengyinhub.business.longrun.E06_17Test` 全部5方法：**5实际项通过，0失败、0错误、0跳过**。唯一XML为本次开始后的新报告，类集合精确一致；守卫退出0/completed，精确容器已确认清理。起止时间为2026-10-15T21:06:06Z—21:08:53Z。命令、源包/日志/XML哈希见 [summary.json](summary.json)。

原ed598的11类76项（75通过/1失败）历史继续保留；其唯一失败在此修复候选的针对性复测闭环。**不声称在b456新SHA重跑了全部76项。** 本差异只有该测试夹具/断言与交付文档，没有生产变化，其他类没有无理由重复执行。

隔离负控模拟异常补齐既有脱敏器支持的MyBatis查询诊断标记，保留通用错误断言，并增加SQL/表名不回送断言；Share503、GONE、隐私及成功路径的断言未放松。原裸SQL文本未脱敏疑点仍由C00登记NEEDS_REACHABILITY / NO_SOURCE_CHANGE：没有真实业务可达证据，不作为已确认新增BUG，未改共享UserSafeMessage/GlobalExceptionHandler，也未因本次通过删除该记录。

历史准入：2026-10-15T20:58:53Z首次尝试因共享构建锁拒绝（exit75、containerId null、0项、未编译/测试），原回执保留 [admission-refused.json](admission-refused.json)。锁正常释放后按同一212入口重试，未停止或修改其他任务。VPS证据保留在 `/home/ubuntu/previews/cy-vps-extra-b45664de-20261015/`，首次run/result另存为*-admission-refused-205853Z.json。

资源保持1536MiB memory且同额memory-swap无额外swap、1CPU、256PIDs、network none/无端口，共享锁持至容器结束；准入内存至少2560MiB、运行低于768MiB仅守卫中止自有容器。没有本机Java/Cloud替代验证，没有变更其他服务、预览、DevTools或生产。

本结果只签收该普通回归类。真实数据库并发/Redis、运行中HTTP、设备与业务验收、集成合并和部署仍未完成，源码交付及MockMvc响应均不能替代这些层级。
