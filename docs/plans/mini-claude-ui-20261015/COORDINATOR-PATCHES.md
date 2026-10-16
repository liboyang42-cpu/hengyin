# 协调者补丁汇总（BL-126，2026-10-16 00:05Z 快照）

> 只写文档，不改任何协调者统筹文件。来源：逐个读取 open 的 `claude/bl-*` / `claude/cl*` PR（#22–#169，共 121 个）正文及作者补充评论里「交协调者」的段落；直接 GET 数另按各分支相对基线 `claude/mini-ui-features-20261015`（`96777f0c`）的实际 diff 复核。PR 正文之后若有修改，以 PR 正文为准。
> **所有 PR 的计数都是各自相对同一基线报的增量，不能直接采用任一分支上的绝对值。** 下文「累计期望值」= 基线 + 各 PR 增量之和，仅供核对；最终一律以合并后的树重新生成/实测为准。

## 0. 基线值（`claude/mini-ui-features-20261015` @ `96777f0c`）

| 文件 / 计数 | 基线值 |
|---|---|
| `app.json` 页面数（主包 + 分包） | 10 + 123 = 133 |
| `scripts/uiaudit/route-matrix.json` routes / `route-matrix-freshness` / `title-system-owner-contract` 盘点 | 133 |
| `scripts/uiaudit/action-ledger.json` `controlCount`（`tests/unit/action-ledger.test.js` L1376 附近） | 4480；tap 1796；组件事件合同（L763）2348 |
| `tests/unit/get-auth-matrix-contract.test.js` 直接 GET / req 包装 GET | 33 / 22（MATRIX 55 行） |
| `tests/unit/nav-page-title-spacing-contract.test.js` cy-page-title 页面数 | 71 |
| `tests/lint/ui-integrity-baseline.js` `ratchet.u5Silent` / u4Dynamic | 28（上限 29） / 59 |
| `scripts/ui-silent-error-allowlist.js` 条目数 | 96 |
| `scripts/shot-matrix.js` STATS | 329 |

## 1. 累计期望值（全部合入后）

| 计数 | 基线 | 增量来源 | 累计期望 | 是否需按合并树重算 |
|---|---|---|---|---|
| `app.json` 页面 | 133 | #23 +1（subpackageClub `badges/index`）、#25 +2（subpackageMember `report-mine/index`、`report-detail/index`）、#27 +1（pages/activity `official-organizer/index`）、#84 +1（subpackageRoam `node-favorites/index`）、#119 +1（pages/merchant `verify-records/index`）、#144 +1（pages/merchant `crm-campaigns/index`） | **140**（若采纳 #35 可选的 `pages/activity/official-parties/index` 则 141，但 #35 未建该页，不建议加） | 否（页面清单确定） |
| route-matrix / route-matrix-freshness / title-system-owner | 133 | 同上 | **140** | 是（`build-route-matrix.js --json` 生成；#23/#27/#119 都写了 133→134，#25 写 135，均为单分支值） |
| nav-page-title-spacing | 71 | #23 +1、#25 +2、#27 +1、#119 +1、#144 +1、#84 +1（新页用了 `cy-page-title`，**正文未列**） | **78** | 是 |
| get-auth-matrix 直接 GET | 33 | #25 +6、#27 +5、#144 +2、#73 +1、#110 +1、#123 +1、#128 +1、#130 +1、#132 +1；**正文未列、diff 实测新增**：#84 +1（`/api/city/nodes/favorites`）、#109 +1（`/api/community-trust/safety/merchants/{id}`）、#112 +1（`/api/city/nodes/merchant/{id}`） | **55** | 是（#74/#77/#95/#118 分支含 #73 的同一条 taxonomy GET，不重复计） |
| get-auth-matrix req 包装 GET | 22 | #83 +1（`req('/api/official/active-for-roam'`）；#37 是同一调用的改写，净 0 | **23** | 否 |
| MATRIX 行数 | 55 | 直接 + 包装 | **78** | 随上两项 |
| action-ledger `controlCount` | 4480 | 正文给出精确增量：#23 +22、#25 +21、#27 +19、#73 +20、#37 +6、#119 +6、#42 +3、#59 +2、#77+#104 +1（#118 合并预演 4501 − #73 的 4500）、#40 +1、#128 +1、#164 +1 = **+103**；约 +1：#74、#88、#130；未给数（新增控件）：#110、#122、#132（约 5）、#144（约 5）、#55、#84、#112、#114 | **≥ 4586，预计约 4600** | **是**（必须在合并树上 `build-action-ledger.js --write` 重建后取实测值） |
| action-ledger tap / 组件事件合同 | 1796 / 2348 | #23 +9/+13、#25 +12/+9、#27 +8/+10、#119 +1/+5、#59 0/+2、#42 0/+1 | ≥ 1826 / ≥ 2388 | 是 |
| `ratchet.u5Silent` | 28 | #120 +1（`subpackageMember/coupon/coupon.js:157`） | **29（=上限，用尽最后一格）** | 否；再有新 U5 需协调者决定是否抬上限 |
| shot-matrix STATS | 329 | #23 +1（F96）、#25 +2（D91/D92）、#27 +1（B92）；#84/#119/#144 新路由按覆盖率门禁也需要矩阵行（正文未列） | 333（已列）/ 336（含未列） | 是 |

