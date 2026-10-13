const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')
const routerSource = fs.readFileSync(path.join(uiRoot, 'src/router/index.js'), 'utf8')
const legacySource = fs.readFileSync(path.join(uiRoot, 'src/views/business/memberbalancedetail/index.vue'), 'utf8')
const ledgerSource = fs.readFileSync(path.join(uiRoot, 'src/views/business/finance/ledger.vue'), 'utf8')
const memberDrawerSource = fs.readFileSync(path.join(uiRoot, 'src/views/business/member/balance-detail.vue'), 'utf8')

function loadFinanceLedger(listMemberbalancedetail) {
  const script = ledgerSource.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ listMemberbalancedetail \} from '@\/api\/business\/memberbalancedetail'$/m, 'const { listMemberbalancedetail } = dependencies')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {}')
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))({ listMemberbalancedetail })
}

function loadLegacyBalancePage() {
  const script = legacySource.match(/<script>([\s\S]*?)<\/script>/)[1]
  return new Function(script.replace(/export\s+default\s*/, 'return '))()
}

function createVm(component) {
  const vm = Object.assign(component.data(), {
    $route: { query: {} },
    $router: { replace: () => Promise.resolve() }
  })
  for (const name of ['routeSignature', 'hydrateFromRoute', 'getList', 'handleQuery', 'resetQuery', 'clearFilter', 'handlePagination']) {
    vm[name] = component.methods[name].bind(vm)
  }
  return vm
}

function loadMemberBalanceDrawer(listMemberbalancedetail) {
  const script = memberDrawerSource.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ listMemberbalancedetail \} from "@\/api\/business\/memberbalancedetail"$/m, 'const { listMemberbalancedetail } = dependencies')
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))({ listMemberbalancedetail })
}

function createDrawerVm(component) {
  const vm = Object.assign(component.data(), {
    download: () => {}
  })
  for (const name of ['init', 'getList', 'handleQuery', 'resetQuery']) {
    vm[name] = component.methods[name].bind(vm)
  }
  return vm
}

test('资金流水是受原余额明细读取权限保护的隐藏路由，旧页仅兼容跳转', () => {
  assert.match(routerSource, /path:\s*'\/finance'/)
  assert.match(routerSource, /permissions:\s*\['business:memberbalancedetail:list'\]/)
  assert.match(routerSource, /path:\s*'ledger'/)
  assert.match(routerSource, /name:\s*'financeLedger'/)
  assert.match(legacySource, /name:\s*'financeLedger'/)
  assert.doesNotMatch(legacySource, /listMemberbalancedetail/)
})

test('旧余额明细地址将既有筛选条件交给资金流水', () => {
  const redirects = []
  const component = loadLegacyBalancePage()
  component.created.call({
    $route: { query: { memberId: '17', eventType: '3', changeType: '2' } },
    $router: { replace: target => redirects.push(target) }
  })

  assert.deepEqual(redirects, [{
    name: 'financeLedger',
    query: { memberId: '17', eventType: '3', changeType: '2' }
  }])
})

test('资金流水只发起读取请求，且旧回包不能覆盖最新筛选', async () => {
  const pending = []
  const component = loadFinanceLedger(query => new Promise(resolve => pending.push({ query, resolve })))
  const vm = createVm(component)

  const first = vm.getList()
  vm.queryForm.memberId = '17'
  vm.queryForm.eventType = 3
  vm.queryForm.changeType = 2
  const second = vm.handleQuery()

  assert.deepEqual(pending[1].query, {
    pageNum: 1,
    pageSize: 10,
    memberId: '17',
    eventType: 3,
    eventId: null,
    changeType: 2
  })
  pending[1].resolve({ rows: [{ id: 2, memberId: 17 }], total: 1 })
  await second
  pending[0].resolve({ rows: [{ id: 1, memberId: 1 }], total: 1 })
  await first

  assert.deepEqual(vm.ledgerList, [{ id: 2, memberId: 17 }])
  assert.equal(vm.total, 1)
  assert.equal(vm.ledgerError, false)
})

