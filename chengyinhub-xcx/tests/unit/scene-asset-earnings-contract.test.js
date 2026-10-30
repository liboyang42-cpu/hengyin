// H01 账户收益场景(方案 §5.2 + §10.3)。
// 盯三件事:①金额三态不串(loading 骨架 / error 不渲任何金额 / ready 才有数字)
//          ②余额字段为 null/空(库列可空=还没收益)按 0.00 展示,不算网络错
//            (2026-09-16 截图冒烟:真实商家号余额为空被渲染成「收益没加载出来,网络可能不稳定」)
//          ③金额卡瘦身:卡内只剩数字,邀请记录留在卡外 cy-cell
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('../helpers/ui-sandbox-vm.js') // 沙箱里装真 utils/toast.js·loading.js

const ROOT = path.resolve(__dirname, '../..')
const DIR = path.join(ROOT, 'components/cy/scene-asset-earnings')
const read = (f) => fs.readFileSync(path.join(DIR, f), 'utf8')

/** 把组件 js 跑起来,拿到 Component() 的定义对象 */
function loadComponent(source) {
  const src = source || fs.readFileSync(path.join(DIR, 'index.js'), 'utf8')
  let def = null
  const requests = []
  const sandbox = {
    Component: (d) => { def = d },
    // 组件在模块顶层 const app = getApp(),globalData 必须走 getter ——
    // 写成普通字段会在加载那一刻按值捕获,后面换 __globalData 就打不进去了。
    getApp: () => ({
      get globalData() { return sandbox.__globalData },
      getUserID: () => sandbox.__memberId,
      getUserRole: () => sandbox.__role,
      getUserType: () => sandbox.__type == null ? (sandbox.__role === 'merchant' ? 2 : 1) : sandbox.__type,
      sendRequest: (opts) => { requests.push(opts) },
    }),
    require: (p) => {
      // utils/toast.js 真件,在只带本沙箱 wx 的上下文里求值(回落 wx.showToast 才打得到下面的桩)
      // 本场景的权限夹具先于通用 UI 加载器；toast/loading/modal 仍跑真模块。
      if (p.indexOf('roleGuard') >= 0) return {
        load: (cb) => { sandbox.__guardLoads += 1; if (sandbox.__deferGuard) sandbox.__guardCallbacks.push(cb); else cb() },
        can: (cap) => { sandbox.__guardCaps.push(cap); return cap === 'withdrawable' && sandbox.__withdrawable },
      }
      if (p.includes('session/login-reload')) return require(path.join(ROOT, 'utils/session/login-reload.js'))
      // 2026-10-28 G-9:本月收入 / 我的资产的纯聚合模块,给真件
      if (p.includes('earnings-view')) return require(path.join(ROOT, 'utils/earnings-view.js'))
      if (p.includes('funds-stages/view-model')) return require(path.join(ROOT, 'components/cy/funds-stages/view-model.js'))
      if (vm.isUiModule(p)) return vm.loadUiModule(p, vm.createContext({ wx: sandbox.wx }))
      // countUp 接线(2026-08-21):motion 是纯 node 兼容模块,直接给真件;
      // 减动效偏好固定 true → 单帧直落,金额断言保持同步可判
      if (p.indexOf('motion-preference') >= 0) return { readReducedMotion: () => sandbox.__reducedMotion }
      if (p.indexOf('motion') >= 0) {
        const real = require(path.join(ROOT, 'utils/motion.js'))
        return { countUp(from, to, onFrame, options) {
          if (!sandbox.__manualMotion) return real.countUp(from, to, onFrame, options)
          const run = { onFrame, onDone: options.onDone, timers: [] }
          sandbox.__motions.push(run)
          return real.countUp(from, to, onFrame, Object.assign({}, options, {
            setTimeout(fn) { const timer = { fn, cancelled: false }; run.timers.push(timer); return timer },
            clearTimeout(timer) { timer.cancelled = true },
          }))
        } }
      }
      throw new Error('unexpected require: ' + p)
    },
    wx: {
      showToast: (o) => { sandbox.__toast = o },
      navigateTo: (o) => { sandbox.__navigations.push(o.url) },
      switchTab: (o) => { sandbox.__navigations.push(o.url) },
    },
    module: { exports: {} },
    __globalData: { userInfo: null },
    __withdrawable: true,
    __role: 'player',
    __guardLoads: 0,
    __guardCaps: [],
    __memberId: 1,
    __type: null,
    __deferGuard: false,
    __guardCallbacks: [],
    __reducedMotion: true,
    __manualMotion: false,
    __motions: [],
    __navigations: [],
  }
  const fn = new Function('Component', 'getApp', 'require', 'wx', 'module', src)
  fn(sandbox.Component, sandbox.getApp, sandbox.require, sandbox.wx, sandbox.module)
  return { def, requests, sandbox }
}

