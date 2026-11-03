'use strict'

// 打磨第 3 轮 §4-9:负数金额统一为「−¥15.44」,走 utils/money-format.js 一个入口;结算、批次详情、退款行都经过它。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { yuan, fixed2, MINUS } = require('../../utils/money-format.js')

const ROOT = path.join(__dirname, '../..')

test('yuan:负数 −¥、两位小数、signed 才给 +、零不带符号、读不出返回 null', () => {
  assert.equal(MINUS, '−')
  assert.equal(yuan(-15.44), '−¥15.44')
  assert.equal(yuan('-15.4'), '−¥15.40')
  assert.equal(yuan(1000), '¥1000.00')
  assert.equal(yuan(1000, { signed: true }), '+¥1000.00')
  assert.equal(yuan(-2, { signed: true }), '−¥2.00')
  assert.equal(yuan(0, { signed: true }), '¥0.00')
  assert.equal(yuan(-0.001), '¥0.00')
  for (const v of [null, undefined, '', 'abc', NaN]) assert.equal(yuan(v), null)
  assert.equal(fixed2(-15.444), '-15.44')
})

function loadPage(rel) {
  let cfg = null
  global.getApp = () => ({ globalData: {} })
  global.wx = { getStorageSync: () => '', setStorageSync() {}, getSystemInfoSync: () => ({}), getWindowInfo: () => ({}), getAppBaseInfo: () => ({}) }
  global.Page = (c) => { cfg = c }
  global.Component = (c) => { cfg = c }
  const file = path.join(ROOT, rel)
  delete require.cache[require.resolve(file)]
  require(file)
  return cfg
}

test('结算页:负的收入明细与批次不再渲成 ¥-', () => {
  const page = loadPage('pages/merchant/ledger/index.js')
  const entry = page.entryRow({ signedAmount: -15.44, source: 'MERCHANT_CLAWBACK' })
  assert.equal(entry.amountDisplay, '−¥15.44')
  assert.equal(entry.amountTone, 'neg')
  assert.equal(page.entryRow({ signedAmount: 1000 }).amountDisplay, '+¥1000.00')
  assert.equal(page.batchRow({ amountTotal: -15.44 }).amountDisplay, '−¥15.44')
  assert.equal(page.redemptionRow({ settlementAmount: -3 }, true).amountDisplay, '−¥3.00')
})

test('源码:结算/批次/核销详情/退款/合作分润/客户详情不再自己拼 ¥', () => {
  const files = [
    'pages/merchant/ledger/index.js',
    'pages/merchant/ledger/batch-detail/index.js',
    'pages/merchant/ledger/order-detail/index.js',
    'pages/merchant/ledger/order-detail/view-model.js',
    'pages/merchant/aftercare/detail/view-model.js',
    'pages/coop/components/settlement-detail/view-model.js',
    'pages/merchant/customer/detail/view-model.js',
    'subpackageA/components/scene-merchant-profit/index.js',
    'subpackageA/components/scene-merchant-profit/index.wxml',
  ]
  for (const f of files) {
    const src = fs.readFileSync(path.join(ROOT, f), 'utf8').replace(/\/\/[^\n]*|\/\*[\s\S]*?\*\//g, '')
    assert.doesNotMatch(src, /`[^`]*¥\$\{|'¥' ?\+|'[+−]¥' ?\+/, f)
  }
})
