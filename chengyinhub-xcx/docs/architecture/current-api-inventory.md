# 城瘾小程序 接口与现状基线清单 (Phase 0)

> 本文档由 6 个只读测绘 agent 的结构化结果汇总而成，作为 Phase 1 重构前的现状基线。所有 `file:line` 证据均保留，作为重构验收对照。

## 1. 请求/认证模型

### 1.1 GET vs POST 何时带 token

- 统一请求入口 `sendRequest(param, customSiteUrl)`：最终 URL = `(customSiteUrl || globalData.siteBaseUrl) + param.url`；默认 `method=GET`。（app.js:400-428）
- **GET 请求永远不带 Authorization 头**：`sendRequest` 仅在 `method==post` 时构造带 Authorization 的默认 header；GET 走 else 分支用 `{'content-type':'application/json'}`，不含任何 token。（app.js:411-428）
- **POST 请求只有在调用方未自带 header 时才注入 Authorization**：`header = param.header` 优先；仅当 `param.header` 为空(falsy)时才用默认 `header={content-type: application/x-www-form-urlencoded;, Authorization: getAuthorization()}`。调用方一旦自定义 header 就会丢失 token，除非自己加。（app.js:403,413-418）
- Authorization 取值来自 `globalData.authorization`（`getAuthorization`），原样作为头值，无 `Bearer` 前缀拼接。启动时从 storage `'authorization'` 恢复；它由登录接口返回的 `res.data.token` 写入。**注意 token 在 `res.data.token` 顶层，而 openId/id 等在 `res.data.data` 内**。（app.js:338-340,83-85,176-177,287-292,416）

### 1.2 401 重登重试

- **静默重登触发条件**：HTTP `statusCode==401` 或 业务 `res.data.code==2`，二者任一即 `needReLogin`。（app.js:430-431）
- **防死循环靠 `param._retried` 标志**：首次触发置 `param._retried=true`，`reLogin` 成功(ok)后用同一 param 递归调用 `sendRequest` 重试一次；因 `_retried` 已为 true，第二次即使再遇 401/code==2 也不会再重登，而是落入后续状态码/业务错误分支。**仅重试 1 次**。（app.js:432-447）
- **reLogin 失败(ok=false)时**：`hideToast`，若 `param.fail` 是函数则回调 `param.fail(res.data)`（传的是首次失败响应），否则弹 toast `'登录已过期，请重新进入'`。**注意此分支不触发 `param.complete`**。（app.js:438-444）
- **reLogin 没有任何单航班锁(single-flight)**：多个并发请求同时收到 401/code==2 时会各自把自己的 `param._retried` 置真并各自发起一次 `wx.login + /api/login/code`，造成 N 个并发重登、互相覆盖 token、最后落地的 token 不确定。全仓库搜索 `reLogin/inFlight/flight` 仅命中 app.js，确认无锁实现。（app.js:432-446）

### 1.3 reLogin 与 firstLogin 重复

- **两者高度重复**：都做 `wx.login → POST /api/login/code`(urlencoded, data:{code}) → 成功时调用同一组 `setOpenID/setAuthorization/setUserID/setUserType/setAvatar/setNickname/setUserRole`。（app.js:152-211,223-251）
- **差异**：
  - `firstLogin` 返回 Promise；有本地缓存短路(open_id+authorization 存在直接 resolve)；开发环境 `useDevUser` 兜底；登录失败 toast。
  - `reLogin` 走 callback(ok) 风格；无 dev 兜底；无缓存短路；失败仅回调 false。
- `setAuthorization` 的 token 取值疑似 bug：登录成功分支用 `res.data.token`，而其余字段用 `res.data.data.xxx`。若后端把 token 放在 `data.data` 下，`authorization` 会被写成 undefined。`reLogin` 分支沿用同一写法。（app.js:175-181, app.js:233-240）

### 1.4 上传与错误 toast

- `uploadFile` 两处(chooseImage / chooseDocument)均携带 `Authorization: Bearer <token>`；两者 url 同为 `/api/common/uploadOSS`，`name='file'`。
- **loading 卡死风险**：两套上传都先 `showToast loading`(duration 10000)，仅在'全部成功'或'单个失败'时 `hideToast`。`uploadFile` 既无 complete 回调也无总数级错误聚合 —— 多文件部分成功部分失败时，失败分支会 `hideToast` 但成功计数永远到不了 `tempFilePaths.length`，callback 不会触发；多个失败会多次 `hideToast`。任一文件失败即 `hideToast`，可能在其它文件仍在上传时过早隐藏 loading。（app.js:537-570,607-650）
- `sendRequest` 的 complete 分支有**死代码**：`param.hideLoading` 单独成行(求值后丢弃，无副作用)，疑似漏写 `wx.hideLoading()`。complete 仍会调用 `param.complete`。（app.js:476-479）
- **自动错误 toast 策略**：`shouldAutoToastRequestError` 在 `param.silentError` 或 `param.autoErrorToast===false` 时返回 false；否则当 `res.code` 存在且 `!=200` 时弹 `getRequestErrorMessage` 文案。即业务 `code!=200`(除被重登拦截的 code==2)默认会自动弹 toast。（app.js:373-378,459-464）

