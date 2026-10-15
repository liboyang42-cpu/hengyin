# 追加六路 Cloud 启动消息

请接手城瘾追加六路 Cloud 小队。只修现有 bug、补共用后端架构与真实回归缺口，不做 UI 或新产品功能，不改 iOS/Flutter。

我授权你直接与以下两位协调者沟通、回报和交接：
- 原全局 C00：“启动 Codex Cloud 后端计划”，threadId 01a13f3d-608a-745c-83d0-c5139121c02d，hostId durable。
- 本地接入/VPS 验证方：threadId 01a13eed-e4d9-77f3-b875-c72d26ea7d59，hostId local。

仓库仅用 liboyang42-cpu/hengyin，起始完整 SHA：1e403b135206844a34219753a36e2dee39c7dd59，分支 codex/mini-bug-arch-integration-20261015。不要使用旧私库或未发布 WIP。

先读取原控制分支 codex/mini-bugs-backend-cloud-plan-20261015 的最新 EXTERNAL_SIX_HANDOFF.md、OWNERSHIP.md、FOLLOWUP_BUG_LANES.md、BACKEND_CHANGES.md 和根/就近 AGENTS。控制目录为 docs/plans/mini-codex-cloud-20261015/；新六路明确委派已在 b8541ad2 发布，现为等待创建与接收，不代表已启动。

按白名单接收六路：
E01 排期时刻、报名/候补保护的真实 MySQL 缺口。
E02 旧详情、编辑历史、退出社区和两身份隐私隔离。
E03 结局正文、内容版本、旧快照兼容。
E04 券限领、库存、退款冲正的剩余竞态。
E05 退款订阅、收藏列表、订单组件账号与晚响应行为。
E06 音频、轮询、定位停止/释放后的晚回调行为。

先向上述两位协调者报告真实 Cloud 任务 ID、环境、完整 SHA、各路精确路径及接收回执，确认后启动最多六个 gpt-6.1-sol worker。模型或名额不足如实报告，不用本地任务代替、不静默换 Astra。

每路仅可新增 EXTERNAL_SIX_HANDOFF 中指定的一个测试文件和专属 README；所有业务源码及既有测试仍归原写者，尤其不碰原 L01–L05、L10、Claude 和 iOS 的文件。先核已有覆盖，补实际未覆盖行为，不复制套件凑数；发现缺陷把复现、失败用例和最小方案交原 C00，由其安排原写者修复或明确转交源码后再修。不要自行扩写权限、资金/退款政策、接口、依赖、migration 或门禁。

Cloud 只做源码工作和允许的轻量 Node 检查。Java/Maven、MySQL、Redis 一律交本地验证方在专用测试 VPS 串行执行；你和 worker 不运行这些服务、不直连 VPS、不改变网络/SSH、不访问生产。

六路使用独立分支；共享 checkout 的 worker 不切分支、不暂存/提交，由本队协调者按精确路径串行提交。每路交带 E 编号与 CY-HANDOFF 标记的草稿 PR、完整公共 SHA、实际结果、VPS 精确命令/预期方法数/环境前提及未验证项。C00 复核后形成统一候选，本地验证方复测，失败继续反馈原写者修复，直到本队任务闭环。

不要改原控制账或自行合并/部署/上传。旧基线的 231 项通过不能套用新 SHA；SOURCE_READY、VPS 自动化、TEST_DEPLOYED、设备和真实业务验收分别报告。现在开始接收任务并回报真实状态，不要只复述计划。
