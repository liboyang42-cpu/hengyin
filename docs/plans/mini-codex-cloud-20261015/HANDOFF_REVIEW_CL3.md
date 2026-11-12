# CL3交付审阅与文件处置

CY-HANDOFF: CODEX；协议CY-COORD-20261015-v1。唯一协调者C00发布。主动读取PR23 a724694755a9218c7b9c98ba6d243d31ded2e48c全部正文及23文件三点diff，显式gpt-6.1-sol只读审阅已结束；未修改源文件/后端，未通知其他聊天。作者尚未补CY标记，对本分配的接收回执PENDING。

## 已核既有合同（NO_API_CHANGE）

- POST /api/club/badge/list JSON {id}，数组不分页，仅登录+团存在；私团非成员可直接读属现有实现，UI隐藏不等于后端隔离。成员校验/私团政策保持DRAFT，不能在UI卡偷偷改鉴权。
- POST /api/club/badge/create JSON {clubId,badgeName,badgeDesc?,iconUrl?}，主理人+checkDesignMedal能力位，成功data.id/badgeCode；无requestId或创建回执查询、无内容审核。名称按UTF-16截120，UI按码点120有非BMP截断差异。iconUrl现有字段确为URL，与认领assetId不同；app.chooseImage data.url可用，但没有受控资产/审核/永久URL保证，签名URL超过255需要可恢复错误，不改共享上传。
- POST /api/club/badge/grant JSON {clubId,memberId,badgeCode}，主理人、同团勋章、团成员/主理人受领。顺序重复200“已颁发过”；count后insert并发有唯一键但未转duplicate-key回执，不得保证所有未知/5xx都可读回确定成功。/club/members是成员名单，不是获奖名单。现有source_type=6/source_id=clubId已写入，但UI是否显示团名未验证，不承诺已展示；不报告尚未复现的“未知来源”。无撤回接口。
- POST /api/user/nickname/check、/suggest为表单；check返回nickname/available/errorCode，suggest返回候选/cursor或顶层errorCode。2–24码点及字符集沿服务；可用查询不预约，保存仍/api/user/update校验。无新请求身份/版本/分页或权限。

以上仅合同冻结，不等于PR23源码SOURCE_READY/TEST_DEPLOYED。已有后端私团隔离/创建审核/更强幂等是独立DRAFT审查项，先复现/具体方案，不能连带修改本批已锁核心或批准新政策。

## 原Claude CL3写者需修

1. badge页_unloaded/_loadCycle、昵称页_unloaded/_nicknameSeq已提供部分生命周期保护，但缺账号owner世代；账号变化后旧isOwner、成员、弹层、草稿及迟到响应可串入新账号。捕获owner、回调与提交前核对，换号清状态并重新读身份；后端仍校验当前账号，不把此问题说成服务端越权。
2. badge创建网络未知后立即creating=false，只提示/刷新而可再次提交；后端无幂等，可能重复建章。保留未决态，独立核对且用户明确确认后才解锁；无法唯一读回仍UNKNOWN。原测试只验提示/刷新，应验确实不能盲重提。
3. 刷新身份失败isOwner=false时create/member弹层仍显示旧数据；清理弹层与上传/成员选择，提交护栏保留。
4. nickname suggest在seq有效性前写_suggestCursor，失败分支缺seq检查；旧请求可污染新基名的cursor/error。先核seq/owner再写任何状态。已有文字不被旧建议覆盖的保护有效，不误报一定覆盖文字。
5. danger-actions“来源标注为本俱乐部”超出已验证展示，应改为写入事实或删该承诺；不声称玩家勋章墙已验。名称UTF-16/码点差异、长图标URL保持明确校验与恢复。

补验：A→登出→B及上传/晚响应；失权刷新弹层；创建超时且读回失败不解锁；并发grant冲突保留未知；来源显示不作未证承诺；长签名URL；旧suggest cursor/error不污染新输入。原两专属测试由只读worker分别独立进程git show内存加载，16/16及12/12通过；未跑全量/Java/DB/VPS/模拟器/设备。作者全量8329的跳过数是推算，不能替最终HEAD实测；作者CI不作本候选证据。

## 文件处置

OWNERSHIP按实际23文件精确登记15项业务/专属测试给原Claude CL3（8新+7既有），不是整个club/utils目录。八项共享文件保留C00，只接最小补丁；与PR20真实增量均交叉，原写者停止/补丁/基线尚未收到。以PR17 base8534daba、PR20 base8b7eb814核三点增量后，PR17与PR23仅action-ledger.test.js交叉，gerenziliao无真实增量冲突，撤回此前基于大父链diff的保守“资料重叠”判断。它们不在本候选已锁bug JS中，不需要夺B01写权。

PR23保持草稿/未集成。独立前端修正由原写者继续；新共享后端问题先具体提案，不启动第二套UI/业务后端。PR21的REQUESTED规划不代表已读本控制表。
