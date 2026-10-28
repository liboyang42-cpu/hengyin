'use strict'
// 文档二 L2:初始状态「开局带着」与「线索与物品」的物品一组。界面只给名字,不出现键名。
const test = require('node:test')
const assert = require('node:assert/strict')

const requests = []
global.getApp = () => ({ sendRequest: r => requests.push(r) })
const toasts = []
global.wx = { showToast: o => toasts.push(o.title) }
const carry = require('../../pages/publish/fabu/theme-initial-carry.js')
const items = require('../../pages/publish/fabu/theme-keys-items.js')

function host(extra) {
  const h = Object.assign({ data: {}, setData(patch) {
    Object.entries(patch).forEach(([path, value]) => {
      const parts = path.split('.'); let at = this.data
      parts.slice(0, -1).forEach(p => { at = at[p] = at[p] || {} }); at[parts.at(-1)] = value
    })
  } }, extra)
  Object.entries(Object.assign({}, carry.methods, items.methods)).forEach(([k, fn]) => { h[k] = fn.bind(h) })
  return h
}

test('开局带着:只列 initial=1 的线索 / 标签,显示中文种类', () => {
  const rows = carry.carryRows([
    { key: 'clue.k1', label: '半张货单', initial: 1 }, { key: 'tag.k2', label: '新来的', initial: 1 },
    { key: 'clue.k3', label: '没带', initial: 0 }, { key: 'counter.v', label: '观察', initial: 1 },
  ])
  assert.deepEqual(rows.map(r => [r.label, r.kindText]), [['半张货单', '线索'], ['新来的', '标签']])
})

test('添加一层:名单取注册表快照,已带的显示「已添加」,选了才可添加,写回 initial=1', () => {
  const h = host({ data: { editScope: '', editingTopicId: '9', operationScope: '',
    attributeRegistrySnapshot: { definitions: [
      { stateKey: 'clue.k1', keyType: 'flag', label: '半张货单' }, { stateKey: 'clue.k3', keyType: 'flag', label: '巡夜人的怀表' },
      { stateKey: 'tag.k2', keyType: 'tag', label: '见过巡夜人' }, { stateKey: 'counter.v', keyType: 'counter', label: '观察' }] },
    initialStateEditor: { rules: { attributes: [{ key: 'clue.k1', label: '半张货单', initial: 1, min: 0, max: 1 }] },
      carry: carry.carryRows([{ key: 'clue.k1', label: '半张货单', initial: 1 }]) } } })
  h.openCarryPicker()
  const picker = h.data.carryPicker
  assert.equal(picker.state, 'ready')
  assert.deepEqual(picker.groups.map(g => g.label), ['线索', '标签'], '计数不在这里')
  assert.equal(picker.groups[0].rows[0].added, true)
  h.confirmCarryPicker(); assert.equal(h.data.carryPicker.show, true, '没选不能添加')
  h.onCarryToggle({ currentTarget: { dataset: { key: 'clue.k1' } } }); assert.equal(h.data.carryPicker.count, 0, '已添加的不能再选')
  h.onCarryQuery({ detail: { value: '巡夜' } })
  assert.deepEqual(h.data.carryPicker.groups.flatMap(g => g.rows.map(r => r.label)), ['巡夜人的怀表', '见过巡夜人'])
  h.onCarryToggle({ currentTarget: { dataset: { key: 'tag.k2' } } })
  h.confirmCarryPicker()
  assert.equal(h.data.carryPicker.show, false)
  assert.deepEqual(h.data.initialStateEditor.rules.attributes.at(-1), { key: 'tag.k2', label: '见过巡夜人', initial: 1, min: 0, max: 1, visible: true })
  assert.deepEqual(h.data.initialStateEditor.carry.map(r => r.label), ['半张货单', '见过巡夜人'])
  h.removeCarry({ currentTarget: { dataset: { key: 'clue.k1' } } })
  assert.deepEqual(h.data.initialStateEditor.carry.map(r => r.label), ['见过巡夜人'])
})

test('就地新建:同组重名写一句;键名系统生成并直接选上', () => {
  const h = host({ data: { editScope: '', editingTopicId: '9', operationScope: '',
    attributeRegistrySnapshot: { definitions: [{ stateKey: 'clue.k1', keyType: 'flag', label: '半张货单' }] },
    initialStateEditor: { rules: { attributes: [] }, carry: [] } } })
  h.openCarryPicker(); h.onCarryCreateOpen(); h.onCarryNewName({ detail: { value: '半张货单' } }); h.createCarryKey()
  assert.equal(h.data.carryPicker.error, '已有同名的线索。')
  h.onCarryKind({ currentTarget: { dataset: { kind: 'tag' } } }); h.onCarryNewName({ detail: { value: '半张货单' } }); h.createCarryKey()
  const req = requests.at(-1); const body = JSON.parse(req.data)
  assert.equal(req.method, 'POST'); assert.match(body.keys[0].stateKey, /^tag\.k[a-z0-9]+$/); assert.equal(body.keys[0].label, '半张货单')
  req.success({ code: 200 })
  assert.equal(h.data.carryPicker.count, 1)
  assert.equal(h.data.carryPicker.groups.find(g => g.kind === 'tag').rows[0].label, '半张货单')
})

test('白名单 / 注册表绑定时开局带着只读', () => {
  for (const data of [{ editScope: 'WHITELIST', initialStateEditor: { rules: { attributes: [] } } },
    { editScope: '', initialStateEditor: { rules: { attributes: [], attributeBinding: {} } } }]) {
    const h = host({ data }); h.openCarryPicker(); assert.equal(h.data.carryPicker, undefined)
  }
})

test('物品一组:用在列位置;被用到的删不掉;改名 / 添加写回主题物品表', () => {
  const c = { items: [{ id: 'a', kind: 'ITEM', name: '旧手电', image: 'https://x/a.png' }, { id: 'b', kind: 'ITEM', name: '撬棍' }, { id: 'm', kind: 'MEDAL', name: '勋章' }],
    grants: [{ id: 'completion_material', itemId: 'a' }], chapters: [{ index: 0, grants: [], eggs: [{ name: '旧仓库', rewards: [{ itemId: 'a' }] }] }] }
  assert.deepEqual(items.itemUsedIn(c, 'a'), ['主题通关奖励', '第 1 章彩蛋「旧仓库」'])
  let saved = JSON.parse(JSON.stringify(c))
  const h = host({ data: { editScope: '' }, _runtimeConfig: () => JSON.parse(JSON.stringify(saved)), _saveRuntime: next => { saved = next } })
  h._syncKeysItems()
  assert.deepEqual(h.data.keysItems.map(i => i.name), ['旧手电', '撬棍'], '勋章不在这里')
  h.onKeysItemChange({ detail: { action: 'delete', id: 'a' } }); assert.ok(saved.items.some(i => i.id === 'a')); assert.equal(toasts.length, 1)
  h.onKeysItemChange({ detail: { action: 'rename', id: 'b', name: '铁撬棍' } }); assert.equal(saved.items[1].name, '铁撬棍')
  h.onKeysItemChange({ detail: { action: 'add', name: '怀表盒' } }); assert.equal(saved.items.at(-1).name, '怀表盒')
  h.onKeysItemChange({ detail: { action: 'delete', id: 'b' } }); assert.ok(!saved.items.some(i => i.id === 'b'))
})
