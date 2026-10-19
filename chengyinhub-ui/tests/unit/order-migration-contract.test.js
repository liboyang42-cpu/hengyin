const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8')
}

function loadLegacyOrderList() {
  const source = read('src/views/business/order/index.vue')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import[^\n]*\n/gm, '')
  const runtime = [
    'const mapGetters = () => ({});',
    'const dateUtil = { getTimeShort2: () => [] };',
    'const dateFormat = () => "";',
    'const parseTime = () => {};',
    'const CyCopyValue = {};',
    'const CyPageHeader = {};'
  ].join('\n')
  return new Function(runtime + '\n' + script.replace(/export\s+default\s*/, 'return '))()
}

function loadLegacyOrderDetail(hasListPermission = true) {
  const source = read('src/views/business/order/detail.vue')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import[^\n]*\n/gm, '')
  const runtime = `const auth = { hasPermi: permission => permission === 'business:order:list' && ${hasListPermission} };`
  return new Function(runtime + '\n' + script.replace(/export\s+default\s*/, 'return '))()
}

function loadOrderWorkbench(dependencies) {
  const source = read('src/views/business/finance/order/index.vue')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ listOrder, updateOrder \} from '@\/api\/business\/order'$/m, 'const { listOrder, updateOrder } = dependencies;')
    .replace(/^import auth from '@\/plugins\/auth'$/m, 'const auth = dependencies.auth;')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {};')
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))(dependencies)
}

function loadOrderDetail(dependencies) {
  const source = read('src/views/business/finance/order/detail.vue')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ getOrder \} from '@\/api\/business\/order'$/m, 'const { getOrder } = dependencies;')
    .replace(/^import \{ getOrderPaymentFacts, queryOrderPaymentFacts \} from '@\/api\/business\/orderPaymentFacts'$/m, 'const { getOrderPaymentFacts, queryOrderPaymentFacts } = Object.assign({ getOrderPaymentFacts: () => Promise.resolve({ data: null }), queryOrderPaymentFacts: () => Promise.resolve({ data: null }) }, dependencies);')
    .replace(/^import auth from '@\/plugins\/auth'$/m, 'const auth = dependencies.auth;')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {};')
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))(dependencies)
}

test('订单列表与详情收归 finance/order，旧入口继续转发到规范地址', () => {
  const router = read('src/router/index.js')
  const list = loadLegacyOrderList()
  const detail = loadLegacyOrderDetail()
  const queryOnlyDetail = loadLegacyOrderDetail(false)
  const listTargets = []
  const detailTargets = []
  const queryOnlyTargets = []

  assert.match(router, /path:\s*['"]\/finance\/order['"][\s\S]*?permissions:\s*\[['"]business:order:list['"]\][\s\S]*?name:\s*['"]financeOrder['"]/);
  assert.match(router, /path:\s*['"]\/finance\/order\/:id\(\\\\d\+\)['"][\s\S]*?permissions:\s*\[['"]business:order:query['"]\][\s\S]*?name:\s*['"]financeOrderDetail['"]/);

  list.methods.redirectToCanonical.call({
    $router: { replace: target => listTargets.push(target) }
  }, { pageNum: '3', pageSize: '25', status: '1', orderSn: 'CY-100', memberId: '88' })
  detail.methods.redirectToCanonical.call({
    $router: { replace: target => detailTargets.push(target) }
  }, { id: '42', from: 'legacy' })
  queryOnlyDetail.methods.redirectToCanonical.call({
    $router: { replace: target => queryOnlyTargets.push(target) }
  }, { id: 'not-an-order' })

  assert.deepEqual(listTargets, [{
    name: 'financeOrder',
    query: { pageNum: '3', pageSize: '25', status: '1', orderSn: 'CY-100', memberId: '88' }
  }])
  assert.deepEqual(detailTargets, [{
    name: 'financeOrderDetail',
    params: { id: '42' },
    query: { from: 'legacy' }
  }])
  assert.deepEqual(queryOnlyTargets, ['/404'])
})

