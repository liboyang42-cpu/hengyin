# pass-mini 小程序端通行证（2026-11-13，分支 fix/pass-mini-20261113）

售卖开关/配置保持关闭，仅业主可开。后端分支 fix/pass-backend-20261113 在本分支创建时尚不存在（远端无此分支），下列后端依赖按文档合同先在小程序端接好，均为加法式字段。

## 条目状态
| 条目 | 状态 | 证据 |
|---|---|---|
| T01 渠道 | 完成 | `subpackageMember/utils/platform-pass.js:41`（channelState：仅 ios/android + 设备支持 + 服务端可用才 XPAY，否则给原因，不退回 JSAPI）；`platform-pass-pay.js:19`（purchase 只认 XPAY，JSAPI 分支删除）；`market/membership/index.js:53`（wx.canIUse 取真实能力） |
| T09 购买页 | 完成 | `platform-pass.js:77` 只出月卡并默认选中、标签「30天·不自动续费」、按钮「开通/续期 ¥金额」；`:116` factRows 可玩主题/适用范围/有效期/退款规则（服务端 `terms`、`libraryCount`、`validDays`）；单主题票入口未动 |
| T09 订单接入 | 完成 | `market/passes/index.js` 我的订单读 `/api/platform-pass/orders` 并列出；新增最小页 `market/pass-order/index`（现有订单页都是活动/实体订单，无法承载）：待支付回查/继续支付（`platform-pass-pay.js:31` resume） |
| T09 状态与幂等 | 完成 | `platform-pass.js` orderStateText/refundText：待支付/已开通/已取消/退款中/已退款，读不到写「状态确认中」；`membership/index.js:86` 同一购买意图沿用同一 requestId；未确认显示「确认中」并点回同一订单；取消支付只提示，不动会员 |
| T06 玩家退款入口 | 完成（按文档合同） | `market/pass-order/index.js` onRefundSubmit：`POST /api/platform-pass/orders/{orderNo}/refund {reason, requestId}`，进度读订单 `refund.status`（REQUESTED/PROCESSING/APPROVED/REJECTED） |

## 对后端分支的依赖（需与后端对齐）
- `POST /api/platform-pass/orders/{orderNo}/refund`，body `{reason, requestId}`；订单视图加 `refund: {status}`，列表/详情加 `createdTime`（可选）。
- `GET /api/platform-pass` 加 `terms: {coverage?, seat?, period?, refund?}`（字符串）；`channels.xpay.supported`（可选，false 时不出购买）。
- 缺字段时页面不画该项，不编内容。

## 同步与检查
- 路由登记：app.json、`scripts/uiaudit/route-matrix.json` 已加 `market/pass-order/index`；动作台账 `--write` 通过（6043 项）。
- GET 权限矩阵：新增 2 个调用点（订单回查、订单详情）已入 MATRIX，直接 GET 128 → 130。
- 未改 ci/architecture/*、workflows；未跑 ci/xcx-check.sh 自检。
- 单测：`tests/unit/l-pass-platform-pass.test.js` 30 项通过。
