# 小程序架构改进 · 执行进度与接续指南

> 给"重开会话的我/接手者":读完这页 + `current-api-inventory.md` 就能完美接上。
> 路线图原文:`docs/superpowers/plans/2026-06-23-xcx-architecture-improvement-roadmap.md`

最后更新:2026-06-24

## 0. 一句话现状

Phase 0/1.1/1.2/1.3/2 + **3.2(结算)+ 3.1(发布)+ 4(位置选址 choosePoi 统一)已完成**(**110 单测绿**)。Phase 4 只统一了 choosePoi(7 站),getLocation(6 站)发散+授权敏感留待单独一轮。之后剩 Phase 5(可观测)/6(分包)。真机崩溃(var-wx)已修并经用户 DevTools 确认"消失了"。
**Phase 0~4 全部已 push 到 Codeup(2026-06-24,远端 = 本地 HEAD `554c5fa`)。** 真机待验 3 条:① checkout-workflow(`eb809e4`)支付控制流;② index 首页位置授权后重新取位(`aa9e35e`);③ play 实时围栏(需先在 mp 后台补 onLocationChange/startLocationUpdate 隐私指引,见 Phase 4 余项段)。

## 1. 分支与提交(关键)

- **工作分支 = `feature/app-backend-support`**(用户真机测试主分支,siteBaseUrl 正确 = `https://www.chengyinhub.com/prod-api`)。
- 架构提交(从老到新):
  - `a4a68fe` test: 建立基线(test:unit/test:automator + 接口清单)
  - `b933f3e` refactor: 收拢会话存储 session-store
  - `88e92f8` fix: 单航班登录 + 假ready修复
  - `baec0fa` fix: session-store var-wx 自遮蔽真机崩溃修复
- 这批提交也在 `feature/xcx-architecture-baseline`(从 master 起,原 SHA `7a80932/38467bd/ff32abb/56e48c0`)。
- ⚠️ **陷阱:master 与 baseline 分支的 siteBaseUrl 仍是旧的 `chengyinhub.shapps.cn`(外包旧机,无 stable_token→会"已被绑定")。别从 master 起步测试。** 真实后端只有 `feature/app-backend-support` 上对。
- 工作区有一摊**未提交的「关卡编辑器」WIP**(publish/temp、topicadd、touch、后端 Java、club/edit、migration sql)——是用户另一条活,**不要替他提交**。

## 2. 已完成的内容

### Phase 0 — 安全网
- `package.json`:`test:unit`(node:test,用 globstar `"tests/unit/**/*.test.js"` 避开 Node24 的 `--test 目录` bug)、`test:automator`。零新依赖。
- `tests/unit/smoke.test.js`、`tests/automator/login-smoke.js`(连 DevTools 自动化端口跑登录冒烟,无端口明确报错不假绿)。
- `docs/architecture/current-api-inventory.md`:**93 端点 / 19 业务域接口清单 + 请求认证模型 + 51 条「Phase 1 不可破坏契约」**(重构验收基准,改任何东西前先对这份)。

### Phase 1.1 — 会话存储 `utils/session/session-store.js`
- 管七字段(user_type/user_id/authorization/open_id/avatar/nickname/role)的内存/Storage 一致性。
- **target 绑 `app.globalData` 同一对象引用** → 所有直接读 `globalData.*` 的代码(sendRequest/firstLogin/getUserRole)零改动自动同步。
- app.js:onLaunch 七个恢复块 → `getSession().restore()`;七组 getter/setter 委托(方法名保留,页面零感知);移除孤儿 setStorage;持久化改同步 setStorageSync。

### Phase 1.2 — 单航班登录 `utils/session/session-manager.js`
- firstLogin/reLogin 两套重复登录收敛为一处 + **单航班锁**(并发 401 只换一次 token)。
- **假 ready 修复**:新增 `app.isAuthenticated` 独立信号;`isAppReady` 仍只表"初始化完成"(app 照常起、看公共内容);正式版登录失败不再伪装已认证,但仍 resolve 不阻断初始化。
- 保留:缓存短路、dev mock(isDevEnv 门控)、三条失败路径原文案 toast。

### 真机踩坑(教训)
- session-store 里 `var wx = ... || (typeof wx ...)` —— 局部 `var wx` 自遮蔽,`typeof wx` 指向被提升的局部 undefined,生产(未注入 options.wx、小程序无 Node global)解析为 null → 崩。**单测显式传 wx 桩把这条生产路径完全盖死(17 单测绿却真机炸)**。修法:局部改名 `storage`,内部全走 `storage.*`;加回归测试"不注入 wx→回退全局 wx"。
- 通用教训:**纯模块也要测"不注入依赖→回退真实环境"的生产路径**;关键登录/会话改动必须真机背书,别只看单测绿。

