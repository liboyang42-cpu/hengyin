const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ROOT = path.resolve(__dirname, '../..')
const PAGE = 'pages/publish/activity/index.js'
const ID_OK = '11010519491231002X' // 沿用既有 GB11643 测试号
function harness(prices = [0]) {
  let member = 42
  let definition
  const requests = []
  const toasts = []
  const app = { globalData: {},
    getUserID: () => member,
    sendRequest: (req) => requests.push(req),
  }
  global.getApp = () => app
  global.wx = { getStorageSync() {}, setStorageSync() {},
    showToast: (o) => toasts.push(o.title), showLoading() {}, hideLoading() {},
    pageScrollTo() {}, nextTick: (cb) => cb(),
  }
  vm.runInNewContext(fs.readFileSync(path.join(ROOT, PAGE), 'utf8'), {
    getApp: () => app, wx: global.wx,
    Page: (config) => { definition = config },
    require: (id) => require(path.resolve(ROOT, path.dirname(PAGE), id)),
    setTimeout, clearTimeout,
  }, { filename: PAGE })
  const page = Object.assign({}, definition)
  page.data = JSON.parse(JSON.stringify(definition.data))
  page.data.accessState = 'ready'
  page.data.step = 2
  page.data.selectedCategoryIds = [1]
  Object.assign(page.data.formData, { name: '夜行', addressName: '江边',
    description: '沿江活动', startDate: '2026-10-01 09:00:00',
    endDate: '2026-10-01 10:00:00', templateId: 5, imgUrl: 'cover.jpg',
    tickets: prices.map((price, i) => ({ name: '票' + i, price, totalStock: 10 })),
  })
  page.setData = (patch, callback) => {
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.')
      const leaf = parts.pop()
      let target = page.data
      for (const part of parts) target = target[part]
      target[leaf] = value
    }
    if (callback) callback.call(page)
  }
  return { page, requests, toasts, setMember: (id) => { member = id },
    req: (url) => requests.find((request) => request.url === url),
    business: () => requests.filter((req) => /\/api\/activity\/(publish|update)/.test(req.url)),
  }
}
function fillIdentity(page) {
  Object.assign(page.data, { identityRealName: '王舟', identityIdCard: ID_OK, identityConsented: true })
}
test('新免费主理人发布零价活动直接落业务单，不查实名、不上传PII', () => {
  const h = harness([0, '0.00'])
  h.page.submitForm()
  assert.equal(h.requests.length, 1)
  assert.equal(h.business().length, 1)
  assert.equal(h.req('/api/publisher/identity'), undefined)
  assert.doesNotMatch(h.business()[0].data, /realName|idCard|identityConsent/)
})
test('多票任一正价恢复采集，空实名不登记也不发布，改回0又直接走', () => {
  const h = harness([0, 1])
  h.page.submitForm()
  assert.equal(h.page.data.identityRequired, true)
  assert.equal(h.page.data.identityReady, false)
  assert.equal(h.business().length, 0)
  assert.equal(h.req('/api/publisher/identity'), undefined)
  h.page.data.formData.tickets[1].price = 0
  h.page.submitForm()
  assert.equal(h.page.data.identityRequired, false)
  assert.equal(h.business().length, 1)
})
test('正价活动先登记，连点只发一笔登记，成功后按原workflow发布且PII隔离', () => {
  const h = harness([1])
  fillIdentity(h.page)
  h.page.submitForm()
  h.page.submitForm()
  assert.equal(h.requests.filter((req) => req.url === '/api/publisher/identity').length, 1)
  assert.equal(h.business().length, 0)
  const identity = h.req('/api/publisher/identity')
  assert.deepEqual(Object.keys(JSON.parse(identity.data)).sort(), ['consent', 'idCard', 'realName', 'source'])
  identity.success({ code: 200 })
  identity.complete({ statusCode: 200 })
  assert.equal(h.business().length, 1)
  assert.doesNotMatch(h.business()[0].data, /realName|idCard|identityConsent/)
})
test('status只回registered即可省掉采集；不下发实名字段', () => {
  const h = harness([1])
  h.page.refreshPrimaryActionState()
  h.req('/api/publisher/identity/status').success({ code: 200, data: { registered: true } })
  assert.equal(h.page.data.identityRegistered, true)
  assert.equal(h.page.data.identityReady, true)
  assert.equal(h.page.data.identityRealName, '')
  h.page.submitForm()
  assert.equal(h.business().length, 1)
  assert.equal(h.req('/api/publisher/identity'), undefined)
})
test('登记业务拒绝与HTTP502都留在原页，释放submitting并可原位重试', () => {
  for (const statusOnly of [false, true]) {
    const h = harness([1]); fillIdentity(h.page); h.page.submitForm()
    const first = h.req('/api/publisher/identity')
    if (statusOnly) first.complete({ statusCode: 502 })
    else first.success({ code: 500, msg: '登记被拒' })
    assert.equal(h.page.data.submitting, false)
    assert.ok(h.page.data.submitError)
    assert.equal(h.business().length, 0)
    h.page.submitForm()
    assert.equal(h.requests.filter((req) => req.url === '/api/publisher/identity').length, 2)
  }
})
for (const [name, change] of [
  ['卸载', (h) => h.page.onUnload()],
  ['换账号', (h) => h.setMember(99)],
  ['新身份回读', (h) => { h.page._accessRequestToken = 100 }],
  ['资格失效', (h) => { h.page.data.accessState = 'denied' }],
]) {
  test('登记迟到不能落活动：' + name, () => {
    const h = harness([1]); fillIdentity(h.page); h.page.submitForm()
    h.page.data.submitting = false // 模拟旧页面外部复位，token/账号仍须独立挡住回包
    change(h)
    h.req('/api/publisher/identity').success({ code: 200 })
    assert.equal(h.business().length, 0)
    assert.equal(h.page.data.identityRegistered, false)
  })
}
for (const price of [null, '', -1, NaN, Infinity, false]) {
  test('非法价格不冒充免费、不发业务单：' + String(price), () => {
    const h = harness([price])
    h.page.submitForm()
    assert.equal(h.business().length, 0)
    assert.equal(h.req('/api/publisher/identity'), undefined)
    assert.ok(h.page.data.errors.ticketPrice0)
  })
}
test('清空价格保留未填写态，按钮不可继续；输入0才恢复免费', () => {
  const h = harness([1])
  h.page.onTicketChange({ currentTarget: { dataset: { field: 'price', index: 0 } }, detail: { value: '' } })
  assert.equal(h.page.data.formData.tickets[0].price, '')
  assert.equal(h.page.data.canContinue, false)
  h.page.onTicketChange({ currentTarget: { dataset: { field: 'price', index: 0 } }, detail: { value: '0' } })
  assert.equal(h.page.data.formData.tickets[0].price, 0)
  assert.equal(h.page.data.identityRequired, false)
  assert.equal(h.page.data.canContinue, true)
})
test('商家收费禁令仍在，不能借实名登记打开付费活动', () => {
  const h = harness([1])
  h.page.data.merchantHost = true
  h.page.data.identityRegistered = true
  h.page.submitForm()
  assert.equal(h.requests.length, 0)
  assert.match(h.toasts[0], /票价只能是 0/)
})
test('状态查询迟到不能给新账号登记态', () => {
  const h = harness([1])
  h.page.refreshPrimaryActionState()
  h.setMember(99)
  h.req('/api/publisher/identity/status').success({ code: 200, data: { registered: true } })
  assert.equal(h.page.data.identityRegistered, false)
})
test('票务末屏采集使用共享同意组件和已有输入样式，CTA保留实名准备闸', () => {
  const markup = fs.readFileSync(path.join(ROOT, 'pages/publish/activity/index.wxml'), 'utf8')
  assert.match(markup, /wx:if="\{\{identityRequired\}\}"/)
  assert.match(markup, /<cy-consent-check checked="\{\{identityConsented\}\}"/)
  assert.match(markup, /disabled="\{\{!canContinue \|\| \(identityRequired && !identityReady\)\}\}"/)
})

test('实名输入只接受姓名与证件字段，不能改登记或权限状态', () => {
  const h = harness([1])
  const input = (field, value) => h.page.onActivityIdentityInput({
    currentTarget: { dataset: { field } }, detail: { value },
  })
  input('identityRealName', '王舟')
  input('identityIdCard', ID_OK)
  assert.equal(h.page.data.identityRealName, '王舟')
  assert.equal(h.page.data.identityIdCard, ID_OK)
  input('identityRegistered', true)
  input('merchantHost', true)
  assert.equal(h.page.data.identityRegistered, false)
  assert.equal(h.page.data.merchantHost, false)
})
