// cy-sheet / cy-scene-sheet · 拖拽契约
//
// 2026-09-01:抓手从「装饰横条」补成真的可拖(当时在逻辑层 setData 跟手)。
// 2026-11-10 qx-u1:跟手搬到视图层(utils/wxs/gesture.wxs),参数换成业主定的:
//   速度 > 0.5 px/ms 或位移 > 面板高 30% 关;否则吸附到最近的档;越过最高档往上拽 × 0.25 并弹回;
//   抓手和头部起拖,正文不起拖;减动效下没有阻尼、吸附直接到位。
//
// 钉的不是"有没有写 touch 绑定",而是**按真实触摸序列驱动 WXS 之后面板去了哪、宿主收到了什么**:
//   · 两条关闭闸(速度 / 位移)各一条用例,各一条负控(拆掉那条闸,用例必须判红)
//   · maskClosable=false / dirty 时与点遮罩同一条闸:过阈值也只弹回;dirty 照样上报 requestclose
//   · 往上拽有阻尼并以弹簧曲线弹回
//   · 组件侧:dismiss 走和 ✕ 同一个出口;宿主不接这次关闭就把面板拉回来
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const Module = require('node:module')
const test = require('node:test')
const { loadWxs, makeEl, makeOwner, sheetDrag, offsetOf } = require('./helpers/gesture-wxs-harness.js')

const ROOT = path.resolve(__dirname, '../..')
const SHEET_JS = path.join(ROOT, 'components/cy/sheet/index.js')
const SHEET_WXML = path.join(ROOT, 'components/cy/sheet/index.wxml')
const SHEET_WXSS = path.join(ROOT, 'components/cy/sheet/index.wxss')
const SCENE_JS = path.join(ROOT, 'components/cy/scene-sheet/index.js')
const SCENE_WXML = path.join(ROOT, 'components/cy/scene-sheet/index.wxml')

function rig(panelHeight = 600) {
  const panel = makeEl({ height: panelHeight })
  const root = makeEl()
  const owner = makeOwner({ '.sh__panel': panel, '.sh': root })
  return { panel, root, owner }
}
const lastStyle = (el) => el.styles[el.styles.length - 1] || {}
const dismissed = (owner) => owner.calls.some((c) => c.name === '_onGestureRelease' && c.detail.action === 'dismiss')
const blocked = (owner) => owner.calls.some((c) => c.name === '_onGestureRelease' && c.detail.action === 'blocked')

/* ——— 视图层(WXS)——— */

test('慢慢拖过面板高 30% ⇒ 关闭(位移闸),面板从松手处落到屏外', () => {
  const gs = loadWxs(); const { panel, owner } = rig(600)
  sheetDrag(gs, owner, { steps: Array(10).fill(20), stepMs: 100 })    // 200px,0.2 px/ms
  assert.ok(dismissed(owner), '慢拖 200px(> 600 × 30%)必须关闭')
  assert.equal(offsetOf(lastStyle(panel)), '100%', '关闭时面板要从当前位置落下去，不能先跳回 0')
})

test('慢慢拖但没到 30% ⇒ 不关,吸回 0 并在过渡结束后撤掉行内样式', () => {
  const gs = loadWxs(); const { panel, owner } = rig(600)
  sheetDrag(gs, owner, { steps: Array(8).fill(20), stepMs: 100 })     // 160px
  assert.equal(owner.calls.length, 0, '没过阈值不该通知宿主')
  assert.equal(offsetOf(lastStyle(panel)), 0)
  assert.match(lastStyle(panel).transition, /cy-motion-standard/, '吸附要有过渡，不能瞬移')
  gs.sheetSettle({ target: { dataset: { gpanel: '1' } }, instance: panel }, owner)
  assert.deepEqual(lastStyle(panel), { transform: '', transition: '' }, '吸回 0 后要撤掉行内 transform,否则压住 morph 退场')
})

test('位移很小但甩得快 ⇒ 关闭(速度闸)', () => {
  const gs = loadWxs(); const { owner } = rig(600)
  sheetDrag(gs, owner, { steps: [20, 20], stepMs: 16 })              // 40px,末段 1.25 px/ms
  assert.ok(dismissed(owner), '快甩必须关闭 —— 这条挂的是速度闸')
})

