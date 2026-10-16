# 第三队 bde356f3：非法年份与跨午夜修复28项普通通过

冻结源码 `bde356f3f0944f4c1a00726fbf821de2267a3eea`（[PR72](https://github.com/liboyang42-cpu/hengyin/pull/72)）。相对2bab，源码仅 AnalyticsController/Test 与 PlayerProgressionServiceImpl/Test 四个既有文件有变化，无新依赖；拒绝pilot零年/负年/带符号扩展年份，保留原合法窗口和响应；成长锁读后统一采样时点，保留默认时区、连续打卡及奖励规则。

专用测试VPS两普通类 AnalyticsControllerTest、PlayerProgressionServiceImplTest 各14项，共 **28实际通过、0失败、0错误、0跳过**。两份fresh XML精确匹配类集；212守卫0/completed，2026-10-16T03:47:03Z—03:49:49Z，精确自有容器已通过显式chengyin-aws过滤确认清理。完整命令、源码包/日志/XML哈希见 [summary.json](summary.json)。证据保留在 `/home/ubuntu/previews/cy-vps-forward-bde356f3-20261016/`。

使用现有离线212入口，共享锁持至结束，1536MiB且memory-swap同额、1CPU、256PIDs、network none、无端口。未改变其他任务或启动本机Java、数据库服务、DevTools。

原 PlayerGrowthConcurrencyMySqlIT 的11项真实MySQL回归本次仍NOT_RUN，不能把这28普通PASS作为数据库锁/并发验收。旧2bab的24项成绩保留原SHA；运行HTTP、设备业务、候选集成、合并部署均未完成。
