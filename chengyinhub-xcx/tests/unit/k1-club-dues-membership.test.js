'use strict'
// K1 club-dues:付费会员页只认服务端事实与资金开关,关着时如实显示「即将开放」,从不在端上推断能否收钱。
const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')

const vm = require('../../pages/club/membership/view-model.js')
const root = path.join(__dirname, '../..')
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8')

const overview = (extra) => Object.assign({
  clubMembershipEnabled: true,
  effectiveStatus: 'FREE',
  hasEntitlement: false,
  plans: [{ id: 9, name: '年度会员', priceFen: 9900, durationDays: 365, benefitsJson: '["活动优先报名"]', refundPolicy: 'MANUAL_REVIEW', status: 'ACTIVE' }],
  invoices: [],
  duesCollection: { state: 'SPLIT' },
}, extra || {})

test('资金开关关着:不能缴费,如实说即将开放', () => {
  const v = vm.shapeOverview(overview(), false, false)
  assert.equal(v.canPay, false)
  assert.equal(v.blockedText, '在线缴费即将开放')
  assert.equal(v.plans[0].priceText, '¥99')
  assert.deepEqual(v.plans[0].benefits, ['活动优先报名'])
})

test('开关开着且俱乐部收款就绪(SPLIT 或今天的 AVAILABLE)才能缴费', () => {
  assert.equal(vm.shapeOverview(overview(), true, false).canPay, true)
  assert.equal(vm.shapeOverview(overview({ duesCollection: { state: 'AVAILABLE' } }), true, false).canPay, true)
  assert.equal(vm.shapeOverview(overview({ clubMembershipEnabled: false }), true, false).canPay, false)
})

test('俱乐部没开通收款:成员被告知联系主理人,主理人被告知去开通', () => {
  const member = vm.shapeOverview(overview({ duesCollection: { state: 'NOT_BOUND' } }), true, false)
  assert.equal(member.canPay, false)
  assert.match(member.blockedText, /联系主理人/)
  const owner = vm.shapeCollection({ state: 'NOT_BOUND', nextStep: 'BIND_COLLECTION_ACCOUNT' }, true)
  assert.equal(owner.ready, false)
  assert.equal(owner.needsBinding, true)
  assert.match(owner.stateText, /开通收款账户/)
  assert.equal(vm.shapeCollection({ state: 'SPLIT' }, false).stateText, '在线收会费即将开放')
})

test('会员状态与账单文案不露机器码', () => {
  const v = vm.shapeOverview(overview({
    effectiveStatus: 'ACTIVE', hasEntitlement: true,
    term: { endsAt: '2027-01-02T00:00:00+08:00', benefitsSnapshot: '["专属徽章"]' },
    invoices: [{ id: 5, planNameSnapshot: '年度会员', amountFen: 9900, status: 'PAYING', version: 1 }],
  }), true, false)
  assert.equal(v.statusText, '会员有效')
  assert.equal(v.renewLabel, '续费')
  assert.equal(v.invoices[0].statusText, '待支付')
  assert.equal(v.invoices[0].resumable, true)
  assert.equal(vm.errorText('CLUB.DUES_COLLECTION_UNAVAILABLE', 'x'), '俱乐部会费暂时不能在线支付，请联系俱乐部主理人')
  assert.equal(vm.errorText('SOME_CODE', '兜底'), '兜底')
})

test('页面:注册在俱乐部分包,缴费前读资金开关 tickets,404 显示即将开放', () => {
  const app = JSON.parse(read('app.json'))
  const club = app.subPackages.find((p) => p.root === 'pages/club')
  assert.ok(club.pages.includes('membership/index'))
  const js = read('pages/club/membership/index.js')
  assert.match(js, /\/api\/app\/money-switch/)
  assert.match(js, /res\.data\.tickets === true/)
  assert.match(js, /closedFeature\(res\)\) \{ this\.setData\(\{ state: 'soon' \}\)/)
  assert.match(read('pages/club/membership/index.wxml'), /付费会员即将开放/)
  const detail = read('pages/club/detail/index.js')
  assert.match(detail, /\/pages\/club\/membership\/index\?manage=1&clubId=/)
})

test('主理人方案表单:校验后转成 plan/save 请求体,价格按分,宽限与逾期为 0', () => {
  assert.equal(vm.planRequest({ name: '', priceYuan: '9.9', durationDays: 30, benefitsText: 'a' }, 3, 'r').error, '方案名称需为 1-40 字')
  assert.match(vm.planRequest({ name: 'x', priceYuan: '0', durationDays: 30, benefitsText: 'a' }, 3, 'r').error, /价格/)
  assert.match(vm.planRequest({ name: 'x', priceYuan: '9.9', durationDays: 30, benefitsText: '' }, 3, 'r').error, /权益/)
  const built = vm.planRequest({ name: '月度会员', priceYuan: '9.9', durationDays: 30, benefitsText: '优先报名\n专属徽章\n', refundReview: true, active: true }, 3, 'cmp-1')
  assert.deepEqual(built.body, {
    clubId: 3, name: '月度会员', priceFen: 990, durationDays: 30, benefits: ['优先报名', '专属徽章'],
    graceDays: 0, pastDueDays: 0, exitPolicy: 'END_IMMEDIATELY', refundPolicy: 'MANUAL_REVIEW', status: 'ACTIVE', requestId: 'cmp-1',
  })
  const edit = vm.planRequest(vm.planForm({ id: 9, version: 2, name: '年度', priceFen: 9900, durationDays: 365, benefitsJson: '["a"]', status: 'INACTIVE' }), 3, 'r2')
  assert.equal(edit.body.id, 9)
  assert.equal(edit.body.version, 2)
  assert.equal(edit.body.status, 'INACTIVE')
  assert.equal(edit.body.refundPolicy, 'NON_REFUNDABLE')
})
