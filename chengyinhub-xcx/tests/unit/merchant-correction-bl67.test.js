'use strict'
// BL-67:商家信息纠错(问题上报,纯文字)。类型只取 taxonomy,幂等键重试,业务拒绝原话展示。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const model = require('../../components/cy/merchant-correction-sheet/model.js')

const ROOT = path.resolve(__dirname, '../..')
const SHEET_JS = path.join(ROOT, 'components/cy/merchant-correction-sheet/index.js')

test('taxonomy:只收 ISSUE_REPORT 且只展示已知问题码', () => {
  const t = model.normalizeTaxonomy({ contributionTypes: ['INFO_UPDATE', 'ISSUE_REPORT'], issueTypes: ['CLOSED', 'WEIRD', 'OTHER'] })
  assert.deepEqual(t.issues.map((i) => i.code), ['CLOSED', 'OTHER'])
  assert.equal(model.normalizeTaxonomy({ contributionTypes: ['INFO_UPDATE'], issueTypes: ['CLOSED'] }), null)
  assert.equal(model.normalizeTaxonomy({ contributionTypes: ['ISSUE_REPORT'], issueTypes: ['WEIRD'] }), null)
  assert.equal(model.normalizeTaxonomy(null), null)
})

test('请求体按 MerchantContributionSubmitRequest,不带附件', () => {
  const key = model.makeIdempotencyKey('12', 1700000000000, 0.123456)
  assert.ok(key.length >= 8 && key.length <= 96)
  assert.deepEqual(model.buildPayload({ merchantId: '12', issueType: 'ADDRESS', description: '地址搬了', idempotencyKey: key }),
    { merchantId: 12, contributionType: 'ISSUE_REPORT', issueType: 'ADDRESS', description: '地址搬了', idempotencyKey: key })
  assert.equal(model.checkDescription('  ').ok, false)
  assert.equal(model.checkDescription('x'.repeat(2001)).ok, false)
  assert.deepEqual(model.receiptOf({ id: 5, reviewStatus: 'PENDING' }), { id: '5', statusText: '待审核' })
})

function loadSheet(requests) {
  let def
  global.getApp = () => ({ sendRequest: (o) => requests.push(o) })
  global.Component = (d) => { def = d }
  delete require.cache[require.resolve(SHEET_JS)]
  require(SHEET_JS)
  const c = Object.assign({}, def.methods, { data: JSON.parse(JSON.stringify(def.data)), properties: { show: true, merchantId: '12' } })
  c.setData = (p) => Object.assign(c.data, p)
  c.events = []
  c.triggerEvent = (n) => c.events.push(n)
  return c
}

function toForm(c, requests) {
  c.reset()
  requests.shift().success({ code: 200, data: { contributionTypes: ['ISSUE_REPORT'], issueTypes: ['CLOSED', 'ADDRESS'] } })
  assert.equal(c.data.stage, 'pick')
  c.onIssueSelect({ detail: { index: 1 } })
  c.onDescriptionInput({ detail: { value: '门店已搬到隔壁街' } })
}

test('提交成功给回执;网络未知用同一幂等键重试', () => {
  const requests = []
  const c = loadSheet(requests)
  toForm(c, requests)
  c.submit()
  const first = requests.shift()
  assert.equal(first.url, '/api/community-trust/contributions/submit')
  assert.equal(first.method, 'POST')
  const body = JSON.parse(first.data)
  assert.equal(body.issueType, 'ADDRESS')
  assert.equal(body.evidenceAssetIds, undefined)
  first.fail()
  assert.equal(c.data.stage, 'unknown')
  c.submit()
  const second = requests.shift()
  assert.equal(JSON.parse(second.data).idempotencyKey, body.idempotencyKey)
  second.success({ code: 200, data: { id: 31, reviewStatus: 'PENDING' } })
  assert.equal(c.data.stage, 'ok')
  assert.match(c.data.receiptText, /受理编号 31/)
  assert.match(c.data.receiptText, /待审核/)
})

test('业务拒绝原话展示,换键', () => {
  const requests = []
  const c = loadSheet(requests)
  toForm(c, requests)
  c.submit()
  const first = requests.shift()
  first.success({ code: 409, msg: '你已提交过该商家的贡献，请在原记录补证' })
  assert.equal(c.data.stage, 'fail')
  assert.equal(c.data.failText, '你已提交过该商家的贡献，请在原记录补证')
  c.backToForm()
  c.submit()
  assert.notEqual(JSON.parse(requests.shift().data).idempotencyKey, JSON.parse(first.data).idempotencyKey)
})

test('taxonomy 读取失败给重试,不进表单', () => {
  const requests = []
  const c = loadSheet(requests)
  c.reset()
  requests.shift().fail()
  assert.equal(c.data.stage, 'loadfail')
})

test('入口只在他人商家主页「更多」里', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'subpackageMember/components/cy/profile/index.wxml'), 'utf8')
  assert.match(wxml, /wx:if="\{\{!isSelf && subjectIsMerchant && subjectMerchant\.id\}\}" title="信息纠错"/)
  assert.match(wxml, /<cy-merchant-correction-sheet wx:if="\{\{!isSelf\}\}"/)
})
