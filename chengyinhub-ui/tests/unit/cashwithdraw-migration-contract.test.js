const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8')
}

function loadCashwithdraw(overrides = {}) {
  const source = read('src/views/business/cashwithdraw/index.vue')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ listCashwithdraw, requeryCashwithdraw, manualRefundCashwithdraw \} from "@\/api\/business\/cashwithdraw"$/m, 'const { listCashwithdraw, requeryCashwithdraw, manualRefundCashwithdraw } = dependencies;')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {};')
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))({
    listCashwithdraw: () => Promise.resolve({ rows: [], total: 0 }),
    requeryCashwithdraw: () => Promise.resolve({}),
    manualRefundCashwithdraw: () => Promise.resolve({ msg: 'ok' }),
    ...overrides
  })
}

test('微信提现出金以原列表权限注册唯一的 finance/withdraw/wechat 入口', () => {
  const router = read('src/router/index.js')
  const dashboard = read('src/views/index.vue')

  assert.match(router, /path:\s*['"]\/finance\/withdraw\/wechat['"][\s\S]*?permissions:\s*\[['"]business:cashwithdraw:list['"]\][\s\S]*?name:\s*['"]financeWechatWithdraw['"]/)
  assert.match(router, /component:\s*\(\)\s*=>\s*import\(['"]@\/views\/business\/cashwithdraw\/index['"]\)/)
  assert.match(dashboard, /label:\s*['"]出金队列['"][\s\S]*?path:\s*['"]\/finance\/withdraw\/wechat['"]/)
  assert.doesNotMatch(dashboard, /label:\s*['"]出金队列['"][\s\S]*?path:\s*['"]\/business\/cashwithdraw['"]/)
})

test('旧微信提现入口只兼容转发到规范路由，不在旧地址读取提现队列', () => {
  const component = loadCashwithdraw()
  const legacyTargets = []
  let legacyReads = 0
  const legacyVm = Object.assign(component.data(), {
    $route: { path: '/business/cashwithdraw', query: { status: '3', pageNum: '2' } },
    $router: { replace: target => legacyTargets.push(target) },
    getList: () => { legacyReads += 1 }
  })

  component.methods.ensureCanonicalRoute.call(legacyVm)

  assert.deepEqual(legacyTargets, [{
    name: 'financeWechatWithdraw',
    query: { status: '3', pageNum: '2' }
  }])
  assert.equal(legacyReads, 0)
})

test('规范微信提现入口保留查询与人工回补的既有状态边界', () => {
  const component = loadCashwithdraw()
  let reads = 0
  const vm = Object.assign(component.data(), {
    $route: { path: '/finance/withdraw/wechat', query: {} },
    $router: { replace: () => assert.fail('规范入口不应重定向') },
    getList: () => { reads += 1 }
  })

  component.methods.ensureCanonicalRoute.call(vm)

  assert.equal(reads, 1)
  const source = read('src/views/business/cashwithdraw/index.vue')
  assert.match(source, /created\(\)\s*\{\s*this\.ensureCanonicalRoute\(\)/)
  assert.match(source, /'\$route'\(\)\s*\{\s*this\.ensureCanonicalRoute\(\)/)
  assert.match(source, /v-hasPermi="\['business:cashwithdraw:manage'\]" v-if="canRequery\(row\)">重查/)
  assert.match(source, /v-if="row\.status === 3"[\s\S]*?登记回补|登记回补[\s\S]*?v-if="row\.status === 3"/)
  assert.match(source, /v-if="row\.status === 4"[\s\S]*?复核回补|复核回补[\s\S]*?v-if="row\.status === 4"/)
  assert.match(source, /status:\s*this\.manualForm\.status/)
})

test('处理中和人工核查/复核单可重查，终态与未知状态均不开放', () => {
  const { canRequery } = loadCashwithdraw().methods
  for (const status of [0, 3, 4]) assert.equal(canRequery({ status }), true)
  for (const status of [1, 2, 5, -1, 99, '3', null, undefined]) assert.equal(canRequery({ status }), false)
  assert.equal(canRequery(null), false)
})

test('重查3/4只调用既有查询API并刷新，不调用人工回补，也不声称复核完成', async () => {
  const queried = []
  let credits = 0
  let reads = 0
  const messages = []
  const component = loadCashwithdraw({
    requeryCashwithdraw: id => { queried.push(id); return Promise.resolve({ data: { status: 3 } }) },
    manualRefundCashwithdraw: () => { credits += 1; return Promise.resolve({}) }
  })
  const vm = {
    canRequery: component.methods.canRequery,
    getList: () => { reads += 1 },
    $modal: { msgSuccess: message => messages.push(message) }
  }
  for (const status of [3, 4]) await component.methods.handleRequery.call(vm, { id: 70 + status, status })
  await component.methods.handleRequery.call(vm, { id: 99, status: 2 })
  assert.deepEqual(queried, [73, 74]); assert.equal(reads, 2); assert.equal(credits, 0)
  for (const message of messages) {
    assert.match(message, /核查/); assert.doesNotMatch(message, /已退回|复核完成/)
  }
})
