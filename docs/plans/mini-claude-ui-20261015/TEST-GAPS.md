# 单测缺口核对（BL-136，2026-10-16 快照，基线 `d0307a78`）

> 只写文档，不补测试。范围：diff 里有小程序 `.js` 改动（不含 `tests/`、`scripts/`）的 open PR。
> 方法：从每个 PR 的 `git diff base...head` 新增行里提取新函数（`function x`、方法简写 `x() {`、`const x = (…) =>`、`x: function`），再在**该 PR 分支**的 `tests/unit/*.test.js` 里找：同一个测试文件里既出现函数名（整词），又出现源模块路径的末两段（例如 `crm-campaigns/view-model`），才算「有直接覆盖」。
> 局限：只是静态匹配，不跑测试、不量覆盖率。没有点名不等于没被执行（私有辅助函数通常经导出函数间接覆盖）；点名也不等于断言充分。修改已有函数的 PR（例如 #138 只改了已有函数里的一行）不在统计里。

## 1. 汇总

- 有新增函数的 PR：**44** 个；新增函数 **507** 个，其中 **244** 个在测试里有直接点名，**263** 个没有。
- 另有 13 个代码 PR 没有新增函数（只改 wxml、测试或已有函数内部）：#57、#58、#62、#65、#68、#91、#98、#100、#117、#125、#134、#138、#150。
- 每个改动的模块都至少被一个测试文件引用（大多是页面静态合同测试），**没有完全零测试的新模块**。
- 真正值得补的是 §2 列出的「**已导出但没有直接测试**的纯函数」，共 21 个，分布在 9 个 PR。§3 是页面处理器，优先级较低；§4 是私有辅助函数，经导出函数间接覆盖，不必补。

| PR | 新增函数 | 有直接测试 | 无直接测试 | 无直接测试的函数名 |
|---|---|---|---|---|
| #22 | 2 | 2 | 0 | — |
| #23 | 64 | 24 | 40 | accountKey accountStale applyOwnership charLength closeCreate closeDangerConfirm closeMemberPicker confirmCreatedVisible emptyForm finish goBack grantReceipt isOk isSuccess jsonBody jsonHeader loadAll loadBadges loadClub loadNicknameSuggestions markUnknown noop normalize normalizeBadges normalizeMembers onFailure onGrantDone onPullDownRefresh onUnload positiveId readSuggestResult reconcileUnknownCreate requestNicknameCheck resetNicknameCheck scheduleNicknameCheck stale switchAccount trimText |
| #25 | 53 | 25 | 28 | _settle _sync appealEventCount backToForm confirmByReadBack eventTitle goBack goMine load onPullDownRefresh onReachBottom onShow openDetail pad parsePayload positiveId retry stageInfo successStatusAbnormal text |
| #27 | 53 | 23 | 30 | _afterAccepted _afterUnknown _applyState _loadEventState _loadStateFromMine _readBackDone _setForms _setPauseEntry _submit _withClearedForm blank composePauseField emptyForms isOk load onBack onConfirmPause onConfirmResume onConfirmSchedule onDismissResult onHide onPullDownRefresh onShow onUnload pad pointValue recordLines stringList timeText verifyReadBack |
| #35 | 11 | 4 | 7 | compareEntries nameEntry roleRank strip timeOf toEntry upper |
| #37 | 14 | 7 | 7 | _poiFilterSummary _savePoiFilter isVisitedPoi onPoiFilterRadius radiusLabel storageKey togglePoiFilterUnvisited |
| #38 | 10 | 6 | 4 | _applyRoamSyncOffline _roamSyncStatus offline reset |
| #39 | 4 | 3 | 1 | _syncIdle |
| #40 | 6 | 2 | 4 | _filtered monthKey monthLabel onFilter |
| #42 | 3 | 3 | 0 | — |
| #43 | 5 | 4 | 1 | _checkReadback |
| #55 | 1 | 0 | 1 | onNativeBackGuard |
| #56 | 3 | 0 | 3 | onNativeBackGuard onUnload syncFormDirty |
| #59 | 4 | 3 | 1 | onUnload |
| #60 | 2 | 0 | 2 | fallbackCenter onFail |
| #73 | 17 | 9 | 8 | backToForm backToPick close loadTaxonomy onDescriptionInput onReasonSelect onReportClose reset |
| #74 | 18 | 10 | 8 | backToForm backToPick close loadTaxonomy onDescriptionInput onReasonSelect onReportClose reset |
| #75 | 17 | 9 | 8 | backToForm backToPick close loadTaxonomy onDescriptionInput onReasonSelect onReportClose reset |
| #77 | 19 | 11 | 8 | backToForm backToPick close loadTaxonomy onDescriptionInput onReasonSelect onReportClose reset |
| #83 | 3 | 3 | 0 | — |
| #84 | 9 | 2 | 7 | errorSubOf goBack load normalizeFavoriteNode onLoad onShow text |
| #88 | 2 | 2 | 0 | — |
| #95 | 19 | 10 | 9 | backToForm backToPick close loadTaxonomy minutesText onDescriptionInput onReasonSelect onReportClose reset |
| #104 | 3 | 1 | 2 | _blockMenuItems _confirmUnblock |
| #109 | 3 | 1 | 2 | loadMerchantSafety positiveInt |
| #110 | 17 | 11 | 6 | backToPick close loadTaxonomy onCorrectionClose onMerchantCorrection validMerchantId |
| #112 | 7 | 3 | 4 | isPlainObject loadMerchantNode settle successStatusAbnormal |
| #113 | 7 | 4 | 3 | _loadShopStripVisitors positiveId sourceKey |
| #114 | 6 | 3 | 3 | loadPlayerCode settle successStatusAbnormal |
| #118 | 22 | 12 | 10 | _blockMenuItems _confirmUnblock backToForm backToPick close loadTaxonomy onDescriptionInput onReasonSelect onReportClose reset |
| #119 | 20 | 5 | 15 | applyItems errorText isSuccess isVerified loadAccess loadList onHide onLoad onShow onUnload retry shapeRecord successObject switchBucket text |
| #120 | 4 | 3 | 1 | count |
| #122 | 5 | 4 | 1 | renameErrorText |
| #123 | 3 | 1 | 2 | broadcastStatsText sameItem |
| #128 | 4 | 2 | 2 | goPrerequisite safePath |
| #130 | 8 | 5 | 3 | loadReviewDetail positiveSafeId settle |
| #132 | 14 | 8 | 6 | closeSafetyRating load onClose refreshCanSubmit resolveMerchantId show |
| #141 | 2 | 2 | 0 | — |
| #144 | 25 | 6 | 19 | closeDetail count errorText isSuccess loadAccess loadDetail loadList onHide onLoad onShow onUnload openDetail pad retryDetail shapeCampaign shapeRecipient statusOf successObject text |
| #147 | 4 | 3 | 1 | nonNegInt |
| #152 | 4 | 2 | 2 | loadActiveSubs pad |
| #158 | 4 | 2 | 2 | loadActiveSubs pad |
| #164 | 3 | 2 | 1 | _postSaveAsTemplate |
| #166 | 3 | 2 | 1 | _postSaveAsTemplate |

