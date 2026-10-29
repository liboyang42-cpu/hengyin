# WP09 M1：unlockHint 完整应用用例施工合同（doc-only v2）

结论：首个完整用例仍是 `POST /api/play/hint/unlock`，归入**既有 `IPuzzlePlayService/PuzzlePlayServiceImpl`**，覆盖原资格→带领/路线→提示模板→定价→唯一消费流水→条件扣分→回滚/重复结果。共享准入14个原实现先收成一份 `PlayNodeAccessService`，其他Controller入口只做同签名投影委托，保持各自原次序。**本合同没有实施生产/测试迁移，M1未开始；不以14项真库绿扩大施工授权。**

## 1. 绑定源码、独占范围和当前证据

- 公共仓库 `https://github.com/liboyang42-cpu/hengyin`；精确联合基线 **`9a2dcf21aef4d7aec7a14065a2601bd86aaad805`**，Controller blob `d529fcb93849a03783e2e5d979afd188179c7be7`。原 d359 计划 `d359cd2de8d4b5f78fc2f998db969ad8ae657327` 的 Controller blob 已变化，不能用其旧行号/旧lead实现施工。另只读核对最新root `6ecaea01e0e1a3f3182189615bc00cf9f791fd47`，本次4个重点源码+59份测试引用文件共63个blob均与9a2完全相同；不是把其余模块的版本当作相同。
- 文档工作树 `/workspace/hengyin-c-w09-m1-contract`，分支 `codex/architecture-day-c-w09-m1-contract`；B1工作树/候选76b冻结。本批仅写 `docs/delivery/architecture-day/C/`；D 的 topic copy 授权 hunk、root hint10/Photo/Theme/APPLIED/台账、G资金均未修改。
- 依据公开 `governance-scope.md` WP09、A01–A05/A12/A15、T14：一次一个完整用例，表征在前，行为修复与迁移分开；响应、权限、锁序、事务传播、奖励及异常恢复保持。不得用移13行collect或Controller业务lambda伪装归位。
- **根协调者已独立核验、非本VM执行**：9a2 / run40044166369，B1新IT4/4+原hint10/10，0failure/error/skip，两组CLEAN，原XML/source/case名/receipt hashes一致。覆盖缺lead表时GET/POST零route建局、恢复后真实route session及claim真实回滚；不证明HTTP/JWT、偏好引擎或M1。
- **最新根独立核验**：6ec/full40044336447已生成backend identity与1840份原XML并全hash匹配；ClubLeadGate10 + PlayLeadAdmissionFailure16 + ApiPlayCurrentUseBoundary6 = **32项实际全0fail/error/skip**。B1已过这些精准验收，full workflow仍在应用startup、未报最终完成。M1仍须root review本合同后授写，当前不编码。本批未dispatch、未VM Java/MySQL、无新增依赖/agent。

## 2. 实际入口、command/result（替代d359含糊处）

生产caller只有小程序 `pages/play/index.js` 的 useHint→unlockHint：固定 `nodeId + _sessionParams()`，POST原表单；冻结 `_legacyHintIntent` 防迟到UI，未在此批改动。未发现第二个生产HTTP caller。后端现有 `unlockHint(Long)` 只是未映射Java委托，仍转三参入口；不把它误报为第二个端点。

HTTP三参数仍为 `Long nodeId, String activityId, String topicId`；actor只经原 `currentMemberId()`/App身份取得。接口**只追加**：

```java
NodeHintUnlockResult unlockNodeHint(NodeHintUnlockCommand command);
int hintUnlockCost();
```

新增业务DTO完整字段（Java21 record，不依赖HTTP/Spring/Mapper/Controller）：

```java
// domain/dto/NodeHintUnlockCommand.java
public record NodeHintUnlockCommand(Long memberId, Long nodeId,
                                    String activityId, String topicId) {}
// domain/vo/NodeHintUnlockResult.java
public record NodeHintUnlockResult(Hints hints, Failure failure) {
    public record Hints(String hint1, String hint2, int cost) {}
    public record Failure(int originalAjaxCode, String message) {}
}
```

`hints/failure`恰一非null；普通拒绝沿原Ajax code/msg，成功包装仍200/“操作成功”。普通返回失败无data，不额外添加NOT_RUN/intentTerminal；**B1已有ServiceException（含403或503/NOT_RUN）不转Failure、不捕获洗成500，原对象越过应用事务/global handler**。hint1/hint2保留null、空串和原内容，使用LinkedHashMap包装，禁止Map.of因null提示而改变异常行为。

