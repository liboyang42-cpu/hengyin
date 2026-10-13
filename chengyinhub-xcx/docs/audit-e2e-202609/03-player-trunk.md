# 阶段3 · 玩家端主干(报名→支付→票券→提现)

只审不改。每条给 文件:行号 + 复现路径。代理报告里未复现的断言集中在文末「已否决」。

## 发现

### F-PL-1 [P1] 余票为 NULL 的票种:界面说「余票待确认」,点进去说「已售罄」并硬拦报名

`components/cy/scene-play-activity-detail/index.js:53-56`

```js
_inventoryText: remaining === null ? '余票待确认' : remaining > 0 ? `剩余 ${remaining} 张` : '已售罄',
_available: remaining !== null && remaining > 0,
```

同文件 `:281-284` 的报名入口:

```js
if (!ticket || ticket._available === false) { toast('该票种已售罄'); return }
```

`_available` 在 `remaining === null` 时被算成 `false`,于是「余票待确认」这一档**既显示未知、又按售罄拦截**,并给出一个与显示相矛盾的理由(「已售罄」)。

库存字段允许为 NULL,已核:

- `sql/base_schema_20260818.sql:3700` — `remaining_inventory int DEFAULT NULL`
- `chengyinhub-admin/src/test/resources/it-schema/explore-alliance-it-schema.sql:164` — 同口径

复现:活动详情(场景 `play-activity-detail`)→ 票种卡显示「余票待确认」→ 点「立即报名」→ toast「该票种已售罄」,进不了报名表。

影响:商家未填/未回填库存的票种,玩家侧被一条**假原因**挡死。属于「结果说谎」+「流程走不通」双料。

### F-PL-2 [P2] 截止校验只在详情组件里,深链直入 baoming 会填完整张表才被后端拒

截止这道闸只存在于上游:

`components/cy/scene-play-activity-detail/index.js:285-288`

```js
const end = ticket.endTime ? toTimestamp(String(ticket.endTime)) : NaN
if (!Number.isNaN(end) && Date.now() > end) { toast('该票种已截止报名'); return }
wx.navigateTo({ url: `/pages/activity/baoming/baoming?activityId=...&ticketId=${ticket.id}` })
```

`pages/activity/baoming/baoming.js` 全文没有截止分支:`grep -n "endTime|截止|deadline"` 只命中候补计时器(`:796-798`),`:639-642` 只是把 `startDate` 格式化后展示。

复现:从分享/深链/历史入口带 `activityId&ticketId` 直接进 baoming(票种已截止)→ 表单照常可填可提交 → 后端拒。

影响:浪费一次填写,不产生错误数据(后端是唯一真源)。定 P2:补的是「早一步告知」,不是「拦住」。

## 已核对无问题(嫌疑排除)

- **`subpackageMember/coupon-qr` 轮询生命周期**:`onShow` 有 `if (!this.data.couponHistoryId) return` 前置守卫(`index.js:38-40`),`onHide`/`onUnload` 均 `clearTimers()`(`:47-48`),`startPolling` 自带 `if (this._pollTimer) clearInterval` 去重(`:153`),`useStatus == 1` 时自停(`:156`)。「切回前台无脑起轮询」的说法不成立。
- **`utils/scene-entry.js:24` 一律 `wx.navigateTo`**:注册表里确有 2 个 route 落在 tabBar 页(`pages/roam/index`、`pages/member/index/index`),但站内入口全部走宿主自己的 `sceneStack.push`(见 `components/cy/profile/index.js:1345-1355` 等),**跨页 `openScene` 的现存调用点只有 `play-activity-detail` 与 `roam-poi-detail` 两个非 tabBar 目标**(`pages/searchmap/index.js:363,966`、`pages/index/index.js:974`、`subpackageA/pages/myproject/index.js:294`、`pages/play/finish-route-recommendation.js:44`)。当前不可达,记为**潜在**项:若以后把 tabBar 宿主的场景 id 交给 `openScene`,必然固定 toast「场景暂时打不开」。
- **baoming 的 unknown 逃生**:`baoming.js:1550-1575` 两条路都给结论(`onPayUnknown` 弹面板 + `!submitted` 分支按 `getState()==='unknown'` 重述),`showCancel:false` 的 modal 有确认键可关。这条是 `01-unknown-state.md` 里点名的**正确先例**,复查成立。
- **searchmap 价格上限 1000**:`minPrice/maxPrice` 默认、重置、入参兜底三处都是 0..1000(`pages/searchmap/index.js:105-106,126-127,918-919,1070-1071`),滑杆 `priceRange.max=1000`(`:109-112`)且输入会被夹到该区间(`:797-798`),页面上明写「价格区间:¥0 - ¥1000」(`index.wxml:199-201`)。入参兜底不是特例,UI 也如实披露。不是缺陷。

## 已否决(代理报告对不上磁盘,逐条留痕)

| 断言 | 实情 |
|---|---|
| `subpackageMember/myinvite/myinvite.js:133-147` 邀请链路参数丢 | 该文件只有 26 行,行号越界 |
| `tests/unit/scene-param-lost.test.js` 存在且红 | 文件不存在 |
| `utils/ticket-source.js:194-196 / 216-221`、`utils/game-door-entry.js:42` | 均在 EOF 之外(全文分别 165 / 39 行) |
| `utils/withdrawal-preflight.js:26` 缺 header → 415 | 四处调用全部显式带 `'Content-Type': 'application/json'`(`:40-125` 逐条已读) |
