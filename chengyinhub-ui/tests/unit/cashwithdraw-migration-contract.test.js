const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8')
}

function loadCashwithdraw() {
  const source = read('src/views/business/cashwithdraw/index.vue')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ listCashwithdraw, requeryCashwithdraw, manualRefundCashwithdraw \} from "@\/api\/business\/cashwithdraw"$/m, 'const { listCashwithdraw, requeryCashwithdraw, manualRefundCashwithdraw } = dependencies;')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {};')
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))({
    listCashwithdraw: () => Promise.resolve({ rows: [], total: 0 }),
    requeryCashwithdraw: () => Promise.resolve({}),
    manualRefundCashwithdraw: () => Promise.resolve({ msg: 'ok' })
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
  assert.match(source, /v-if="row\.status === 0"[\s\S]*?重查|重查[\s\S]*?v-if="row\.status === 0"/)
  assert.match(source, /v-if="row\.status === 3"[\s\S]*?登记回补|登记回补[\s\S]*?v-if="row\.status === 3"/)
  assert.match(source, /v-if="row\.status === 4"[\s\S]*?复核回补|复核回补[\s\S]*?v-if="row\.status === 4"/)
  assert.match(source, /status:\s*this\.manualForm\.status/)
})