test('拖拽期间根上挂 sh--dragging,松手摘掉;跟手不带过渡', () => {
  const gs = loadWxs(); const { panel, root, owner } = rig(600)
  const ds = { panel: '.sh__panel', root: '.sh', dragcls: 'sh--dragging', wh: 800 }
  let t = 0
  gs.sheetStart({ currentTarget: { dataset: ds }, touches: [{ clientY: 100 }], timeStamp: t }, owner)
  gs.sheetMove({ currentTarget: { dataset: ds }, touches: [{ clientY: 150 }], timeStamp: (t += 16) }, owner)
  assert.ok(root.classes.has('sh--dragging'))
  assert.equal(offsetOf(lastStyle(panel)), 50, '向下 1:1 跟手')
  assert.equal(lastStyle(panel).transition, 'none')
  gs.sheetEnd({ currentTarget: { dataset: ds }, touches: [], timeStamp: t + 300 }, owner)
  assert.equal(root.classes.has('sh--dragging'), false)
})

test('轻点(位移 < 4px)不进入拖拽:不写样式、不通知宿主 —— 头部的 ✕ 照常能点', () => {
  const gs = loadWxs(); const { panel, owner } = rig(600)
  sheetDrag(gs, owner, { steps: [2, 1], stepMs: 16 })
  assert.equal(panel.styles.length, 0)
  assert.equal(owner.calls.length, 0)
})

test('往上拽:越过最高档只走 25%,松手用弹簧曲线弹回 0', () => {
  const gs = loadWxs(); const { panel, owner } = rig(600)
  const ds = { panel: '.sh__panel', wh: 800 }
  gs.sheetStart({ currentTarget: { dataset: ds }, touches: [{ clientY: 300 }], timeStamp: 0 }, owner)
  gs.sheetMove({ currentTarget: { dataset: ds }, touches: [{ clientY: 200 }], timeStamp: 100 }, owner)
  assert.equal(offsetOf(lastStyle(panel)), -25, '往上 100px 只该走 25px')
  gs.sheetEnd({ currentTarget: { dataset: ds }, touches: [], timeStamp: 200 }, owner)
  assert.equal(offsetOf(lastStyle(panel)), 0)
  assert.match(lastStyle(panel).transition, /cy-ease-spring/, '越界弹回要用弹簧曲线')
  assert.equal(owner.calls.length, 0, '往上拽不该关闭')
})

test('减动效:往上拽顶住不动;吸附直接到位不带过渡', () => {
  const gs = loadWxs(); const { panel, owner } = rig(600)
  const ds = { panel: '.sh__panel', wh: 800, rm: true }
  gs.sheetStart({ currentTarget: { dataset: ds }, touches: [{ clientY: 300 }], timeStamp: 0 }, owner)
  gs.sheetMove({ currentTarget: { dataset: ds }, touches: [{ clientY: 200 }], timeStamp: 100 }, owner)
  assert.equal(offsetOf(lastStyle(panel)), 0, '减动效下没有阻尼位移')
  gs.sheetMove({ currentTarget: { dataset: ds }, touches: [{ clientY: 380 }], timeStamp: 1000 }, owner)
  gs.sheetEnd({ currentTarget: { dataset: ds }, touches: [], timeStamp: 1000 }, owner)
  assert.deepEqual(lastStyle(panel), { transform: '', transition: '' }, '减动效吸附直接到位')
})

test('量不到面板高时降级成只看速度,慢拖到底也不误关', () => {
  const gs = loadWxs(); const { owner } = rig(0)
  sheetDrag(gs, owner, { steps: Array(30).fill(20), stepMs: 100 })
  assert.equal(dismissed(owner), false, 'h=0 时位移闸必须失效')
})

test('不许随手关(blocked)时,过阈值也只弹回,并告诉宿主 blocked', () => {
  const gs = loadWxs(); const { panel, owner } = rig(600)
  sheetDrag(gs, owner, { steps: Array(10).fill(20), stepMs: 100, ds: { blocked: true } })
  assert.equal(dismissed(owner), false, 'blocked 却被拖关了 —— 守卫被绕过')
  assert.ok(blocked(owner), '要告诉宿主 blocked(dirty 时由组件上报 requestclose)')
  assert.equal(offsetOf(lastStyle(panel)), 0, '面板必须弹回，不能停在半路')
  assert.match(lastStyle(panel).transition, /transform/, '弹回要有过渡，不能瞬移')
})

test('退场中(data-off)不接手势', () => {
  const gs = loadWxs(); const { panel, owner } = rig(600)
  sheetDrag(gs, owner, { steps: Array(10).fill(20), stepMs: 100, ds: { off: true } })
  assert.equal(panel.styles.length, 0)
  assert.equal(owner.calls.length, 0)
})

/* ——— 负控:每条闸拆一次,对应用例必须判红 ——— */

test('负控:拆掉速度闸后,快甩用例必须判红', () => {
  const gs = loadWxs((s) => s.replace('var DISMISS_VELOCITY = 0.5;', 'var DISMISS_VELOCITY = 1e9;'))
  const { owner } = rig(600)
  sheetDrag(gs, owner, { steps: [20, 20], stepMs: 16 })
  assert.equal(dismissed(owner), false)
})