### Phase 1.3 — 请求/上传通道收口
- `utils/transport/request-client.js`:从 app.js sendRequest 抽出,行为零变 + **认证头自动注入**。认证模型严格兼容旧行为:
  - GET 且无 param.header → 公共请求,不带 Authorization;
  - POST 且无 param.header → urlencoded + 自动 Authorization;
  - 提供了 param.header → 自动补 Authorization(除非已显式带或 `param.auth===false`),content-type 保留;页面已显式带的不覆盖。
  - 401/业务 code==2 → 单航班重登重试一次(_retried 防死循环);statusCode 非 200 → successStatusAbnormal/toast;错误归一沿用 app 既有纯函数。
- `utils/transport/upload-client.js`:替换 app.js chooseImage/chooseDocument 两套 uploadFile 循环。**修「多文件部分失败 loading 卡死」**:所有文件 settle 后 onDone 恰好触发一次,返回失败列表。保持无 Authorization 的独立 multipart 路径(契约 #24)、url/name/formData/code==200/data.url 不变(#21/#23)。
- app.js:`sendRequest` 委托 request-client;`chooseImage/chooseDocument` 委托 upload-client(新增 `getRequestClient()/getUploadClient()` 惰性 getter)。对外方法名/签名零变。
- `utils/analytics.js`:postBatch 改走 `app.sendRequest`(不再直连 wx.request、不再手拼 Authorization);**修 flush 并发快照覆盖丢事件**(契约 #35):加投递锁 + 成功后基于"当前队列"原子出队 + 排空续投。MAX_QUEUE/MAX_BATCH/白名单/事件结构不变。
- **删 17 页手拼 `'Authorization': app.getAuthorization()`**(workflow 并行,每页 node --check):shanghuziliao/club·apply/settlement/activity·baoming/member·couponInfo/member·tixian/topic·merchantapply1·2/topic·settlement/publish·activity/publish·fabu/publish·simple(2处)/profile/gerenziliao/shangjiaruzhu/index/merchant·apply。
- **有意排除的 WIP 文件(用户「关卡编辑器」另一条活,未替他动)**:`pages/publish/temp/index.js`、`pages/club/edit/index.js` 仍各有 1 处手拼 Authorization —— 等那条线落地后顺带清。
- **残留 wx.request 均为契约明确允许**:app.js exchangeCode 与 index.js `firstLogin`(登录适配器,直连 /api/login/code)、index.js `reverseGeocode`(天地图第三方 API,非本后端、无认证)。index.js 页面级 firstLogin 是登录逻辑重复,留待 Phase 2 收口。
- 新增单测:request-client(16)/upload-client(7)/analytics(4)。**test:unit 共 45 绿。**

### Phase 2 — 统一身份、能力、调试视角(RBAC)
**确认门已过**(用户拍板:① 镜像后端 resolveRole ② 务实保留 roleGuard + 加薄 identity-policy ③ 配额 fail-closed)。
**后端契约(从代码核对,无需再问)**:`/api/role/info`(`ApiRoleController`)下发 `{role, permission, usage}`;`RoleServiceImpl.resolveRole` = `member.role` 非空优先,否则 `userType==2→merchant`、其余 `player`;角色全集 = **player/club/merchant**(无 official)。权限位:配额 `maxThemes/maxNodesPerTheme/maxEvents/maxCoupons`(null=不限)+ 布尔 `canCreateTheme/...` + `nodeTypes[]` + `dataScope/withdrawable`;用量 `themes/events/coupons`。
- `utils/identity/identity-policy.js`(**纯函数,新增**):`resolveRole`(镜像后端)、`effectiveRole/isMerchantView/isClubView`(`debug_user_view='user'` 仅强制玩家展示、不改业务身份)、`can`、`quota`、`hasSnapshot`、`reachedLimit`(**无快照 fail-closed**)。13 单测。
- `utils/roleGuard.js`:**已存在且已提交(`bc0d600`)**——上一版进度文档说"本仓不存在"是 master 基线的旧情况,**已纠正**。本期改为能力解释**委托 identity-policy**,`reachedLimit` 变 fail-closed,新增 `hasSnapshot()`;`load/role/permission/usage/quota/clear` 对外 API 不变。6 回归单测。
- **修 3 个 bug**:
  1. `shezhi.switchIdentity` 切视角**不再写 `user_type`**(只写 `debug_user_view`),消除"调试切换污染真实身份"(契约:正式版忽略该键,且 canSwitchIdentity 仅 develop)。
  2. `shezhi.logout` 增 `roleGuard.clear()`:`wx.clearStorageSync` 清不掉 roleGuard 模块级 `_cache`、reLaunch 不重启模块 → 原会残留旧权限。
  3. **首页 `user_type==2` 与设置页 `role==='merchant'` 判商户打架** → 统一走 `identity-policy`(role 优先)。
- 迁移视角路由到 policy:`index.js` isMerchantView/isClubView/onShow 兜底、`shezhi.updateViewState`、`merchant/index` 准入闸(均 role 优先、镜像后端);onShow 兜底/准入闸同步回填 role。
- `publish/fabu` 节点上限守卫加 fail-closed(权限未就绪拦截,页面 onLoad 已预热 load)。
- **保留不动(纯展示/数据映射/一致动作规则)**:`role/center`(role 显示积分vs资产)、`member/index.wxml`(userType 按钮)、`activity/detail`·`topic/index`(商家不可报名,一致用 user_type)、`index` linkType 映射。
- 新增/回归单测:identity-policy(13)+roleGuard(6)。**test:unit 共 64 绿。**

### Phase 3.2 — 结算金额与校验(发布段 3.1 未做)

2026-09-30 退役说明：当前源码已无 `checkout-model.js` 引用，该文件及孤立单测随冗余清理退役。以下实施记录保留为历史证据，不代表现行引用关系；`checkout-workflow.js` 本次保留。
- `utils/checkout/checkout-model.js`(**纯函数,新增**):
  - `computeDiscount({basePrice,userPoints,useDiscount})`:积分抵扣(1积分=0.02元、封顶50、不超票面)精确算法——原 `settlement` 与 `topic/settlement` 各重复 2 份、**共 4 份**,现单一实现。
  - `validateCheckout({hasTicket,hasAddress,agreementChecked,realName,phone,addressMissingMsg})`:下单校验链(票→地址→协议→姓名→手机→手机格式),地址缺失文案各页传入(配送地址 / 个人信息)。
  - 13 单测。
- 接线 `pages/settlement/settlement.js` + `pages/topic/settlement/settlement.js`:toggleDiscount/recalculateDiscount/handlePayment 校验链改调 checkout-model;**两页合计 -247/+54 行**(脚本明显缩小)。不动 WXML/WXSS、不改支付流程(`/api/registration/create` → `wx.requestPayment` 原样)。
- `utils/checkout/checkout-workflow.js`(**纯状态机,新增**):idle→submitting→(payable?)paying:免费成功→done。依赖注入 createOrder/requestPayment。不变量:防重(在途只提交一次)、创建订单失败不拉起支付、支付取消/失败回 idle 可重试、**支付成功只上报一次**、**页面销毁后不再回调(destroy 守卫,避免 setData after unload)**。8 单测。两结算页 handlePayment/createRegistration/wechatPayment 收口到状态机 + 加 onUnload destroy;DTO/接口/支付参数/文案/跳转 URL/topic 的 goBack 均零变。**⚠️ 支付流程改了控制权,需重新真机验一遍(下单/支付成功/取消/失败/返回)。**
- **test:unit 此前共 85 绿。**

### Phase 3.1 — 发布(activity + fabu;temp 是 WIP 排除)
- 差异矩阵结论:两页 `sendData` 提交时序**近乎逐字相同**(仅 url `/api/activity/publish` vs `/api/topic/create` + analytics 事件名/bizType 不同),`validateForm` 则差异大(字段集/文案/票务规则/聚焦 UX)。按"只抽 ≥2 页共享规则、避免浅模块"取舍:
- `utils/publish/publish-workflow.js`(**纯状态机,新增**):`createPublishWorkflow({request})` → `submit(payload,{onSuccess,onFail})`。idle→submitting→成功 done / 失败回 idle。不变量:防重(在途只发一次,替代两页手搓的 `_submitting`)、业务/网络失败回 idle 可重试、成功 onSuccess 只触发一次、**destroy 守卫(修两页原本缺失的 setData/redirect-after-unload)**。8 单测。
- `utils/publish/publish-validator.js`(**纯函数,新增**):`createErrorBag`(errors 映射 + order + messages 三轨累积,replace 两页手搓的 errors{}/errorMessages[] 双推;第四参 toastMessage 让 fabu 的"第N个票务"前缀 toast 与字段内联短文案分离)+ `nonEmpty`(空值/占位哨兵判空)。**未抽 requireTicketNames**:两页票务规则各与专有项(stock vs price+mode)交错,抽出会改错误顺序,故逐票交错校验仍留页面。7 单测。
- 接线 `pages/publish/activity/index.js`:validateForm 改用 bag/nonEmpty(字段顺序/文案/scrollToError 零变);sendData 收口 workflow(url/analytics/toast/redirect/submitting 保持);加 onUnload destroy。
- 接线 `pages/publish/fabu/index.js`:同上(errorMessages→bag.messages/firstMessage、focusValidationError 保留、章节守卫与票务 price+mode 留页;节点上限 roleGuard 守卫不动)。
- 行为零变:两页字段集/文案/错误顺序/首错聚焦/跳转/DTO 归一化全部保持,仅把累积机制与提交时序收敛到共享模块。
- **test:unit 共 100 绿(85→+8 workflow +7 validator)。** node --check 两页通过。
- **B/C 段提交控制流 automator 已自动回归通过(`8f1597a`)**:`tests/automator/publish-flow.js` 在真机运行时 stub app.sendRequest(只拦 /api/topic/create + /api/activity/publish,放行 analytics)+ wx 跳转,驱动两页 sendData——防重(在途只发一次)/成功(发布成功 toast+redirect 项目页)/业务失败(app.tips+submitting 回 false+可重试)/网络失败(网络错误 toast)/**onUnload 销毁守卫(回调不跳转不弹成功)**,fabu+activity 各 13 项全绿,**零生产数据**。跑法:`ONLY=activity DEVTOOLS_CLI=.../cli node chengyinhub-xcx/tests/automator/publish-flow.js`(分次跑,别 KEEP_OPEN 多开会锁住 IDE)。
- **A 段(校验视觉)也已 automator 自动回归通过**:`tests/automator/publish-validate.js` 灌各种表单状态、读回 `errors` 映射 + 捕获 toast/pageScrollTo/activeTab,断言文案/顺序/键零变;并截图人工复核。fabu 9/9 + activity 9/9。验到:① 空表单/逐字段首错文案正确(主题名称→描述…;活动标题→地点→…)② fabu 票务"第1个票务:"前缀 toast vs 字段内联短文案分离 ③ fabu 无章节切 tab 0、activity 滚动定位 `#field-name`/`#field-address` ④ activity-empty 截图肉眼确认红字内联渲染(请填写活动标题/地点/说明、请选择开始日期/活动类型,`*` 标记齐全)。validateForm 不发网络,prod-safe。
- **结论:Phase 3.1 单测(100)+ A 段(校验)+ B/C 段(提交控制流)三层全绿,行为零变已多重背书。** 真机仅剩"端到端真发一条数据"可选确认(automator 为不污染生产库已 stub,未真发)。

### Phase 4 — 位置选址封装(choosePoi 统一)
- 勘察:位置 API 调用点分两类。**choosePoi(7 站,用户主动选址)模式逐字相同**;**getLocation(6 站)各页用途发散**。按"只抽 ≥2 页共享、避免浅模块、不改业务行为"取舍——本轮只统一 choosePoi。
- `utils/location/location-manager.js`(**新增**):`pickLocation({onPick,guideOnDeny})` 封装 wx.choosePoi——城市校验拦截(type1+city 无 address/name→toast)、取消静默、拒绝授权(可选`showPermissionGuide`引导+「需要位置权限」toast)、其它失败「选择地点失败」toast、成功归一化 `{name,address,longitude,latitude}` 回调 onPick;`showPermissionGuide`(去设置 openSetting,原各页重复)。DI wx 便于单测 + 覆盖全局 wx 回退路径(Phase1 教训)。**10 单测**。
- 迁移 7 站点(各页 onPick 保留自己的 setData 字段映射与成功 toast 差异):`square/list`、`topic/merchantapply1`(**guideOnDeny=false**:本页原拒绝不弹引导,保持零变;保留成功 toast)、`shangjiaruzhu`、`components/touch`(**保留 showLocationPermissionGuide**——其 getLocation 仍用)、`publish/activity`、`publish/fabu`(chooseTicketMeetingPoint 1564 + choosePoiForNode 2103;删本页 guide)。删除 4 处重复的 showLocationPermissionGuide。
- **getLocation 用途表(本轮保留页面内,发散+授权敏感,待单独一轮 + 真机 + 隐私复核)**:
  - `pages/index/index.js` 城市推荐:`getFuzzyLocation`→getSetting→authorize(fuzzy 失败回退 userLocation)→handleAuthFail 引导→executeGetFuzzyLocation(loading + getLocation gcj02 + 拉 nearbyActivityList + 天地图逆地理 reverseGeocode→saveInfo)。**✅ 已修 this bug**:`handleAuthFail` 里 `openSetting.success` 回调原用 `this.executeGetFuzzyLocation()`,该 `this` 非页面实例→开启权限后不会重新取位;已 `const that=this` 改 `that.executeGetFuzzyLocation()`(**需真机确认:拒绝→去设置→授权→自动重新取位**)。
  - `pages/searchmap/index.js` `moveToLocation`:getLocation→设地图中心+重载列表。
  - `components/touch/index.js` `getLocation`:getLocation→amap 逆地理编码→setData address(无 key 时降级坐标串)。
  - `pages/play/index.js` 到点打卡:精确 getLocation + `startLocationUpdate`/`onLocationChange`/`stopLocationUpdate` 实时;拒绝→走手动「我到了」不骚扰。
- 行为零变:7 站点的字段映射/成功 toast/拒绝引导差异全保留;getLocation 全部未动。
- **test:unit 共 110 绿(100→+10 location-manager)。** 7 个改动文件 node --check 通过。choosePoi 是原生地图 UI,automator 难模拟选点结果,控制逻辑已被 10 单测覆盖,故不另跑 automator。

### Phase 4 余项(getLocation + 隐私声明审计)
- **结论:getLocation 4 类用途不可安全收敛为共享模块**(逆地理 index=天地图 / touch=高德,提供商都不同;授权策略 index=authorize+fallback+openSetting、play=getSetting+priming+手动降级、touch=裸调+deny引导、searchmap=无;落地后动作各异;仅 play 实时)。强抽=浅模块且动敏感授权流→**本轮不抽,保留页面内**,仅做下面两件确定性收益。
- **✅ 修 index `this` bug**(`handleAuthFail` openSetting 回调,见上),`node --check` 过。属页面深耦合 glue,无法廉价单测,**待真机确认重新取位路径**。
- **✅ 隐私接口声明审计(app.json 已补,用户拍板)**:实际用到 `choosePoi/getLocation/onLocationChange/startLocationUpdate`;`requiredPrivateInfos` 原只声明前二者,已补 `onLocationChange`+`startLocationUpdate`(play 实时围栏)。**⚠️ 用户待办(提审前必须):mp 后台「隐私保护指引」同步勾选/填写这两个接口的用途说明,否则会因"声明了但指引未覆盖"被拒**;app.json 与后台两边一致后,play 的 startLocationUpdate 才真生效。

## 3. 如何验证

```bash
npm --prefix chengyinhub-xcx run test:unit   # 85 绿
node --check chengyinhub-xcx/app.js          # 语法
```
真机(Phase 1.3+2+3.2 **已于 2026-06-24 验证通过**):登录、发帖(普通/简易/活动)、个人页、身份切换三页一致+退出重登不残留旧权限、图片/文件上传(含多文件部分失败 loading 正常收掉)、结算折扣金额与下单校验、报名支付——均无问题;getAuthorization 崩溃已消失。

## 4. 下一步

- **Phase 4 余项(单独一轮,需真机+隐私复核)**:getLocation 6 站点的用途化精度/降级统一 + 修 index.handleAuthFail 的 `this` bug;或加 location-manager.getLocationByPurpose(城市推荐 fuzzy+降级 / 打卡 precise)。
- **Phase 5 可观测**:error-reporter/performance-reporter/analytics-queue。
- 收口 index.js 页面级 `firstLogin`(目前直连 /api/login/code,与 app/session-manager 重复)。
- **待真机/DevTools 重验**:① checkout-workflow 改了两结算页支付控制流(下单/支付成功/取消/失败/返回);② Phase 3.1 两发布页提交控制流(activity 活动 + fabu 普通,各走 初始/校验错误/提交中/成功跳转/失败恢复)。
- WIP「关卡编辑器」落地后,顺带清 `pages/publish/temp/index.js`、`pages/club/edit/index.js` 的手拼 Authorization。
- 若加 `canRegister` 能力位,可把 activity/detail·topic/index 的"商家不可报名"从 user_type 收口到 identity-policy。

## 5. 任务台账(TaskCreate 实时列表)

Phase0(3项)/1.1/1.2/1.3/2/**3.2结算(含支付状态机)/3.1发布(A+B/C automator 全过)/4位置选址 已 completed**;另有"DevTools 包体/启动基线截图""checkout-workflow 支付流程重验"待用户操作(CLI 代劳不了)。
