# CORE e0e3f517：新取消收藏修复10项定向通过

冻结源码 `e0e3f517b8e6deb26ed3697a00d38ae14f015e76`（[PR44](https://github.com/liboyang42-cpu/hengyin/pull/44)），相对 `0d63043ad06775bc3dc52fb925620ef36d1081a7` 只有 ApiTopicController 的显式取消删除结果判断、新 TopicLikeExplicitCancelConcurrencyTest 和交付说明。无依赖变化。

授权测试VPS实际执行 TopicLikeExplicitCancelConcurrencyTest 与 ApiTopicLikeDirectionTest，两类共 **10实际通过、0失败、0错误、0跳过**。两份XML均晚于本次开始时间，精确匹配类表；212守卫0/completed，起止2026-10-16T03:35:37Z—03:38:22Z。守卫创建的精确容器已通过显式 chengyin-aws 过滤确认不存在。完整命令、源码包/日志/XML哈希和计数见 [summary.json](summary.json)。

使用现有212离线入口，共享锁持至结束；1536MiB且memory-swap同额、1CPU、256PIDs、network none、无端口。源包和独占缓存位于 `/home/ubuntu/previews/cy-vps-core-e0e3f517-20261016/`，没有本机Java替代或变更其他任务服务。

这是新SHA上的普通定向回归，SQL边界有替身，不等于真实MySQL并发或运行HTTP验收。原483及157记录保留在原候选，不继承为新全量成绩。真实数据库/Redis、HTTP、设备/业务、集成合并及部署均未在此完成。
