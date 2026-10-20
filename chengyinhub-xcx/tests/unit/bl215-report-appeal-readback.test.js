'use strict'
// BL-215:申诉已受理但回读失败时,按钮立即收起;点「知道了」后回读一次以服务端为准。
const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')

const FILE = path.resolve(__dirname, '../../subpackageMember/report-detail/index.js')

function loadPage(responder) {
  const requests = []
  let config = null
  const prev = { getApp: global.getApp, Page: global.Page, wx: global.wx, getCurrentPages: global.getCurrentPages }
  global.getApp = () => ({ globalData: {}, sendRequest: (o) => { requests.push(o); responder(o) } })
  global.Page = (c) => { config = c }
  global.wx = { navigateTo() {}, navigateBack() {}, redirectTo() {}, stopPullDownRefresh() {}, showToast() {}, showModal() {} }
  global.getCurrentPages = () => [{}, {}]
  delete require.cache[FILE]
  require(FILE)
  const vm = Object.assign({}, config, {
    setData(patch, cb) {
      for (const k of Object.keys(patch)) {
        const parts = k.split('.')
        let o = this.data
        while (parts.length > 1) { const p = parts.shift(); o[p] = o[p] || {}; o = o[p] }
        o[parts[0]] = patch[k]
      }
      if (cb) cb()
    },
  })
  vm.data = JSON.parse(JSON.stringify(config.data))
  return { vm, requests, restore: () => Object.assign(global, prev) }
}

function submit(vm) {
  vm.openAppeal()
  vm.onReasonInput({ detail: { value: '处理结论与事实不符' } })
  vm.toConfirm()
  vm.submitAppeal()
}

test('BL-215 申诉成功 + 回读失败 → appealable 置 false,关闭后回读一次', () => {
  let readFails = false
  const h = loadPage((o) => {
    if (o.url.endsWith('/taxonomy')) { o.success({ code: 200, data: { reasons: [] } }); return }
    if (o.url.endsWith('/appeal')) { readFails = true; o.success({ code: 200, data: { reportId: 7 } }); return }
    if (readFails) { o.fail({}); return }
    o.success({ code: 200, data: { id: 7, stage: 'RESOLVED', timeline: [] } })
  })
  try {
    h.vm.onLoad({ reportId: '7' })
    assert.equal(h.vm.data.report.appealable, true)
    submit(h.vm)
    assert.equal(h.vm.data.sheet, 'ok')
    assert.equal(h.vm.data.report.appealable, false, '已受理,回读前不得再可申诉')
    const reads = () => h.requests.filter((r) => r.url === '/api/community-trust/reports/7').length
    const before = reads()
    h.vm.closeSheet()
    assert.equal(h.vm.data.sheet, '')
    assert.equal(reads(), before + 1, '成功后关闭也回读一次')
  } finally { h.restore() }
})