## 2. 按文件分列的补丁

### 2.1 `chengyinhub-xcx/app.json`
- #23 subpackageClub `pages` 加 `"badges/index"`。
- #25 subpackageMember 加 `"report-mine/index"`、`"report-detail/index"`。
- #27 `pages/activity` 分包追加 `"official-organizer/index"`。
- #84 subpackageRoam `official-route/index` 之后加 `"node-favorites/index"`；**入口按钮（`pages/roam/index.wxml` entry-map__actions 加「收藏」）需用户决定**，正文附 diff。
- #119 `pages/merchant` 分包 `customer/detail/index` 后加 `"verify-records/index"`（⚠️ 已提交在 #119 分支，见 §3）。
- #144 `pages/merchant` 分包加 `"crm-campaigns/index"`。
- #35（可选，不建议）主包 `pages/activity/official-parties/index`——该页未建。

### 2.2 `scripts/uiaudit/action-ledger.json` 与 `tests/unit/action-ledger.test.js`
- 需重建且**控件数变化**：#23、#25、#27、#37、#40、#42、#59、#73、#74、#77、#88、#104、#110（新写口 `/api/community-trust/contributions/submit`）、#112、#114、#118、#119、#122（新写口 `/api/object-card/rename`）、#128、#130、#132、#144、#164（新接口 `POST /api/template/topic-template/save-as`）、#55（`bind:afterleave`）、#84。
- 需重建但**控件数不变**（行号/摘要漂移）：#22、#38、#39、#43、#56、#57、#58、#60、#68、#109、#113、#120、#123、#138、#147、#152。
- `build-action-ledger.js`：#104/#118 要求在 `EXPLICIT_WRITE_APIS` 加 `'/api/im/unblock'`，并登记 im chat 的 unblock 控件（#104 正文给出代码片段）。

