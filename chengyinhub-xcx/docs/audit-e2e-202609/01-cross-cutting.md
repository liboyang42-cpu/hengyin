# 阶段1 · 横切根基链路(身份 / 登录 / 深链 / 票源归因 / 请求层)

## 发现

### F-TS-1 [P1] 俱乐部分享落到主题页,那条链路的建单永远不带票源归因

**生产者**:`pages/club/detail/index.js:2634`

```js
path: ticketSource.buildSharePath('/pages/topic/index/index', {
```

`utils/ticket-source.js:146-149` 拼出的路径带 `id=<topicId>&sourceClubId=<club>`;落地时 `app.js:44 / :138 / :144`(`onLaunch`/`onShow` → `captureTicketSource`)确实把归因写进本地记录。

**消费者断了**:全仓只有报名表合并归因 —— `pages/activity/baoming/baoming.js:1199`

```js
Object.assign(data, ticketSource.attributionPayload(
  (that.data.activityInfo && that.data.activityInfo.topicId) || null));
```

主题页自己那条下单链路 `pages/topic/index/index.js:865-882` 完全不引用 ticket-source:

```js
data: JSON.stringify({
  ownerType: 1,
  ownerId: topicId,
  realName: realName,
  phone: phone,
  isUsePoint: 0,
  requestId: requestId
}),
```

`grep -n "ticket-source|ticketSource|captureTicketSource" pages/topic/index/index.js` → **零命中**。

复现:俱乐部详情 → 分享主题卡(路径含 `sourceClubId`)→ 好友点开落在 `/pages/topic/index/index` → 在该页直接报名付费 → `/api/registration/create` 请求体无 `sourceClubId`。

影响:后端 `RegistrationRequest.sourceClubId` 缺失即按「归平台」结算(见 `utils/ticket-source.js:5-17` 注释的口径),**俱乐部这一单的票源奖励静默为 0**,而界面与结算单上看不出任何异常。这是钱和归属算错,不是文案问题。

修法方向(留给修复批):主题页 createOrder 合并 `attributionPayload(payload.topicId)`,并补一条与 `tests/unit/tandianri-b5-role-contracts.test.js:115` 同形的主题页契约测试。

## 已核对无问题

- **身份/视角单一真源**:`utils/identity/identity-policy.js` 为唯一判据,`app.js:454-474` 三级回退与 `pages/index/index.js:36-60` 的 `goMerchantHome` 唯一出口一致;`components/tabBar` 的 MERCHANT_LIST/CONSUMER_LIST 只按 policy 切,没有第二套判法。
- **请求层 body 编码**:`utils/transport/request-client.js:39-51` 的「POST 无显式 header → urlencode」规则下,上一轮遗留的两处人工待确认项已核清:`pages/merchant/reviews`(:160)与 `components/cy/profile`(:55)均为**动态 method/url 的静态残留告警**,实际调用点显式带 `Content-Type: application/json`,不会 415。
- **小程序码入场**:`utils/game-door-entry.js`(全文 39 行)32-hex scene → `pathForScanEntry` 的映射是穷举式,未命中返回空并由调用方兜底,不存在「猜路径」。
- **ticket-source 捕获层的 fail-closed 是对的**:非法 `sourceClubId`(0/负/非数字/超长)不落归因(`:76-84`)、同 topic 冲突打墓碑且跨启动钩子保留(`:94-121`、`tests/unit/tandianri-b5-ticket-source.test.js:191-197` 已锁)。**问题只出在消费者漏接(F-TS-1),不在这一层。**

## 待真机 / 遗留

- 深链入口的真实 `scene`/`q` 参数(小程序码扫入)只能真机验证;本轮做的是「scene 串 → 路由表 → 落地页 onLoad 读取」的静态闭环。
- `getPhoneNumber` 授权链(付费、80058)按边界不动。
