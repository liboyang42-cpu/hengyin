'use strict'
// BL-222:提交申诉前已发出的刷新回包在申诉成功后才到,不得把页面冲回旧阶段。
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

const OLD = { id: 7, stage: 'RESOLVED', timeline: [] }
const NEW = { id: 7, stage: 'APPEALED', timeline: [{ eventType: 'APPEALED' }] }

for (const late of ['success', 'fail']) {
  test(`BL-222 申诉成功后,提交前发出的刷新晚到(${late})不覆盖回读结果`, () => {
    let appealed = false
    let hold = false
    const held = []
    const h = loadPage((o) => {
      if (o.url.endsWith('/taxonomy')) { o.success({ code: 200, data: { reasons: [] } }); return }
      if (o.url.endsWith('/appeal')) { appealed = true; o.success({ code: 200, data: { reportId: 7 } }); return }
      if (hold) { hold = false; held.push(o); return }
      o.success({ code: 200, data: appealed ? NEW : OLD })
    })
    let pulls = 0
    global.wx.stopPullDownRefresh = () => { pulls += 1 }
    try {
      h.vm.onLoad({ reportId: '7' })
      assert.equal(h.vm.data.report.appealable, true)
      hold = true
      h.vm.onPullDownRefresh() // 详情请求在途不回包
      assert.equal(held.length, 1)
      submit(h.vm)
      assert.equal(h.vm.data.sheet, 'ok')
      assert.equal(h.vm.data.report.stage, 'APPEALED')
      assert.equal(h.vm.data.report.appealable, false)
      if (late === 'success') held[0].success({ code: 200, data: OLD })
      else held[0].fail({})
      assert.equal(h.vm.data.report.stage, 'APPEALED', '旧快照不得覆盖刷新后的阶段')
      assert.equal(h.vm.data.report.appealable, false, '「提交申诉」不得重新可点')
      assert.equal(pulls, 1, '下拉转圈照常收起')
    } finally { h.restore() }
  })
}