test('负控:拆掉位移闸后,慢拖用例必须判红', () => {
  const gs = loadWxs((s) => s.replace('var DISMISS_RATIO = 0.3;', 'var DISMISS_RATIO = 1e9;'))
  const { owner } = rig(600)
  sheetDrag(gs, owner, { steps: Array(10).fill(20), stepMs: 100 })
  assert.equal(dismissed(owner), false)
})

test('负控:不查 blocked 时,blocked 用例必须判红', () => {
  const gs = loadWxs((s) => s.replace("if (r.action === 'dismiss' && !d.blocked) {", "if (r.action === 'dismiss') {"))
  const { owner } = rig(600)
  sheetDrag(gs, owner, { steps: Array(10).fill(20), stepMs: 100, ds: { blocked: true } })
  assert.equal(dismissed(owner), true)
})

/* ——— 组件侧(逻辑层)——— */

function loadComponent(file, mutate) {
  let src = fs.readFileSync(file, 'utf8')
  if (mutate) { const next = mutate(src); assert.notEqual(next, src, '负控注入点写错了'); src = next }
  let captured = null
  const fn = vm.compileFunction(src, ['require', 'module', 'exports', 'Component', 'getApp', 'wx'], { filename: file })
  const prevBehavior = global.Behavior
  const prevWx = global.wx
  global.Behavior = (o) => o
  global.wx = global.wx || { getWindowInfo: () => ({ windowWidth: 375, windowHeight: 812, statusBarHeight: 20 }) }
  try {
    fn(Module.createRequire(file), { exports: {} }, {}, (o) => { captured = o }, () => ({ globalData: {} }),
      { getWindowInfo: () => ({ windowWidth: 375, windowHeight: 812, statusBarHeight: 20 }) })
  } finally { global.Behavior = prevBehavior; global.wx = prevWx }
  return captured
}

function instance(def, data) {
  const events = []
  const propDefaults = {}
  for (const [k, v] of Object.entries(def.properties || {})) propDefaults[k] = v && v.value
  const inst = {
    data: Object.assign({}, propDefaults, def.data, { _closing: false, show: true }, data),
    setData(patch) { Object.assign(this.data, patch) },
    triggerEvent(name, detail) { events.push({ name, detail }) },
    events,
  }
  for (const [k, v] of Object.entries(def.methods)) inst[k] = v.bind(inst)
  return inst
}

for (const [label, file] of [['cy-sheet', SHEET_JS], ['cy-scene-sheet', SCENE_JS]]) {
  test(`${label}:dismiss 走 ✕ 同一出口(requestclose + close)`, () => {
    const inst = instance(loadComponent(file), {})
    inst._onGestureRelease({ action: 'dismiss' })
    assert.deepEqual(inst.events.map((e) => e.name), ['requestclose', 'close'])
    assert.equal(inst.events[1].detail.reason, 'drag')
    clearTimeout(inst._dragOutTimer)
  })

  test(`${label}:dirty 时 blocked 只上报 requestclose,不发 close`, () => {
    const inst = instance(loadComponent(file), { dirty: true })
    inst._onGestureRelease({ action: 'blocked' })
    assert.deepEqual(inst.events.map((e) => e.name), ['requestclose'])
  })

  test(`${label}:不脏的 blocked(不许随手关)什么都不发`, () => {
    const inst = instance(loadComponent(file), { dirty: false })
    inst._onGestureRelease({ action: 'blocked' })
    assert.deepEqual(inst.events, [])
  })

  test(`${label}:宿主不接这次关闭(show 仍为 true)⇒ 发 gcmd 把面板拉回来`, async () => {
    const inst = instance(loadComponent(file), {})
    const before = inst.data._gestureCmd
    inst._onGestureRelease({ action: 'dismiss' })
    await new Promise((r) => setTimeout(r, 450))
    assert.equal(inst.data._gestureCmd, before + 1)
  })
}

test('cy-sheet 负控:拿掉宿主不接时的回拉,上一条必须判红', async () => {
  const def = loadComponent(SHEET_JS, (s) => s.replace('if (this.data.show && !this.data._closing) {', 'if (false) {'))
  const inst = instance(def, {})
  inst._onGestureRelease({ action: 'dismiss' })
  await new Promise((r) => setTimeout(r, 450))
  assert.equal(inst.data._gestureCmd, 0)
})

/* ——— 接线 ——— */