/** 造一个最小的组件实例:setData 直接合进 data */
function instantiate({ globalData, withdrawable, role, source, memberId, type, deferGuard, reducedMotion, manualMotion } = {}) {
  const { def, requests, sandbox } = loadComponent(source)
  if (role !== undefined) sandbox.__role = role
  if (memberId !== undefined) sandbox.__memberId = memberId
  if (type !== undefined) sandbox.__type = type
  if (deferGuard !== undefined) sandbox.__deferGuard = deferGuard
  if (reducedMotion !== undefined) sandbox.__reducedMotion = reducedMotion
  if (manualMotion !== undefined) sandbox.__manualMotion = manualMotion
  if (globalData !== undefined) sandbox.__globalData = globalData
  if (withdrawable !== undefined) sandbox.__withdrawable = withdrawable
  const ctx = {
    data: JSON.parse(JSON.stringify(def.data)),
    properties: {},
    events: [],
    setData(patch) { Object.assign(this.data, patch) },
    triggerEvent(name, detail) { this.events.push({ name, detail }) },
  }
  Object.assign(ctx, def.methods)
  return { ctx, def, requests, sandbox }
}

test('缓存里有合法余额时直接 ready,不再发请求', () => {
  const { ctx, requests } = instantiate({ globalData: { userInfo: { id: 1, balance: '12.5' } } })
  ctx.load()
  assert.equal(ctx.data.state, 'ready')
  assert.equal(ctx.data.balance, '12.50')
  assert.equal(requests.length, 0)
})

test('余额字段缺失(null/undefined/空串)是正常数据,按 0.00 展示而不是网络错', () => {
  for (const missing of [null, undefined, '', '  ']) {
    const { ctx, requests } = instantiate({ globalData: { userInfo: null } })
    ctx.load()
    assert.equal(ctx.data.state, 'loading', '发请求前应停在 loading')
    requests[0].success({ code: '200', data: { balance: missing } })
    assert.equal(ctx.data.state, 'ready', `balance=${String(missing)} 是「还没有收益」,不该落 error`)
    assert.equal(ctx.data.balance, '0.00', `balance=${String(missing)} 应按 0.00 展示`)
  }
})

test('余额是坏值(非数字/负数)仍落 error,不摆伪 ¥0.00', () => {
  for (const bad of ['abc', -1, NaN]) {
    const { ctx, requests } = instantiate({ globalData: { userInfo: null } })
    ctx.load()
    requests[0].success({ code: '200', data: { balance: bad } })
    assert.equal(ctx.data.state, 'error', `balance=${String(bad)} 是坏值不是「没有收益」,应落 error`)
    assert.equal(ctx.data.balance, '', 'error 态不得残留任何金额')
  }
})

test('请求非 200 / 缺 data 落 error(真失败才报错)', () => {
  const nonOk = instantiate({ globalData: { userInfo: null } })
  nonOk.ctx.load()
  nonOk.requests[0].success({ code: '500', msg: '服务异常' })
  assert.equal(nonOk.ctx.data.state, 'error')

  const noData = instantiate({ globalData: { userInfo: null } })
  noData.ctx.load()
  noData.requests[0].success({ code: '200' })
  assert.equal(noData.ctx.data.state, 'error')
})

test('余额是 0 是合法数据,必须 ready 并渲 0.00(不能当成取不到)', () => {
  const { ctx, requests } = instantiate({ globalData: { userInfo: null } })
  ctx.load()
  requests[0].success({ code: '200', data: { balance: 0 } })
  assert.equal(ctx.data.state, 'ready')
  assert.equal(ctx.data.balance, '0.00')
})

