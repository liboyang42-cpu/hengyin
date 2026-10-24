## 当前有效约束：保持现有入口，不新增

用户最新明确决定：**“保持现有入口，不新增”**。新增隔离MySQL/Redis集成入口方案已关闭，不再处于等待批准状态，也不再催批同一方案。现有VPS212和CI配置保持原样；现有自动CI自然运行，不手动触发或重复重跑。普通回归只在新真实源码候选或未解失败时由本地按现有212验证，已通过且未变的候选不补跑。

现有入口未覆盖的真实MySQL/Redis项继续为 **NOT_RUN／现有入口覆盖缺口**，包括E02_25MySqlIT2、第三Growth真MySQL11等；不改称通过，不以普通/mock/skip结果代替。已有精确SHA的PASS和失败/锁拒绝历史均保留；真实HTTP/设备业务/集成合并部署仍未验收。此为约束更新，无新增开发或补跑任务。详见[持久决定](VALIDATION_ENTRY_DECISION.json)。C00只维护全局账，各队只维护自有交付账。

### 以下为早期调度协议；历史运行数与任务池数量不代表当前活动数

# 两队各50项：离线阶段滚动源码协议

CY-HANDOFF: CODEX。协议CY-LONGRUN-SOURCE-20261015-v1；本页覆盖此前等待验证/只有测试写权的时序。用户明确把原AGENTS编译、测试、截图要求推迟到下次本地验证。只做现有bug和共用后端，Claude UI/新功能、独立iOS、Flutter边界不变。

## 已落地的任务池与单写者

- [CORE 50项](LONGRUN_CORE_50.json)：C01/C02各9、C03–C06各8。原队六worker及精确source_files逐项登记。
- [EXTRA6 50项](LONGRUN_EXTRA6_50.json)：E01/E02各9、E03–E06各8。新队真实thread01a13f9c-e8ba-753e-9c39-03840bb4900b/host durable，已6/6实际启动并收到新模式；承接精确源码，不能仍只等测试/README写权。
- [精确源码所有者](LONGRUN_SOURCE_OWNERS.json)：两队source文件交集为空，每个文件唯一lane写者；同lane共享文件的任务串行。表外只读，不能用模块通配抢写。当前源码清单可写，不再逐项等待本地或C00一般确认。
- 每项都有标题、真实路径、触发、不变量、修复边界、政策停止条件；都是AUDIT_THEN_FIX，不是声称100个已确诊bug。确认已修/不成立要留源码依据，不重复实现。

## 基线、源码转交及公共候选

CORE新独立checkout /workspace/hengyin-longrun，分支codex/mini-longrun-core-20261015，基线4a943501262cb533158428d9748d8ac49aaa8950；PR28的在跑CI保留自然完成，不为新工作反复取消/重跑。EXTRA6现有独立worktree/公共1e403b13可继续，精确授权源文件从1e403b13到4a943501没有本批改动；不能把旧base里的其他文件覆盖CORE新修复。

原B01/B03/B04/A03及C00对这些源文件已在c24202f/1e403b13交最终补丁并停止；L01–05在4a943501交付后停止。现按源文件表正式交接。C00保留共同核心归CORE。当前主动读PR20全部路径后确认：订单detail/index.js仍与PR20重叠，不转交；history/index.js与participation-detail/index.js没有该重叠，可从已停止B01转EXTRA6/E05，优先修其规则变更前已报告8项失败的账号/组件代次缺口，不重跑。此前保守冻结三组件的规划由本精确处置覆盖。

EXTRA6协调者获写自己的EXTERNAL_COORDINATION/{BACKLOG.md,QUEUE.json,CLAIMS.json}三文件，复制初始50项并独立维护领取/完成/补位/分支/完整SHA/每文件当前child writer；C00仍独占全局OWNERSHIP、BACKEND_CHANGES及docs/plans控制表。该队可在既定独占源文件内自调度、补任务，不必等Mac或C00逐项同意。C00主动读取PR16/交付PR和专属队列，并将全局合同/依赖归并主账；不假定消息已收到。

两队每项可写其明确regression_file和evidence_file（见JSON），前提是路径尚未存在且未被他人申领；已有测试/共享fixture若不在源表仍只读，需C00精确登记。回归只写不跑，NOT_RUN。新增任务不得用伪缺陷/空标题/重复不变量补数；真无下一可做项记录EXHAUSTED/政策阻塞与恢复指令。

## 六线自动补位

