'use strict'

// F0434:资料页的「城市签名」是自由文字,不是结构化城市。保存资料不得暗改城市(workAddress 只由首页城市链写),
// 页面要写明这一点,避免用户以为在这里改了城市。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('../helpers/ui-sandbox-vm.js')

const ROOT = path.resolve(__dirname, '../..')
const read = (relative) => fs.readFileSync(path.join(ROOT, relative), 'utf8')

function loadPage() {
  let definition
  const requests = []
  vm.runInNewContext(read('pages/gerenziliao/gerenziliao.js'), {
    getApp: () => ({
      globalData: {}, getUserID: () => 7, tips() {}, chooseImage() {},
      sendRequest(options) { requests.push(options) },
    }),
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
    Object.entries(patch).forEach(([key, value]) => {
      const parts = key.split('.')
      let cursor = this.data
      for (let i = 0; i < parts.length - 1; i += 1) cursor = cursor[parts[i]]
      cursor[parts.at(-1)] = value
    })
    if (callback) callback()
  }
  return { page, requests }
}

test('改签名后保存:提交里没有任何城市/地址字段', () => {
  const h = loadPage()
  h.page.data.canSave = true
  h.page.data.userInfo.name = '城市玩家'
  h.page.data.userInfo.workAddress = '上海市' // 即便回读里带了城市,也不得随资料一起写回
  h.page.openPop()
  h.page.onInputChange({ currentTarget: { dataset: { field: 'introduction' } }, detail: { value: '夜里走弄堂' } })
  h.page.confirm()
  h.page.saveInfo()
  const payload = JSON.parse(h.requests[0].data)
  assert.equal(payload.introduction, '夜里走弄堂')
  for (const key of ['workAddress', 'city', 'cityId', 'cityCode', 'address']) {
    assert.ok(!(key in payload), '资料保存不得携带 ' + key)
  }
})

test('页面写明签名不等于城市,城市在首页切换', () => {
  const wxml = read('pages/gerenziliao/gerenziliao.wxml')
  assert.match(wxml, /城市签名<\/view>\s*<view class="profile-media-scope">只改签名文字，不会改动你的所在城市/)
})
