'use strict'
// BL-216:补证成功(accepted)但回读失败时,不再显示「补充说明」;关闭后静默回读一次。
const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')

function loadPage(requests) {
  let def
  global.getApp = () => ({ globalData: {}, sendRequest: (o) => requests.push(o) })
  global.Page = (d) => { def = d }
  global.wx = { stopPullDownRefresh() {}, navigateTo() {}, showToast() {} }
  const file = path.join(ROOT, 'subpackageMember/correction-detail/index.js')
  delete require.cache[require.resolve(file)]
  require(file)
  const p = Object.assign({}, def, { data: JSON.parse(JSON.stringify(def.data)) })
  p.setData = (patch, cb) => {
    for (const k of Object.keys(patch)) {
      const parts = k.split('.')
      let o = p.data
      while (parts.length > 1) { const h = parts.shift(); o[h] = o[h] || {}; o = o[h] }
      o[parts[0]] = patch[k]
    }
    if (cb) cb()
  }
  return p
}

function submitOk(p, requests) {
  p.onLoad({ contributionId: '7' })
  requests.shift().success({ code: 200, data: { id: 7, reviewStatus: 'NEEDS_EVIDENCE', canSupplement: true, version: 1 } })
  assert.equal(p.data.item.canSupplement, true)
  p.openSupplement()
  p.onSupplementInput({ detail: { value: '新地址在 3 号门' } })
  p.submitSupplement()
  const post = requests.shift()
  assert.equal(post.url, '/api/community-trust/contributions/supplement')
  post.success({ code: 200, data: { id: 7 } })
  const readBack = requests.shift()
  assert.equal(readBack.url, '/api/community-trust/contributions/7')
  return readBack
}

for (const [name, failIt] of [
  ['网络失败', (r) => r.fail()],
  ['业务失败', (r) => r.success({ code: 500, msg: 'x' })],
  ['状态码异常', (r) => r.successStatusAbnormal({}, 502)],
]) {
  test('补证成功 + 回读' + name + ' → 成功态且「补充说明」收起,关闭后回读一次', () => {
    const requests = []
    const p = loadPage(requests)
    failIt(submitOk(p, requests))
    assert.equal(p.data.sheet, 'ok')
    assert.equal(p.data.item.canSupplement, false)
    assert.equal(p.data.item.statusText !== undefined, true, '其余投影保持不动')
    p.openSupplement()
    assert.equal(p.data.sheet, 'ok', '不可再打开补证半屏')
    p.closeSheet()
    assert.equal(p.data.sheet, '')
    assert.equal(requests.length, 1)
    assert.equal(requests[0].url, '/api/community-trust/contributions/7')
    p.submitSupplement()
    assert.equal(requests.length, 1, '不再发补证请求')
  })
}

test('补证成功 + 回读成功仍以服务端为准', () => {
  const requests = []
  const p = loadPage(requests)
  submitOk(p, requests).success({ code: 200, data: { id: 7, reviewStatus: 'PENDING', canSupplement: false, version: 2 } })
  assert.equal(p.data.sheet, 'ok')
  assert.equal(p.data.item.statusText, '待审核')
  assert.equal(p.data.item.canSupplement, false)
})