## 2. 会话状态与 app ready 语义

### 2.1 七个会话字段与 Storage key

- 会话快照由 **7 个字段**组成，onLaunch 时从对应 Storage key 同名读回 `globalData`：`user_type←'user_type'`、`user_id←'user_id'`、`authorization←'authorization'`、`open_id←'open_id'`、`avatar←'avatar'`、`nickname←'nickname'`、`role←'role'`(RBAC 角色，前端身份单一真源)。（app.js:75-103）
- `globalData` 在 onLaunch 内被重新整体赋值(覆盖文件底部 line 260-270 的同名静态声明)，除会话字段外还含 `siteBaseUrl`、`statusBarHeight`、`navBarHeight`、`menuButtonInfo`。**`siteBaseUrl` 硬编码为 `'https://chengyinhub.shapps.cn/prod-api'`(line 44)**，与 MEMORY 记录的真实线上 `www.chengyinhub.com` 不一致，且 localhost 那行注释也错标成'正式'。（app.js:43-64）

### 2.2 getter/setter 与双写

- **七组 getter/setter**：`setAuthorization/getAuthorization`、`setUserID/getUserID`、`setOpenID/getOpenID`、`setUserType/getUserType`、`setAvatar/getAvatar`、`setNickname/getNickname`、`setUserRole/getUserRole`。每个 setter 都**双写**：先写 globalData，再调 `setStorage` 写同名 Storage key(`setUserRole` 额外带 `!role` 守卫)。（app.js:287-328 六组；app.js:510-525 role 组；app.js:271-286 setStorage）
- `getUserRole` 是唯一带**三级回退**的 getter：`globalData.role → wx.getStorageSync('role')`(命中即回填 globalData)`→ 按 user_type 兜底`(==2 返回 `'merchant'`，否则 `'player'`)。其余 getter 仅直读 globalData。（app.js:510-519 vs app.js:329-346）

### 2.3 假 ready 风险

- **存在假 ready 缺陷**：正式版(非 develop 环境)登录失败时，`firstLogin` 仍无条件 `resolve()`。`login/code` 请求 success 但 `res.data.data` 为空走 else 分支只弹 toast 后 resolve(line 185-188)；request fail 分支非 dev 也只弹 toast 后 resolve(line 194-197)；`wx.login` 本身 fail 同样 resolve(line 205-207)。**三条失败路径都不 reject**。（app.js:182-208）
- `firstLogin` 的 resolve 会沿 `appInitial→appReadyPromise.then` 链一路把 `isAppReady` 置为 true。因此正式版登录失败后 `isAppReady` 仍变 true、`appReadyPromise` 仍 resolve，而 `authorization/open_id` 等却是空字符串，形成 **'已 ready 但无会话'的假 ready**。（app.js:111-117, app.js:122-131, app.js:151-211）
- `setAuthorization` 的 token 取值疑似 bug(`res.data.token` vs 其余 `res.data.data.xxx`)：若 token 实际在 `data.data` 下，会被写成 undefined，但 firstLogin 依旧 resolve，同样导致假 ready(有 open_id 无有效 token)。（app.js:175-181, app.js:233-240）
- `waitForAppReady` 消费者预期：同步快路径——若 `isAppReady` 已 true 直接返回 `Promise.resolve()`，否则返回 `appReadyPromise`。它从不 reject(正常登录失败不 reject)。（app.js:253-258）
- **全仓唯一消费者**是首页 `pages/index/index.js`：onLoad 里 `app.waitForAppReady().then(...)` 后才 `setData({isAppReady:true})`、`loadPageData()`、`getFuzzyLocation()`；catch 弹'加载失败'。首页把 'app ready' 当作'会话已就绪、可发带 token 请求'的信号——但因假 ready，登录失败时首页仍会进入 then 分支去加载数据。（pages/index/index.js:130-141, pages/index/index.js:423）
- **会话写入的第二处来源**：sendRequest 在 HTTP 401 或 code==2 时调 reLogin 静默重登，复用 `/api/login/code` 换 token 并回写 7 字段(成功 callback(true))。这条链与 onLaunch 的 firstLogin 各写一套会话。（app.js:430-447, app.js:223-251）
- `isDevEnv` 仅在 `envVersion==='develop'` 时返回 true，体验版/正式版返回 false，从而 `useDevUser` mock 兜底(写入 dev-token 等)只在开发版触发。（app.js:6-12, app.js:212-221）

## 3. 接口清单

### 3.1 传输层格式约定

