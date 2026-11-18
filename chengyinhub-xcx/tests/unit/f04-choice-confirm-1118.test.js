// F04(2026-11-18,照 Duolingo):单选类玩法先选、可以换,按「确认选择」才提交;确认后锁定。
// 清单按 playkit-view.js TYPE_OF 过了一遍:qa(单选)/branch/pricePair/blindTaste 原来点即提交,
// predict/accusation 本来就有确认,多选/排序/连线/分类本来就是按钮提交。
const assert = require('node:assert/strict')
const { test } = require('node:test')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.join(__dirname, '../..')

function load(kit) {
  let def = null
  const prevC = global.Component, prevB = global.Behavior
  global.Behavior = (b) => b
  global.Component = (d) => { def = d }
  const file = path.join(ROOT, 'pages/play/components/playkit-' + kit + '/index.js')
  delete require.cache[require.resolve(file)]
  require(file)
  global.Component = prevC; global.Behavior = prevB
  return def
}
function mount(def, props) {
  const events = []
  const inst = Object.assign({ data: Object.assign({}, def.data, { reducedMotion: true }, props),
    setData(p) { Object.assign(this.data, p) }, triggerEvent(name, detail) { events.push({ name, detail }) },
    selectComponent: () => null }, def.methods)
  return { inst, events }
}
const tap = (inst, i) => inst.onPick({ currentTarget: { dataset: { i } } })

test('branch:多个选项时点选项不提交、可以换,确认后报最后选中的那一条并锁住', () => {
  const def = load('branch')
  const { inst, events } = mount(def, { show: true, writable: true, ended: false, options: [{ id: 'l', t: '左' }, { id: 'r', t: '右' }] })
  def.observers['show, body, ended, writable, stepId, failureSeq'].call(inst, true)
  tap(inst, 0); tap(inst, 1)
  assert.equal(events.length, 0)
  assert.equal(inst.data.chosen, 1)
  inst.onConfirm(); inst.onConfirm(); tap(inst, 0)
  assert.deepEqual(events, [{ name: 'choose', detail: { index: 1, optionId: 'r' } }])
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/play/components/playkit-branch/index.wxml'), 'utf8')
  assert.match(wxml, /bindtap="onConfirm"/)
  assert.match(wxml, /aria-selected="\{\{chosen === index\}\}"/)
})

test('pricePair:点海报只是选中,确认才交;答错后可以另选再确认', () => {
  const def = load('pricepair')
  const { inst, events } = mount(def, { show: true, posters: [{ id: 'a' }, { id: 'b' }, { id: 'c' }], revealed: false })
  tap(inst, 2); tap(inst, 0)
  assert.equal(events.length, 0)
  inst.onConfirm(); inst.onConfirm()
  assert.deepEqual(events, [{ name: 'submit', detail: { index: 0, pickId: 'a' } }])
  inst.selectComponent = () => ({ miss: () => false, nudge() {} })
  inst.settle(false, { index: 0 })
  assert.equal(inst.data.submitted, false); assert.equal(inst.data.chosen, -1)
  tap(inst, 1); inst.onConfirm()
  assert.deepEqual(events.map(e => e.detail.pickId), ['a', 'b'])
})

test('blindTaste:睁眼作答先选、可以换,确认才作答,确认后不再接点', () => {
  const def = load('blindtaste')
  const { inst, events } = mount(def, { show: true, stage: 2, selectedKey: '', locked: false })
  inst.onPick({ currentTarget: { dataset: { key: 'A' } } })
  inst.onPick({ currentTarget: { dataset: { key: 'B' } } })
  assert.equal(events.length, 0)
  assert.equal(inst.data.chosenKey, 'B')
  inst.onConfirm(); inst.onConfirm()
  inst.onPick({ currentTarget: { dataset: { key: 'A' } } })
  assert.deepEqual(events, [{ name: 'answer', detail: { key: 'B' } }])
  assert.equal(inst.data.chosenKey, 'B')
})

test('确认前的选择只在本机,不广播;路线说明不再需要「选定后不可更改」', () => {
  for (const kit of ['qa', 'branch', 'pricepair', 'blindtaste']) {
    const js = fs.readFileSync(path.join(ROOT, 'pages/play/components/playkit-' + kit + '/index.js'), 'utf8')
    assert.doesNotMatch(js, /选定后不可更改/)
  }
})

test('qa 单选网络失败:选中保留,主按钮变成「重试」,再按提交同一条', () => {
  const def = load('qa')
  const { inst, events } = mount(def, { show: true, mode: 'pick', multi: false, judge: true, finished: false, passed: false,
    optionList: [{ k: 0, id: 'a', state: '' }, { k: 1, id: 'b', state: '' }], ctaLabel: '确认选择', ctaDisabled: true })
  tap(inst, 1); inst.onCta()
  assert.equal(events.length, 1)
  def.observers.failureSeq.call(inst, 1)
  assert.equal(inst.data.ctaLabel, '重试')
  assert.equal(inst.data.optionList[1].state, 'is-on')
  inst.onCta()
  assert.deepEqual(events.map(e => e.detail.optionId), ['b', 'b'])
})
