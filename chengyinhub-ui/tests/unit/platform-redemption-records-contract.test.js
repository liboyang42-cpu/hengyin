const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8')
}

function loadPage(overrides = {}) {
  const source = read('src/views/business/redemption/index.vue')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ listCouponRedemptions, listPointsMallRedemptions, listGiftRedemptions \} from '@\/api\/business\/redemption'$/m,
      'const { listCouponRedemptions, listPointsMallRedemptions, listGiftRedemptions } = dependencies;')
    .replace(/^import \{ checkPermi \} from '@\/utils\/permission'$/m, 'const { checkPermi } = dependencies;')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {};')
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))(Object.assign({
    listCouponRedemptions: () => Promise.resolve({ rows: [], total: 0 }),
    listPointsMallRedemptions: () => Promise.resolve({ rows: [], total: 0 }),
    listGiftRedemptions: () => Promise.resolve({ rows: [], total: 0 }),
    checkPermi: () => true
  }, overrides))
}

function vmFor(component, extra = {}) {
  const vm = Object.assign(component.data(), extra)
  Object.keys(component.methods).forEach(key => {
    if (!(key in extra)) vm[key] = component.methods[key].bind(vm)
  })
  Object.defineProperty(vm, 'visibleSources', { get: () => component.computed.visibleSources.call(vm) })
  return vm
}

test('三类兑换来源各走各的端点、各自分页，同名对象不合并', async () => {
  const calls = []
  const component = loadPage({
    listCouponRedemptions: params => {
      calls.push(['coupon', params])
      return Promise.resolve({ rows: [
        { historyId: 11, couponId: 3, couponName: '同名权益', memberId: 7, useStatus: 1 },
        { historyId: 12, couponId: 4, couponName: '同名权益', memberId: 7, useStatus: 0 }
      ], total: 2 })
    },
    listPointsMallRedemptions: params => {
      calls.push(['points', params])
      return Promise.resolve({ rows: [{ orderId: 21, orderSn: 'PM21', memberId: 7, quantity: 3, status: 2 }], total: 1 })
    },
    listGiftRedemptions: params => {
      calls.push(['gift', params])
      return Promise.resolve({ rows: [{ consumptionId: 31, giftId: 5, giftName: '同名权益', memberId: 7, points: 9, delFlag: 0 }], total: 1 })
    }
  })
  const vm = vmFor(component, { $route: { query: {} } })

  await vm.getList()
  assert.equal(vm.rows.length, 2, '同名的两张券是两条记录，不汇总')
  assert.deepEqual(vm.rows.map(row => vm.businessIdLabel(row)), ['领取记录 #11', '领取记录 #12'])
  assert.equal(vm.fulfilment(vm.rows[0]).label, '已核销')
  assert.equal(vm.quantityOf(vm.rows[0]), 1)

  vm.activeSource = 'points'
  await vm.handleSourceChange()
  assert.equal(vm.businessIdLabel(vm.rows[0]), '订单 PM21')
  assert.equal(vm.quantityOf(vm.rows[0]), 3)
  assert.equal(vm.fulfilment(vm.rows[0]).label, '已发货')

  vm.activeSource = 'gift'
  await vm.handleSourceChange()
  assert.equal(vm.businessIdLabel(vm.rows[0]), '礼物消费 #31')
  assert.equal(vm.pointsOf(vm.rows[0]), 9)

  assert.deepEqual(calls.map(call => call[0]), ['coupon', 'points', 'gift'])
  const source = read('src/views/business/redemption/index.vue')
  assert.doesNotMatch(source, /reduce\(|groupBy|合并汇总后/, '页面不得按名称聚合')
})

test('平台失效与过期的券分开显示，读取失败不伪装成空记录', async () => {
  const component = loadPage({ listCouponRedemptions: () => Promise.reject(new Error('offline')) })
  const vm = vmFor(component, { $route: { query: {} } })
  assert.equal(vm.fulfilment({ source: 'coupon', useStatus: 0, couponStatus: 3 }).label, '已平台失效')
  assert.equal(vm.fulfilment({ source: 'coupon', useStatus: 2, couponStatus: 1 }).label, '已过期')
  assert.equal(vm.fulfilment({ source: 'points', status: 4, aftersaleStatus: 4 }).label, '已退款')

  vm.rows = [{ historyId: 1 }]
  await vm.getList()
  assert.equal(vm.loadError, true)
  assert.deepEqual(vm.rows, [])
})

test('只展示有列表权限的来源，路由沿用原有列表权限', () => {
  const component = loadPage({ checkPermi: perms => perms[0] === 'business:order:list' })
  const vm = vmFor(component, { $route: { query: {} } })
  assert.deepEqual(vm.visibleSources.map(source => source.key), ['points'])
  const router = read('src/router/index.js')
  assert.match(router, /path:\s*'\/supply\/redemption-records'[\s\S]*?permissions:\s*\['business:couponhistory:list', 'business:order:list', 'business:crowdfunding:list'\]/)
})
