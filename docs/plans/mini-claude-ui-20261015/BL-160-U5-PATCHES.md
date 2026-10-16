# BL-160 U5 白名单同步补丁（按 PR 分节）

来源：#194 `COORDINATOR-PATCHES-CHECK.md` X-5。`ui-validity-gates` U5 读的是 `chengyinhub-xcx/scripts/ui-silent-error-allowlist.js`，
`ci/silent-error-gate.js` 读的是 `ci/silent-error-allowlist.txt`；下面 6 个 PR 至少少登记了其中一份。
每节是在该 PR 分支上直接 `git apply` 的最小补丁（理由按该 PR 新增 silentError 调用的实际错误承接写，不是套话）。

| PR | 分支 | 改前 U5 | 改后 U5 | 改后 silent-error-gate |
|---|---|---|---|---|
| #122 | bl-79-object-card-rename | FAIL 未进白名单 object-cards/index/index.js(1) | 通过 | 通过 |
| #123 | bl-80-broadcast-stats | FAIL official-mine 白名单 1 实际 2 | 通过 | 通过 |
| #128 | bl-85-merchant-prereq | FAIL merchant/index 白名单 2 实际 3 | 通过 | 通过 |
| #141 | bl-98-registration-trend | FAIL club/topic-detail 白名单 6 实际 7（两份都未登记） | 通过 | 通过 |
| #114 | bl-71-player-code | FAIL 未进白名单 scene-share-invite(1)（两份都未登记） | 通过 | 通过 |
| #73 | bl-32-report-member | FAIL 未进白名单 report-sheet(2) | 通过 | 通过 |

复跑：在各分支 worktree 里 `cd chengyinhub-xcx && node scripts/ui-validity-gates.js | grep ^U5`；仓库根 `node ci/silent-error-gate.js`。

> 合并时注意：多个补丁都在 `ui-silent-error-allowlist.js` 末尾追加，或改同一行计数（#123 与其他改 official-mine 的 PR），按最终合并树取**累计**计数，保留两侧行。

