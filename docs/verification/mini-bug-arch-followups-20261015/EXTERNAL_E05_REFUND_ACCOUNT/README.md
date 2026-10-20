# E05 退款订阅与账号隔离行为回归

CY-HANDOFF: CODEX

任务：E05 / BK-XCX。基线：`1e403b135206844a34219753a36e2dee39c7dd59`。
工作树：`/workspace/hengyin-extra-e05`；分支：`codex/extra-e05-regression-20261015`。
控制材料：`/tmp/chengyin-extra-six-context/` 的 EXTERNAL_SIX_HANDOFF、OWNERSHIP、FOLLOWUP_BUG_LANES、BACKEND_CHANGES、COORDINATION（协调者指定控制快照 `b8541ad2407d0a360588ab77ad32fe3d1c9eb71e`）；根及小程序 AGENTS 已读。

交付状态：SOURCE_DELIVERED_UNVERIFIED。历史 Node 结果仅属于上述基线和当时测试文件，不是新 SHA 验收。按协调者转达的最新用户规则，后续不运行任何 Node/Java/Maven/数据库/DevTools/业务验证，也不等待或手动重跑 CI。

NO_API_CHANGE；TEST_DEPLOYED=NO。只新增本 README 与 `chengyinhub-xcx/tests/unit/b01-refund-account-regression.test.js`。没有修改业务源码、旧测试、控制账、依赖、CI、迁移或 UI；没有暂存、提交、push、部署。

## 已有覆盖与新增差异

| 已有文件 | 已有覆盖 | 本次新增差异 |
|---|---|---|
| `tests/unit/b01-lifecycle-account.test.js` | 三组件确认后订阅、SDK fail 不阻断退款；topic unlike 稳定 ID；收藏列表换号/卸载晚回调 | 不复制 SDK fail case。增加真实 helper 的 accept/reject/空模板集成、未结算订阅时退款已发出、换号后晚订阅无新写；三组件退款确认与回执的换号/卸载边界 |
| `tests/unit/subscribe.test.js` | helper 的解析、状态枚举、上限、未配置告警、同步 API 调用 | 通过三组件真实退款 handler 调用 helper，而非仅独立 helper 返回值 |
| `tests/unit/cancellation-feedback.test.js` | 取消回执文案及刷新 | 验证旧账号/已卸载组件不得消费回执产生新请求或宿主事件 |
| 既有收藏覆盖 | topic unlike 列表重排、旧 post unlike 成功不能删新账号列表 | 增加 post unlike 稳定 ID 重排；A complete 不得释放 B 同 ID 在途锁；A topic 确认框不得向 B 发请求 |

## 方法与执行证据

测试通过 `node:vm` 执行真实组件/Page 源码；真实 `utils/subscribe.js` 在独立 VM 装载。仅 modal、toast、loading、传输、平台及计时器边界受控。订阅 API 断言位于确认手势的同步调用栈；回调由测试明确推进，不依赖真实网络或时间。空模板仅在 VM 隔离的导出 TEMPLATES 中设为空，不改源码。记录 transport 发送时账号；没有把源码字符串匹配作为行为验证。

从仓库根执行的精确命令：

```sh
node --test chengyinhub-xcx/tests/unit/b01-refund-account-regression.test.js
```

2026-10-15 已实际执行两次，均在协调者要求暂停新验证之前完成。首次 24 项：13 通过、11 失败；其中3项为测试夹具把 participation 的 data.id 设为字符串而断言数字，随后将统一 synthetic fixture 设为数字7，未放宽业务断言。第二次是当前文件结果：**24 项，16 通过，8 失败，0 cancelled，0 skipped，0 todo，退出码1**；Node 报告 duration_ms=152.395867。收到暂停通知后不再执行测试。

| 生成的测试方法名 / 参数 | 期望数量 | 实际通过 / 失败 |
|---|---:|---:|
| `{detail,history,participation}: refund dispatch precedes late subscription {accept,reject,empty}` | 9 | 9 / 0 |
| `{detail,history,participation}: stale confirmation after {account,detach} cannot dispatch refund` | 6 | 2 / 4 |
| `{detail,history,participation}: late refund success after {account,detach} has no business effects` | 6 | 2 / 4 |
| `favorites: post unlike removes captured stable ID after rows reorder` | 1 | 1 / 0 |
| `favorites: A unlike completion cannot unlock B request for the same stable ID` | 1 | 1 / 0 |
| `favorites: topic confirmation opened by A cannot send after switching to B` | 1 | 1 / 0 |
| 合计 | 24 | 16 / 8 |

