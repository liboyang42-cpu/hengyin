// 无对错选择题(qa PICK + judge:false,城市路线 14 题):编辑器存盘不丢 judge:false 与选项 effects;
// 玩家页先选、可以换,确认才提交;任一项都算完成,显示选中项自己的话;不出现次数与正确答案。夹具为中性数据。
const assert = require('node:assert/strict')
const { test } = require('node:test')
const path = require('node:path')

const cfg = require('../../pages/publish/utils/publish/advanced-game-config.js')
const { pickPlayKit } = require('../../pages/play/utils/playkit-view.js')

const CONFIG = { schemaVersion: 1,
  qa: { enabled: true, mode: 'PICK', judge: false, title: '示例站点先做哪一种', maxTries: 0, xp: 0, multi: false, options: [
    { id: 'A3', label: '先听自述', fb: '甲的回复', correct: false, effects: [{ var: '', op: 'ADD_TAG', value: 'tag.order_self' }] },
    { id: 'B3', label: '先看材料', fb: '乙的回复', correct: false, effects: [{ var: '', op: 'ADD_TAG', value: 'tag.order_all' }] }] },
  outcomeRules: { version: 1, pass: { reply: '继续下一站' }, fail: { reply: '本关没有错误答案' } } }

test('编辑器:judge:false 不要求正确答案,存盘保留 judge 与每个选项的结果标签', () => {
  const parsed = cfg.parse(JSON.stringify(CONFIG))
  assert.equal(parsed.error, '')
  assert.equal(cfg.validate(parsed.value), '')
  const out = JSON.parse(cfg.serialize(parsed.value))
  assert.equal(out.qa.judge, false)
  assert.deepEqual(out.qa.options.map(o => o.effects[0].value), ['tag.order_self', 'tag.order_all'])
  assert.deepEqual(out.outcomeRules, CONFIG.outcomeRules)
})

function loadQa() {
  let def = null
  const prevC = global.Component, prevB = global.Behavior
  global.Behavior = (b) => b
  global.Component = (d) => { def = d }
  const file = path.join(__dirname, '../../pages/playkit/components/playkit-qa/index.js')
  delete require.cache[require.resolve(file)]
  require(file)
  global.Component = prevC; global.Behavior = prevB
  return def
}

test('玩家页:先选可换、确认才提交;回执后显示选中项的话,没有次数', () => {
  const base = { sessionId: 1, version: 2, status: 'RUNNING' }
  const before = pickPlayKit(Object.assign({}, base, { playKit: { qa: { mode: 'PICK', judge: false, title: '示例站点先做哪一种',
    options: [{ id: 'A3', label: '先听自述' }, { id: 'B3', label: '先看材料' }], attempts: 0 } } }), new Date())
  assert.equal(before.judge, false)
  const def = loadQa(); const events = []
  const inst = Object.assign({ data: Object.assign({}, def.data, before, { show: true, mode: 'pick', reducedMotion: true }),
    setData(p) { Object.assign(this.data, p) }, triggerEvent(n, d) { events.push({ n, d }) }, selectComponent: () => null }, def.methods)
  def.observers[Object.keys(def.observers).find(k => k.startsWith('show, options'))].apply(inst,
    [true, inst.data.options, 'pick', false, false, false])
  assert.equal(inst.data.ctaLabel, '确认选择'); assert.equal(inst.data.triesCap, 0)
  inst.onPick({ currentTarget: { dataset: { i: 0 } } }); inst.onPick({ currentTarget: { dataset: { i: 1 } } })
  assert.equal(events.length, 0)
  inst.onCta()
  assert.deepEqual(events.map(e => e.d.optionId), ['B3'])
  if (inst._introTimer) clearTimeout(inst._introTimer)
  const after = pickPlayKit(Object.assign({}, base, { playKit: { qa: { mode: 'PICK', judge: false, title: '示例站点先做哪一种',
    options: [{ id: 'A3', label: '先听自述' }, { id: 'B3', label: '先看材料' }], attempts: 1, finished: true, passed: true,
    lastOptionId: 'B3', lastFeedback: '乙的回复' } } }), new Date())
  assert.equal(after.feedback, '乙的回复')
  assert.equal(after.passed, true)
})