### 2.3 `tests/unit/get-auth-matrix-contract.test.js`（MATRIX 新行）
| PR | 文件 | marker | mode / role / scope（以正文最新版为准） |
|---|---|---|---|
| #73 | `components/cy/report-sheet/index.js` | `url: '/api/community-trust/reports/taxonomy'` | optional / visitor/member / global-read+viewer-personalization |
| #110 | `components/cy/merchant-correction-sheet/index.js` | `url: '/api/community-trust/contributions/taxonomy'` | optional / visitor/member / global-authenticated-read |
| #123 | `pages/activity/official-mine/index.js` | `url: '/api/official/broadcast/' + encodeURIComponent(String(id)) + '/stats'` | required / publisher / self-publisher |
| #128 | `pages/merchant/index/index.js` | `url: '/api/merchant/ops/prerequisites'` | required / merchant-owner（评论写 `merchant/owner`，需统一）/ self-merchant |
| #130 | `subpackageB/pages/im/chat/index.js` | `` url: `/api/audit/${auditTaskId}/detail` ``（早期评论的 `url: auditUrl` 已废） | required / member / self-submitter |
| #132 | `pages/play/merchant/components/safety-rating-sheet/index.js` | `url: '/api/community-trust/safety/taxonomy'` | 正文 required/member/public-taxonomy，评论 optional/visitor/member/global-authenticated-read——**scope 待协调者裁定**（与 #110 同类） |
| #144 | `pages/merchant/crm-campaigns/index.js` ×2 | `url: '/api/merchant/crm/campaigns',`、`` url: `/api/merchant/crm/campaigns/${id}` `` | required / merchant / MARKETING_READ |
| #25 | 6 行（report-mine / report-detail 等） | 见 #25 分支 | 见 #25 |
| #27 | 5 行，新 scope `controlled-event-operator` | 见 #27 分支 | 见 #27 |
| #83（包装） | `pages/roam/index.js` | `req('/api/official/active-for-roam'` | optional / visitor/member / public-list+viewer-personalization |
| #84（**正文未列**） | `subpackageRoam/node-favorites/index.js` | `url: '/api/city/nodes/favorites'` | 需登录；待补 |
| #109（**正文未列**） | profile 组件（商家安全感受摘要） | `/api/community-trust/safety/merchants/{merchantId}` | 待补 |
| #112（**正文未列**） | profile 组件（商家收藏据点） | `/api/city/nodes/merchant/{merchantId}` | 待补 |

**scope 枚举核对**（`get-auth-matrix-contract.test.js:114` 的 `VALID_SCOPES`，不在枚举里的 scope 会让 `:244` 断言失败）：
- ✅ 在枚举内：#73 `global-read+viewer-personalization`、#110 `global-authenticated-read`、#123 `self-publisher`、#128 `self-merchant`、#83 `public-list+viewer-personalization`。
- ❌ 不在枚举内：#130 `self-submitter`（改用 `self-account`，或同一补丁里加进枚举）；#132 `public-taxonomy`（改用 `global-authenticated-read`，与 #110 一致）；#144 写的 `MARKETING_READ` 是权限码不是 scope（按现有商家 CRM 行应为 `self-merchant`，role 列写 `merchant/merchant:crm:...` 形式）；#27 新增的 `controlled-event-operator` 需在同一补丁里加入枚举（#27 正文已说明）。

#113（`POST /api/roam/shop/visitors`）、#114（`POST /api/user/player-code`）是 POST，不进 GET 矩阵。

### 2.4 silentError 白名单（`ci/silent-error-allowlist.txt` + `scripts/ui-silent-error-allowlist.js`）
- 交协调者：#25（report-mine 2、report-detail 4、im/list 1→2）；#145（仅当 Codex 采纳其 fabu 补丁）。
- 已在各自分支内登记（整合时并集即可）：#119（verify-records 2）、#120（coupon.js 1）、#128、#130（chat 5→6）、#132（+3）、#144（crm-campaigns 3 处）、#152（decor 2→3）、#164（`ui-silent-error-allowlist.js:95` 5→6，正文未提）。
- 基线自身的 Fast gates 红（fabu 0→2、complaint 0→1）不是任何 BL PR 引入的。