- POST 默认 `content-type=application/x-www-form-urlencoded`(非 JSON body)，后端按表单解析。
- 响应判错统一看 `res.statusCode!=200` 与 `body.code`：`code==2` 视作登录失效，`code==200 / data.code==200` 视作成功。（app.js:414-415,431,449,548-549）
- 生产基址 `siteBaseUrl='https://chengyinhub.shapps.cn/prod-api'`(localhost:8080 注释掉)；静态资源 `baseImgUrl='https://chengyinhub.shapps.cn/prod-api/profile/'`。`utils/config.js` 只导出 `baseImgUrl/amapWebKey`，不含 siteBaseUrl。（app.js:43-45, utils/config.js:2-10）
- 全部 93 个 `/api/*` 端点分布在 17 个业务域。`/api/user/info` 为全站最频繁端点(20 次)。

### 3.2 按业务域分组

| 业务域 | 典型方法 | 是否认证 | 备注 / 关键端点 |
|---|---|---|---|
| login | POST | 否(登录前无 token) | `/api/login/code`(换 token，显式 urlencoded)、`/api/getwxbindphone`、`/api/logout`；login/code 直接用 `wx.request` 而非 sendRequest（app.js:165-166,228-229 / index.js:937-938,1012-1013 / shezhi.js:116-117） |
| common | POST | 否(多为公共展示) | `/api/common/banner`、`/api/common/dict`、`/api/common/infomation_list`、`/api/common/infomation_detail`、`/api/category/list`（index.js:1434-1435 / publish/temp/index.js:152-153 / subpackageA infomation.js:71-72 / search2/index.js:437-438） |
| 上传 | wx.uploadFile | 是 | `/api/common/uploadOSS`，`name='file'`，返回 `data.code==200 + data.url`；独立传输路径，显式携带 Bearer token。 |
| user | POST | 是 | `/api/user/info`(×20 最频繁)、`/update`、`/list`、`/setInviter`、`/invite_list`、`/statistics_chat`、`/follow/action`；address `{list,info,action,setDefault,delete}`；balance `{list,statistics}`；points `{list,statistics}`；income `{breakdown,topic-stats,topic-list,invite-stats,brand-stats,brand-list}`（app.js:413-417） |
| topic | POST | 是 | `/api/topic/list`、`list-to-merchant`、`info-to-user`、`info-to-merchant`、`create`、`delete`、`like`、`like_list`、`update_user_status`（subpackageA project/index.js:1059-1060 / index.js:612,1163 / merchantjoin.js:48-49） |
| activity | POST | 是 | `/api/activity/list`、`info`、`like`、`like_list`、`publish`、`update_publish_status`、`delete`（subpackageA project/index.js:1101-1102 / searchmap/index.js:99-100 / index.js:618,646） |
| template | POST | 是 | `/api/template/list`、`info`、`my-list`、`myinfo`、`homeData`、`publish`、`update-publish-status`、`updateLibraryStatus`、`delete` |
| registration | POST | 是 | 用户侧 `/api/registration/{list,info,create,cancel,pay,join_info,verify_answer,scan_qr_code,payment-readiness}`；活动报名和自玩通行证在现金应付时读取 readiness，服务端创建收费订单前再次拦截；商家侧 `/api/registration/merchant/{list,info,create,cancel,select}`；scan_qr_code+verify_answer 为扫码核销链路 |
| merchant | POST | 是 | `/api/merchant/info`(×4)、`update`、`dashboard`、`merchant_registration`、`/api/merchant/orders/ledger`（商家维度强制隔离；支持今日/本月/全部、待处理/已完成/售后、服务端分页及汇总）、`/api/merchant/settlements/ledger`（结算批次分页、待结算/已结算/退款冲销/净额，只读） |
| club | POST | 是 | `/api/club/list`、`join`、`apply`、`my-apply`、`members`、`remove-member` |
| coupon | POST | 是 | `/api/coupon/mypublishlist`、`myrecvlist`、`publish` |
| withdrawal | POST | 是 | `/api/withdrawal/list`、`create` |
| creativesquare | POST | 是 | `/api/creativesquare/list`、`info`、`like`、`action`、`report`(Strava 化动态)（square/list/index.js:700-701） |
| comment | POST | 是 | `/api/comment/add`(×4)、`list`、`like`、`report` |
| play | GET / POST | 是 | `/api/play/nodes`(**GET — 全仓唯一显式 method:'GET' 业务端点，故不自动带 Authorization**)、`/api/play/checkin`(POST) |
| points | POST | 是 | `/api/points/list`、`result_list` |
| im | POST | 是 | `/api/im/{conversations,messages,send,read,unread-total,start,report,block,delete}`(messages/read 各 ×3) |
| analytics | POST | 是但容错 | `/api/analytics/events/batch`(批量事件)；走 `app.sendRequest` 自动鉴权且静默失败，不传稳定用户标识；仅上报非敏感短标量 properties，后端会拒绝敏感字段（utils/analytics.js:81-132） |
| 身份/成长 | POST | 是 | `/api/role/info`、`/api/creator/center`、`/api/creator/apply`、`/api/growth/center`、`/api/world/home`(世界地图首页)（role/center/index.js:236 / subpackageP3 world/index.js:25-26） |
| betagame | POST | 是 | `/api/betagame/list`、`/api/betagame/vote`(toupiao 投票页)（toupiao.js:29-30） |

