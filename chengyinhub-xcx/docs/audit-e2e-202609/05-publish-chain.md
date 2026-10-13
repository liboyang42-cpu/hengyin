# 阶段5 · 内容 / 发布链路(fabu / temp / simple / activity / 草稿箱 / AI 预检)

只审不改。所有断言已回磁盘复核;文末「已否决」记录代理报告里对不上的条目。

## 发现

### F-PB-P1 [P1] temp:「据点玩法形态必选」这条校验拦不住发布

校验写了、红字也会渲染、但**发布路径不认它**:

`pages/publish/temp/index.js:1918-1920`
```js
if (this.data.from === 'citynode' && !this.data.interactionType) {
  errors.interactionType = '请选择玩法形态';
}
```

`pages/publish/temp/index.js:1982`(`validateForm` 的放行判据)
```js
const firstKey = ['title', 'description', 'players', 'duration', 'categoryIds', 'finish', 'advanced', 'voice', 'reward'].find(k => errors[k]);
```
清单里**没有** `interactionType`。于是它是唯一错项时 `validateForm` 返回 true,`publishGame`(`:1998` → `:2017` `sendData(... '/api/merchant/city-node/template/submit')`)照发。

对照证据:同一文件 `:1990-1994` 的「查看预览」置灰兜底清单里**有** `interactionType` —— 两条路口径不一致,说明这是漏改而非设计:
```js
const order = ['title', 'description', 'players', 'duration', 'categoryIds',
  'interactionType', 'finish', 'advanced', 'voice', 'reward'];
```
渲染侧确认会显示红字:`pages/publish/temp/index.wxml:137` `<cy-inline-error wx:if="{{errors.interactionType}}" .../>`。

复现:商家端据点入口(`from=citynode`)→ 不选玩法形态 → 描述/人数/时长/类别/玩法都填好 → 点「发布」→ 通过。

影响:正是 `:1917` 注释自己警告的后果 ——「据点玩法形态没选 = 玩家侧静默退化」,商家以为配好了,玩家到店里看到的是一个空洞据点。

### F-SM-1 [P1] simple:置灰主 CTA 没绑 disabledtap,页面唯一的自解释文案永远出不来

`pages/publish/simple/index.wxml:83`
```xml
<cy-btn variant="primary" ... disabled="{{!canEnterAiEditor}}" bindtap="enterAiEditor">
```
无 `bind:disabledtap`。而 `components/cy/btn/index.js:35-40` 明确:禁用态下原生 tap 被吞,只补发 `disabledtap`,**不会**透传 `tap`:
```js
if (this.data._v === 'disabled') {
  this.triggerEvent('disabledtap', e.detail);
  return;
}
```
所以 `enterAiEditor`(`index.js:225-235`)里那句自解释永远执行不到:
```js
this.setData({ aiError: '请逐个在地图中确认地点后再继续。' });
```
全页 `disabledtap` 命中数:0(`grep -n "disabledtap" index.wxml`)。

复现:建议版(mode=1)→ 生成主题草稿 → 留一个点位未在地图确认 → 点「确认地点,进入编辑器」→ 什么反应都没有。

### F-SM-2 [P2] simple:整块旧「快速发布」链路是孤儿,且它的反馈函数本来就渲染不出来

- 三个提交口在 wxml **零绑定**:`onClubChange`(`index.js:305`)、`onSaveDraft`(`:563`)、`onPublish`(`:592`)。本页 wxml 实际只有 8 个绑定(`useAiPrompt / onAiIdeaInput / onAiPlanTitleInput / onAiPlanStoryInput / onAiNodeNameInput / onAiNodeStoryInput / confirmAiNodePoi / generateAiPlan / enterAiEditor`),`grep -c stations index.wxml` = **0**。
- 由此 `validate()`(`:470-483`)、站点增删(`:418/:425`)、选点(`:441-465`)、`_doPublish`(`:616`)全部不可达;`_offerDraftResume`(`:250`)**没有任何调用点**,所以旧草稿也不会弹「继续编辑」死胡同弹窗 —— 这块是干净的死代码,不是活的坏路。
- 但它的反馈通道本身就是坏的:`tip(msg)`(`:664-669`)只 `setData({ toast: msg })`,而 `index.wxml` 里 `toast` **零渲染**(整页 toast 只有 `:4` 的 `<cy-toast id="cy-toast" />`,页面从未调用它;顶部 `:2` 引入的 `utils/toast.js` 助手在全页无一处调用)。

裁决提示:这条按「清理」处理即可;若修复批要把快速发布重新接线,**必须先给 tip() 换真通道**,否则 18 处校验/失败提示全部静默。

### F-AN-1 [P2] 发布成功埋点的 bizId 恒为 null

`pages/publish/fabu/index.js:5708`
```js
bizId: resData && resData.id ? resData.id : null,
```
`resData` 来自 `:5669` `cb({ ok: true, data: res.data })`,而后端返回的是标量:`ApiTopicController.java:464` `return AjaxResult.success("主题发布成功", topicId);`。所以 `resData.id` 恒 undefined。紧邻的下一行 `:5713 const tid = resData; // 后端返回 topicId` 才是对的形状 —— 跳转因此正常,只有埋点丢 ID。

