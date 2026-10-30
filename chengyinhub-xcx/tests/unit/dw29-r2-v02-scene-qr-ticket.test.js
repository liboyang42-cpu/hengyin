'use strict'
// 2026-10-29 走查 V-02 Round 2:深链/玩法页的入场码组件与票夹背面同一版式与规则 ——
// 只有标题 + 码(码心城瘾 logo)，7 天内到期才写「N 天内过期」；无倒计时、无码串、无说明；到期前 30 秒静默续签。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')
const ROOT = path.resolve(__dirname, '../..')
const read = rel => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const FILE = path.join(ROOT, 'components/cy/scene-qr-ticket/index.js')

function mount(props) {
  let definition
  const requests = [], intervals = []
  const app = { getSession: () => ({ getRevision: () => 0 }), _loginEpoch: 0, getUserID: () => '11', getAuthorization: () => 'fixture-auth-11',
    sendRequest(options) { requests.push(options) } }
  const wx = { getStorageSync: () => '', setStorageSync() {} }
  vm.runInNewContext(fs.readFileSync(FILE, 'utf8'), { Component(value) { definition = value }, getApp: () => app, wx,
    require: id => require(path.resolve(path.dirname(FILE), id)), Date,
    setInterval(fn) { intervals.push(fn); return intervals.length }, clearInterval(id) { intervals[id - 1] = null } })
  const page = { ...definition.methods, data: { ...definition.data, registrationId: '71', ...props },
    setData(patch) { Object.assign(this.data, patch) }, triggerEvent() {} }
  definition.lifetimes.attached.call(page)
  return { page, requests, intervals, definition }
}

test('V-02 组件版式:标题 + 码 + logo，到期行有才出，没有倒计时/码串/说明/放大层', () => {
  const xml = read('components/cy/scene-qr-ticket/index.wxml').replace(/<!--[\s\S]*?-->/g, '')
  const card = xml.slice(xml.indexOf('class="voucher__card"'), xml.indexOf('seat-choice__switch'))
  assert.match(card, /<text wx:if="\{\{title\}\}" class="voucher__title ep1">\{\{title\}\}<\/text>/)
  // 10-29 第三轮:码换成 cy-dot-qr(圆点 + 码心黑色 logo + 彩色波),和票夹背面同一个组件
  assert.match(card, /<cy-dot-qr wx:if="\{\{state === 'ready'\}\}" src="\{\{qr\}\}" size="\{\{360\}\}" \/>/)
  assert.match(card, /<text wx:if="\{\{expiry\}\}" class="voucher__exp">\{\{expiry\}\}<\/text>/)
  assert.doesNotMatch(xml, /Code :|countdown|城瘾活动入场码|出示扫码核销|现在核销|bigqr|长期开放/)
  const css = read('components/cy/scene-qr-ticket/index.wxss')
  const wallet = read('subpackageMember/signup/index.wxss')
  for (const [mine, theirs] of [['.voucher__qr {', '.wc__qr {'], ['.voucher__exp {', '.wc__exp {']]) {
    const body = (src, sel) => src.slice(src.indexOf(sel) + sel.length, src.indexOf('}', src.indexOf(sel)))
    assert.equal(body(css, mine), body(wallet, theirs), mine + ' 与票夹同值')
  }
  assert.doesNotMatch(read('components/cy/scene-qr-ticket/index.js'), /有效期剩余|setScreenBrightness/)
})

test('V-02 组件到期行与票夹同一规则', () => {
  const pad = n => String(n).padStart(2, '0')
  const d = new Date(Date.now() + 2 * 86400000)
  const soon = d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' 23:59:00'
  assert.equal(mount({ until: soon }).page.data.expiry, '2 天内过期')
  assert.equal(mount({ until: '' }).page.data.expiry, '')
  assert.equal(mount({ until: Date.now() + 60 * 86400000 }).page.data.expiry, '')
  const h = mount({}); h.definition.observers.until.call(h.page, Date.now() + 1000); assert.equal(h.page.data.expiry, '今天过期')
})

test('V-02 组件到期前静默续签，续签中旧码照常出示，失败就稍后重试，真过期才落错误', () => {
  const h = mount({ title: '海上方舟' })
  assert.equal(h.requests.length, 1)
  let now = Date.now()
  h.requests[0].success({ code: 200, data: { qrcodeUrl: 'qr-1', code: 'RAW', expiresAt: now + 300000 } })
  assert.equal(h.page.data.state, 'ready'); assert.equal(h.page.data.qr, 'qr-1'); assert.equal('code' in h.page.data, false)
  const tick = () => h.intervals.filter(Boolean).at(-1)()
  tick(); assert.equal(h.requests.length, 1, '离到期还远不续签')
  h.page._expiresAt = Date.now() + 20000
  tick(); assert.equal(h.requests.length, 2, '到期前 30 秒内静默续签')
  assert.equal(h.page.data.state, 'ready'); assert.equal(h.page.data.qr, 'qr-1')
  tick(); assert.equal(h.requests.length, 2, '续签中不重复请求')
  h.requests[1].fail()
  assert.equal(h.page.data.state, 'ready'); assert.equal(h.page.data.qr, 'qr-1', '续签失败旧码照常出示')
  tick(); assert.equal(h.requests.length, 2, '5 秒内不重试')
  h.page._retryAt = 0; tick(); assert.equal(h.requests.length, 3)
  h.requests[2].success({ code: 200, data: { qrcodeUrl: 'qr-2', expiresAt: Date.now() + 300000 } })
  assert.equal(h.page.data.qr, 'qr-2')
  h.page._expiresAt = Date.now() - 1; tick()
  assert.equal(h.page.data.state, 'error'); assert.match(h.page.data.errorText, /已过期/)
})

test('V-02 玩法页与深链宿主把标题和票有效期交给组件', () => {
  const play = read('pages/play/index.js')
  assert.match(play, /openScene\('qr-ticket', \{ registrationId: regId, title: this\.data\.topicName \|\| '', until: this\._passExpiresAt \|\| '' \}\)/)
  assert.equal((play.match(/_passExpiresAt = d\.expiresAt \|\| ''/g) || []).length, 2)
  for (const host of ['pages/play/index.wxml', 'components/cy/scene-deep-link/index.wxml']) {
    assert.match(read(host), /<cy-scene-qr-ticket registration-id="\{\{sceneCurrent\.params\.registrationId\}\}" title="\{\{sceneCurrent\.params\.title\}\}" until="\{\{sceneCurrent\.params\.until\}\}"/)
  }
})
