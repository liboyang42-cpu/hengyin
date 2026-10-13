# 阶段4(续) · Roam 漫游 × 核销码 × IM

只审不改。与「商家端剩余子域」代理报告交叉的部分,以本文件自读证据为准。

## 发现

### F-RM-1 [P2] 据点核销码在商家首页扫码口被拒,且不告诉商家该去哪核

`utils/verification-scan.js:10-21` 的 `v1.*` 路由只认 `group_*` / `activity` / `topic`,其余一律:

```js
return invalid('暂不支持此动态码');
```

据点码的真实格式带 `citynode_` 段(`ApiVerifyController.java:153`
`VerifyDynCodeUtil.issue(poiId, "citynode_" + memberId, ...)`)，所以它必然走到这一行。
这条**是有意为之的护栏**,单测钉住了「不能把据点码误送进普通验票端点」:
`tests/unit/verification-scan.test.js:42-48`。

问题在出口没有指路。商家首页那颗扫码:

```js
// pages/merchant/index/index.js:856-860
const scan = resolveVerificationScan(res.result);
if (scan.kind === 'invalid') {
  cyToast(scan.message);
  return;
}
```

toast 2 秒后消失,商家不知道**这个码是可用的、只是要从「据点」页扫**(`pages/merchant/citynode/index.wxml:26` 的「扫码核销」→ `index.js:412 scanRedeem()` → `/api/verify/citynode/redeem`)。同一个码在两个入口给出两种结论,后者的存在性对商家不可见。

复现:玩家出据点码(`subpackageRoam/citynode-code`)→ 商家在首页点扫码 → 「暂不支持此动态码」→ 商家以为玩家码坏了。

裁决方向:护栏保留,文案补指路(如「据点码请从「据点」页核销」),或在 invalid 分支按 `citynode_` 前缀给一条可跳转的引导。**不要**把 citynode 塞进共享路由表 —— 它的归属/幂等校验在另一个端点。

### F-RM-2 [P2] `hangoutId` 是死参数:传了,落地页压根不接

生产者 `pages/roam/index.js:629`:

```js
wx.navigateTo({ url: '/subpackageRoam/nearby/index?hangoutId=' + encodeURIComponent(cur.id) });
```

消费者 `subpackageRoam/nearby/index.js:104` 的 `onLoad()` **不带 `options` 形参**,全页 `grep hangout` 只命中数据源与 kind 判定(`:152 /api/roam/hangout/nearby`、`:159 kind === 'hangout'`),没有任何一处读 id。

实际后果有限(落地页按定位列附近所有的局,目标局通常也在其中),所以定 P2:意图(「从这个局卡点进去看它附近」)在实现上被静默降为「看附近」。要么在 nearby 里定位/高亮该 id,要么把这个参数删掉。

### F-RM-3 [P2] 出码倒计时的兜底 TTL 两端分叉(60s vs 300s)

- 玩家据点码:`subpackageRoam/citynode-code/index.js:51` `that.startCountdown(Math.floor((d.ttlMs || 300000) / 1000));`
- 商家/游戏点位:`pages/merchant/game-node/index.js:569` `that.startCheckinCountdown(Math.floor((data.ttlMs || 60000) / 1000))`
- 服务端真值只有一档:`ApiVerifyController.java:45` `DYN_TTL_MS = 300_000L`,且两处响应都显式带 `ttlMs`(`:134`、`:163`)。

**今天不发作**(字段恒在),但兜底值一处对了、一处差 5 倍。一旦某个签发口漏传 `ttlMs`,game-node 会把 5 分钟的码显示成 60 秒,并在倒计时归零后自动重新出码(`citynode-code/index.js:64-70` 同形逻辑),表现为「码一直在换」而不是明确的错误。属一次性对齐常量的清理项。

## 已核对无问题(嫌疑排除)

- **`qr-citynode` 场景没有缺注册**:`utils/scene-registry.js:50` 有登记,路由 `/subpackageRoam/citynode-code/index` 也在 `app.json` 的分包页里。反向核对做了全量扫描:生产代码(排除 tests/scripts/docs)里 `openScene(...)` / `getScene(...)` / `sceneId:` 出现的 52 个已注册 id **零缺号**;注册表里 52 条 `route` 指向未注册页的也是 **0 条**。→ `components/cy/scene-route-content/index.js:162` 那句「场景配置缺失，请返回后重试」在当前代码里走不到。
- **据点出码/核销的 body 编码是对的**:`subpackageRoam/citynode-code/index.js:44` 与 `pages/merchant/citynode/index.js:424` 都是 `method: 'POST'` + 裸 `data` 无显式 header —— 命中 `request-client.js:39-51` 的 urlencoded 默认;后端两枚端点用的是普通形参 `Long poiId` / `String code`(`ApiVerifyController.java:141 / :171`,非 `@RequestBody`),**两边匹配,不会 415**。
- **出码页状态机分得干净**:`citynode-code/index.js:13-14` 把 `missing` 与 `error` 分家,注释说明理由(「缺参不可能靠重试变出参数,给它重试就是假按钮」),缺参态唯一出口 `onMissingBack():31-34`,卸载清定时器 `:37`,`successStatusAbnormal` 与 `fail` 各有文案。
- **IM 长轮询生命周期正确**:`subpackageB/pages/im/chat/index.js:558-560`
  ```js
  onShow() { this.startPoll(); },
  onHide() { this.stopPoll(); },
  onUnload() { this.stopPoll(); if (this._routeTimer) clearTimeout(this._routeTimer); },
  ```
  且 `:565` 注释显示「只在 onLoad 成立不够」这条已被想过。列表页 `im/list/index.js:40 onShow` 走一次性拉取,无遗留定时器。
- **据点核销的权限前置**:`pages/merchant/citynode/index.js:413-415` 先判 `merchantAccess.canVerify` 再拉相机;后端 `ApiVerifyController.java:174-175` 另有一次 `merchantAccessService.require(..., MerchantPermission.VERIFY)`,前端绕过也有闸。

## 待真机 / 遗留

- 5 类码(团码 / 活动票 / 主题票 / 券 / 据点码)的**真实相机扫码**只能真机验;本轮做的是「码串格式 → 路由函数 → 端点参数形状」的静态闭环。
- `game-node` 的「提交码」生产者是否存在,由商家端子域报告独立给结论,合并进 findings 时以其 + 我复核后的口径为准。