test('资金流水切换 route query 时重置分页与旧筛选，并只保留新查询', async () => {
  const pending = []
  const component = loadFinanceLedger(query => new Promise(resolve => pending.push({ query, resolve })))
  const vm = createVm(component)
  const oldQuery = { memberId: '17', eventType: '3', eventId: '56', changeType: '2' }

  vm.hydrateFromRoute(oldQuery)
  vm.queryParams.pageNum = 4
  const first = vm.getList()
  let nextCalled = false
  const second = component.beforeRouteUpdate.call(
    vm,
    { query: { memberId: '18', eventType: '1' } },
    { query: oldQuery },
    () => { nextCalled = true }
  )

  assert.equal(nextCalled, true)
  assert.deepEqual(pending[1].query, {
    pageNum: 1,
    pageSize: 10,
    memberId: '18',
    eventType: 1,
    eventId: null,
    changeType: null
  })
  pending[1].resolve({ rows: [{ id: 2, memberId: 18 }], total: 1 })
  await second
  pending[0].resolve({ rows: [{ id: 1, memberId: 17 }], total: 1 })
  await first

  assert.deepEqual(vm.ledgerList, [{ id: 2, memberId: 18 }])
  assert.equal(vm.queryParams.pageNum, 1)
  assert.equal(vm.queryParams.eventId, null)
})

test('资金流水以方向决定符号，已带负号的支出不显示双负号', () => {
  const component = loadFinanceLedger(() => Promise.resolve({ rows: [], total: 0 }))
  assert.equal(component.methods.formatAmount({ changeType: 2, changeBalance: -12.5 }), '-¥12.50')
  assert.equal(component.methods.formatAmount({ changeType: 1, changeBalance: -12.5 }), '+¥12.50')
  assert.equal(component.methods.formatAmount({ changeType: 2, changeBalance: null }), '未记录')
  assert.equal(component.methods.money(null), '未记录')
})

test('资金流水保留原始事件类型，包含新增资金写入方使用的类型', () => {
  const component = loadFinanceLedger(() => Promise.resolve({ rows: [], total: 0 }))
  assert.equal(component.methods.eventTypeLabel(6), '事件类型 #6')
  assert.equal(component.methods.eventTypeLabel(9), '事件类型 #9')
  assert.equal(component.methods.eventTypeLabel(91), '事件类型 #91')
})

test('用户 360 余额抽屉只读且始终锁定当前用户范围', () => {
  assert.match(memberDrawerSource, /import \{ listMemberbalancedetail \} from "@\/api\/business\/memberbalancedetail"/)
  assert.doesNotMatch(memberDrawerSource, /getMemberbalancedetail|addMemberbalancedetail|updateMemberbalancedetail|delMemberbalancedetail/)
  assert.doesNotMatch(memberDrawerSource, /handleAdd|handleUpdate|handleDelete|handleExport|submitForm|download|memberbalancedetail:export|<el-dialog/)
  assert.match(memberDrawerSource, /this\.queryParams\.memberId = this\.memberId/)
  assert.doesNotMatch(memberDrawerSource, /created\(\)\s*\{\s*this\.getList\(\)/)
})

test('用户 360 切换用户时只保留当前用户的余额流水', async () => {
  const pending = []
  const component = loadMemberBalanceDrawer(query => new Promise(resolve => pending.push({ query, resolve })))
  const vm = createDrawerVm(component)

  const first = vm.init(17)
  const second = vm.init(18)
  assert.equal(pending[0].query.memberId, 17)
  assert.equal(pending[1].query.memberId, 18)
  pending[1].resolve({ rows: [{ id: 2, memberId: 18 }], total: 1 })
  await second
  pending[0].resolve({ rows: [{ id: 1, memberId: 17 }], total: 1 })
  await first

  assert.equal(vm.memberId, 18)
  assert.equal(vm.queryParams.memberId, 18)
  assert.deepEqual(vm.memberbalancedetailList, [{ id: 2, memberId: 18 }])
})
