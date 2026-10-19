# Stranded private work: questify-be

Source: private `github/feature/questify-platform-integration` (tip `57b22916e0`), merge base `5e23cd5228`. Carried as a per-file 3-way merge onto `integrate/master-port-20261019`.

| result | files |
|---|---|
| identical | 0 |
| clean | 633 |
| merged | 76 |
| conflict | 43 |
| add_conflict | 0 |
| delete | 0 |
| delete_conflict | 0 |
| reserved | 0 |
| binary_conflict | 0 |

## Files with conflict markers (resolve all; `git grep -n '^<<<<<<< public'`)

- `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiAiNpcController.java`
- `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiPlayProgressController.java`
- `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiTemplateController.java`
- `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiThemeRuntimeController.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/it/AdvancedGameRuntimeMySqlIT.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/it/JourneyFullFlowMySqlIT.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/it/TemplateMarketLifecycleMySqlIT.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/it/ThemeRuntimeMySqlIT.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/CmsTopicPublishDraftTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/RegistrationFundsMybatisIntegrationTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/TemplateEditOwnershipTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/TemplateMedalStyleCopyAndBindingContractTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/TemplatePublishQuestionMediaTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/TopicCopyNpcRemapTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/TopicCopyTemplateOwnershipTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/ChapterFlowWriteServiceTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/CopyTopicCopyTypeNpeTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/support/TopicBundleReviewServiceTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiPlayDelistedCompletedRetryContractTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiPlayMyCompletedSelfPlayContractTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiPlayPuzzleHintTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiThemeRuntimeControllerTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/JourneyPublishFreezeTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/RoamSessionWriteMybatisIntegrationTest.java`
- `chengyinhub-common/pom.xml`
- `chengyinhub-framework/src/main/java/com/chengyinhub/framework/config/DruidConfig.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/TopicRouteSessionMapper.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/ITopicRouteRuntimeService.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/impl/AiGatewayServiceImpl.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/AdvancedGameRuntimeServiceImpl.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/AuditTaskServiceImpl.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsTopicLikeServiceImpl.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsTopicServiceImpl.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/PlayJourneyStoryServiceImpl.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TemplatePublishServiceImpl.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TopicRouteRuntimeServiceImpl.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/WechatPayServiceImpl.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/ThemeRuntimeConfig.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/ThemeRuntimeEngine.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/TopicBundleReviewService.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/TopicStateKeyRefService.java`
- `chengyinhub-system/src/main/resources/mapper/business/TopicRouteSessionMapper.xml`
- `pom.xml`

## C00-reserved files (not edited; proposed hunks under `c00/`, deliver via CY-HANDOFF)


## Source commits (provenance; includes commits whose changes fall outside this carrier's area)

