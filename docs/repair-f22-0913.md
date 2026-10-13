# F22 修复记录：官方活动详情的可读性、任务说明与奖励承诺

日期：2026-09-13 · 范围：小程序「官方活动详情」页（仅前端展示；不改后端/暂停政策）
来源：`第六轮_全产品查漏与官方活动实跑.md` · P2 / F22（第 40–47 行）

## 问题

1. **可读性**：事实栏（参与人数 / 我的进度 / 活动周期 / 开放时间 / 范围 / 任务数）底色是主题
   浅色表面，文字却借首页深色区的 `--cy-color-home-upcoming-text`（白）/`-sub`（白 60%），
   白字压浅灰肉眼难辨（`official-detail/index.wxss:39–45`）。
2. **任务说明**：`description` 为空时 `.od-task-sub` 直接渲染 `item.missionType` 枚举
   （如 `THEME_VERIFIED_FINISH`），玩家读不懂；该字段为空是允许发布的输入。
3. **奖励承诺**：`rewardJson={}` 时 `_rewards` 兜底 `'🏅 参与即有惊喜'`，且底部恒显示
   「完成活动任务可获得页面所示奖励」——无依据的承诺。

## 修复

### ① 可读性（`index.wxss`）

事实栏文字改用**主题文字 token**（会随浅/暗档正确翻转），不再借首页深色区白字：

- `.od-facts` → `color: var(--cy-color-text-primary)`
- `.od-fact-value` → `var(--cy-color-text-primary)`；`.od-fact-label` / `.od-fact-sub` → `var(--cy-color-text-secondary)`

背景 `--cy-color-bg-surface-strong` 不动，横向滚动（`.od-facts` scroll-x / `.od-facts-inner` inline-flex）不动。
两档 token 的 WCAG 对比度（测试直接计算）：

| 档 | 主文字 / 底 | 次文字 / 底 |
|---|---|---|
| 玩家暗档 | #F8F8F8 / #3B3B3D ≈ **10.4:1** | #B7B7B7 / #3B3B3D ≈ **5.5:1** |
| 商家浅档 | #000000 / #E4E4E4 ≈ **16.5:1** | #404040 / #E4E4E4 ≈ **8.1:1** |

（旧的白字 #FFFFFF 压浅灰 #E4E4E4 ≈ 1.3:1，即 F22 的不可读。）

### ② 任务说明（`index.js` + `index.wxml`）

按后端 `OfficialEventMission.java` 的 4 个类型（只读核对）给出准确中文行动与完成判据；
`description` 有值就用作者文案，为空才兜底；未知类型明说规则未提供，不臆造：

| missionType | 标题兜底 | 行动/完成判据兜底 | 后端可用 |
|---|---|---|---|
| `ROAM_POI_ARRIVAL` | 漫游据点到达 | 该任务类型暂不支持发布与核验 | 否 |
| `EVENT_POINT_ARRIVAL` | 活动点位到达 | 走到活动指定点位，由服务端核验到达 | 是 |
| `THEME_VERIFIED_FINISH` | 完成绑定主题 | 完成绑定的主题，完成后自动同步 | 是 |
| `ROAM_SESSION_AREA` | 漫游区域覆盖 | 该任务类型暂不支持发布与核验 | 否 |
| 未知 | 活动任务 | 任务规则未提供，请以主办方说明为准 | 否 |

`_decorate` 给每个 mission 派生 `_titleText` / `_descText` / `_supported`，WXML 只读这些字段
（含 aria-label），不再出现 `item.description || item.missionType`。后端禁发布的类型照常展示条目，
但动作标签为「暂不支持」、点击给明确提示（不静默、不误触发报名）。

### ③ 奖励（`index.js` + `index.wxml`）

- `_rewards`：解析失败 / 无 `rewardJson` / 非对象 / 数组 ⇒ 一律返回 `[]`（坏数据不当有奖）；
  删除 `'参与即有惊喜'` 默认项。有配置时按后端 `rewardJson` 实际字段展示：
  `settleBadge===true`→结算限定徽章、`settleCouponId`（正整数）→结算专属券、
  `settleXp`（正整数）→带数量的成长值、`collectiveCouponId`（正整数）→集体达标券（带条件文案）。
- WXML：奖励列表 `wx:if="{{rewards.length}}"`；无配置时显示「主办方尚未配置奖励，页面不承诺任何奖励。」
- 底部奖励说明仅在确有奖励时显示，文案改为「完成活动任务后按上方配置发放 / 实际发放结果以活动结算为准」。

### 保留项

暂停说明（`.od-note--danger` + `pausedReason`）与「暂停/结束态 CTA 渲染为不可点文本」保持原样，
未改 F21 的暂停/退场政策，未动报名/奖励后端。

## P2 复核补修（Standards/Spec 双轴提出）

