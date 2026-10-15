# 给iOS独立agent的交接提示词

把下面整段发给已经在做iOS的agent。它接续原任务，不要求切到小程序文档分支开发iOS。

```text
继续你正在做的iOS工作，保留当前分支、未提交改动和既有实现。另一个Codex Cloud负责小程序现有bug和统一后端架构，Claude负责小程序UI与新功能；他们不会修改iOS代码，Flutter仍不在本轮范围。此前文档“不做iOS”仅指他们的任务包，不是让你停止iOS。

先读取公共仓库 liboyang42-cpu/hengyin 的分支 codex/mini-bugs-backend-cloud-plan-20261015：
- docs/plans/mini-codex-cloud-20261015/IOS_HANDOFF.md
- docs/plans/mini-codex-cloud-20261015/BACKEND_CHANGES.md
- docs/plans/mini-codex-cloud-20261015/TASKS.md 与 tasks.json（后端文件归属）

iOS仓库是 liboyang42-cpu/chengyin-ios。先登记你实际分支/SHA和已完成工作，不因交接改回main或重新做迁移。交接核过main a8d387e及migration/native-ios f0df085；它们只是查询快照，不替代你当前基线。

把I00–I11纳入你现有计划：先复核确实成立的bug与合同缺口，再做原生体验/性能优化；已有回执、支付读回、账号隔离、音频中断和群投票等先复用。重点核PR12的GPS accuracy、显式点赞方向和平台徽章范围，以及B02保存读回、B03资产、B04排期、B05隐私、A01回执、A02 AI和A03结局/版本兼容对原生的影响。

当前PR12–15只是草稿代码，不能当服务端已部署。原生PlayCompletionEvidence.location目前没有accuracy字段，而后端PR12准备支持该字段；沿provider→坐标投影→证据→请求核精度与样本时间，不把WGS84改标签当GCJ02，不以省略差精度绕过。具体适配以当前共享合同和目标测试部署为准。

你只改iOS源码、专属测试和文档，不直接并行改hengyin里Codex独占的后端文件。后端根因提交脱敏复现与所属BK任务，由其所有者修复；需要新接口先协调统一合同，不造iOS专用业务真源。小程序不做会费/群聊/投票不能套成删除原生已有模块，也不等于批准原生开收费。

可先独立完成已有原生bug、兼容测试、离页/账号切换/音频/地图/VoiceOver/动态字体等检查；后端未冻结的字段只准备适配，不猜接口、不开放当前OFF的能力，不改变既有支付、身份、权限、签名和发布策略。每次适配或联调前重新读BACKEND_CHANGES.md并核实际代码/部署版本；没有自动消息推送。

后端Java/数据库/Redis及集成服务仍用已授权测试VPS；原生Swift/Xcode测试按你已有获准环境。交付I编号状态表、精确iOS/后端SHA、修改文件、实际测试和独立业务读回，分清源码/构建/CI/VPS/模拟器/真机/发布。先接续已授权独立工作；真正需要新增产品/权限/资金决策时才提具体方案。
```
