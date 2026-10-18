# E04-07-A VPS 准入拒绝回执

固定公开源码 `dedb44d54a4a277273943c0cbbca9f6e7528482a`，直接父 `a76268d5454c8a5c5e3181ae7574bfb93932f800`。仅原券身份未知时审计、券不动，其他已授权积分/经验冲正继续；不新增 schema 依赖。精确差异为消费者、新 E04_07Test 与 A 说明三文件，符合 C00 已公开授权。

2026-10-16 13:01:10 UTC，既有212守卫返回 **exit75 / insufficient_start_memory / containerId=null**。MemAvailable 2,195,624 KiB，约2144 MiB，低于2560 MiB启动门槛。计划单类3方法，**实际执行0项、fresh Surefire XML 0份，NOT_RUN**。这是资源准入拒绝，不是测试失败，不开原作者修复槽；不继承任何旧PASS。

源包 SHA256 `b5db4d5f22a8465184ad13980d30aacc538c6073ec19efb756819b66cee74191`，守卫 SHA256 `12374f742dcf1547c8eabf28c28403c94c8307fe20cba0a3a63d7f2b49764fba`。本任务目录 `/home/ubuntu/previews/cy-vps-extra-dedb44d5-20261016` 保留源包、selection、独占source/cache及原run/result。显式 `chengyin-aws` 仅按精确任务名过滤，确认未创建容器。详细回执及文件哈希见 [summary.json](summary.json)。

不绕守卫、不停止他人服务、不加内存或swap、不新增入口/网络/CI。后续仅在资源自然达到现有准入条件时重试，并保留本次拒绝历史。E02与E04-B独立源码不等本次验证；新候选不继承本候选或历史成绩。普通回归、真实MySQL/Redis、运行HTTP、设备业务及迁移/合并/部署均未在本次验收。
