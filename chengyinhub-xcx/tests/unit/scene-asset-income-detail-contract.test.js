// H02 收益明细。
// 起因:迁移时跑负控发现「把 moneyText 的空值返回改成 '0.00'」全仓零断言能判红 ——
// 既有门禁只验了 wxml 的渲染分支(amountText != null ? … : '—'),没验产出 amountText 的那一步。
// 空值被伪造成 0.00 之后 wxml 判断恒真,占位分支永远走不到,门禁却全绿。这里补上行为断言。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { flattenComponentToPage } = require('../helpers/component-as-page.js')

const ROOT = path.resolve(__dirname, '../..')
const MODULE = path.join(ROOT, 'components/cy/scene-asset-income-detail/index.js')

function loadComponent() {
  const sandbox = { requests: [] }
  global.getApp = () => ({
    getUserID: () => 7, /* Explicit authenticated private-list fixture. */ sendRequest: (o) => sandbox.requests.push(o) })
  global.Component = (config) => { sandbox.component = config; sandbox.def = flattenComponentToPage(config) }
  global.wx = {}
  delete require.cache[require.resolve(MODULE)]
  require(MODULE)
  const vm = Object.assign({}, sandbox.def, {
    setData(patch) { Object.assign(this.data, patch) },
  })
  vm.data = JSON.parse(JSON.stringify(sandbox.def.data))
  return { vm, sandbox }
}

test('金额为空 ⇒ amountText 为 null(渲 —),绝不伪造成 0.00', () => {
  const { vm } = loadComponent()
  for (const blank of [null, undefined, '', 'abc', false, true, [], [5], ' ', '0']) {
    const [row] = vm.formatList([{ changeBalance: blank, changeType: 1 }])
    assert.equal(row.amountText, null, `changeBalance=${String(blank)} 应产出 null 让 wxml 走占位分支`)
  }
})

test('金额是 0 是合法记录,必须渲 0.00 而不是 —', () => {
  const { vm } = loadComponent()
  const [row] = vm.formatList([{ changeBalance: 0, changeType: 1 }])
  assert.equal(row.amountText, '+¥0.00')
})

// changeType 是收支方向(后端 UmsMemberBalanceDetail:1=收入 2=支出),不是流水状态。
// 原契约锁的是 statusText '已到账'/'处理中' —— 那两个词库里没有对应字段,是编出来的,
// 且把每一条支出都说成「处理中」。契约锁着假语义,等于反向锁死修复。
test('changeType 解释成收支方向,不再冒充流水状态', () => {
  const { vm } = loadComponent()
  const income = vm.formatList([{ changeType: 1, changeBalance: 128 }])[0]
  const expense = vm.formatList([{ changeType: 2, changeBalance: -50 }])[0]

  assert.equal(income.isIncome, true)
  assert.equal(income.directionText, '收入')
  assert.equal(expense.isIncome, false)
  assert.equal(expense.directionText, '支出')

  // 组件不得再产出 statusText —— 它是被删掉的假状态,复活就红
  assert.equal(income.statusText, undefined)
  assert.equal(expense.statusText, undefined)
})

test('方向不只靠颜色:符号进文案,支出不得渲成 +¥-50.00', () => {
  const { vm } = loadComponent()
  assert.equal(vm.formatList([{ changeType: 1, changeBalance: 128 }])[0].amountText, '+¥128.00')
  // 写入方给支出配的是 changeBalance.negate(),负号必须被方向符号吸收,不能两个负号叠着
  assert.equal(vm.formatList([{ changeType: 2, changeBalance: -50 }])[0].amountText, '−¥50.00')
  // 支出但后端给了正值时,方向仍以 changeType 为准
  assert.equal(vm.formatList([{ changeType: 2, changeBalance: 50 }])[0].amountText, '−¥50.00')
})

test('未知 changeType 不得默认为支出,显示中性待确认状态', () => {
  const { vm } = loadComponent()
  for (const changeType of [null, undefined, true, false, '1', 0, 3]) {
    const row = vm.formatList([{ changeType, changeBalance: 88 }])[0]
    assert.equal(row.isIncome, null, `changeType=${String(changeType)} 不得伪装成支出`)
    assert.equal(row.directionKnown, false)
    assert.equal(row.directionText, '方向待确认')
    assert.equal(row.amountText, '¥88.00', '方向未知时金额不得带收入/支出符号')
  }
})

test('negative control:把 changeType 当状态用、或退回硬拼 + 号,各自判红', () => {
  const { vm } = loadComponent()
  const expense = vm.formatList([{ changeType: 2, changeBalance: -50 }])[0]
  // 退回「支出=处理中」的写法
  assert.notEqual(expense.directionText, '处理中', '支出被说成处理中 = 拿方向字段冒充状态')
  // 退回硬拼 '+¥' 的写法
  assert.doesNotMatch(expense.amountText, /^\+/, '支出不得带加号')
  assert.doesNotMatch(expense.amountText, /-\d/, '负号必须被方向符号吸收,不能渲成 ¥-50.00')
})

test('接口返回的 rows 不是数组时落错误态,不当成空列表', () => {
  const { vm, sandbox } = loadComponent()
  vm.getList()
  sandbox.requests[0].success({ code: '200', data: { rows: '不是数组' } })
  assert.equal(vm.data.loadErr, true)
  assert.equal(vm.data.loading, false)
})