#### #122（`claude/bl-79-object-card-rename`）
```diff
diff --git a/chengyinhub-xcx/scripts/ui-silent-error-allowlist.js b/chengyinhub-xcx/scripts/ui-silent-error-allowlist.js
--- a/chengyinhub-xcx/scripts/ui-silent-error-allowlist.js
+++ b/chengyinhub-xcx/scripts/ui-silent-error-allowlist.js
@@ -109,0 +110 @@ module.exports = [
+  { file: 'subpackageP3/pages/object-cards/index/index.js', count: 1, reason: 'BL-79 改名失败(后端原文/网络)回显在改名半屏 cy-field 错误位,关全局 toast 避免双弹。' },
```
#### #123（`claude/bl-80-broadcast-stats`）
```diff
diff --git a/chengyinhub-xcx/scripts/ui-silent-error-allowlist.js b/chengyinhub-xcx/scripts/ui-silent-error-allowlist.js
--- a/chengyinhub-xcx/scripts/ui-silent-error-allowlist.js
+++ b/chengyinhub-xcx/scripts/ui-silent-error-allowlist.js
@@ -29 +29 @@ module.exports = [
-  { file: 'pages/activity/official-mine/index.js', count: 1, reason: '我的发布页把失败映射为页面可见错误态。' },
+  { file: 'pages/activity/official-mine/index.js', count: 2, reason: '我的发布页把失败映射为页面可见错误态。 BL-80 通知复盘读取失败如实写在该卡触达行并可再点重试,关全局 toast 避免双弹(+1)。' },
```
#### #128（`claude/bl-85-merchant-prereq`）
```diff
diff --git a/chengyinhub-xcx/scripts/ui-silent-error-allowlist.js b/chengyinhub-xcx/scripts/ui-silent-error-allowlist.js
--- a/chengyinhub-xcx/scripts/ui-silent-error-allowlist.js
+++ b/chengyinhub-xcx/scripts/ui-silent-error-allowlist.js
@@ -56 +56 @@ module.exports = [
-  { file: 'pages/merchant/index/index.js', count: 2, reason: '商家首页身份聚合请求失败落 consoleState=error 和 consoleError，不伪装成空态。2026-09-17 拍板 #24 入驻申请状态回读新增一处:失败同样落 consoleError + 「重新加载」页内承接,通用 toast 会双弹;申请行读到了才渲染状态卡,读不到不编状态。' },
+  { file: 'pages/merchant/index/index.js', count: 3, reason: '商家首页身份聚合请求失败落 consoleState=error 和 consoleError，不伪装成空态。2026-09-17 拍板 #24 入驻申请状态回读新增一处:失败同样落 consoleError + 「重新加载」页内承接,通用 toast 会双弹;申请行读到了才渲染状态卡,读不到不编状态。 2026-10-15 BL-85 开通前置检查是只读辅助区块:读取失败就不展示,不挡工作台其他区块,也不弹 toast(+1)。' },
```
#### #141（`claude/bl-98-registration-trend`）
```diff
diff --git a/chengyinhub-xcx/scripts/ui-silent-error-allowlist.js b/chengyinhub-xcx/scripts/ui-silent-error-allowlist.js
--- a/chengyinhub-xcx/scripts/ui-silent-error-allowlist.js
+++ b/chengyinhub-xcx/scripts/ui-silent-error-allowlist.js
@@ -37 +37 @@ module.exports = [
-  { file: 'pages/club/topic-detail/index.js', count: 6, reason: '「商家与成员」两个弹层各自持有可见错误态(各带 cy-error 重试,互不污染),主题设置取数失败落 cy-error,保存失败落精确 toast 并把开关回滚到提交前的值;结束主题的失败落 J5 确认框自己的 errorText,后端拒绝文案原样回显,框不关、按钮可再点;导演台改集合时间(/api/club/lead/edit-ops,HO-26)失败落 cy-result-sheet 失败半屏,后端拒绝文案原样进 why,传输失败/5xx 标「结果待确认」并回读 fetchDetail。' },
+  { file: 'pages/club/topic-detail/index.js', count: 7, reason: '「商家与成员」两个弹层各自持有可见错误态(各带 cy-error 重试,互不污染),主题设置取数失败落 cy-error,保存失败落精确 toast 并把开关回滚到提交前的值;结束主题的失败落 J5 确认框自己的 errorText,后端拒绝文案原样回显,框不关、按钮可再点;导演台改集合时间(/api/club/lead/edit-ops,HO-26)失败落 cy-result-sheet 失败半屏,后端拒绝文案原样进 why,传输失败/5xx 标「结果待确认」并回读 fetchDetail。 BL-98 报名按日趋势(/api/registration/count_by_date)失败落参与者半屏里的 cy-error(带重试),403 等后端原因原样进 sub(+1)。' },
diff --git a/ci/silent-error-allowlist.txt b/ci/silent-error-allowlist.txt
--- a/ci/silent-error-allowlist.txt
+++ b/ci/silent-error-allowlist.txt
@@ -192,0 +193 @@ chengyinhub-xcx/components/cy/npc-chat/index.js 1  # 清除对话记录失败落
+chengyinhub-xcx/pages/club/topic-detail/index.js 1  # BL-98 报名按日趋势:失败落参与者半屏 cy-error(带重试),403 等后端原因原样回显;关自动 toast 避免双弹
```
#### #114（`claude/bl-71-player-code`）
```diff
diff --git a/chengyinhub-xcx/scripts/ui-silent-error-allowlist.js b/chengyinhub-xcx/scripts/ui-silent-error-allowlist.js
--- a/chengyinhub-xcx/scripts/ui-silent-error-allowlist.js
+++ b/chengyinhub-xcx/scripts/ui-silent-error-allowlist.js
@@ -109,0 +110 @@ module.exports = [
+  { file: 'components/cy/scene-share-invite/index.js', count: 1, reason: 'BL-71 个人小程序码(/api/user/player-code)生成失败落 qrState=code-error,由 cy-error 显示后端文案并可重试,关全局 toast 避免双弹。' },
diff --git a/ci/silent-error-allowlist.txt b/ci/silent-error-allowlist.txt
--- a/ci/silent-error-allowlist.txt
+++ b/ci/silent-error-allowlist.txt
@@ -192,0 +193 @@ chengyinhub-xcx/components/cy/npc-chat/index.js 1  # 清除对话记录失败落
+chengyinhub-xcx/components/cy/scene-share-invite/index.js 1  # BL-71 个人小程序码:生成失败落 qrState=code-error,cy-error 显示后端文案并可重试;关自动 toast 避免双弹
```
#### #73（`claude/bl-32-report-member`）
```diff
diff --git a/chengyinhub-xcx/scripts/ui-silent-error-allowlist.js b/chengyinhub-xcx/scripts/ui-silent-error-allowlist.js
--- a/chengyinhub-xcx/scripts/ui-silent-error-allowlist.js
+++ b/chengyinhub-xcx/scripts/ui-silent-error-allowlist.js
@@ -109,0 +110 @@ module.exports = [
+  { file: 'components/cy/report-sheet/index.js', count: 2, reason: 'BL-32 共用举报半屏:读原因失败落 loadfail 结果半屏(带重试),提交失败落 fail/unknown 结果半屏(含后端原因、同键重试),关自动 toast 避免双弹。' },
```
