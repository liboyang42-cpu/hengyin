## 当前有效约束：保持现有入口，不新增

用户最新明确决定：**“保持现有入口，不新增”**。新增隔离MySQL/Redis集成入口方案已关闭，不再处于等待批准状态，也不再催批同一方案。现有VPS212和CI配置保持原样；现有自动CI自然运行，不手动触发或重复重跑。普通回归只在新真实源码候选或未解失败时由本地按现有212验证，已通过且未变的候选不补跑。

现有入口未覆盖的真实MySQL/Redis项继续为 **NOT_RUN／现有入口覆盖缺口**，包括E02_25MySqlIT2、第三Growth真MySQL11等；不改称通过，不以普通/mock/skip结果代替。已有精确SHA的PASS和失败/锁拒绝历史均保留；真实HTTP/设备业务/集成合并部署仍未验收。此为约束更新，无新增开发或补跑任务。详见[持久决定](VALIDATION_ENTRY_DECISION.json)。C00只维护全局账，各队只维护自有交付账。

### 以下为此前交付与状态历史；涉及新增入口待批的表述已被本决定覆盖

## E02-25普通64项公开证据已签收／真RR2未运行

C00已核读[PR24公开README及summary](https://github.com/liboyang42-cpu/hengyin/blob/0d3b6195203b0b963e0dd77df8412fde70b3923e/docs/verification/mini-bug-arch-20261015/VPS_EXTRA_29fe9236/README.md)，证据提交`0d3b6195203b0b963e0dd77df8412fde70b3923e`。冻结源码`29fe9236ede15a8f1bbd3aaf2b93e3fc25409726`实际执行E02_25Test4＋E02_24Test5＋community.CommunityPostServiceImplTest55＝**64PASS，0失败/错误/跳过**；fresh精确3XML、212guard0/completed和精确容器清理均已核对，原日志/XML/源码哈希见[E02_25_ORDINARY_VPS_RESULT](E02_25_ORDINARY_VPS_RESULT.json)。本地本轮Java已结束，无失败修复槽需要开启，原作者身份/文件锁保留。

这是新SHA实际普通Service/SQL替身回归，不继承faef60PASS、不称66通过或RR实库闭环。E02_25MySqlIT **2项仍NOT_RUN**，真实RR快照、DuplicateKey/current读及SQL副作用提交未验；真Redis、运行HTTP、设备业务、候选集成、合并部署仍未验收，TEST_DEPLOYED=NO。新真库入口授权仍待用户明确答复，212/CI/网络不变。无真实READY不凑任务，后续实际源码另交新SHA。

### 以下为此前交付／待验历史，以本节当前结果为准

## E02-25已交源码，64普通／2真库待验

EXTRA新冻结源码`29fe9236ede15a8f1bbd3aaf2b93e3fc25409726` / PR36，原E02已停写/锁保留。C00已核公开diff：仅3生产文件按授权改comment重复键catch当前读及exact Mapper/SQL，另新4方法普通回归、新2方法guarded MySQL回归、原E02_24的duplicate夹具接线及说明；首次普通读、正常插入后读、post/body/parent绑定、事务/权限/HTTP/DDL/依赖不变，旧55未改。`e9b9e1912fa449e1ee1c0344ac2ff0dc4cb268ec`直接父为29fe，仅本队3交付文档，不另作源修复。

本地已接收，现按212准备E02_25Test4＋E02_24Test5＋community.CommunityPostServiceImplTest55=64源声明，实际报告未回，NOT_RUN；真实E02_25MySqlIT2仍缺批准入口，NOT_RUN。旧faef60PASS仍只在faef，不套29fe。Cloud未运行验证，TEST_DEPLOYED=NO；运行HTTP/设备/集成合并部署均未验。[精确七路径／完整FQN／依赖交接](E02_25_SOURCE_DELIVERY.json)。当前原队实际writer0/READY0，不凑任务；实测失败再由原E02单修复槽继续。

### 以下为此前候选及交付历史

## 当前三候选普通定向证据已签收

已核读PR24@`cae72e05ee668271d79ea9c95e505d7e8971d3a5`的三份README和summary：CORE `e0e3f517` **10/10**、EXTRA `faefbaf8` **60/60**、第三 `bde356f3` **28/28** 实际通过，均0失败/错误/跳过、fresh精确2XML、212guard0/completed、自有精确容器清理。[CORE证据](https://github.com/liboyang42-cpu/hengyin/blob/cae72e05ee668271d79ea9c95e505d7e8971d3a5/docs/verification/mini-bug-arch-20261015/VPS_CORE_e0e3f517/README.md) / [EXTRA证据](https://github.com/liboyang42-cpu/hengyin/blob/cae72e05ee668271d79ea9c95e505d7e8971d3a5/docs/verification/mini-bug-arch-20261015/VPS_EXTRA_faefbaf8/README.md) / [第三队证据](https://github.com/liboyang42-cpu/hengyin/blob/cae72e05ee668271d79ea9c95e505d7e8971d3a5/docs/verification/mini-bug-arch-20261015/VPS_FORWARD_bde356f3/README.md)。EXTRA首次锁拒绝0项仍保留，不改写历史。各结果只属于各自完整SHA，不是合并候选全量验证；SQL边界有替身，不是实际数据库/Redis/运行HTTP或设备业务验收。TEST_DEPLOYED=NO，未合并/部署。

真存储[入口缺口](https://github.com/liboyang42-cpu/hengyin/blob/cae72e05ee668271d79ea9c95e505d7e8971d3a5/docs/verification/mini-bug-arch-20261015/VPS_DB_ENTRY_GAP_20261016/README.md)已核：本次列CORE8类未入funds固定名单、EXTRA所列23类仅2已入/21未入，第三Growth真MySQL11未入；名单覆盖不等于新SHA已运行，有Redis mock不能称真Redis联验。独立限额入口仍待用户明确答复，212/CI/网络不改。

E02-25原`/root/external_e02`已实际[ACK恢复](https://github.com/liboyang42-cpu/hengyin/pull/16#issuecomment-6207665975)，源码writer1（队伍回执快照），无需等待上述结果。仅附加同owner已有E02_24Test的duplicate当前读夹具前提，保5方法与全部断言、不改旧55；新RR源码/真实库回归仍NOT_RUN，不继承faef60PASS。

### 以下为此前交付与验证历史，当前以本节及LONGRUN_CURRENT为准

## E02-24普通定向结果更新

EXTRA `faefbaf8947b6ff202849385d28473fe2eaaa21e` 已由本地VPS212实际执行E02_24Test5＋CommunityPostServiceImplTest55，**60PASS/0失败/0错误/0跳过**，fresh精确2XML、guard0/completed、精确自有容器清理。开始03:44:19Z、结束03:47:03Z（2026-10-16 UTC）；首次03:40:30Z锁拒绝0项作为历史保留。独立公开PR24汇总待补，当前仅按本地实际回报登记，不编造证据SHA/日志哈希。普通SQL替身测试不等于真实MySQL RR/Redis/HTTP；后续E02-25新候选不能继承60PASS。第三bde于03:47:03Z进入同入口串行执行，尚无结果。Cloud不补跑，TEST_DEPLOYED=NO。

## E01-19普通定向结果更新

CORE e0e3f517b8e6deb26ed3697a00d38ae14f015e76已获本地VPS212普通2类10实际PASS（0failure/error/skip，fresh精确类集、guard0/completed、精确容器清理）；已核读[PR24公开README及结构化summary](https://github.com/liboyang42-cpu/hengyin/blob/5a90a1e81db8274960c3d5dee9d6646aa73311be/docs/verification/mini-bug-arch-20261015/VPS_CORE_e0e3f517/README.md)，证据提交`5a90a1e81db8274960c3d5dee9d6646aa73311be`；原始报告由本地保留。旧483不迁移，真MySQL/Redis、HTTP、设备/业务、合并部署仍未验。EXTRA faef普通60项PASS已回报、公开报告待补；第三bde串行验证进行中，不影响原E02按已发精确grant继续RR源码；ACK未到不虚报worker运行。

## E01-19 显式取消收藏的并发ACK修复

公共源码 `e0e3f517b8e6deb26ed3697a00d38ae14f015e76` / PR44，原0d之上一小提交，源码作者CORE/C03；Cloud未运行，本地新普通2类10项定向PASS，真实SQL/HTTP仍未验。`POST /api/topic/like` 显式type0两请求都读到旧行而DELETE1/0时，第二个body.code由500改为200、msg“取消点赞成功”，符合既有方向幂等合同；HTTP方法/路径/参数/字段/账号过滤/目标ID不变。空type旧toggle的DELETE0仍失败，负数失败、数据库异常仍传播，不增加自动重试或端侧写权。

新5＋既有方向5已由本地在新SHA实际执行10/10通过；无新库/配置/DDL，不沿用0d157PASS。真实数据库/HTTP/设备/合并部署未验收，TEST_DEPLOYED=NO。回执及精确文件见[E01_19_SOURCE_CLAIM](E01_19_SOURCE_CLAIM.json)。

## 当前E06针对性复测闭环：5PASS／真实HTTP未验

C00已完整读回[PR24公开报告](https://github.com/liboyang42-cpu/hengyin/blob/b5273c3233d1753c9488edc410e1a1c0d5b29fed/docs/verification/mini-bug-arch-20261015/VPS_EXTRA_b45664de/README.md)与summary：固定源码 `b45664de6136eb97c46ed0c86e95fc25686dc1b7` 正常212准入后，E06_17Test单类**5实际项PASS，0失败/0错误/0跳过**，fresh XML精确类集，guard0/completed，精确容器清理。源包、日志及XML哈希已入总控；仅普通单类针对性签收。

旧ed598的11类76实际75PASS/1FAIL原记录及初次锁拒绝原证据继续保留；该唯一失败方法现按b456五项targeted PASS闭环，不称b456重跑全76、不将其他类成绩迁移。原E06修复槽已释放，不新增源码改动；仅有真实独立READY才继续领取，否则EXHAUSTED，不为凑线造任务。EXTRA自己维护本队交付账，C00只更新总控；Cloud无补跑。

裸SQL疑点仍NEEDS_REACHABILITY/NO_SOURCE_CHANGE，无真实业务可达证据不计新BUG，不改共享生产文件。GET分享读库故障HTTP503/body500、原GONE/隐私边界的MockMvc回归通过不等于运行中HTTP验收；真实数据库/Redis、HTTP/身份业务、设备、合并部署及迁移均未验收，TEST_DEPLOYED=NO。第三队普通24PASS/真MySQL11未运行仍独立。

### 以下排队及旧失败为历史，以本节当前签收为准

## 当前E06修复复测排队：NOT_RUN，非源码失败

当前状态一致口径：旧 `ed598258c0f614385940f326099b9626531fab02` 的11类76实际=75PASS/1FAIL仍保留，失败未因push关闭；修复 `b45664de6136eb97c46ed0c86e95fc25686dc1b7` 仅E06_17Test单类5个源码声明，212因another_build_holds_lock返回75/containerId=null，本次实际0项、未编译测试，**NOT_RUN排队**，不是修复失败或通过。

C00已完整读回[PR24公开排队证据](https://github.com/liboyang42-cpu/hengyin/blob/60febdbdcdcc7eed2b445a80f3d1f21d2cd76966/docs/verification/mini-bug-arch-20261015/VPS_EXTRA_b45664de/README.md)及admission-refused.json；commit `60febdbdcdcc7eed2b445a80f3d1f21d2cd76966`，源包SHA256 `2ab95e689599d4c67bf1777bb7e00293faf2ef50248ab40b695f393d214c5db3`。本地本次回报无活动Java验证，待正常212准入；Cloud不补跑、不重复改测试或生产源、不绕锁，不再全76。裸SQL疑点保持NEEDS_REACHABILITY/NO_SOURCE_CHANGE，无业务可达证据不计新bug。

跨端仍按已交源码合同读：GET分享读库DataAccessException的HTTP200→503/body500不变，非法token/无行/坏存储仍HTTP200/GONE；真实HTTP/业务/两端/部署未验收。Claude/iOS接收ACK待回，不能据源码或旧四Share方法通过称已上线。第三队普通24PASS/真MySQL11未运行独立记录。

## E06修复新候选交付／裸SQL疑点裁定

EXTRA原E06已交修复 `b45664de6136eb97c46ed0c86e95fc25686dc1b7`（直接父ed598，原child a9d1dcda7d9944ace6c89000fc2aa5696018fdff），C00实际核对公共compare仅2路径：E06_17Test.java和E06-17.md，生产/依赖0改。夹具增加既有Error querying database标记、保留原裸SQL子串，原generic/HTTP200/no-mapper及Share503与全部负控不变，额外加2条响应不含SQL断言。SOURCE_DELIVERED_UNVERIFIED/修后NOT_RUN，不能把旧75PASS或旧56PASS继承到此SHA。已交本地PR24#6202107770，仅E06_17Test聚焦复测（5是源码声明、实际以fresh XML计），不再全76，无新DB/Redis前提；Cloud不运行。

裸SQL裁定：**NEEDS_REACHABILITY / NO_SOURCE_CHANGE；不计新增已确认BUG。** C00只读核GlobalAdvice→UserSafeMessage与AjaxResult重复脱敏链，及原sqlLeak_isMaskedToGeneric使用的框架异常形状；定向main源码查找没有证明真实业务会抛出该人工短裸SQL形状。仅有合成Controller/MVC证据不能扩成生产可利用结论，不自动扩regex/屏蔽业务文案、不转写共享源；独立疑点保留，夹具修复不等于修掉它。

## 当前EXTRA影响面失败与唯一原作者修复线

EXTRA候选 `ed598258c0f614385940f326099b9626531fab02` 已据[PR24@77cb3320fbbf47ef061db811fdc51a0a07c462f8](https://github.com/liboyang42-cpu/hengyin/blob/77cb3320fbbf47ef061db811fdc51a0a07c462f8/docs/verification/mini-bug-arch-20261015/VPS_EXTRA_ed598258/README.md)完整读回：编译PASS，11类76实际项=75PASS/1FAIL/0ERROR/0skip，exact fresh XML，guard1/child_command_failed。75源码声明不能当实际执行数；PlayJourneyStoryServiceImplTest实际26，合计76。唯一失败为E06_17Test无关Controller脱敏隔离负控:111，预期generic、实际裸SQL。E03_18新5项通过；E06 Share503/GONE/隐私/成功4项通过，不得说Share503失效或整组通过。

已收到[EXTRA原E06实际接收ACK](https://github.com/liboyang42-cpu/hengyin/pull/16#issuecomment-6202056607)：external_e06只占一条修复线，沿自有E06_17Test/E06-17文档核旧全局Advice支持的真实异常形状，保generic/不泄漏/隔离/503全部断言，不删负控/跳过/期待泄漏。其他真实独立工作继续；无READY不造任务。修复必须新完整SHA、路径/依赖交本地针对性复测；Cloud不手动跑。共享UserSafeMessage/GlobalExceptionHandler未授E06写权，先核归属再正式转交，C00不替其改原测试。

裸SQL疑点独立保留为[SHARED_SAFE_MESSAGE_SQL_OBSERVATION.json](SHARED_SAFE_MESSAGE_SQL_OBSERVATION.json)：C00已直接比较ed598与e72，UserSafeMessage/GlobalAdvice内容完全相同。原pattern未覆盖该短裸SQL，of返回原消息；这是既有脱敏覆盖缺口的静态/MockMvc证据，不是已证明生产端点真实泄漏。待真实caller/驱动异常形状、旧合同与原owner核清，未改任何共享生产文件；即使隔离fixture修正通过也不关闭该疑点。真实HTTP/DB/设备/业务/合并部署继续未验。

# iOS独立agent优化与共用后端交接

> 三方统一协作规则：[CY-COORD-20261015-v1](COORDINATION.md)。当前文件归属与候选见[OWNERSHIP.md](OWNERSHIP.md)；初始规划不等于已领单。最终提示词：[Codex](START_PROMPT.md)、[Claude](CLAUDE_START_PROMPT.md)、[iOS](IOS_START_PROMPT.md)。

2026年10月15日。**iOS由独立agent继续负责，小程序Codex/Claude任务不改iOS代码；后端仍统一。** Flutter仍不在当前工作范围。本文件补充你已在做的iOS任务，不要求重新开始迁移或放弃现有分支。

## 从哪里开始

先读本文件，再读 [BACKEND_CHANGES.md](BACKEND_CHANGES.md) 和 [IOS_START_PROMPT.md](IOS_START_PROMPT.md)。[TASKS.md](TASKS.md)与[tasks.json](tasks.json)是共用后端/小程序Codex责任清单；[CLAUDE.md](CLAUDE.md)是小程序UI/新功能边界。原来的“不做iOS”只约束该任务包，不能理解为让你停止iOS工作。

## E06-17 分享快照运输合同及第三队兼容索引

本节同步已交付源码合同，不是新增原生/后端开发权限或部署通知。最新完整说明见[后端总账E06-17](BACKEND_CHANGES.md#e06-17-运输合同同步分享读库异常-http200--503)。

- EXTRA/PR36固定源码 `ed598258c0f614385940f326099b9626531fab02`：GET `/api/roam/share/snapshot` 有效token读库抛DataAccessException时，由HTTP200改为**HTTP503**，body仍AjaxResult.code500/通用文案且无data。非法token/无行/坏存储/隐私失败仍HTTP200及既有GONE，成功读取与匿名隐私闸不变。路径、参数和body形状相同不能掩盖HTTP状态变化，旧`NO_API_CHANGE`标签不代表运输合同不变。
- 源码状态 **SOURCE_DELIVERED_UNVERIFIED**；本地11类已回76实际75PASS/1FAIL，唯一隔离负控失败，见当前状态，**真实HTTP未验**；旧e72两类56PASS不覆盖。TEST_DEPLOYED=NO、合并/部署/两端验收未完成。iOS若实际使用此分享读面，保留既有错误与GONE区别，不因503认定服务端已撤回；不在未部署版本上宣称已可联调，不新增共享请求策略或自动重试政策。
- 第三队PR72/2babbe28：后台漏斗日期保null/空/blank/单边查询和完整rows，不增加必填/62天/分页；不要求iOS新增后台接口调用。成长修复无新字段/DDL/奖励语义，普通24PASS仅属普通回归，11真MySQL仍NOT_RUN，真实并发/HTTP/业务另验。

Claude/iOS不重复修后端，后端缺陷仍交EXTRA6/E06或第三队原作者；原生源归独立iOS所有者，本文不扩共享请求模块/产品政策写权。此索引已发布不等于对端已读，Claude/iOS合同接收ACK待收。

## 本次核实的源码基线

| 仓库/分支 | 核实SHA | 说明 |
|---|---|---|
| 公共统一后端 hengyin/master | 8b7eb814e3db00aa2543fee489fad44f9aa7aec3 | 当前文档分支的代码基底，不代表测试运行包 |
| 公共后端集成分支 claude/mini-foundation-abc-integration-squash-20261014 | 0b301c9b8d1853a86ed689c701eaad40cb3b70d7 | PR11头，相关子PR12–15未合并，需C00组合审查 |
| 公共iOS main | a8d387e206ed3994b6d265b3618db0c293f6ec15 | 本机只读快照与GitHub提交匹配 |
| 公共iOS migration/native-ios | f0df08598adbd73cdd85089ce4d1e0965e0e070c | GitHub查询比main多52提交；你实际在用的分支仍由你登记，不自动切分支 |

iOS源码与说明公开于 [chengyin-ios](https://github.com/liboyang42-cpu/chengyin-ios)。以下指针固定到已查询的migration快照，README虽仍称main为foundation，也不能仅凭这句判断主干功能。[iOS README](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/README.md)写明多项production能力仍受批准注册表/端点门控；[CN登录合同](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/docs/cn-native-session-contract.md)要求真实账号读回，地址/开关本身不证明登录已可用。不要因后端有新PR就打开全部原生能力。

## 已有能力先复用

| 已核原生实现线索 | 当前证据 | 不要误判为 |
|---|---|---|
| 会话回执与稳定重放 | [Core/PlayGameSessionRuntime.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/PlayGameSessionRuntime.swift)：playerReceipt、expectedRevision、retryExact | 所有恢复缺失，或已证明跨进程/跨设备全部验收 |
| 订单未知与账号隔离 | [Core/OrderLifecycleCoordinator.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/OrderLifecycleCoordinator.swift)；[Core/RegionalSessionStorageScope.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/RegionalSessionStorageScope.swift) | 只有小程序有幂等/账号保护 |
| 原生音频中断 | [App/NativePlatformAdapters.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/App/NativePlatformAdapters.swift)；[App/MerchantNPCVoiceDevice.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/App/MerchantNPCVoiceDevice.swift) | iOS没有中断监听，需要照抄wx音频模块 |
| 积分两类读面 | [Core/WalletCommerceService.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/WalletCommerceService.swift)：assetPoints非分页，points另一分页路径 | 所有积分接口都分页，或页面有数据就已结算正确 |
| NPC数据读面 | [Core/NPCChatData.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/NPCChatData.swift)：export只读模型 | 原生已有本人全部NPC删除闭环 |
| 原生群投票 | [Core/GroupPollService.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/GroupPollService.swift)；[App/GroupPollViews.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/App/GroupPollViews.swift) | 因小程序不露出，就要删除iOS实现 |

源码/测试入口存在只是静态线索，本次没有运行Swift、Xcode、设备或真实后端验收。旧研究的“iOS最全”“已接好”“AOT决定性能”“后台音频唯一在Flutter”等不作为本轮完成结论。

## 优先级与任务

这是12项复核/优化卡，不是12个已复现bug。先I00和P0的已确认问题；等待后端的项可先做兼容测试、输入保真及UI状态，不擅自猜协议。已修或未纳入的项目记录证据即可。

| 编号 | 优先级/任务 | 依赖 |
|---|---|---|
| I00 | P0 先接续现有分支与准入合同 | 独立可先做；后端C00协作 |
| I01 | P0 GPS精度与坐标系适配 | BK-GPS；PR12仅代码提案，运行待核 |
| I02 | P0 写入未知恢复与账号隔离 | A01合同；本地回归可先做 |
| I03 | P0 作者保存重开与媒体恢复 | B02、A03；现有UI/本地草稿可独立回归 |
| I04 | P0 报价支付退款与资产读回 | B03；既有原生能力不得因小程序范围被删 |
| I05 | P0 私密内容引用撤回及缓存访问 | B05；不改变授权模型 |
| I06 | P1 原生音频中断与生命周期资源 | 原生可独立核；不照搬wx实现 |
| I07 | P1 徽章积分榜单与列表语义 | BK-BADGE及既有读接口 |
| I08 | P1 AI不可用用量与NPC数据权利 | A02；既有删除接口见BK-EXISTING |
| I09 | P1 排期与团队页面的过期状态读回 | B04及现有允许动作 |
| I10 | P2 原生交互无障碍与性能测量 | 不依赖新后端合同，可与等待项并行 |
| I11 | P2候选 新入口按iOS已授权范围选择 | 需先核现有实现；不要求全量移植 |

### I00 先接续现有分支与准入合同

P0；独立可先做；后端C00协作。

记录你实际分支/HEAD/未提交工作、当前iOS任务及运行包，不切回main重做迁移。核现有端点、market/realm、token与role读回、功能准入和测试身份；后端地址/批准注册表为空不是可以填生产地址的提示。

验收：有当前基线与源/运行对应表；无合法环境时保留OFF，不用假身份或role默认值冒充可用。

源码入口：[README.md](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/README.md)；[docs/cn-native-session-contract.md](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/docs/cn-native-session-contract.md)；[Core/CNAccountSessionService.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/CNAccountSessionService.swift)；[Core/RegionalSessionStorageScope.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/RegionalSessionStorageScope.swift)。

已有测试入口：[Tests/CoreTests/CNAccountSessionServiceTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/CNAccountSessionServiceTests.swift)；[Tests/CoreTests/RegionalSessionStorageScopeTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/RegionalSessionStorageScopeTests.swift)。这些是需由你核实并运行的入口，不是本次已通过的测试。

### I01 GPS精度与坐标系适配

P0；BK-GPS；PR12仅代码提案，运行待核。

已核该iOS快照的PlayCompletionEvidence.location只有经纬度/coordinateSystem，complete的location分支只发经纬度；PR12新增accuracy尚不能由该证据类型表达。这是即将接入合同的缺口，不证明已发生上线故障。沿实际provider→projection→evidence→请求链保留精度与样本时间；负精度无效、差精度不能故意省略。

验收：核PR12目标部署后验证缺失/无效/阈值内外、旧客户端兼容、扫码通路；WGS84不改标签冒充GCJ02，未获有效投影能力保持受控不可用。

源码入口：[Core/PlayExperienceService.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/PlayExperienceService.swift)；[Core/RuntimeLocationProjection.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/RuntimeLocationProjection.swift)；[Core/RoamLiveSessionController.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/RoamLiveSessionController.swift)；[App/PlayNativeDeviceProvider.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/App/PlayNativeDeviceProvider.swift)；[App/RuntimeNativeLocationProvider.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/App/RuntimeNativeLocationProvider.swift)。

已有测试入口：[Tests/CoreTests/PlayExperienceTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/PlayExperienceTests.swift)；[Tests/CoreTests/RoamLiveSessionTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/RoamLiveSessionTests.swift)。这些是需由你核实并运行的入口，不是本次已通过的测试。

### I02 写入未知恢复与账号隔离

P0；A01合同；本地回归可先做。

已有playerReceipt与retryExact，复用，不新建回执服务。逐链核requestId/version/旧scope捕获、持久化未决锁、进程重启及两设备读回；不得把某个pending内存属性泛化成全项目无持久化。取消/退款等旧接口没有requestId的地方不能凭空补字段。

验收：超时先查原事实，同requestId异命令不换ID绕过；晚到响应不串账号/realm；进程重启保留原身份，不能以查不到、读取失败或退出页面直接清锁。

源码入口：[Core/PlayGameSessionRuntime.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/PlayGameSessionRuntime.swift)；[Core/PlayExperienceCoordinator.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/PlayExperienceCoordinator.swift)；[Core/RegionalSessionStorageScope.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/RegionalSessionStorageScope.swift)；[Core/OrderLifecycleCoordinator.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/OrderLifecycleCoordinator.swift)。

已有测试入口：[Tests/CoreTests/PlayExperienceTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/PlayExperienceTests.swift)；[Tests/CoreTests/RegionalSessionStorageScopeTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/RegionalSessionStorageScopeTests.swift)；[Tests/CoreTests/OrderLifecycleCoordinatorTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/OrderLifecycleCoordinatorTests.swift)。这些是需由你核实并运行的入口，不是本次已通过的测试。

### I03 作者保存重开与媒体恢复

P0；B02、A03；现有UI/本地草稿可独立回归。

保留草稿/主题/章节/节点ID与revision，保存后以真实读回验证顺序、模板绑定和运行规则；区分媒体已选/上传未知/已确认引用，发布失败保留编辑。后端字段尚未冻结时不同时重写编辑器或新增DSL。

验收：重开、章重排、二次编辑、丢响应、上传中关闭、旧作品更新均不丢语义；旧会话/旧内容继续可读，新增内容不反转旧结局。

源码入口：[Core/TemplateAuthoringService.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/TemplateAuthoringService.swift)；[Core/TemplateAuthoringSavedDraft.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/TemplateAuthoringSavedDraft.swift)；[Core/TemplateAuthoringPublishReceipt.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/TemplateAuthoringPublishReceipt.swift)；[Core/TemplateAuthoringMediaReferenceSnapshot.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/TemplateAuthoringMediaReferenceSnapshot.swift)。

已有测试入口：[Tests/CoreTests/TemplateAuthoringSavedDraftTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/TemplateAuthoringSavedDraftTests.swift)；[Tests/CoreTests/TemplateAuthoringPublishReceiptTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/TemplateAuthoringPublishReceiptTests.swift)；[Tests/CoreTests/TemplateAuthoringMediaReferenceSnapshotTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/TemplateAuthoringMediaReferenceSnapshotTests.swift)。这些是需由你核实并运行的入口，不是本次已通过的测试。

### I04 报价支付退款与资产读回

P0；B03；既有原生能力不得因小程序范围被删。

沿已授权iOS订单/核销/退款链核服务端报价、版本、积分单位、资产读回和SDK回跳；SDK成功不能确认付款，UNKNOWN不能自动退款重付。cart在iOS可能有既有契约，小程序零调用不等于iOS应删；维持其当前准入状态。

验收：重复点击/回跳/超时/退款中断不重复资产变更；价款与旧订单快照不变；有独立账务读回；不恢复支付开关、IAP、出金或新资金政策。

源码入口：[Core/OrderLifecycleCoordinator.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/OrderLifecycleCoordinator.swift)；[Core/OrderLifecycleService.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/OrderLifecycleService.swift)；[Core/WalletCommerceService.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/WalletCommerceService.swift)；[Core/WalletCommerceContracts.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/WalletCommerceContracts.swift)。

已有测试入口：[Tests/CoreTests/OrderLifecycleCoordinatorTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/OrderLifecycleCoordinatorTests.swift)；[Tests/CoreTests/OrderLifecycleServiceTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/OrderLifecycleServiceTests.swift)；[Tests/CoreTests/WalletCommerceTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/WalletCommerceTests.swift)。这些是需由你核实并运行的入口，不是本次已通过的测试。

### I05 私密内容引用撤回及缓存访问

P0；B05；不改变授权模型。

核原帖/引用/媒体缓存、退群/撤销分享/删改后的再次访问；权限不能由旧快照永久授予，也不能因删除关联默认变PUBLIC。适配现有后端拒绝与失效投影；根因在后端时交B05，不造仅靠客户端的权限保护。

验收：两账号及原旧链接回读符合原政策，失效媒体不残留可见；合法公开内容不被误清；鉴权/RBAC变化另批。

源码入口：[Core/SquareWorkspaceService.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/SquareWorkspaceService.swift)；[Core/SquareWorkspaceStore.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/SquareWorkspaceStore.swift)；[Core/SquareWorkspaceCoordinator.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/SquareWorkspaceCoordinator.swift)。

已有测试入口：[Tests/CoreTests/SquareWorkspaceTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/SquareWorkspaceTests.swift)；[Tests/CoreTests/SquareWorkspaceLegacyRecoveryTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/SquareWorkspaceLegacyRecoveryTests.swift)。这些是需由你核实并运行的入口，不是本次已通过的测试。

### I06 原生音频中断与生命周期资源

P1；原生可独立核；不照搬wx实现。

已有AVAudioSession中断/路由变更监听，先核覆盖与释放。来电/耳机拔出/录音抢占/页面退场/切账号后正确暂停与恢复意图，取消或用户主动暂停后不擅自续播；不重复注册观察者或让旧回调恢复新会话。

验收：真实设备验证中断/锁屏/前后台与UI一致，无残留会话/观察者/临时文件；兼容既有最低版本，不为采用新文档API擅自提高部署目标或追加后台entitlement。

源码入口：[Core/PlatformAudioPlayback.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/PlatformAudioPlayback.swift)；[Core/PlatformChapterAudioBridge.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/PlatformChapterAudioBridge.swift)；[App/NativePlatformAdapters.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/App/NativePlatformAdapters.swift)；[App/MerchantNPCVoiceDevice.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/App/MerchantNPCVoiceDevice.swift)。

已有测试入口：[Tests/CoreTests/PlatformChapterAudioTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/PlatformChapterAudioTests.swift)；[Tests/CoreTests/ShopNPCInterruptionTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/ShopNPCInterruptionTests.swift)。这些是需由你核实并运行的入口，不是本次已通过的测试。

### I07 徽章积分榜单与列表语义

P1；BK-BADGE及既有读接口。

PR12平台徽章将排除club_id非空定义，端上不补回俱乐部数据凑数量。积分已有assetPoints非分页与另一个分页points分支，保持不同合同；未在Top50不表示0分；部分读取失败不能抹掉已成立身份卡。

验收：徽章来源/缓存失效正确；两种积分读面不混page参数与返回模型；榜外显示真实名次/分数或未知；异常/空/拒绝状态可区分。

源码入口：[Core/ProfileService.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/ProfileService.swift)；[Core/GrowthCenterService.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/GrowthCenterService.swift)；[Core/RoamBadgeWallReader.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/RoamBadgeWallReader.swift)；[Core/WalletCommerceService.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/WalletCommerceService.swift)。

已有测试入口：[Tests/CoreTests/ProfileServiceTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/ProfileServiceTests.swift)；[Tests/CoreTests/GrowthCenterTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/GrowthCenterTests.swift)；[Tests/CoreTests/PlayPlayerLeaderboardReadbackTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/PlayPlayerLeaderboardReadbackTests.swift)。这些是需由你核实并运行的入口，不是本次已通过的测试。

### I08 AI不可用用量与NPC数据权利

P1；A02；既有删除接口见BK-EXISTING。

当前NPCChatData是export只读模型，不据此说已有原生删除。若已授权iOS范围包括删除，按现有后端本人全部NPC历史语义、危险确认及晚到请求作废接入；否则明确待纳入，不假装PR15小程序按钮可直接给原生用。AI缺配置/审核失败/超时不可回示例成功，用量未知不显示0真实成本。

验收：删除含义与后端一致，重开回读和其他账号隔离；A02未冻结新usage/额度字段前不造成功账本或新API，不自动打开模型付费/记忆场景。

源码入口：[Core/NPCChatData.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/NPCChatData.swift)；[Core/NPCChatDataCoordinator.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/NPCChatDataCoordinator.swift)；[Core/MerchantNPCConversationHistory.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/MerchantNPCConversationHistory.swift)；[App/NPCChatDataView.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/App/NPCChatDataView.swift)。

已有测试入口：[Tests/CoreTests/NPCChatDataTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/NPCChatDataTests.swift)；[Tests/AppUnitTests/NPCChatDataViewTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/AppUnitTests/NPCChatDataViewTests.swift)。这些是需由你核实并运行的入口，不是本次已通过的测试。

### I09 排期与团队页面的过期状态读回

P1；B04及现有允许动作。

核系列原时刻/时区/候补状态、当前allowedActions和更新后读回；不要把原生编辑默认值覆盖到服务端保存值，也不允许仅凭旧页面按钮可点绕过最新状态。

验收：非09:00场次保存重开不改时刻；报名/候补后动作与服务端一致；队伍轮询慢响应不覆盖新状态，离页后正确停止。

源码入口：[Core/ClubSeriesLeadOptions.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/ClubSeriesLeadOptions.swift)；[Core/PlayPlayerTeamReadback.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/PlayPlayerTeamReadback.swift)；[Core/PlayExperienceService.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/PlayExperienceService.swift)。

已有测试入口：[Tests/CoreTests/PlayPlayerTeamReadbackTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/PlayPlayerTeamReadbackTests.swift)；[Tests/CoreTests/PlayExperienceTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/PlayExperienceTests.swift)。这些是需由你核实并运行的入口，不是本次已通过的测试。

### I10 原生交互无障碍与性能测量

P2；不依赖新后端合同，可与等待项并行。

先量地图/长列表/图片音频内存与更新频率，使用稳定业务ID、取消离页Task、必要增量刷新/可见内容加载，避免为了对齐小程序组件重写SwiftUI。检查关闭sheet/返回保留草稿、重试/空/权限/未知结果、VoiceOver、动态字体和Reduce Motion。

验收：代表性真机/系统版本有同场景前后证据及回归，无卡死/误触/资源泄露；synthetic截图与真实业务截图分开，不拿AR/3D源码数量当设备表现排名。

源码入口：[App/WalkingNavigationView.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/App/WalkingNavigationView.swift)；[App/PlayRouteMapView.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/App/PlayRouteMapView.swift)；[App/PlayExperienceView.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/App/PlayExperienceView.swift)。

已有测试入口：[Tests/AppUITests/AccessibilityPresentationFlowTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/AppUITests/AccessibilityPresentationFlowTests.swift)；[Tests/CoreTests/PlayRouteMapCameraTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/PlayRouteMapCameraTests.swift)。这些是需由你核实并运行的入口，不是本次已通过的测试。

### I11 新入口按iOS已授权范围选择

P2候选；需先核现有实现；不要求全量移植。

对官方路线、收到印章、队伍举报、主题状态键、认领已有门店/进度等逐项核当前原生入口。只给确实缺且纳入你原计划的项拆单；creator已有服务、群投票已有服务与UI，不把旧零调用/小程序不露出裁决套成iOS缺陷。

验收：每项标已有/部分/未纳入/依赖后端；新功能字段用真实合同，不发明表或端点；小程序不做会费/群聊/投票不意味着原生删除它们，也不等于批准原生开收费。

源码入口：[Core/RoamExperienceService.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/RoamExperienceService.swift)；[Core/CreatorContentService.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/CreatorContentService.swift)；[Core/GroupPollService.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Core/GroupPollService.swift)。

已有测试入口：[Tests/CoreTests/RoamExperienceServiceTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/RoamExperienceServiceTests.swift)；[Tests/CoreTests/GroupPollTests.swift](https://github.com/liboyang42-cpu/chengyin-ios/blob/f0df08598adbd73cdd85089ce4d1e0965e0e070c/Tests/CoreTests/GroupPollTests.swift)。这些是需由你核实并运行的入口，不是本次已通过的测试。

## 后端依赖与工作交界

| 后端所有者 | 给iOS的内容 | iOS可先做 | 等待合同/部署后做 |
|---|---|---|---|
| C00/C99 | 固定代码SHA、真实接口/DTO/错误/权限/版本/兼容fixture和变化记录 | 记录当前原生适配器/能力开关/测试缺口 | 在同一获授权VPS版本下集成 |
| B02 | 保存/重开/发布保真 | 原生草稿/媒体/离页与旧数据回归 | 新的DTO差异与读回 |
| B03 | 资产/报价/并发/冲正 | SDK结果不直接确认、UNKNOWN状态、旧订单显示 | 账务事实及当前策略联调 |
| B04/B05 | 时刻/候补/可见性撤回 | 旧页面和缓存失效回归 | 服务端当前allowedActions/拒绝结果 |
| A01 | 会话/回执/稳定请求身份 | 复用现有原生pending/receipt，覆盖重启/账号切换 | 新原因/版本边界，以真实合同为准 |
| A02/A03 | AI失败/用量、结局/版本兼容 | 不可用/未知展示与旧fixture回归 | 真实字段/新fixture；不默认启用新策略 |

本agent只改iOS仓库及其专属测试/文档，不直接并行编辑hengyin的Codex独占文件。发现后端根因，提交最小失败请求（脱敏）、预期/实际、所属BK/后端任务和可观察读回，由对应后端所有者处理。若iOS新增功能需要专属接口，先向小程序Claude/后端协调者申领文件并冻结合同，不能另造/iOS API真源。

## 如何持续获得后端变化

每次开始适配或联调前，更新文档分支并读 [BACKEND_CHANGES.md](BACKEND_CHANGES.md)，再核对应源提交/PR、合并状态和测试部署证据。C00/C99独占维护该公共记录，各worker把变化交协调者汇总，避免大家同时改一份日志。没有建立自动推送或已授予跨agent消息通道，不把静态文档当实时通知。

后端变更若改变字段含义、HTTP方法、必填、错误、权限、版本/CAS或重放语义，需要兼容方案及iOS测试向量；未冻结前iOS只做准备，不猜字段。后端已合并也不等于VPS已部署；iOS不得依未部署合同开启写入。

## 环境和交付

iOS代码/Swift测试/Xcode构建使用iOS agent已经获准的平台；本任务不运行它们。Java构建、开发集成后端、MySQL/Redis留在专用VPS，额外CI用GitHub托管runner；Cloud的localhost不是Mac隧道。发布、签名、实际支付、短信、账号/权限和production开关仍按原审批边界，不把本交接当生产批准。

交付一张状态表：I编号、当前分支/SHA、已有/缺口/待后端/不纳入、关联BK与实际后端SHA、修改文件、实际测试、设备/系统、业务独立读回和未验收项。单列Swift/Core XCTest、AppUnit、XCUITest、模拟器、真机、真实账号/支付/provider、部署和发布，不拿Python契约/构建/fixture截图代替后面的层级。iOS工期由你按现有已完成范围核算，不能累加Codex34—57人日或旧跨端预算。

平台核对参考：[Apple水平精度说明](https://developer.apple.com/documentation/corelocation/cllocation/horizontalaccuracy)（精度以米表示，负值表示位置无效）；[Apple音频中断说明](https://developer.apple.com/documentation/avfaudio/handling-audio-interruptions)。新版本音频API须按现有最低版本做可用性处理，本交接不要求提升部署目标。