test('旧筛选的成功和失败回执都不能覆盖新筛选', () => {
  for (const callback of ['success', 'fail']) {
    const { vm, sandbox } = loadComponent()
    vm.getList()
    const old = sandbox.requests[0]
    vm.onFilterChange({ detail: { key: 'create' } })
    sandbox.requests[1].success({ code: 200, data: { rows: [{ id: 2, changeBalance: 8, changeType: 1 }], total: 1 } })
    const current = JSON.parse(JSON.stringify(vm.data))
    old[callback]({ code: 200, data: { rows: [{ id: 1, changeBalance: 4, changeType: 1 }], total: 20 } })
    assert.deepEqual(vm.data, current)
  }
})

test('切到主办分润或正文卸载后旧请求不再回填', () => {
  for (const target of ['club', 'detached']) {
    for (const callback of ['success', 'fail']) {
      const { vm, sandbox } = loadComponent()
      vm.getList()
      if (target === 'club') vm.onFilterChange({ detail: { key: 'club' } })
      else sandbox.component.lifetimes.detached.call(vm)
      const current = JSON.parse(JSON.stringify(vm.data))
      sandbox.requests[0][callback]({ code: 200, data: { rows: [{ id: 1 }], total: 1 } })
      assert.deepEqual(vm.data, current)
    }
  }
})

test('当前筛选请求失败可以重试恢复，旧重试回执不能覆盖新结果', () => {
  const { vm, sandbox } = loadComponent()
  vm.getList()
  sandbox.requests[0].fail()
  assert.equal(vm.data.loadErr, true)
  vm.onRetry()
  sandbox.requests[1].success({ code: 200, data: { rows: [], total: 0 } })
  sandbox.requests[0].fail()
  assert.equal(vm.data.loadErr, false)
  assert.equal(vm.data.loading, false)
  assert.deepEqual(vm.data.list, [])
})

test('当前筛选分页仍追加记录，加载中不能重复发起下一页', () => {
  const { vm, sandbox } = loadComponent()
  vm.getList()
  sandbox.requests[0].success({ code: 200, data: { rows: [{ id: 1, changeBalance: 8, changeType: 1 }], total: 11 } })
  vm.loadMore()
  vm.loadMore()
  assert.equal(sandbox.requests.length, 2)
  assert.equal(sandbox.requests[1].data.pageNum, 2)
  sandbox.requests[1].success({ code: 200, data: { rows: [{ id: 2, changeBalance: 4, changeType: 1 }], total: 11 } })
  assert.deepEqual(vm.data.list.map(row => row.id), [1, 2])
  assert.equal(vm.data.hasMore, false)
})

test('失败页重试保留既有流水，连续失败后加载更多仍重试原页且不重复追加', () => {
  const { vm, sandbox } = loadComponent()
  const response = id => ({ code: 200, data: {
    rows: [{ id, changeBalance: id, changeType: 1 }], total: 31,
  } })
  vm.getList()
  sandbox.requests[0].success(response(1))
  vm.loadMore()
  sandbox.requests[1].success(response(2))
  vm.loadMore()
  sandbox.requests[2].fail({ code: 500 })
  const saved = JSON.stringify(vm.data.list)
  vm.onRetry()
  assert.equal(JSON.stringify(vm.data.list), saved)
  assert.equal(sandbox.requests[3].data.pageNum, 3)
  sandbox.requests[3].successStatusAbnormal({ code: 500 })
  assert.equal(JSON.stringify(vm.data.list), saved)
  vm.loadMore()
  assert.equal(sandbox.requests[4].data.pageNum, 3)
  sandbox.requests[4].success(response(3))
  assert.deepEqual(vm.data.list.map(row => row.id), [1, 2, 3])
  assert.equal(vm.data.loadErr, false)
})

test('wxml 的占位分支确实存在(否则上面的 null 无处落地)', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'components/cy/scene-asset-income-detail/index.wxml'), 'utf8')
  // 符号已经进了 amountText,wxml 不许再自己拼 —— 拼了支出就会变成 +¥-50.00
  assert.match(wxml, /\{\{item\.amountText \|\| '—'\}\}/)
  assert.doesNotMatch(wxml, /'\+¥'\s*\+/, "wxml 不得硬拼 '+¥',方向符号由 formatList 给")
  // 收支两档各自挂色；方向未知必须保持中性色，不能掉进支出红色
  assert.match(wxml, /item\.directionKnown\s*\?/)
  assert.match(wxml, /item\.isIncome \? 'id-item-amount--in' : 'id-item-amount--out'/)
})

test('入账绿/出账红两档在 wxss 里真有定义,且走 DS token 不是字面色', () => {
  const wxss = fs.readFileSync(path.join(ROOT, 'components/cy/scene-asset-income-detail/index.wxss'), 'utf8')
  assert.match(wxss, /\.id-item-amount--in\s*\{[^}]*color:\s*var\(--cy-success\)/)
  assert.match(wxss, /\.id-item-amount--out\s*\{[^}]*color:\s*var\(--cy-danger\)/)
})
