# 评审规则静态复核（BL-137）

> 只汇总，不改代码。扫描时间 2026-10-16，基线 `origin/claude/mini-ui-features-20261015`（d0307a78）。
> 范围：所有 open 的 `claude/bl-*` / `claude/cl*` PR（共 126 个）里，相对基线**新增**的 `chengyinhub-xcx/**/*.js|*.wxml` 行（排除 `tests/`、`scripts/`）。有代码改动的 PR 共 51 个，其余为 BLOCKED 说明或纯文档 PR，不在本表。
> 计数方法（01:50Z 复核）：对 `origin/claude/bl-*`、`origin/claude/cl*` 全部分支跑 `git diff --name-only d0307a78...$b -- 'chengyinhub-xcx/**/*.js' 'chengyinhub-xcx/**/*.wxml' ':!chengyinhub-xcx/tests' ':!chengyinhub-xcx/scripts'`，非空的 65 个；去掉无 PR 的 8 个（`bl-08/09/10/11/13/14/24` 作废分支、`bl-51-report-reason-note`）和扫描后才开 PR 的 6 个（#183–#186、#188、#189），得 51。#75（BL-34，带 #73 代码）、#158/#166（带上游提交）计在内。初版写的 56 未按此口径去重，作废。

## 复核的四条规则

| 规则 | 判据 | 豁免 |
|---|---|---|
| R1 新可点元素有 `hover-class` | 新增行含 `bindtap`/`catchtap`，回溯到所在标签，标签内无 `hover-class=`，**或 `hover-class` 挂在不支持它的组件（如 `text`、`image`）上** | `button`、`cy-*` 组件（`cy-btn`/`cy-cell` 组件内部自带 `hover-class`，已核基线 `components/cy/btn|cell/index.wxml`）、只有 `catchtap="noop"` 的拦截层、`scroll-view`/`map`/`input` 等原生控件 |
| R2 `app.sendRequest` 回调不在方法简写里依赖 `this` | 新增行以 `success(…) {` / `fail(…) {` / `complete(…) {` / `successStatusAbnormal(…) {` 开头，花括号体内出现 `this` | 箭头函数（`success: (res) => …`）、`function` 写法里用 `that` |
| R3 宿主页场景半屏经单一 `sceneCurrent` | 基线中用 `sceneCurrent` 的宿主（`pages/roam`、`pages/merchant/index`、`pages/club/detail`、`pages/play/index`、`pages/shezhi`）里新增的 `cy-sheet`/`cy-*-sheet`/`.psheet`/`.pdim`，是否包在 `sceneCurrent.id === …` 宿主内；另外人工看了非宿主页/组件新增半屏是否叠在场景半屏之上 | 组件内部用单一 `stage` 切换多张结果半屏（`report-sheet`、`merchant-correction-sheet`、`safety-rating-sheet`），不算多宿主 |
| R4 无裸错误文案 / 假成功文案 | 新增 `title: x.msg/errMsg/message` 无 `||` 兜底；`res.msg`/`e.message` 直接回显；成功提示（`toast.success`、`kind="success"`、「已…」）是否只在业务码成功后出现 | `app.getRequestErrorMessage(res, 兜底)`、`(res && res.msg) \|\| '中文兜底'`、wxml 侧 `{{err \|\| '兜底'}}` |

## 结论总览

| 规则 | 命中 | 说明 |
|---|---|---|
| R1 | **1** | 见下表 F-4（#23 把 `hover-class` 挂在 `<text>` 上，不生效）；其余 50 个代码 PR 的新增可点元素都带 `hover-class`，或是自带按压态的 `cy-btn`/`cy-cell` |
| R2 | **0** | 新增方法简写回调 60 余处（#166/#164/#158/#152/#147/#141/#128/#118/#104/#84/#27/#25/#23），体内全部改用 `that`/闭包变量；其余新增回调均为箭头函数 |
| R3 | **1 条需复核** | 见下表 F-1；宿主页新增半屏（#37 `roam-poi-filter`、#59 `club-edit` 返回守卫）均走 `sceneCurrent` |
| R4 | **0 条违规，2 条 nit** | 见下表 F-2、F-3；所有成功态均在业务码 200（或回读确认）后才出现，没有发现假成功 |

## 逐条明细

