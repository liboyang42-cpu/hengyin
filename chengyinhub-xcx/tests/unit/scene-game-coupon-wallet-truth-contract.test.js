const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { flattenComponentToPage } = require('../helpers/component-as-page.js')

const ROOT = path.resolve(__dirname, '../..')
const MODULE = path.join(ROOT, 'components/cy/scene-game-coupon-wallet/index.js')

function loadComponent() {
  const sandbox = { requests: [], events: [] }
  global.getApp = () => ({ getAuthorization: () => 'current-session', getUserID: () => 'member-A', sendRequest: (options) => sandbox.requests.push(options) })
  global.Component = (config) => { sandbox.def = Object.assign(flattenComponentToPage(config), { _hide: config.pageLifetimes.hide }) }
  global.wx = {}
  delete require.cache[require.resolve(MODULE)]
  require(MODULE)
  const vm = Object.assign({}, sandbox.def, {
    setData(patch, callback) { Object.assign(this.data, patch); if (callback) callback() },
    triggerEvent(name, detail) { sandbox.events.push({ name, detail }) },
  })
  vm.data = JSON.parse(JSON.stringify(sandbox.def.data))
  return { vm, sandbox }
}

function loadRows(rows) {
  const { vm, sandbox } = loadComponent()
  vm.load()
  sandbox.requests[0].success({ code: 200, data: rows })
  return { vm, sandbox }
}

test('券状态只认数值 0/1/2/3，unknown 不得伪装成待使用', () => {
  const { vm } = loadRows([
    { id: 1, useStatus: 0 },
    { id: 2, useStatus: 1 },
    { id: 3, useStatus: 2 },
    { id: 4, useStatus: 3 },
    ...[null, undefined, true, false, '0', '1', '2', '3', 4, 5].map((useStatus, index) => ({ id: index + 10, useStatus })),
  ])

  assert.deepEqual(vm.data.items.slice(0, 4).map((item) => [item._statusText, item._statusVariant, item._isUsable]), [
    // 2026-09-01 迁 cy-badge 词表:blue→info、done→neutral(未知券本来就是 neutral,没动)
    ['待使用', 'info', true],
    ['已使用', 'neutral', false],
    ['已过期', 'neutral', false],
    // 2026-09-17 券拍板:平台手动失效(3)是有效终态,显示「已失效」且不可用
    ['已失效', 'danger', false],
  ])
  for (const item of vm.data.items.slice(4)) {
    assert.equal(item._statusText, '状态待确认')
    assert.equal(item._statusVariant, 'neutral')
    assert.equal(item._isUsable, false)
  }
})

test('状态筛选与打开核销码都使用严格投影，未知券没有动作语义', () => {
  const { vm, sandbox } = loadRows([
    { id: 1, couponName: '合法券', useStatus: 0 },
    { id: 2, couponName: '字符串伪券', useStatus: '0' },
    { id: 3, couponName: '空值伪券', useStatus: null },
  ])

  vm.selectTab({ currentTarget: { dataset: { tab: 1 } } })
  assert.deepEqual(vm.data.items.map((item) => item.id), [1])

  vm.openCode({ currentTarget: { dataset: { item: vm.data.all[1] } } })
  vm.openCode({ currentTarget: { dataset: { item: vm.data.all[2] } } })
  assert.equal(sandbox.events.length, 0)

  vm.openCode({ currentTarget: { dataset: { item: vm.data.all[0] } } })
  assert.deepEqual(sandbox.events, [{
    name: 'open',
    detail: { id: 'qr-coupon', params: { couponHistoryId: '1', name: '合法券' } },
  }])
})

test('可操作性不靠原始字段，箭头使用项目图标资产', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'components/cy/scene-game-coupon-wallet/index.wxml'), 'utf8')
  const json = JSON.parse(fs.readFileSync(path.join(ROOT, 'components/cy/scene-game-coupon-wallet/index.json'), 'utf8'))
  assert.match(wxml, /item\._isUsable/)
  assert.doesNotMatch(wxml, /item\.useStatus\s*===/)
  assert.match(wxml, /<cy-icon[^>]*name="arrow-right"/)
  assert.doesNotMatch(wxml, /›/)
  assert.equal(json.usingComponents['cy-icon'], '/components/cy/icon/index')
})

test('negative control: Number(useStatus) 强转会被契约判红', () => {
  const source = fs.readFileSync(MODULE, 'utf8')
  assert.doesNotMatch(source, /Number\([^)]*useStatus[^)]*\)/)
})


test('同身份隐藏后重见回读券终态，首展与在途show不重复请求', () => {
  const { vm, sandbox } = loadComponent()
  vm.onLoad()
  vm.onShow()
  assert.equal(sandbox.requests.length, 1)
  sandbox.requests[0].success({ code: 200, data: [{ id: 11, useStatus: 0 }] })
  if (sandbox.requests[0].complete) sandbox.requests[0].complete()
  sandbox.def._hide.call(vm)
  vm.onShow()
  assert.equal(sandbox.requests.length, 2)
  vm.onShow()
  assert.equal(sandbox.requests.length, 2)
  sandbox.requests[1].success({ code: 200, data: [{ id: 11, useStatus: 2 }] })
  assert.equal(vm.data.items[0]._statusText, '已过期')
  assert.equal(vm.data.items[0]._canView, false)
  sandbox.requests[0].success({ code: 200, data: [{ id: 11, useStatus: 0 }] })
  assert.equal(vm.data.items[0]._statusText, '已过期', '旧代回包不能复活券')
})

for (const [status, label] of [[1, '已使用'], [2, '已过期'], [3, '已失效']]) {
  test(`首次读取在途时隐藏后返回重新读取${label}，旧回包不复活券`, () => {
    const { vm, sandbox } = loadComponent()
    vm.onLoad()
    vm.onShow()
    assert.equal(sandbox.requests.length, 1)
    sandbox.def._hide.call(vm)
    vm.onShow()
    assert.equal(sandbox.requests.length, 2, '返回不能复用隐藏前仍在途的读取')
    vm.onShow()
    assert.equal(sandbox.requests.length, 2, '本次返回后的重复 show 不重复读取')
    const old = sandbox.requests[0]
    const loading = JSON.stringify(vm.data)
    old.success({ code: 200, data: [{ id: 11, useStatus: 0 }] })
    old.fail()
    old.successStatusAbnormal()
    old.complete()
    assert.equal(JSON.stringify(vm.data), loading, '旧回包不能覆盖新请求的加载态')
    vm.onShow()
    assert.equal(sandbox.requests.length, 2, '旧 complete 不能结束新请求')
    sandbox.requests[1].success({ code: 200, data: [{ id: 11, useStatus: status }] })
    assert.equal(vm.data.items[0]._statusText, label)
    assert.equal(vm.data.items[0]._canView, false)
    assert.equal(vm.data.items[0]._isUsable, false)
    const current = JSON.stringify(vm.data)
    old.success({ code: 200, data: [{ id: 11, useStatus: 0 }] })
    old.fail()
    old.successStatusAbnormal()
    old.complete()
    assert.equal(JSON.stringify(vm.data), current, '旧回包不能复活终态券')
    vm.openCode({ currentTarget: { dataset: { item: vm.data.items[0] } } })
    assert.equal(sandbox.events.length, 0)
    sandbox.def._hide.call(vm)
    vm.onShow()
    vm.onShow()
    assert.equal(sandbox.requests.length, 3, '下一次返回仍只重新读取一次')
  })
}
