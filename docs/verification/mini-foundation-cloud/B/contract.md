# B 小程序消息、隐私与数据消费者合同

起点：codex/mini-foundation-cloud-handoff-20261013；交接提交 e67e72a，业务基线 0f0c32f1edc955f6117108daecbb492652212931。仅本包独立分支修改，不合并部署。

## 所有权和接口

- B 拥有 OWNERSHIP.json 的 B 文件和同域新增文件/测试。协调者明确将 IImService.java、ApiImController.java、ImConversationMemberMapper.java 的最小兼容拓展归 B；现有签名保留。A/C 生产文件只读。
- NPC 静态契约先应用 B-ci 修正；继续验证真实登录身份、付款和报名现态、节点主题、有效期及 deny-before-provider，NPC 生产 Controller 属 C，资格 Mapper 属 A。
- A 报名通知调用 sendSystemInNestedTransaction(Long,Integer,String,String) 原合同保持，NESTED 写消息失败不能污染 A 外层事务；本包不新增通知渠道/群聊/旧搭子局。
- listMessages 原签名保留；新增 afterId 升序分页用于原消息增量补缺，原 cursorId 继续历史分页。
- markRead 原签名保留；新增 readTo 仅确认客户端已读水位，不越过服务端当前可见消息；水位单调，未展示的新消息保持未读。锁顺序继续 conversation FOR UPDATE → current member FOR UPDATE → 当前资格 → message。
- 新增 readSentMessage 按当前认证成员、原会话、原 clientMessageId 回读；当前成员失权拒绝，结果始终使用路线投影。未知回读不自动造新请求。
- route 投影隐藏已删/下架/私密名字、保原 ID，不改数据库原消息/extraJson/requestHash/会话摘要和普通详情权限。客户端只依据投影卡名，不从一般详情接口恢复私密名字；每次当前回读替换缓存，迟到响应不得覆盖。

## 客户端恢复和缓存

实际宿主为 subpackageB/pages/im/chat/index.js 文本/图片/位置/路线发送、消息增量、read、delete；列表原当前状态/账号 epoch 修补保持。新同域 im-send-recovery 工具按账号+会话持久化原发送意图，仅保存原 clientMessageId，不保存聊天原文、图片、位置坐标；原 payload 在页面内存用于用户明确重试。重进只查原收据，未找到保持未知。

新增未列同域接口/Mapper及通知Task由B单一拥有；pages/shezhi/shezhi.js登出入口由协调者明确归B，只添加恢复代次失效，保原鉴权/注销及已有缓存范围。

发送前落盘成功才发请求；失败停止本次发送。原发送未知响应后仅查询原 ID，用户重试也沿原 clientMessageId。重进查原结果，不自动盲发。清缓存沿现有 wx clearStorage（本范围恢复记录随之删除）；回包必须核记录身份/epoch，不能重建已清条目。

读/分页/轮询/卡详情/发送/删除回包均校验账号、会话及页 epoch；删除成功使旧回包失效，不复活会话。轮询 afterId 分页连续取完缺段再更新水位。

## 验证

Node 有意义竞争/恢复/落盘失败测试可在云环境执行；Java/MySQL/Redis 仅指定 VPS 或既有 GitHub CI，禁止云端自建数据库及修改 CI/依赖。VPS 当前无可用 TCP/凭据且目标 HTTP refused；保留 Java/真实数据库/身份/微信工具/设备未验断点。逐项原 ID、来源、摘要和所有调用方另记录本目录。

## 原报名站内通知持久意图

B单一拥有 ISystemNotificationIntentService.enqueue(Long,Integer,String,String,String)->Long，先导commit 1ce1f978893d23055862afff1ee6a5cacc1b778e 由A原样cherry-pick编译依赖。A首次建单事务固定 registration-signup:<primaryId> 和原内容；重放不读取live对象重新enqueue。B同key异内容拒绝，返回持久intentId不代表送达。worker固定最大ID逐页、逐条事务，锁 intent → conversation → member → original message；投递消息写原 clientMessageId=notification_<intentId> 与 DELIVERED 收据同事务。单条失败保持PENDING、后页继续；不处理外部微信渠道/UNKNOWN。新增测试migration未经生产DDL授权，未部署。A运行仍依赖B实现及migration。

## 共享 inventory 单一归属

协调者批准B单一拥有本次 scripts/uiaudit/action-ledger.json 机器生成刷新：4432 controlID集合完全相同，70既有控制（shezhi33/chat32/square5）只更新来源行号；其中3既有IM控制追加认证只读send-receipt targets。status/writeApis/分类/验收字段不变，无新控件，不改action-evidence或生成器。A/C不编辑同文件，最终合成时其他域差异由root在验证快照中单独生成。