test('cy-sheet wxml:抓手与头部起拖(WXS),正文不起拖;面板收 transitionend 与 gcmd', () => {
  const wxml = fs.readFileSync(SHEET_WXML, 'utf8')
  assert.match(wxml, /<wxs module="gs" src="\.\.\/\.\.\/\.\.\/utils\/wxs\/gesture\.wxs" \/>/)
  const grip = /<view class="sh__grip"[\s\S]*?>/.exec(wxml)
  assert.ok(grip, '抓手必须是 .sh__grip(热区容器)')
  for (const ev of ['catchtouchstart', 'catchtouchmove', 'catchtouchend', 'catchtouchcancel']) {
    assert.match(grip[0], new RegExp(ev + '="\\{\\{gs\\.sheet'), `抓手缺 ${ev} —— 少 cancel 会让来电打断后卡在拖拽态`)
  }
  assert.match(grip[0], /data-blocked="\{\{!maskClosable \|\| dirty\}\}"/, '和点遮罩同一条闸')
  assert.match(grip[0], /data-rm="\{\{reducedMotion\}\}"/)
  const head = /<view class="sh__head [\s\S]*?>/.exec(wxml)
  assert.match(head[0], /bindtouchstart="\{\{gs\.sheetStart\}\}"/, '头部也能起拖')
  assert.match(head[0], /bindtouchcancel="\{\{gs\.sheetEnd\}\}"/)
  const body = /<scroll-view class="sh__body"[^>]*>/.exec(wxml)
  assert.doesNotMatch(body[0], /gs\.sheet/, '正文不起拖,内容照常滚动')
  assert.match(wxml, /class="sh__panel[^"]*"[^>]*bindtransitionend="\{\{gs\.sheetSettle\}\}"[^>]*change:gcmd="\{\{gs\.sheetCmd\}\}" gcmd="\{\{_gestureCmd\}\}"/)
  assert.match(wxml, /class="sh__panel[^"]*"[^>]*style="\{\{\(_morphOpen && !_closing\) \? '' : _morphStyle\}\}"/,
    '.sh__panel 的行内 style 必须保持 morph 契约的原样')
  assert.match(wxml, /_dragOut \? 'sh--drag-out' : ''/)
})

test('cy-sheet wxss:甩出去关闭时退场不再从 0 播 sh-down', () => {
  const wxss = fs.readFileSync(SHEET_WXSS, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '')
  assert.match(wxss, /\.sh\.sh--drag-out \.sh__panel \{ animation: none; \}/)
  assert.match(wxss, /\.sh\.sh--dragging \.sh__panel \{ animation: none; transition: none; \}/)
})

test('cy-scene-sheet wxml:half / full 头部起拖,page 档没有', () => {
  const wxml = fs.readFileSync(SCENE_WXML, 'utf8')
  assert.match(wxml, /<wxs module="gs" src="\.\.\/\.\.\/\.\.\/utils\/wxs\/gesture\.wxs" \/>/)
  const head = /<view class="ss__head" wx:else[\s\S]*?>/.exec(wxml)
  assert.ok(head, '头部只在非 page 档渲染')
  for (const ev of ['bindtouchstart', 'catchtouchmove', 'bindtouchend', 'bindtouchcancel']) assert.match(head[0], new RegExp(ev + '="\\{\\{gs\\.sheet'))
  assert.match(head[0], /data-blocked="\{\{!closable \|\| dirty\}\}"/, '没有 ✕ 的场景不许拖关')
  assert.match(wxml, /class="ss__panel"[^>]*bindtransitionend="\{\{gs\.sheetSettle\}\}"[^>]*gcmd="\{\{_gestureCmd\}\}"/)
})

test('qx-green:窗口高由视图层量遮罩(data-whsel),不走 setData;量到才有中间档,量不到只回 0', () => {
  const gs = loadWxs()
  const panel = makeEl({ height: 600 })
  const owner = makeOwner({ '.sh__panel': panel, '.sh': makeEl(), '.sh__mask': makeEl({ height: 800 }) })
  sheetDrag(gs, owner, { steps: Array(6).fill(20), stepMs: 100, ds: { wh: 0, whsel: '.sh__mask', detents: 'half' } })
  assert.equal(offsetOf(lastStyle(panel)), 120, '600 高的面板在 800 高窗口里 half 档 = 600 - 0.6 × 800')
  const bare = makeEl({ height: 600 })
  const noMask = makeOwner({ '.sh__panel': bare, '.sh': makeEl() })
  sheetDrag(gs, noMask, { steps: Array(6).fill(20), stepMs: 100, ds: { wh: 0, whsel: '.sh__mask', detents: 'half' } })
  assert.equal(offsetOf(lastStyle(bare)), 0, '量不到窗口高就只有自身一档')
  assert.match(fs.readFileSync(SHEET_WXML, 'utf8'), /data-whsel="\.sh__mask"/)
  assert.match(fs.readFileSync(SCENE_WXML, 'utf8'), /data-whsel="\.ss__mask"/)
})
