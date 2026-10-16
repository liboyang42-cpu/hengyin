# 真MySQL/Redis入口缺口与恢复计划

2026-10-16本地只读复核：测试VPS SSH可达，已安装的受控Java入口只有 `/home/ubuntu/.local/bin/chengyin-build-budget-212`，哈希仍为 `12374f742dcf1547c8eabf28c28403c94c8307fe20cba0a3a63d7f2b49764fba`。该入口固定 network none、无端口、无凭据透传，不能连接外部MySQL/Redis；普通构建锁排队是另一个问题，不等于真库入口已经可用。

现有 `.github/workflows/funds-mysql-it.yml` 在GitHub-hosted环境创建MySQL 8.0.45 scratch，再调用 `ci/funds-mysql-it-gate.sh` 固定类表；有环境校验、唯一suffix和fresh报告门禁，但未完整登记当前新增回归类，也没有Redis服务步骤。没有手动触发、重跑或修改CI。普通CI、源码声明和skip不能作为这些类的真实运行证据。

[候选分列清单](inventory.json)从新CORE e0e3与EXTRA faef源码核读原8类/23类，记录不同开关和Redis引用线索；Redis引用不表示该类实际连接真实Redis。第三队 bde356f3f0944f4c1a00726fbf821de2267a3eea 的 PlayerGrowthConcurrencyMySqlIT 仍11声明，要求 CHENGYIN_FUNDS_MYSQL_IT=1、显式HOST/PORT/DATABASE_BASE/DATABASE_SUFFIX/USER/PASSWORD、真实MySQL8、独占临时schema和建库/删库权限。三候选独立，不合计为去重类数。

待批准的入口范围：保留212原样，独立受控集成入口使用同一共享锁、固定源码及类集；只连本任务隔离网络、无外网/生产/公开端口；任务级内存/CPU/PID预算、启动/运行内存守卫和精确资源清理。先核预算能否容纳Java及MySQL/Redis；不足则拒绝，不停别人、不升级加费、不加swap。凭据只通过任务私有配置注入，不进入命令/日志/仓库。必须实际核验fresh XML、0skip、数据库身份及临时库清理。当前仅方案，未创建入口或服务，未运行真库/Redis测试。

后续运行HTTP、设备业务及集成合并部署仍是独立验收层级，未完成。
