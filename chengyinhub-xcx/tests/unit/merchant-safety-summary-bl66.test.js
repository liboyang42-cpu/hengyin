'use strict'
// BL-66:商家公开页安全感受摘要(只读)。样本不足/策略未启用/失败都不展示,不做评分提交。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const { shapeMerchantSafety } = require('../../subpackageMember/components/cy/profile/merchant-safety-summary.js')

const ROOT = path.resolve(__dirname, '../..')
const PROFILE_JS = path.join(ROOT, 'subpackageMember/components/cy/profile/index.js')
const PROFILE_WXML = path.join(ROOT, 'subpackageMember/components/cy/profile/index.wxml')

test('只在样本充足且等级已知时给出摘要', () => {
  const ok = shapeMerchantSafety({ sampleStatus: 'SUFFICIENT', level: 'SAFE', sampleCount: 12, windowDays: 90 })
  assert.equal(ok.text, '到访用户感到安全')
  assert.match(ok.note, /近 90 天 12 人反馈/)
  assert.match(ok.note, /仅代表用户感受/)
  assert.equal(shapeMerchantSafety({ sampleStatus: 'INSUFFICIENT', level: null, sampleCount: 2 }), null)
  assert.equal(shapeMerchantSafety({ sampleStatus: 'POLICY_PENDING', level: null }), null)
  assert.equal(shapeMerchantSafety({ sampleStatus: 'SUFFICIENT', level: 'WHATEVER', sampleCount: 5 }), null)
  assert.equal(shapeMerchantSafety({ sampleStatus: 'SUFFICIENT', level: 'SAFE', sampleCount: 0 }), null)
  assert.equal(shapeMerchantSafety(null), null)
  assert.equal(shapeMerchantSafety([]), null)
  const noWindow = shapeMerchantSafety({ sampleStatus: 'SUFFICIENT', level: 'UNSAFE', sampleCount: 6 })
  assert.equal(noWindow.text, '部分到访用户感到不安全')
  assert.doesNotMatch(noWindow.note, /undefined|null|NaN/)
})

function loadProfile(requests) {
  const previous = { Component: global.Component }
  let definition
  global.getApp = () => ({
    globalData: { features: {} }, getUserID: () => '42', getUserRole: () => 'player', getUserType: () => 1,
    sendRequest: (o) => requests.push(o),
  })
  global.wx = { getStorageSync: () => '', setStorageSync() {}, setNavigationBarColor() {}, setBackgroundColor() {} }
  global.Component = (d) => { definition = d }
  delete require.cache[require.resolve(PROFILE_JS)]
  require(PROFILE_JS)
  global.Component = previous.Component
  const c = Object.assign({}, definition.methods, { data: JSON.parse(JSON.stringify(definition.data)) })
  c.setData = (patch) => Object.assign(c.data, patch)
  return c
}

test('商家主页读取摘要:GET 正确路径,充足才写入', () => {
  const requests = []
  const c = loadProfile(requests)
  c.applySubjectMerchant({ id: 77, name: '店', businessStatus: 1 })
  const req = requests.find((r) => /safety\/merchants/.test(r.url))
  assert.equal(req.url, '/api/community-trust/safety/merchants/77')
  assert.equal(req.method, 'GET')
  req.success({ code: 200, data: { sampleStatus: 'SUFFICIENT', level: 'NEUTRAL', sampleCount: 8, windowDays: 30 } })
  assert.equal(c.data.merchantSafety.text, '到访用户感受一般')
})

test('样本不足、失败、换了被看者:都不展示', () => {
  const requests = []
  const c = loadProfile(requests)
  c.applySubjectMerchant({ id: 77 })
  requests.pop().success({ code: 200, data: { sampleStatus: 'INSUFFICIENT', sampleCount: 1 } })
  assert.equal(c.data.merchantSafety, null)

  c.applySubjectMerchant({ id: 77 })
  requests.pop().success({ code: 400, msg: '商家不存在' })
  assert.equal(c.data.merchantSafety, null)

  c.applySubjectMerchant({ id: 77 })
  const stale = requests.pop()
  c.clearSubjectMerchant('not-merchant')
  stale.success({ code: 200, data: { sampleStatus: 'SUFFICIENT', level: 'SAFE', sampleCount: 9 } })
  assert.equal(c.data.merchantSafety, null, '迟到回包不得写到别的主页上')
})

test('模板只在有摘要时渲染一行,沿用营业信息 pc-mrow 样式,无评分提交入口', () => {
  const wxml = fs.readFileSync(PROFILE_WXML, 'utf8')
  assert.match(wxml, /<view class="pc-mrow" wx:if="\{\{merchantSafety\}\}"/)
  const js = fs.readFileSync(PROFILE_JS, 'utf8')
  assert.doesNotMatch(js, /safety\/ratings|safety\/submit/)
})
