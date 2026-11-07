/* R11 g1 F1:预测站过了截止整点(或这一轮已揭晓)且没选 ⇒ 玩家能「不选，继续」,路线不停;
   本站没有承接商家时,任何文案都不承诺「由商家给出答案」。 */
const assert = require('node:assert/strict')
const test = require('node:test')
const path = require('node:path')
const { pickPlayKit, serverAction, serverPayload } = require('../../pages/play/utils/playkit-view.js')

function loadPredict() {
  const file = path.resolve(__dirname, '../../pages/play/components/playkit-predict/index.js')
  const prev = { Behavior: global.Behavior, Component: global.Component, wx: global.wx }
  let captured = null
  global.Behavior = (b) => b
  global.Component = (c) => { captured = c }
  global.wx = global.wx || {}
  try { delete require.cache[require.resolve(file)]; require(file) } finally { Object.assign(global, prev) }
  return captured
}
function ctxOf(config, data) {
  const events = []
  const ctx = {
    data: Object.assign({}, config.data, { options: [{ key: 'A', label: '甲' }], reducedMotion: true }, data),
    setData(p) { Object.assign(this.data, p) },
    triggerEvent(name, detail) { events.push({ name, detail }) },
    _paint() {}, _stop() {}, _spin() {},
  }
  Object.assign(ctx, config.methods)
  return { ctx, events }
}
function observe(config, ctx, needle) {
  const key = Object.keys(config.observers).find((k) => k.includes(needle))
  config.observers[key].call(ctx, true)
}

test('服务端下发 closed/shopAnswers 过桥到 kit', () => {
  const kit = pickPlayKit({ sessionId: 1, version: 1, playKit: { predict: {
    question: 'q', closeAtHour: 20, myOptionKey: '', closed: true, shopAnswers: false,
    options: [{ key: 'a', label: 'A' }] } } })
  assert.equal(kit.type, 'predict')
  assert.equal(kit.closed, true)
  assert.equal(kit.shopAnswers, false)
})

test('跳过动作在册,payload 为空;服务端记 skipped 后这一段算完、让位给下一段', () => {
  assert.equal(serverAction('predict', 'skip'), 'SKIP_CLOSED_PREDICT')
  assert.deepEqual(serverPayload('predict', 'skip', {}), {})
  const next = pickPlayKit({ sessionId: 1, version: 2, playKit: {
    predict: { question: 'q', myOptionKey: '', skipped: true, options: [{ key: 'a', label: 'A' }] },
    dailySign: { lines: ['签'] } } })
  assert.equal(next.type, 'dailysign')
})

test('已关且没选:按钮发 skip,不发 submit,也不盖「选」的印', () => {
  const config = loadPredict()
  const { ctx, events } = ctxOf(config, { closed: true, myOptionKey: '' })
  ctx.onCommit()
  ctx.onCommit()
  assert.deepEqual(events.map((e) => e.name), ['skip'])
  assert.equal(ctx.data.committed, false)
  ctx.onPick({ currentTarget: { dataset: { i: 0 } } })
  assert.equal(ctx.data.picked, -1, '已关的一轮不能再选')
})

test('没有承接商家:等待文案只说截止,揭晓块不承诺商家', () => {
  const config = loadPredict()
  const { ctx } = ctxOf(config, { closeAtHour: 20, revealDays: 1, revealHour: 20, shopAnswers: false })
  observe(config, ctx, 'closeAtHour')
  assert.equal(ctx.data.waitLabel, '今天 20:00 截止。')
  const r = loadPredict()
  const c2 = ctxOf(r, { settleStatus: 0, shopAnswers: false }).ctx
  observe(r, c2, 'settleStatus')
  assert.equal(c2.data.revealKind, '')
  const c3 = ctxOf(r, { settleStatus: 2, shopAnswers: false }).ctx
  observe(r, c3, 'settleStatus')
  assert.ok(!c3.data.revealText.includes('商家'))
})

test('有承接商家:原文案不变', () => {
  const config = loadPredict()
  const { ctx } = ctxOf(config, { closeAtHour: 20, revealDays: 1, revealHour: 20 })
  observe(config, ctx, 'closeAtHour')
  assert.equal(ctx.data.waitLabel, '今天 20:00 截止，明天 20:00 由商家给出答案。')
})
