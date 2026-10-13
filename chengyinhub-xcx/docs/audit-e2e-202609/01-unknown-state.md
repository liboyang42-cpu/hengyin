# 阶段1(补写) · 写请求 unknown 态逃生门盘点

⚠️ 本文是**重建件**:上一轮的 `01-unknown-state.md` 与 `00-baseline.md`、`event-contract.js` 等文件在本会话压缩后已不在磁盘上(`docs/audit-e2e-202609/` 只有 6 个文件)。下面每条都重新回磁盘核过,行号以当前工作区为准;旧稿里对不上磁盘的条目已单列「已否决」。

## 机制:unknown 是终态,而且是设计如此

`utils/checkout/checkout-workflow.js`:

```js
function submit(payload, handlers) {
  // 防重:仅 idle 可发起(submitting/paying/verifying 在途、done 已完成均忽略)。
  if (state !== 'idle') return false;
```
```js
} else if (verifyRes && verifyRes.status === 'unknown') {
  state = 'unknown'; // 结果不明时禁止重建订单，必须从订单页/服务端回读
  call(handlers.onPayUnknown, verifyRes);
}
```

对外只暴露 `submit / destroy / getState / isBusy` —— **没有任何把 unknown 复位成 idle 的接口**。所以 `submit()` 在 unknown 之后恒返回 `false`,即 `submitted === false`。

这个闸本身是对的(防重复建单扣款)。**问题全在调用方有没有把闸解释给人看。**

## 六处 `!submitted` 分流(实测清单)

| 宿主 | 行号 | 处理 | 判定 |
|---|---|---|---|
| `pages/activity/baoming/baoming.js` | `:1563-1575` | `if (that._workflow.getState() === 'unknown') modal.show({title:'支付结果待确认', content:'这笔付款正在与服务端核对,不要重复支付。稍后可到「我的-订单」查看最终状态。'})`,且注释写明「闸不能是哑巴」 | ✅ 正确先例 |
| `pages/coop/list/index.js` | `:609-612` | `that._finishAction(actionKey, '', '支付正在处理中，请勿重复提交')` | ✅ 给理由 |
| `components/cy/scene-member-order-detail/index.js` | `:521-524` | 只 `setData({paying:false}) + cyLoading.hide()` | ❌ 静默 |
| `components/cy/scene-member-order-history/index.js` | `:351-354` | 只 `setData({isPaying:false}) + cyLoading.hide()` | ❌ 静默 |
| `pages/topic/index/index.js` | `:1076-1079` | 只 `_selfPlaySubmitting = false; loading.hide();` | ❌ 静默 |
| `pages/merchant/game-node/index.js` | `:693-698` | 写态机复位 `writeState:'idle'`,另配 `canRetryUnknownWrite` + `confirmUnknownWrite():701` | ✅ 商家侧最完整的一版,可当模板 |
| `subpackageMember/components/scene-member-participation-detail/index.js` `:97` / `pages/merchant/index/index.js` `:848` / `components/cy/profile/index.js` `:484` | — | 仅 `cyLoading.hide()`,这三处是**非支付类**写口(核销/资料),不涉 unknown | 不计 |

### F-UK-1 [P1] 三处「付了钱但结果不明」的入口点了没反应

三张静默卡(订单详情、订单历史、主题页自玩)共同形状:用户按「继续支付/去支付」→ 若 workflow 处于 unknown,`submit()` 返回 false → 页面只把 loading 撤掉。**按钮看起来坏了,而且用户刚付过钱**。

- 复现路径:任一路付费报名 → 支付回调后 `verifyPayment` 返回 `status:'unknown'`(如支付确认接口非 200/超时)→ 弹层关闭 → 再点同页「继续支付」→ 无反馈。
- 危害排序:`scene-member-order-detail`(钱已付、最焦虑的页)> `scene-member-order-history` > `pages/topic/index`(自玩快捷下单)。
- 修法方向(留给修复批):照 `baoming.js:1569` 的判据补一条 `getState() === 'unknown'` 说明,或复用 `game-node` 的 `canRetryUnknownWrite` + 手动「我已确认」回读形状。**不要**为了让按钮动起来而复位 state —— 那正是这条闸要防的事。

## 已否决(上一轮记录里对不上磁盘的条目)

| 旧断言 | 实情 |
|---|---|
| `subpackageMember/tixian/tixian.js:314` 静默 catch,而 `tixian.wxml:75` 留了错误位没人填 | **两边都是反的**。`tixian.js` 每个失败分支都写 `submitError`(`:278` 业务失败、`:286-289` 网络失败、`:299` catch),`tixian.wxml:74` 的 `<cy-inline-error wx:if="{{submitError}}" ... action="重试" bind:action="saveData" />` 正好接住;余额取不到另有 `balanceErr` → `wxml:15`「暂时取不到」+ `:18` 重试。提现这条链是全仓最规范的之一 |
| 三处静默宿主的路径写成 `subpackageMember/components/scene-member-order-{detail,history}/index.js:543/351` | 组件实际在 `components/cy/` 下,行号分别是 `:521` 与 `:351` |
