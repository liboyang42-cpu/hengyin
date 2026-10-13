# 行为断言清单(截图之外的那一半)· 2026-08-18

> **截图只证明「显示成什么样」,不证明「点下去会发生什么」。**
> 2026-07-26 的 UI 终审里 12 条 P0/P1 有 4 条被撤销、3 条被下调,根因就是拿截图当行为证据。
> 本清单里的每一条都必须用 `page.callMethod()` **断返回值 / 断 setData 后的 data / 断 getCurrentPages()**,
> 不允许以「图上看着对」结案。

## 2026-08-21 执行状态

本清单已有代表性执行证据，但尚未逐条跑完 2,380 个控件：

- A1/A3/A5/A6 与 B4 已落为 `tests/unit/behavior-assertions-local.test.js` 的真实页面方法测试；A4 由 `chapter-recruitment-workbench.test.js` 双向翻转覆盖。
- B1–B3、B5–B6、C1–C3、D1–D3 已由对应页面/权限/资金/返回/错误恢复契约测试覆盖；F1/F2 保留 2026-08-18 DevTools 页面栈回读证据。
- `scripts/uiaudit/action-ledger.json` 已收录 2,380 个真实事件绑定，逐条记录动作类别、期望对端回读及 error/timeout/duplicate-trigger 必跑分支；`scripts/uiaudit/action-evidence.json` 单独保存非 pending 动态结果，生成 inventory 时不会抹掉证据。`npm run audit:actions` 会同时校验清单 freshness 与 evidence schema；当前 overlay 为 0 条，不能把该门禁写成全部按钮行为 PASS。
- 263 个状态中 238 个可操作状态已完成当前生产小程序源码的 DevTools 采集终闸：238/238 `ok`，重复 md5 为 0；25 个不可截图项保留明确替代验证，未从分母静默删除。

边界：这些证据覆盖代表性本地逻辑层、受控状态与页面栈，不等于 2,380 个控件逐条完成真 `tap()`，也不等于真实资金/owner/微信外部能力已经在体验版完成。L2–L7 仍须按 ledger 逐条落证；L6 的真实服务端事实回读、L7 的 iOS/Android 定位/相机/扫码/分享/支付/订阅消息不能用 `callMethod()` 或截图代替。

## 通用写法

```js
const mp = await launchFixed();                    // scripts/_infra.js,别自己调 automator.launch()
const page = await mp.reLaunch('/路由');
await page.waitFor(300);
// ① 断返回值
const got = await page.callMethod('方法名', 参数);
// ② 断 setData 之后的 data(callMethod 之后必须重新 page.data(),别用调用前的快照)
const d = await page.data();
// ③ 断导航真的发生了 —— {ok:true} 不算数,navigateTo 返 ok 而页面栈没动是实测过的
const stack = await mp.evaluate(() => getCurrentPages().map((p) => p.route));
```

> ⚠️ **`page.callMethod` 绕开命中测试**:它直接调方法,**不经过遮挡/disabled 判定**。
> 所以「闸放行了吗」用 callMethod 断,「按钮点得到吗」必须另配 `tap()` + DOM 断言,
> 两者不能互相顶替(假绿的第 6 种模式:驱动层绕过被测机制)。

---

## A. 本轮改过的四项(必做,回归面最直接)

| # | 页面 | 断言 | 期望 |
|---|---|---|---|
| A1 | `subpackageMember/complaint/index` | `callMethod('loadTopics')` 后 `page.data().topics` | 数组;每项含 `id`/`name`;**结束 >7 天的主题也必须在里面**(这是本轮修的根因①) |
| A2 | 同上 | 服务端 `/api/coop/complaint/topics` 与 `/api/coop/complaint/report` 一致性 | 已由真库 IT `ComplaintPickerOwnerType3RealDbIT` 覆盖(6/6 绿),**前端这层只需断 topics 非空 + 能选中** |
| A3 | 同上 | `callMethod('selectPickerOption', {currentTarget:{dataset:{picker:'topic',index:0}}})` → `page.data().canSubmit` | 选了主题且理由 ≥5 字后为 `true`;只选主题不写理由仍为 `false` |
| A4 | `pages/publish/fabu/index` 招商开关 | `callMethod('onChapterRecruitToggle', {currentTarget:{dataset:{index:0}},detail:{value:true}})` → `page.data().formData.chapters[0].recruitEnabled` | `1`;再调一次翻回 `0`(**翻转要验两次,只验一次证明不了它会翻**) |
| A6 | `pages/merchant/decor/coop-setting/index` 局部保存 | 同 A5 | 同上 |

> ⚠️ D6「上下架翻转」在本仓找不到叫 `toggleShelf` 的方法;最接近的是
> `subpackageA/pages/myproject/index` 列表项上的 `_canToggle`/`_toggleLabel` 字段。
> **执行前先从页面源码取到真实方法名再写断言** —— grep 零命中不等于不存在,
> 但拿一个猜的方法名去 callMethod 会得到「方法不存在」的假红。

---

## B. 闸(权限 / 状态 / 资金)——「点了到底放不放行」