test('请求失败落 error', () => {
  const { ctx, requests } = instantiate({ globalData: { userInfo: null } })
  ctx.load()
  requests[0].fail()
  assert.equal(ctx.data.state, 'error')
})

test('组件销毁后忽略晚到请求,不得重新启动金额动画或 setData', () => {
  const { ctx, def, requests } = instantiate({ globalData: { userInfo: null } })
  def.lifetimes.attached.call(ctx)
  def.lifetimes.detached.call(ctx)
  const snapshot = JSON.stringify(ctx.data)
  requests[0].success({ code: '200', data: { balance: 88 } })
  assert.equal(JSON.stringify(ctx.data), snapshot)
  requests[0].fail()
  assert.equal(JSON.stringify(ctx.data), snapshot)
})

function assertWithdrawCapability(source) {
  for (const role of ['player', 'merchant']) {
    const allowed = instantiate({ globalData: { userInfo: null }, withdrawable: true, role, source })
    allowed.ctx.load(true)
    allowed.requests[0].success({ code: 200, data: { balance: 0 } })
    allowed.ctx.openWithdraw()
    assert.deepEqual(allowed.ctx.events, [{ name: 'open', detail: { id: 'member-withdraw' } }])
    assert.equal(allowed.sandbox.__guardLoads, 1)
    assert.deepEqual(allowed.sandbox.__guardCaps, ['withdrawable'])

    const denied = instantiate({ globalData: { userInfo: null }, withdrawable: false, role, source })
    denied.ctx.load(true)
    denied.requests[0].success({ code: 200, data: { balance: 0 } })
    denied.ctx.openWithdraw()
    assert.deepEqual(denied.ctx.events, [])
    assert.equal(denied.sandbox.__guardLoads, 1)
    assert.deepEqual(denied.sandbox.__guardCaps, ['withdrawable'])
    assert.match(denied.sandbox.__toast.title, /暂无提现权限/)
  }
}

test('提现只认 withdrawable 能力，不按玩家或商家角色猜测', () => {
  assertWithdrawCapability()
})

test('提现权限负控：删能力闸或改成按商家角色放行必须判红', () => {
  const source = read('index.js')
  const withoutGuard = source.replace("!roleGuard.can('withdrawable')", 'false')
  const byRole = source.replace("!roleGuard.can('withdrawable')", "app.getUserRole() !== 'merchant'")
  for (const changed of [withoutGuard, byRole]) {
    assert.notEqual(changed, source, '负控锚点失效')
    const breached = instantiate({ withdrawable: false, role: 'merchant', source: changed })
    breached.ctx.load(true)
    breached.requests[0].success({ code: 200, data: { balance: 0 } })
    breached.ctx.openWithdraw()
    assert.equal(breached.ctx.events.filter((event) => event.name === 'open').length, 1,
      '负控必须复现未有提现能力却打开提现场景')
    assert.throws(() => assertWithdrawCapability(changed), assert.AssertionError)
  }
})

test('三态在 wxml 里互斥,error 分支不含任何金额绑定', () => {
  const wxml = read('index.wxml')
  assert.match(wxml, /wx:if="\{\{state === 'loading'\}\}"[\s\S]*?cy-skeleton|cy-skeleton[^>]*wx:if="\{\{state === 'loading'\}\}"/)
  const errorBlock = wxml.slice(wxml.indexOf("state === 'error'"), wxml.indexOf('<block wx:else>'))
  assert.doesNotMatch(errorBlock, /money\.amount|\{\{balance\}\}|¥/, 'error 态不得出现任何金额')
})

