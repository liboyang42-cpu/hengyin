# F13 小程序结束前确认写入（2026-09-13）

范围：roam 页面 JS/WXML、roam-finish-write-order 新增真实 handler 测试，以及受防重复提交/错误提示影响的两项既有测试调整。未改后端、订单规则、门禁生成物。

## 根因与修复

后端结束锁已正确拒绝结束后的 reveal/discover/shop 写入；前端原 `_postRoamFinish` 仅发起 `_flushReveal` 就立即 finish，可能把最后一批格子挡在会话之外。现在复用 reveal Promise 等待在途请求，继续排空最多 200 格/批（含不足 50 格尾批）后才发 finish；失败保留队列并明确“暂未结算”，可人工重试。结算请求共享 Promise 防重复点击。bootstrap HTTP 异常及成功壳缺少有效 sid 沿 unknown 合同停止重放，不伪造 sid、不清队列。

地点 discover 在途结束会等真实 handler 更新完成；失败留在地图保留原地点卡和人工重试入口，不自动重放可选奖励。HTTP unknown 保留原不可盲重试提示，重新进入核对仍是既有边界，不声称自动恢复。探店原有 visit.active 已阻止结束，失败重试/成功后玩家完成探店流程均保留，不自动代玩家结束探店。会话代际隔离 reveal/discover/finish 旧回执。

## RED → GREEN

- `/tmp/cy-f13-client-red.log`：旧真实 handler 2/2 RED，确认 finish 抢先发出。
- `/tmp/cy-f13-client-bootstrap-red.log`：成功壳缺 sid 的负控 1 RED（10 PASS）。
- `/tmp/cy-f13-client-green.log`：11 项新测试 + 距离、请求恢复、庆祝反馈相关，共 49/49 PASS。
- `/tmp/cy-f13-client-roam-green.log`：roam-* 共 252/252 PASS。

覆盖在途/尾批、失败留队重试、空队列正常结算、重复点击、bootstrap unknown/缺 sid、旧会话回执、地点成功/失败/unknown、探店原有阻挡与人工重试。距离重试既有测试改为真正结束首次失败回执后再重试，避免通过篡改 UI 状态模拟仍在途请求已失败。

## 验证边界

以上为本机 Node 真实页面 handler 与可控网络回执；未接生产、实际微信渠道、真机 GPS 或端到端真实 MySQL。WXML 仅沿用 cy-error 增补准确文案，截图/开发者工具 UI、action-ledger 与 UI baseline 由总控统一验证；本任务未更新或伪造这些观测。未 commit。

## 总控集成收尾

Standards / Spec 两轴复核均0项。原两处长toast压缩为“暂未结算，请先处理地点卡”，保留状态和处理动作。UI门禁原26项仅12组旧/新行号及2处长文案；4条U4与8条U5调用前后7行逐项完全相同，理由及54/25棘轮不变，只迁移key/digest。两轴再次核验0项。`/tmp/cy-f13-ui-green.log`：UI-GATE-0 PASS，Legacy error/warn均0。未把门禁通过当作真机UI验证。

## 总控原生页面补验

`/tmp/f13-ui-probe.js`和原`/tmp/f13-ui-controlled.js`在原9763实例复跑，三状态截图均已实际查看：reveal失败显示说明与重新结算，bootstrap未知不显示重试，成功显示25探索值。此前黑屏暂未复现，原因未证实，没有据此修改布局。

`/tmp/f13-ui-tap.log`记录真实cy-error子节点tap→retry事件→页面handler：保留失败格子后手动重试，reveal成功再finish、清队列。未知响应无重试节点，调用retry也不重发。请求已恢复并断开。均是受控网络DevTools验证，不是实际HTTP、真机GPS或生产证明。