**没有虚构hint身份/版本字段**：该端点当前无actionId、expectedVersion、epoch或独立不可变response receipt。原经济唯一身份是 `(memberId,eventType=14,eventId=nodeId)`，跨activity/session/version不变、不因TTL释放。命令不添加UUID、前置版本或另一套回执；其他ANSWER/SCAN/GPS/photo/个人动作的原key/version/runtime/authority契约仍留原入口。A05登记必须如实标“原账本唯一身份/现无显式命令版本”，不能宣称已实现全部WP05。

参数顺序不可整理：先原登录拒绝“请先登录”，再nodeId null/<=0→“缺少节点ID”，再node/无template→“该节点无提示”。仅当**原两个scope均blank**才把node.topicId变为ctxTopicId；activityId="0"不等于blank，不额外补topic。保留每处原Long.valueOf/trim差异，不能统一normalize导致“ 500 ”等原拒绝改变。应用方法也按同序拒绝非法actor，直接应用测试用可信server actor；DTO不得绑定客户端memberId。

## 3. 14个共享实现的唯一来源与目标合同

新 concrete `@Service PlayNodeAccessService` 仅搬当前14个实现，不造interface+impl/RuntimeResolver/通用准入框架，不接受业务lambda或Controller。新类自己无事务注解，使用调用者已有事务；各非事务GET不被顺带包大事务。其**单一构造**依赖按序为：

1. ICmsActivityService cmsActivityService；2. CmsActivityMapper currentPlayActivityMapper；3. CmsRegistrationMapper cmsRegistrationMapper；4. ICmsTopicNodeService cmsTopicNodeService；5. IPlayerNodeProgressService playerNodeProgressService；6. ICmsTopicService cmsTopicService；7. IOmsTicketService omsTicketService；8. ClubLeadMapper clubLeadMapper；9. IGameSessionRuntimeService gameSessionRuntimeService；10. ICmsTopicChapterService cmsTopicChapterService；11. ITopicRouteRuntimeService topicRouteRuntimeService。

均复用现有bean/只读授权事实；生产必需注入，不新增缺依赖放行。旧算法中已有route/runtime空分支只保持原源码含义，测试要显式依赖或明确负控，不在生产新增mock/no-lead/null fallback。CurrentPlayRegistration和所有Mapper XML/缓存flag原样复用，不复制当前资格/业务规则。

目标公开签名：

```java
Context resolveContext(Long actor, String activityId, String topicId, NodeScope node);
Context resolveContext(Long actor, String activityId, String topicId, NodeScope node, Long registrationId);
String activityTimeError(ActivityWindow activity);
String leadGateError(Long activityId, NodeScope node);
String routeAccessError(Long actor, Long activityId, Long topicId, NodeScope node, String originalActionId);
boolean isDelistedNode(NodeScope node);
boolean hasCompletedNodeFact(Long actor, String activityId, NodeScope node);
boolean hasCompletedNodeFact(Long actor, Long activityId, NodeScope node);
ProgressFact findNodeProgressRow(Long actor, Long activityId, Long topicId, Long nodeId);
int resolveMode(Long activityId, Long topicId);
boolean isPrevDone(Long actor, Long activityId, NodeScope node);
String unlockGateError(Long actor, Long activityId, NodeScope node);
// firstTicketMode/isNightNow保持该service私有，不额外公开API。
```

嵌套不可变字段冻结如下；**ProgressFact补id，纠正d359的两字段遗漏**：

```java
record NodeScope(Long nodeId, Long topicId, Long chapterId, Long sortId,
                 Long status, Long unlockAfterNodeId) {}
record ActivityWindow(Long startEpochMillis, Long endEpochMillis) {}
record ProgressFact(Long id, Integer status, String picUrl) {}
record Failure(int originalAjaxCode, String message) {}
record Context(Long activityId, Long topicId, boolean selfPlay, boolean nightWarning,
               boolean continuing, Long registrationId, Integer purchaseKind, Failure failure) {}
```

null节点/进度/时间边界与字段null保留，不用0/空串填补。ActivityWindow只投影现有Date毫秒，service内部仍按原new Date比较；isNightNow仍用系统Calendar原22–06边界，不顺带引入Clock/时区重定义。

