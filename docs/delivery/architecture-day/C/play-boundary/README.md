# C 玩法应用边界候选

已实施机械拆分：Controller 3848→307 行，58→8 个业务注入；25 条实际 HTTP 映射（任务估数24）、38个既有公开 Java 入口重载保留。13 个 owner 按查询、scope/冻结输入、完成事实、回执、命令、奖励组织，详见 owners.json（涵盖全部110个移出的方法体）。没有复制 runtime 或奖励算法；Controller 不传业务 lambda，应用不回调 Controller。

基线 d49c12b820419308236010e0710f96d7bf48c4c9；独立 codex/architecture-day-c-play-boundary。只变更 C 控制器、新应用类、随真实 owner 迁移的测试接线。三个 runtime、生产表、金额规则、JWT/RBAC、CI、台账均未改。读回公共 master44699155：#567/#585/#586 三个 merge 对此 Controller 无路径差异；本候选不整树覆盖 master，最终由 root 保留其余主线规则。

HTTP 限流/参数/错误码及六个 DeadlockRetry 保留；后续事务收口批将 Controller 的九个事务注解移除，应用命令成为同选项 REQUIRED/READ_COMMITTED 的唯一事务入口，没有第二重试环。偏好提交仍由已有 PreferencePlayService 的事务/重试包裹原完成回调。回执授权→原回执→聚合锁→再次回执→版本→claim→效果→终态顺序保留；原照片上传事实/审核ID/奖励封顶和 Hint event14及ledger→member锁序未改。

测试仅改装配/反射观察 owner；现有用例名和业务断言保留。PlayNodeAccessTestFixtures 在每次显式替换后重建真实应用对象图，后置桩仍生效；测试 no-lead 替身保留在测试侧。根独占 hint 真库类未改，PlayHintUnlockMySqlIT.review.patch 仅替换两条旧 Controller 字段注入；root 需应用后统一回归。ChapterFlowWriteServiceTest 仅两处调用已经移出的 isPrevDone 改观察 owner，其他 D 装配不动。

实际检查：Python 静态逐体核对110、HTTP路径/顺序与公开签名、应用依赖DAG、旧测试用例名；git diff --check 均PASS。未运行Java编译、任何Java/MySQL/HTTP测试、Spring启动或CI；不借用旧源码结果。统一回归还需 root 的相关单元/真库/启动与 action-ledger 真实owner适配。

## Entry 与 Query 的实际边界（ce04 后独立增量）

按 root 明确决定，本批保留兼容 GET 的原入场行为，不宣称 HTTP GET 全部纯读。PlayEntryApplication 接管 nodes/routeState：原资格与时效读取→原 state(可能建局/共享刷新)→Query准备→逐节点题面投影→原 nodeRuntimeConfig(可能共享刷新)→冻结模板投影→节点及列表收尾→原 assignOrGet→冻结角色投影→原 Ajax。没有把所有 runtime 刷新提前批量做；条件/时点/次数均沿用原代码。Query 投影只接收原 routeState、原逐节点配置和不可变角色 code/name，没有调用 Entry 或持有函数回调。

特别回读：nodeRuntimeConfig→sharedSnapshot→sharedRouteSnapshot.refreshZeroHpRead 有写，故也移入 Entry，而不因其方法名有 Config 就冒充纯读。NodeQuery 的实际 runtime 调用仅 tags(当前局既有状态读取)；其他 Query 中 sessionStatuses/isRouteLeaderboardHidden 也只查现有状态。Actor/scope与原冻结route引用在 request-owned投影数据中为 final；nodeList过滤和展示map只在本次投影内修改。

BasicIntentApplication.basicActionContext 仍是有共享状态刷新语义的兼容 GET 命令入口；PreferenceApplication.preference 仍是有原 canPlay 入场语义的兼容入口。两者已在源码明确标识，保留原授权/lead/unlock/回执/事务注解与调用顺序，不包装成 Query。普通节点完成命令、照片、Hint、奖励算法不在本增量改动。

root540d16 的 readAssigned 端口已交付；本兼容 Entry 使用原 assignOrGet 结果投影，不为展示增加额外查询或改变首次分配。A/E 的 querySharedRouteState/queryState 用于未来直接查询既有状态时，缺局必须 null；本批通过 Entry 传原冻结结果，不依赖尚未交付的端口、不复制 runtime实现，也不伪造ACTIVE或成功。

本增量实际执行 Python 静态逐阶段 token核对、原入场读序与逐模板写口位置、纯Query禁写口、HTTP适配token和词法括号检查，及 git diff --check；Java/DB/CI未运行。仍需 root统一编译、旧场景/并发回滚、真库及Spring启动验收。回退整体撤销 Entry+Query分段+Controller/fixture归属接线，无DDL或持久化变更。

## 应用事务单一所有者（b248 后独立增量）

Controller 9→0个@Transactional，六个DeadlockRetry保持原HTTP位置。RateLimiter ORDER=DeadlockRetryConfig.ORDER-1，重试ORDER=LOWEST_PRECEDENCE-1；无Controller事务时每次retry克隆重新调用应用代理。应用事务完成rollback/cleanup后异常才回到HTTP重试；外层已有事务时既有Interceptor直接透传，REQUIRED只加入原事务，不引入REQUIRES_NEW或内层重试。

