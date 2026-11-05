/* 第十二轮 k7(j3 第二轮复查):
   N4 d20 检定站整屏黑:r5 检定开局时 advancedPlay.show 为真而 playKit.kit 为空,故事流层被 hidden 藏起来,
      内嵌检定段永远量不到、永远 in:false;检定卡改为在这种时候不藏故事流,量不出来就换整屏检定卡。
   N1 预测「选」印落在没选的那张上:印钉在屏幕右上角而转盘还在转;改为点中即停到正中、印盖在正中那张。
   N2 猜数字带结果反馈,「重新开始本次玩法」后「就这个数」不发请求:locked 只增不减。
   N3 状态主题背包:PROOF_PENDING 不是「待审核」。 */
const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const ROOT = path.join(__dirname, '../..')

function loadComponent(rel) {
  const file = path.join(ROOT, rel)
  const prev = { Behavior: global.Behavior, Component: global.Component, wx: global.wx }
  let captured = null
  global.Behavior = (b) => b
  global.Component = (c) => { captured = c }
  global.wx = global.wx || {}
  try { delete require.cache[require.resolve(file)]; require(file) } finally { Object.assign(global, prev) }
  return captured
}
function ctxOf(config, data, extra) {
  const events = []
  const ctx = Object.assign({
    data: Object.assign({}, config.data, data),
    setData(p) { Object.assign(this.data, p) },
    triggerEvent(name, detail) { events.push({ name, detail }) },
    selectComponent() { return null },
  }, config.methods, extra)
  return { ctx, events }
}
function observe(config, ctx, needle, ...args) {
  const key = Object.keys(config.observers).find((k) => k.includes(needle))
  config.observers[key].call(ctx, ...args)
}

test('N4:r5 检定开着时故事流层不被 hidden 藏掉', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/play/index.wxml'), 'utf8')
  const line = wxml.split('\n').find((l) => l.includes('<view class="chfull ') && l.includes('hidden='))
  assert.ok(line, '找得到故事流外层')
  assert.match(line, /advancedPlay\.show && !playKit\.kit && !\(journeyCheck\.show && journeyCheck\.present === 'inline'\)/)
})

test('N4:故事流没开时内嵌检定改为整屏;开着时铺进故事流并挂上兜底', () => {
  const runtime = require(path.join(ROOT, 'pages/play/utils/r5-player-runtime.js'))
  const m = runtime.methods()
  const view = { sessionId: 7, nodeId: 73, present: 'inline', config: { outcomeRulesVersion: 1, check: { enabled: true, tier: 'medium' } },
    check: { allowedActions: ['ROLL_CHECK'] } }
  const make = (chapterFull) => {
    const page = { data: { chapterFull, journeyCheck: {}, themeBag: {}, chapterParas: [] }, opened: 0, guarded: 0,
      setData(p) { Object.assign(this.data, p) }, refreshThemeBackpack() {}, openChapterFull() { this.opened++ },
      _applyR5PlainOutcome() {} }
    Object.assign(page, m, { _guardInlineCheck() { page.guarded++ }, _applyR5PlainOutcome() {} })
    return page
  }
  const closed = make(false)
  assert.equal(closed._applyR5AdvancedView(view), true)
  assert.equal(closed.data.journeyCheck.present, 'fullscreen')
  assert.equal(closed.opened, 0)
  const open = make(true)
  open._applyR5AdvancedView(view)
  assert.equal(open.data.journeyCheck.present, 'inline')
  assert.equal(open.opened, 1)
  assert.equal(open.guarded, 1)
})

test('N4:内嵌检定段到时还没显形 ⇒ 换整屏检定卡,并把那一段从故事流里摘掉', () => {
  const runtime = require(path.join(ROOT, 'pages/play/utils/r5-player-runtime.js'))
  const timers = []
  const realSet = global.setTimeout, realClear = global.clearTimeout
  global.setTimeout = (fn) => { timers.push(fn); return timers.length }
  global.clearTimeout = () => {}
  try {
    const page = { data: { chapterFull: true, journeyCheck: { show: true, present: 'inline' },
      chapterParas: [{ i: 0, kind: 'text', in: false }, { i: 1, kind: 'journeyCheck', in: false }] },
    setData(p) { for (const k of Object.keys(p)) { if (k === 'journeyCheck.present') this.data.journeyCheck.present = p[k]; else this.data[k] = p[k] } } }
    Object.assign(page, runtime.methods())
    page._guardInlineCheck(); timers.pop()()
    assert.equal(page.data.journeyCheck.present, 'fullscreen')
    assert.deepEqual(page.data.chapterParas.map((p) => p.kind), ['text'])
    // 已经显形:不动
    const ok = { data: { chapterFull: true, journeyCheck: { show: true, present: 'inline' },
      chapterParas: [{ i: 0, kind: 'journeyCheck', in: true }] }, setData() { throw new Error('不该改') } }
    Object.assign(ok, runtime.methods())
    ok._guardInlineCheck(); timers.pop()()
  } finally { global.setTimeout = realSet; global.clearTimeout = realClear }
})

