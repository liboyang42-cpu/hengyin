# BL-166 安全感受半屏 U5 逐项理由补丁（交协调者）

- 对象：#132（BL-89）`chengyinhub-xcx/pages/play/merchant/components/safety-rating-sheet/index.js:115`
  （`resolveMerchantId` 里 `/api/merchant/public-home` 的 `silentError: true`）。
- 错误去向（已逐行回读）：`success` 非 200 / 无 id 与 `fail` 都走 `done(null, msg)` →
  `submit()` 里 `fail(message)` → `setData({ submitting: false, submitError })`，半屏内可见并可再点提交。
- `tests/lint/ui-integrity-baseline.js` 归协调者，本分支**不提交该文件**；下方是在 #132 分支上 `git apply` 的最小补丁：
  `u5Silent` 加一条理由、棘轮 28 → 29（`ui-integrity.js` 冻结上限 `NON_INCREASING_CEILING.u5Silent` 为 29，未改门禁）、
  `keyDigest.u5Silent` 按门禁同一算法（sha256(sorted keys joined by \n)）重算。

## 本地验证（#132 分支临时 worktree）

| | `node tests/lint/ui-integrity.js` |
|---|---|
| 补丁前 | `UI-GATE-0:FAIL (1 violations)` · `[U5] …/safety-rating-sheet/index.js:115 silentError:true 调用点未登记允许静默的理由` |
| 补丁后 | `U5 PASS:需逐项理由 29,…,逐项理由 29` · `UI-GATE-0:PASS` |

> 注意：补丁后 u5Silent 棘轮 = 冻结上限 29。若其他 open PR 也要新增 U5 逐项理由，会触发「超过冻结上限」，需协调者按最终合并树统一裁定；digest 也须按最终树 key 集合重算。

## 补丁

```diff
diff --git a/chengyinhub-xcx/tests/lint/ui-integrity-baseline.js b/chengyinhub-xcx/tests/lint/ui-integrity-baseline.js
index e85dfc28..320a25e9 100644
--- a/chengyinhub-xcx/tests/lint/ui-integrity-baseline.js
+++ b/chengyinhub-xcx/tests/lint/ui-integrity-baseline.js
@@ -258,7 +258,8 @@ module.exports = {
     // 2026-09-17 同批:接收方匿名 GET 改为单独字面量调用(GET 权限矩阵只认字面量 sendRequest),同文件再 +1,24 → 25(冻结上限 25 不变)。
     // 2026-09-17 release-0917 第三阶段 C:总控裁定营销同意入口附属读取静默(唯一允许的 U5 放宽),25 → 26。
     // 2026-09-19 定价页接合作方入口(批复1-a=2):loadLineupNames 补名读取新增一条 u5Silent 登记,26 → 27。
-    "u5Silent": 28,
+    // 2026-10-16 BL-166:BL-89 安全感受半屏商家 id 解析静默(失败落半屏 submitError),28 → 29(冻结上限 29 不变)。
+    "u5Silent": 29,
     "keyDigest": {
       // 2026-09-24 卡包底图:play/index.js data 里加 topicCover 一行,u5Silent 那条已完成里程碑读取 5444→5445,
       //   新旧行内容一致、理由一字未改,u5Silent digest 按同算法显式重算(旧值复算吻合)。
@@ -960,7 +961,8 @@ module.exports = {
       // 2026-09-21 端到端收口合入上传重试与账号隔离，既有调用仅位移，理由与数量不变。
       // 2026-09-25 CU-M-183/185/186 同批:merchantinfo.js 那条 info-to-user silentError 调用点 2530→2532,
       //   逐条回读仍是「只为读章节 recruitEnabled、失败不弹全局 toast」这一处,静默理由一字未改,条数 27 不变。
-"6da2f9eac8f63031130c4ba17e260d411a1bc80daa5d9f961e83bf211915d93c"
+      // 2026-10-16 BL-166:新增 safety-rating-sheet/index.js:115 一条,按门禁同一算法重算。
+"6b074121490127df2735f082ac43124cb834037220d4376df337eaad48677665"
 
     }
   },
@@ -1316,6 +1318,7 @@ module.exports = {
   // 2026-09-08 二次合 master(#1047/#1048)后再次实测:play/index.js 的 my-completed 调用点
   //   落在 4945(master 侧记 4277、本分支侧记 4968,两侧对合并树都不对)。理由一字未改,digest 重算。
   "u5Silent": {
+    "chengyinhub-xcx/pages/play/merchant/components/safety-rating-sheet/index.js:115": "BL-89 安全感受半屏提交前按 memberId 解析商家 id(/api/merchant/public-home):失败经 done(null, msg) 落半屏 submitError(cy-inline-error,提交按钮恢复可再点),关通道 toast 避免与半屏内错误双弹;owner=小程序,复核=2026-12-31",
     "chengyinhub-xcx/pages/roam/index.js:5406": "漫游入口地图活动层随地图中心后台刷新:失败汇总到 entryLoadError 并提供重新加载,关闭通用 toast 避免拖图时与页内错误态重复报错;owner=小程序,复核=2026-12-31",
     "chengyinhub-xcx/pages/roam/index.js:5413": "漫游入口地图队伍层随地图中心后台刷新:失败汇总到 entryLoadError 并提供重新加载,关闭通用 toast 避免拖图时与页内错误态重复报错;owner=小程序,复核=2026-12-31",
   // (本支) "chengyinhub-xcx/pages/activity/list/index.js:105": "公共活动列表读取:失败落 loadError/errorMsg,由页面 cy-error 呈现并给重试,关掉自动 toast 是避免双弹;owner=小程序,复核=2026-12-31",
```