### 3.3 免认证公共接口(登录前可调用)

- `/api/login/code`(换 token，登录前无 Authorization)
- `/api/common/banner`、`/api/common/dict`、`/api/common/infomation_list`、`/api/common/infomation_detail`
- `/api/category/list`
- `/api/common/uploadOSS`(上传，显式携带 Authorization)

## 4. 认证旁路与埋点并发缺陷

### 4.1 直连 wx.request 绕过 app.sendRequest 的文件

> 真正绕过 sendRequest 直连 `wx.request` 的 HTTP 调用只有 **4 处**：index.js 两处 + app.js 两处。任务点名的四个结算/报名/订单页命中 grep 是因为 `wx.requestPayment`(微信支付 SDK，非 HTTP 后端请求，消费后端预签名 payParams，本质无法走 sendRequest)，并非认证旁路。（grep `wx.request(` 仅命中 index.js:1011 与 index.js:1563）

| # | 文件:行 | 类型 | 为何绕过 / 认证方式 |
|---|---|---|---|
| 1 | app.js:164-181 | raw wx.request `/api/login/code` (onLaunch 引导登录) | 拿到 Authorization 之前的引导请求；不带 Authorization，仅 POST code，响应 `setAuthorization(res.data.token)` |
| 2 | app.js:227-240 | raw wx.request `/api/login/code` (reLogin 静默重登) | 同上，换 token 逻辑复制粘贴；`setAuthorization(r.data.token)` |
| 3 | pages/index/index.js:1011-1025 | raw wx.request `/api/login/code` (firstLogin) | 取得认证之前的引导请求；`app.setAuthorization(res.data.token)`，token 取 `res.data.token` 而 openId/id 取 `res.data.data.*`(双层 data) |
| 4 | pages/index/index.js:1846-1858 | `app.sendRequest('/api/map/reverse-geocode')` | 逆地理编码由后端代理，腾讯位置服务 Key 仅从 `CHENGYIN_TENCENT_MAP_KEY` 注入，不下发到小程序。 |

> 说明：上述三处 `/api/login/code` (app.js:165、app.js:228、index.js:1011) 是同一段换 token 逻辑的复制粘贴，均在'拿到 Authorization 之前'故无法走 sendRequest。绕过 sendRequest 的 raw 调用同时丢失了 401/code==2 自动重登能力(app.js:413-417 post header 注入；app.js:431-446 needReLogin 重试)。

补充离群点：`baoming.js:628-634` 走 `app.sendRequest`(已自动注 Authorization)，却又手动传 header 重复塞 `'Authorization': app.getAuthorization()`。功能非 bug，但属 Phase 1 要统一掉的重复拼装。四个支付页下单(产出 payParams)那一步均走 sendRequest 正常收口：`baoming /api/registration/create`(baoming.js:628)、`order /api/registration/pay`(order.js:342)、`settlement/topic-settlement` 取 `res.data.payParams`(settlement.js:527)。

### 4.2 analytics flush 并发缺陷

- **flush 快照-回写竞态确认**：`flush()` 在 114 行同步抓取 queue 快照，116 行 `batch=queue.slice(0,50)`，投递成功后 119 行 `setQueue(queue.slice(batch.length))`。回写用的是闭包捕获的旧 queue 快照，而非'投递完成时的最新队列'。（analytics.js:113-122）
- **丢失新事件**：`track()` 每次 push 后立即 `flush()`(133 行)。若 flushA 在途时 track 又 push 事件 E 并触发 flushB，flushA 成功回调执行 `setQueue(queueA.slice(batch.length))` 时 queueA 是旧快照不含 E，这次 `setQueue` 整盘覆盖 storage，把 E 抹掉 → **E 永久丢失**。（analytics.js:124-134, analytics.js:119, setQueue 实现 analytics.js:45-49）
- **重复投递**：两个并发 flush 各自抓取的快照前缀相同(都从队头 `slice(0,50)`)，flushA 与 flushB 把同一批头部事件各投一次 → 同一 `idempotencyKey` 的事件被 POST 两遍。前端无去重，仅靠后端 idempotencyKey 兜底。（analytics.js:116, analytics.js:74）
- **根因**：flush 无'投递中'锁/无原子读改写。`getQueue/setQueue` 各自是同步 storage 操作，但 flush 跨越异步 `postBatch` 把 read 和 write 分到了 `wx.request` 前后，中间窗口任何 track 的 push 都会被成功回调的快照回写吞掉。（analytics.js:37-49, analytics.js:113-122）

## 5. 身份解释现状

> 任务点名的 `utils/roleGuard.js` 在本仓库不存在。全仓 find 无任何 `*roleGuard*` / `*guard*` 文件(node_modules 除外)，`utils/` 下只有 `analytics.js / config.js / mockData.js / theme.js`。身份解释逻辑实际散落在 app.js 与各页面，**没有集中守卫层**。