test('规范订单列表只接住服务端实际支持的筛选与分页 query，并拒绝无列表权限的读取', () => {
  const requests = []
  const component = loadOrderWorkbench({
    listOrder: query => new Promise(resolve => requests.push({ query, resolve })),
    updateOrder: () => Promise.resolve(),
    auth: { hasPermi: permission => permission === 'business:order:list', hasPermiAnd: () => false }
  })
  const replaceTargets = []
  const vm = Object.assign(component.data(), {
    $router: { replace: target => replaceTargets.push(target) },
    $route: { query: {} },
    $modal: { msgSuccess: () => {} }
  })
  vm.getList = component.methods.getList.bind(vm)

  component.methods.loadRoute.call(vm, {
    pageNum: '3',
    pageSize: '25',
    status: '1',
    orderSn: 'CY-100',
    memberId: '88',
    startTime: '2026-07-01 00:00:00',
    endTime: '2026-07-02 23:59:59'
  })

  assert.deepEqual(requests[0].query, {
    pageNum: 3,
    pageSize: 25,
    status: 1,
    orderSn: 'CY-100',
    memberId: '88'
  })
  assert.deepEqual(replaceTargets, [])

  const source = read('src/views/business/finance/order/index.vue')
  assert.match(source, /<template #more>[\s\S]*?label="用户 ID"/)
  assert.doesNotMatch(source, /el-date-picker/)

  const deniedRequests = []
  const deniedComponent = loadOrderWorkbench({
    listOrder: query => new Promise(resolve => deniedRequests.push({ query, resolve })),
    updateOrder: () => Promise.resolve(),
    auth: { hasPermi: () => false, hasPermiAnd: () => false }
  })
  const deniedTargets = []
  const deniedVm = Object.assign(deniedComponent.data(), {
    $router: { replace: target => deniedTargets.push(target) },
    $route: { query: {} },
    $modal: { msgSuccess: () => {} }
  })
  deniedVm.getList = deniedComponent.methods.getList.bind(deniedVm)
  deniedComponent.methods.loadRoute.call(deniedVm, { orderSn: 'CY-403' })

  assert.deepEqual(deniedTargets, ['/401'])
  assert.equal(deniedRequests.length, 0)
})

test('规范订单详情从路径参数读取对象，并带回用户进入详情前的列表上下文', async () => {
  const requests = []
  const component = loadOrderDetail({
    getOrder: id => new Promise(resolve => requests.push({ id, resolve })),
    auth: { hasPermi: permission => permission === 'business:order:list' }
  })
  const routes = []
  const vm = Object.assign(component.data(), {
    $router: { push: target => routes.push(target) },
    $route: { query: { pageNum: '3', pageSize: '25', status: '1', orderSn: 'CY-42', memberId: '88' } }
  })
  vm.loadOrder = component.methods.loadOrder.bind(vm)

  component.methods.loadOrder.call(vm, '42')
  assert.equal(requests[0].id, '42')

  requests[0].resolve({ data: { id: 42, orderSn: 'CY-42', products: [{ id: 7 }] } })
  await Promise.resolve()
  await Promise.resolve()
  assert.equal(vm.order.id, 42)
  assert.deepEqual(vm.products, [{ id: 7 }])

  component.methods.goOrderList.call(vm)
  assert.deepEqual(routes, [{
    name: 'financeOrder',
    query: { pageNum: '3', pageSize: '25', status: '1', orderSn: 'CY-42', memberId: '88' }
  }])
})

test('规范订单详情聚合服务端回读的售后退款状态与订单项关联，不猜测跨域退款单关联', () => {
  const component = loadOrderDetail({ getOrder: () => Promise.resolve({ data: {} }) })
  const vm = Object.assign(component.data(), {
    order: { aftersaleStatus: 3, createTime: '2026-07-01 10:00:00', paymentTime: '2026-07-01 10:05:00' },
    products: [
      { id: 7, aftersaleStatus: 0 },
      { id: 8, aftersaleOrderId: 99, aftersaleStatus: 2 }
    ]
  })
  vm.aftersaleLabel = component.methods.aftersaleLabel.bind(vm)

  assert.deepEqual(component.computed.refundItems.call(vm), [
    { key: 'aftersaleStatus', label: '订单级售后状态', value: '退款中' },
    { key: 'aftersaleItemCount', label: '售后记录订单项', value: '1 个' }
  ])
  assert.deepEqual(component.computed.orderTimelineItems.call(vm), [
    { key: 'createTime', label: '下单时间', value: '2026-07-01 10:00:00' },
    { key: 'paymentTime', label: '支付时间', value: '2026-07-01 10:05:00' }
  ])
  assert.equal(component.methods.itemAftersaleLabel({ aftersaleStatus: 0 }), '未申请售后')
  assert.equal(component.methods.itemAftersaleLabel({ aftersaleOrderId: 99, aftersaleStatus: 2 }), '关联售后单 #99')
  assert.equal(component.methods.itemAftersaleLabel({ aftersaleStatus: 2 }), '已记录售后状态')
  assert.match(read('src/views/business/finance/order/detail.vue'), /title="退款与售后"/)
  assert.match(read('src/views/business/finance/order/detail.vue'), /title="订单时间"/)
  assert.doesNotMatch(read('src/views/business/finance/order/detail.vue'), /listRefund/)
})

test('规范订单详情只展示可信订单字段，不猜测订单来源或保留物流残留', () => {
  const component = loadOrderDetail({ getOrder: () => Promise.resolve({ data: {} }) })
  const source = read('src/views/business/finance/order/detail.vue')

  assert.equal(component.methods.orderSourceLabel, undefined)
  assert.doesNotMatch(source, /订单来源|orderSourceLabel|order\.payType|订单类型/)
  assert.doesNotMatch(source, /shippingItems|receiverCountry|receiverCity|receiverResidentialType|receiverHouseNumber|receiverDoorbell/)
  assert.doesNotMatch(source, /deliveryTime|receiveTime|deliveryCompany|deliverySn/)
})

test('订单详情不把只有详情查询权限的用户导向未注册的订单列表', () => {
  const component = loadOrderDetail({
    getOrder: () => Promise.resolve({ data: {} }),
    auth: { hasPermi: permission => permission === 'business:order:query' }
  })
  const routes = []
  const vm = Object.assign(component.data(), {
    $route: { query: { pageNum: '3' } },
    $router: { push: target => routes.push(target) }
  })

  component.methods.goOrderList.call(vm)
  assert.deepEqual(routes, [])
  assert.match(read('src/views/business/finance/order/detail.vue'), /v-if="canList"/)
})

test('规范订单页沿用服务端 OMS 状态枚举，不把待评价和终态错标', () => {
  const list = loadOrderWorkbench({
    listOrder: () => Promise.resolve({ rows: [], total: 0 }),
    updateOrder: () => Promise.resolve(),
    auth: { hasPermi: () => true, hasPermiAnd: () => true }
  })
  const detail = loadOrderDetail({ getOrder: () => Promise.resolve({ data: {} }) })

  assert.deepEqual(list.data().statusOptions.map(item => [item.value, item.label]), [
    [0, '待付款'],
    [1, '待发货'],
    [2, '已发货'],
    [3, '待评价'],
    [4, '已完成'],
    [5, '已关闭'],
    [6, '无效订单']
  ])
  assert.equal(detail.methods.statusLabel(3), '待评价')
  assert.equal(detail.methods.statusLabel(4), '已完成')
  assert.equal(detail.methods.statusLabel(6), '无效订单')
  assert.equal(list.methods.hasAftersale(2), true)
  assert.equal(list.methods.aftersaleLabel(4), '退款成功')
  assert.equal(list.methods.hasAftersale(1), false)
})

test('规范订单详情提供可复制的订单编号，支持跨页调证时恢复对象上下文', () => {
  const source = read('src/views/business/finance/order/detail.vue')

  assert.match(source, /import CyCopyValue from '@\/components\/CyCopyValue'/)
  assert.match(source, /components:\s*\{\s*CyCopyValue, CyDetailGrid, CyPageHeader, CyResourceState\s*\}/)
  assert.match(source, /<cy-detail-grid title="订单事实"[\s\S]*?<template #orderSn>[\s\S]*?<cy-copy-value :value="order\.orderSn" label="订单编号" \/>/)
})

test('订单详情仅向具备查询权限的人开放，并保留当前列表筛选与分页上下文', () => {
  const component = loadOrderWorkbench({
    listOrder: () => Promise.resolve({ rows: [], total: 0 }),
    updateOrder: () => Promise.resolve(),
    auth: { hasPermi: permission => permission === 'business:order:list', hasPermiAnd: () => false }
  })
  const routes = []
  const vm = Object.assign(component.data(), {
    $router: { push: target => routes.push(target) },
    queryParams: { pageNum: 3, pageSize: 25, status: 1, orderSn: 'CY-42', memberId: '88' }
  })

  component.methods.goDetail.call(vm, { id: 42 })
  assert.deepEqual(routes, [])

  const permittedComponent = loadOrderWorkbench({
    listOrder: () => Promise.resolve({ rows: [], total: 0 }),
    updateOrder: () => Promise.resolve(),
    auth: { hasPermi: permission => ['business:order:list', 'business:order:query'].includes(permission), hasPermiAnd: () => false }
  })
  const permittedRoutes = []
  const permittedVm = Object.assign(permittedComponent.data(), {
    $router: { push: target => permittedRoutes.push(target) },
    queryParams: { pageNum: 3, pageSize: 25, status: 1, orderSn: 'CY-42', memberId: '88' }
  })
  permittedVm.listRouteQuery = permittedComponent.methods.listRouteQuery.bind(permittedVm)
  permittedComponent.methods.goDetail.call(permittedVm, { id: 42 })

  assert.deepEqual(permittedRoutes, [{
    name: 'financeOrderDetail',
    params: { id: '42' },
    query: { pageNum: '3', pageSize: '25', status: '1', orderSn: 'CY-42', memberId: '88' }
  }])

  const source = read('src/views/business/finance/order/index.vue')
  assert.match(source, /<cy-entity-link\s+v-if="canQuery"/)
  assert.match(source, /<div v-else class="order-identity">/)
  assert.doesNotMatch(source, /deliveryTime/)
})

test('订单列表与详情读取失败明确进入可重试错误态，不伪装成空数据', async () => {
  const list = loadOrderWorkbench({
    listOrder: () => Promise.reject(new Error('network failed')),
    updateOrder: () => Promise.resolve(),
    auth: { hasPermi: () => true, hasPermiAnd: () => true }
  })
  const listVm = Object.assign(list.data(), {})
  await list.methods.getList.call(listVm)
  assert.equal(listVm.listError, true)
  assert.deepEqual(listVm.orderList, [])
  assert.equal(listVm.total, 0)

  const detail = loadOrderDetail({
    getOrder: () => Promise.reject(new Error('network failed'))
  })
  const detailVm = Object.assign(detail.data(), {
    $modal: { msgError: () => {} }
  })
  await detail.methods.loadOrder.call(detailVm, '42')
  assert.equal(detailVm.detailError, true)
  assert.equal(detailVm.loaded, false)

  const listSource = read('src/views/business/finance/order/index.vue')
  const detailSource = read('src/views/business/finance/order/detail.vue')
  assert.match(listSource, /<cy-resource-state[\s\S]*?v-if="listError"[\s\S]*?@retry="getList"/)
  assert.match(detailSource, /<cy-resource-state[\s\S]*?v-if="detailError"[\s\S]*?@retry="loadOrder\(\$route\.params\.id\)"/)
})

test('平台备注只提交订单 id 与备注，不借迁移页写入履约或收货字段', async () => {
  const writes = []
  const component = loadOrderWorkbench({
    listOrder: () => Promise.resolve({ rows: [], total: 0 }),
    updateOrder: data => {
      writes.push(data)
      return Promise.resolve()
    },
    auth: { hasPermi: () => true, hasPermiAnd: () => true }
  })
  const vm = Object.assign(component.data(), {
    $modal: { msgSuccess: () => {} },
    getList: () => Promise.resolve()
  })

  component.methods.openNote.call(vm, { id: 9, orderSn: 'CY-9', merchantNote: '旧备注' })
  vm.noteForm.merchantNote = '  新备注  '
  component.methods.submitNote.call(vm)
  await Promise.resolve()
  await Promise.resolve()

  assert.deepEqual(writes, [{ id: 9, merchantNote: '新备注' }])
})

test('平台备注保存失败保留草稿与弹窗，提示可恢复的失败原因', async () => {
  const messages = []
  const component = loadOrderWorkbench({
    listOrder: () => Promise.resolve({ rows: [], total: 0 }),
    updateOrder: () => Promise.reject(new Error('forbidden')),
    auth: { hasPermi: () => true, hasPermiAnd: () => true }
  })
  const vm = Object.assign(component.data(), {
    $modal: { msgSuccess: () => {}, msgError: message => messages.push(message) },
    getList: () => Promise.resolve()
  })

  component.methods.openNote.call(vm, { id: 9, orderSn: 'CY-9', merchantNote: '旧备注' })
  vm.noteForm.merchantNote = '待跟进的退款事实'
  await component.methods.submitNote.call(vm)

  assert.equal(vm.noteOpen, true)
  assert.equal(vm.noteForm.merchantNote, '待跟进的退款事实')
  assert.equal(vm.noteSubmitting, false)
  assert.deepEqual(messages, ['平台备注保存失败，请检查权限或网络后重试'])
})
