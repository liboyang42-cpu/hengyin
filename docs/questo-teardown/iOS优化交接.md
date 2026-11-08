# 城瘾iOS第2至8项优化交接

交给App负责人实施。2026年11月7日，版本1.0。

## 目标和分工

让App与小程序使用同一套后端事实，完成模板采用、明确发布状态、完整故事游玩、故障恢复、多人协作、购买售后和完局维护。暂停第1项的AI起草与创作入口优化，不扩写AI创作功能。

**App负责人修改Swift及App界面；DeepSeek后端负责人只修改Java、必要的数据和配套测试。** 全部跨端UI要求见同目录[UI优化施工清单](UI优化施工清单.md)，本文件是可独立交给App负责人的执行说明。已有的手动编辑、付费工坊、回执恢复和订单适配继续复用。

### 开工基线

- 后端候选：公开仓库`liboyang42-cpu/hengyin`，基线`147754ba8740abf208bda75cf86ed5901cac4c02`。后端分支`feature/questo-backend-20261107`，Java 21、Spring Boot 4.1.1。后续以实际交付提交为准。
- 本机iOS参考：`/Users/arlan/Downloads/chengyin-ios`，分支`feat/original-model-core-20261106`，提交`18f6787494fd8118332af877b8d709be32fcb2e7`。它是参考快照，不能直接当App负责人目标分支或上架包。
- 先核目标分支、正常入口、实际部署端点和登录身份。后端新字段以提交、请求响应样例和错误码为准，不照抄竞品GraphQL或旧报告提案。
- 本机参考中`RegionalLaunchConfiguration.composition`仍传`reviewed:nil`，`PlayChapter`只解码章节ID、名称、description和audioUrl；正常部署接入及运行blocks是优先核查项。

## 一 十个执行包

### APP-01 正常入口与运行依赖接通

**用户结果：** 正常登录后从首页、详情进入真实游玩和订单，不依赖fixture入口。

**复用：** `App/RegionalLaunchConfiguration.swift`、`App/AppCompositionRoot.swift`、`App/NativeRuntimeDependencies.swift`、`App/AppSession.swift`、`App/QuestifyApp.swift`。

在既有类型化部署配置中接入已核验测试部署，逐操作装配依赖和精确endpoint grant。账号、市场和部署变化后撤销旧lease与待派发任务；不把nil默认值整体改为允许，不通过通配URL或硬编码账号接通。

**验收：** 正常入口登录→真实列表→详情→开局；切A到B后旧响应不污染B；未配置、401、403及网络失败分别显示。

### APP-02 模板采用与跨城配置

**用户结果：** 知道采用的是哪一版、能在哪个城市和主体下用、还有哪些地点和现场题待配置。

**复用：** 现有模板或工坊详情、安装目标、采用后主题编辑入口，不创建第二套模板库。对应UI任务U02-01至05。

将模板要求、本次地点、观察目标、站位、不可见时的替代条件分开显示。原模板、自己的副本、安装目标和运行局分别保存身份。换地点后列出需重新确认的题面、答案、素材与开放条件；不把地图选点当作实地核验。来源更新只提供差异和明确升级动作，不覆盖自己的修改。

**后端依赖：** 实际采用、来源、地点待办和许可合同。主题模板`POST /api/template/topic-template/use`当前返回`data.topicId`对象，旧报告的标量描述不适用。

**验收：** 同模板在两城独立；换城不会沿用“已核验”假状态；未绑定可存稿，公开或开售资格由后端判断。

### APP-03 发布检查与发布状态

**复用：** `Core/ProjectEditIssueLocation.swift`及既有发布回执和发布结果界面。对应U03-01至03。

消费既有publish-check列表，保留`level/code/nodeId/message`，增量接章节、块和字段定位。分别显示公开、审核、销售及下一步，不从一个status推导全部结果。优先接已有`commercialAwaitingReview/commercialNextStep`；检查通过只证明相应检查层。

**验收：** 点击缺项回到正确位置；已公开审核中不写成已过审；商业审核未过不写成已开售；发布丢回包先查实际结果，不重新发布。

### APP-04 完整故事与真实后果

**复用：** `Core/PlayContracts.swift`、`Core/PlayExperienceContracts.swift`、`Core/PlayExperienceService.swift`、`App/PlayExperienceView.swift`、`App/PlayJourneyContentViews.swift`；使用既有富剧情类型与节点映射。对应U04-01至04。

