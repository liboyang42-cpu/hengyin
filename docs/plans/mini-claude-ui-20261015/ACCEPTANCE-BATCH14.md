# 第十四批本地验收清单（BL-148–156，BL-157）

> 只写文档。第十四批全部是「只加 `tests/unit/*`、不改被测代码」的单测卡，**不需要上设备**，验收 = 在依赖 PR 合入后的树上跑命令，对照期望的 pass / fail / todo。
> 快照 2026-10-16 02:30Z，PR 状态以 GitHub 为准。每个分支都从被测 PR 切出，合入前 diff 会带上被测 PR 的提交，只需审最后一个提交。
> 统一前置：`cd chengyinhub-xcx`。

## 一、逐卡验收命令

| 卡 | PR | 须在其后合并 | 命令（`node --test …`） | 期望 pass / fail / todo | todo 暴露的缺陷（未改代码） |
|---|---|---|---|---|---|
| BL-148 俱乐部勋章 | #192 | #23 CL3 | `tests/unit/club-badge-utils.test.js` | 10 / 0 / 1 | `grantReceipt(null\|{})` 返回成功文案，纯函数不区分结果未知（页面有 `isSuccess` 守卫，线上不受影响） |
| BL-149 昵称校验 | #193 | #23 CL3 | `tests/unit/nickname-check-utils.test.js` | 10 / 0 / 0 | —（口径用例记录：首尾全角空格 U+3000 前端 trim 掉、Java `trim()` 不去，提示层差异） |
| BL-150 举报详情视图 | #195 | #25 CL2 | `tests/unit/report-common-view-utils.test.js` | 8 / 0 / 0 | — |
| BL-151 NPC 导出时间与积分规则值 | #197 | #27 CL4 | `tests/unit/npc-export-time-points-value-bl151.test.js` | 9 / 0 / 0 | —（现状记录：秒级时间戳会按毫秒显示为 1970 年） |
| BL-152 漫游商家访客来源键 | #196 | #113 BL-70 | `tests/unit/roam-shop-visitors-sourcekey.test.js` | 6 / 0 / 0 | — |
| BL-153（1/2）核销记录 shapeRecord | #198 | #119 BL-76 | `tests/unit/merchant-verify-record-shape.test.js` | 6 / 0 / 0 | — |
| BL-153（2/2）触达任务 shapeCampaign | #199 | #144 BL-100 | `tests/unit/merchant-crm-campaign-shape.test.js` | 5 / 0 / 0 | — |
| BL-154 私信解除拉黑回读 | #200 | #104 BL-61 | `tests/unit/im-unblock-readback-bl154.test.js` | 5 / 0 / 1 | `_confirmUnblock` 在 `/api/im/unblock` 200 后、回读前就 `dc.done()`「已解除拉黑」（已报 #104） |
| BL-154（重复认领） | #201 | #104 BL-61 | `tests/unit/im-unblock-confirm-handler.test.js` | 7 / 0 / 3 | 同上 + 断网时文案应为「结果未确认」并回读 |
| BL-155 前置检查跳转白名单 | #202 | #128 BL-85 | `tests/unit/merchant-prerequisites-whitelist.test.js` | 7 / 0 / 0 | — |
| BL-156 另存为模板结果未知 | #203 | #164 BL-120 | `tests/unit/myproject-save-as-template-unknown-bl156.test.js` | 8 / 0 / 0 | —（已有按 id 在途守卫，无需 todo） |

**一次跑完**（全部依赖 PR 合入后）：

```sh
node --test tests/unit/club-badge-utils.test.js tests/unit/nickname-check-utils.test.js \
  tests/unit/report-common-view-utils.test.js tests/unit/npc-export-time-points-value-bl151.test.js \
  tests/unit/roam-shop-visitors-sourcekey.test.js tests/unit/merchant-verify-record-shape.test.js \
  tests/unit/merchant-crm-campaign-shape.test.js tests/unit/im-unblock-readback-bl154.test.js \
  tests/unit/im-unblock-confirm-handler.test.js tests/unit/merchant-prerequisites-whitelist.test.js \
  tests/unit/myproject-save-as-template-unknown-bl156.test.js
```

期望合计 pass 81 / fail 0 / todo 5（若只保留 #200、#201 之一，见第三节）。另跑一次全量 `node --test "tests/unit/**/*.test.js"` 确认没有新增失败。

## 二、todo 转正条件

| todo | 所在 PR | 被测 PR 怎么改后去掉 `todo` |
|---|---|---|
| `grantReceipt` 空响应当成功 | #192 | #23 让空/非对象响应返回 `{ unknown: true }` 或去掉成功兜底文案 |
| 解除拉黑回读前提示成功 | #200、#201 | #104 把 `dc.done()` 挪到 `loadMessages` 回读确认 `blockedByMe === false` 之后 |
| 解除拉黑断网文案 | #201 | #104 `fail()` 改为「结果未确认」并触发一次回读 |

## 三、需要协调者处理

- **BL-154 重复认领**：#200（L2，认领提交 02:12:57Z）与 #201（L1，02:13:00Z）同时开了 PR，测试文件不同名、可以共存，不冲突；#201 覆盖更多（含会话切换迟到回包、断网文案 todo）。建议保留 #201、关闭 #200，或两者都合（多 5 个用例，无重复断言之外的副作用）。
- **BL-153 重复分支**：`claude/bl-153-verify-records-shape-tests`（L2，无 PR）与 #198 同卡，晚 2 秒认领，已让出；该分支删不掉（推送代理拒绝删除远端分支），请协调者删除。
- `ACCEPTANCE-INDEX.md`（BL-138，#182）不在基线上，本卡不改它；需要在其 §3「纯测试」表追加的行见本 PR 正文的补丁。

## 四、未验证

- 本文档的期望值照抄各 PR 正文（#200、#203、#197 为本线实测）；其余 PR 未在本线复跑。
- 没跑 CI、全量单测、`ci/xcx-check.sh`。
