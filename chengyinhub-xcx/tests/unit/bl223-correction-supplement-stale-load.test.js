'use strict'
// BL-223:补证前已发出的刷新回包在补证成功后才到,不得把旧 item(旧版本号、可补证)覆盖回来。
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

const OLD = { id: 7, reviewStatus: 'NEEDS_EVIDENCE', canSupplement: true, version: 1 }
const NEW = { id: 7, reviewStatus: 'PENDING', canSupplement: false, version: 2 }

for (const late of ['success', 'fail']) {
  test(`BL-223 补证成功后,提交前发出的刷新晚到(${late})不覆盖回读结果`, () => {
    const requests = []
    const p = loadPage(requests)
    let pulls = 0
    global.wx.stopPullDownRefresh = () => { pulls += 1 }
    p.onLoad({ contributionId: '7' })
    requests.shift().success({ code: 200, data: OLD })
    assert.equal(p.data.item.canSupplement, true)
    p.onPullDownRefresh()
    const stale = requests.shift() // 下拉刷新在途
    assert.equal(stale.url, '/api/community-trust/contributions/7')
    p.openSupplement()
    p.onSupplementInput({ detail: { value: '新地址在 3 号门' } })
    p.submitSupplement()
    requests.shift().success({ code: 200, data: { id: 7 } })
    requests.shift().success({ code: 200, data: NEW })
    assert.equal(p.data.sheet, 'ok')
    assert.equal(p.data.item.version, 2)
    if (late === 'success') stale.success({ code: 200, data: OLD })
    else stale.fail()
    assert.equal(p.data.item.canSupplement, false, '「补充说明」不得重新可点')
    assert.equal(p.data.item.version, 2, '版本号保持回读后的值')
    assert.equal(pulls, 1, '下拉转圈照常收起')
  })
}