### 2.5 `tests/lint/ui-integrity-baseline.js`
- #22：`pages/roam/index.js` 15 条（u4Dynamic 4、u5Silent 11）等量换 key，两条 keyDigest 重算。
- #39：`pages/roam/index.js` 15 条行号映射 `746→748; 1710→1712; 1799→1801; 2460→2462; 2469→2471; 3752→3772; 4047→4067; 4261→4281; 4346→4366; 4448→4468; 4584→4604; 4642→4662; 4874→4894; 5406→5426; 5413→5433`，keyDigest 重算。**#22 与 #39 都改 roam 行号，必须按合并树重新映射。**
- #25：im/list 一条 229→269，u4Dynamic digest 重算。
- #120：新增 u5Silent `subpackageMember/coupon/coupon.js:157`，ratchet 28→29，digest 重算。
- #164：第 987 行键 `subpackageA/pages/myproject/index.js:408` → `:452`。
- 视合并树而定：#73、#84、#109（profile wxml +4 行）、#119。#27、#138（已改为同行写法）明确不需要。

### 2.6 nav-page-title-spacing / route-matrix-freshness / title-system-owner
- nav-page-title：#23、#27、#119、#144 各 +1，#25 +2，#84 +1（未列）。
- route/title 盘点：#23、#27、#119 各 +1，#25 +2，#84、#144 各 +1（#84/#144 正文只写了 app.json，需同步这两项）。

### 2.7 其他统筹文件
- `utils/danger-actions.js`：#23 加 `club.badge.grant`；#27 加 `official.organizer.schedule|pause|resume`。
- `scripts/fixtures.json` + `scripts/shot-matrix.js`：#23 F96、#25 D91/D92、#27 B92（只追加，不全量重生）。
- `scripts/ui-theme-unit-lint.js:30`：#27 商家域正则 `official-detail` → `official-(?:detail|organizer)`。
- `tests/unit/im-list-mutation-race.test.js`：#25 require 桩加一行。
- `tests/unit/unused-component-registry.test.js`：#35 在 CL4 合并并挂载 `cy-official-coorganizers` 后删一行。
- `components/cy/tabs/index.wxss`：#63 可选删过时注释。

## 3. 已把协调者文件提交在自己分支上的 PR（整合时可丢弃/重生）
- #119：`app.json`、`route-matrix.json`（134）、`action-ledger.json`（4486）、`action-ledger.test.js`、`route-matrix-freshness.test.js`、`title-system-owner-contract.test.js`、`nav-page-title-spacing-contract.test.js`（72）。
- #120：`action-ledger.json`（4480）、`ui-integrity-baseline.js`（u5Silent 29）。
- #23、#25、#27：为本地门禁生成过上述同类文件，正文声明 PATCH_PROPOSED、不声称拥有。

## 4. 需要按合并后的树重算的项（汇总）
1. `controlCount`、tap、组件事件合同——所有 PR 都基于 4480/1796/2348 报增量（#74 基于 #73 的 4500，#118 的 4501 假设 #73+#77+#104 已合）。
2. get-auth-matrix 直接 GET（#110 写「若 #132 先合则 34→35」，#123 写「在当时的值上 +1」，#144 写与 #128/#130/#132 累加）——累计 55，含正文漏列的 #84/#109/#112。
3. route-matrix / title / nav-page-title——#23/#27/#119 都写 134、#25 写 135，累计 140 / 78。
4. `ui-integrity-baseline` 中 `pages/roam/index.js`（#22、#39、#37、#38、#83、#113 都动 roam）与 profile 组件（#109、#112、#138、#66 系列）的行号键。
5. shot-matrix STATS 与 fixtures（#23/#25/#27 已列，#84/#119/#144 未列）。

## 附：本次核对发现的正文漏项（交协调者/各线确认）
- #84：新页用 `cy-page-title`（nav-page-title +1）、新增直接 GET `/api/city/nodes/favorites`，正文均未列。
- #109、#112：各新增 1 条直接 GET，正文未给 MATRIX 行与计数。
- #144：正文只列 app.json 与 nav-page-title，route-matrix / title-system-owner 盘点 +1 未列。
- #164：silentError 白名单 5→6 只在分支里改了，正文未提。
