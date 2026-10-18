# EXTRA 29fe9236：E02-25当前读恢复64项普通回归通过

冻结完整源码 `29fe9236ede15a8f1bbd3aaf2b93e3fc25409726`（[PR36](https://github.com/liboyang42-cpu/hengyin/pull/36)）；文档head `e9b9e1912fa449e1ee1c0344ac2ff0dc4cb268ec` 直接父为该源码，仅自有BACKLOG/QUEUE/CLAIMS三文档，不视新修复。

相对faef，生产只改 CommunityPostServiceImpl 的comment DuplicateKey恢复查询、新增 CommunityPostMapper exact author/request ForUpdate接口及同投影/WHERE XML当前读。保首次普通读、原post/body/parent绑定、权限与事务隔离配置；无DDL或新依赖。按C00精确7路径授权，新增E02_25Test、E02_25MySqlIT和说明；同owner E02_24Test只调整duplicate夹具的current赢家前提，原5方法/全部绑定断言保留，旧55方法不改。三份自有交付账变化另外列入差异，不是全局账或共享源码越权。

测试VPS通过现有212离线入口实际执行：

| 普通类 | 实际项 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|
| E02_25Test | 4 | 0 | 0 | 0 |
| E02_24Test | 5 | 0 | 0 | 0 |
| CommunityPostServiceImplTest | 55 | 0 | 0 | 0 |
| 合计 | **64** | **0** | **0** | **0** |

fresh三份XML均晚于本轮开始，类集合精确一致；212守卫0/completed，起止2026-10-16T04:09:10Z—04:11:52Z。精确自有容器已通过显式chengyin-aws过滤确认不存在。完整命令、源包/日志/XML哈希及原始守卫回执见 [summary.json](summary.json)。源包SHA256 `d62465ebcc63192f74c95e2e02d86101451fe13a54d873fec83367923d4f2c8f`；原始证据保留在 `/home/ubuntu/previews/cy-vps-extra-29fe9236-20261016/`，没有沿用旧target或把faef60PASS继承过来。

资源保持1536MiB且memory-swap同额、1CPU、256PIDs、network none/无端口，共享锁持至结束；启动内存>=2560MiB及运行768MiB守卫未放宽。没有Cloud/本机Java替代、手动触发CI、预览或生产变更，也没有为准入停止其他任务。

这64项只证明普通Service/SQL替身分支回归。新 `E02_25MySqlIT` **2项仍NOT_RUN**：需要独立门控CHENGYIN_E02_25_MYSQL_IT=1、显式获准MySQL8 scratch六项配置和适用真实存储入口；212断网入口不能代替。实际RR快照、真实DuplicateKey/current-read及SQL副作用提交未在此验收，不声称RR竞态已实库闭环。真Redis、运行HTTP、设备业务、候选集成、合并部署均未完成。真库入口授权问题仍待用户明确答复，未创建网络或改CI。
