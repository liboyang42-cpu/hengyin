const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const VueRouter = require('vue-router')

const uiRoot = path.resolve(__dirname, '../..')

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8')
}

function loadCoupon(overrides = {}) {
  const source = read('src/views/business/coupon/index.vue')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ listCoupon, getCoupon, addCoupon, updateCoupon, previewCouponRule, stopIssueCoupon, invalidateCoupon, listCouponRuleVersions, getCouponAffectedHolders \} from '@\/api\/business\/coupon'$/m, 'const { listCoupon, getCoupon, addCoupon, updateCoupon, previewCouponRule, stopIssueCoupon, invalidateCoupon, listCouponRuleVersions, getCouponAffectedHolders } = dependencies;')
    .replace(/^import \{ checkPermi \} from '@\/utils\/permission'$/m, 'const { checkPermi } = dependencies;')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {};')
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))(Object.assign({
    listCoupon: () => Promise.resolve({ rows: [], total: 0 }),
    getCoupon: () => Promise.resolve({ data: {} }),
    addCoupon: () => Promise.resolve({}),
    updateCoupon: () => Promise.resolve({}),
    previewCouponRule: () => Promise.resolve({ data: null }),
    stopIssueCoupon: () => Promise.resolve({}),
    invalidateCoupon: () => Promise.resolve({ data: null }),
    listCouponRuleVersions: () => Promise.resolve({ data: [] }),
    getCouponAffectedHolders: () => Promise.resolve({ data: { affectedHolderCount: 0 } }),
    checkPermi: () => true
  }, overrides))
}

