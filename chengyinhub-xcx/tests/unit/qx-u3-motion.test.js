// qx-u3 motion(ref3):成功 morph + 叠层景深。
// 钉三件事:
//   ① 叠层景深由弹层宿主按打开顺序统一判定(utils/sheet-depth.js + behaviors/sheet-depth.js),页面不再逐个传 under;
//      四个宿主(cy-sheet / cy-scene-sheet / project-drawer / project-layer)同一组数值 .94 / -10px / 压暗。
//   ② 成功 morph 的节拍(utils/morph-success.js):done 与收尾字段同一次写入、先写成功状态再撤 done、减动效不停留。
//   ③ 五个成功时刻真的接上了 morph,而且只在服务端确认成功之后才亮勾。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')
const Module = require('node:module')

const ROOT = path.resolve(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')

const depth = require('../../utils/sheet-depth.js')
const morph = require('../../utils/morph-success.js')

function fakeLayer(pageId) {
  return {
    data: { _under: false },
    getPageId: () => pageId,
    _applySheetDepth(under) { this.data._under = under },
  }
}

test('景深纯函数:按打开顺序,只有最后一层不在下面', () => {
  assert.deepEqual(depth.underFlags(0), [])
  assert.deepEqual(depth.underFlags(1), [false])
  assert.deepEqual(depth.underFlags(3), [true, true, false])
})

test('景深登记表:叠一层 → 下层退后;关上层 → 下层回位;重开回到顶层;按页面隔离', () => {
  depth._reset()
  const drawer = fakeLayer('p1')
  const layer = fakeLayer('p1')
  const other = fakeLayer('p2')
  depth.open(drawer)
  assert.equal(drawer.data._under, false, '只有一层时不退后')
  depth.open(other)
  assert.equal(drawer.data._under, false, '别的页面的弹层不影响本页')
  depth.open(layer)
  assert.equal(drawer.data._under, true)
  assert.equal(layer.data._under, false)
  depth.open(drawer)   // 重开:回到顶层
  assert.equal(drawer.data._under, false)
  assert.equal(layer.data._under, true)
  depth.close(drawer)
  assert.equal(layer.data._under, false, '上层关掉,下层立刻回位(与上层退场同时)')
  depth.close(layer)
  depth.close(other)
  depth._reset()
})

test('景深 behavior:show 观察者登记 / 出表,卸载出表且不再 setData', () => {
  global.Behavior = (o) => o
  delete require.cache[require.resolve('../../behaviors/sheet-depth.js')]
  const def = require('../../behaviors/sheet-depth.js')
  delete global.Behavior
  depth._reset()
  const mk = () => {
    const inst = { data: Object.assign({}, def.data), getPageId: () => 'p', calls: 0,
      setData(p) { this.calls++; Object.assign(this.data, p) } }
    Object.assign(inst, def.methods)
    return inst
  }
  const a = mk(); const b = mk()
  def.observers.show.call(a, true)
  def.observers.show.call(b, true)
  assert.equal(a.data._under, true)
  def.observers.show.call(b, false)
  assert.equal(a.data._under, false)
  def.observers.show.call(b, true)
  assert.equal(a.data._under, true)
  def.lifetimes.detached.call(b)
  assert.equal(a.data._under, false)
  const before = b.calls
  def.lifetimes.detached.call(b)
  assert.equal(b.calls, before, '卸载后不再 setData')
  depth._reset()
})

test('四个弹层宿主都挂了景深 behavior,并且用同一组 .94 / -10px / 压暗', () => {
  const hosts = [
    ['components/cy/sheet', "(stacked || _under) ? 'sh--stacked'", /\.sh\.sh--stacked \.sh__panel \{[^}]*translateY\(-10px\) scale\(\.94\)[^}]*brightness\(\.7\)/],
    ['components/cy/scene-sheet', "_under ? 'ss--under'", /\.ss--in\.ss--under \.ss__panel \{[^}]*translateY\(-10px\) scale\(\.94\)[^}]*brightness\(\.7\)/],
    ['pages/topic/components/project-drawer', "(under || _under) ? 'dw--under'", /\.dw--under \{[^}]*translateY\(-10px\) scale\(\.94\)[^}]*brightness\(\.7\)/],
    ['pages/topic/components/project-layer', "(under || _under) ? 'ly--under'", /\.ly--under \{[^}]*translateY\(-10px\) scale\(\.94\)[^}]*brightness\(\.7\)/],
  ]
  for (const [dir, cls, css] of hosts) {
    assert.match(read(dir + '/index.js'), /behaviors\/sheet-depth\.js/, dir + ' 要挂 sheet-depth behavior')
    assert.ok(read(dir + '/index.wxml').includes(cls), dir + ' 的 wxml 要把 _under 拼成退后类')
    assert.match(read(dir + '/index.wxss'), css, dir + ' 景深数值不一致')
  }
  // 减动效:cy-sheet 的景深过渡在减动效下关掉
  assert.match(read('components/cy/sheet/index.wxss'), /\.sh--reduced-motion \.sh__panel \{ transition: none; \}/)
})