| # | 位置 | 断言 | 期望 |
|---|---|---|---|
| B1 | `pages/topic/merchantinfo` 承接方编辑闸 | `callMethod('canEditRegistration')` | `stateKey∈{pending,rejected}` 时 `true`,`running/ended` 时 `false` |
| B2 | 同上,主办方编辑入口 | `callMethod('buildQuickActions','host','merchant',false)` → 返回数组的 `key` | **不含 `edit`**;传 `true` 时含 `edit`(本轮新加的闸) |
| B3 | 同上,承接方编辑 | `callMethod('buildQuickActions','join','merchant',false)` | 仍含 `edit`(承接方的闸是 canEditRegistration,与 host.canEdit 无关) |
| B4 | `pages/topic/merchantinfo` 提现闸 | `page.data().canWithdraw` | `status===0` 才为 `true` |
| B5 | 商家侧任一页(非商家身份进入) | 身份注入成 player 后 `reLaunch` → `getCurrentPages()` | 被踢回或显示无权限态,**不能停在商家页**(白屏互踢那一族) |
| B6 | 资金:提现/结算详情 | 金额字段 | 断**字符串原文**(`'¥128.00'`),不断数字 —— 精度与千分位是资金页最容易悄悄坏的地方 |

> ⚠️ **只 assertThrows 是假绿**:相邻多闸抛同型异常会互相接住。
> 断言必须指明**是哪一道闸**(比对提示文案 / 比对返回的具体值),不能只断"抛了"。

---

## C. 空态与错误态 ——「没有数据时长什么样、能不能出去」

对每个有列表的页各一条(清单见 `route-matrix.json` 里 `needsData:true` 的 45 条):

| # | 断言 | 期望 |
|---|---|---|
| C1 | 注入 `{loading:false, list:[]}` 后 `page.data()` | 空态字段(`emptyTit`/`emptySub`)非空字符串 —— **空态不能是空白页** |
| C2 | 注入 `{loadErr:true}` 后 `callMethod('retryLoad')` 或页面自己的重试方法 | 重试后 `loading` 回到 `true`,`errorMsg` 清空(**认证终态不能把用户永久留在加载态**) |
| C3 | 错误态文案 | 是**可执行的**说明(「请稍后重试」),不是裸错误码 |

> ⚠️ **判「内容不见了」之前必须先证伪 sticky 取景**:用 `page.$('选择器').offset()` / `.style()`
> 回读位置与尺寸。sticky 吸顶头会把下方内容拍成"消失",2026-08-04 实证过。

---

## D. 返回 / 关闭 ——「点了真的出得去」

| # | 断言 | 期望 |
|---|---|---|
| D1 | 每个带 `onBack` 的页(本仓 7 个)`callMethod('onBack')` 后读 `getCurrentPages()` | 栈**真的**变短,或(栈长为 1 时)`switchTab` 到会员中心。⚠️ `navigateTo` 返 `{ok:true}` 而页面栈没动是实测过的,**回执 ≠ 观测** |
| D2 | 所有半屏/弹窗的关闭 | 关闭后对应 `xxxSheet.show === false`,且**页面栈不变**(关弹窗不该退页) |
| D3 | tabBar 页的返回 | tabBar 页栈长恒为 1 ⇒ 必须走 `switchTab`,不能 `navigateBack`(裸 chevron 点不动那一族) |

---

## F. 纯重定向壳 ——「截图证明不了跳转,回读页面栈才行」

本仓有两个页面自身没有界面,`onLoad` 第一句就把人转走。它们在截图矩阵里是 blocked
(`scripts/uiaudit/blocked-baseline.json` 里 E06 / F05),验证方式只能是**回读页面栈**。

| # | 断言 | 期望 | 实测 |
|---|---|---|---|
| F1 | `reLaunch('/pages/club/workbench/index?clubId=1')` 后读 `getCurrentPages()` | 末项 `route` = `pages/club/detail/index`,`options` = `{id:'1', tab:'manage'}` | 2026-08-18 PASS |
| F2 | `reLaunch('/pages/coop/withdraw/records/index')` 后读 `getCurrentPages()` | 末项 `route` = `subpackageMember/tixianjilu/tixianjilu` | 2026-08-18 PASS |

> 这两条各自还有一份静态契约(`tests/unit/club-workbench-contract.test.js`),
> 静态锁「转去哪」,行为断言锁「真的转过去了」—— 两层都要,因为改了目标路由而静态测试
> 跟着一起改,只有回读能发现「改完之后其实转不过去」。

---

## E. 不要用截图证明的事(写下来免得又有人拿图结案)

- **权限**:图上没有按钮 ≠ 后端拦得住;图上有按钮 ≠ 点得动。两边都要 callMethod。
- **校验**:表单红字是显示,**提交会不会被拦**要断返回值。
- **导航**:见 D1。
- **金额**:见 B6。
- **时区**:CI 恒 UTC,时区 bug 结构性抓不到;涉及日期的断言必须在 `TZ=Asia/Shanghai` 与
  一个负偏移时区各跑一遍(`npm run test:unit` 已是这个规矩)。
