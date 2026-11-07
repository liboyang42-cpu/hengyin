// 第十二轮 k5(2026-11-05 业主):俱乐部场次按核销付费的商家费,从主理人份额里出。
// 俱乐部结算的场次行多一行「商家核销费」(服务端格式化好的金额,没有就不出);商家台账的场次核销费可点开详情。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.resolve(__dirname, '../..')
const PAGE = path.join(root, 'pages/club/settlement/index.js')

function mount() {
  let config
  const requests = []
  const app = { globalData: { user_id: 990005, role: 'member', user_type: 1 },
    getUserID() { return this.globalData.user_id }, sendRequest(options) { requests.push(options) } }
  global.getApp = () => app
  global.Page = value => { config = value }
  global.getCurrentPages = () => [{}, {}]
  global.wx = { showToast() {}, stopPullDownRefresh() {}, navigateBack() {}, switchTab() {}, navigateTo() {}, showModal() {} }
  delete require.cache[require.resolve(PAGE)]
  require(PAGE)
  const vm = Object.assign({}, config, { data: Object.assign({}, config.data), setData(patch) { Object.assign(this.data, patch) } })
  return { vm, requests }
}

const SUMMARY = { settledAmountText: '¥0.00', settledAmountStatus: 'verified', unverifiedSettledCount: 0,
  pendingAdjustment: null, topics: [], isOwner: true }
const SESSION = { activityId: 9, name: '外滩钟声 · 周六场', dateText: '11月4日', grossText: '¥ 136.50',
  feeText: '¥ 4.10', feeRateText: '3%', netText: '¥ 108.40', payeeText: '已入你的账户', merchantFeeText: '¥ 24.00' }

test('场次行带商家核销费:销售额 = 到账 + 平台服务费 + 商家核销费,金额原样显示', () => {
  const { vm, requests } = mount()
  vm.onLoad({ clubId: '990004' })
  requests[0].success({ code: 200, data: { ...SUMMARY, sessions: [SESSION] } })
  assert.equal(vm.data.state, 'ready')
  const row = vm.data.summary.sessions[0]
  assert.equal(row.merchantFeeText, '¥ 24.00')
  assert.equal(row.netText, '¥ 108.40')
  const wxml = fs.readFileSync(path.join(root, 'pages/club/settlement/index.wxml'), 'utf8')
  assert.match(wxml, /<text wx:if="\{\{item\.merchantFeeText\}\}" class="topic-row-meta-line">商家核销费 \{\{item\.merchantFeeText\}\}<\/text>/)
})

test('没有商家费的场次不出这一行;字段类型不对整页拒收', () => {
  const { vm, requests } = mount()
  vm.onLoad({ clubId: '990004' })
  requests[0].success({ code: 200, data: { ...SUMMARY, sessions: [{ ...SESSION, merchantFeeText: null }] } })
  assert.equal(vm.data.summary.sessions[0].merchantFeeText, '')
  const bad = mount()
  bad.vm.onLoad({ clubId: '990004' })
  bad.requests[0].success({ code: 200, data: { ...SUMMARY, sessions: [{ ...SESSION, merchantFeeText: 24 }] } })
  assert.equal(bad.vm.data.state, 'error')
})

test('商家台账的场次核销费(session_fee)能打开核销详情', () => {
  const code = fs.readFileSync(path.join(root, 'pages/merchant/ledger/order-detail/index.js'), 'utf8')
  assert.match(code, /const RECORD_TYPES = \[[^\]]*'session_fee'/)
})
