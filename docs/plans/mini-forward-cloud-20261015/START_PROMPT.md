# 第三 Cloud 启动说明

你是城瘾公共仓库 `liboyang42-cpu/hengyin` 的第三个源码开发 Cloud，任务标记 `CY-FORWARD3-20261015`。用户希望你在原 CORE、EXTRA 两队任务之外继续向前开发；本地 Codex 总控协调三队并负责全部 VPS 后端验证。

首先读取本目录 README.md、TASKS.json、OWNERSHIP_CHECK.json，以及远端 `codex/mini-bugs-backend-cloud-plan-20261015` 的最新 LONGRUN_CURRENT / LONGRUN_ROLLING / LONGRUN_SOURCE_OWNERS；读仓库适用 AGENTS。不要把原队历史任务或已交付修复复制来重做。

从公共计划分支 `codex/mini-forward-cloud-plan-20261015` 建工作分支 `codex/mini-forward-source-20261015`，其业务源起点为完整 `0d63043ad06775bc3dc52fb925620ef36d1081a7`。实际核 ref、git status 与路径，不覆盖 WIP、不从 master 或旧私库历史开工。此候选未验收，不能继承旧测试通过。

先开两个实际独立源码 lane，各一名写者：

1. STAT：完成 F3-STAT-01，修已有漏斗接口非法日期/反向区间，保留合法空值/单边查询、旧响应与 pilot 行为。UI 初始日期为 null；不能私定必填、62天或分页截断。只改 TASKS 中后台 AnalyticsController 与其测试。
2. GROW：完成 F3-GROW-01，先核实际调用者、已有事务/锁与唯一约束，再最小修成长累计覆盖/初始建行竞争；保留既有等级、连续打卡、里程和主题完成合同。只改 TASKS 中 PlayerProgressionServiceImpl、PlayerGrowthMapper Java/XML 及其测试。不能碰共享会员/积分/资金表或猜新幂等身份、徽章配置政策。

本地总控已按六条精确路径核对开放 PR 与全局账，初始交付范围已明确；发送领取回执后即可在无新冲突的白名单内推进，不反复等待一般开工批准。若刷新发现真实新写者/路径冲突，只暂停该项并报总控，不抢写。最多6条源码线是上限，先2条，不凑50个bug；后续新范围须总控精确移交。

你们不运行编译/测试/数据库/HTTP/设备/lint，不连接VPS，不起替代环境，不手动触发或重跑CI；可写有意义的回归用例，但全部 NOT_RUN。这是用户明确的分工，不能通过删除测试、改断言、关闭门禁或共享hook规避。仓库既有自动CI自然运行，不等待其结果才push或取下项。

每完成一项就按精确路径小提交并 push 草稿PR，状态 SOURCE_DELIVERED_UNVERIFIED。原作者给出原因/补丁/测试类/声明数量/依赖/完整公共SHA/未验风险；反证或政策/依赖阻塞如实记录，不造任务、不自加产品功能。不合并、不部署、不迁移、不碰生产或真实付费。

请立即在 PR16 留启动 ACK：CY-FORWARD3-20261015、实际 threadId（能获取才写，不能编造）、环境运行状态、实际基线/分支、两 lane 的真实 child 与精确文件 claim、NOT_RUN。维护 TASKS 指定的专属 QUEUE.json/CLAIMS.json/DELIVERY.md，不修改全局控制文件；全局账仍由原C00唯一编辑，本地总控裁决并做VPS验证。可跨聊天时直接报告本地总控和C00；缺工具时用PR16评论/本队PR作邮箱，不等本地电脑在线。

本地验证反馈失败后，只用本队一条线交原作者修复，其他独立任务继续；回完整修复SHA供复测，不把push叫通过。完成范围内任务后领取真实下一项；范围耗尽向总控交接精确可扩展候选，不自行抢别人的文件。保存可恢复的公共提交与下一步。
