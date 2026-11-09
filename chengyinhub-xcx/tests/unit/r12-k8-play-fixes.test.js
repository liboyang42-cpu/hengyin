/* 第十二轮 k8-play(j3 第二轮复查,k7 已修 N4 N1 N2 N3 的一部分;这里补余下的):
   N2 猜数字带结果反馈没过时,终局屏不给真值 —— 不带反馈的同一猜会显示「你猜 X · 实际 Y」。
   N7 结果反馈判定屏整屏饱和红 / 绿;第二屏多一行「原结果已保留…」说明,外加得分 0 / 已完成 0 项假零。 */
const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const ROOT = path.join(__dirname, '../..')
const { pickPlayKit } = require('../../pages/play/utils/playkit-view.js')

const estimateView = (status, seg) => ({ sessionId: 9, version: 2, status, canRetry: true, plainOutcome: true,
  playKit: { estimate: Object.assign({ question: '桥有多长', unit: '米', min: 0, max: 1000 }, seg) } })

test('N2:猜数字没过的终局屏给出你猜的数和真值', () => {
  const kit = pickPlayKit(estimateView('FAILED', { submitted: true, tier: 'MISS', guess: 120, answer: 452.5 }), new Date())
  assert.equal(kit.terminal, true)
  assert.equal(kit.terminalDetail, '你猜 120 米 · 实际 452.5 米')
})

test('N2:没交过(服务端不给真值)就不出这一行', () => {
  const kit = pickPlayKit(estimateView('FAILED', { submitted: false, tier: '' }), new Date())
  assert.equal(kit.terminal, true)
  assert.ok(!kit.terminalDetail)
  const live = pickPlayKit(estimateView('ACTIVE', { submitted: false }), new Date())
  assert.equal(live.terminalDetail, undefined)
})

test('N7:终局屏不再有「原结果已保留」说明行,得分 / 完成项为 0 不显示', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/play/components/playkit/index.wxml'), 'utf8')
  const tpl = wxml.slice(0, wxml.indexOf('</template>'))
  assert.ok(!/<text[^>]*>[^<]*原结果已保留/.test(tpl))
  assert.match(tpl, /kit\.sessionScore > 0/)
  assert.match(tpl, /kit\.completedUnits > 0/)
  assert.match(tpl, /kit\.terminalDetail/)
})

test('N7:独立弹出的结果反馈走暗底,判定颜色只落在对勾和两个字上', () => {
  const css = fs.readFileSync(path.join(ROOT, 'pages/play/components/play-verdict/index.wxss'), 'utf8')
  const rule = (sel) => { const i = css.indexOf(sel + ' {'); assert.ok(i >= 0, sel); return css.slice(i, css.indexOf('}', i)) }
  assert.match(rule('.pv--closable .pv__fill'), /--cy-color-bg-page/)
  assert.match(rule('.pv--closable .pv__word'), /--cy-color-status-danger/)
  assert.match(rule('.pv--closable .pv__cta'), /--cy-color-bg-elevated/)
})

test('N4:内嵌检定卡铺进故事流就对准它(和内嵌玩法同一个 chapter-game 锚点),骰子与「按住摇骰」不再停在一屏之外', () => {
  const runtime = require('../../pages/play/utils/r5-player-runtime.js')
  const timers = []
  const realSet = global.setTimeout, realClear = global.clearTimeout
  global.setTimeout = (fn) => { timers.push(fn); return timers.length }
  global.clearTimeout = () => {}
  try {
    const anchors = []
    const page = { data: { chapterFull: true, chapterGameAnchor: '', journeyCheck: { show: true, present: 'inline', nodeId: 73 },
      chapterParas: [{ i: 0, kind: 'journeyCheck', in: true }] },
    setData(p, cb) { Object.assign(this.data, p); if ('chapterGameAnchor' in p) anchors.push(p.chapterGameAnchor); if (cb) cb() } }
    Object.assign(page, runtime.methods())
    page._guardInlineCheck()
    assert.deepEqual(anchors, ['', 'chapter-game-73'])
    timers[0]()   // 1.2 秒后再对准一次
    assert.deepEqual(anchors, ['', 'chapter-game-73', '', 'chapter-game-73'])
    // 换成整屏卡后不再拉故事流
    page.data.journeyCheck.present = 'fullscreen'
    timers[0]()
    assert.equal(anchors.length, 4)
  } finally { global.setTimeout = realSet; global.clearTimeout = realClear }
})

test('N4 rig 复走:检定点数正好等于难度读「正好过线」,不写「过线 0 点」', () => {
  const prev = { Behavior: global.Behavior, Component: global.Component }
  let c = null
  global.Behavior = (b) => b; global.Component = (x) => { c = x }
  try { require('../../pages/play/components/playkit-journey-check/index.js') } finally { Object.assign(global, prev) }
  const m = c.methods._marginText
  assert.equal(m({ total: 13, dc: 13, success: true }), '正好过线')
  assert.equal(m({ total: 15, dc: 13, success: true }), '过线 2 点')
  assert.equal(m({ total: 9, dc: 13, success: false }), '差 4 点')
})