## 确定性缺陷：交 C00 / 原写者修复

失败断言全部保留，没有 skip/todo，也没有改业务源码使测试变绿。detail 对照组7项全通过；history、participation 各4项失败。

### E05-1：旧确认可向新账号发送退款

精确源码：

- `chengyinhub-xcx/subpackageMember/components/cy/scene-member-order-history/index.js:233`，`cancelOrder`，确认 success 在247行。
- `chengyinhub-xcx/subpackageMember/components/scene-member-participation-detail/index.js:420`，`cancelParticipation`，确认 success 在427行。

最小复现：账号11点击订单7取消→保留确认框→账号切22（或执行 lifetimes.detached）→调用旧确认框 success({confirm:true})。期望订阅与退款均0次，实际各1次；换号场景的退款通过当前 app transport、在账号22下发送旧订单7。仅证明客户端错误调用，不声称后端已接受跨账号订单。

失败 case：`history/participation: stale confirmation after account/detach cannot dispatch refund`，测试文件91行，95行断言实际1、期望0。

最小修复建议：由C00安排原写者在这两组件捕获发起确认时 member + 生命周期 generation；确认回调首先验证二者仍当前，detached 使 generation 失效。只在当前确认手势内发订阅和退款，保留现有接口/付费判断/服务端权限。

### E05-2：旧退款成功回执污染新账号或已卸载宿主

精确源码：

- `chengyinhub-xcx/subpackageMember/components/cy/scene-member-order-history/index.js:259`，`cancelOrderRequest`，success 在270行。
- `chengyinhub-xcx/subpackageMember/components/scene-member-participation-detail/index.js:437`，`cancelRequest`，success 在445行。

最小复现：账号11确认退款→保存请求回调→账号切22或 detached→旧请求 success({code:200,...})。history 额外发送 `/api/registration/list`（请求数2，期望保持1），并产生 toast / page_no 更新；participation 产生 toast 与宿主 `refresh`、`back`（期望空 effects）。这与旧确认导致新写请求是两个独立窗口。

失败 case：`history/participation: late refund success after account/detach has no business effects`，测试文件98行，104/105行断言。

最小修复建议：请求捕获 member + generation；success/fail 的 toast、setData、getList、triggerEvent 前校验当前身份/实例。请求所属 loading/锁仍按请求身份释放，不能因为失效阻止清理，也不能让旧 complete 释放新请求锁。可参考 detail 的 `cancelRegistration` 既有防护，但需由原写者按该组件生命周期接入。此次未替原写者修改任何源码。

## 明确未验证

未执行 Java/javac/Maven、MySQL/Redis、服务或 HTTP、生产/VPS/SSH/网络操作；未做真实订阅授权、微信开发者工具/真机、真实账号退款读回、支付渠道、iOS/Flutter、合并/部署/上传。Node fixture 证明客户端行为，不代表退款到账或后端越权成功。无 UI 变更，页面截图要求不适用。L01 play/player-game-module、L05 publish、Claude UI 均未改动，也未运行其测试。冻结候选231项签收不用于本次24项结论。

## 仅供协调者排队的后续候选（未执行）

- history `index.js:281` 的退款 fail 无 owner/generation guard；participation `index.js:457` 的退款 fail 同样直接 toast。触发：A 请求后换号/卸载，再失败回调；当前24项只覆盖旧 success，未独立断言旧 fail 的业务副作用。与 E05-2 同修复边界，不另造产品。
- history `index.js:107` detached 只销毁 workflow；`getList` 的 isCurrent（141行）只判 requestId/member，不判 disposed。触发：同账号列表在途时组件 detached，再返回旧列表。现有收藏列表卸载覆盖不能替代订单 history 列表，当前24项也未直接覆盖列表响应。需 C00 先复核/派原写者，不能据此直接宣称已运行失败。
- history `index.js:285` 起 complete 无请求身份判定，直接解除 `_cancelRequestInFlight` 并 setData。触发：A退款在途→换账号/实例状态接管→旧 complete；需结合原写者修复后的请求身份模型安排回归。当前24项检验收藏同 ID complete 隔离，未覆盖订单 complete。