1. 每队协调者按lane领取第一个READY_SOURCE_ONLY；取锁前确认base/原写者停止证据/精确文件。每lane同时一个worker，最多6，全部gpt-6.1-sol，禁止Astra。
2. worker静态读实际调用链，真实缺陷才最小修改；不成立记NO_CHANGE_SOURCE_REVIEW，不把未执行的回归叫通过。不得跑Node、Java、Maven、DB、lint、DevTools或任何手动业务验证。已自动运行的CI不关闭/弱化，不手动重跑，不等待它补位。
3. 每项完成立即冻结该补丁、发送精确文件与结果；协调者按明确路径串行小提交、公共完整SHA和草稿PR交SOURCE_DELIVERED_UNVERIFIED。共享工作区worker不得Git切分支/暂存/提交；冻结提交后立即领取下一项，可在等待几秒提交时只读下一项，不等验证。
4. 没有源修改的有效反证也写专属证据并提交；需要新政策、权限/RBAC、收费/额度、DDL/新依赖、生产/部署授权时记BLOCKED_DECISION，释放该项写入并领取下一项，不能猜政策。跨队文件只交最小补丁提案给所有者，禁止自己改。
5. 每交付/反证/阻塞一项，协调者从本队已授权文件的实际剩余调用链补一个具体未重复项，目标维持50个可行动/在途任务；分配新递增ID、实际触发和路径，保留原历史，不改已交付SHA含义。若无真实独立候选，明确记录耗尽，不虚构永动任务。
6. C00串行形成CORE公共候选；EXTRA6串行形成自己候选。跨队依赖只集成精确已交付公共SHA/补丁，保留原小提交和回退链，不因对方叫完成就假定接口/部署可用。

## 已冻结的首个跨队内部修复契约

E03-01已静态报告freezeEnding传session=null导致结束存档绕过endingChapters。EXTRA6/E03拥有PlayJourneyStoryServiceImpl，可增兼容重载 `freezeEnding(Long topicId,String raw,TopicRouteEngine.State state,TopicRouteSession session)`，现有3参入口保留原兼容语义；4参用真实session选择既有快照，HTTP/DTO/结局顺序不变。CORE/C06拥有TopicRouteRuntimeServiceImpl的freezeThemeStory调用点，待对方公开重载提交后串行集成再传已有session；不能向未存在方法盲堆引用。双方在等待这一具体依赖时做其他项，不占空槽。源码未验证，最终一起验证。

## 离线与恢复

不连接VPS/生产，不合并PR、不部署/上传、不执行业务迁移。SOURCE_DELIVERED_UNVERIFIED和NOT_RUN是当前交付状态；原1e403b13 231通过及规则变更前真实Node历史保留，不能套到新SHA。后续自动CI即使通过也只是该自动执行范围，不能替代用户下次集中验收。

用户关闭电脑后，Cloud在平台允许运行时独立继续，不依赖本地中转。平台时长、额度或环境终止时必须推送当前小提交/队列游标/持锁文件/阻塞及下一项恢复指令；不能承诺永久运行。恢复先读本协议、源表、两队队列与PR16最新评论，核远端SHA再从READY续领，不覆盖未提交补丁。

## 首批滚动收口与补位

CORE已有13个独立小提交公开（截至b09d9c45），逐项SHA写回CORE池，全部SOURCE_DELIVERED_UNVERIFIED/NOT_RUN；新增9个来源于当前源码实际调用切片的补位条目，累计创建59，后续持续补。仅新增独立证据/回归且未改业务源的任务冻结后可直接取下一项；改业务源仍等协调者精确提交后再叠写同文件，不能混提交。新增4个旧测试精确写权见源表supplemental_test_owners，仅必要fixture/新合同兼容，保留业务断言，不运行。

EXTRA6已在PR16 comment6192852909明确ACK_LONGRUN_SOURCE并确认29源码文件。E05旧policy_blocker文字已清：history/participation已转交，仅detail保留PR20；以最新源表/专项转交为准。C05-07任务草案要求旧代次一律UNCERTAIN与既有RESOLVED_NO_ACTIVE_RESERVATION实际合同冲突；不改现有额度政策，作为任务文字错误/已有反证处理，不能因本轮草案制造新产品决策。

## 冻结完整快照消除提交等待（最新）

每任务worker将精确changed files完整复制到不可变/tmp/longrun-snapshots/<任务ID>/<repo相对路径>并列manifest；交ID/目录/结论后立即继续下一项，允许同lane同源连续写。C00先核精确所有者，再按该冻结文件hash-object/update-index串行小提交；不读取后来WIP代替先前源码，保留每lane任务依赖顺序。worker仍不能Git暂存/提交，只有它写业务文件。仅文件复制/源码提交，不是验证。快照或manifest一旦交付不可改；补点用新任务或明确新revision，不覆盖已收快照。