| 原实现（9a2位置） | 唯一实现与必须保持的来源/分支 |
| --- | --- |
| isDelistedNode:1205 | 原status!=null且!=1；不把null改停用 |
| hasCompletedNodeFact(String):1215 | 原字符串解析/非法false→Long版；HTTP完成入口使用的原shape不改 |
| hasCompletedNodeFact(Long):1226 | 本人/node非null→findNodeProgressRow，仅status=1；routeAccessError调用Long版 |
| findNodeProgressRow:2366 | member/activity/node；仅自玩加topic；原列表首行/null规则，不改排序/查询数；返回id/status/picUrl |
| isPrevDone:2622 | 原主题在架节点sortId与status=1进度集合；不顺手增加旧查询缺少的过滤或null保护 |
| routeAccessError:2648 | 原下架已完成豁免→resolveMode→原canPlay（传原actionId）/原无runtime线性分支→原unlockGate；不换runtime/重建version |
| unlockGateError:2671 | 原前置node进度status=1；再读前置name生成原文案；不把到店status=0算完成 |
| activityTimeError:2705 | 原start未到/end已到即拒；同边界和文案 |
| resolveMode:2723 | topic.productType优先；firstTicketMode活动后主题；缺省1，不收窄已授权正常路径 |
| firstTicketMode:2737 | 原owner/type查询第一条非null mode；不排序/补ID/改先后 |
| isNightNow:2945 | 原Calendar HOUR_OF_DAY，不新增Clock算法 |
| resolveCtx四参:2967 | 仅同源五参委托null registrationId，不另写资格规则 |
| resolveCtx五参:2972 | 原activity优先、归属/CurrentPlayRegistration当前锁读/再次activity锁/时效；selfPlay continuing原查局→原资格→原夜间提示，保留402与普通拒绝差异 |
| leadGateError:3034 | **使用B1修后实现**：自玩→原runtime状态优先→ClubLead/章节；原ServiceException403不变、依赖故障503/NOT_RUN，安全日志不输出SQL/cause；禁止退回d359 fail-open |

全部签名/body hash/直接依赖调用与源行号见 `wp09-m1-source-inventory.json`；重载caller的词法集合是保守并集，表中的手工分支是实际合同，不把扫描结果当AST/运行证明。

## 4. 共享caller原先后保持（不做“统一检查全部”）

| caller | 原顺序（省略未移动业务） |
| --- | --- |
| nodes | 原activityTimeError→hintUnlockCost→resolveMode；历史/当前授权仍原路径，不改成统一新使用资格 |
| checkinOnce/answerOnce/arriveOnce | 下架/已完成→resolveCtx→mode→lead→route；FREE_COLLECT特有早分支/到店/核销仍原处 |
| revealPuzzle/puzzleHint | resolveCtx→原mode拒绝→lead→route；逐级免费提示不是收费hint解锁 |
| preference GET/POST共用helper | **B1后** resolveCtx→lead→unlock→route→mode；不撤销76b顺序，不新增GET事务 |
| sensorResult | 下架/完成→resolveCtx→route→mode→lead；保留原可写外层事务，不擅自对齐为preference次序 |
| photo | 原FREE_COLLECT filter：mode→lead→unlock；其他凭证/普通照片分支按原order，普通为lead→route→权威progress；不移动上传验证、照片同意、machineApprove或奖励 |
| isTopicCompleted/advanceProgress | 只把原resolveMode交同源实现；状态、退款/核销、奖励/完成coordinator仍原处 |
| unlockHint | resolveCtx→lead→route→template→hintUnlockCost→余额预查→ledger→member，详见下节 |

Controller保留原private签名作为**纯投影+委托桥**，无SQL、资格判断、奖励回调或旧算法副本；isNightNow/firstTicketMode仅在新owner内部被调用后从Controller移除。其他入口原事务/DeadlockRetry不改。`resolvePreferencePlayCtx`等编排仍在Controller原位置，不把它们也塞进本次service。

临时private `PlayCtx`适配保留actId/topicId/selfPlay/nightWarning/continuing/error形状；registration只用已验证Context的id/purchaseKind构造一个**私有只读投影**，不查询/落库、不补字段，不伪造资格。原两处消费者分别是isExploreChapterRedeemed读id+purchaseKind、recordFirstSuccessfulScan读id；原CAS及其后续当前读校验全部不动。不新增通用entity转换层。