### 5.1 两套并行身份字段

- 前端存在两套语义重叠字段：`user_type`(1 用户/2 商家，数字)与 `role`(player/club/merchant，字符串)。app.js 注释明确 role 仅作 user_type 之上表达'俱乐部'的更细粒度补充，user_type 双身份机制保留不动，二者非单一真源。（app.js:99-103, app.js:508-518）
- `getUserRole()` 三级回退：`globalData.role → storage 'role' → user_type==2?'merchant':'player'`。兜底分支把 user_type 当 role 来源，二者在读路径上互相混判；club 角色一旦 globalData/storage 未回填就会被兜底误判成 player。（app.js:510-518）

### 5.2 debug_user_view 散落混判点

| 点 | 位置 | 判定依据 | 问题 |
|---|---|---|---|
| 首页 static 分流 | pages/index/index.js:6-8,:42 | `isMerchantView()` = storage `user_type==2 && debug_user_view!=='user'`；data 初始化/onLoad/onShow 三处共享唯一真源 | debug 标志直接参与真实身份分流 |
| 首页 user/info 兜底重定向 | pages/index/index.js:376,:383-385 | 仅当 `debug_user_view!=='user'` 才拉 `/api/user/info` 并在 `userType==2` 时 redirect 到商户首页 | debug 标志门控服务端身份兜底覆盖 |
| 设置页视角 | pages/shezhi/shezhi.js:21-30 | `app.getUserRole()==='merchant' && debug_user_view!=='user'`(基于 role 字符串) | 与首页基于 user_type 数字的同名判定**用了不同真源(role vs user_type)**，存在不一致风险 |

### 5.3 debug 写入污染真实身份

- `shezhi.switchIdentity()` 切视角时**同时写 debug_user_view 和 `wx.setStorageSync('user_type', 1/2)`**，即调试切视角直接覆盖了作为真实身份缓存的 user_type storage 值，而非只设独立 override 标志。（pages/shezhi/shezhi.js:80-92）
- 污染会传播：被改写的 user_type storage 在下次 app 启动被 app.js:75-76 读回 `globalData.user_type`；`getUserRole()` 兜底分支(app.js:517-518)据此推角色；merchant 首页 onLoad(merchant/index/index.js:32)直接读 storage user_type 决定放行。debug 写入与真实会话字段同源，无法区分。（app.js:75-76, app.js:517-518, pages/merchant/index/index.js:32）
- `switchIdentity` 仅在 `canSwitchIdentity`(envVersion==='develop')时允许，生产点击会 tips 拒绝。但 setData 的 user_type/debug_user_view 写入本身**没有环境护栏**，护栏只在 UI 入口层。（pages/shezhi/shezhi.js:80-84,:24-26; pages/merchant/index/index.js:25）

### 5.4 散落直接比较 user_type 命中点

- **商户首页准入(硬门禁)**：onLoad 读 storage `user_type!=2` 即 redirect 回首页；onShow 拉 user/info 后 `res.data.userType!=2` 则 setUserType 降级并 redirect。（pages/merchant/index/index.js:32-34,:79-82）
- **业务动作禁止**：活动报名 `bmClick` 用 `app.getUserType()==2` 拦截'商家不可报名'；主题页同款判断。（pages/activity/detail/index.js:146, pages/topic/index/index.js:564）
- **首页内部**：line 668 用 `this.data.userInfo.userType==2` 决定 banner 跳商户报名页；line 1436 用 `app.getUserType()==2` 决定 linkType 参数；line 1020 `setUserType(res.data.data.userType)` 回写。`userInfo.userType`(接口返回)与 storage user_type 是第三个并存来源。（pages/index/index.js:668,:1436,:1020）

### 5.5 role 服务端回填散落点 + 清理散落

- role 回填散落多点各自调 `setUserRole`：登录(app.js:181)、静默重登(app.js:240)、设置页 user/info 回调(shezhi.js:137)、会员页(member/index/index.js:391)、role/center 用 `/api/role/info` 单独取 role(role/center/index.js:85)。**无统一拉取入口**。
- 退出登录清理在 `shezhi.logout` 内联手写：globalData.user_type/role 置空 + `wx.clearStorageSync()`(连带清掉 debug_user_view/role/user_type)。**app.js 无对应集中 clearSession 方法**。（pages/shezhi/shezhi.js:95-124, 特别是:107,:110-111）

## 6. 位置与隐私基线 (Phase 4 预留)

### 6.1 隐私声明现状

- `app.json` 声明 `requiredPrivateInfos` 仅含 `choosePoi` 与 `getFuzzyLocation` 两个接口，**未声明**精确定位 `getLocation/chooseLocation/onLocationChange`。（app.json:105-108）
- `permission` 仅声明 `scope.userFuzzyLocation`(模糊位置)，desc 为发布内容时填城市/附近地点；**未声明** `scope.userLocation` 精确位置的 desc。（app.json:109-113）

### 6.2 全仓 11 个 location 调用点

