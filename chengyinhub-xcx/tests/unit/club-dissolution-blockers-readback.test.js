// BL-140:解散阻断明细回读 —— POST /api/club/dissolution-blockers({id}) 返回原始 deposits/settlements,
// 前端只按 url 的 type+id 对照现有行,不拼状态/金额文案;回读前不得移除行或宣称可解散。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const COMPONENT = path.join(ROOT, 'components/cy/scene-club-edit/index.js')
const U = (type, id) => '/pages/club/dissolution-blockers/index?clubId=7&type=' + type + '&id=' + id
const ITEMS = [
  { title: '保证金 #9', url: U('deposit', 9) },
  { title: '结算单 #3', url: U('settlement', 3) },
  { title: '保证金 #5', url: '/pages/shezhi/about/index' },
]

function loadComponent() {
  const requests = [], navigations = []
  let captured = null
  const prev = { Component: global.Component, getApp: global.getApp, wx: global.wx }
  global.Component = (o) => { captured = o }
  global.getApp = () => ({ globalData: {}, sendRequest: (o) => requests.push(o) })
  global.wx = { navigateTo: (o) => navigations.push(o.url), getStorageSync: () => '', setStorageSync: () => {} }
  try { delete require.cache[COMPONENT]; require(COMPONENT) } finally {
    global.Component = prev.Component; global.getApp = prev.getApp
  }
  const vm = Object.assign({}, captured.methods, { data: Object.assign({}, captured.data), setData(p) { Object.assign(this.data, p) } })
  vm.pageShow = captured.pageLifetimes.show.bind(vm)
  return { vm, requests, navigations }
}

function readBack(data, items) {
  const { vm, requests } = loadComponent()
  vm.data.clubId = '7'
  vm.data.dissolutionBlockerItems = (items || ITEMS).slice()
  vm.refreshDissolutionBlockers('')
  requests[0].success({ code: '200', data })
  return vm.data
}

test('回读:回读里没有的行移除,无 type+id 的行(转平台核验)保留,新增只计数', () => {
  const d = readBack({ deposits: [{ id: 9 }, { id: 11 }], settlements: [] })
  assert.deepEqual(d.dissolutionBlockerItems.map(i => i.title), ['保证金 #9', '保证金 #5'])
  assert.match(d.dissolutionBlockerText, /1 项已不在阻断明细中/)
  assert.match(d.dissolutionBlockerText, /另有 1 项未在本列表中/)
  assert.doesNotMatch(d.dissolutionBlockerText, /新增/)
})

function readBackWith(data, ctx, items) {
  const { vm, requests } = loadComponent()
  vm.data.clubId = '7'
  vm.data.dissolutionBlockerItems = (items || ITEMS).slice()
  vm._blockerType = ctx.type
  vm._blockerOriginKeys = ctx.originKeys
  vm.refreshDissolutionBlockers('')
  requests[0].success({ code: '200', data })
  return vm.data
}

test('回读:转平台核验的历史保证金(无链接)仍在 deposits 里,不算「未在本列表」', () => {
  const items = [ITEMS[0], ITEMS[2]]
  const d = readBackWith({ deposits: [{ id: 9 }, { id: 5 }], settlements: [] },
    { type: 'deposit', originKeys: ['deposit:9', 'deposit:5'] }, items)
  assert.deepEqual(d.dissolutionBlockerItems.map(i => i.title), ['保证金 #9', '保证金 #5'])
  assert.doesNotMatch(d.dissolutionBlockerText, /未在本列表中/)
  assert.match(d.dissolutionBlockerText, /仍未结清/)
})

test('回读:结算分区里出现的保证金是下一阶段,单独说明、不算本列表遗漏', () => {
  const items = [{ title: '结算单 #3', url: U('settlement', 3) }]
  const d = readBackWith({ deposits: [{ id: 9 }, { id: 11 }], settlements: [{ id: 3 }] },
    { type: 'settlement', originKeys: ['settlement:3'] }, items)
  assert.equal(d.dissolutionBlockerItems.length, 1)
  assert.doesNotMatch(d.dissolutionBlockerText, /未在本列表中/)
  assert.match(d.dissolutionBlockerText, /还会提示另外 2 项待处理/)
  assert.match(d.dissolutionBlockerText, /仍未结清/)
})

test('回读:两个列表都为空时,无链接的历史行也一并移除', () => {
  const d = readBackWith({ deposits: [], settlements: [] },
    { type: 'deposit', originKeys: ['deposit:9', 'deposit:5'] }, [ITEMS[0], ITEMS[2]])
  assert.deepEqual(d.dissolutionBlockerItems, [])
  assert.match(d.dissolutionBlockerText, /已无待处理项/)
})

