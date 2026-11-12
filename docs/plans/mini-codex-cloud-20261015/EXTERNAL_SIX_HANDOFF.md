# 六路独立Cloud交接白名单

CY-HANDOFF: CODEX；C00唯一源码/控制账协调者。此表为明确委派，**PENDING_CLOUD_CREATION_AND_ACK，不代表任务已启动或对方已收到**。本地验证协调者可把本表直接交新hengyin Cloud，再由该Cloud最多6个gpt-6.1-sol worker执行；禁止Astra。未取得正确Cloud创建入口，不创建本地替代任务。

公共工作基线：`liboyang42-cpu/hengyin@1e403b135206844a34219753a36e2dee39c7dd59`，分支`codex/mini-bug-arch-integration-20261015`，PR18冻结。新worker独立checkout/分支，只从公共SHA读取；当前C00后续分支`codex/mini-bug-arch-followups-20261015`尚未发布新源码候选，WIP不算基线。

本Cloud实际五路：L01/B01_sol（补丁完成待C00复核），L02/feature_points_rules_sol（补丁完成静态复核），L03/A02_sol（实现与真库夹具），L04/feature_coorganizer_sol（补丁完成待C00复核），L05/publish_unknown_sol（实现/Node回归）；精确源码锁见[FOLLOWUP_BUG_LANES.md](FOLLOWUP_BUG_LANES.md)。L10回归修复槽保留，不向外转交。现有源码无一释放，新Cloud不能覆盖。

## 仅允许以下精确新文件

每路仅其新测试＋专属README可写，其他路径一律只读。已有覆盖充分时提交反证/审计即可，不能为凑线路复制套件。新测试可以先揭示失败，不能放宽断言使既有缺陷绿。不得创建同义后端接口或新产品；源码修改须先给C00复现、最小方案及精确路径，由原写者修或正式转交。

### E01 承接L06：排期/候补真实MySQL读回

拟唯一写者：`external_E01`；状态：`PENDING_CLOUD_CREATION_AND_ACK`。

- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/B04ScheduleReadbackMySqlIT.java`
- `docs/verification/mini-bug-arch-followups-20261015/EXTERNAL_E01_SCHEDULE/README.md`

核已有B04ClubEventScheduleReadbackTest和ClubEventOps真库覆盖；只补真实Mapper/Service跨时区时刻、null混排、报名/候补/结算保护缺口，不以mock称真库。

### E02 承接L07：隐私/旧详情/退出社区隔离

拟唯一写者：`external_E02`；状态：`PENDING_CLOUD_CREATION_AND_ACK`。

- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/B05CommunityVisibilityMySqlIT.java`
- `docs/verification/mini-bug-arch-followups-20261015/EXTERNAL_E02_PRIVACY/README.md`

先复用CommunityPostMapperMyBatisTest；仅验证既定已删除作者不可见/非作者状态/历史编辑及退出社区边界，不静默改变RBAC/隐私政策。

### E03 承接L08：结局/版本/历史快照兼容

拟唯一写者：`external_E03`；状态：`PENDING_CLOUD_CREATION_AND_ACK`。

- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/A03EndingSnapshotMySqlIT.java`
- `docs/verification/mini-bug-arch-followups-20261015/EXTERNAL_E03_ENDING/README.md`

先核JourneyFullFlowMySqlIT/AdvancedGameRuntime等真实覆盖；冻结正文/只投影选中章/缺键兼容/布尔fallback，保持首末命中及priority，不再造会话真源。

### E04 承接L09：券库存/限领/退款冲正

拟唯一写者：`external_E04`；状态：`PENDING_CLOUD_CREATION_AND_ACK`。

- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/B03CouponLifecycleMySqlIT.java`
- `docs/verification/mini-bug-arch-followups-20261015/EXTERNAL_E04_COUPON/README.md`

先审已有券并发、库存/限领、RedemptionRewardClawback真库测试；仅补未覆盖实际调用/数据库约束缺口，缺requestId本身不是bug，不定新退款政策。

