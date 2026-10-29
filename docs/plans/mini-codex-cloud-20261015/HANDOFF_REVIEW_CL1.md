# CL1交付审阅与精确处置

CY-HANDOFF: CODEX；协议CY-COORD-20261015-v1。2026-10-15由唯一协调者C00发布，审阅worker显式gpt-6.1-sol、只读结束。已主动读取PR21 `c6bc2200daefec1f31c00962ab494b0e1b504c00` 的CY-HANDOFF及全部两份文档、PR22 `64b485a4564725f490f6620f136e8879bbe09289` 18文件diff。PR22尚无交付标记/完整验证正文；本记录不是对方已收到的证明，接收回执PENDING。

## 基线和合同

PR22与PR21当前HEAD共同祖先d9dc65a，并非以最新c6bc220或Codex候选69c281af为基线；两点diff可能带回TASKS旧文档，不能当合入补丁。使用共同祖先三点diff的18个文件审阅。

NO_API_CHANGE：仅消费既有GET /api/preset/route/info，在当前漫游叠本机路线、站点针和距离估算，不新开服务端路线玩法，不产生权威完成/打卡/奖励，不修改DTO、权限、分页、requestId或version。进度不能代替服务端会话事实。地图辅助合同CONTRACT_FROZEN，当前实现需下列修复，尚非SOURCE_READY，更无TEST_DEPLOYED。十个专属Node测试由只读worker以git show内存加载实际10/10；未做全套、Java/DB、开发者工具或设备。

## 由Claude CL1原写者修正

1. utils/roam-follow-route.js consumeHandoff在removeStorageSync失败后继续返回，TTL内可重复消费重置进度；删除失败应不消费或具备实际幂等标记。保留10项并新增失败存储case。
2. 有owner的交接必须严格匹配当前owner；A写入后登出当前为空仍被消费违反隔离。匿名→首次登录可以单列明确合同，A→空/B不得放行。
3. 交接消费后仅保留page._followRoute，不进入既有恢复记录；进程回收恢复漫游会丢路线。可以明确“仅当前页面生命周期”并提示，若做恢复须原恢复文件所有者交补丁，不能静默声称已支持跨进程。
4. official-route/index.js switchTab失败应提示并撤销交接或明确保留重试，不使稍后普通开漫游意外消费残留。
5. 详情load seq未在卸载/账号世代失效；新增startFollow会交付旧plan，补卸载/换号/重载失败晚响应case。保持主漫游既有generation/owner校验，不回退。
6. HUD“仅本机估算，不计入打卡”必须始终可见，不能因distText为空在完成态消失。缺坐标/最多30站截断后不能称整条官方路线完成；终态用“可定位站点均已接近”等准确含义，区分缺坐标和超上限。
7. 已在某站附近加叠层但静止时尚未advance，应纳入首次渲染体验验收；属于辅助提示，不改变后端到点门禁。

最小验收：保留原10项，增加上述存储失败、A→登出→B/匿名→登录、switchTab失败、卸载/换号晚响应、同局hide/show不归零、跨进程明确降级或恢复、退出跟随/结算不改轨迹及会话/未决请求身份、缺坐标/31站以上/末站始终本机估算说明。实际定位和组件四态仍须开发者工具/真机，Node不代替。

## 文件、冲突及交接

OWNERSHIP新增七个实际独立新文件给Claude CL1/PR22，详情页四文件仍同一Claude CL1所有者；不把广泛通配申请当批准。以下七个为统筹最小补丁，当前不得整包合入：

- pages/roam/index.js、index.json、index.wxml（均chengyinhub-xcx前缀）：其中JS/WXML与PR20未停止原写者冲突，必须取得停止+补丁+新基线后C00接线，不因PR22已写就夺权。
- scripts/uiaudit/action-ledger.json、tests/lint/ui-integrity-baseline.js、tests/unit/action-ledger.test.js、tests/unit/roam-features-task3.test.js（同前缀）：C00统筹；PR22基于旧4480/1796生成，本候选已因真实退款授权调整local tap至1792，不能覆盖。最终按双方源码重建并逐ID审查，不只改数字消绿。

已读PR21新文档的REQUESTED清单。CL2新report-*、CL3 badges、CL4主办方面板及专属测试仍缺实际精确名，需在其交付PR补齐后登记；无冲突已领设计继续。PR21 README把积分写成“分页+四态”，TASKS已写全量，应同步README为POST /api/points/list一次全量；根权限、发布状态及iOS独立分工用最新控制协议，不能沿旧文档“均CI绿”推断当前候选/设备验收。

处置结果：PR22保持草稿/未集成，独立修复由原Claude CL1继续，不启动第二个Codex UI实现。等待实际原写者交付与接收回执，其余bug/架构CI继续。

校准：主漫游onShow本身有owner变化清理，PR22已插exit，应承认正常A→B清理与定位generation保护有效；上述消费helper有owner→空owner问题仍需修，不能据此断言正常onShow下A→B一定残留。PR22 a5cb35ed只改事件合同计数，不是七项审阅修复的交付。