test('N1:点中一张就停到正中;选定时印盖在发出去的那张上', () => {
  const config = loadComponent('pages/play/components/playkit-predict/index.js')
  const { ctx, events } = ctxOf(config, { options: [{ key: 'A', label: '会' }, { key: 'B', label: '不会' }], reducedMotion: true, show: true },
    { _spin() {}, _stop() {} })
  ctx._slices = []; ctx._n = 2; ctx._angle = 7   // 转盘停在两张之间的某处
  ctx.onPick({ currentTarget: { dataset: { i: 1 } } })
  assert.equal(ctx.data.picked, 1)
  assert.equal(Math.round(ctx._cardAngle(1, ctx._angle, 2)), 0, '点中的那张已转到正中')
  ctx._angle = 3   // 动画途中按下「就这个」
  ctx.onCommit()
  assert.equal(ctx._cardAngle(1, ctx._angle, 2), 0, '落印时正中就是选中的那张')
  assert.deepEqual(events.pop(), { name: 'submit', detail: { key: 'B', index: 1 } })
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/play/components/playkit-predict/index.wxml'), 'utf8')
  const ring = wxml.slice(wxml.indexOf('pd__ring'), wxml.indexOf('<!-- 揭晓'))
  assert.ok(ring.includes('pd__seal'), '印在转盘里、落在正中那张上')
  const wxss = fs.readFileSync(path.join(ROOT, 'pages/play/components/playkit-predict/index.wxss'), 'utf8')
  assert.match(wxss, /\.pd__seal \{\s*position: absolute; left: 50%; top: 50%/)
})

test('N1:重进时服务端已记下本人选项 ⇒ 转盘停在那张、已盖印、不能再选', () => {
  const config = loadComponent('pages/play/components/playkit-predict/index.js')
  let spun = false
  const { ctx } = ctxOf(config, { options: [{ key: 'A', label: '会' }, { key: 'B', label: '不会' }], myOptionKey: 'B', reducedMotion: false },
    { _spin() { spun = true }, _stop() {}, _paint() {} })
  observe(config, ctx, 'myOptionKey', true)
  assert.equal(ctx.data.picked, 1)
  assert.equal(ctx.data.committed, true)
  assert.equal(ctx._cardAngle(1, ctx._angle, 2), 0)
  assert.equal(spun, false)
})

test('N2:服务端把猜数字这一局重开 ⇒ 解锁,「就这个数」能再交', () => {
  const config = loadComponent('pages/play/components/playkit-estimate/index.js')
  const { ctx, events } = ctxOf(config, { unit: '米' }, { _paint() {} })
  ctx._ticks = [1, 2, 3]; ctx._index = 1
  observe(config, ctx, 'submitted', true, 'MISS', 9, 5, '', '米')
  assert.equal(ctx.data.locked, true)
  observe(config, ctx, 'submitted', false, '', 0, 0, '', '米')
  assert.equal(ctx.data.result, null)
  assert.equal(ctx.data.locked, false)
  ctx.onSubmit()
  assert.equal(events.length, 1)
  assert.deepEqual(events[0].detail, { guess: 2 })
})

test('N3:当前任务还没过(PROOF_PENDING)读作「打开任务」,不写待审核', () => {
  const gameplay = require(path.join(ROOT, 'pages/play/utils/theme-gameplay.js'))
  const view = { scope: 'PLAYER', activeQuest: 'node_73', allowedActions: [{ operation: 'QUEST_CANCEL', contentId: 'node_73' }],
    quests: [{ questKey: 'node_73', nodeId: 73, enabled: false, operation: 'QUEST_RESOLVE', disabledReason: 'PROOF_PENDING', trustedPass: false, remainingCount: 1, countScope: 'OWNER' }] }
  const row = gameplay.actionRows(view, [{ nodeId: 73, name: '01 D20' }])[0]
  assert.equal(row.actionText, '打开任务')
  assert.equal(row.disabledText, '')
  assert.equal(row.openable, true)
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/play/theme-backpack.wxml'), 'utf8')
  assert.ok(!wxml.includes('打开原任务'), '不再并排一颗「打开原任务」')
  assert.match(wxml, /data-open="\{\{!item\.enabled && item\.openable\}\}"/)
})
