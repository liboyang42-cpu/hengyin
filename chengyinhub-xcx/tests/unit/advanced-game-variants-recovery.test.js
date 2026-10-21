'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const cfg = require('../../pages/publish/utils/publish/advanced-game-config.js')
const WHEN = { var: 'sys.hp', op: 'LTE', value: 3 }
function model(section = 'timer', values = { durationSeconds: 30 }) {
  const m = cfg.defaultConfig()
  Object.assign(m[section], values, { enabled: true })
  return m
}
function variant(m, relax, when = WHEN) {
  m.variants = [{ when, relax }]
  return m
}
test('variants拒绝畸形when而不是只检查存在', () => {
  for (const when of ['bad', 1, true, [], {}, { op: 'BOGUS' },
    { var: 'sys.secret', op: 'EQ', value: 1 }, { var: 'counter.Bad', op: 'EQ', value: 1 },
    { var: 'sys.hp', op: 'LTE', value: '3' }, { var: 'sys.hp', op: 'LTE', value: null },
    { var: 'sys.hp', op: 'LTE', value: 100 }, { var: 'sys.hp', op: 'LTE', value: -100 },
    { var: 'sys.hp', op: 'LTE', value: 1.5 },
    { op: 'HAS_TAG', value: 'bad' }, { op: 'NODE_COMPLETED', nodeId: 0 },
    { op: 'ITEM_HELD', itemId: 'Bad', quantity: 1 },
    { op: 'ITEM_ACQUIRED', itemId: 'key', quantity: 1000 }]) {
    assert.match(cfg.validate(variant(model(), { 'timer.durationSeconds': '+1' }, when)), /when/, JSON.stringify(when))
  }
})
test('已支持条件DSL全部正常放行，不新增条件业务规则', () => {
  const comparisons = ['EQ', 'NE', 'GT', 'GTE', 'LT', 'LTE']
  const variables = ['clue.found', 'relation.friend', 'counter.tries', 'sys.hp', 'sys.luck',
    'sys.exhausted', 'sys.asked.12.question_1', 'sys.mistakes.12', 'sys.outcome.12', 'sys.passed.12']
  const conditions = variables.flatMap(v => comparisons.map(op => ({ var: v, op, value: -99 })))
    .concat([{ var: 'sys.hp', op: 'EQ', value: 99 }, { op: 'HAS_TAG', value: 'tag.found' },
      { op: 'NODE_COMPLETED', nodeId: 12 }, { op: 'ITEM_HELD', itemId: 'key', quantity: 1 },
      { op: 'ITEM_ACQUIRED', itemId: 'key_1', quantity: 999 }])
  for (const when of conditions) assert.equal(cfg.validate(variant(model(), { 'timer.durationSeconds': '+1' }, when)), '', JSON.stringify(when))
})
test('timer量程越界本地拒绝，validate与serialize均生效', () => {
  const m = variant(model(), { 'timer.durationSeconds': '+90000' })
  assert.match(cfg.validate(m), /第 1 条.*计时时长/)
  assert.throws(() => cfg.serialize(m), /第 1 条.*计时时长/)
  assert.equal(cfg.validate(variant(model(), { 'timer.durationSeconds': '+86370' })), '')
})
test('first-match逐条模拟，两条各自合法不能累加误杀', () => {
  const m = model('timer', { durationSeconds: 80000 })
  m.variants = [{ when: WHEN, relax: { 'timer.durationSeconds': '+6000' } },
    { when: { var: 'sys.luck', op: 'GT', value: 1 }, relax: { 'timer.durationSeconds': '+6000' } }]
  const before = JSON.stringify(m)
  assert.equal(cfg.validate(m), '')
  assert.equal(JSON.stringify(m), before, '校验不得改原配置')
  m.variants[1].relax['timer.durationSeconds'] = '+7000'
  assert.match(cfg.validate(m), /第 2 条.*计时时长/, '后条仍需验证，即使前条条件相同也不能跳过')
})
test('current-N与=0合法边界保留，越下界与越次数上界拒绝', () => {
  assert.equal(cfg.validate(variant(model('quietHold', { seconds: 8 }), { 'quietHold.seconds': '-3' })), '')
  assert.match(cfg.validate(variant(model('quietHold', { seconds: 8 }), { 'quietHold.seconds': '-4' })), /第 1 条.*5 至 300/)
  assert.equal(cfg.validate(variant(model('stopwatch', { targetSeconds: 10, toleranceMs: 300, tries: 3 }), { 'stopwatch.tries': '=0' })), '')
  assert.equal(cfg.validate(variant(model('stopwatch', { targetSeconds: 10, toleranceMs: 300, tries: 3 }), { 'stopwatch.tries': '+7' })), '')
  assert.match(cfg.validate(variant(model('stopwatch', { targetSeconds: 10, toleranceMs: 300, tries: 3 }), { 'stopwatch.tries': '+8' })), /第 1 条/)
})
test('同条多字段作用后统一校验，判定误差允许小数底值', () => {
  const m = model('stopwatch', { targetSeconds: 10, toleranceMs: 300, tries: 3 })
  assert.equal(cfg.validate(variant(m, { 'stopwatch.toleranceMs': '+100', 'stopwatch.tries': '=0' })), '')
  assert.match(cfg.validate(variant(m, { 'stopwatch.toleranceMs': '+90000', 'stopwatch.tries': '=0' })), /第 1 条/)
  const estimate = model('estimate', { title: '猜数', unit: '个', min: 0, max: 100, answer: 50, tolerance: 2.5 })
  assert.equal(cfg.validate(variant(estimate, { 'estimate.tolerance': '+3' })), '')
})

test('全部次数容错字段统一遵守既有0至10合同，=0不会误作负值或收紧', () => {
  for (const path of cfg.RELAX_ATTEMPTS) {
    const [section, field] = path.split('.')
    const m = model()
    m[section] = { ...m[section], enabled: true, [field]: 3 }
    Object.assign(m[section], { title: '题目', prompt: '题干', target: '你好', answerText: '答案' })
    if (m[section].items) m[section].items.forEach(row => { row.label = '项目'; row.name = '图片'; row.imageUrl = 'https://example.com/a.png' })
    if (m[section].hotspots) { m[section].imageUrl = 'https://example.com/a.png'; m[section].hotspots.forEach(row => { row.label = '目标' }) }
    assert.equal(cfg.validate(variant(m, { [path]: '+7' })), '', path + ' 上边界应合法')
    assert.equal(cfg.validate(variant(m, { [path]: '=0' })), '', path + ' 不限应合法')
    m.variants = [{ when: WHEN, relax: { [path]: '+8' } }]
    assert.match(cfg.validate(m), /第 1 条.*0 至 10/, path)
  }
  const m = model('typeIn', { title: '打字', target: '你好', seconds: 30, tries: 3 })
  assert.equal(cfg.validate(variant(m, { 'typeIn.tries': '=0' })), '')
})