同形问题在 `pages/publish/simple/index.js:631`(`res.data && res.data.id`)。

### F-SM-3 [P2] simple 发布成功没有任何去详情的路(死变量)

`pages/publish/simple/index.js:632-637`
```js
const topicUrl = tid ? ('/pages/topic/index/index?id=' + tid) : '/subpackageA/pages/myproject/index';
...
that._resultSheetNext = '/subpackageA/pages/myproject/index';
```
`topicUrl` 算出来后从未使用。落点是 2026-09-06 用户裁决(收敛导航分叉),所以**行为正确、代码有赘**;只报赘。

### F-DR-1 [P2] 活动草稿续写恒回第 0 步

`subpackageA/pages/myproject/index.js:290` 草稿/未通过活动 → `/pages/publish/activity/index?id=`;该页 `step: 0` 是初始值(`pages/publish/activity/index.js:72`),onLoad 只回填 `formData`,全页改 `step` 的只有用户点下一步的 `:351` 与 `:385`。表单内容在,但要重新点回原来那一步。

### F-SEC-1 [P2] AI 安全预检只覆盖 fabu 一条链路(但后端不漏)

生产代码里 `/api/ai/safety/precheck` 只有一个调用点:`pages/publish/fabu/index.js:5383`。temp / activity / simple / 广场发帖四条提交链路零预检。

**降级依据**:内容安全闸本身在服务端,各条提交口都有 ——
`ApiTopicController.java:407`、`:531`(update)、`ApiTemplateController.java:329/380`、`ApiMerchantNodeController.java:209`、`ApiCreativeSquareController.java:572/625`。
所以缺的不是「安全」而是「advisory 体验」:fabu 用户在点发布前就看到哪几段有问题,其余三条要等后端整条拒。

fabu 这一处的健壮性已核:显式 JSON header + `silentError:true`,失败/超时统一降级成一条 advisory 放行,`complete` 里解锁(`:5394/:5404/:5409`),**不会把发布按钮永久卡住**。

### F-TA-1 [P2] topicadd 生产零引用,删除面(上轮遗留裁决)

全仓(排除 tests/scripts)只有 `app.json:72` 注册它,没有任何页面跳它。删除会打断的持有者:`tests/unit/topicadd-shell-redirect-contract.test.js`、`system-navigation-title-contract.test.js:42,160`、`title-system-owner-contract.test.js:130`、`primary-action-form-state-contract.test.js:139`、`merchant-theme-route-contract.test.js:40`、`nav-route-exists.test.js:172`(注释写明「2026-10 到期删」)、`scripts/shot-matrix.js:296,480`。

### 已核对无问题

- fabu `submitForm`(`index.js:5477`)每条早退都带 toast 或弹层;两颗主 CTA 均已挂 `bind:disabledtap`(`pages/publish/activity/index.wxml:246-247`)。
- temp `sendData`(`:2074`)有 fail 兜底,且 `request-client.js:134-142` 已把 HTTP 非 200 回落 `fail`,不会永久锁 `_submitting`。
- 草稿箱双路各归其位:`components/cy/post-drafts` 唯一宿主是发贴器(`components/cy/post-compose/index.wxml:101`,继续编辑 = `onPickDraft` 回填正文,可达且正确);发布编辑器的草稿走服务端 `myproject → fabu?id`,fabu `onLoad:1787` 读 `options.id` ✓。
- temp 成功落点 `wx.reLaunch('/subpackageMember/mytemplate/mytemplate')`(`:2197`)路由已注册(`app.json:250`);`from=fabu` 支靠 eventChannel `templateCreated`,fabu `:6004` 有监听 ✓。
- 上轮 P1「temp AI 助写无绑定」**现状已消**:助写链在 2026-09-18「批3C」整体删除(`tests/lint/ui-integrity-baseline.js:68,401`),temp 内已无 `aiAssist` handler。它留下的 `themeName/nodeName` 两个死字段转为门禁项,见 `06-gate-blindspots.md`。

## 已否决(代理断言与磁盘不符)

| 断言 | 实情 |
|---|---|
| 「`pages/publish/fabu/index.js:5713 const tid = resData` 与 `:5708` 自相矛盾,会拼出 `topicId=[object Object]` 导致项目主页空白」 | **反了**。后端 `ApiTopicController.java:464` 返回 `AjaxResult.success(msg, topicId)`,`res.data` 就是标量 topicId;`:5713` 正确,`:5708` 的 `resData.id` 才是错的那一行(降级为 F-AN-1 埋点问题,跳转链路无碍) |
| 「AI 预检缺失是 P1 安全风险」 | 服务端每条提交口都有 `checkText`(见 F-SEC-1 证据),缺的是前端 advisory,定 P2 |
| 「simple `_offerDraftResume` 会弹死胡同草稿恢复」 | 该方法无任何调用点,不会弹 |