for (const [comp, fail] of [['playkit-quiethold', '_micFailure'], ['playkit-shout', '_failure']]) {
  test(`k2 第 5 项遗留:${comp} 麦克风被拒时屏上写原因,重新开始时清掉`, () => {
    const prev = { Behavior: global.Behavior, Component: global.Component, wx: global.wx }
    let c = null
    global.Behavior = (b) => b; global.Component = (x) => { c = x }; global.wx = global.wx || {}
    try { delete require.cache[require.resolve(path.join(ROOT, `pages/play/components/${comp}/index.js`))]; require(path.join(ROOT, `pages/play/components/${comp}/index.js`)) } finally { Object.assign(global, prev) }
    const events = []
    const ctx = Object.assign({ data: Object.assign({}, c.data, { show: true }), setData(p) { Object.assign(this.data, p) },
      triggerEvent(n) { events.push(n) } }, c.methods)
    ctx._micDenied()
    assert.equal(ctx.data.micDenied, true)
    assert.deepEqual(events, ['micdenied'])
    const wxml = fs.readFileSync(path.join(ROOT, `pages/play/components/${comp}/index.wxml`), 'utf8')
    assert.match(wxml, /wx:if="\{\{micDenied && !skipped && !running\}\}"[^>]*>需要授权麦克风才能开始</)
    const src = fs.readFileSync(path.join(ROOT, `pages/play/components/${comp}/index.js`), 'utf8')
    assert.ok(!src.includes("this.triggerEvent('micdenied')\n") && src.includes('this._micDenied()'))
    assert.match(src, /onStart\(\) \{\n\s+if \(this\.data\.skipped\) return;[^\n]*\n\s+if \(this\.data\.micDenied\) this\.setData\(\{ micDenied: false \}\)/)
  })
}

test('N11:相册主动作画成主按钮,收起相册退成次按钮;开着相册交完照片后自动收起,重开已完成的相册不自动收', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/play/components/playkit-album/index.wxml'), 'utf8')
  assert.match(wxml, /bindtap="onCta" class="album__cta"/)
  assert.match(wxml, /album__back \{\{completionEnabled && !done \? 'album__back--ghost' : ''\}\}/)
  const prev = { Component: global.Component }
  let c = null
  global.Component = (x) => { c = x }
  try { delete require.cache[require.resolve(path.join(ROOT, 'pages/play/components/playkit-album/index.js'))]; require(path.join(ROOT, 'pages/play/components/playkit-album/index.js')) } finally { Object.assign(global, prev) }
  const timers = []
  const realSet = global.setTimeout
  global.setTimeout = (fn) => { timers.push(fn); return timers.length }
  try {
    const events = []
    const ctx = Object.assign({ data: { show: true, done: false }, setData(p) { Object.assign(this.data, p) }, triggerEvent(n) { events.push(n) } }, c.methods)
    ctx._shownAt = Date.now() - 5000
    ctx.data.done = true
    c.properties.done.observer.call(ctx, true, false)
    timers.pop()()
    assert.deepEqual(events, ['close'])
    // 重开已完成的相册:show 与 done 同时到 ⇒ 不收
    const again = Object.assign({ data: { show: true, done: true }, setData(p) { Object.assign(this.data, p) }, triggerEvent(n) { events.push(n) } }, c.methods)
    c.properties.show.observer.call(again, true)
    c.properties.done.observer.call(again, true, false)
    assert.equal(timers.length, 0)
  } finally { global.setTimeout = realSet }
})

test('N9:精准停表重进时剩余次数扣掉服务端已记的失手(以前每次重进都从满格算,第三次失手还说「还有 1 次机会」)', () => {
  const kit = pickPlayKit({ sessionId: 5, version: 4, status: 'RUNNING',
    playKit: { stopwatch: { targetSeconds: 10, toleranceMs: 300, tries: 3, attempts: 2, submitted: false } } }, new Date())
  assert.equal(kit.tries, 3)
  assert.equal(kit.used, 2)
  const prev = { Behavior: global.Behavior, Component: global.Component, wx: global.wx }
  let c = null
  global.Behavior = (b) => b; global.Component = (x) => { c = x }; global.wx = global.wx || {}
  try { delete require.cache[require.resolve(path.join(ROOT, 'pages/play/components/play-stage/index.js'))]; require(path.join(ROOT, 'pages/play/components/play-stage/index.js')) } finally { Object.assign(global, prev) }
  const ctx = Object.assign({ data: Object.assign({}, c.data), setData(p) { Object.assign(this.data, p) } }, c.methods)
  const key = Object.keys(c.observers).find((k) => k.includes('tries'))
  assert.match(key, /used/)
  c.observers[key].call(ctx, true, 3, 0, 2)
  assert.equal(ctx.data.triesTotal, 3)
  assert.equal(ctx.data.triesLeft, 1)
  c.observers[key].call(ctx, true, 3, 0, 0)
  assert.equal(ctx.data.triesLeft, 3)
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/play/components/playkit-stopwatch/index.wxml'), 'utf8')
  assert.match(wxml, /tries="\{\{tries\}\}" used="\{\{used\}\}"/)
})
