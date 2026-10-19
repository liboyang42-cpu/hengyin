'use strict'

// X039:主理人「自由探索期次」为空时区分原因 —— 还没承接 vs 已承接但条款未开售。
// 原因只在列表真为空时才查;读不到原因保持通用空态,不猜。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const ROOT = path.resolve(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const JS = read('pages/club/detail/index.js')
const WXML = read('pages/club/detail/index.wxml')

function extractMethod(name) {
  const start = JS.indexOf('  ' + name + '(')
  assert.ok(start > 0, '找不到 ' + name)
  let depth = 0
  let end = JS.indexOf('{', start)
  for (; end < JS.length; end += 1) {
    if (JS[end] === '{') depth += 1
    else if (JS[end] === '}') { depth -= 1; if (depth === 0) { end += 1; break } }
  }
  return JS.slice(start, end)
}

function harness() {
  const requests = []
  const sandbox = {
    app: { sendRequest: (o) => requests.push(o) },
    jsonBody: (v) => JSON.stringify(v),
    jsonHeader: () => ({}),
    isSuccess: (res) => !!res && String(res.code) === '200',
    module: {},
  }
  vm.runInNewContext('module.exports = {' + extractMethod('loadShareEditionEmptyReason') + '}', sandbox)
  const page = Object.assign({ data: { clubId: 7, shareEditionEmptyReason: '' } }, sandbox.module.exports)
  page.setData = function (patch) { Object.assign(this.data, patch) }
  return { page, requests }
}

test('空列表后才查原因;识别两种原因,其余保持通用空态', () => {
  assert.match(JS, /if \(!rows\.length\) that\.loadShareEditionEmptyReason\(clubId\);/)
  for (const [reason, expected] of [['AWAITING_SALE', 'AWAITING_SALE'], ['NOT_UNDERTAKEN', 'NOT_UNDERTAKEN'], ['WHATEVER', '']]) {
    const h = harness()
    h.page.loadShareEditionEmptyReason(7)
    assert.equal(h.requests[0].url, '/api/club-compensation/editions/empty-reason')
    h.requests[0].success({ code: 200, data: { reason } })
    assert.equal(h.page.data.shareEditionEmptyReason, expected)
  }
  const stale = harness()
  stale.page.loadShareEditionEmptyReason(8) // 已切到别的俱乐部
  stale.requests[0].success({ code: 200, data: { reason: 'AWAITING_SALE' } })
  assert.equal(stale.page.data.shareEditionEmptyReason, '', '过期响应不得写到当前俱乐部')
})

test('两种原因各有说清楚的空态文案', () => {
  assert.match(WXML, /shareEditionEmptyReason === 'AWAITING_SALE'\}\}" title="已承接的期次还没开售"/)
  assert.match(WXML, /shareEditionEmptyReason === 'NOT_UNDERTAKEN'\}\}" title="还没有承接自由探索期次"/)
})