// 2026-10-28 G-9 业主改版(取代 D33 的白卡 + 三颗灰按钮):Revolut 式 —— 余额居中大字,
// 下面四个圆形动作 提现 / 明细 / 分析 / 更多(提现记录、邀请记录收进「更多」),再下面是本月收入与我的资产。
test('G-9 余额只承载金额，四个动作在余额下方，次级入口收进「更多」', () => {
  const wxml = read('index.wxml')
  const card = wxml.slice(wxml.indexOf('<view class="ae-card">'), wxml.indexOf('<view class="ae-actions">'))
  assert.match(card, /ae-card__label[\s\S]*ae-card__amount/)
  assert.doesNotMatch(card, /cy-cell|ae-action/, '入口不得留在余额区内')
  const actions = wxml.slice(wxml.indexOf('<view class="ae-actions">'), wxml.indexOf('<cy-empty'))
  for (const t of ['提现', '明细', '分析', '更多']) assert.match(actions, new RegExp(`ae-action[^>]*[\\s\\S]*?<text>${t}</text>`))
  // 走查 v2 R10(2026-10-30):「更多」贴底(不再 inset),不重复外面已有的结算明细
  assert.ok(wxml.indexOf('<cy-sheet show="{{moreShow}}"') >= 0, '更多弹窗与其他弹窗同样贴底')
  const more = wxml.slice(wxml.indexOf('<cy-sheet show="{{moreShow}}"'))
  assert.doesNotMatch(more, /openSettlement/, '结算明细不在更多里重复')
  assert.match(more, /class="tm-row" bindtap="openWithdrawHistory"/, '提现记录必须保留入口')
  assert.match(more, /class="tm-row" bindtap="openInvite"/, '邀请记录必须保留入口')
  assert.match(wxml, /<cy-earnings-overview bind:detail="openIncomeDetail" \/>/)
})

test('G-9 动作是圆形图标 + 文字，热区走统一按钮高度', () => {
  const wxss = read('index.wxss')
  const card = wxss.slice(wxss.indexOf('.ae-card {'), wxss.indexOf('.ae-card__label'))
  const action = wxss.slice(wxss.indexOf('.ae-action {'), wxss.indexOf('.ae-section'))
  assert.match(card, /border:\s*0/)
  assert.match(action, /min-height:\s*var\(--cy-btn-h\)/)
  assert.match(action, /border-radius:\s*50%/)
  assert.match(action, /background:\s*var\(--cy-color-action-secondary-bg\)/)
})

test('不重刷面板底色(否则和 .ss__panel 叠成近实色)', () => {
  const wxss = read('index.wxss')
  const root = wxss.slice(wxss.indexOf('.ae {'), wxss.indexOf('.ae-card {'))
  assert.doesNotMatch(root, /background:\s*var\(--cy-color-bg-surface\)/)
})