- `getFuzzyLocation` 3 处：pages/index 1513&1519、components/touch 114(首页城市/附近推荐 + 发布组件填地点)。
- `choosePoi` 7 处：components/touch 180、square/list 417、merchantapply1 365、publish/activity 699、publish/fabu 1534&2108、shangjiaruzhu 545(发布选址/集合点/商家入驻地址)。
- `getLocation` 精确 1 处：searchmap 183(地图页移动到当前位置)。（pages/index/index.js:1519, pages/searchmap/index.js:183）

### 6.3 声明缺口与死代码

- **声明与实现不一致**：searchmap 用 `wx.getLocation`(精确 gcj02)，但 app.json 未在 `requiredPrivateInfos` 声明 getLocation、也未声明 `scope.userLocation` 的 desc，属隐私接口声明缺口(新规下可能直接失败/审核风险)。（pages/searchmap/index.js:183-184, app.json:105-108）
- 该精确 getLocation 调用点 `moveToLocation` 当前**不可达**：唯一触发它的按钮在 WXML 中被注释掉，onLoad/onShow 均不调用，仅 onLoad 调 getList。事实上的死代码，但仍是声明缺口隐患。（pages/searchmap/index.wxml:15, pages/searchmap/index.js:573-592）

### 6.4 授权闭环与无后台采集

- 首页定位走标准授权闭环：`getSetting` 检查 userFuzzyLocation/userLocation → 未授权才 authorize → 失败 fallback 到 userLocation → 仍失败弹一次 showModal 引导，不循环不反复弹。（pages/index/index.js:1448-1505）
- choosePoi 失败处理统一：errMsg 含 cancel 静默 return；含 auth deny/permission 调 `showLocationPermissionGuide` 弹一次，无重试循环。`showLocationPermissionGuide` 在多页重复定义(touch/square/list/publish.activity/publish.fabu/shangjiaruzhu)，实现一致。（components/touch/index.js:200-209,218-229）
- **无后台/持续位置采集**：全仓无 `onLocationChange/startLocationUpdate/startLocationUpdateBackground`，app.json 无 `requiredBackgroundModes location`；所有定位均一次性、用户动作触发。（app.json:105-108, pages/index/index.js:130-133）
- `getFuzzyLocation` 成功后将经纬度回传 `/api/activity/list` 与逆地理编码 amap regeo(仅 fuzzy 精度坐标)，坐标 `toFixed(6)` 入 setData；touch 组件回退文案用 `toFixed(4)`。（pages/index/index.js:1533-1542, components/touch/index.js:117-128）

## 7. Phase 1 不可破坏契约

> 汇总所有测绘 agent 的 preserve 条目(去重)，作为重构验收基准。

### 7.1 请求/认证/重试

1. **GET 绝不携带 Authorization**；POST 在调用方未自定义 header 时默认携带 `Authorization=getAuthorization()`，且调用方传入的 `param.header` 必须原样优先生效(不被覆盖)。
2. 请求 URL 拼接规则保持：最终 URL = `(customSiteUrl 传入则用之，否则 globalData.siteBaseUrl) + param.url`；`param.url` 始终以 `/api/...` 开头。重构后任何页面传入的 url 字符串语义不能变。
3. HTTP 方法默认值保持：缺省即 GET；只有显式 POST 才走表单+Authorization 注入路径。`/api/play/nodes` 必须仍以 GET 发出(否则后端路由不匹配)。
4. POST 默认请求头契约保持：`content-type='application/x-www-form-urlencoded;'` 且 `Authorization=getAuthorization()` 原样字符串(**无 Bearer 前缀**)。
5. 登录态失效判定保持 `statusCode==401 || res.data.code==2` 两条触发条件等价。
6. 静默重登后自动重试且最多只重试一次；重试用同一 param 对象、同一 customSiteUrl，业务回调(success/fail)对调用方透明(无感知重登)；该自愈语义对所有业务页透明。
7. 重登失败时：若调用方提供 `param.fail` 则回调并传首次失败响应体，否则弹'登录已过期'类 toast；不得在此情况下再触发 success。
8. 重登成功后 token 必须实际刷新到 globalData 与 storage 后再发重试请求(重试用新 token)。
9. Authorization 取值链保持：`getAuthorization()` 读 `globalData.authorization`，其值来自 `/api/login/code` 的 `res.data.token` 经 `setAuthorization` 落 globalData+本地存储；不得改变 token 落地字段名 `'authorization'` 或注入位置。
10. 回调契约保持：`success/fail/complete/successStatusAbnormal` 四类回调签名与触发时机不变；自动错误 toast 行为对未自定义 fail 的调用方保持。
11. 自动错误 toast 开关语义保持：`param.silentError` 或 `param.autoErrorToast===false` 抑制；否则 code 存在且 `!=200` 自动弹错误文案。
12. 响应判错口径保持：`statusCode!=200` 视为异常；上传成功判 `data.code==200`；登录失效判 `body.code==2`。

### 7.2 会话状态与 ready

