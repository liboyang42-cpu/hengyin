# 追加六路 Cloud：启动准备与防重叠边界

2026-10-15。用户要求在既有 Cloud 之外追加六路，继续修复现有 bug 和共用后端架构；不做新产品功能、UI、iOS 或 Flutter。

**当前状态：PREPARED_NOT_STARTED。六路未启动、未取得代码写权。** 本页是具体交接提案，不取代原 C00 的文件所有权表，也不证明额外六路可同时获得运行名额。

## 已核实的基线与现有线路

- 公共仓库：[liboyang42-cpu/hengyin](https://github.com/liboyang42-cpu/hengyin)。起始候选 `1e403b135206844a34219753a36e2dee39c7dd59`，本轮 VPS 自动化 231 项与 12 项 CI 检查通过；此证据不沿用到未来源码提交。
- 唯一全局协调者：Cloud 任务“启动 Codex Cloud 后端计划”，threadId `01a13f3d-608a-745c-83d0-c5139121c02d`、hostId `durable`。
- 本地验证/追加任务接入方：threadId `01a13eed-e4d9-77f3-b875-c72d26ea7d59`、hostId `local`。用户已授权双方直接交流。
- 现有后续分支 `codex/mini-bug-arch-followups-20261015`，启动基线为上述 SHA；不能把工作区未提交变化当新公共基线。
- 已读原控制文件：[FOLLOWUP_BUG_LANES.md](https://github.com/liboyang42-cpu/hengyin/blob/670d07ed/docs/plans/mini-codex-cloud-20261015/FOLLOWUP_BUG_LANES.md)及同版本 tasks.json。实际启动前必须重新读最新 [OWNERSHIP.md](https://github.com/liboyang42-cpu/hengyin/blob/codex/mini-bugs-backend-cloud-plan-20261015/docs/plans/mini-codex-cloud-20261015/OWNERSHIP.md)。

L01 会话 UNKNOWN/回执单调、L02 作者聚合详情、L03 NPC 删除屏障、L04 AI 日志未知用量、L05 作者写入 UNKNOWN 已分配原 Cloud；追加方不得编辑这些线路的源码或测试。L10 反馈修复槽仍由原 C00 保留。Claude 和独立 iOS 的文件锁继续有效。

## 六路具体提案

下列路径全部是**候选白名单**，只有原 C00 在唯一控制账写明新所有者、实际路径、基线以及旧写者停止/转交后，才成为写入许可。QUEUED 不表示无所有者；同目录不等于整目录授予。

| 追加槽 | 拟承接内容 | 原关联 | 实际启动条件 |
|---|---|---|---|
| E01 | 既有排期时刻、候补保护、物化并发的真实 MySQL 缺口 | L06 | C00 明确将未启动任务及精确路径转交 |
| E02 | 既有隐私、旧详情、退出社区与引用访问的真实 MySQL 隔离缺口 | L07 | C00 转交；新增鉴权政策必须另获批准 |
| E03 | 结局选择、内容版本与历史快照的剩余兼容缺口 | L08 | C00 转交；不碰 L01/L02 当前持有文件 |
| E04 | 既有优惠券限领、库存、退款冲正的剩余竞态缺口 | L09 | C00 转交；不重做已通过的积分/应付修复 |
| E05 | 已有退款订阅、收藏刷新和订单组件账号作用域回归与可复现 bug | B01 已集成部分 | C00 单独确认这些文件不被 L01/L05 或原写者继续占用 |
| E06 | 已有音频、轮询和定位公共模块生命周期回归与可复现 bug | PR13/B01 已集成部分 | C00 确认 helper 与专属测试写权，冻结对调用者的合同 |

### E01 拟议路径

- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ClubEventScheduleMaterializer.java`
- `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiClubEventOpsController.java`
- 既有 `ClubEventScheduleMaterializerTest.java`、`ApiClubEventOpsControllerTest.java` 及 C00 逐项登记的专属 MySQL 测试；Mapper/DTO 不自动连带授权。

### E02 拟议路径

- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CommunityPostServiceImpl.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/CommunityPostLifecyclePolicy.java`
- `chengyinhub-system/src/main/resources/mapper/business/CommunityPostMapper.xml`
- 对应既有社区生命周期/Service 测试及单独登记的新真实 MySQL 套件；媒体上传、对象存储、其他内容栈不自动授权。

### E03 拟议路径

- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ChapterFlowCompiler.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/JourneyStoryValidator.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/ThemeRuntimeConfig.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/ThemeRuntimeEngine.java`
- 对应专属兼容测试逐项登记；`ApiTopicController.java`、`pages/play/index.js`、`player-game-module.js` 明确排除。需要其他运行时文件则先申请，不创建第二个运行时。

### E04 拟议路径

- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/SmsCouponHistoryServiceImpl.java`
- `chengyinhub-system/src/main/resources/mapper/business/PmsSkuMapper.xml`
- 对应券/库存/退款链其他实际文件与专属测试必须先列明、比对所有权后再领。资金政策、支付协议、SQL migration、PointsAward/MerchantPayable 既有文件不自动归入。

### E05 拟议路径

- `chengyinhub-xcx/utils/subscribe.js`
- `chengyinhub-xcx/pages/mylike/mylike.js`
- `chengyinhub-xcx/subpackageMember/components/cy/scene-member-order-detail/index.js`
- `chengyinhub-xcx/subpackageMember/components/cy/scene-member-order-history/index.js`
- `chengyinhub-xcx/subpackageMember/components/scene-member-participation-detail/index.js`
- 既有 subscribe/mylike 相关测试及独占 E05 新测试路径。只有真实复现且原修复未覆盖才编码；拒绝授权不阻断成立的退款，不承诺订阅送达。

### E06 拟议路径

- `chengyinhub-xcx/utils/audio-session.js`
- `chengyinhub-xcx/utils/poller.js`
- `chengyinhub-xcx/utils/location/location-manager.js`
- `chengyinhub-xcx/utils/location/bg-tracker.js`
- `chengyinhub-xcx/utils/location/last-location.js`
- 对应 audio-session/poller/location 专属测试。先核 PR13 与 B01 既有修复，禁止复制 helper。页面调用方、WXML/WXSS、后端定位校验及新 UI 不自动授权。

## 公共规则与交付

1. 原 C00 继续唯一维护 `docs/plans/mini-codex-cloud-20261015/`、共享合同、公共配置与集成分支。追加协调者管理本队，不另建相互矛盾的总表。
2. 每一路必须拥有精确源码及测试路径，一文件一写者。原写者停止、补丁交付和新基线未齐全时不得抢写；不能因原任务静默或排队自动释放。
3. 新 Cloud 使用公共仓库的独立工作区，分支采用 `codex/extra6-<lane>-20261015`。同一工作区的 worker 不自行切分支、暂存、提交；由该队协调者按明确路径串行提交。不同独立工作区也不得改他队文件。
4. 显式使用用户已指定的 `gpt-6.1-sol` worker；若不可用如实报告，不静默换 Astra。实际能启动多少与任务/账户运行限制分别报告，不把创建、排队、运行混为一谈。
5. 后端 Java 构建/测试、MySQL、Redis 全部由本地验证线程在专用测试 VPS 串行集中执行，追加六路不直连或各自抢占 VPS，不在 Mac/Cloud 运行 Java/数据库。Node 等允许的轻量源码验证可按现有项目规则执行。
6. 云端交付公共完整 SHA、最小测试矩阵、逐类期望数、环境前提与未验收项。本地验证失败直接反馈原写者，修复后交新 SHA；不得关闭测试、减分母或放宽断言。
7. 无真实缺口时交“已修/误报/未复现”的源码和验证证据，不为填满六个名额制造改动。新鉴权/权限、依赖、跨模块大改、资金或产品政策、业务 DDL 按现有要求另行决定；生产始终不在范围。

## 创建入口的实际阻塞

本地任务可以读取/联系现有 Codex Cloud，但当前暴露的任务创建工具没有同类 Codex Cloud 目标。CLI 环境选择器目前只列旧私有 chengyin/chengyin-app 环境，不能把它们当作公开 hengyin 环境；现有浏览器账号显示 Work 需升级，桌面应用自身 UI 自动化被安全限制拦住。没有切换旧私库远端、修改环境、升级账户或创建本地替代任务。

因此用户需在已经可用的公开 hengyin Cloud 环境新建一个 Cloud 任务，交本地协调方任务链接/名称。一个新 Cloud 协调任务可在其实际名额内管理六路；是否额外六路同时运行必须由真实状态验证。启动文本见 [START_PROMPT.md](START_PROMPT.md)。
