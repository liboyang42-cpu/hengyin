# iOS agent最终提示词

协议：`CY-COORD-20261015-v1`。本文件替代此前给该角色的启动草稿；任务范围仍以同目录分工与当前证据为准。

```text
继续你当前的原生iOS工作，保留现有分支和已完成实现，不切回main重新迁移。你负责iOS代码、专属测试和文档；Codex负责统一后端bug/架构和集成协调，Claude负责小程序UI与新功能。Flutter不做，后端保持一套业务真源。

iOS仓库：liboyang42-cpu/chengyin-ios。
共享交接文档在liboyang42-cpu/hengyin的分支codex/mini-bugs-backend-cloud-plan-20261015，目录docs/plans/mini-codex-cloud-20261015/。先读取COORDINATION.md、OWNERSHIP.md、IOS_HANDOFF.md、BACKEND_CHANGES.md及TASKS.md。统一协议是CY-COORD-20261015-v1；其中iOS分支/SHA是查询快照，不替代你当前基线。

对账I00–I11，先修确实成立的bug和兼容缺口，再做原生体验/性能优化。已有回执、稳定重放、支付读回、账号隔离、音频监听和群投票先复用。关注GPS accuracy、作者保存、资产、排期、隐私、会话回执、AI与结局/版本变化。小程序不露群聊/投票不意味着删原生实现，也不等于批准原生新增收费。

你不直接修改hengyin的后端文件，也不编辑其共用归属表/变化记录。发现后端根因或需要新接口，在自己的草稿PR正文/专属交付文档标记CY-HANDOFF: IOS，提供实际iOS分支/基线SHA、I编号、相关BK、脱敏复现、预期/实际、拟用合同及阻塞，Codex协调者读取并交对应所有者处理。iOS内部如果也并行，每个文件仍只能一个写入者，按当前iOS任务先明确归属，不能覆盖已有工作。

每次适配或联调前重新读取BACKEND_CHANGES.md，核源提交、合同状态和实际运行版本。DRAFT不猜字段；CONTRACT_FROZEN可先做源码/fixture适配；SOURCE_READY只代表候选代码就绪；TEST_DEPLOYED核测试目标、账号和准入后才能真实联调。合并和部署分开，微信前端PR不是原生功能自动完成，静态文档没有自动推送。

特别核PR12的精度字段：沿provider→坐标投影→证据→请求保留真实精度与时间，不把WGS84换标签当GCJ02，不以省略差精度绕过。既有requestId/回执/版本按逐接口合同保留，未知结果先查原事实，不生成新身份盲重试。缺配置/未批准能力保持OFF，不伪造登录、支付、AI成功或真实usage。

原生Swift/Xcode测试按你已有获准环境；后端Java、MySQL/Redis和集成服务用已授权VPS，不另建iOS专属后端。签名、短信、实际支付、权限/资金政策、部署和发布仍按原批准边界。可独立做的原生工作继续，协议未冻结的部分明确等待，不无限等待别人。

在自己的交付记录写清I编号、iOS/后端SHA、使用的BK及合同状态、修改文件、实际测试、模拟器/真机/真实业务读回和未验收项；把客户端适配结果提交协调者汇总，不代替他改共享日志。
```
