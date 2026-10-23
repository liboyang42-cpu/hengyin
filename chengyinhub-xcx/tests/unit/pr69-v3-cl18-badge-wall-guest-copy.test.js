'use strict'
// PR #69 V3 · CL-18(F-W1-03/12/13, #69 cd1349f1):游客进勋章墙看到的是登录引导，不是「勋章数据没有到达」。
// 状态位(wallAuthRequired、单次登录、401/403 区分)由 private-read-login-entry.test.js 覆盖;
// 这里把页面真实 data 代入 WXML 里 cy-error 的属性表达式，校验游客实际看到的标题、说明与按钮。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const WXML = fs.readFileSync(path.join(ROOT, 'subpackageP3/pages/badge-wall/index/index.wxml'), 'utf8')

function mount(owner) {
  let definition
  const requests = []
  const app = { getUserID: () => owner, sendRequest: o => { requests.push(o) }, firstLogin: () => Promise.resolve() }
  global.getApp = () => app
  global.wx = { getStorageSync: () => '', showToast() {}, navigateTo() {}, getWindowInfo: () => ({ statusBarHeight: 20 }) }
  global.Page = d => { definition = d }
  global.Component = d => { definition = d }
  const file = path.join(ROOT, 'subpackageP3/pages/badge-wall/index/index.js')
  delete require.cache[file]
  require(file)
  const page = Object.assign({}, definition, definition.methods, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, done) { Object.assign(this.data, patch); if (done) done() },
  })
  page.data.viewMode = 'list'
  return { page, requests }
}

// 取 class="bw-error-state" 的 cy-error，按页面 data 求值它的 {{}} 属性。
function renderErrorState(data) {
  const tag = WXML.match(/<cy-error[^>]*class="bw-error-state"[\s\S]*?\/>/)
  assert.ok(tag, '勋章墙必须保留整页错误/登录态出口')
  const attrs = {}
  for (const [, name, expr] of tag[0].matchAll(/([\w:-]+)="\{\{([\s\S]*?)\}\}"/g)) {
    // eslint-disable-next-line no-new-func
    attrs[name] = new Function('d', 'with (d) { return (' + expr + ') }')(data)
  }
  return attrs
}

const flush = () => new Promise(resolve => setImmediate(resolve))

test('游客打开勋章墙:不发私有请求，标题是登录引导、按钮是「登录」', () => {
  const { page, requests } = mount('')
  page.loadData()
  assert.equal(requests.length, 0)
  const shown = renderErrorState(page.data)
  assert.equal(shown['wx:if'], true)
  assert.equal(shown.title, '登录后查看我的勋章')
  assert.equal(shown.sub, '登录后可查看你已获得的城市勋章')
  assert.equal(shown.retry, '登录')
  assert.equal(shown['load-failure'], false, '登录引导不能被当成加载失败留白')
  assert.doesNotMatch(shown.title, /没有到达|失败/)
})

test('会话过期(真实401)同样显示登录引导；网络失败仍是读取失败文案', async () => {
  const expired = mount('7')
  expired.page.loadData()
  for (const r of expired.requests) r.success({ code: 401, msg: '请先登录' })
  await flush()
  assert.equal(renderErrorState(expired.page.data).title, '登录后查看我的勋章')

  const offline = mount('7')
  offline.page.loadData()
  for (const r of offline.requests) r.fail({ errMsg: 'offline' })
  await flush()
  const shown = renderErrorState(offline.page.data)
  assert.equal(shown.title, '勋章数据没有到达')
  assert.equal(shown.retry, '重试')
})
