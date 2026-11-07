'use strict'
// 第十二轮 k6-merchant-screens:p2 N-1(活动验票行打开详情)、p1 N-1(积分抵扣行按报价收起)、
// p2 N-4(没有合作结算时不画假零)、p2 N-5(合作页不再许诺分成/结算)。旧代码上第 1、2、3、4 例都会红。

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const ROOT = path.resolve(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')

function loadOrderDetail() {
  const jsPath = 'pages/merchant/ledger/order-detail/index.js'
  const requests = []
  let definition
  const sandbox = {
    console,
    getApp: () => ({ globalData: { statusBarHeight: 20, navBarHeight: 44 }, sendRequest(o) { requests.push(o) } }),
    getCurrentPages: () => [{}, {}],
    Page(value) { definition = value },
    require(id) {
      if (id.includes('merchant-theme')) return { merchantPageShow() {}, merchantPageRestore() {} }
      if (id.includes('page-motion')) return { countMoney() {}, enter() {} }
      return require(path.resolve(path.dirname(path.join(ROOT, jsPath)), id))
    },
    wx: { navigateBack() {}, reLaunch() {}, navigateTo() {}, stopPullDownRefresh() {} },
  }
  vm.runInNewContext(read(jsPath), sandbox, { filename: jsPath })
  const page = Object.assign({}, definition)
  page.data = JSON.parse(JSON.stringify(definition.data))
  page.setData = function (patch, cb) { Object.assign(this.data, patch); if (cb) cb() }
  return { page, requests }
}

test('p2 N-1:核销与退款里的活动验票行打开核销详情,状态按已入个人账户', () => {
  const { page, requests } = loadOrderDetail()
  page.onLoad({ recordType: 'activity_ticket', recordId: '80' })
  assert.equal(page.data.loadState, 'loading', '不能再落到「缺少核销记录信息」')
  assert.equal(requests.length, 1)
  assert.deepEqual(JSON.parse(requests[0].data), { recordType: 'activity_ticket', recordId: '80' })
  requests[0].success({ code: 200, data: {
    recordType: 'activity_ticket', recordId: '80', recordKey: 'activity_ticket:80', fulfillmentState: 'ACTIVE',
    settlementState: 'SETTLED', settlementRoute: 'ACTIVITY', displayState: 'SETTLED', destination: 'PERSONAL_ACCOUNT',
    settlementAmount: '37.83', topicName: '周六手冲咖啡体验', chapterName: '单人体验',
  } })
  assert.equal(page.data.loadState, 'ready')
  assert.equal(page.data.detail.amountDisplay, '¥37.83')
  assert.equal(page.data.detail.stateText, '已入个人账户')
  const pending = loadOrderDetail()
  pending.page.onLoad({ recordType: 'activity_ticket', recordId: '81' })
  pending.requests[0].success({ code: 200, data: {
    recordType: 'activity_ticket', recordId: '81', recordKey: 'activity_ticket:81', fulfillmentState: 'ACTIVE',
    settlementState: 'PENDING', settlementRoute: 'ACTIVITY', displayState: 'PENDING_SETTLEMENT', settlementAmount: '37.83',
  } })
  assert.equal(pending.page.data.detail.stateText, '待结算', '活动票不随主题结算')
  const other = loadOrderDetail()
  other.page.onLoad({ recordType: 'aftercare', recordId: '3' })
  assert.equal(other.page.data.loadState, 'missing-param', '其它类型仍按缺参处理')
})

test('p1 N-1:报价说这单不能抵积分(自由探索/可抵 0)就收起积分抵扣行', () => {
  const js = read('pages/activity/baoming/baoming.js')
  assert.match(js, /pointsUsable: res\.data\.pointsUsable === false \? false/)
  const wxml = read('pages/activity/baoming/baoming.wxml')
  assert.match(wxml, /wx:if="\{\{pointsUsable !== false && selectedTicket\.price > 0 && userInfo\.point != 0\}\}"/)
})

test('p2 N-4:没有合作结算时分润页只给空状态,不画 ¥0.00、余额与灰按钮', () => {
  const wxml = read('subpackageA/components/scene-merchant-profit/index.wxml')
  const guarded = wxml.match(/<block wx:if="\{\{topics\.length\}\}">([\s\S]*?)<\/block>/)
  assert.ok(guarded, '指标/余额/提现整组必须挂在 topics.length 下')
  assert.match(guarded[1], /<cy-stat-card/)
  assert.match(guarded[1], /<cy-funds-stages/)
  assert.match(guarded[1], /bind:tap="goWithdraw"/)
  assert.doesNotMatch(wxml, />还没有已入账分润</)
  assert.match(wxml, /wx:else fill title="还没有合作结算"/)
})

test('p2 N-5:引流型合作不再出现「分成」「按核销人数结算」说明句', () => {
  assert.doesNotMatch(read('pages/topic/components/project-join/index.wxml'), /这一屏没有节点可列/)
  assert.doesNotMatch(read('pages/coop/invite-detail/index.wxml'), /按核销人数结算/)
})