test('优惠券以原列表权限注册唯一的 supply/coupon 入口', () => {
  const router = read('src/router/index.js')

  assert.match(router, /path:\s*['"]\/supply\/coupon['"][\s\S]*?permissions:\s*\[['"]business:coupon:list['"]\][\s\S]*?name:\s*['"]supplyCoupon['"]/)
  assert.match(router, /component:\s*\(\)\s*=>\s*import\(['"]@\/views\/business\/coupon\/index['"]\)/)
  assert.match(router, /path:\s*['"]\/business\/coupon['"][\s\S]*?redirect:\s*to\s*=>\s*\(\{\s*name:\s*['"]supplyCoupon['"]/)
  assert.ok(router.indexOf("path: '/supply/coupon'") < router.indexOf("path: '/business/coupon'"))
})

test('旧优惠券地址在后台同 path 菜单注册后仍路由级跳到规范入口', () => {
  const router = new VueRouter({ mode: 'abstract', routes: [] })
  router.addRoutes([
    { path: '/supply/coupon', name: 'supplyCoupon' },
    { path: '/business/coupon', redirect: to => ({ name: 'supplyCoupon', query: Object.assign({}, to.query) }) }
  ])
  router.addRoutes([{ path: '/business/coupon', name: 'serverLegacyMenu' }])

  const resolved = router.match('/business/coupon?name=%E5%A4%8F%E6%97%A5&couponType=1&pageNum=2')
  assert.equal(resolved.name, 'supplyCoupon')
  assert.equal(resolved.path, '/supply/coupon')
  assert.deepEqual(resolved.query, { name: '夏日', couponType: '1', pageNum: '2' })
})

test('旧优惠券入口只兼容转发，不在旧地址读取列表', () => {
  const component = loadCoupon()
  const targets = []
  let reads = 0
  const vm = Object.assign(component.data(), {
    $route: { path: '/business/coupon', query: { name: '夏日', couponType: '1' } },
    $router: { replace: target => targets.push(target) },
    getList: () => { reads += 1 }
  })

  component.methods.ensureCanonicalRoute.call(vm)

  assert.deepEqual(targets, [{ name: 'supplyCoupon', query: { name: '夏日', couponType: '1' } }])
  assert.equal(reads, 0)
})

test('规范优惠券入口恢复并清空筛选，保留读取与状态动作权限边界', () => {
  const component = loadCoupon()
  let reads = 0
  const vm = Object.assign(component.data(), {
    $route: { path: '/supply/coupon', query: { name: '夏日', couponType: '1', scopeType: '0', status: '3', pageNum: '2', pageSize: '20' } },
    $router: { replace: () => assert.fail('规范入口不应重定向') },
    applyRouteQuery() { component.methods.applyRouteQuery.call(this) },
    getList: () => { reads += 1 }
  })

  component.methods.ensureCanonicalRoute.call(vm)

  assert.deepEqual(vm.query, { name: '夏日', couponType: 1, scopeType: 0, merchantId: null, status: 3, pageNum: 2, pageSize: 20 })
  assert.equal(reads, 1)

  const targets = []
  const filterVm = Object.assign(component.data(), {
    $route: { path: '/supply/coupon', query: { name: '夏日', couponType: '1', pageNum: '2' } },
    $router: { replace: target => targets.push(target) },
    getList: () => assert.fail('筛选变更应先更新路由'),
    routeQuery() { return component.methods.routeQuery.call(this) },
    sameRouteQuery(next) { return component.methods.sameRouteQuery.call(this, next) }
  })
  filterVm.query = { name: null, couponType: null, scopeType: null, merchantId: null, status: null, pageNum: 1, pageSize: 10 }

  component.methods.syncRoute.call(filterVm)

  assert.deepEqual(targets, [{ name: 'supplyCoupon', query: {} }])
  const source = read('src/views/business/coupon/index.vue')
  assert.match(source, /v-if="canQueryCoupon && canEditCoupon"/)
  assert.match(source, /v-hasPermi="\['business:coupon:query'\]"/)
  assert.match(source, /v-hasPermi="\['business:coupon:edit'\]"/)
  assert.match(source, /v-hasPermi="\['business:couponhistory:list'\]"/)
  assert.doesNotMatch(source, /\bdelCoupon\b/)
  assert.doesNotMatch(source, /label="已使用数量"|label="领取数量"/)
})

test('平台失效走独立接口：必须带读取时的规则版本与独立原因，并展示受影响持券人', async () => {
  let invalidated = null
  let promptOptions = null
  const component = loadCoupon({
    getCoupon: () => Promise.resolve({ data: { id: 9, ruleVersion: 4 } }),
    getCouponAffectedHolders: () => Promise.resolve({ data: { affectedHolderCount: 2 } }),
    invalidateCoupon: (id, payload) => {
      invalidated = { id, payload }
      return Promise.resolve({ data: { affectedHolderCount: 2, holders: [{ historyId: 1, memberId: 5 }], version: { reason: '套券' } } })
    },
    updateCoupon: () => assert.fail('平台失效不得再走通用编辑接口')
  })
  const vm = Object.assign(component.data(), {
    $prompt: (message, title, options) => {
      promptOptions = options
      assert.match(message, /2 张未使用/)
      return Promise.resolve({ value: '  套券 ' })
    },
    $modal: { msgSuccess: () => {} },
    getList: () => Promise.resolve()
  })

  await component.methods.deactivateCoupon.call(vm, { id: 9, status: 1 })

  assert.deepEqual(invalidated, { id: 9, payload: { expectedVersion: 4, reason: '套券' } })
  assert.equal(promptOptions.inputValidator('   '), false, '原因必填')
  assert.equal(vm.holdersOpen, true)
  assert.equal(vm.invalidationResult.holders[0].memberId, 5)
})

test('停发只拦新领取：带规则版本提交到停发接口，不改已领取券', async () => {
  let stopped = null
  const component = loadCoupon({
    getCoupon: () => Promise.resolve({ data: { id: 9, ruleVersion: 2 } }),
    stopIssueCoupon: (id, payload) => {
      stopped = { id, payload }
      return Promise.resolve({})
    }
  })
  const vm = Object.assign(component.data(), {
    $prompt: () => Promise.resolve({ value: '' }),
    $modal: { msgSuccess: () => {} },
    getList: () => Promise.resolve()
  })
  assert.equal(component.methods.canStopIssue.call(vm, { status: 4 }), false)
  assert.equal(component.methods.canStopIssue.call(vm, { status: 1 }), true)

  await component.methods.stopIssue.call(vm, { id: 9, status: 1 })

  assert.deepEqual(stopped, { id: 9, payload: { expectedVersion: 2, reason: null } })
})

test('编辑先预览影响：版本冲突或非法值时不能确认保存，失败后重读预览', async () => {
  const previews = []
  let updates = 0
  const component = loadCoupon({
    previewCouponRule: payload => {
      previews.push(payload)
      return Promise.resolve({ data: previews.length === 1
        ? { versionConflict: false, validationError: null, changes: [{ field: 'amount' }], newClaims: { ruleVersion: 3, issuable: true, remaining: 10 }, claimed: { total: 4, unused: 2, used: 1, expired: 1, legacyWithoutSnapshot: 0 } }
        : { versionConflict: true, expectedVersion: 2, currentVersion: 3, changes: [{ field: 'amount' }] } })
    },
    updateCoupon: () => {
      updates += 1
      return Promise.reject(new Error('409'))
    }
  })
  const vm = Object.assign(component.data(), {
    $modal: { msgSuccess: () => assert.fail('冲突不应提示成功') },
    getList: () => Promise.resolve()
  })
  Object.defineProperty(vm, 'canConfirmRuleChange', { get() { return component.computed.canConfirmRuleChange.call(vm) } })

  await component.methods.openImpactPreview.call(Object.assign(vm, {}), { id: 8, expectedVersion: 2, amount: 30 })
  assert.equal(vm.canConfirmRuleChange, true)
  vm.openImpactPreview = payload => component.methods.openImpactPreview.call(vm, payload)
  await component.methods.confirmRuleChange.call(vm)

  assert.equal(updates, 1)
  assert.equal(previews.length, 2, '保存失败后重读预览')
  assert.equal(vm.impact.versionConflict, true)
  assert.equal(vm.canConfirmRuleChange, false)
  assert.match(component.methods.claimedText.call(vm, { total: 4, unused: 2, used: 1, expired: 1, legacyWithoutSnapshot: 0 }), /保持领取时/)
})

test('新券保留零表示不限量，普通编辑不回写状态或跨范围残留商家归属', () => {
  const component = loadCoupon()
  const vm = Object.assign(component.data(), {
    form: Object.assign(component.data().form, {
      name: '夏日券',
      couponType: 0,
      publishCount: 0,
      scopeType: 0,
      merchantId: 99,
      status: 1
    })
  })

  const createPayload = component.methods.payloadForForm.call(vm)
  assert.equal(vm.form.publishCount, 0)
  assert.equal(createPayload.publishCount, 0)
  assert.equal(createPayload.status, 1)
  assert.equal(createPayload.merchantId, null)

  vm.form = Object.assign({}, vm.form, { id: 8, scopeType: 1, merchantId: 99, status: 1, ruleVersion: 6, publishCount: 50 })
  const editPayload = component.methods.payloadForForm.call(vm)
  assert.deepEqual(Object.keys(editPayload).sort(), ['amount', 'description', 'enableTime', 'endTime', 'expectedVersion', 'id', 'minPoint', 'name', 'publishCount', 'reason', 'startTime'])
  assert.equal(editPayload.expectedVersion, 6)
  assert.equal(Object.hasOwn(editPayload, 'status'), false)
  assert.equal(Object.hasOwn(editPayload, 'merchantId'), false)

  const source = read('src/views/business/coupon/index.vue')
  assert.match(source, /publishCount:\s*0/)
  assert.match(source, /:min="0"/)
  assert.match(source, /v-model="form\.scopeType"\s*:disabled="Boolean\(form\.id\)"/)
  assert.match(source, /v-model="form\.merchantId"[\s\S]*?:disabled="Boolean\(form\.id\)"/)
})

test('较早优惠券详情回包不会覆盖较晚编辑对象', async () => {
  const pending = {}
  const component = loadCoupon({
    getCoupon: id => new Promise(resolve => { pending[id] = resolve })
  })
  const vm = Object.assign(component.data(), {
    $nextTick: callback => callback(),
    $refs: { couponForm: { clearValidate: () => {} } },
    $modal: { msgError: () => {} }
  })

  const first = component.methods.handleEdit.call(vm, { id: 1 })
  const second = component.methods.handleEdit.call(vm, { id: 2 })
  pending[2]({ data: { id: 2, name: '后打开的券' } })
  await second
  pending[1]({ data: { id: 1, name: '先打开的券' } })
  await first

  assert.equal(vm.form.id, 2)
  assert.equal(vm.form.name, '后打开的券')
})

test('优惠券列表读取失败不会伪装成空目录，且可重新读取', async () => {
  const component = loadCoupon({ listCoupon: () => Promise.reject(new Error('offline')) })
  const vm = Object.assign(component.data(), {
    list: [{ id: 7 }],
    total: 1,
    query: { name: null, couponType: null, scopeType: null, merchantId: null, status: null, pageNum: 1, pageSize: 10 }
  })

  await component.methods.getList.call(vm)

  assert.deepEqual(vm.list, [])
  assert.equal(vm.total, 0)
  assert.equal(vm.listError, true)
  assert.equal(vm.loading, false)
  const source = read('src/views/business/coupon/index.vue')
  assert.match(source, /<cy-resource-state[\s\S]*?v-if="listError"[\s\S]*?@retry="getList"/)
})

// =====================================================================================
// menu 2050 迁到 2209「内容与供给」之后的两条契约
// 配套 ③ 迁移:chengyinhub-admin/sql/migration_menu_2050_coupon_to_supply_20260726.sql
// =====================================================================================

/**
 * 从 router 源码里抠出某个具名路由的 meta.activeMenu。
 * 抠不到直接 assert.fail —— 否则正则改坏时测试会静默变成空循环(假绿)。
 */
function activeMenuOf(routerSource, routeName) {
  const matched = new RegExp(`name:\\s*['"]${routeName}['"][\\s\\S]{0,400}?activeMenu:\\s*['"]([^'"]+)['"]`).exec(routerSource)
  assert.ok(matched, `router 里找不到 ${routeName} 的 meta.activeMenu —— 解析失败不算通过`)
  return matched[1]
}

test('后台菜单迁到 /supply/coupon 后，服务端同 path 动态路由夺不走静态 canonical 入口', () => {
  // menu 2050 的 parent 从 2214 改成 2209 之后，后端 getRouters 生成的路径也是 /supply/coupon，
  // 与 router/index.js 的静态路由同 path。这里复刻真实注册顺序：
  //   静态 dynamicRoutes → src/store/modules/permission.js:43  router.addRoutes(asyncRoutes)
  //   服务端菜单        → src/permission.js:36                 router.addRoutes(accessRoutes)
  // vue-router 3.4.9 dist/vue-router.common.js:1436-1439 对同 path 是「首个注册者胜出，
  // 后来者静默丢弃」，所以先注册的静态那份必须继续生效。
  const router = new VueRouter({ mode: 'abstract', routes: [] })
  router.addRoutes([{ path: '/supply/coupon', name: 'supplyCoupon' }])
  router.addRoutes([{ path: '/supply/coupon', name: 'serverMenuCoupon' }])

  const resolved = router.match('/supply/coupon?name=%E5%A4%8F%E6%97%A5&pageNum=2')

  assert.equal(resolved.name, 'supplyCoupon')
  assert.equal(resolved.path, '/supply/coupon')
  assert.deepEqual(resolved.query, { name: '夏日', pageNum: '2' })
})

test('优惠券两个入口的 activeMenu 必须解析到真实存在的规范路由，不能是死字符串', () => {
  // 侧栏高亮取的是 meta.activeMenu(src/layout/components/Sidebar/index.vue:38-46)。
  // 菜单换域后若 activeMenu 还指着旧 fullpath，它就变成一个谁也匹配不上的死字符串 ——
  // 页面照常打开、零报错，只是侧栏永远不高亮。这里不去比字面量，而是把 activeMenu
  // 真的丢进 vue-router 解析：解析不到 supplyCoupon 就是断链。
  const routerSource = read('src/router/index.js')
  const router = new VueRouter({ mode: 'abstract', routes: [] })
  router.addRoutes([{ path: '/supply/coupon', name: 'supplyCoupon' }])

  for (const routeName of ['supplyCoupon', 'couponLedger']) {
    const activeMenu = activeMenuOf(routerSource, routeName)
    const resolved = router.match(activeMenu)
    assert.equal(
      resolved.name, 'supplyCoupon',
      `${routeName} 的 activeMenu '${activeMenu}' 必须解析到 supplyCoupon（迁移后菜单 fullpath = /supply/coupon）`
    )
  }
})