13. 七个会话字段与 Storage key 同名映射保持：`user_type/user_id/authorization/open_id/avatar/nickname/role` 各对应同名 key，onLaunch 从这 7 个 key 恢复 globalData(app.js:75-103)。
14. 登录(首登与重登)写入的会话字段集合一致：`open_id、authorization(=token)、user_id、user_type、avatar、nickname、role` 七项；token 取自响应顶层 `token`、其余取自 `data`。
15. 七组 getter/setter 公开签名与双写语义保持：每个 setter 同时更新 globalData 与对应 Storage key；六个直读 getter 仍可被各页面以现有名字调用。
16. `getUserRole` 三级回退顺序保持：`globalData.role → storage 'role'(命中回填) → user_type==2?'merchant':'player'`；`setUserRole` 的 `!role` 空值守卫保持。`getUserType()` 返回 `globalData.user_type` 不变。重构收拢会话后这两个函数对同样底层状态须产出相同结果。
17. `firstLogin` 的本地缓存短路(已有 open_id+authorization 时直接成功不发网络登录)与开发环境 `useDevUser` 兜底行为保持不变。
18. `waitForAppReady` 契约保持：isAppReady 已就绪时返回已 resolve 的 Promise(同步快路径)，否则返回与 onLaunch 同一个 `appReadyPromise`；返回值必须是 thenable 供首页 `.then` 链使用。
19. `isAppReady` 在初始化链完成后被置 true 这一可观察状态保持(首页依赖其做 setData 与首屏加载门控)；**若 Phase 1 修复假 ready，需作为单独行为变更显式标注，而非重构副作用**。
20. `isDevEnv` 门控保持：mock 兜底(`useDevUser`)只在 `envVersion==='develop'` 生效，体验版/正式版登录失败必须暴露真实失败(弹 toast)，不得静默注入 dev 会话。

### 7.3 上传

21. `uploadFile` 上传地址 `/api/common/uploadOSS`、字段名 `name='file'`、chooseDocument 的 `formData{fileType,fileName}` 保持不变；成功响应判定 `data.code==200`、URL 取 `data.url` 保持不变。
22. chooseImage 返回字符串 URL 数组、chooseDocument 返回 `{url,filename,size,type}` 对象数组的回调数据形状保持不变。
23. 多文件上传需全部成功后才回调 `callback`(传完整结果数组)的契约保持(且修复后不得在中途泄漏/卡死 loading)。
24. uploadOSS 独立传输路径保持：走 `wx.uploadFile`，不经 sendRequest，重构会话层不应强制其复用 JSON 通道或破坏 multipart 上传与现有 Bearer token 行为。

### 7.4 接口端点

25. 全部 93 个 `/api/*` 端点路径字符串保持不变(分布在 17 个业务域)，重构只动会话/传输的承载方式，不动任何 url 常量。
26. 公共/免认证接口在登录前可调用的能力保持：`/api/login/code`、`/api/common/banner`、`/api/common/dict`、`/api/category/list`、`/api/common/infomation_list/detail` 等展示类接口，即使无 Authorization 也应能正常返回。

### 7.5 认证旁路与埋点

27. 登录引导链路：首次进入(无 authorization)→ `wx.login` 拿 code → POST `/api/login/code` → 用 `res.data.token` 落地 Authorization，且 `openId/userId/userType/avatar/nickname/role` 取自 `res.data.data.*`(双层 data)。Phase 1 收口后此双层解析与各字段落地必须逐字保持。
28. `wx.requestPayment` 收到的 payParams(`timeStamp/nonceStr/package/signType/paySign`)必须仍来自下单接口响应的 `res.data.payParams`，且支付 SUCCESS→paymentSuccess()、cancel→'您已取消支付'、其它 fail→'支付失败，请重试'分支行为不变(topic/settlement 取消/失败后还会 `app.goBack()`)。
29. 首页逆地理编码统一走 `/api/map/reverse-geocode`，由后端持有腾讯位置服务 Key；小程序不再携带第三方地图 Key。
30. dev 环境兜底：`wx.login` 或 `/api/login/code` 失败且 `isDevEnv()` 为真时调 `useDevUser()` 写 'dev-token' 等 mock 身份；Phase 1 不得误删此 dev 兜底路径。
31. analytics 对外契约：`track(eventName,payload)` 仅放行 `ALLOWED_EVENTS` 白名单；事件结构为 `eventName/sessionId/source/pagePath/bizType/bizId/cityCode/propertiesJson/idempotencyKey/occurredAt`，不传稳定用户标识，服务端以认证态 `memberId` 归因；`propertiesJson` 仅保留非敏感短标量；每事件带稳定 `idempotencyKey` 供后端幂等。
32. analytics 投递目标与鉴权不变：POST `{siteBaseUrl}/api/analytics/events/batch`，body=`{events:[...]}`，header 带 `Authorization=app.getAuthorization()`；成功判定 `res.statusCode===200 && body.code===200`。
33. analytics 队列容量约束不变：storage key `'p3_analytics_queue_v1'`，`MAX_QUEUE=100`(setQueue 时 `slice(-100)` 截断保留最新)、`MAX_BATCH=50`(单次最多投 50)。修并发缺陷后这三个量纲与'成功才出队、失败保留重试'语义须保持。
34. sessionContext 语义不变：`setSessionContext` 合并 ctx 且 sessionId 为空时自动生成 `nowSessionId()`；track 在无 sessionId 时自动补建会话。
35. **Phase 1 修复并发缺陷后的不变底线**：已成功投递的事件最终只被后端有效计一次、且在投递窗口内新 track 的事件不被丢弃——'不丢事件、不因前端快照覆盖导致永久丢失'必须成立(允许通过原子读改写/投递锁/按 idempotencyKey 去重实现，但对外可观测的最终事件集合不变)。

