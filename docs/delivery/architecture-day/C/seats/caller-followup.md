# 核销第四入口与复用页面代次收口

本批是 `f1cfe74d58b766e385e639d40385e208790644a8` 的独立增量；工作目录 `/workspace/hengyin-c-seat-verification`，分支 `codex/architecture-day-c-seat-verification`。只修改两个实际小程序生产调用者、两个专属测试和本目录证据，不改后端、A/G资金域、CI、台账或baseline。

第一批 `frontend.md` 的通用扫码清单漏记了第四个入口 `pages/club/detail/index.js.onClubScan → submitClubScan`；本批按真实调用修复，没有以补清单代替代码。

## 三处有执行证据的遗漏

1. 俱乐部核销 key 仍包含二维码 nonce。执行原 adapter + 既有真实持久 workflow 的 Node 反例：首次席位写 UNKNOWN、销毁、刷新 nonce 后重建，期望 `/api/verify/seat/receipt`，实际 `/api/registration/scan_dynamic_code`。修改前该定点实际 1 fail/0 skip。现俱乐部入口复用原 `seatTarget/seatKey/seatRecovery`，不造第二个恢复机制；主题席位继续要求带队场次，活动席位保留原动态码端点。登录代次、页面代次、clubId 在相机打开前和异步回包处冻结；切俱乐部递增原代次、终止内存任务，磁盘 UNKNOWN 保留。原非席位流程保留，loading 按本操作所有权清理，未申请 loading 的席位分支不能清理别人的显示。
2. `scene-member-participation-detail._applyRecordId` 复用组件切换记录时只更新取消报名代次，核销代次不变。实际执行该方法 + 原 `goScanQR`：71→72→71 后原相机回调仍进入提交（实际 `['sent']`，期待 `[]`），修改前定点 1 fail/0 skip。现合法记录切换递增原 `_detailGeneration`、销毁原核销内存 workflow 并清理本页原 loading 所有权；不改取消/退款算法。新增场景同时证明旧回包不能清新记录 pending，返回原记录只读原席位 receipt。
3. 俱乐部 `submitClubScan` 强制 `jsonHeader()`，其实际定义是 `Content-Type: application/json`。该入口可达的 `scan_dynamic_code(String code)`、`scan_qr_code(String type,String code,Long activityId)`、`redeemGroupCode(String code)`、`couponVerification(String code,String token,String scope)` 都是普通表单参数，不是 `@RequestBody`。仅删除此调用处的 JSON 头，沿用原 request-client 的 POST urlencoded 默认值和 object data，不改变后端接口。四类请求执行真实 request-client 检查最终 wire header/data；另精确恢复原 JSON 头的源码负控实际得到 `application/json` 并被表单合同拒绝。初版测试夹具把 jsonHeader 桩为空对象，本批同步改为生产实际 JSON 头，原业务断言不删改。

三项均不产生新席位费/退款政策，不乘 order_num、不把整单扫码视作全员履约。后端首席位原订单副作用和后续仅席位事实的行为未改。

## 验证与边界

本批当前源码实际定点 **10 文件 390/390 PASS，0 fail/skip**；逐文件结果在 `caller-followup-node-results.json`。独立进程命令为 `node --test --test-isolation=none --test-reporter=tap <file>`。其中 adapter suite 原 248 条保留、增加俱乐部四场景及五条编码/负控至257；caller suite 原7条保留，新增登录/俱乐部/记录代次与在途 UNKNOWN 至11。其余含原俱乐部27、晚到ID3、参与真值11、取消反馈30、退款账号18、席位工具23、商家节点8、旧回读2。四个变更 JS 语法检查及 diff whitespace 检查通过。

未执行 Java/MySQL/HTTP/JWT/真机/DevTools/CI，不把 Node wire/VM 证据当服务端授权或真库验收。`run40077180059` 仍按 root 反馈记录为 Admin 编译阻断、业务0/9、CLEAN；其 helper 编译修复由 root 保留，C 未重复修改。

已只读核对 root 公共 `7da3e4ea385596aa850dc760f2cf6adc6c554602`：它尚未包含 f1 的参与详情接线。本增量必须在 f1 之后取差异，不可跳过依赖或用整份旧树覆盖 root。root 后续按最终组合源重建 action ledger/精确 UI 基线，统一安排后端验证；本批不并写这些共享资产。

剩余检查：活动扫码 `resolveScanContext` 的当前支付/时间前置闸在 `redeemActivitySeat` 的原回执重放之前；专用 seat/receipt 已能只读原事实。下一片需核对该 HTTP 顺序与原授权/类型规则的一致性，不能直接绕过前置闸或把购票人可读回执权限当作核销权限。本批没有扩大读取或核销授权。