CORE首批和补位公共源码截至9c55cda0，已留每项可回退提交；追加具体源切片并如实更新delivered游标。C03-09与05重因已标SUPERSEDED_DUPLICATE，不凑独立项；C06 routeActionId旧键异payload原回放是既定兼容，不另立相反新指纹修复任务。每项源码/反证都未跑新增验证，不能当验收关闭。

EXTRA6/E04申请6193059698的SmsCouponMapper.java及3既有测试已按源表精确转交，可新增tryReserveIssuableCoupon专用库存CAS：沿原NULL兼容status非3/4与原库存条件，0行原路径拒发，异常不吞，旧incrementReceiveCount统计语义不变；只同步fixture/原合同断言，不运行。无业务DDL/新API/费用政策。CORE/C04追加原NpcChatServiceTest仅10项已调用provider却读不到持久结果的错误分类修正，原不泄露答案/无日志/无audio断言保持。


## 长期源码最新checkpoint（覆盖旧状态文字）

[当前恢复入口](LONGRUN_CURRENT.md)与CORE池记录公共2b1984b59a0929a360e8095d5fc1955a1aed7964 / PR44；后续一律SOURCE_DELIVERED_UNVERIFIED、NOT_RUN。两队真实计数/文件单写者/跨队合同/未决政策和本地恢复规则均在入口，不以历史通过套用新源码。E04额外接口及三测试、C06 runtime mapper独立当前读、C04暂停投影SQL精确写权已在源表登记；其他原锁不变。

## 当前恢复与三方精确接收（覆盖旧待办快照）

公共源码 `2b1984b59a0929a360e8095d5fc1955a1aed7964` / PR44，SOURCE_DELIVERED_UNVERIFIED/NOT_RUN；CORE178历史=135源交付+38反证+3决策阻塞+1重复取代+1委派，实际可行动0/目标缺50。锁保留不等于有人在运行。EXTRA最后25ede658/105历史，5旧夹具及LIKE授权c79d2886已公开，尚无新ACK。Claude本轮37份PR接收与精确文件处置见CLAUDE_HANDOFF_INTAKE.json，唯一C00控制账，冲突只交原写者补丁，未移交共享核心。当前SOURCE_ONLY不是CONTRACT_FROZEN/TEST_DEPLOYED或客户端上线凭据。旧1e403b13的231PASS与69c失败历史独立保留，新源码不能继承。最新恢复入口LONGRUN_CURRENT/START_PROMPT；集中验证清单已更新2b1984，仍不运行。

## 最新1修复+5开发与本地验证交接

CORE `f7e0e52346d0aec248e6288f1f5ed4aaae66ac4c`、EXTRA `a37ce191756ab11896faf4e61e8a06b4279f0303`，均SOURCE_DELIVERED_UNVERIFIED/NOT_RUN。每队只占1条失败修复线，优先原文件作者；多失败串行，移交写者必须先停止和逐路径登记。其他5条做真正独立的现有bug/后端源码，完成即push，不等待测试/CI；耗尽不凑数或假称运行。实际VPS复测由本地`01a13eed-e4d9-77f3-b875-c72d26ea7d59`独占，Cloud不再新增手动编译/测试/CI触发/专门配置，不尝试VPS或Cloud替代，既有CI自然运行。CORE15测试fixture修复未复编译，不改生产/断言；EXTRA五fixture及E02合同已解除授权阻塞、实际ACK6198215180已收到，E03/E05新小提交已公共，E02后续另SHA。完整候选依赖、矩阵、门禁、预期源码声明数和原Cloud诊断路径见BACKEND_VALIDATION_VPS_HANDOFF.json/md。未获实际VPS结果不签收，旧231不继承，未合并/部署/业务迁移。

## 首轮VPS483失败与原作者串行修复

已完整读回PR24@6e7a22025192710fa5edfd8be5a10ed425edc0ee公开README/summary：f7e源码编译PASS，但83类483项470PASS/5FAIL/8ERROR/0skip为FAIL，8 gated/JDBC另未测。C03/C04/C06原写者依次占唯一修复槽，前人停写才交下一人；新公共链b423114b→603dab12→最终 `0d63043ad06775bc3dc52fb925620ef36d1081a7`，8测试文件及证据修正，生产零变更。nullable Long/mock求值/HTTP异常边界/精确现金原行/CAS当前读夹具问题未通过弱化断言掩盖。新SHA未编译/测试，NOT_RUN。完整13方法历史、原堆栈引用、157源声明聚焦清单见VPS_REPAIR_ROUND_1.json/md。本地独占验证，Cloud无手动运行/CI/环境替代，其他独立源继续。