`findNodeProgressRow`桥把新ProgressFact的id/status/picUrl同值投影给原局部代码。原照片路径从未完成进度行取 `machineApproveProgressId`，随后advanceProgress以id执行markPhotoMachineApproved/CAS及一次奖励；d359“只消费status/picUrl”不成立。不得丢id、改成新进度或挪审批/奖励实现。

## 5. 完整unlock应用的具体事务、依赖与返回顺序

PuzzlePlayServiceImpl现有四参构造改为**单一九参构造**，保持前四顺序，追加五个必需依赖；不保留null兼容构造：

```java
PuzzlePlayServiceImpl(ICmsMemberTemplateService templateService,
    PlayAttemptLogMapper attemptLogMapper, PlayerNodeProgressMapper progressMapper,
    UmsMemberMapper memberMapper, ICmsTopicNodeService nodeService,
    PlayNodeAccessService accessService, ICmsPointsSettingService settingsService,
    IUmsMemberService memberService, IUmsMemberPointsDetailService detailService)
```

旧requestHint/reveal/score实现不改。新unlockNodeHint**不调用**旧lockMember/requestHint/selectMaxPuzzleHintLevel，后者member→attempt的锁序与本命令不同。hintUnlockCost只移原算法，nodes和命令共用这一实现；原pointsNum优先value.intValue、status null/1、第一行、负值/0返回等行为不顺带清洗，命令cost<=0仍回cost=0。

公开unlockNodeHint设置原 `@Transactional(rollbackFor=Exception.class)`：REQUIRED、DEFAULT隔离、readOnly=false、无新DeadlockRetry/REQUIRES_NEW。Controller三参入口删原事务注解，保留URL/Operation/RateLimiter(time10,count120,MEMBER)、原参数与可信身份提取/早拒绝及Ajax包装。Controller不传Supplier/Function/Consumer/Runnable，也不持有Puzzle实现对象；应用不反调Controller或读取SecurityContext。

完整顺序固定：

1. 原actor/nodeId拒绝；读node，缺node或templateId拒绝。按原blank条件选ctxTopicId。
2. access.resolveContext→原带领检查→原routeAccessError（**routeActionId仍null**）。不得把hint虚构成ANSWER/新action或释放唯一消费身份。
3. 原templateService按templateId读取；缺template或hint1/hint2都StringUtils.isEmpty→拒绝。保持原空白字符串判断，不换isBlank。此命令原本读取当前template，不擅自改冻结模板/按历史回执吐首次正文。
4. 原hintUnlockCost；cost<=0直接返回两提示/cost0，不读余额、不写流水。
5. 普通读member point，member/null point按原0，before<cost→“积分不足”。不先锁会员，也不先查duplicate。
6. 构造原UmsMemberPointsDetail：memberId；currentPoints=before；afterPoints=before-cost；changePoints=(long)cost；changeReason=“解锁提示”；eventType=14L；eventId=nodeId；changeType=2L；delFlag=0；createTime=new Date。调用原insertUmsMemberPointsDetail（该service仍会覆盖createTime为其当前时间）。保留原insert返回值处理，不新加一条<=0分支混入迁移。
7. **仅DuplicateKeyException**走原免费重放成功：当前两提示/cost0、不扣款。其他插入异常原样抛出，整个应用事务回滚，不扩大catch。
8. 插入成功后原deductPoints(member,cost)。真实服务条件UPDATE point>=cost，0行会抛RuntimeException，不是正常false。仍按原语义调用，不把boolean false另创为新业务分支。捕获RuntimeException后，对原活动事务setRollbackOnly，返回普通失败“积分不足,请重试”（ASCII逗号）。不能只返回失败后提交刚插流水，也不能吞outer rollback异常。
9. 成功返回两提示与cost；不推进节点/报名/奖励/通关，不新增外部动作/日志账本。

### 必须保留的SQL/锁序

活动：普通node/activity读→CurrentPlayRegistration内部**activity FOR UPDATE→原报名查询候选→registration FOR UPDATE**→原再次activity锁读/时效→lead/必要章节读→原route服务（可能同事务建局）→template/settings读→普通member余额读→**event14唯一ledger INSERT锁→member条件UPDATE行锁**。自玩：原hasRunningSession读→registration锁→夜间→后续同序。每次原查询数、候选顺序与锁重读不通过新缓存合并，不能擅删“重复activity读”。