- `c3d62e6db4` 10-02 04:07 feat: 增加默认关闭的原生计步协议与权威时段提醒契约
- `2cd0d28327` 10-02 04:20 test: 修正原生协议真库事务装配并补齐继承迁移说明
- `f9caba1740` 10-02 07:36 feat: 补齐默认关闭的原生设备验证并升级 BC 三组件
- `a2daa1b7ee` 10-02 10:44 feat: 增加未激活的内容发布、AI 作用域与玩法授权基础
- `81a20db263` 10-02 13:17 feat: 整合一致内容捕获与不可变候选并接入隔离 MySQL 门禁
- `d62ab23181` 10-02 13:31 test: 保留候选并发异常类型以定位真实 MySQL 恢复失败
- `61ab2709c3` 10-02 13:51 fix: 保留服务端只读约束并兼容 MySQL 捕获事务初始化
- `964a594294` 10-02 23:25 feat: 整合玩法预占安装和城市积分事务并补齐隔离 MySQL 门禁
- `6e5a8e3549` 10-02 23:46 feat: 整合作用域 NPC 默认关闭网关及安全失败修复
- `3837457c8a` 10-02 23:50 test: 用隔离 MySQL 观测私人据点候选约束而不接入应用
- `affcd57f59` 10-03 00:05 fix: 合并商家 NPC 身份边界并完善隔离据点诊断
- `227967abdc` 10-03 00:23 test: 跨字符集重复演练私人据点候选约束指纹
- `c37bbd27ff` 10-03 00:41 feat: 整合免费玩法授权、私人据点及不可变候选审核事务
- `062eb9a705` 10-03 01:01 fix: 增加审核实时结构预检并补齐据点迁移声明
- `c9fe624884` 10-03 01:18 test: 修复真实 MySQL 同名临时表阴影夹具
- `86c8479c7f` 10-03 02:30 feat: 增加默认不启用的步行目标来源证据暂存
- `c4a5037719` 10-03 03:00 feat: 据点隐私保留与严格内容审核接口，增量真库门禁
- `e5a57e9e64` 10-03 03:08 test: 修正隔离据点保留测试库名长度，保留UUID与28项断言
- `dcafdc2091` 10-03 03:20 feat: 免费模组同事务安装授权与隔离结构校验，保留全部真库门禁
- `e05e4da55b` 10-03 03:30 feat: 自有安装回执与冻结候选审核预览，增量70项真库验证
- `1529ebf9ab` 10-03 03:54 fix: 审核预览显式授权与回执索引计划，同连接警告负对照
- `d5533f2498` 10-03 04:07 test: 复用隐私测试建表辅助方法，保持独立池与原始列定义
- `f2baeffe82` 10-03 04:30 feat: 审核控制台冻结候选预览与严格生产浏览器验证
- `a74d359d8a` 10-03 04:58 test: 对齐审核浏览器真实标题与受控主题资源，保留严格43项门禁
- `36f9012761` 10-03 05:26 test: 审核浏览器按真实按钮文本精确定位并拒绝歧义
- `75a40b8dd8` 10-03 07:58 fix: 拒绝报名列表中的空或无效用户身份并补充归属测试
- `6a0c548965` 10-03 08:19 feat: 增加缺省关闭的城市棋盘版本草稿工作台
- `257f6083c8` 10-03 08:27 fix: 补充城市棋盘草稿迁移依赖声明
- `20bd5ff857` 10-03 08:37 fix: 补齐城市棋盘草稿端点权限并验证方法拦截
- `9738ef3650` 10-03 08:59 test: 增加城市棋盘草稿真实 MySQL 门禁
- `33052aadab` 10-03 09:30 test: 增加隔离城市棋盘草稿真实浏览器门禁
- `481a644427` 10-03 16:45 test: 保留城市棋盘 MySQL 并发夹具显式空字段
- `e24cfaa447` 10-03 16:47 test: 修正城市棋盘浏览器标签定位与真实路由历史夹具
- `95280ad1e2` 10-03 17:01 test: 等待城市棋盘真实页面转换后再建立历史夹具
- `ec8da62814` 10-03 17:18 feat: 新增常驻城市棋盘 V2 提案合同与兼容性规范
- `00b206b9ce` 10-03 17:24 feat: 补齐模板选择题编辑与保存后审核入口
- `f77d622122` 10-03 17:28 feat: 新增未接线的见面同意生命周期与安全适配基础
- `f1daebc2ef` 10-03 17:32 test: 增加模板选择题编辑到审核的真实浏览器门禁
- `a0350dc87e` 10-03 17:51 feat: 新增默认关闭的见面同意持久化与候选迁移
- `86f7510a20` 10-03 18:02 test: 增加见面同意持久化真实 MySQL 验证门禁
- `5f1df61e42` 10-03 18:05 test: 经可见父菜单进入模板浏览器测试页面
- `2662885fdd` 10-03 18:18 test: 兼容精确 Surefire 方法类名格式并保留 MySQL 证明门禁
- `781a949152` 10-03 19:52 feat: 增加 App 工坊结构化叶包清单契约校验
- `8415ee795f` 10-03 19:54 feat: 增加默认关闭的彩蛋发现回执评估契约
- `73975e5415` 10-03 19:58 feat: 增加双配置门控的会面同意 API 适配器
- `449f89ab16` 10-03 20:01 feat: 增加隔离非现金奖励库存与幂等消费回执
- `2ca299a57d` 10-03 20:12 fix: 将非现金奖励隔离 SQL 显式登记为测试夹具
- `23d018738b` 10-03 20:16 feat: 持久化隔离彩蛋评估回执与事务事件
- `1b941540a9` 10-03 20:19 test: 按类路径加载非现金奖励夹具和当前模块映射
- `4b0248cbc0` 10-03 20:59 test: 增加非现金奖励真实 MySQL 验证与严格证据门禁
- `f6b01644e6` 10-03 22:00 feat: 增加默认关闭的本人非现金奖励只读接口
- `4f124660ed` 10-03 22:02 feat: 以同一事务联动 IM 拉黑与隔离会面同意清理
- `5f24c049ea` 10-04 01:26 feat: 增加已审核个人模组的只读来源验证
- `fc1faad57f` 10-04 01:27 feat: 记录报名确认退款取消和过期生命周期事件
- `82a6fd862c` 10-04 02:06 test: 补齐报名生命周期的回归及 MySQL 审计测试夹具
- `f32ef2a393` 10-04 02:33 feat: 明确已购模组版本的永久使用期限契约
- `0bf8a3b13e` 10-04 02:33 feat: 为商户 NPC 组装已审核事实和引用来源
- `b5e741816a` 10-04 03:42 feat: 保存商户 NPC 公开事实草稿与审核绑定
- `11be8cb2f0` 10-04 03:43 test: 将商户 NPC 草稿纳入隔离 MySQL 实测门禁
- `6f8c8ed348` 10-04 05:02 feat: 增加商户 NPC 审核候选的只读预览
- `7914c9d9ef` 10-04 05:03 fix: 阻断通用审核与日志中的未接线 NPC 类型
- `b5bc9f2363` 10-04 05:22 test: 同步审核套件数量并补全审核日志查询夹具
- `03c24a626a` 10-04 07:31 feat: 增加默认关闭的 CITY 玩家只读接口
- `4a8b515d9a` 10-04 07:32 feat: 增加商家优惠券幂等命令回执及真实 MySQL 验证
- `7aa31fbda5` 10-04 07:56 test: 修正优惠券 MySQL 夹具的编码与重设桩
- `43a596ef92` 10-05 18:30 feat(workshop): add reviewed paid license issuance and owner read safeguards
- `561239597b` 10-05 19:06 fix(workshop): enforce stored payment precision and register migration dependency
- `0faeb8977e` 10-05 20:13 feat(backend): integrate reviewed fixed-coupon receipts and current-row merchant authoring
- `e40721fa4e` 10-05 20:30 fix(ci): preserve legacy suite proofs and accept exact hosted report identities
- `37681656a3` 10-06 01:53 test(coupons): wire required guards and caller transactions in legacy fixtures
- `f564283ed1` 10-06 04:54 feat(workshop): integrate reviewed paid draft and provenance protections
- `ce61c0bbac` 10-07 23:00 fix: 修复CI测试来源夹具并更新source-map-js安全锁定
- `2813cf48a5` 10-08 19:48 Add verified dormant media and qmod support components
- `01c687fb2c` 10-09 01:42 test: repair lifecycle approval fixture and freeze club expiry clock
- `a704199eca` 10-09 01:53 test: reconcile merchant withdrawal schema checks with target branch
- `9b2c61fbfe` 10-09 03:11 feat: add reviewed theme core contracts without runtime activation
- `81d5b6b5f5` 10-09 11:35 Save default-off theme runtime and whole-team replay integration [skip ci]
- `41bc8706e4` 10-09 11:55 Add theme history and guarded current-run controls [skip ci]
- `f48a3354dd` 10-09 12:21 Show source-owned theme message receipts and resource labels [skip ci]
- `ca6dc201df` 10-09 12:47 Add bounded public theme result posters [skip ci]
- `f9139e7b07` 10-09 12:59 Expose existing public result card authoring controls [skip ci]
- `863198c67e` 10-09 13:17 feat(theme): add staged replay and scene authoring controls [skip ci]
- `63cca6d485` 10-09 13:28 feat(theme): add staged conditional event broadcast authoring [skip ci]
- `b0ee5312bd` 10-09 13:38 feat(theme): expose staged runtime budget and rescue settings [skip ci]
- `944c943bee` 10-09 13:57 feat(theme): add isolated entry-only draft evaluator [skip ci]
- `ce920e1be9` 10-09 14:16 feat(theme): show owner-bound configuration overview in draft preview [skip ci]
- `cece6301d1` 10-09 14:30 fix(theme): protect runtime state-key references during authoring [skip ci]
- `5674be48e9` 10-09 14:43 feat(editor): show saved-topic publish-check results [skip ci]
- `394192a767` 10-09 17:10 feat(editor): author custom initial state attributes [skip ci]
- `b590eb6885` 10-09 17:19 fix(registration): reject invalid principals before joined queries [skip ci]
- `372f0029d9` 10-09 17:24 feat(editor): compose declared-attribute event consequences [skip ci]
- `4f23035479` 10-09 18:59 Complete five reviewed theme authoring increments
- `d275497b1c` 10-10 09:19 fix: preserve exact merchant node duration on create and update
- `f19e82eb68` 10-10 17:59 fix: accept explicit overnight merchant business hours in route candidates
- `e324203362` 10-10 20:26 fix: reconcile scoped runtime test fixtures and mini interaction regressions
- `0f03c77d21` 10-10 20:59 Fix saved-check expiry feedback and restore bounded CI contracts
- `06fa23fde9` 10-10 21:32 fix(mini): clarify saved-check and runtime copy without changing behavior
- `ed87463816` 10-10 22:18 fix(mini-audit): resolve exported factory mixins without hiding writes
- `b8684762ad` 10-10 22:58 fix(theme): isolate FIRST identity and preserve completion transaction contracts
- `bac44bbd1a` 10-10 23:32 test: restore scoped runtime and transactional completion fixtures
- `84bfb90150` 10-10 23:59 test: complete Theme MySQL fixture schema and paid team seeds
- `0ece68e7cd` 10-11 00:38 test: share isolated receipt and leaderboard fixture table declarations
- `d82401fb8d` 10-11 00:50 fix: review owner template projections before library republication
- `27c2bedae3` 10-11 02:21 fix: bind template republish moderation to frozen review content [skip ci]
- `f859cd6e26` 10-11 02:40 fix: match republish text moderation to initial publish field coverage
- `f1fe895f34` 10-11 23:37 feat(rendezvous): add isolated dual-confirmation meeting handoff
- `13ef25a068` 10-12 00:39 fix: include merchant template rules in pre-save text moderation
- `95e499471e` 10-12 03:30 feat(merchant): expose proven frozen redemption accrual basis
- `57b22916e0` 10-12 20:10 Add exact own topic favorite state and explicit delete-only unlike