运行章节补解码并按编排顺序渲染后端已有的`chapters[].blocks`。保留旧章节description/audio路径；图片、音频、节点、念头和条件正文不能被扁平description覆盖。开场允许无住宅坐标。背包、选择、资源、解锁和结局依据服务端投影和回执更新，不在UI本地判断后发奖。

内容需求与客户端实际能力分别声明；未知必需结构在购买或开局前阻断。只有作者批准且经审核的等价替代才可降级，不静默删关键块。

**验收：** 同一内容身份两端的关键证据、条件、选择后果和结局一致；不足资源不通过；未解锁结局不提前显示。

### APP-05 定位 现场替代与原请求恢复

**复用：** `App/RuntimeNativeLocationProvider.swift`、`Core/RuntimeLocationProjection.swift`、`Core/WalkingNavigationCoordinator.swift`、`Core/PlayDurableRecovery.swift`、`Core/PlayRecoveryStorage.swift`、`Core/PlayGameSessionRuntime.swift`。对应U05-01至03。

WGS84/GCJ02沿已验证转换边界处理；语言选择不决定坐标系。实际到达合同已有`accuracy`，当前后端围栏50米、水平精度大于80米拒绝；导航自己的阈值不能替代任务到达规则。新原因码接入后区分缺定位、缺精度、无效精度、精度不足、距离远、任务方式不符和地点暂停。扫码、人工或替代证据仅展示后端批准动作。

保持先持久化后派发、原requestId精确重试、revision/epoch和账号隔离。结果未知先查原回执；切局或切账号后迟到响应作废。阅读位置与通关进度分开。

**验收：** 拒权、漂移、过期采样、无效坐标、围挡、杀进程、丢回包及旧epoch；都不能假完成或重复发奖。具体测例按最终后端合同更新。

### APP-06 多人角色 席位与组织协作

**复用：** `Core/PlayGameSessionRuntime.swift`、`Core/JourneyRoleView.swift`、`Core/JourneyRoleViewCoordinator.swift`、`App/JourneyRoleViewSection.swift`、`App/SessionPlayRuntimeView.swift`及队伍和俱乐部页面。对应U06-01至04。

分别显示已购席位、邀请、有效成员、剧情角色、队长职务和主持权限；受邀不等于已付。只读自己的私密线索，按availableActions提交动作；掉线重入取最新投影。退队、拒邀、踢出和接管显示真实影响，不在端上自动提权。

**验收：** A为iOS玩家兼队长，B为小程序玩家，C为组织者；商家业务另加D。末席由B与独立玩家E竞争，A保留队长席。核对同局、角色隔离、奖励唯一、邀请到期、退队和重新同步。

### APP-07 商品 支付与退款

**复用：** `Core/OrderLifecycleCoordinator.swift`、`Core/OrderLifecycleService.swift`、`Core/OrderPaymentVerifier.swift`、`Core/WeChatSDKPaymentAdapter.swift`、`App/OrderLifecycleView.swift`及现有支付桥。对应U07-01、02、04、05。

确认页讲清购买的交付物、人数、受益主体、期限、总价和退款规则；金额只读服务端报价。SDK返回、服务端到账、权益激活、退款受理、渠道退款成功、账本对账和实际打款分别显示。unknown保留原订单，不能重下单。

逐SKU与市场核对数字内容、线下服务等允许通道。需要IAP的商品只有在StoreKit、服务端交易验证、恢复购买、pending和退款撤权验证完成后开放；本机存在微信适配器不代表IAP已完成。支付分类需要App负责人按提交时的Apple官方规则核对。

**验收：** 报价→支付→服务端确认→可用权益→部分或全额退款；取消、回调延迟和重复通知均不重复发权益。缺真实通道证据的SKU保持不可售。

### APP-08 工坊已购 安装与正文读取

**复用：** `Core/WorkshopPurchasedService.swift`、`Core/WorkshopPaidInstallService.swift`、`Core/WorkshopPaidInstallController.swift`、`Core/WorkshopPaidInstallPendingStore.swift`、`Core/WorkshopPaidInstalledTextService.swift`及对应App View和SessionBinding。对应U07-03。

