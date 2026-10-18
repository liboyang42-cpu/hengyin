# EXTRA faefbaf8：评论重放修复60项定向通过

冻结源码 `faefbaf8947b6ff202849385d28473fe2eaaa21e`（[PR36](https://github.com/liboyang42-cpu/hengyin/pull/36)），相对b456生产只补 CommunityPostServiceImpl 两处评论重放路径的规范化parent身份绑定；另有新E02_24Test与交付说明，无接口、Mapper、DDL或依赖变化。

专用测试VPS实际执行 E02_24Test 与 CommunityPostServiceImplTest，**60实际通过、0失败、0错误、0跳过**。fresh两XML精确类集，212守卫0/completed，2026-10-16T03:44:19Z—03:47:03Z。精确容器已通过显式chengyin-aws过滤确认不存在。命令及源码包/日志/XML哈希见 [summary.json](summary.json)。独占源码/缓存/原报告保留在 `/home/ubuntu/previews/cy-vps-extra-faefbaf8-20261016/`。

首次2026-10-16T03:40:30Z因另一任务持有共享锁拒绝，exit75/another_build_holds_lock/containerId null，实际0项，[原始回执](admission-refused-034030Z.json)保留。锁正常释放后按同一212入口重试，未停止或更改其他任务。首次run/result在VPS另存*-admission-refused-034030Z.json，旧失败记录未覆写。

本轮1536MiB且memory-swap同额、1CPU、256PIDs、network none、无端口；锁持至结束，启动/运行内存门槛未放宽。没有本机Java或Cloud替代，没有服务部署。

这些回归在SQL边界使用替身，不等于真实MySQL RR或Redis并发验证。新RR源码后续若另交，不能继承这次60项成绩。原305/76/5结果仅保留原候选历史；真实DB/Redis、运行HTTP、设备业务、合并部署仍未在此验收。