member普通预读不是行锁，不得把它改为selectForUpdate；新方法不能复用Puzzle.lockMember，也不能把deductPoints提前到ledger之前。事务已在service中时route REQUIRED参与同一事务，保留原DEFAULT事务下的实际传播，不新设RC。基本动作外层RC/retry及lease/回执写完全不动。

## 6. 字段退场、测试接线与精确文件owner

完成迁移后Controller新增一个PlayNodeAccessService必需注入。只删除已经无生产消费者的四字段：`clubLeadMapper`、`omsTicketService`（共享access owner），`cmsPointsSettingService`、`umsMemberService`（Puzzle owner）。**保留umsMemberPointsDetailService**：nodes仍按原唯一身份批量读已解锁事实；其他catalogue、当前资格Mapper及runtime字段在未迁移路径仍使用，不能批量删除。旧私有hintUnlockCost成为 `puzzlePlayService.hintUnlockCost()` 一行委托。

精确生产施工文件只有：

- `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiPlayProgressController.java`：上述HTTP适配、14helper纯桥/私有投影、四无消费者字段退出；不移动其他24端点业务。
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/IPuzzlePlayService.java`：仅追加两方法/import，由root单一合入共享合同。
- `.../service/impl/PuzzlePlayServiceImpl.java`：九参构造、完整unlockNodeHint、原hintUnlockCost；原六个谜题API不改。
- 新 `.../service/support/PlayNodeAccessService.java`：上述14来源实现/records。
- 新 `.../domain/dto/NodeHintUnlockCommand.java`、`.../domain/vo/NodeHintUnlockResult.java`：上述字段，不另造全局异常/响应框架。

测试实施范围按 **`wp09-m1-test-wiring.md`完整55行+当前新增4行**。source inventory记录每个文件hash、constructor位置、所有共享依赖setField与helper反射。d359的55引用文件/41直接构造文件均仍在；当前实际59/45。不是让55份全改：仅注释/无共享调用者不接新服务，反射/公共契约仍回归。

计划新增有限test-only `PlayNodeAccessTestFixtures`，setup结束时捕获原依赖并构造真实access，显式接原lead/tickets；原CurrentPlayControllerFixtures继续提供activity/registration锁读委托，移除向已退场Controller字段写入。测试也不在业务执行期间反调Controller查新依赖。后置替换runtime/progress等实例后明确rewire；same mock重stub不用rewire。P/H测试把原settings/member移入真实Puzzle，不能每个fixture复制定价或mock应用成功。fault tests仍证明leaf定价/积分/奖励/终态零调用；新的应用入口调用本身不是经济效果，不能保留一个对“入口零调用”的错误Mockito假设而阻止业务测试。

四份现有Puzzle直接new（ApiPlayPuzzleHintTest、JourneyPublishFreezeTest、PuzzlePlayServiceImplTest、PuzzleRevealArrivalFactTest）全部显式改九参。新增 `business/service/impl/NodeHintUnlockApplicationTest.java` 直接调用真实service，无Controller；增加正常/拒绝/重复/并发/rollback及HTTP适配差分表征，不能复制旧生产算法作新oracle。

root所有 `PlayHintUnlockMySqlIT` **先交精确fixture delta再由root单写**：10个@Test/原并发断言/精确schema清理不动；应用迁移后actor查身份会在TX外，原getAppUserId中的实际可写TX、CONNECTION_ID与callerEntered latch断言必须**移到新应用首次调用的原ICmsTopicNodeService.selectCmsTopicNodeById测试桩**：answer先断言实际可写TX、以真实JdbcTemplate读CONNECTION_ID并记录、在资格锁之前countDown，随后返回原同一node。actor方法只证明可信身份提取，不删除事务/连接证明，也不只把期待改false。这样败者在真实资格锁之前报告已进入新应用事务，仍有两个真实connection，不因错误挪latch造成串行伪并发。B1 IT4的hint使用独立应用装配/事务断言位置；其answer保留原Controller可写事务/claim证明，preference保留无外层TX及真实route断言，不能对共享fixture一刀切关闭assertTx。

根/其他owner：Mapper/schema/唯一键、CurrentPlayRegistration、UmsMember/UmsMemberPointsDetail资金实现、ApiClubController、root CI/架构owner/命令注册/ledger库存、G资金/完成读侧、Theme/APPLIED/Photo专属文件不在本批改写。新service的三个Mapper读依赖按原资格/带领事实owner逐条登记A01退出条件；不新增Mapper SQL或跨域写。根若更新 `ci/architecture/owners.json` / `commands.json`，仅登记精确迁移owner/调用源与原event14事实；commands当前是参考清单，不新增审批JSON/运行许可机制，不重建 `ci/architecture/baseline.json` 或扩包级例外。root统一接线/回读action-ledger的原hint控件到新应用入口；小程序控件ID/权限/按钮不变，保留伪造控件必红负控，不新增unknown handler豁免或重冻UI baseline。新依赖图为Controller→Puzzle/access，Puzzle→access，access→原runtime/catalogue/资格；当前生产IPuzzle消费者仅两个Controller，未见service反向依赖。静态检查不能证明Spring无环，必须同源启动/SCC；不加@Lazy掩盖新环。

## 7. 行为表征/机械证据与P2单列

根已核验B1合法旧源的unit32（6ec）+IT14（9a2）精准终态；本次只读核对的63个C相关文件blob两源一致。其余模块和完整workflow不据此宣称通过。M1内部可分两个**纯机械提交**以便审阅：A=14共享实现+显式fixture接线，B=完整unlock/定价/事务归位；只有B及T14完成才叫首个完整用例归位，A不冒充WP09完成。二者都须后续root授写，当前均未编码。

同一表征输入对旧B1与M1候选分别在受控独立schema执行，记录候选SHA、HTTP响应code/msg/hint1/hint2/cost、member余额、event14原唯一身份/笔数/金额、报名/进度/奖励/回执向量、SQL与锁序/传播。时间/生成ID只允许精确登记归一化，金额/状态/错误/查询数不忽略。root新hint10与B1 IT4保持原业务断言，再补direct应用/HTTP包装/事务切面/依赖启动证据。所有SQL连接和cleanup仍真实MySQL，XML计数/case名/skip/CLEAN按根harness回读，不把本文件行数当执行数。

必须保留：活动/自玩合法首次、免费/停用/pointsNum/value/仅hint2/null提示、匿名/坏目标/无模板、错主题/旧票/取消/时效/集合/章节/前置/下架各原分支；顺序/并发重复与响应丢失后新service恢复；ledger前故障、插入后异常、余额竞争、扣分写后异常的完整回滚；其他caller原action/expected/version/照片ID/CAS奖励及GET无新增事务。新增直接service用例需真实Spring manager验证有效TX，不能只做注解反射。

**P2-余额预查影响重复可用性（独立行为项，不在M1修）**：已解锁会员后来余额不足，当前先余额检查会“积分不足”，未到duplicate读回。是否先查原解锁事实与当前读取授权/当前正文，需要root和积分owner统一原权益/回执合同；不能趁归位先锁member、改成member→ledger、先扣分，或改变唯一key跨activity/session覆盖范围。未来需专门同key已解锁+余额不足正控、取消/退款/跨账号读取拒绝与并发锁序实证。

另一独立风险是普通余额预读使ledger currentPoints/afterPoints在并行其他消费下可能非实际扣前后余额；不在本批改账本快照/算法，不把条件UPDATE唯一性冒充资金守恒证据。相关并发负例由资金owner统一设计，发现真实缺陷先冻结机械批、独立行为修复后重取表征基线，不删断言或扩大baseline。

## 8. 回退、授权停点与本轮实跑

无新生产DDL/数据迁移/双写/资金回填。机械代码回退只能回到**已修B1**，不能退回d359 lead fail-open或撤销preference先lead；不删除event14消费、不回补余额、不释放唯一身份、不改旧照片/回执。如果依赖装配失败，回退完整机械增量或停新hint命令，保留事实读取，不能靠null fallback续跑。

当前只读施工合同和清单已完成；实际仅执行Python词法/字段/源码hash盘点、文档diff检查/秘密扫描。**本轮Java/JUnit/MySQL/Node/HTTP/CI均未执行**，未声称迁移等价已通过。源JSON是read-only inventory，不是字节码依赖、AST、锁图或事务证明。根已收到上述B1精准终态，仍需review本合同的精确接口/文件owner/fixture delta后再授写。WP09其余端点与WP13可观测/资源隔离仍未完成，不用Photo四阶段或本合同代替。
