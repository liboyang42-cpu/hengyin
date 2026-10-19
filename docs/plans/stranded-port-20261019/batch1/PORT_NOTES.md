# Stranded private work: batch1

Source: private `codex/batch1-cloud-20261012`, merge base `8a180437d1`. Per-file 3-way merge onto `integrate/master-port-20261019`.

| result | files |
|---|---|
| identical | 922 |
| clean | 71 |
| merged | 34 |
| conflict | 57 |
| add_conflict | 0 |
| delete | 0 |
| delete_conflict | 0 |
| reserved | 2 |
| binary_conflict | 0 |

## Excluded on purpose

- Private dev/test VPS relay tooling stays out of the public repo: `ci/mini-vps-relay.mjs`, `ci/tests/mini-vps-relay.test.mjs`, `ci/batch1-vps-verify.sh`, and batch1's change to `ci/upgrade-startup-smoke.sh`, which pointed at a local remote-docker socket. Public CI runs on GitHub-hosted runners only.

## Files with conflict markers

- `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiAiNpcController.java`
- `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiImController.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ClubWaitlistMaintenanceIsolationTransactionTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ClubWaitlistServiceImplTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/RefundPayoutAtomicityContractTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/RegistrationVerifyAndSettleTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/VerificationQrcodeRepairTaskTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/PhotoCheckVisionServiceTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/impl/AiGatewayServiceImplTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/impl/PhotoCheckQuotaPoolTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/AiCreatorServiceImplTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/ImSendIdempotencyMybatisIntegrationTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/ImServiceImplTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/task/RefundReconcileTaskTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiAiNpcVoiceChatFormatTest.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/ClubEventOpsMapper.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/CmsRegistrationMapper.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/ImConversationMemberMapper.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/ImMessageMapper.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/PlayTeamMapper.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/RefundApplicationMapper.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/ICmsRegistrationService.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/IImService.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/AiGatewayService.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/PhotoCheckVisionService.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/impl/AiGatewayServiceImpl.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/AiCreatorServiceImpl.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/BusinessAuditLogQueryServiceImpl.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ClubWaitlistServiceImpl.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsRegistrationServiceImpl.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ImServiceImpl.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/NpcChatService.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/WxContentSecurityServiceImpl.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/task/OrderCloseTask.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/task/RefundReconcileTask.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/task/VerificationQrcodeRepairTask.java`
- `chengyinhub-system/src/main/resources/mapper/business/ClubEventOpsMapper.xml`
- `chengyinhub-system/src/main/resources/mapper/business/CmsRegistrationMapper.xml`
- `chengyinhub-system/src/main/resources/mapper/business/ImConversationMemberMapper.xml`
- `chengyinhub-system/src/main/resources/mapper/business/ImMessageMapper.xml`
- `chengyinhub-system/src/main/resources/mapper/business/MerchantSubscriptionMapper.xml`
- `chengyinhub-system/src/main/resources/mapper/business/NpcChatRequestMapper.xml`
- `chengyinhub-system/src/main/resources/mapper/business/PlayTeamMapper.xml`
- `chengyinhub-system/src/main/resources/mapper/business/RefundApplicationMapper.xml`
- `chengyinhub-xcx/pages/privacy/index.js`
- `chengyinhub-xcx/pages/publish/fabu/index.js`
- `chengyinhub-xcx/pages/roam/index.js`
- `chengyinhub-xcx/subpackageB/pages/im/chat/index.js`
- `chengyinhub-xcx/tests/unit/analytics.test.js`
- `chengyinhub-xcx/tests/unit/c06-state-and-exit-contract.test.js`
- `chengyinhub-xcx/tests/unit/im-uiux-recovery-contract.test.js`
- `chengyinhub-xcx/tests/unit/roam-fog-fallback.test.js`
- `chengyinhub-xcx/utils/analytics.js`
- `chengyinhub-xcx/utils/location/bg-tracker.js`
- `chengyinhub-xcx/utils/roam-player-memory.js`
- `chengyinhub-xcx/utils/roam-tile-memory-sync.js`
- `chengyinhub-xcx/utils/transport/upload-client.js`

