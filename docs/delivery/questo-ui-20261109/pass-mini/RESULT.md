# pass-mini 小程序端通行证（2026-11-13，分支 fix/pass-mini-20261113）

售卖开关/配置保持关闭，仅业主可开。后端分支 fix/pass-backend-20261113（PR #818）在本分支开工后才出现，已按其实际接口对齐退款入口。

## 条目状态
| 条目 | 状态 | 证据 |
|---|---|---|
| T01 渠道 | 完成 | `subpackageMember/utils/platform-pass.js:41`（channelState：仅 ios/android + 设备支持 + 服务端可用才 XPAY，否则给原因，不退回 JSAPI）；`platform-pass-pay.js:19`（purchase 只认 XPAY，JSAPI 分支删除）；`market/membership/index.js:53`（wx.canIUse 取真实能力） |
| T09 购买页 | 完成 | `platform-pass.js:77` 只出月卡并默认选中、标签「30天·不自动续费」、按钮「开通/续期 ¥金额」；`:116` factRows 可玩主题/适用范围/有效期/退款规则（服务端 `terms`、`libraryCount`、`validDays`）；单主题票入口未动 |
| T09 订单接入 | 完成 | `market/passes/index.js` 我的订单读 `/api/platform-pass/orders` 并列出；新增最小页 `market/pass-order/index`（现有订单页都是活动/实体订单，无法承载）：待支付回查/继续支付（`platform-pass-pay.js:31` resume） |
| T09 状态与幂等 | 完成 | `platform-pass.js` orderStateText/refundText：待支付/已开通/已取消/退款中/已退款，读不到写「状态确认中」；`membership/index.js:86` 同一购买意图沿用同一 requestId；未确认显示「确认中」并点回同一订单；取消支付只提示，不动会员 |
| T06 玩家退款入口 | 完成（按文档合同） | `market/pass-order/index.js` onRefundSubmit：`POST /api/platform-pass/orders/{orderNo}/refund-apply {reason}`（#818），进度读订单 `refund.reviewStatus`（PENDING/APPROVED/REJECTED/NONE） |

## 对后端分支的依赖（需与后端对齐）
- **#818 未合并前，CI「Fast gates / API 存在性」会红**：`/api/platform-pass/orders/{}/refund-apply` 只在 #818 里有 Mapping。不开豁免册（只减不增），#818 合入 master 后本分支合并 master 即绿。
- 后端 #818 的概览**没有** `terms`（适用范围、退款规则文案），小程序已按可选字段读取 `GET /api/platform-pass` 的 `terms: {seat?, refund?, period?}`，缺则不画；需要后端补（加法式）。`channels.xpay.supported` 同为可选。
- 退款条款正文由业主定（#818 只存引用 PASS-REFUND-V1）。

## 同步与检查
- 路由登记：app.json、`scripts/uiaudit/route-matrix.json` 已加 `market/pass-order/index`；动作台账 `--write` 通过（6043 项）。
- GET 权限矩阵：新增 2 个调用点（订单回查、订单详情）已入 MATRIX，直接 GET 128 → 130。
- 未改 ci/architecture/*、workflows；未跑 ci/xcx-check.sh 自检。
- 单测：`tests/unit/l-pass-platform-pass.test.js` 30 项通过；同步更新路由数 / 标题盘点 / 页面清理清单 / 按压反馈等计数类单测。

STATUS: PARTIAL - 退款入口依赖未合并的后端 PR #818（API 存在性闸在其合并前为红）；概览 terms 文案字段待后端补；真机/模拟器截图未做

## Shepherd 1113

- 2026-11-13 把 master（含 #818 通行证后端）合入 `fix/pass-mini-20261113`，无冲突、无历史改写。
- 与 #818 实际接口对齐核对：`POST /api/platform-pass/orders/{orderNo}/refund-apply`、订单 `refund.reviewStatus`、`libraryCount`、`validDays`、`channels.xpay.available/reason` 均与小程序读法一致；概览没有 `terms`，小程序本就按可选字段读取、缺则不画，无需改代码（退款规则正文仍待业主定稿后由后端加法式补）。
- 合入后唯一失败来源：动作台账漂移（`refund-apply` 现有服务端 Controller 来源），按文档工具 `build-action-ledger.js --write` 重新生成（6043 项）；未改 ci/architecture/*、workflows、闸脚本，未跑 xcx-check 自检。
- 本地复核：CWD 无关性、API 存在性（A=0）、silentError、ui-integrity、route-matrix --check、`l-pass-platform-pass` 单测 30 项均通过。
- CI（head `b9e99a402`，run 2399）：Fast gates、Mini program static gates、20 个单测分片、Secret scan 全部 success。该分支随后已由 lead 合入 master（#820）。

STATUS: DONE