### 7.6 身份解释

36. 读取当前身份的统一入口语义保持：`app.getUserRole()` 三级回退结果不变，`app.getUserType()` 返回 `globalData.user_type` 不变。重构收拢会话后这两个函数对同样底层状态须产出相同结果。
37. 首页商户分流最终判定结果不变：`user_type==2` 且未被 debug 强制为 user 时，商户进首页直达/重定向到 `/pages/merchant/index/index`，玩家(及 debug=user)留在玩家首页。data 初始化/onLoad/onShow 三处分流结果保持一致(当前由 `isMerchantView()` 统一)。
38. `debug_user_view==='user'` 覆盖效果保留：即便真实 `user_type==2`，debug=user 时首页按玩家视角渲染、不触发 `/api/user/info` 商户兜底重定向(index.js:376)，设置页 isMerchantView 也为 false。debug 仅在 `envVersion==='develop'` 门控下可设置。
39. 商户首页 `/pages/merchant/index/index` 准入闸门不变：非商户(`user_type!=2`)进入即 redirect 回 `/pages/index/index`；onShow 拉 user/info 发现 `userType!=2` 时降级 `setUserType` 并踢回玩家首页。
40. 商家不可报名/参与的业务拦截不变：activity/detail bmClick 与 topic/index 在 `userType==2` 时弹'商家不可报名'并阻止下单；banner/linkType 按 `userType==2` 走商户报名页跳转分支保持。
41. 服务端身份回填落地效果不变：登录、静默重登、user/info、role/info 成功后，role 与 user_type 写入 globalData+storage 并成为后续判定来源；userType 升级(玩家→商家)在跨设备场景下仍能通过首页 user/info 兜底被发现并切换。
42. 退出登录后身份彻底清空：globalData 的 `user_type/role/authorization/open_id/user_id` 及 storage(含 `role/user_type/debug_user_view`)全部清掉，reLaunch 回首页呈现未登录玩家态，无身份残留。
43. role/center 价值区按角色分流不变：`role==='player'` 走积分(`loadPointsStat/loadPointTasks`)，club/merchant 走收益(`loadBalanceStat/loadIncome`)；role 缺省回退为 `'player'`。

### 7.7 位置与隐私

44. 授权检查顺序不变：`getFuzzyLocation` 前必先 `wx.getSetting` 判定 `scope.userFuzzyLocation||scope.userLocation`，未授权才 authorize；`authorize(userFuzzyLocation)` 失败必须 fallback 到 `authorize(userLocation)`(index.js:1448-1483)。
45. 拒绝不当异常：choosePoi/getLocation 的 fail 中 errMsg 含 'cancel' 必须静默 return 不弹任何 toast/modal；仅 auth deny/permission 才走 `showLocationPermissionGuide`(touch/index.js:200-209)。
46. 权限引导单次且非阻塞：任何拒绝最多弹一次 showModal(确认→`wx.openSetting()`)，不得引入循环/定时重弹/自动重试(touch/index.js:218-229, index.js:1485-1505)。
47. choosePoi 业务校验保留：`res.type===1` 且仅有 city 无 address/name 时提示'请选择具体地点，不能选择城市'并 return，不写入坐标。
48. 坐标精度与字段契约：`getFuzzyLocation` 用 `type:'gcj02'`，回传后端坐标保持 fuzzy 精度(不得静默升级为精确 `wx.getLocation`)；首页 locationInfo 经纬度 `toFixed(6)` 字符串格式不变(index.js:1519-1531)。
49. 隐私声明边界不扩大：收拢会话/传输不得新增精确定位或后台定位接口调用；`requiredPrivateInfos/permission` 当前仅 `choosePoi+getFuzzyLocation+userFuzzyLocation` 的范围须保持(app.json:105-113)。
50. 无后台采集契约：重构后仍不得引入 `onLocationChange/startLocationUpdate(Background)` 或 `requiredBackgroundModes location`；定位保持一次性、用户动作触发。
51. 定位失败不阻断主流程：fuzzy 定位失败仅 setData errorMsg/toast 提示，页面其余数据加载(`loadPageData/getList`)继续，不得因定位失败中断页面(index.js:1544+, searchmap/index.js:206-213)。