保留免费OwnedPackage的metadataOnly范围；付费已购、安装写、正文读和专业操作沿各自授权装配。先与后端负责人确认`api/workshop/purchased/list|detail`、`install/targets|history|status|submit`和`installed-text/detail`真实承载服务，不能因客户端写了路径就认定可调用。

review显示所购修订、条款、地区、用途、目标及额度；提交后未知保留原UUID查status。安装成功与本地地点配置完成分别显示。正文继续执行现有no-store、2MB限制和账号上下文校验。

**验收：** A已购安装→读取正文→配置地点→进入原发布链；B未购无法读取或安装；重复提交只一份，退款后新使用依原许可规则处理。

### APP-09 媒体 弱网与有限离线

**复用：** `Core/ResponseLimitedHTTPTransport.swift`、`Core/JourneyContentService.swift`及现有图像、音频和恢复存储。对应U04-04、U05-04。

资源与内容身份绑定，展示必需/可选、大小和就绪状态；完整下载校验后原子启用，半包不覆盖有效版本。私有缓存与账号许可隔离；音频有同版文字，静音仍能获得必要线索。离线阅读、动作待确认、联网竞争和支付分别说明。

**验收：** 飞行模式同版阅读、低磁盘、链接过期、损坏资源、后台中断和切账号；恢复网络不重复提交，清媒体不清已确认进度或订单。

### APP-10 完局 推荐 反馈与创作者维护

**复用：** 既有结局和奖励区、活动详情、订单Coordinator、创作者收益及分析适配入口。对应U08-01至05。

先显示服务端确认的结局和奖励，再给真实可报名内容。照片默认私人回顾，公开另有明确动作。卡关、题目歧义、坏地点和媒体问题关联原局、节点及内容身份。创作者可读维护进展和实际收益，不能把准入通过当作财务权限。

推荐曝光/点击是行为，第二笔有效报名、退款和完成是服务端事实。保留稳定eventId、来源局和跨端去重，不上报答案、私密线索或精确轨迹。报告明确30日成熟窗、分子分母、水位和退款排除；未成熟不当0%。榜单只展示后端实际支持的范围。

**验收：** 第一局正式完成→推荐→第二笔有效报名可关联；重复打开、离线补报、换端与退款不重复计数；无推荐仍能正常结束。

## 二 与后端负责人的合同交接

| 对象 | App先使用的现有合同 | 需要后端提供的增量或确认 |
|---|---|---|
| 模板采用 | 主题/use的data.topicId；工坊原安装链 | 来源身份、地点待办、许可和换城行为 |
| 发布 | publish-check列表、发布回执、商业下一步 | 字段定位、检查版本、多条状态轴的真实含义 |
| 内容 | play/nodes的chapters、blocks、玩家态过滤 | 内容身份、能力需求及旧局固定版本 |
| 动作 | game/session/view、command、receipt；个人原动作恢复 | 稳定错误码、允许的替代动作和兼容策略 |
| 定位 | arrive的经纬度和accuracy | 拒绝原因及可重试范围，阈值变更需明确批准 |
| 多人 | 原队伍、角色、availableActions及领队入口 | 票、席位、邀请、角色与职务各自的状态 |
| 交易 | 原报价、订单、服务端到账和退款事实 | SKU通道、许可、安装和退款后使用规则 |
| 完局与维护 | 原结局、推荐、报名和收益入口 | 完成/归因身份、维护关联及成熟复报口径 |

新字段和端点只有在后端交付实际提交与样例后才接入。前端可先把已有状态和错误呈现准确；待后端项不能用fixture或本地赋值冒充接通。

## 三 实施顺序与交付要求

1. APP-01先确认正常入口；同时核对目标分支与参考快照差异。
2. APP-02至05沿共同合同推进，先走通单人内容、发布、阅读和恢复。
3. APP-06多人、APP-07支付及APP-08工坊按实际启用商品分别接通。
4. APP-09完整离线和APP-10经营优化继续推进；必要的弱网恢复、正式完成和报名事实随前面链路一起交付。

每个执行包交付实际Swift变更、消费者及装配、测试结果、构建版本、真实后端和内容身份、失败状态与回执，以及尚未验证项。小程序通过不代替App通过；fixture、模拟器、目标设备、支付沙盒和实地分别登记。

本次只整理交接，未修改Swift、构建App、执行真实支付或发布App。App负责人接手后保留其他任务的未提交内容，不把参考分支整体覆盖目标分支。
