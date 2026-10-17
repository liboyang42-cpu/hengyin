# FORWARD3 2babbe28：24项普通回归通过，真库待验

[PR72](https://github.com/liboyang42-cpu/hengyin/pull/72) 源码完整SHA：`2babbe283ed2bfd2bf94859e0f5a589f12dcf9bd`。后续仅交付文档提交不改变此源码标识。候选包含日期非法/反向区间保护及成长累加/首次建行竞争修复；具体合同与锁序限制见原交付文档。

专用测试VPS正常准入后，212守卫完成七模块编译与两个普通类回归：**24实际测试全部通过，0失败、0错误、0跳过**。两份fresh XML的类集合与选择精确一致，每类12项；守卫退出0/completed，精确容器已确认清理。精确命令、起止时间、源包/日志/XML哈希见 [summary.json](summary.json)。源码/缓存/日志留在 `/home/ubuntu/previews/cy-vps-forward-2babbe28-20261015/`。

历史准入：2026-10-15T20:35:49Z首次尝试因其他任务持有共享锁被拒绝（exit75，containerId null，执行0项），原始回执继续保留在 [admission-refused.json](admission-refused.json)。锁正常释放后，于20:44:37Z重试，20:47:21Z完成；没有停止其他任务或绕过守卫。

**11项真实MySQL并发测试未运行。** 当前212离线入口network none，不能替代真实MySQL/Redis集成；已查看的既有资金CI使用固定类清单，也不覆盖这11项。适用的真实数据库验证入口尚待确认，不能以普通回归结果宣称消除并发问题。运行中后端HTTP、实际数据库/Redis、设备与业务验收、合并和部署均未完成。

资源：1536MiB内存且memory-swap同额无额外swap、1CPU、256PIDs、network none、无发布端口；共享java-build.lock持有至容器结束，准入至少2560MiB可用内存，运行低于768MiB仅中止自有容器。没有变更其他服务、网络、预览或DevTools。