test('/dissolve 报阻断时记下类型与原始 id,关闭弹层清空', () => {
  const src = fs.readFileSync(COMPONENT, 'utf8')
  assert.match(src, /that\._blockerType = \(res\.data && res\.data\.blockerType\) \|\| ''/)
  assert.match(src, /that\._blockerOriginKeys = dissolutionBlockerKeys\(res\.data\)/)
  const { vm } = loadComponent()
  vm._blockerType = 'deposit'; vm._blockerOriginKeys = ['deposit:1']
  vm.closeDissolutionBlockers()
  assert.equal(vm._blockerType, '')
  assert.deepEqual(vm._blockerOriginKeys, [])
})

test('回读:两个列表都为空 = 资金阻断已清,文案以解散结果为准、不宣称已解散', () => {
  const d = readBack({ deposits: [], settlements: [] }, ITEMS.slice(0, 2))
  assert.deepEqual(d.dissolutionBlockerItems, [])
  assert.match(d.dissolutionBlockerText, /以解散结果为准/)
  assert.doesNotMatch(d.dissolutionBlockerText, /已解散/)
})

test('回读:结构缺两个列表时不可信,现有行原样保留;无变化如实说仍未结清', () => {
  const bad = readBack(null)
  assert.equal(bad.dissolutionBlockerItems.length, 3)
  assert.match(bad.dissolutionBlockerText, /暂无法核对/)
  const same = readBack({ deposits: [{ id: 9 }], settlements: [{ id: 3 }] })
  assert.equal(same.dissolutionBlockerItems.length, 3)
  assert.match(same.dissolutionBlockerText, /仍未结清/)
})

test('无 clubId 不发回读', () => {
  const { vm, requests } = loadComponent()
  vm.refreshDissolutionBlockers('')
  assert.equal(requests.length, 0)
})

test('退款重试 200 后读阻断明细:请求合同 + 以回读为准移除', () => {
  const { vm, requests } = loadComponent()
  vm.data.clubId = '7'
  vm.data.dissolutionBlockerItems = ITEMS.slice(); vm.data.showDissolutionBlockers = true
  vm.openDissolutionBlockerItem({ currentTarget: { dataset: { index: 0 } } })
  requests[0].success({ code: '200', msg: '保证金退款已受理', data: { refundState: 'PROCESSING' } })
  assert.equal(vm.data.dissolutionBlockerItems.length, 3, '回读前不得移除')
  assert.equal(requests[1].url, '/api/club/dissolution-blockers')
  assert.equal(requests[1].method, 'POST')
  assert.deepEqual(JSON.parse(requests[1].data), { id: '7' })
  requests[1].success({ code: '200', data: { deposits: [], settlements: [{ id: 3, amount: 10, direction: 'outgoing' }] } })
  assert.deepEqual(vm.data.dissolutionBlockerItems.map(i => i.title), ['结算单 #3', '保证金 #5'])
  assert.match(vm.data.dissolutionBlockerText, /^保证金退款已受理；已重新核对/)
})

test('阻断明细回读失败:原样复述后端文案,行不动', () => {
  const { vm, requests } = loadComponent()
  vm.data.clubId = '7'
  vm.data.dissolutionBlockerItems = ITEMS.slice()
  vm.refreshDissolutionBlockers('')
  requests[0].success({ code: '500', msg: '无权查看该俱乐部的解散阻断明细' })
  assert.equal(vm.data.dissolutionBlockerText, '无权查看该俱乐部的解散阻断明细')
  assert.equal(vm.data.dissolutionBlockerItems.length, 3)
  vm.refreshDissolutionBlockers('')
  requests[1].fail()
  assert.match(vm.data.dissolutionBlockerText, /未能重新核对/)
  assert.equal(vm.data.dissolutionBlockerItems.length, 3)
})

test('从结算页返回:重新打开阻断弹层并回读;非阻断跳转不触发', () => {
  const { vm, requests, navigations } = loadComponent()
  vm.data.clubId = '7'
  vm.data.dissolutionBlockerItems = ITEMS.slice()
  vm.openDissolutionBlockerItem({ currentTarget: { dataset: { index: 2 } } })
  vm.pageShow()
  assert.equal(requests.length, 0, '跳关于页不是处理阻断项')
  vm.openDissolutionBlockerItem({ currentTarget: { dataset: { index: 1 } } })
  assert.deepEqual(navigations[1], '/pages/club/settlement/index?clubId=7')
  vm.pageShow()
  assert.equal(vm.data.showDissolutionBlockers, true)
  assert.equal(requests[0].url, '/api/club/dissolution-blockers')
  vm.pageShow()
  assert.equal(requests.length, 1, '只回读一次')
})

test('回调不在方法简写里依赖 this', () => {
  const src = fs.readFileSync(COMPONENT, 'utf8')
  const start = src.indexOf('refreshDissolutionBlockers(prefix) {')
  const block = src.slice(start, src.indexOf('onBack()', start))
  const callbacks = block.slice(block.indexOf('app.sendRequest'))
  assert.doesNotMatch(callbacks, /\bthis\./)
})