// CU-M-144(2026-09-24 走查):商家从「财务 → 去我的资产」进的就是这一页。余额为 0 时空态过去写
// 「发布路线与场次获得创作收益，或创建俱乐部获得分润」—— 后半句把主办分润说成只有建俱乐部才拿得到,
// 而商家自有主题的分润同样会落进这个余额(asset-income 的 club 分支读的就是它)。空态按余额真来路说。
function assertEarningsEmptyState(wxml) {
  const empty = wxml.match(/<cy-empty[^>]*title="还没有收益"[^>]*>/)
  assert.ok(empty, '余额为 0 必须有空态')
  assert.doesNotMatch(empty[0], /创建俱乐部/, '空态不许把主办分润限定成只有俱乐部才有')
  assert.doesNotMatch(empty[0], /\bsub="/, '空态只留标题和按钮，不写说明句')
  return empty[0]
}

test('CU-M-144 空态按余额真来路说，不把主办分润限定成俱乐部', () => {
  assertEarningsEmptyState(read('index.wxml'))
})

test('negative control CU-M-144: 空态退回「创建俱乐部获得分润」必须判红', () => {
  const wxml = read('index.wxml')
  const reverted = wxml.replace('title="还没有收益"',
    'title="还没有收益" sub="发布路线与场次获得创作收益，或创建俱乐部获得分润"')
  assert.notEqual(reverted, wxml, '负控锚点失效(源码已改动?)')
  assert.throws(() => assertEarningsEmptyState(reverted), assert.AssertionError)
})

function readyBalance(options = {}, balance = 12.5) {
  const harness = instantiate(options)
  harness.ctx.load(true)
  harness.requests[0].success({ code: 200, data: { balance } })
  return harness
}

test('缓存余额必须有同一账号归属；缺ID或其他账号缓存改走实际请求', () => {
  for (const userInfo of [{ balance: 88 }, { id: 2, balance: 88 }]) {
    const h = instantiate({ globalData: { userInfo } })
    h.ctx.load(false)
    assert.equal(h.requests.length, 1)
    assert.equal(h.requests[0].data.member_id, 1)
    assert.equal(h.ctx.data.balance, '')
  }
})

for (const callback of ['success', 'fail', 'successStatusAbnormal']) {
  test(`换号后的旧余额 ${callback} 回调清除旧值且不自动重发已登录账号请求`, () => {
    const h = readyBalance()
    h.ctx.refresh()
    h.sandbox.__memberId = 2
    h.requests[1][callback]({ code: 200, data: { balance: 88 } })
    assert.equal(h.ctx.data.state, 'error')
    assert.equal(h.ctx.data.balance, '')
    assert.equal(h.ctx.data.balanceDisplay, '')
    assert.equal(h.requests.length, 2)
  })
}

for (const [field, value] of [['__role', 'merchant'], ['__type', 2]]) {
  test(`同账号${field}变化也拒绝旧余额结果`, () => {
    const h = instantiate()
    h.ctx.load(true)
    h.sandbox[field] = value
    h.requests[0].success({ code: 200, data: { balance: 88 } })
    assert.equal(h.ctx.data.state, 'error')
    assert.equal(h.ctx.data.balance, '')
  })
}

test('游客不发送收益私有读，登录完成后只读当前账号并接纳真实空余额', () => {
  const h = instantiate({ memberId: 0 })
  h.ctx.load(true)
  assert.equal(h.requests.length, 0)
  assert.equal(h.ctx.data.state, 'login')
  h.sandbox.__memberId = 2
  h.def.pageLifetimes.show.call(h.ctx)
  assert.equal(h.requests.length, 1)
  assert.equal(h.requests[0].data.member_id, 2)
  h.requests[0].success({ code: 200, data: { balance: null } })
  assert.equal(h.ctx.data.balance, '0.00')
})

test('同账号普通续期后正常余额响应仍可落定', () => {
  const h = instantiate()
  h.ctx.load(true)
  h.sandbox.__globalData.token = 'controlled-renewed-session'
  h.requests[0].success({ code: 200, data: { balance: 12.5 } })
  assert.equal(h.ctx.data.state, 'ready')
  assert.equal(h.ctx.data.balance, '12.50')
})

for (const callback of ['fail', 'successStatusAbnormal']) {
  test(`刷新开始即清除旧金额，当前${callback}不能遗留先前金额`, () => {
    const h = readyBalance()
    h.ctx.refresh()
    assert.equal(h.ctx.data.state, 'loading')
    assert.equal(h.ctx.data.balance, '')
    assert.equal(h.ctx.data.balanceDisplay, '')
    h.requests[1][callback]()
    assert.equal(h.ctx.data.state, 'error')
    assert.equal(h.ctx.data.balance, '')
  })
}

test('页面隐藏后旧回调无效，返回页面自动读取当前身份', () => {
  const h = instantiate()
  h.def.lifetimes.attached.call(h.ctx)
  h.def.pageLifetimes.hide.call(h.ctx)
  const hidden = JSON.stringify(h.ctx.data)
  for (const callback of ['success', 'fail', 'successStatusAbnormal']) {
    h.requests[0][callback]({ code: 200, data: { balance: 88 } })
    assert.equal(JSON.stringify(h.ctx.data), hidden)
  }
  h.sandbox.__memberId = 2
  h.def.pageLifetimes.show.call(h.ctx)
  assert.equal(h.requests.length, 2)
  assert.equal(h.requests[1].data.member_id, 2)
  h.requests[1].success({ code: 200, data: { balance: 0 } })
  assert.equal(h.ctx.data.balance, '0.00')
})

test('页面显示时身份未变不重复取数，变更身份则清除旧金额并重读', () => {
  const h = readyBalance()
  h.def.pageLifetimes.show.call(h.ctx)
  assert.equal(h.requests.length, 1)
  h.sandbox.__memberId = 2
  h.def.pageLifetimes.show.call(h.ctx)
  assert.equal(h.requests.length, 2)
  assert.equal(h.ctx.data.state, 'loading')
  assert.equal(h.ctx.data.balance, '')
})

test('真实金额动画的排队帧在换号后不得显示旧余额', () => {
  const h = readyBalance({ reducedMotion: false, manualMotion: true }, 88)
  const run = h.sandbox.__motions[0]
  h.sandbox.__memberId = 2
  run.timers[0].fn()
  assert.equal(h.ctx.data.balance, '')
  assert.equal(h.ctx.data.balanceDisplay, '')
  assert.equal(h.ctx.data.state, 'error')
  assert.ok(run.timers.every((timer) => timer.cancelled))
})

test('旧动画完成回调不能清除新动画句柄；旧帧也不能覆盖新金额', () => {
  const h = readyBalance({ reducedMotion: false, manualMotion: true }, 88)
  const old = h.sandbox.__motions[0]
  h.ctx.refresh()
  h.requests[1].success({ code: 200, data: { balance: 20 } })
  const currentStop = h.ctx._stopCountUp
  old.onDone()
  old.onFrame(88)
  assert.equal(h.ctx._stopCountUp, currentStop)
  assert.equal(h.ctx.data.balance, '20.00')
  assert.equal(h.ctx.data.balanceDisplay, '0.00')
})

for (const change of ['account', 'role', 'type', 'hide', 'detach', 'refresh']) {
  test(`提现权限等待期间${change}变化，旧回调不能打开新身份场景`, () => {
    const h = readyBalance({ deferGuard: true })
    h.ctx.openWithdraw()
    assert.equal(h.sandbox.__guardCallbacks.length, 1)
    if (change === 'account') h.sandbox.__memberId = 2
    if (change === 'role') h.sandbox.__role = 'merchant'
    if (change === 'type') h.sandbox.__type = 2
    if (change === 'hide') h.def.pageLifetimes.hide.call(h.ctx)
    if (change === 'detach') h.def.lifetimes.detached.call(h.ctx)
    if (change === 'refresh') h.ctx.refresh()
    h.sandbox.__guardCallbacks[0]()
    assert.deepEqual(h.ctx.events, [])
    assert.equal(h.sandbox.__toast, undefined)
  })
}

test('金额结果还未确定时不执行提现能力请求或打开场景', () => {
  const h = instantiate()
  h.ctx.load(true)
  h.ctx.openWithdraw()
  assert.equal(h.sandbox.__guardLoads, 0)
  assert.deepEqual(h.ctx.events, [])
})

test('旧身份的明细、记录、邀请、空态入口都不得执行导航', () => {
  const h = readyBalance()
  h.sandbox.__memberId = 2
  for (const action of ['openIncomeDetail', 'openWithdrawHistory', 'openInvite', 'goPublish']) h.ctx[action]()
  assert.deepEqual(h.ctx.events, [])
  assert.deepEqual(h.sandbox.__navigations, [])
  assert.equal(h.ctx.data.balance, '')
})

test('当前身份的正常明细和记录入口保留，不增加请求', () => {
  const h = readyBalance()
  for (const action of ['openIncomeDetail', 'openWithdrawHistory', 'openInvite', 'goPublish']) h.ctx[action]()
  assert.equal(h.ctx.events.length, 2)
  assert.equal(h.sandbox.__navigations.length, 2)
  assert.equal(h.requests.length, 1)
})

test('收益错误态不把身份失效误报为网络原因，提现标签匹配客服入口', () => {
  const wxml = read('index.wxml')
  assert.doesNotMatch(wxml, /网络可能不稳定/)
  assert.match(wxml, /aria-label="联系平台客服处理提现"/)
})


test('收益真实401/403与网络异常分别是登录/权限/读取故障，均不保留旧金额', () => {
  for (const [code,state] of [[401,'login'],[403,'permission'],[500,'error']]) {
    const h=readyBalance();h.ctx.refresh();h.requests[1].success({code,msg:'真实响应'});
    assert.equal(h.ctx.data.state,state);assert.equal(h.ctx.data.balance,'');assert.equal(h.ctx.data.balanceDisplay,'');
  }
});
