'use strict'
// BL-210:收藏据点 onShow 静默回读失败时,empty 与 ready 一样保持现状;首屏失败仍进 error。
const test = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')

const PAGE = path.join(__dirname, '..', '..', 'subpackageRoam/node-favorites/index.js')

function boot() {
  const requests = []
  global.getApp = () => ({
    globalData: {},
    getImgUrl: (u) => u,
    sendRequest: (o) => { requests.push(o); return { abort() {} } },
  })
  global.getCurrentPages = () => [{}, {}]
  global.wx = { navigateTo() {}, navigateBack() {}, switchTab() {} }
  let def
  global.Page = (c) => { def = c }
  delete require.cache[require.resolve(PAGE)]
  require(PAGE)
  const page = Object.assign({}, def)
  page.data = JSON.parse(JSON.stringify(def.data))
  page.setData = function (patch) { Object.assign(this.data, patch) }
  return { page, requests }
}

function bootEmpty() {
  const ctx = boot()
  ctx.page.onLoad()
  ctx.requests[0].success({ code: 200, data: [] })
  assert.equal(ctx.page.data.state, 'empty')
  ctx.page.onShow()
  assert.equal(ctx.requests.length, 2)
  return ctx
}

test('empty 状态下 onShow 静默回读业务失败仍保持 empty', () => {
  const { page, requests } = bootEmpty()
  requests[1].success({ code: 500, msg: '服务繁忙' })
  assert.equal(page.data.state, 'empty')
})

test('empty 状态下 onShow 静默回读网络失败仍保持 empty', () => {
  const { page, requests } = bootEmpty()
  requests[1].fail({ errMsg: 'request:fail' })
  assert.equal(page.data.state, 'empty')
})

test('首屏失败仍进 error', () => {
  const a = boot()
  a.page.onLoad()
  a.requests[0].success({ code: 500, msg: '服务繁忙' })
  assert.equal(a.page.data.state, 'error')
  const b = boot()
  b.page.onLoad()
  b.requests[0].fail({ errMsg: 'request:fail' })
  assert.equal(b.page.data.state, 'error')
})