七个原读写入口的隔离/rollbackFor与两只读回执入口不变。photo和revealPuzzle的短兼容应用入口补同READ_COMMITTED/rollbackFor=Exception事务，防止短方法proxy→this.full绕过完整入口注解；原缺身份即拒的checkin/answer/arrive重载和无参查询仍不启事务。Hint的唯一事务/重试仍在已有PuzzlePlayService；Preference仍在已有PreferencePlayService，不加HTTP/Entry外层事务。旧照片上传事实I/O仍留在原业务事务内，未改为事务外；Advanced Photo四阶段/外部UNKNOWN合同未触碰。

原4个DeadlockRetryEntryPointContractTest用例保留并把事务断言指向实际owner；同suite另补2个测试，使用实际Controller/Photo/Puzzle应用+Spring事务/重试代理和mock JDBC，拟检查rollback→close→第二identity→新connection事务、原隔离、照片/揭示短兼容入口，不能当MySQL证据。B1真库原4个@Test/wrapper正文逐字不变，只有identity断言移到无TX层、setup显式为真实应用安装事务代理；原claim/路线/Hint叶子的TX与真实回滚断言未削减。夹具按Controller真实target归一身份，防止后置替换经过CGLIB代理时另建空对象图。root-owned PlayHintUnlockMySqlIT未改。

实际仅执行Python源码静态核对：HTTP事务移除/六重试、命令body和原事务参数无改、两兼容owner、B1四正文、旧合同case保留+2、词法/独占范围，及diff检查。Java/DB/CI均未运行；本批无新增CI/回归批次，由root同源联合执行既有suite和启动验证。

第17节采用记录：本唯一应用事务owner片对应IR01/IR06/IR-T07；代理测试另捕获每次应用入参，逐次断言原actor/activity/node/media/actionId/expected不漂移，短兼容入口亦按原参数断言。该增量仅增强既有2测试，不新增suite/CI；尚未运行Java。IR-T01/05旧代次小程序cleanup不在本事务增量改动范围，不以本片源码/测试冒充其验收；后端原回执先于新版本检查的正文未动。

## action-context 可写入场事务（5fa8 后独立修复）

已沿本工作树和root986的实际A源码确认：sharedRouteSnapshot 的 REQUIRED/DEFAULT/rollbackFor=Exception 参与调用方事务；refreshZeroHpRead在RUNNING且零HP规则适用时锁session，syncChallenge改变状态后saveRouteOwner→updateStoryStateCas、bumpSession→updateSessionCas实际UPDATE。旧外层readOnly=true不会被内层可写声明覆盖，是存量入场错误。本修复仅把basicActionContext声明改为@Transactional(rollbackFor=Exception.class)，沿A默认隔离与REQUIRED，不新增重试/REQUIRES_NEW、不移除refresh、不动scope/读取先后/回执规则。basicActionReceipt原readOnly=true逐字保留，A/E和root IT未改。

既有PlayCommandIntentTest加1场景：真实Controller→应用Spring事务代理→共享边界替身（使用实际A方法的事务属性），断言共享刷新边界可写且加入原事务、原actor/scope投影、无个人局fallback/claim；随后原receipt入口仍readOnly并保持UNKNOWN。mock JDBC仅作为事务属性单测，不冒充零HP/MySQL CAS验证；Java未运行。该类旧private-helper夹具同步补显式actor（迁移后应用参数），原6case/业务断言未删改。实际执行仅源码检查及diff检查，待root同批统一编译/单元/真库。

## 刷新后动作版本一致性（008bf后独立修复）

成立的失败机制：普通selectSessionByActivity和锁读selectSessionByActivityForUpdate的statement/SQL cache key不同；A普通读可复用S0，refresh锁读得到S1，bumpSession只给S1.setRevision。updateStoryStateCas/updateSessionCas默认UPDATE清cache，但不会修改C已持有的S0，故原expected可为3而snapshot已来自刷新后的4。A snapshot当前有gameSessionId/authorityEpoch/runtimeInstance，没有可直接复用的revision字段。

本修复不依赖普通读后移后的L1/RR行为：先沿原scope/节点授权执行sharedRouteSnapshot；共享分支在同一可写事务内复用现有FOR UPDATE当前读取得session/version（原共享刷新已持锁时复用该锁；其他共享规则在此锁住当前事实）。个人分支保留普通读取和原个人投影，无独立初始化/新Mapper/全局cache修改/奖励变更。原回执优先于当前版本检查和action-receipt纯读正文不动，A源码未改。

既有PlayCommandIntentTest再加1case（累计8）：两个独立普通/锁读对象，刷新只改锁对象4、旧引用仍3；断言先refresh后锁读，返回原runtime/epoch配合新expected4；旧expected3被409/intentNotExecuted拒绝且零claim/效果，新意图4正常一次。008bf事务场景仅把session桩观察口改为实际锁读；全caller搜索还定位ApiPlayCheckinLiveCodeTest的一条共享场合法夹具，补同一原GameSession的锁读桩，原扫码/零奖励断言保留。对象/调用模型不冒充真实MyBatis/MySQL；实际仅静态/差异核对，Java/DB/CI未运行，随root同批统一回归。
