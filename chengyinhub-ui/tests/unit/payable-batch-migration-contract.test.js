const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8')
}

function loadPayableBatch(dependencies) {
  const source = read('src/views/business/finance/payable-batch/index.vue')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/import\s*\{[\s\S]*?\}\s*from '@\/api\/business\/payablebatch'/, 'const { listPayableBatch, generatePayableBatch, confirmPayableBatch, markPayableBatchPaid, markPayableBatchInvoice, markPayableBatchInvoiceRed } = dependencies;')
    .replace(/^import (Cy\w+|ClubDirectReceivables) from .+$/gm, 'const $1 = {};')
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))(dependencies)
}

test('商家应付月结以原列表权限注册唯一的 finance/payable-batch 入口', () => {
  const router = read('src/router/index.js')

  assert.match(router, /path:\s*['"]\/finance\/payable-batch['"][\s\S]*?permissions:\s*\[['"]business:payablebatch:list['"]\][\s\S]*?name:\s*['"]financePayableBatch['"]/)
  assert.match(router, /component:\s*\(\)\s*=>\s*import\(['"]@\/views\/business\/finance\/payable-batch\/index['"]\)/)
})

test('旧应付月结入口只兼容转发，不再读取或改变月结状态机', () => {
  const source = read('src/views/business/payablebatch/index.vue')

  assert.match(source, /\$router\.replace\(\{\s*name:\s*['"]financePayableBatch['"]/)
  assert.doesNotMatch(source, /listPayableBatch|generatePayableBatch|confirmPayableBatch|markPayableBatchPaid/)
})

test('规范月结页保留月末默认上月规则，并把读取失败显式展示为错误状态', async () => {
  const component = loadPayableBatch({
    listPayableBatch: () => Promise.reject(new Error('network')),
    generatePayableBatch: () => Promise.resolve({}),
    confirmPayableBatch: () => Promise.resolve({}),
    markPayableBatchPaid: () => Promise.resolve({}),
    markPayableBatchInvoice: () => Promise.resolve({}),
    markPayableBatchInvoiceRed: () => Promise.resolve({})
  })
  const vm = Object.assign(component.data(), { parseTime: () => '2026-07-23 10:00' })
  vm.appliedPeriodYm = '2026-06'

  assert.equal(component.methods.prevMonth.call(vm, new Date(2026, 2, 31)), '2026-02')
  await component.methods.getList.call(vm)

  assert.equal(vm.batchError, true)
  assert.deepEqual(vm.batchList, [])
  assert.equal(vm.loading, false)
})

test('月结空态不鼓励生成当月批次', () => {
  const source = read('src/views/business/finance/payable-batch/index.vue')

  assert.doesNotMatch(source, /确认本月已有应付发生/)
  assert.match(source, /该已结束结算期已有应付发生/)
})