## C00-reserved (proposed hunks under `c00/`)

- `chengyinhub-xcx/app.js`
- `chengyinhub-xcx/scripts/uiaudit/action-ledger.json`

## Source commits

- `5506cc1738` 10-12 02:33 docs: 固定第一批43项云端并行分工与VPS验收合同
- `12d086c286` 10-12 02:54 fix: 复核第一批底层候选并补充云端交付合同
- `66dc8006d1` 10-12 03:14 fix: 补齐订阅生效与商家头像回调边界
- `f3b390a4dc` 10-12 03:22 docs: 固定商城恢复IM同步和NPC请求底层续做合同
- `81c3211a24` 10-12 03:30 chore: 通过专用VPS runner只读取回已有测试证据
- `7c01e64325` 10-12 03:37 chore: 补专用测试资源退出状态与内存只读取证
- `73d5b2064f` 10-12 03:46 chore: 用专用VPS runner验证隔离审阅候选
- `32bb7987d3` 10-12 03:57 chore: 更新NPC编译与人设指纹审阅候选
- `d2dd9fe599` 10-12 04:01 chore: 明确未调用供应商的过期租约候选边界
- `878bf5c3fe` 10-12 04:10 chore: 用现有JDK摘要逻辑修正人设指纹候选
- `d9859cf132` 10-12 04:33 chore: 对齐订单插入测试前置并加强失败拒绝断言
- `878ab79a20` 10-12 04:34 chore: 固定含完整Mockito导入的审阅候选
- `4bfb850096` 10-12 04:40 chore: 修正失败插入测试的Mockito覆盖方式
- `9b42d71c32` 10-12 04:53 fix: 补齐购物车回执、消息补拉与NPC请求恢复基础
- `e9b7555677` 10-12 04:55 fix: 消息渲染完成后再提交已读游标
- `e74c8a78af` 10-12 04:58 docs: 固化草稿公开规则与第四轮补证合同
- `ce15bb24ba` 10-12 05:16 chore: 补回工作簿证据并准备第四轮VPS补证
- `8b9d20fa95` 10-12 05:23 chore: 修正AI测试配置并隔离本轮测试日志
- `234f681478` 10-12 05:33 chore: 补额度原子退还验证与数据库测试装配
- `d172fd8721` 10-12 05:40 chore: 适配拍照额度退还测试到原子执行
- `c21b26a1ea` 10-12 05:51 chore: 固化NPC租约断点修复与消息并发补证候选
- `2cd6329877` 10-12 06:32 fix: 补齐消息恢复、AI全文检查与租约额度原子边界
- `5ed9b45b1e` 10-12 07:36 docs: 固化六包云端合同与App原始适配快照
- `9b07dac4c7` 10-12 07:45 docs: 校正实施证据并冻结位置卡片VPS验证候选
- `5c17fb35de` 10-12 07:51 docs: 保存六包候选与A和D完整链路补齐合同
- `ab01e9ea2b` 10-12 08:55 docs：交接云端实现与本地联调分工及当前候选
- `57a6d28a24` 10-12 09:01 ci：专用VPS复验当前候选及购物车全资源事务
- `2445c4b3b7` 10-12 10:22 docs：核对本地VPS与App证据并更新原改进表
- `bd4ac86edb` 10-12 11:00 fix: 补齐小程序消息回包隔离并交接App收口
- `102683c8a7` 10-12 11:13 fix: 校验小程序消息持久回读并更新原改进表
- `06e8ae30c5` 10-12 11:56 fix: 保留漫游持久化失败增量并刷新动作台账
- `36ca48f893` 10-12 12:32 fix: 修补小程序事件队列持久化与回执出队
- `45935464fd` 10-12 12:48 fix: 防止小程序上传迟到回包改写结果并校验HTTP终态
- `ecec222967` 10-12 12:49 docs: 核对小程序上传终态证据并更新X019
- `c1a0aee810` 10-12 12:59 fix: 核对小程序漫游原会话回包并保留未确认格子
- `1978102d45` 10-12 13:05 fix: 漫游既有会话写结果未知时先核对原状态
- `3c73a15250` 10-12 13:08 docs: 更新漫游原会话恢复证据与未闭合断点
- `6044f5650d` 10-12 13:26 fix: 校验小程序草稿回读身份并保留保存期间的新修改
- `ad88d48166` 10-12 13:29 docs: 更新主题草稿终态证据与完整链路断点
- `33442af989` 10-12 14:17 docs: 核对小程序VPS候选打包与隔离库迁移准备
- `ba41d262f5` 10-12 14:54 docs: 核对小程序候选启动并补齐隔离库运行依赖
- `16a24d3084` 10-12 15:02 fix: 草稿回读保留完整章节并拒绝重复编号
- `b199f980c2` 10-12 15:07 docs: 回填小程序草稿章节回读补修证据
- `c879ddcd40` 10-12 15:20 fix: 游戏请求启动异常保留原命令与未知状态
- `ed321a0e3e` 10-12 15:25 docs: 回填小程序游戏命令异常与快照检查证据
- `58a8c4665c` 10-12 15:53 fix: 导演恢复记录写前保护并核对落盘结果
- `fd987e0511` 10-12 16:01 docs: 回填导演恢复记录存读与原请求保护证据
- `a892aa38ba` 10-12 16:28 fix: 隔离候补晋级范围解析失败并保留后续处理
- `118f27bf16` 10-12 16:31 docs: 回填小程序候补隔离证据并暂停App商城范围
- `d07992d249` 10-12 16:43 fix: 统一商家权益结束边界并补真实MySQL回归
- `ceba8dd20f` 10-12 16:46 docs: 回填小程序权益时间边界的真实MySQL证据
- `252771321b` 10-12 17:07 docs: 固定小程序联调候选版本并回填六项运行证据
- `d5e750ef89` 10-12 17:21 fix: 收口已确认的AI审核分类与原次数恢复
- `03064e733d` 10-12 17:29 docs: 回填小程序审核独立验证与六文件收口证据
- `cb2331f0a0` 10-12 18:04 fix: 收口通知原事件回执并纠正未知响应及旧接受事实
- `41bc95fea6` 10-12 18:11 docs: 回填通知结果修补与真实数据库恢复证据
- `7f242d74ab` 10-12 18:30 fix: 拒绝小程序消息位置卡非有限坐标
- `621eabebe8` 10-12 18:46 docs: 收口小程序已提交版本联调并保留真实身份断点
- `c5035c21d7` 10-12 18:46 docs: 保留小程序候选历次运行快照
- `ba68e01dbe` 10-12 19:08 chore: 增加小程序候选VPS字节通道与严格预检
- `c0485be396` 10-12 19:15 docs: 回填小程序VPS字节通道与Mac鉴权证据
- `641893be81` 10-12 19:52 fix: 隔离小程序发布旧回调并校验草稿章节归属
- `5169f8efe0` 10-12 19:53 fix: 绑定小程序上传批次身份并拒绝临时照片回执
- `a0873a7f0d` 10-12 19:55 fix: 按稳定游标遍历候补维护积压并保留事务隔离
- `8ac8f79783` 10-12 20:08 fix: 按当前路线资格投影小程序历史卡片和会话摘要
- `81f91292e4` 10-12 21:37 fix: 保留小程序漫游恢复数据并隔离导演迟到回执
- `97cb0022ef` 10-12 21:45 docs: 回填六线修补证据并标明测试数据库中断
- `d12bc3323e` 10-12 21:47 docs: 保存额度与票务候选合同及未实跑断点
- `7b0456d35d` 10-12 22:40 fix: 撤回漫游定位时阻断迟到启动并保留游戏定位
- `4206a0be8d` 10-12 22:54 fix: 修补小程序消息权限票务补偿与AI额度恢复
- `163434e6cb` 10-12 23:01 docs: 回填六线实测证据与小程序待验断点

