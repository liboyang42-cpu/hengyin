# G05-01 negative-only 完整候选（禁止合并/部署）

结论：只在原 ThemeRuntimeMySqlIT 末尾追加原两条反例；旧类正文/所有原case、断言、wrapper、SQL、fixtures逐字保留，生产代码零变化。编译/运行均0，尚不能称真库红例。

- 公共基线：`4cf548c3e8b05c6d23f3252c7e5d063691ef0eaa`；独立目录 `/workspace/hengyin-g-arrival-negative`；分支 `codex/architecture-day-g-arrival-negative-20261024`。
- 被审生产/Mapper/原IT相较919无差异。原反例来自b7c8dbe441affcc00aa87d7ca83983c5b2615044的review patch，只改说明注释为已加入negative候选，方法正文不改。
- `replayArrivalMustBeReadableFromTheCurrentPlayInstance`：真实原runtime合法END/replay后，调用真实旧ShopDayProofService，要求当前新实例可读回到达status0。不是mock runtime结果。
- `replayArrivalMustNotCollideWithTheOldDefaultArrival`：原default到达留存，合法replay后新到达不得撞旧唯一键失败，当前实例可见，旧status0留存。
- 实际执行：原类还原字节比对、原/新增case枚举、diff范围/whitespace、源码hash与secret-file-gate；没有Java/MySQL/Redis/CI dispatch/生产操作，不改CI/registry、不加skip。
- 精确case数量与测试源码SHA256见source-manifest.json；root统一在同SHA受控CI裁决实际失败。本提交仅负控证据，禁止merge/deploy；后续正向候选单独分支保留此历史。