### P2-1 奖励逐字段正整数校验（不接受 truthy）

后端契约：`OfficialEventV2ServiceImpl` 用 fastjson `getLong("settleCouponId")` /
`getLong("collectiveCouponId")` / `getInteger("settleXp")`（数字字符串会被一并转数）。
原来 `if (rw.settleCouponId)` / `Number(settleXp) > 0` 会把负数、字符串 `"0"`、对象/数组、
`Infinity`、小数当奖励。`positiveInt(value, maxText)` 按字段各自范围校验：
`settleXp` 为 int（`1..2147483647`），`settleCouponId` / `collectiveCouponId` 为 long
（`1..9223372036854775807`）；number 须安全整数且在界内，字符串须纯数字（去前导零后非 0）
且按**字符串逐位比较**判上界，其余按「没有这项」；单项非法只丢该项。

**数值精度**：券 ID 接受 Java Long 范围内的数字和十进制字符串。总控复审否决了“超安全整数的 JSON 数字按未配置处理”：这是前端解析造成的回归，不能要求已有后台配置改格式。现在先验证原始 JSON，再跳过完整字符串并保留数字词元，按十进制位移还原数学上的整数（含小数/指数写法），最后按各字段 int/long 范围校验。未引入 BigInt 或依赖；非法原文、非整数及超界值仍拒绝。

### P2-2 后端禁发布任务如实标未支持

`validatePublishMission`（`OfficialEventV2ServiceImpl.java:1457–1465`）明确 block
`ROAM_SESSION_AREA`（P1）与 `ROAM_POI_ARRIVAL`（未过 Gate0），且 `missionComplete`
（`:1649–1653`）对 `ROAM_POI_ARRIVAL` 恒 false。原前端给这两类兜「走到…由服务端核验」
并显示「自动同步」，是编造核验路径。改为 `_supported:false` + 文案「暂不支持」+
点击 toast「该任务类型暂不支持核验」；条目保留不删，后台规则未改。

### P2-3 集体券不是无条件全员

后端 `collectiveReached`（`:2060–2070`）要求 `collectiveEnabled` 且 `threshold>0` 且当前达标；
发放循环（`:1148–1161`）要求本人 eligible（`allMissionsComplete`）且遍历的是 ACTIVE 报名者。
两处文案都改准确：
- 奖励列表里的集体券：`_collectiveRewardText` → 「集体达标券（全城达标且本人完成全部任务后发放）」；
  未开启全城达标时如实说明「主办方未开启全城达标…才发放」。
- **集体进度区**（`index.wxml:144`，原来无条件写「达标后全员解锁集体奖励」）：新增
  `e._hasCollectiveReward`（`rewardJson.collectiveCouponId` 为合法 Long 才为真）。
  有配置 → 「开启且集体达标、本人保持有效报名并完成全部任务后发放集体奖励」；
  无配置 → 「当前未配置集体奖励，全城进度仅供了解」。**进度区与 `wx:if="{{e.collective && e.collective.enabled}}"` 保留不删**。
个人券/成长值不受影响，照常展示。

## 改动文件

| 文件 | 改动 |
|---|---|
| `chengyinhub-xcx/pages/activity/official-detail/index.wxss` | ① 事实栏文字改主题 token（两档可读） |
| `chengyinhub-xcx/pages/activity/official-detail/index.js` | ② `MISSION_TYPE_TEXT`+`_supported`+`_decorate` 派生 `_titleText/_descText/_supported/_hasCollectiveReward`；③ `_rewards` 无默认承诺/坏数据按无奖；P2-1 `positiveInt(value,maxText)` 按 int/long 范围校验；P2-3 `_collectiveRewardText`；`_cta`/`onMissionTap` 未支持分支 |
| `chengyinhub-xcx/pages/activity/official-detail/index.wxml` | ② 任务行读派生字段、动作标签按 `_supported` 区分；③ 奖励列表/说明按实际配置开关 + 无奖如实说明；P2-3 集体进度区提示按 `_hasCollectiveReward` 分支 |
| `chengyinhub-xcx/tests/unit/official-detail-f22-contract.test.js` | 新增 27 条契约（含对比度计算、逐字段范围校验、Long 字符串精度、未支持路径、集体券与进度区条件、负控） |

## 验证

### 负控（先 RED）

```
cd chengyinhub-xcx
node --test tests/unit/official-detail-f22-contract.test.js
```

- 首轮 13 条中 **8 条判红**：事实栏仍用 home-upcoming；`_descText` 未定义；未知类型无
  「规则未提供」；空配置 `_rewards` 造默认奖励；坏 JSON 被当成有奖；`settleXp=0` 造奖励；
  WXML 无奖励开关/仍承诺「页面所示奖励」。