## 2. 已导出、无直接测试的纯函数（建议补）

| PR | 模块 | 函数 | 建议用例 |
|---|---|---|---|
| #23 CL3 | `pages/club/utils/club-badge.js` | `normalizeBadges`、`normalizeMembers` | 后端回 `null` / 非数组 / 缺 id 的条目时返回空数组或过滤掉；字段重命名映射与后端 `GrowthBadge` 一致 |
| #23 CL3 | 同上 | `grantReceipt` | 成功、业务拒绝、结果未知三种回包各返回什么；不能把未知当成功 |
| #23 CL3 | 同上 | `charLength`、`positiveId`（`trimText` 为私有） | emoji / 代理对按一个字符计；`'0'`、负数、`'abc'` → 无效 id |
| #23 CL3 | `utils/nickname-check.js` | `normalize`（`isOk`、`readSuggestResult` 为私有） | 首尾空白和全角空格；超长截断与否和后端 `/nickname/check` 口径一致 |
| #25 CL2 | `subpackageMember/report-common/view.js` | `appealEventCount`、`positiveId` | 时间线里重复 APPEAL 事件的计数；`reportId` 为字符串数字 / 非法值 |
| #27 CL4 | `pages/activity/official-organizer/organizer-rules.js` | `composePauseField` | 预设选项 + 补充文字的拼接（只有预设、只有补充、两者都有、都为空），结果仍是后端要求的文本字段 |
| #27 CL4 | 同上 | `verifyReadBack` | 回读状态与期望一致 → 成功；不一致 → 「还没同步」；回读失败 → 结果未知（不能当成功） |
| #27 CL4 | `components/cy/npc-chat/chat-export.js` | `timeText` | 秒 / 毫秒时间戳、ISO 字符串、空值 |
| #27 CL4 | `subpackageMember/components/cy/profile/points-rules.js` | `pointValue` | 正负值、0、缺字段时的 `||` 兜底 |
| #37 BL-01 | `utils/roam-poi-filter.js` | `storageKey`、`isVisitedPoi`、`radiusLabel` | `storageKey` 按账号隔离（不同 memberId 键不同，未登录也有稳定键）；`isVisitedPoi` 对已打卡 / 未打卡 / 字段缺失；`radiusLabel` 1/3/5 km |
| #40 BL-04 | `subpackageMember/points-history/rows.js` | `monthKey`、`monthLabel` | 跨年、iOS 不认的 `YYYY-MM-DD HH:mm` 字符串、非法日期 |
| #110 BL-67 | `components/cy/merchant-correction-sheet/model.js` | `validMerchantId` | 0、负数、字符串数字、`undefined` |
| #113 BL-70 | `utils/roam-shop-visitors.js` | `sourceKey` | 同一商家不同来源的键不冲突；缺 id 时的行为 |
| #119 BL-76 | `pages/merchant/verify-records/view-model.js` | `shapeRecord`（目前只经 `shapeRecordList` 覆盖） | 单条记录的状态映射、金额 / 时间格式、缺字段 |
| #144 BL-100 | `pages/merchant/crm-campaigns/view-model.js` | `shapeCampaign`（目前只经 `shapeCampaignList` 覆盖） | 未知 status → 兜底文案；收件人计数缺失时 `|| 0` |

