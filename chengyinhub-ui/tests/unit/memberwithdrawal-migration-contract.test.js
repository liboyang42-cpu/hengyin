const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8')
}

function loadPage() {
  const source = read('src/views/business/memberwithdrawal/index.vue')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ listMemberwithdrawal, reviewMemberwithdrawal, getMemberwithdrawalPayout \} from '@\/api\/business\/memberwithdrawal'$/m, 'const { listMemberwithdrawal, reviewMemberwithdrawal, getMemberwithdrawalPayout } = dependencies;')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {};')
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))({
    listMemberwithdrawal: () => Promise.resolve({ rows: [], total: 0 }),
    reviewMemberwithdrawal: () => Promise.resolve({}),
    getMemberwithdrawalPayout: () => Promise.resolve({ data: {} })
  })
}

test('银行卡提现以原列表权限注册唯一的 finance/withdraw/bank 入口', () => {
  const router = read('src/router/index.js')

  const canonicalRoute = /path:\s*['"]\/finance\/withdraw\/bank['"][\s\S]*?permissions:\s*\[['"]business:memberwithdrawal:list['"]\][\s\S]*?name:\s*['"]financeBankWithdraw['"]/;
  assert.match(router, canonicalRoute)
  assert.match(router, /component:\s*\(\)\s*=>\s*import\(['"]@\/views\/business\/memberwithdrawal\/index['"]\)/)
})

test('旧银行卡提现入口只兼容转发到规范路由，不在旧地址读取队列', () => {
  const component = loadPage()
  const targets = []
  let reads = 0
  const vm = Object.assign(component.data(), component.methods, {
    $route: { path: '/business/memberwithdrawal', query: { memberId: '42', status: '0', realname: '不应进入规范地址', bankName: '不应进入规范地址', bankAccount: '不应进入规范地址', mobilephone: '不应进入规范地址' } },
    $router: { replace: target => targets.push(target) },
    getList: () => { reads += 1 }
  })

  component.methods.ensureCanonicalRoute.call(vm)

  assert.deepEqual(targets, [{
    name: 'financeBankWithdraw',
    query: { memberId: '42', status: '0' }
  }])
  assert.equal(reads, 0)
})

test('规范银行卡提现页只走审核和按需 PII 读取合同', () => {
  const source = read('src/views/business/memberwithdrawal/index.vue')

  assert.match(source, /import \{ listMemberwithdrawal, reviewMemberwithdrawal, getMemberwithdrawalPayout \} from '@\/api\/business\/memberwithdrawal'/)
  assert.match(source, /v-if="scope\.row\.status === 0"[\s\S]*?审核|审核[\s\S]*?v-if="scope\.row\.status === 0"/)
  assert.match(source, /v-hasPermi="\['business:memberwithdrawal:audit'\]"/)
  assert.match(source, /v-hasPermi="\['business:memberwithdrawal:viewpii'\]"/)
  assert.match(source, /reviewMemberwithdrawal\(\{\s*id: this\.reviewForm\.id,\s*status: this\.reviewForm\.status,\s*reviewReason: this\.normalizedReviewReason\(\)/)
  assert.match(source, /getMemberwithdrawalPayout\(row\.id\)/)
  assert.doesNotMatch(source, /addMemberwithdrawal|updateMemberwithdrawal|delMemberwithdrawal/)
})

test('银行卡提现列表只展示掩码，路由 query 读取只白名单业务定位字段', () => {
  const source = read('src/views/business/memberwithdrawal/index.vue')
  const listTemplate = source.split('<el-dialog')[0]

  assert.match(source, /row\.realnameMask/)
  assert.match(source, /row\.bankNameMask/)
  assert.match(source, /row\.bankAccountMask/)
  assert.match(source, /row\.mobilephoneMask/)
  assert.doesNotMatch(source, /<el-table-column[^>]+prop="realname"/)
  assert.doesNotMatch(source, /<el-table-column[^>]+prop="bankAccount"/)
  assert.match(source, /memberId: this\.positiveIntegerOrNull\(this\.\$route\.query\.memberId\)/)
  assert.match(source, /status: this\.withdrawalStatusOrNull\(this\.\$route\.query\.status\)/)
  assert.doesNotMatch(source, /realname:\s*this\.\$route\.query\.realname/)
  assert.doesNotMatch(source, /bankAccount:\s*this\.\$route\.query\.bankAccount/)
  assert.doesNotMatch(listTemplate, /scope\.row\.realname(?!Mask)/)
  assert.doesNotMatch(listTemplate, /scope\.row\.bankAccount(?!Mask)/)
  assert.doesNotMatch(listTemplate, /scope\.row\.mobilephone(?!Mask)/)
  assert.match(source, /if \(value == null \|\| value === ''\) return '—'/)
})

test('清空审核状态不会被转换为待审核筛选', () => {
  const component = loadPage()
  const vm = Object.assign(component.data(), component.methods)

  assert.equal(vm.toListQuery().status, null)
  vm.filters.status = ''
  assert.equal(vm.toListQuery().status, null)
})

test('驳回状态明确说明余额已退回，审核提交中不能伪装取消', () => {
  const source = read('src/views/business/memberwithdrawal/index.vue')
  const component = loadPage()
  const vm = Object.assign(component.data(), component.methods)
  let closeCount = 0

  assert.match(source, /:show-close="!reviewSaving"/)
  assert.match(source, /:close-on-press-escape="!reviewSaving"/)
  assert.match(source, /:before-close="beforeReviewClose"/)
  assert.equal(vm.statusLabel(2), '审核驳回（余额已退回）')
  vm.reviewSaving = true
  vm.beforeReviewClose(() => { closeCount += 1 })
  assert.equal(closeCount, 0)
  vm.reviewSaving = false
  vm.beforeReviewClose(() => { closeCount += 1 })
  assert.equal(closeCount, 1)
})

test('PII 弹层和列表均拒绝较早请求覆盖当前状态', () => {
  const source = read('src/views/business/memberwithdrawal/index.vue')

  assert.match(source, /const requestId = \+\+this\.listRequestId/)
  assert.match(source, /if \(requestId !== this\.listRequestId\) return/)
  assert.match(source, /const requestId = \+\+this\.payoutRequestId/)
  assert.match(source, /if \(requestId !== this\.payoutRequestId\) return/)
  assert.match(source, /本次查看已记入审计日志/)
})
