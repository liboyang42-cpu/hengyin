'use strict'
// 2026-10-29 走查 V-02:票夹入场码 —— 码心放城瘾 logo、不露原始 token、不显示 5:01 倒计时，
// 到期前后台静默续签，背面写票本身的有效期(长期开放或活动时间)。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')
const ROOT = path.resolve(__dirname, '../..')
const read = rel => fs.readFileSync(path.join(ROOT, rel), 'utf8')

function loadPage() {
  const sandbox = { requests: [], timers: [] }
  const app = { getImgUrl: x => x, getPageSize: () => 10, getUserID: () => '1001', sendRequest(o) { sandbox.requests.push(o) } }
  const context = {
    getApp: () => app, Page(config) { sandbox.config = config }, console, Date,
    setTimeout(fn, ms) { sandbox.timers.push({ fn, ms }); return sandbox.timers.length },
    clearTimeout(id) { if (sandbox.timers[id - 1]) sandbox.timers[id - 1].cleared = true },
    setInterval() { throw new Error('入场码不再每秒倒计时') }, clearInterval() {},
    wx: { showToast() {} },
    require(r) {
      if (r.endsWith('/utils/play-engine.js')) return require('../../utils/play-engine.js')
      if (r.endsWith('/utils/session/login-reload.js')) return require('../../utils/session/login-reload.js')
      if (r.endsWith('/utils/toast.js')) return () => {}
      throw new Error('unexpected require: ' + r)
    },
  }
  vm.createContext(context)
  vm.runInContext(read('subpackageMember/signup/index.js'), context)
  const page = Object.assign({}, sandbox.config)
  page.data = JSON.parse(JSON.stringify(sandbox.config.data))
  page.setData = patch => Object.assign(page.data, patch)
  return { page, sandbox }
}

test('V-02 背面不露原始 token、不显示倒计时，改写票本身有效期；码心放城瘾 logo', () => {
  const xml = read('subpackageMember/signup/index.wxml').replace(/<!--[\s\S]*?-->/g, '')
  const back = xml.slice(xml.indexOf('wc__face wc__back'), xml.indexOf('team-entry'))
  assert.doesNotMatch(back, /qrCode|Code :/)
  assert.doesNotMatch(back, /qrCountdown/)
  assert.match(back, /<text class="wc__exp">\{\{item\.wValidity\}\}<\/text>/)
  assert.match(back, /class="wc__qr-logo"><image class="wc__qr-logo-img" src="\/images\/cy_logo_mark\.png"/)
  assert.ok(fs.statSync(path.join(ROOT, 'images/cy_logo_mark.png')).isFile())
  assert.match(read('subpackageMember/signup/index.wxss'), /\.wc__qr \{ position: relative;/)
  assert.doesNotMatch(read('subpackageMember/signup/index.js'), /有效期剩余/)
})

test('V-02 票的有效期:没有活动时间写长期开放，有就写时间', () => {
  const { page } = loadPage()
  page._topic = { state: 'ready', list: [{ id: 11, registrationStatus: 2, cmsTopic: { name: '海上方舟' } }] }
  page._activity = { state: 'ready', list: [{ id: 12, registrationStatus: 2, cmsActivity: { name: '夜行', startDate: '2099-12-02 09:00:00' } }] }
  page.rebuildTicketList()
  const byId = Object.fromEntries(page.data.ticketList.map(t => [t.id, t]))
  assert.equal(byId[11].wValidity, '长期开放')
  assert.equal(byId[12].wValidity, '2099年12月2日 09:00')
})

test('V-02 动态码到期前静默续签，旧码不清空、不进 loading', () => {
  const { page, sandbox } = loadPage()
  page.data.ticketList = [{ id: 11, wTitle: '海上方舟' }]
  page.data.currentSwiperTicket = 0
  page.onCardTap({ currentTarget: { dataset: { index: 0 } } })
  const now = Date.now()
  sandbox.requests[0].success({ code: 200, data: { qrcodeUrl: 'qr-1', code: 'RAW', expiresAt: now + 300000 } })
  assert.equal(page.data.qrState, 'ready'); assert.equal(page.data.qrUrl, 'qr-1')
  const timer = sandbox.timers.at(-1)
  assert.ok(timer.ms > 250000 && timer.ms <= 270000, '到期前约 30 秒续签:' + timer.ms)
  timer.fn()
  assert.equal(sandbox.requests.length, 2)
  assert.equal(page.data.qrState, 'ready'); assert.equal(page.data.qrUrl, 'qr-1', '续签期间旧码照常出示')
  sandbox.requests[1].success({ code: 200, data: { qrcodeUrl: 'qr-2', expiresAt: Date.now() + 300000 } })
  assert.equal(page.data.qrUrl, 'qr-2')
})

test('V-02 静默续签失败时旧码未过期则稍后重试，不弹错误', () => {
  const { page, sandbox } = loadPage()
  page.data.ticketList = [{ id: 11 }]; page.data.currentSwiperTicket = 0
  page.onCardTap({ currentTarget: { dataset: { index: 0 } } })
  sandbox.requests[0].success({ code: 200, data: { qrcodeUrl: 'qr-1', expiresAt: Date.now() + 300000 } })
  sandbox.timers.at(-1).fn()
  sandbox.requests[1].fail()
  assert.equal(page.data.qrState, 'ready'); assert.equal(page.data.qrUrl, 'qr-1')
  assert.equal(sandbox.timers.at(-1).ms, 5000)
})

test('V-02 翻回正面后不再续签；再翻过来码快过期就重新签发', () => {
  const { page, sandbox } = loadPage()
  page.data.ticketList = [{ id: 11 }]; page.data.currentSwiperTicket = 0
  const tap = () => page.onCardTap({ currentTarget: { dataset: { index: 0 } } })
  tap()
  sandbox.requests[0].success({ code: 200, data: { qrcodeUrl: 'qr-1', expiresAt: Date.now() + 20000 } })
  tap(); assert.equal(page.data.flipped, false)
  sandbox.timers.at(-1).fn(); assert.equal(sandbox.requests.length, 1)
  tap(); assert.equal(sandbox.requests.length, 2)
})