test('W2-05 招商 / 邀约叠层:页面不再手写 under,由宿主统一判定', () => {
  const host = read('pages/topic/components/project-host/index.wxml')
  assert.doesNotMatch(host, /\bunder="/, 'project-host 不应再逐个传 under')
  assert.match(host, /<project-drawer show="\{\{ merchantSheet\.show \}\}"/)
  assert.match(host, /<project-layer show="\{\{ layers\.clubInvite\.show \}\}"/)
})

test('morph 节拍:done 与收尾字段同一次写入;停留后先写成功状态再撤 done', async () => {
  const writes = []
  const page = { data: {}, setData(p) { writes.push(Object.assign({}, p)); Object.assign(this.data, p) } }
  const order = []
  const ran = morph.settle(page, 'claimDone', () => { order.push('then:' + page.data.claimDone); page.setData({ headline: '已领取' }) },
    { reduced: false, hold: 5, with: { busy: false } })
  assert.equal(ran, true)
  assert.deepEqual(writes[0], { busy: false, claimDone: true }, 'busy 与 done 必须同一次 setData,否则药丸先弹回一帧')
  assert.equal(page.data.headline, undefined, '停留期间还不换成功文案')
  await new Promise((r) => setTimeout(r, 20))
  assert.deepEqual(order, ['then:true'], '成功状态在圆还收着时写入')
  assert.deepEqual(writes.at(-1), { claimDone: false }, '最后才撤 done,药丸带着新文字长回来')
})

test('morph 节拍:减动效不亮勾、不停留;cancel 后不再回调', async () => {
  const writes = []
  const page = { data: {}, setData(p) { writes.push(p); Object.assign(this.data, p) } }
  let called = 0
  assert.equal(morph.settle(page, 'k', () => called++, { reduced: true, with: { busy: false } }), false)
  assert.equal(called, 1)
  assert.deepEqual(writes, [{ busy: false }], '减动效只写收尾字段,不写 done')
  assert.equal(morph.holdFor({ reduced: true }), 0)
  assert.equal(morph.holdFor({ reduced: false }), morph.HOLD_MS)
  morph.settle(page, 'k', () => called++, { reduced: false, hold: 5 })
  morph.cancel(page)
  await new Promise((r) => setTimeout(r, 20))
  assert.equal(called, 1, '离页取消后不再执行成功回调')
})

function loadBtn() {
  const file = path.join(ROOT, 'components/cy/btn/index.js')
  let captured = null
  const fn = vm.compileFunction(fs.readFileSync(file, 'utf8'), ['require', 'module', 'exports', 'Component'], { filename: file })
  const prev = global.Behavior
  global.Behavior = (o) => o
  try { fn(Module.createRequire(file), { exports: {} }, {}, (o) => { captured = o }) } finally { global.Behavior = prev }
  return captured
}

test('cy-btn morph:done 期间吞点击;收圆转圈时不因 disabled=busy 变灰;不传 morph 行为不变', () => {
  const def = loadBtn()
  const events = []
  const inst = { data: { morph: true, done: true, loading: false, _v: 'primary' }, triggerEvent: (n) => events.push(n), setData(p) { Object.assign(this.data, p) } }
  def.methods.__onTap.call(inst, { detail: {} })
  assert.deepEqual(events, [], 'done 时点击被吞')
  inst.data.done = false
  def.methods.__onTap.call(inst, { detail: {} })
  assert.deepEqual(events, ['tap'])
  const obs = def.observers['variant, disabled, morph, loading']
  assert.ok(obs, '观察者要同时看 morph / loading')
  obs.call(inst, 'primary', true, true, true)
  assert.equal(inst.data._v, 'primary', 'morph 转圈期间保持原色')
  obs.call(inst, 'primary', true, false, true)
  assert.equal(inst.data._v, 'disabled', '不传 morph 的按钮 disabled 照旧变灰')
  const wxml = read('components/cy/btn/index.wxml')
  assert.match(wxml, /wx:if="\{\{loading && !morph\}\}" class="btn__spinner"/, '非 morph 按钮仍是原来的转圈 + 文字')
  const wxss = read('components/cy/btn/index.wxss')
  assert.match(wxss, /clip-path: inset\(calc\(50% - 44rpx\) calc\(50% - 44rpx\) round 44rpx\)/, '收成 88rpx 的圆')
  assert.doesNotMatch(read('style/morph-mark.wxss'), /@keyframes/, 'morph 只走 transition,不新增 keyframes')
  assert.match(wxss, /\.btn\.btn--morph\.btn--reduced-motion \{ transition: none; \}/, '减动效下收圆 / 长回直接到位(rig 减动效实拍发现的回归)')
  assert.equal((read('subpackageJourney/pages/landing/index.js').match(/headRise: morph\.holdFor\(\) > 0/g) || []).length, 2, '减动效不播标题浮出')
})

test('五个成功时刻接上 morph,且只在服务端确认成功的分支里亮勾', () => {
  const landing = read('subpackageJourney/pages/landing/index.js')
  assert.match(read('subpackageJourney/pages/landing/index.wxml'), /morph loading="\{\{busy\}\}" done="\{\{claimDone\}\}"/)
  // 领取席位 / 礼物:网络未知与失败分支只写 busy:false,不进 morph
  for (const fn of ['_claimSeat', '_claimGift']) {
    const body = landing.slice(landing.indexOf(fn + '() {'), landing.indexOf('\n  },', landing.indexOf(fn + '() {')))
    const settleAt = body.indexOf("morph.settle(this, 'claimDone'")
    assert.ok(settleAt > body.indexOf('this._claimed ='), fn + ':亮勾必须在服务端确认之后')
    assert.ok(body.indexOf('结果还没确认') < settleAt && body.indexOf('领取失败') < settleAt)
  }
  // 2026-11-10 qx-s2 staging 合并:r2-create(b4-u02,后一轮业主点名)撤掉了模板采用弹层,「应用」直接复制进编辑器;
  // u3 挂在弹层「确定」上的收勾随弹层退场(见 qx-s2/RESULT 待你决定)。这里只守住:复制失败不进编辑器。
  const tdJs = read('pages/templatedetail/utils/topic-use-flow.js')
  assert.ok(tdJs.indexOf('opts.open(result.topicId)') > tdJs.indexOf('if (!result.ok)'), '/use 只在复制成功后进编辑器')
  const fabu = read('pages/publish/fabu/index.js')
  assert.equal((fabu.match(/morph\.settle\(that, 'publishDone'/g) || []).length, 2, '新建与改已有两条发布成功路径')
  assert.ok(fabu.indexOf("morph.settle(that, 'publishDone'") > fabu.indexOf('onSuccess: (resData) =>'))
  assert.ok(fabu.indexOf("morph.settle(that, 'publishDone'") < fabu.indexOf('onFail: (res) =>'), '失败分支不亮勾')
  assert.match(read('pages/publish/fabu/index.wxml'), /morph loading="\{\{submitting\}\}" done="\{\{publishDone\}\}"/)
  const help = read('pages/play/play-help.wxml')
  assert.match(help, /helpPanel\.doneLevel !== item\.level/, '提示内容等勾亮完再浮出')
  assert.match(help, /cy-mm cy-mm--/)
})

test('提示解锁(UG-05):成功先亮勾、内容随后浮出;重看与减动效不停留', async () => {
  const store = {}
  global.wx = { getStorageSync: (k) => store[k], setStorageSync: (k, v) => { store[k] = v } }
  global.getApp = () => ({ getUserID: () => 7 })
  require.cache[require.resolve('../../utils/modal.js')] = { exports: { show: (o) => o.success({ confirm: true }) } }
  delete require.cache[require.resolve('../../pages/play/utils/play-help-panel.js')]
  const help = require('../../pages/play/utils/play-help-panel.js')
  const layer = (unlocked) => ({ runId: 55, nodeId: 11, enabled: true, remainingFreeUses: 1,
    layers: [{ level: 1, type: 'TEXT', usable: true, unlocked, free: true, costPoints: 0 }] })
  const run = async (reduced) => {
    store.play_reduced_motion = reduced
    const responses = [{ code: 200, data: layer(false) },
      { code: 200, data: { runId: 55, nodeId: 11, level: 1, type: 'TEXT', content: '看门牌', chargedPoints: 0 } },
      { code: 200, data: layer(true) }]
    const p = Object.assign({ data: Object.assign({ game: { nodeId: 11 } }, JSON.parse(JSON.stringify(help.data))),
      _routeState: { sessionId: '55' }, setData(patch) { Object.assign(this.data, patch) } },
    help.methods(() => Promise.resolve(responses.shift())))
    await p.loadHelpLadder(); p.openHelpPanel()
    const pending = p._helpUse(p.data.helpPanel.rows[0], false)
    assert.equal(p.data.helpPanel.busyLevel, 1, '解锁中:行尾收成转圈')
    await pending
    return p
  }
  let p = await run(false)
  assert.equal(p.data.helpPanel.busyLevel, null)
  assert.equal(p.data.helpPanel.doneLevel, 1, '服务端确认后亮勾')
  assert.equal(p.data.helpPanel.rows[0].content, '看门牌', '数据立即写入,只是展示晚一拍')
  assert.equal(p.data.helpPanel.rows[0].fresh, true, '计数器重读后仍保留浮出标记')
  await new Promise((r) => setTimeout(r, morph.HOLD_MS + 40))
  assert.equal(p.data.helpPanel.doneLevel, null, '停留后撤勾,内容浮出')
  p = await run(true)
  assert.equal(p.data.helpPanel.doneLevel, null, '减动效不亮勾')
  assert.equal(p.data.helpPanel.rows[0].fresh, false)
  delete global.wx
  delete global.getApp
})
