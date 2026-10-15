# 追加六路 Cloud 启动包

2026-10-15。用户要求追加六路，继续修复现有 bug 与共用后端架构，并避免与原 Cloud、Claude、iOS 重叠。

**状态：PENDING_CLOUD_CREATION_AND_ACK。六路尚未启动。** 原 C00 已在控制提交 `b8541ad2` 发布明确委派，每路只拥有一个全新测试文件和自己的交付 README。业务源码仍归原写者；发现缺陷后由 C00 安排原写者修复，或履行精确文件转交后由追加方修复。

唯一权威白名单：[EXTERNAL_SIX_HANDOFF.md](https://github.com/liboyang42-cpu/hengyin/blob/codex/mini-bugs-backend-cloud-plan-20261015/docs/plans/mini-codex-cloud-20261015/EXTERNAL_SIX_HANDOFF.md)。开始前读取其最新版本及 OWNERSHIP，不以本启动包扩大写权。

| 线路 | 具体任务 | 唯一新测试文件 |
|---|---|---|
| E01 | 排期时刻、报名与候补保护的真实 MySQL 缺口 | chengyinhub-admin/src/test/java/com/chengyinhub/business/service/B04ScheduleReadbackMySqlIT.java |
| E02 | 旧详情、编辑历史、退出社区与两身份隐私隔离 | chengyinhub-admin/src/test/java/com/chengyinhub/business/service/B05CommunityVisibilityMySqlIT.java |
| E03 | 结局正文、内容版本、旧快照兼容 | chengyinhub-admin/src/test/java/com/chengyinhub/business/service/A03EndingSnapshotMySqlIT.java |
| E04 | 券限领、库存、退款冲正的剩余竞态 | chengyinhub-admin/src/test/java/com/chengyinhub/business/service/B03CouponLifecycleMySqlIT.java |
| E05 | 退款订阅、收藏列表和订单组件的账号/晚响应行为 | chengyinhub-xcx/tests/unit/b01-refund-account-regression.test.js |
| E06 | 音频、轮询、定位的停止/释放与晚回调行为 | chengyinhub-xcx/tests/unit/b01-platform-lifecycle-regression.test.js |

每路专属 README 的精确路径、已有覆盖与验收条件见原 C00 白名单。旧源码、旧测试、共享 fixture、门禁、配置、依赖、migration 与控制账均无自动写权；已有覆盖充分时交反证，不复制套件或凑 bug。

## 环境与协作

- 公共仓库 liboyang42-cpu/hengyin；起始完整 SHA `1e403b135206844a34219753a36e2dee39c7dd59`，分支 `codex/mini-bug-arch-integration-20261015`。不得使用旧私库历史或未发布 WIP；换基线须由 C00 给公共完整 SHA。
- 原全局 C00：任务“启动 Codex Cloud 后端计划”，threadId `01a13f3d-608a-745c-83d0-c5139121c02d`、hostId `durable`。
- 本地接入/验证方：threadId `01a13eed-e4d9-77f3-b875-c72d26ea7d59`、hostId `local`。用户授权双方直接交流。
- 原 Cloud 的 L01–L05 与 L10 继续原归属；四路尚未启动的 L06–L09 测试缺口交 E01–E04，新旧队伍不同时编辑这些新文件。
- 新 Cloud 先报告真实任务 ID/环境/公共 SHA，再由 C00 登记接收回执；确认后最多六个 gpt-6.1-sol worker。名额与模型不可用须如实报告，不用本地任务冒充云端，不静默换 Astra。
- Cloud 只做源码、轻量 Node 验证；Java/Maven/MySQL/Redis 都由本地验证方在专用测试 VPS 按资源限额串行执行。六路不直连、争用 VPS 或改变网络/SSH；不访问生产。
- 各路使用独立分支；若共享 checkout，则 worker 不自行切分支/暂存/提交，由本队协调者按明确路径串行提交。不得回退他人改动。
- 交付公共完整 SHA、带 E 编号的 CY-HANDOFF 草稿 PR、精确命令/预期数/前提/未验证项，C00 复核形成统一候选，本地复测并反馈，修复后新 SHA 再验。

当前 231 项 VPS 自动化与 12 项 CI 只签收旧冻结基线，不覆盖新测试或未来修复，不代表部署、设备或业务 READY。UI/新功能交 Claude，iOS 独立 agent，Flutter 排除。

## 启动

当前本地工具可读/联系已有 Cloud，但没有直接创建同类 Codex Cloud 的目标；CLI 仅列旧私库环境，浏览器账号显示 Work 需升级，桌面自操作被安全限制。没有升级、改环境或创建本地替代。

用户在已经可用的公开 hengyin Cloud 环境新建一个任务，粘贴 [START_PROMPT.md](START_PROMPT.md)。该任务接收六路白名单并在实际名额内启动，回报真实任务 ID 后本地可直接联系，不需用户反复转述。
