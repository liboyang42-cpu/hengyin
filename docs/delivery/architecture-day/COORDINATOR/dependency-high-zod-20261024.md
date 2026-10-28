# 最终 High 告警修补

输入是候选ca0e、标准CI40122522919的原始Grype报告：GHSA-mrhx-c7xc-m3w5，Zod3.25.76，High。来源为`ops/turntable-render`的既有Puppeteer→chromium-bidi依赖链。

[官方公告](https://github.com/advisories/GHSA-mrhx-c7xc-m3w5)列出修复版本4.6.6；[上游修补](https://github.com/colinhacks/zod/pull/6652)将容器大小限制提前到元素解析前，避免对超大输入积累验证对象。没有添加豁免、降低严重性或放宽Grype门禁。

既有Puppeteer同主版本25.11.0更新至[官方25.16.0](https://github.com/puppeteer/puppeteer/releases/tag/puppeteer-core-v25.16.0)，保持Node>=22.12。仅用npm的package-lock-only/ignore-scripts生成锁：chromium-bidi157.0.8090-0、Zod4.6.7。没有强行将旧BiDi的Zod3范围override到Zod4，也未新增直接依赖。

最后统一CI在原依赖job安装冻结锁、检查Puppeteer API与原render自检，并从实际BiDi依赖位置加载Zod，验证超长数组不进入元素解析，合法输入仍解析。源码交付时NOT_RUN，Grype和组合回归结果待最终同SHA验收。

现有Chrome154真实24帧渲染及生产同步未执行；CLI/GLB/沙箱/超时限制未改。回滚只恢复本次package/lock文件及CI接线；回滚会恢复原告警，不能作为通过依据，不执行生产操作。
