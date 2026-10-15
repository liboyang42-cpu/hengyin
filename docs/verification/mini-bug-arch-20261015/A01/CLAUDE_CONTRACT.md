# A01 给 Claude 的既有合同

本单未新增HTTP API；与A03协作新增内部snapshot可选endingChapters键。以下是源码合同，不是现场HTTP验收；所有身份、ID与数据均为虚构fixture。成功使用AjaxResult `code:200,msg,data`；游戏已知错误为AjaxResult `code:500,msg,data:{reasonCode:...}`（使用默认error包装），不等于HTTP 409/500。Controller未显式设置传输层状态；实际HTTP状态须VPS核验。

## 游戏会话

| 真实HTTP | 参数 | 返回及约束 |
|---|---|---|
| GET `/api/game/session/view` | query activityId，perspective=PLAYER/MERCHANT/CLUB | data为该身份安全投影，含revision、status、availableActions等既有字段；身份由登录会员及商家权限/活动关系校验，无客户端memberId授权 |
| POST `/api/game/session/command` | JSON activityId、可选nodeId、requestId、expectedRevision、action、payload对象 | action由服务端trim+大写；requestId后端非空且<=64；非PREPARE要求expectedRevision>=0；客户端另限制requestId为8–64位字母数字下划线连字符。action专属payload继续依现有实现，不新增统一万能字段 |
| GET `/api/game/session/receipt` | query activityId、requestId | data含receiptId/activityId/requestId/action/outcome/reasonCode/revision/replayed/result；未找到为PENDING、无receiptId，不能判成功 |
| GET `/api/game/session/merchant/entries` | 无 | 当前账号可负责的站点入口 |
| GET `/api/game/session/recap/export` | query activityId | 俱乐部主理人聚合复盘，未生成拒绝 |

command的actor/perspective由action派生，不能在payload指定别人的会员/商家主体。回执键范围为活动+当前登录会员+requestId。SHA指纹包括activityId/nodeId/expectedRevision/action/payload，payload对象键排序、数组顺序保留；客户端保留**完整原信封**，重试不能改revision或payload。同键原命令重放原result/revision，replayed=true；不同命令为GAME_IDEMPOTENCY_CONFLICT。新键旧revision为GAME_STATE_CONFLICT；其他重要reasonCode为GAME_BAD_REQUEST、GAME_FORBIDDEN_SCOPE、GAME_SESSION_NOT_PREPARED、GAME_TASK_EVIDENCE_INVALID、GAME_EVIDENCE_STORAGE_UNAVAILABLE。它们均不是新的HTTP状态协议。

APPLIED才是确定已生效；FAILED为确定业务失败，reasonCode/result.reason解释失败；PENDING或网络错误或服务内部错误保留未知，按原键读回及刷新view，不换键盲重试。FAILED回执不保证所有错误均落库：缺身份/无有效指纹及落库故障可没有回执；PENDING查询也不代表服务端已领取。读取原回执不授予新的动作权限。无新增分页、缓存TTL、trace字段、epoch或version字段；使用既有revision。第二设备同账号读同源，必须先刷新view，不能靠另一设备本地旧快照推进。

明确fixture：测试会员7、活动71、站点8、revision4，已有商家岗位且站点属于该商家。JSON `{"activityId":71,"nodeId":8,"requestId":"a01-ready-71","expectedRevision":4,"action":"STATION_READY","payload":{"capacity":12}}` 仅在站点及准备配置满足既有条件时成功；原回执示意 `{"receiptId":501,"activityId":71,"requestId":"a01-ready-71","action":"STATION_READY","outcome":"APPLIED","revision":5,"replayed":false,"result":{}}`。result是动作专属内容，空对象仅为信封示意，不能用此替代真实站点fixture。重放返原result且不二次推进；同键改capacity应GAME_IDEMPOTENCY_CONFLICT；换键仍revision4应GAME_STATE_CONFLICT。假上传/缺必需照片拒绝首次完成且无奖励。若首请求断网，fixture预期为未知；同键receipt读到APPLIED才确定成功，PENDING继续保持未知。这些后端fixture未执行。

## 路线、旧局与继续游玩

GET `/api/play/route-state` query topicId、activityId（兼容actId；activityId优先；有活动可由服务端推主题），读取登录会员当前路线；GET `/api/play/nodes` query activityId/topicId读取节点权威态。既有完成口均是POST参数绑定：`/api/play/checkin` activityId/code/topicId/routeActionId/expectedRouteVersion/可选registrationId；`/api/play/answer` activityId/nodeId/answer/topicId/routeActionId/expectedRouteVersion；`/api/play/arrive`再带longitude/latitude/accuracy；`/api/play/photo`带picUrl，缺图拒绝。坐标与照片不是新增trace字段，不记录到本合同测试输出。

路线使用routeActionId、expectedRouteVersion及server完成receipt，不等同game的requestId指纹信封。服务内route action按session/member/actionId读原结果；同actionId即使换node/receipt仍返原推进结果，这是明确保留的兼容行为。客户端禁止复用该身份表达新动作，也不能期待这里检测所有异命令。新动作版本冲突为ServiceException业务409及当前routeState；实际HTTP包装由现有控制器/异常处理决定，不把内部码直接写成HTTP409。

GET `/api/play/run-session` query activityId/topicId读登录会员暂停用时；POST `/api/play/run-session/save` 参数activityId/topicId/elapsedSeconds/savedAt（elapsedSeconds 0..604800，savedAt>0）；POST `/api/play/run-session/clear` activityId/topicId/savedAt留ENDED墓碑；GET `/api/play/run-session/list`最多20条。它只是用时/暂停投影，不能替代nodes/route-state的完成、时间窗、路线终态与奖励真源，不新增会话中心。没有requestId/epoch字段；savedAt不等于路线version。归属均从服务端会员取得。

新建局在configSnapshotJson内冻结endingChapters映射（id/name/atmospherePreset/audioUrl/blocks），读侧仅向玩家返回已选择结局章。没有该键的历史快照沿原实时正文回退，已丢失正文无法恢复；有键但无该章返回null，不读取现行章补齐。结局条件、选择顺序和优先级不改。旧局继续消费开始时configSnapshotJson及moduleConfigSnapshotJson；历史缺nodeJourney字段按既有回退规则，不宣称所有老数据均有完整冻结块。playInstanceId是局身份，服务内recordNodeResult会拒绝旧局；客户端epoch用于拒绝晚到回调，没有服务端同名字段。丢响应、退出重进或第二设备先读上述权威状态，不能把本地待上传照片显示为服务端完成。未知恢复面板、新入口及新产品由Claude另行设计，A01未提供新UI接口。

fixture：会员7/主题10/自玩activityId0，路线初始version0；routeActionId=client-action-1完成node1后version1，重发原动作取原结果；另一个新actionId携version0应业务冲突。发布新模板后旧局有nodeJourney时继续用冻结配置；无该键的历史局走既有兼容回退。另会员8不可读会员7的sessionId。以上需CI/测试库/设备独立读回，不是已通过验收。