## 3. 页面处理器 / 生命周期（优先级较低，每个 PR 补 1 个关键路径即可）

| PR | 建议优先覆盖的处理器 | 关键断言 |
|---|---|---|
| #104 / #118 | `subpackageB/pages/im/chat/index.js` `_confirmUnblock` | 回读确认之前不显示「已解除拉黑」（对应监督 nit `:667`） |
| #109 | profile `loadMerchantSafety` | 失败时不渲染摘要块，也不弹 toast |
| #112 | profile `loadMerchantNode` | 回包 `code===200` 但 data 异常时走 `successStatusAbnormal`，不显示假收藏态 |
| #113 | `pages/roam/index.js` `_loadShopStripVisitors` | 请求合并与上限（`SOURCE_MAX`），失败时不影响横滑卡 |
| #114 | `scene-share-invite` `loadPlayerCode` | 失败时保留原二维码位的空态 |
| #119 | `verify-records` `switchBucket`、`loadList` | 切换 tab 时丢弃旧响应；没有权限时走 `loadAccess` 的空态 |
| #122 | `object-cards` `renameErrorText` | 后端原文回显在 cy-field 错误位，不弹全局 toast |
| #123 | `official-mine` `broadcastStatsText` | 数字后的单位有 `||` 兜底 |
| #128 | 工作台 `goPrerequisite` + `safePath` | 不在 `ALLOWED_PATHS` 里的 path 不跳转 |
| #130 | IM `loadReviewDetail` | 只有可重试错误才显示重试 |
| #132 | `safety-rating-sheet` `refreshCanSubmit`、`resolveMerchantId` | 未核销节点不可提交；merchantId 非法时不发请求 |
| #144 | `crm-campaigns` `loadDetail`、`retryDetail` | 详情失败可重试，并且不覆盖列表 |
| #152 / #158 | `decor` `loadActiveSubs` | 页面销毁后回调不 setData（对应监督 nit `decor/index.js:486-497`） |
| #164 / #166 | `myproject` `_postSaveAsTemplate` | 结果未知时不提示成功；重复点击防抖 |
| #55 / #56 / #59 | `onNativeBackGuard`、`syncFormDirty`、`onUnload` | 表单脏时拦截返回；弹窗开着时再按返回的行为（对应 #56 监督 nit） |
| #73 系（#74/#75/#77/#95/#118） | `report-sheet` `loadTaxonomy`、`backToPick`、`reset` | taxonomy 失败可重试；关闭后再打开时状态清空（#98 BL-54 已登记待补） |

其余未点名的处理器（`onLoad`、`onShow`、`onHide`、`goBack`、`onPullDownRefresh` 等）大多只是转调，见 §1 表格，不单列。

## 4. 私有辅助函数（不建议单测）

`pad`、`text`、`count`、`positiveInt`、`nonNegInt`、`isPlainObject`、`blank`、`emptyForm(s)`、`stringList`、`minutesText`（#95 的 model 私有）、`#35 utils/official-parties.js` 里的 `compareEntries`/`nameEntry`/`roleRank`/`toEntry`/`upper`/`strip`（经导出的 `buildOfficialPartiesView` 覆盖；`timeOf` 以 `_timeOf` 导出）、`#38 roam-sync-status` 的 `offline`/`reset`（是 `createRoamSyncStatus` 返回对象的方法），这些都没有出现在模块导出里，经导出函数间接执行。

## 复跑

```bash
B=origin/claude/mini-ui-features-20261015
# 1) 某 PR 新增函数名
git diff -U0 "$B...origin/<branch>" -- 'chengyinhub-xcx/*.js' ':!chengyinhub-xcx/tests' ':!chengyinhub-xcx/scripts' \
  | grep '^+' | grep -oE 'function [A-Za-z_$][A-Za-z0-9_$]*|^\+\s*[A-Za-z_$][A-Za-z0-9_$]*\s*\([^)]*\)\s*\{'
# 2) 是否有直接测试：同一测试文件里既有函数名、又有模块路径末两段
git grep -l -w <fn> origin/<branch> -- 'chengyinhub-xcx/tests/unit/*.test.js' | xargs -I{} sh -c 'git show {} | grep -qF "<dir>/<module>" && echo {}'
```
