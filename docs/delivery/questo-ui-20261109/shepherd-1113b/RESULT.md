# shepherd-1113b：#820 / #821 合入 master 并转绿（2026-11-13）

两个 PR 均未由本次任务合并；#820 在本次任务中途已由 lead 合入 master。

| PR | 分支 | 代码 head（CI 已验） | 检查 |
|---|---|---|---|
| #820 通行证小程序（只走虚拟支付） | `fix/pass-mini-20261113` | `b9e99a402`（run 2399） | 全部 success：Fast gates、Mini program static gates、20 个单测分片、Secret scan。已合入 master（`07a95600f`）。 |
| #821 自玩票虚拟支付（默认关闭） | `fix/selfplay-xpay-20261113` | `514a78957`（run 2407） | 全部 success：Backend tests、Fast gates、Mini program static gates、20 个单测分片、Migration gates、Secret scan。随后仅追加 RESULT 文档提交 `f028c1db7`。 |

## #820
- 合入 master（含 #818），无冲突。接口核对：`refund-apply`、`refund.reviewStatus`、`libraryCount`、`validDays`、`channels.xpay.available` 与后端一致；概览无 `terms`，小程序按可选字段读取，不改代码。
- 唯一需处理项：动作台账漂移，`build-action-ledger.js --write` 重生成。

## #821
- 两次合入 master（#819；#820/#822），保留两条功能。生成物：动作台账重生成；`ui-integrity-baseline.js` 取 master 侧后重锚 topic 页 U4-DYNAMIC（2125→2148→2146，仅因本线自身增行位移），`keyDigest` 取门禁 `baselineKeyDigest()` 的值，附带日期注释；不新增登记、不上调棘轮。`ci/migration-dependencies.json` 两条迁移声明并存。
- 真实失败（本 PR 造成，已修）：#818 改了 `xpayClientParams` 签名致后端编译失败（冻结行补 offerId）；订单列表 `payOrder` 的两次写需在台账声明。
- 另一会话同时推送了相同修复，已取其提交、弃自己的重复提交，未强推。

未改 ci/architecture/*、workflows、闸脚本；未跑 xcx-check 自检。

STATUS: DONE
