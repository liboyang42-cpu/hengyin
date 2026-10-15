# 共用后端变化与iOS影响记录

> 三方统一协作规则：[CY-COORD-20261015-v1](COORDINATION.md)。当前文件归属与候选见[OWNERSHIP.md](OWNERSHIP.md)；初始规划不等于已领单。最终提示词：[Codex](START_PROMPT.md)、[Claude](CLAUDE_START_PROMPT.md)、[iOS](IOS_START_PROMPT.md)。

## 执行发布记录（2026-10-15，优先于后面的初始规划）

CY-HANDOFF: CODEX。控制协议 `CY-COORD-20261015-v1`；唯一协调者C00/C99。业务草稿 [PR18](https://github.com/liboyang42-cpu/hengyin/pull/18)，worker公共基线 `d551c6babd018d41aec90dad2c0acaf1c534644d`，本次源码候选 `c24202f6`（原 `c236f131` 加两个既有补偿测试的调用前失败前提修正）。精确锁见OWNERSHIP.md；后续纯文档提交不代表新部署。

**本轮没有合并GitHub PR、执行DDL、部署、上传或生产变更。所有 TEST_DEPLOYED 均为 NO，iOS适配/微信设备/独立业务读回均未签收。** 本Cloud没有配置可核实的授权VPS路径和必要身份，未回落生产或自行搭建Java/MySQL。各项fixture是虚构输入，只准源码/fixture适配，不得当服务器已上线。

### 发布层级与证据

| BK | 状态 | 源码及相关责任 | 当前测试/限制 |
|---|---|---|---|
| GPS / LIKE / BADGE | CONTRACT_FROZEN | 已评审复用PR12，收入PR18；C00保留核心锁 | 原有方法/合同见下方初始基线；同候选CI正在重验，无真实GPS/两账号读回 |
| AUTHOR | CONTRACT_FROZEN | B02 `cc9e99c`，配置缺席保真 | 专属5项Java测试交现有CI；首次create丢响应仍UNKNOWN |
| ASSET | CONTRACT_FROZEN | B03 `cc6a8b9`，锁读及应付CAS | c236f131 Funds MySQL IT run38728912034成功；B03额外opt-in真库用例不在该门禁，未运行，不能推导并发业务已验 |
| SCHEDULE | CONTRACT_FROZEN | B04/B01 `1352b6a`、`38513c9` | 6项Java用例交CI；既有JS消费8/8；VPS/设备待验 |
| PRIVACY | CONTRACT_FROZEN | B05 用户批准 `e437a52` | 已删旧详情及编辑历史最小读取修复；两身份mapper用例交CI，无真实旧媒体URL验收 |
| SESSION | CONTRACT_FROZEN | A01 `38513c9`，内部结局快照 | 客户端game-session回归16/16；命令/回执现有合同，不虚构统一恢复API |
| AI | CONTRACT_FROZEN（仅下述已固定部分） | A02 `f72ddb1`、`3b53769`，补偿测试 `c24202f6` | c236f131总10095项、失败2、错误0、跳过530；两个失败为旧用例仍按调用后异常退额，已修调用前触发，待新CI |
| NARRATIVE | CONTRACT_FROZEN | A03 `38513c9`，构造测试修正 `3ee0fd0` | 旧选择策略与快照读侧测试交CI；历史缺快照正文不可追溯 |
| XCX | SOURCE_READY（仅已运行Node范围） | B01 `0171a6c`、`8a83c83`、`38513c9` | 生命周期/账号/退款/排期相关Node输出在B01；C99 ledger40/40，GET/route31通过，UI-GATE-0 PASS；不是整端真机验收 |
| EXISTING | CONTRACT_FROZEN（列明原接口部分） | PR14/15兼容集成，C00 | 官方路线只读、积分全量等边界保留；认领材料另列DRAFT |
| CLAIM-MATERIAL / AI-DELETE / AI-VOICE | DRAFT | 见下方具体待决策项，尚未委派共享核心 | 不启动猜测协议的写接线；不因其他任务阻塞停止独立工作 |

所有行的GitHub合并状态均为 UNMERGED，测试部署 NOT_VERIFIED。相关测试通过后由协调者逐项更新SOURCE_READY；530项跳过不计通过。c236f131后端日志通过现有ci/backend-test.sh保留原 `mvn -B clean test` 和退出码、仅收敛控制台噪声以显露失败；未改workflow/pom/门禁测试列表。动作ID总数4480保持，4项退款控件新增真实订阅授权后改分类，保留原退款POST、重复触发及服务端/设备读回要求。

### 已冻结差异及两端共用fixture

以下对应PR18的 `docs/verification/mini-bug-arch-20261015/<任务>/` 合同/专属测试；早期lane文档中的“尚未运行/前半待做”是提交时快照，以本记录和实际CI SHA为准。未列出的HTTP方法、权限、分页、requestId、version与旧行为均不因本轮自动扩展。

- **BK-AUTHOR（B02→iOS I03/Claude作者端）**：POST `/api/topic/create`、`/v2/create`、`/update`、`/v2/update`（后3同/api/topic前缀），POST `/api/topic/edit-detail`、`/publish-draft` 保持原绑定、AjaxResult和本人/商家PROJECT_MANAGE权限。更新缺席publishMode、teamMode/teamMaxMembers、xpBudget、selfPlay及其未传price/quota保留；显式0仍关闭。仅productType及票mode都缺席时保留类型；chapters/tickets等仍全量替换，不能泛化PATCH。fixture member42/topic700原team2/max3/selfPlay1/price8/quota9/xp6200，最小改名保存后保持；显式关闭生效，teamMode7拒绝。保存重新读edit-detail取得重建章节节点ID/configVersion。没有新增create requestId或全主题revision；首次create丢响应仍未知，不按名称猜成功。
- **BK-ASSET（B03→iOS I04/I07） NO_API_CHANGE**：新增内部会员积分当前锁读，不改变奖励倍率、NULL eventId幂等、旧订单/资金主体。会员余额NULL拒绝，不coalesce为0。confirm/paid/invoice SQL CAS增加amount_total>0，开票兼有冻结保护；竞态零行false不能当成功，付款双人复核保留。fixture余额100，另业务扣30再奖5应75/流水70→75；两个不同事件各奖5为110，同非空事件一笔；应付100追扣120后不得推进确认/付款/开票。无新HTTP/DTO/分页/requestId/version；未知按原流水/批次读回，不换事件键补发。
- **BK-SCHEDULE（B04→iOS I09/Claude排期）**：POST `/api/club/event-ops/series/detail`、`/list` 既有data新增可空startTime（上海HH:mm），来自未来有效场次共同时间；无场次或混合14:30/16:45返回null，序列化缺字段同未知。登录+ACTIVITY_MANAGE保留，list仍非分页。fixtureclub21/series61未来00:30读回00:30；切换空/混合系列清空旧值，原必填闸阻止静默09:00保存。`/update-future`仍expectedVersion，保留日期不改时刻，无requestId；`/occurrences`任意报名历史、结算或WAITING/OFFERED/CLAIMED候补使editable=false，不能代替写时CAS。沿原400/409业务异常，不假定HTTP状态。
- **BK-PRIVACY（B05→iOS I05/Claude内容） NO_API_CHANGE**：用户批准作者分支增加lifecycle不等于DELETED；旧详情和由相同读取链保护的编辑历史不可见。其他作者草稿/审核中状态保留，不切内容链、不放宽RBAC。fixture作者与非作者重读同已删ID均不可见；未删合法公开/作者状态不退化。未证实COMMUNITY解绑变PUBLIC或post-to-post引用泄漏，不编造修复；原媒体独立访问仍未验证。
- **BK-SESSION（A01→iOS I02）**：GET `/api/game/session/view`、`/receipt`，POST `/command`（同/api/game/session前缀）不新增字段。原activity+member+requestId键、完整命令指纹、expectedRevision保留；同键同信封重放，异信封GAME_IDEMPOTENCY_CONFLICT，新键旧版本GAME_STATE_CONFLICT。APPLIED才确定；PENDING/缺回执/断网保留未知，先读回，不换键。AjaxResult业务code与HTTP状态分开。fixture活动71/站8/revision4/STATION_READY/requestId=a01-ready-71，原capacity12重放只推进一次；改capacity冲突。路线是独立routeActionId/expectedRouteVersion，历史同键异payload仍重放原结果，不能套game指纹承诺。暂停用时savedAt不等于路线version。详情见A01/CLAUDE_CONTRACT.md，无新增分页/epoch/API。
- **BK-NARRATIVE（A03→iOS I03/Claude创作）**：新局内部configSnapshotJson增加endingChapters，不是公开DTO/新实体；只冻结同主题引用的结局章，读GET `/api/play/journey/ending`、`/stage-end`仅投影本局选中章。键存在但缺章返回null，不实时补；整个键缺失的旧局沿原回退，不能重建已删正文。v2写ending.fallback出现时仅JSON布尔，字符串/1/null拒绝（原400业务校验）。JourneyStory首命中、Theme无priority末命中/显式priority最大且同分末命中保持；schemaVersion/configVersion/发布快照三者不同。fixture旧局冻old title，作者改new title，旧局仍old；空冻结映射不读现行章，历史缺键按旧回退；未知写入拒绝。权限/分页/回执无变化。
- **BK-AI（A02→iOS I08/Claude）**：原AI创作POST路径/DTO/权限/分页不改；未知场景、缺key/disabled/mock明确UPSTREAM_UNAVAILABLE，内部reason不自动成为新HTTP字段。system+user及完整输出每段2000 UTF-16、重叠100审核，不切代理对；未审核后缀拒绝时chat调用0。缺配置/输入审核不可用等provider调用前失败按原预占回收；MODEL_STARTED后异常/超时/空回复保留UNKNOWN，不退额盲重发；输出已生成审核失败仍保留用量。NPC原requestId回执记PROCESSING/UNKNOWN、reasonCode=REQUEST_OUTCOME_UNKNOWN、retryable=false，不承诺答案已恢复；原请求身份保留。未知tokenOut/tokenEst为null，其他估算不当真实provider计费。fixture缺key→chat0且原日/代次/nonce只补偿一次；调用后timeout→不补偿、不换键；长输入system/后缀违规不调用模型；未知成本不能显示真实0。普通generate仍无客户端requestId/查询新接口，不能自动重试。
- **BK-XCX（B01） NO_API_CHANGE**：音频中断后hide/end不自动后台续播；隐藏卸载后迟到轮询不复活；点赞按稳定对象ID，账号/局generation隔离缓存与迟到回调。三个退款handler用户手势链先请求既有refund/refundRejected订阅，拒绝/空模板不阻止真实退款；模板未配置仍未配置。WXML/WXSS未改。iOS不用复制小程序API或事件处理。
- **BK-EXISTING**：POST `/api/points/list`一次全量；POST `/api/roam/stamp/received`；GET `/api/preset/route/list`、`/route/info`仅浏览不是开局。NPC POST `/api/ai/npc/chat/data/delete`现有本人全NPC日志删除返回{deleted:n}，不是彻底删除全部回执/阻止晚到写入承诺。分页/权限仍各原接口，Claude/iOS新增入口需独立核自身适配。

兼容和回退：所有合同两端消费相同任务fixture及例外，不造第二份后端；UI适配源码/fixture另列为待签收。发生旧局结果漂移、旧单不可读、越权、资产重复即停止推广并回退对应最小源码提交；不得删迁移/账本/未决锁。旧服务包没有新startTime时客户端保持未知而非默认保存，新局快照缺键按兼容路径。没有部署记录就不安排“已上线”联调。

### DRAFT与实际请求处置

- **CLAIM-MATERIAL / PR21 CL5**：`uploadOSS.fileName`有源码证明是原OSS对象键，`url`是另签URL；app.chooseImage默认取url，当前claim-sheet未覆盖，merchant-claim把URL作为assetId且固定PHOTO。服务只校验type/id非空及32/255长度、1–10份，无type枚举、对象归属/存在校验；同requestId异指纹业务409由controller转code/msg。历史DDL注释不能作当前业务合同。待原业务确认proofType、上传bizType、审核读取及旧URL兼容的具体fixture；不冻结PHOTO，不要求Claude猜键/先修UI接线。共享上传核心保留Codex；认领专属Service/DTO/Mapper可在合同与精确文件登记后委派Claude，目前未授权其改鉴权/DDL。
- **AI-DELETE**：现有接口只删日志，safe_text回执仍保留，晚到saveLog可重建；删除屏障/留存变更尚无批准方案，保持DRAFT，不标全量永久删除完成。独立新UI设计可继续。
- **AI-VOICE**：ASR/TTS独立日池无获准政策；不擅自启用新计费/语音配额。admin Vue的token||0展示会把null误作0，属Claude UI待接，不抢写后台页面。
- **PR21 CL1–CL4**：已读取d9dc65a全部文档并核PR17/20冲突。精确领取/保留见OWNERSHIP；全roam/utils/pages/club通配申请不批准；event-ops/index.js及NpcChatService等核心仍原所有者。新增举报/徽章页面和专属测试缺精确清单，等待自己的CY-HANDOFF补齐。既有无冲突文件可按登记继续独立设计，不因CL5待定阻塞。
- **iOS**：已主动查询chengyin-ios开放PR与CY标记，暂无实际交付；已有多个native分支，活动所有者未知，不回收、不选分支，不声称已通知/接收。I01–I11影响使用上述同一合同，原生适配/Swift/设备结果须由其实际SHA交付。
- **PR17/20**：当前不整包集成。PR20有新DDL和漫游/订单/账本重叠，PR17不同父链且有额外隐私工作，保留原作者；作者的成功/部署声明不算PR18证据。

下文保留启动时原始快照以便审查已有合同来源；其中“计划/未冻结/未运行”不覆盖本执行记录，也不表示当前目标环境状态。

2026年10月15日初始快照。**记录区分已有公共补丁、计划工作、现有接口和实际部署，计划不等于已变更。** 由C00/C99协调者独占更新；后端各worker提交变化片段，iOS agent按记录消费，不并行修改后端独占源码。iOS任务入口见 [IOS_HANDOFF.md](IOS_HANDOFF.md)。

## 已查询PR状态

| PR | HEAD | 目标分支 | 状态 | 部署证据 |
|---|---|---|---|---|
| [PR #11](https://github.com/liboyang42-cpu/hengyin/pull/11) | 0b301c9b8d18 | codex/mini-foundation-cloud-handoff-20261013 | OPEN草稿，未合并；checks返回0项 | 本轮未核 |
| [PR #12](https://github.com/liboyang42-cpu/hengyin/pull/12) | a5af41f57fa3 | claude/mini-foundation-abc-integration-squash-20261014 | OPEN草稿，未合并；checks返回0项 | 本轮未核 |
| [PR #13](https://github.com/liboyang42-cpu/hengyin/pull/13) | d6cd231e9354 | claude/mini-foundation-abc-integration-squash-20261014 | OPEN草稿，未合并；checks返回0项 | 本轮未核 |
| [PR #14](https://github.com/liboyang42-cpu/hengyin/pull/14) | 6a9647714286 | claude/mini-foundation-abc-integration-squash-20261014 | OPEN草稿，未合并；checks返回0项 | 本轮未核 |
| [PR #15](https://github.com/liboyang42-cpu/hengyin/pull/15) | 8884a678ea30 | claude/mini-foundation-abc-integration-squash-20261014 | OPEN草稿，未合并；checks返回0项 | 本轮未核 |

查询时PR16是文档PR，不能作为这些业务实现已合并/部署的证据。下表状态只对应本次查询，后续以实际提交和运行证据更新。

| 编号 | 主题 | 性质/当前状态 | 来源/责任 | 原生关联 |
|---|---|---|---|---|
| BK-GPS | GPS到点精度 | 已有后端补丁提案，未合并；部署未核 | [PR #12](https://github.com/liboyang42-cpu/hengyin/pull/12) | C00复用，iOS I01 |
| BK-LIKE | 显式点赞方向 | 已有后端补丁提案，未合并；部署未核 | [PR #12](https://github.com/liboyang42-cpu/hengyin/pull/12) | C00复用；iOS实际调用方 |
| BK-BADGE | 平台徽章范围 | 已有后端补丁提案，未合并；部署未核 | [PR #12](https://github.com/liboyang42-cpu/hengyin/pull/12) | C00复用；iOS I07 |
| BK-AUTHOR | 作者保存读回合同 | 计划修复，字段变更未冻结 | B02 | B02→iOS I03 |
| BK-ASSET | 积分券库存及商家账务 | 计划修复，政策不变 | B03 | B03→iOS I04/I07 |
| BK-SCHEDULE | 排期时刻及候补保护 | 计划修复，政策不变 | B04 | B04→iOS I09 |
| BK-PRIVACY | 私密内容引用及撤回 | 计划复核/修复；授权模型变更另批 | B05 | B05→iOS I05 |
| BK-SESSION | 会话回执及结果未知 | 计划整理；逐接口合同保留 | A01 | A01→iOS I02 |
| BK-AI | AI失败完整输入及用量 | 已有线索待修；新字段未冻结 | A02 | A02→iOS I08 |
| BK-NARRATIVE | 结局选择及内容版本 | 计划兼容整理；新策略未批准 | A03 | A03→iOS I03 |
| BK-EXISTING | 现有接口与小程序新入口 | 接口已存在；PR14/15是小程序接线，不是后端已改 | [PR #14](https://github.com/liboyang42-cpu/hengyin/pull/14)、[PR #15](https://github.com/liboyang42-cpu/hengyin/pull/15) | C00核合同；iOS I07/I08/I11按原计划 |

## BK-GPS GPS到点精度

性质：已有后端补丁提案，未合并；部署未核。来源：[PR #12](https://github.com/liboyang42-cpu/hengyin/pull/12)；交界：C00复用，iOS I01。

当前合同或计划：POST /api/play/arrive 坐标判定路径新增可选accuracy，单位米；空白兼容旧调用，非有限/负值拒绝，超过80拒绝。它不是每个游玩API统一新增的必填字段，也不代表已有专用错误码。

iOS影响：当前原生location证据类型与complete字段无accuracy，需要保留真实测量精度，核坐标系/时间/扫码兼容。不能故意省略差精度绕过。

验证：先单测契约，再按目标测试部署核阈值内外/缺失/无效与扫码；不可因PR已写就把服务端视为已上线。

部署状态：NOT_VERIFIED。本记录不声明目标环境已经具备上述能力。

## BK-LIKE 显式点赞方向

性质：已有后端补丁提案，未合并；部署未核。来源：[PR #12](https://github.com/liboyang42-cpu/hengyin/pull/12)；交界：C00复用；iOS实际调用方。

当前合同或计划：POST /api/topic/like：显式type=1赞、type=0取消重复幂等；省略/空type保留旧toggle。非法type的现有回落尚未形成新严格校验合同。

iOS影响：核原生具体operation factory/适配器，明确发送方向；不能按“默认1”改。纯like_list读面不需要多加写权限。

验证：赞→取消→赞、重复/乱序与账号切换；用真实读回核结果，不只看按钮。

部署状态：NOT_VERIFIED。本记录不声明目标环境已经具备上述能力。

## BK-BADGE 平台徽章范围

性质：已有后端补丁提案，未合并；部署未核。来源：[PR #12](https://github.com/liboyang42-cpu/hengyin/pull/12)；交界：C00复用；iOS I07。

当前合同或计划：selectAllActive限制club_id is null，俱乐部定义走独立scope查询。不是新全局徽章授权模型。

iOS影响：平台墙数量/分组/缓存可能变化，不把平台缺席补回俱乐部私有定义，不破坏身份卡降级语义。

验证：两账号/平台墙/俱乐部墙分别核归属与缓存。

部署状态：NOT_VERIFIED。本记录不声明目标环境已经具备上述能力。

## BK-AUTHOR 作者保存读回合同

性质：计划修复，字段变更未冻结。来源：B02；交界：B02→iOS I03。

当前合同或计划：现有create/save/edit/publish的ID、顺序、节点绑定、规则与revision保真。尚未发布新的DTO版本/接口。

iOS影响：保留原生草稿/发布回执与媒体未决状态；等真实差异和fixture后再改decoder，不猜字段或造新草稿实体。

验证：保存重开/重排/二次编辑/丢响应/旧数据样本。

部署状态：NOT_VERIFIED。本记录不声明目标环境已经具备上述能力。

## BK-ASSET 积分券库存及商家账务

性质：计划修复，政策不变。来源：B03；交界：B03→iOS I04/I07。

当前合同或计划：验证实际并发与冲正缺口；不自动改积分倍率、旧订单快照、退款范围或资金主体。cart仅小程序无调用不代表原生没有既有适配。

iOS影响：报价、付款/退款/核销与资产以服务器读回；不要用SDK布尔确认，不自行改旧订单显示单位。

验证：同业务重复/不同业务并发/超时未知/追加冲正及独立账本。

部署状态：NOT_VERIFIED。本记录不声明目标环境已经具备上述能力。

## BK-SCHEDULE 排期时刻及候补保护

性质：计划修复，政策不变。来源：B04；交界：B04→iOS I09。

当前合同或计划：保留原集合时刻/时区；编辑与生成保护纳入真实报名及候补。没有新增已发布日程DTO。

iOS影响：原生已开编辑页和allowedActions需重新读事实，默认时间不能覆盖已存时刻。

验证：非默认时刻、跨日期边界、候补、重复生成、旧页面操作。

部署状态：NOT_VERIFIED。本记录不声明目标环境已经具备上述能力。

## BK-PRIVACY 私密内容引用及撤回

性质：计划复核/修复；授权模型变更另批。来源：B05；交界：B05→iOS I05。

当前合同或计划：保留现有隐私政策，核正文/媒体/引用/旧链接与删除关联。没有批准切换内容链或放宽RBAC。

iOS影响：旧snapshot不是永久访问许可；适配失效/拒绝及缓存收口，不用仅本机隐藏替代后端校验。

验证：作者/非作者/退群/撤回/旧媒体链接的独立再次读取。

部署状态：NOT_VERIFIED。本记录不声明目标环境已经具备上述能力。

## BK-SESSION 会话回执及结果未知

性质：计划整理；逐接口合同保留。来源：A01；交界：A01→iOS I02。

当前合同或计划：复用现有session/state/receipt与CAS；同requestId异命令仍冲突，相同命令定位原结果。没有新建已发布统一回执端点，也没有批准给所有写接口加requestId。

iOS影响：原生已有game/session/receipt、expectedRevision与retryExact；route advance等另有版本字段，不混成一个数字。进程重启保留原请求身份，媒体缺证不算有效完成。

验证：超时、重复/异命令、晚响应、epoch变更、重启与第二设备。

部署状态：NOT_VERIFIED。本记录不声明目标环境已经具备上述能力。

## BK-AI AI失败完整输入及用量

性质：已有线索待修；新字段未冻结。来源：A02；交界：A02→iOS I08。

当前合同或计划：先修缺配置mock成功、完整输入未审核风险，再核provider真实usage/估算、失败归还和未知对账。价格/留存/付费场景不自动改变。

iOS影响：原生区分不可用、明确失败和未知；无usage不能显示成0真实成本。新的字段/原因码以协调者实际diff为准。

验证：缺配置、审核失败、超限、丢响应及额度恢复；没有真实付费调用当单测。

部署状态：NOT_VERIFIED。本记录不声明目标环境已经具备上述能力。

## BK-NARRATIVE 结局选择及内容版本

性质：计划兼容整理；新策略未批准。来源：A03；交界：A03→iOS I03。

当前合同或计划：保留各旧运行时首命中/显式优先级与会话开始快照；不按数组下标降序反转，不硬要求旧内容版本等于新协议版本。

iOS影响：原生渲染消费权威结果；状态键/条件编辑与预演复用已冻结fixture，不重写一套结局真源。

验证：同一旧样本结果不漂移；旧局更新后仍可读；未知写入拒绝。

部署状态：NOT_VERIFIED。本记录不声明目标环境已经具备上述能力。

## BK-EXISTING 现有接口与小程序新入口

性质：接口已存在；PR14/15是小程序接线，不是后端已改。来源：[PR #14](https://github.com/liboyang42-cpu/hengyin/pull/14)、[PR #15](https://github.com/liboyang42-cpu/hengyin/pull/15)；交界：C00核合同；iOS I07/I08/I11按原计划。

当前合同或计划：POST /api/points/list当前一次全量；POST /api/roam/stamp/received；GET /api/preset/route/list、/route/info。style/list属发布预设；POST /api/ai/npc/chat/data/delete按本人全部NPC历史。商家claims proofType/assetId真实语义待确认。

iOS影响：原生WalletCommerce已区别assetPoints非分页与另一个分页points；NPCChatData当前为export只读。不要复制小程序UI或假称原生已接删除。官方路线PR15只有浏览没有按路线开局。

验证：方法/返回/账号隔离/分页/删除范围及认领审批读回；新增原生入口要先纳入已有产品范围。

部署状态：NOT_VERIFIED。本记录不声明目标环境已经具备上述能力。

## 后续每次变更必须补齐

| 字段 | 内容 |
|---|---|
| BK编号/主题 | 复用同一业务编号；精确标记新增、修复、兼容重构或只改小程序 |
| 所有者/相关任务 | 后端责任人或worker，iOS对接任务，Claude新功能归属 |
| 实现状态 | 计划 / 提案PR / 已合并 / 候选已测；对应commit、PR、父基线 |
| 协议差异 | 方法/路径/DTO/必填/单位/返回/错误/权限/分页/requestId/version；没有变化也明确写无协议变化 |
| 兼容影响 | 旧原生调用/旧会话/旧订单/未知枚举/缓存影响；不兼容先停并给可审阅方案 |
| fixture与测试 | 正常/失败/未知/冲突/越权向量；实际运行输出，作者声明与独立验证分开 |
| 部署证据 | 脱敏测试环境标识、运行包版本/commit、数据库目标核实、时间；不记录地址/口令/生产目标 |
| iOS验收 | 待接 / 已适配源码 / 测试通过 / 测试VPS联调 / 真机通过；关联iOS SHA/设备，未做不填通过 |
| 回退 | 兼容窗口、停止条件和回退操作；不删既有迁移或重置未决锁 |

没有新消息或没有部署证据时保持原状态，不把“后端统一”推导成iOS自动开通。测试数据使用明确synthetic fixture，凭据只进获准环境，不进GitHub。