| # | PR | 规则 | file:line（PR head） | 现状 | 建议 |
|---|---|---|---|---|---|
| F-1 | #42 BL-05 | R3 需复核 | `components/cy/scene-roam-stamp-album/index.wxml:39` | 集邮册本身是 `pages/roam/index.wxml:880` 的 `sceneCurrent.id === 'roam-stamp-album'` 场景半屏（`variant: 'full'`）；BL-05 在组件里新增 `<cy-sheet show="{{!!detail}}">` 详情半屏，叠在场景半屏之上，显隐不经 `sceneCurrent`。同一组件也被 `pages/play/index.wxml:1518`、`components/cy/scene-deep-link/index.wxml:49` 挂载 | 监督线确认「场景内二级半屏」是否允许；不允许则改为组件内切换视图（不加新半屏），或在宿主注册子场景。改动前需用户/协调者拍板，本卡不改 |
| F-2 | #88 BL-43 | R4 nit | `pages/merchant/decor/ai-npc/index.js:792`（`_doClearVoice` 的 `.catch`） | `_request` 的 reject 一律带中文兜底（`'请求失败，请重试'`/`'网络异常，请稍后重试'`），`refreshVoice` 自抛的也是中文；只有 `.then` 链内出现运行时异常（TypeError 等）时 `e.message` 才会是英文原文 | 可选：`e.message` 改为 `(e && e.message) \|\| '声音没有清除，请稍后再试'` 不能挡住英文；真要挡需判断 `e instanceof Error && /[一-龥]/`。与本页既有 `.catch((e) => toast(e.message))` 写法一致，**不阻塞** |
| F-4 | #23 CL3 | R1（**已在 #23 `94da5557` 修复**，三处 `<text>` 改为 `<view>`，head `06048b3d`） | `pages/gerenziliao/gerenziliao.wxml:172-173`（重新检查）、`:178-179`（推荐昵称胶囊）、`:182-184`（换一批）（head `79570ed2`） | 三处都是 `<text bindtap … hover-class="cy-pressed">`；微信 `text` 组件不支持 `hover-class`，点了没有按压反馈。初版 R1 只看「标签内有无 `hover-class=`」，漏了这一类 | 在 #23 里改：外包一层 `view` 并把 `hover-class` 移到 `view` 上（或直接把 `text` 换成 `view`）。本卡不改代码 |
| F-3 | #164 BL-120（#166 BL-122 同一行） | R4 nit | `subpackageA/pages/myproject/index.js:347` 另存为模板 `success(res)` 内 `toast((res && res.msg) \|\| '已另存为主题模板')` | 成功分支已判 `res.code == '200'`，不是假成功；但 RuoYi 成功 msg 常为「操作成功」，会盖掉更具体的「已另存为主题模板」 | 可选：成功时固定显示「已另存为主题模板」。**不阻塞** |

### 已核、未命中但值得记一笔的

- #141 BL-98 `pages/club/topic-detail/index.js` `trendError` 回显后端 403 原文（如「无权查看该项目趋势」），wxml 侧 `sub="{{trendError || '稍后再试一次'}}"` 有兜底，符合 U6。
- #132 BL-89 `pages/play/merchant/index.wxml` 新增 `cy-safety-rating-sheet`：该页基线不是 `sceneCurrent` 宿主（只有 `cy-privacy-gate`），用页面自有布尔 `safetyRatingShow`，与本页写法一致；组件内 `stage` 单一切换，`stage: 'done'` 只在 `code===200 && data.ratingId` 后出现。
- #112 BL-69 收藏据点 `cyToast.success` 只在 `readFavoriteToggle(res)` 读到确定的 `favorited` 后出现，失败/未知走 `settle(null, …)`。
- 举报/纠错类半屏（#73/#74/#75/#77/#95/#110/#118）网络失败统一用「暂时不知道是否已提交。再试一次不会重复提交」，不承诺成功。

## 复跑方法

在仓库根目录，先 `git fetch origin '+refs/heads/claude/*:refs/remotes/origin/claude/*'`，对每个 PR 分支 `$b`：

```sh
B=origin/claude/mini-ui-features-20261015
# 有哪些代码文件
git diff --name-only $B...origin/claude/$b -- chengyinhub-xcx ':!chengyinhub-xcx/tests' ':!chengyinhub-xcx/scripts' | grep -E '\.(js|wxml)$'
# R1 候选：新增可点行（再看所在标签有没有 hover-class）
git diff -U0 $B...origin/claude/$b -- chengyinhub-xcx ':!chengyinhub-xcx/tests' ':*.wxml' | grep -E '^\+.*(bind|catch):?tap='
# R2 候选：新增方法简写回调（再看花括号体内有没有 this）
git diff -U0 $B...origin/claude/$b -- chengyinhub-xcx ':!chengyinhub-xcx/tests' ':*.js' | grep -E '^\+\s*(success|fail|complete|successStatusAbnormal)\s*\('
# R3 候选：新增半屏
git diff -U0 $B...origin/claude/$b -- chengyinhub-xcx ':*.wxml' | grep -E '^\+.*(<cy-[a-z-]*sheet|psheet|pdim|show=")'
# R4 候选：错误原文回显 / 成功提示
git diff -U0 $B...origin/claude/$b -- chengyinhub-xcx ':!chengyinhub-xcx/tests' ':*.js' | grep -E "^\+.*(\.(msg|errMsg|message)\b|toast\.success|icon: *'success'|已(提交|保存|清除|收藏|另存|解除|颁发))"
```

本次是用 node 脚本按上面判据逐 PR 取 PR head 的完整文件，回溯标签 / 匹配花括号体做的判断；命中项再逐条人工看上下文。脚本是临时文件，不入库。