- P2 首轮补修 20 条中 **7 条判红**：`ROAM_POI_ARRIVAL 目前未支持`；负数 `settleCouponId` 仍算奖励；
  单项非法未只丢该项；WXML 动作标签未按 `_supported` 区分；点未支持任务无提示且误触发报名；
  集体券文案缺「完成全部任务」；集体未开启未如实说明。
- 两轴复审补修 27 条中 **4 条判红**：Long 合法字符串 `9007199254740993` 被 Number 精度隐藏；
  XP `2147483648` 越界仍算奖励；集体进度区无 `_hasCollectiveReward`（空配置也承诺）；
  进度区无「开启且集体达标、本人保持有效报名并完成全部任务」准确条件。

### 修复后（GREEN）

```
node --test tests/unit/official-detail-f22-contract.test.js
# tests 27 · pass 27 · fail 0
```

覆盖：事实栏两档对比度 ≥4.5（+ 白字负控）；支持类型准确中文 + 未支持类型如实标注 + 未知类型
规则未提供；有描述不覆盖作者文案；空配置 / 坏 JSON / 假值按无奖；逐字段**范围**校验
（int 1..2147483647 / long 1..9223372036854775807，含负数/字符串0/对象/数组/Infinity/小数/超界/前导零）
且 Long 合法大字符串不因 Number 精度被隐藏；未支持任务点击提示且不误触发报名；集体券与进度区条件文案
（有配置写全条件、无配置不承诺）；个人券/成长值不被误隐藏；暂停文案与 CTA 禁用。

### 相关测试与 lint

```
node --test tests/unit/official-detail-f22-contract.test.js \
          tests/unit/official-pages-state-contract.test.js \
          tests/unit/official-solo-story-seed-contract.test.js \
          tests/unit/official-inbox-contract.test.js \
          tests/unit/wxml-balanced-tags-contract.test.js \
          tests/unit/nav-page-title-spacing-contract.test.js \
          tests/unit/primary-button-height-contract.test.js
# tests 51 · pass 51 · fail 0

npm run lint:scope                       # JS-SCOPE PASS: 495 个生产 JS
node scripts/a11y-tap-target-lint.js     # 过小命中区 0 处(基线 0)
```

### 门禁状态（按总控指示不改生成物，集成后统一处理）

- `npm run lint:ui` → **FAIL**：22 条全在 `pages/publish/fabu/index.js`（属另一任务 F14 的基线漂移）；
  `official-detail` 只有 3 条 **信息性** `[U1 DYNAMIC]` 动态接口日志，无基线违规。
- `node scripts/uiaudit/build-action-ledger.js --check` → **FAIL**。重建 `+572/-495`、
  `controlCount 3963→3964`：`official-detail/index.js` **14 条行号漂移、无新控件**（本次 F22）、
  fabu 381 条（F14）、nearby 62 条（F08 已 commit `08a93406f`）。**未改 `action-ledger.json` / UI baseline。**
- 未为规避门禁压缩无关代码。

## 未验证项

- 未做原生/真机截图：真实可见可读性与玩家/商家身份下的实际渲染由总控后续截图验收。
- 未连生产、未改后端；未动 F14（已交 root）/F08（已 commit `a945b40de`、`08a93406f`）/
  F20（`a4c44d980`）文件。
- 未 push / 未开 PR / 未合并。

## 总控最终数值修复

根工作区保留原始大数词元，修正第三版错误测试预期。`/tmp/cy-f22-numeric-long-red.log`：29项中2 RED，合法数值及重复键最后合法值被误藏；`/tmp/cy-f22-number-forms-red.log`：补小数/指数表示后1 RED。最终 `/tmp/cy-f22-final-green.log`：29/29通过，包含原始Long两边界、精确整数的小数/指数写法、越界和非整数、字符串内容不被改写、重复键保持最后值、非法前导零和尾逗号不能被“修成”有效配置。上面的27/51为第三版历史结果，不能替代本次最终验收。

最终两轴复审均0项；Standards独立29项及9,180组精确整数对照一致。根相关测试53/53（/tmp/cy-f22-integrated-green.log），JS-SCOPE通过，UI-GATE-0通过（/tmp/cy-f22-ui-green.log）；旧DeepSeek工作区的F14门禁漂移不是根集成状态。真实微信双身份渲染/奖励发放仍未验证。

## 总控正式页面补验与身份边界纠正

`/tmp/chengyin-f22-shared-ui/receipt.json`：实际fetch/decorate/reward/CTA handler展示4类合法奖励（含两个大Long字符串券ID），两类unsupported任务原生点击明确提示且不新增请求；空奖励不承诺，暂停保留非操作CTA。5张截图已实际查看，固定light下文字可读。仅2次拦截GET、0写请求，finally恢复并断开。

该页面没有主办/玩家角色分支，现码固定light。此前tokens对比度不能当成真实双身份验证；本次也只证明共用页面handler与渲染，真实角色鉴权及发奖仍未验证。
