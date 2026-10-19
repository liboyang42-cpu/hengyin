'use strict'

// F0435/F0436:作品图与联系二维码 —— 公开范围说明、撤回/审核清除后的回读、保存回执里的审核态。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('../helpers/ui-sandbox-vm.js')

const ROOT = path.resolve(__dirname, '../..')
const read = (relative) => fs.readFileSync(path.join(ROOT, relative), 'utf8')

function applyPatch(target, patch) {
  Object.entries(patch).forEach(([key, value]) => {
    const parts = key.split('.')
    let cursor = target
    for (let index = 0; index < parts.length - 1; index += 1) cursor = cursor[parts[index]]
    cursor[parts.at(-1)] = value
  })
}

function loadPage() {
  let definition
  const requests = []
  const app = {
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getUserID: () => 7,
    tips() {},
    chooseImage() {},
    sendRequest(options) { requests.push(options) },
  }
  vm.runInNewContext(read('pages/gerenziliao/gerenziliao.js'), {
    getApp: () => app,
    getCurrentPages: () => [{}, {}],
    Page(value) { definition = value },
    require(request) {
      if (request === '../../utils/response-shape.js') return require('../../utils/response-shape.js')
      if (request === '../../utils/form-state.js') return require('../../utils/form-state.js')
      throw new Error(`unexpected require: ${request}`)
    },
    wx: { hideLoading() {}, showLoading() {}, showToast() {}, navigateBack() {} },
    setTimeout() { return 1 },
    clearTimeout() {},
    console,
  }, { filename: 'pages/gerenziliao/gerenziliao.js' })
  const page = Object.assign({}, definition)
  page.data = JSON.parse(JSON.stringify(definition.data))
  page.setData = function (patch, callback) {
    applyPatch(this.data, patch)
    if (callback) callback()
  }
  return { page, requests }
}

test('服务端撤回或审核清除的二维码,重读后不再由旧内存值冒充当前资料', () => {
  const h = loadPage()
  h.page.getUserData()
  h.requests[0].success({ code: 200, data: { name: '玩家', wechat: 'https://o/qr.png', casePics: 'https://o/w1.png' } })
  assert.equal(h.page.data.userInfo.wechat, 'https://o/qr.png')

  h.page.getUserData()
  // SelfMemberVO 序列化可能省略 null 字段:省略 = 当前没有,不能沿用上一份
  h.requests[1].success({ code: 200, data: { name: '玩家', casePics: 'https://o/w1.png' } })
  assert.equal(h.page.data.userInfo.wechat, '', '二维码已不在服务端资料里,本人页也不能再显示')
  assert.equal(h.page.data.userInfo.casePics, 'https://o/w1.png', '作品图独立保留')
  assert.equal(JSON.stringify(h.page.data.casePicsList), JSON.stringify(['https://o/w1.png']))
})

test('换了新图保存后,回执说明图片在审核中;没换图则照常返回', () => {
  const changed = loadPage()
  changed.page.getUserData()
  changed.requests[0].success({ code: 200, data: { name: '玩家', wechat: 'https://o/qr-old.png', casePics: '' } })
  changed.page.data.userInfo.wechat = 'https://o/qr-new.png'
  changed.page.saveInfo()
  const payload = JSON.parse(changed.requests[1].data)
  assert.equal(payload.wechat, 'https://o/qr-new.png')
  changed.requests[1].success({ code: 200 })
  assert.equal(changed.page.data.mediaReviewPending, true)

  const same = loadPage()
  same.page.getUserData()
  same.requests[0].success({ code: 200, data: { name: '玩家', wechat: 'https://o/qr.png', casePics: '' } })
  same.page.saveInfo()
  same.requests[1].success({ code: 200 })
  assert.equal(same.page.data.mediaReviewPending, false)
})

test('资料页写明二维码仅自己可见、作品公开、审核与无法召回', () => {
  const wxml = read('pages/gerenziliao/gerenziliao.wxml')
  assert.match(wxml, /联系二维码<\/view>\s*<view class="profile-media-scope">仅自己可见/)
  assert.match(wxml, /探索作品<\/view>\s*<view class="profile-media-scope">公开展示/)
  assert.match(wxml, /无法召回/)
  assert.match(wxml, /mediaReviewPending \? '新图片已提交审核/)
  assert.match(wxml, /aria-label="移除联系二维码"/)
})
