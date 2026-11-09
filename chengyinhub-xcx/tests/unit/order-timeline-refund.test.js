'use strict'
// 2026-11-03 pay-gate Round 3D:退款后的订单进度。退款单 payment_status=4(已退款),
// 旧时间线只把 2 当「已付」,于是退款单显示「订单已创建 → 等待支付」,正在退款 / 退款成功两行永远走不到。
const { beforeEach, test } = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')
const { flattenComponentToPage } = require('../helpers/component-as-page.js')

const JS = path.resolve(__dirname, '../../subpackageMember/components/cy/scene-member-order-detail/index.js')

let sandbox
beforeEach(() => {
  sandbox = { requests: [] }
  global.getApp = () => ({
    globalData: {}, tips() {}, sendRequest: o => sandbox.requests.push(o),
    getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
    getImgUrl: src => src || '',
  })
  global.Component = config => { sandbox.config = flattenComponentToPage(config) }
  global.wx = { showToast() {}, hideLoading() {}, showLoading() {}, navigateTo() {}, navigateBack() {} }
})

function timeline(row) {
  delete require.cache[require.resolve(JS)]
  require(JS)
  const vm = Object.assign({}, sandbox.config, { setData(patch, cb) { Object.assign(this.data, patch); if (cb) cb() } })
  vm.data = Object.assign({}, sandbox.config.data)
  sandbox.requests = []; vm.data.orderId = String(row.id); vm.onLoad()
  sandbox.requests.find(r => r.url === '/api/registration/info').success({ code: 200, data: row })
  return (vm.data.orderTimeline || []).map(r => r.label)
}

const base = {
  id: 80, ownerType: 2, payableAmount: 39.99, wechatPaymentAmount: 39.99,
  createTime: '2026-11-03 11:39:00', paymentTime: '2026-11-03 11:39:05',
}

test('已付未退:订单已创建 → 付款成功 → 等待到店核销', () => {
  assert.deepEqual(timeline(Object.assign({}, base, { paymentStatus: 2, registrationStatus: 2 })),
    ['订单已创建', '付款成功', '等待到店核销'])
})

test('退款派发中(payment_status=4, payout 6):付款成功 → 正在退款,不出现「等待支付」', () => {
  const labels = timeline(Object.assign({}, base, { paymentStatus: 4, registrationStatus: 3,
    refundApplication: { status: 1, payoutStatus: 6, refundAmount: 39.99 } }))
  assert.deepEqual(labels, ['订单已创建', '付款成功', '正在退款'])
})

test('退款到账(payout 1):付款成功 → 退款成功', () => {
  const labels = timeline(Object.assign({}, base, { paymentStatus: 4, registrationStatus: 3,
    refundApplication: { status: 1, payoutStatus: 1, refundAmount: 39.99 } }))
  assert.deepEqual(labels, ['订单已创建', '付款成功', '退款成功'])
})

test('未付款仍是「等待支付」', () => {
  assert.deepEqual(timeline(Object.assign({}, base, { paymentStatus: 1, registrationStatus: 1, paymentTime: null })),
    ['订单已创建', '等待支付'])
})
