# CI 执行方式

- PR：快速门、完整静态门、影响测试。测试按相对 require 依赖及同名源文件关联；动态加载、文件读取等无法可靠排除的测试一律纳入。目标为 10 分钟内，实际耗时以 run 为准。
- 后端 PR：只测试改动模块及 Maven 必需依赖。支付、报名、结算、退款、通行证等资金域、共享基础层和 SQL 改动同时执行完整后端与 Funds MySQL IT。
- master 每次 push：20 个小程序分片、完整后端、资金真库、Redis 恢复和 Followup 全量运行。`Master full CI` 或任一真库／恢复检查变红，必须立即修复；PR 绿不代表 master 全量已通过。
- 每日北京时间 03:17，`Nightly full CI dispatch` 派发上述四条完整工作流。派发成功只表示已启动，测试结论看各实际 run；未触发任何生产部署。
- Node 测试默认每项 60 秒，整个分片另有 8 分钟硬上限，模块初始化卡住或遗留句柄不能再挂满 30 分钟。新 PR 提交自动取消该 PR 的旧 run。

PR 合并规则：`Mini program static checks`（聚合所有适用快速检查）与两个既有 Funds required checks 均绿。保留原 required-check 名称；小程序分片是动态数量，不应逐项加入 branch protection。资金 PR 必须等完整后端和真库执行完成。

动作台账、路由矩阵和当前截图影响矩阵不再提交。CI 按当前 checkout SHA 生成台账与路由，校验控件映射／唯一性及原证据 overlay，后续 job 只下载同一 SHA 的生成物，并校验完整源码指纹、产物摘要和影响计划摘要，拒绝源码或产物漂移。真实截图、人工证据、覆盖豁免和债务基线仍保留版本控制，不以重建库存冒充验收。

本机跑小程序测试前执行 `node ci/prepare-mini-artifacts.js`，然后照常运行测试或 `bash ci/xcx-check.sh`。`npm run test:unit`、`npm run lint` 会自动准备生成物；构建和 Java 测试仍在专用 VPS 或 GitHub-hosted runner。

UI 登记使用“文件＋所属方法＋调用代码摘要＋相同调用序号”，不依赖行号。挪动未改代码不改登记键；新增调用、改代码或换所属方法仍判新 finding。理由、过期项清理、键集合摘要及只减不增的数值棘轮继续生效。不要通过重建基线消除新 finding。