### E05 退款订阅/列表账号隔离剩余bug

拟唯一写者：`external_E05`；状态：`PENDING_CLOUD_CREATION_AND_ACK`。

- `chengyinhub-xcx/tests/unit/b01-refund-account-regression.test.js`
- `docs/verification/mini-bug-arch-followups-20261015/EXTERNAL_E05_REFUND_ACCOUNT/README.md`

只读utils/subscribe.js、pages/mylike/mylike.js及三个已集成退款组件真实手势/晚回调，VM行为验证允许/拒绝订阅均不阻断退款、稳定ID/换号隔离；不重复L01/L05、不写Claude UI。

### E06 音频/轮询/GPS基础模块剩余bug

拟唯一写者：`external_E06`；状态：`PENDING_CLOUD_CREATION_AND_ACK`。

- `chengyinhub-xcx/tests/unit/b01-platform-lifecycle-regression.test.js`
- `docs/verification/mini-bug-arch-followups-20261015/EXTERNAL_E06_PLATFORM/README.md`

只读utils/audio-session.js、utils/poller.js、utils/location/{location-manager,bg-tracker,last-location}.js及既有测试；针对stop/dispose后迟到回调与共享消费者释放做可复现行为验证，不扩大GPS用途/权限、不写play/publish/roam/Claude页面。

## 执行与验收约束

- 开始前读根/就近AGENTS及最终START_PROMPT、OWNERSHIP、BACKEND_CHANGES、本表和当前CY-HANDOFF；发现文件已存在或其他申领先报C00，不覆盖。测试文件不存在已由C00检索确认。
- E01–E04只可新增真实集成测试，专用scratch schema建表夹具可以复用既有安全提取；不改base schema、migration、业务DDL、pom/依赖、CI门禁、生产配置或共享测试工具。显式启用时前提不全必须失败，禁止假通过/静默跳过。
- E05/E06只新增Node行为回归，可复用现有VM harness，不把source字符串匹配当业务验证，不改旧已锁测试或前端源码。排除L01 play/player-game-module、L05 publish、Claude所有UI文件。
- Cloud只源码/轻量Node；不跑Java/Maven/数据库，不直连VPS、不改网络/SSH、不访问生产。VPS由本地验证者统一按资源限额串行单Maven容器。
- 每路独立分支交公共完整SHA＋专属PR（CY-HANDOFF: CODEX，标E编号）＋精确变更/测试命令/预期方法数及必要环境；尚未运行写未运行。C00先复核再形成统一候选；不得拿冻结231结果套用新SHA。
- OWNERSHIP、BACKEND_CHANGES、tasks以及全部docs/plans控制目录仅C00写；共享核心、旧文件、其他业务/测试/文档无写权。TEST_DEPLOYED/合并/部署/微信设备/运行时真实业务分开，均不由单测推定。
- 原4路未启动队列由E01–E04承接，C00不再并行派原B04/B05/A03/B03编辑同名新测试。E05/E06属于定向复核，不宣称已经找到新bug。

## 已收到正式回执，C00授权启动（覆盖待创建状态）

已主动读取[正式回执6192515205](https://github.com/liboyang42-cpu/hengyin/pull/16#issuecomment-6192515205)，核12个路径与白名单逐字一致、与L01–L05/L10无交集。新Cloud真实threadId `01a13f9c-e8ba-753e-9c39-03840bb4900b`，host durable；六独立worktree均基于1e403b135206844a34219753a36e2dee39c7dd59。**现确认接收并允许E01–E06按原白名单启动，ACK_RECEIVED_START_AUTHORIZED**；无新增源码/旧测试/控制表写权。启动前0运行/6待启动为回执观测，实际启动数待对方回报，不伪记6运行。必须gpt-6.1-sol；独立分支/串行提交，VPS与新资源守卫由本地协调者统一。无需再等C00一般性确认，具体额外文件仍申请。
