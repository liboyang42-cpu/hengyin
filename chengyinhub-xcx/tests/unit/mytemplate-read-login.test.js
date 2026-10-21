'use strict'
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const modulePath = path.resolve(__dirname, '../../subpackageMember/mytemplate/mytemplate.js')

function mount(owner = '', token = '') {
  const requests = [], logins = []
  let definition
  const app = {
    getUserID: () => owner, getAuthorization: () => token,
    getUserRole: () => 'user', getUserType: () => 'user',
    getRequestErrorMessage: (r, fallback) => r && (r.msg || r.errMsg) || fallback,
    sendRequest: r => requests.push(r),
    firstLogin: () => new Promise(resolve => logins.push(resolve)),
    reLogin: () => new Promise(resolve => logins.push(resolve)),
  }
  global.getApp = () => app
  global.Page = value => { definition = value }
  global.wx = { getAccountInfoSync: () => ({ miniProgram: { envVersion: 'release' } }),
    setNavigationBarColor() {}, setBackgroundColor() {}, stopPullDownRefresh() {}, showToast() {} }
  delete require.cache[require.resolve(modulePath)]; require(modulePath)
  const page = Object.assign({}, definition, { data: structuredClone(definition.data),
    setData(value, done) { Object.assign(this.data, value); if (done) done.call(this) } })
  return { page, app, requests, logins, identity(id, authorization) { owner = id; token = authorization } }
}
const flush = () => new Promise(resolve => setImmediate(resolve))
const success = (request, rows = [{ id: 7, title: 'Own template' }]) => {
  request.success({ code: 200, data: { rows, total: rows.length } }); request.complete()
}

for (const [owner, token] of [['', ''], ['0', 'Bearer unit'], ['7', ''], ['', 'Bearer unit']]) {
  test(`guest or incomplete session makes no private request (${owner}/${!!token})`, () => {
    const h = mount(owner, token); h.page.onLoad({}); h.page.onShow()
    assert.equal(h.requests.length, 0); assert.equal(h.page.data.readAccess, 'login')
    assert.equal(h.page.data.loading, false); assert.equal(h.page.data.list.length, 0)
  })
}
test('normal player and merchant entries retain scopes and make one initial list read', () => {
  for (const scope of ['', 'MERCHANT']) {
    const h = mount('7', 'Bearer unit'); h.page.onLoad({ scope }); h.page.onShow()
    assert.equal(h.requests.length, 1); assert.equal(h.requests[0].data.scope, scope)
    success(h.requests[0]); assert.equal(h.page.data.list[0].id, 7)
    assert.equal(h.page.data.readAccess, '')
  }
})
test('visible login uses normal single-flight; refusal keeps entry, success reloads current account', async () => {
  const h = mount(); h.page.onLoad({ scope: 'MERCHANT' }); h.page.retryRead(); h.page.retryRead()
  assert.equal(h.logins.length, 1); h.logins[0]({ ok: false }); await flush()
  assert.equal(h.requests.length, 0); assert.equal(h.page.data.readAccess, 'login')
  h.page.retryRead(); h.identity('7', 'Bearer new'); h.logins[1]({ ok: true }); await flush()
  assert.equal(h.requests.length, 1); assert.equal(h.requests[0].data.scope, 'MERCHANT')
  success(h.requests[0]); assert.equal(h.page.data.list[0].id, 7)
})
test('bootstrap completion only reloads a visible current epoch with complete session', async () => {
  const h = mount(); let resolve
  h.app.isAppReady = false; h.app.waitForAppReady = () => new Promise(done => { resolve = done })
  h.page.onLoad({}); h.identity('7', 'Bearer ready'); resolve(); await flush()
  assert.equal(h.requests.length, 1)
  const hidden = mount(); let ready
  hidden.app.isAppReady = false; hidden.app.waitForAppReady = () => new Promise(done => { ready = done })
  hidden.page.onLoad({}); hidden.page.onHide(); hidden.identity('7', 'Bearer ready'); ready(); await flush()
  assert.equal(hidden.requests.length, 0)
})
test('normal login failure keeps retry available without issuing private reads', async () => {
  for (const failure of ['throw', 'reject']) {
    const h = mount(); h.page.onLoad({})
    h.app.firstLogin = () => { if (failure === 'throw') throw new Error('unavailable'); return Promise.reject(new Error('unavailable')) }
    assert.doesNotThrow(() => h.page.retryRead()); await flush()
    assert.equal(h.page.data.readAccess, 'login'); assert.equal(h.page._loginPending, false)
    assert.equal(h.requests.length, 0)
  }
})
test('a login begun for one owner cannot reload a different owner', async () => {
  const h = mount('7', 'Bearer old'); h.page.getList(); h.requests[0].success({ code: 401 }); h.requests[0].complete()
  h.page.retryRead(); h.identity('8', 'Bearer other'); h.logins[0]({ ok: true }); await flush()
  assert.equal(h.requests.length, 1)
})
for (const [branch, response, status, access] of [
  ['success', { code: 401 }, undefined, 'login'], ['success', { code: 2 }, undefined, 'login'],
  ['successStatusAbnormal', { code: 500 }, 401, 'login'],
  ['fail', { code: 500 }, 401, 'login'], ['success', { code: 403 }, undefined, 'permission'],
  ['successStatusAbnormal', { code: 500 }, 403, 'permission'],
]) {
  test(`${branch} ${status || response.code} uses explicit ${access} state, clears private rows`, () => {
    const h = mount('7', 'Bearer unit'); h.page.data.list = [{ id: 3 }]; h.page.data.total = 1
    h.page.getList(); h.requests[0][branch](response, status); h.requests[0].complete()
    assert.equal(h.page.data.readAccess, access); assert.equal(h.page.data.list.length, 0)
    assert.equal(h.page.data.total, 0); assert.equal(h.page.data.loading, false)
  })
}
test('500 and network failures retain existing list and pagination failure behavior', () => {
  const h = mount('7', 'Bearer unit'); h.page.getList(); success(h.requests[0])
  h.page.refreshList(); h.requests[1].success({ code: 500, msg: 'service unavailable' }); h.requests[1].complete()
  assert.equal(h.page.data.readAccess, ''); assert.equal(h.page.data.list[0].id, 7)
  h.page.data.hasMore = true; h.page.loadMore(); h.requests[2].fail({ errMsg: 'offline' }); h.requests[2].complete()
  assert.equal(h.page.data.page, 1); assert.equal(h.page.data.list[0].id, 7)
  assert.equal(h.page.data.loadMoreError, 'offline')
})
test('same-account renewal failure clears private snapshot and permits explicit login', async () => {
  const h = mount('7', 'Bearer unit'); h.page.getList(); success(h.requests[0])
  h.page.refreshList(); h.identity('', ''); h.requests[1].fail({ code: 401 }, 401)
  assert.equal(h.page.data.readAccess, 'login'); assert.equal(h.page.data.list.length, 0)
  h.page.retryRead(); h.identity('7', 'Bearer renewed'); h.logins[0]({ ok: true }); await flush()
  assert.equal(h.requests.length, 3)
})
test('switching owner or token rejects old responses and clears previous private rows', () => {
  for (const [owner, token] of [['8', 'Bearer other'], ['7', 'Bearer replacement']]) {
    const h = mount('7', 'Bearer unit'); h.page.onLoad({}); const old = h.requests[0]
    h.page.data.list = [{ id: 3 }]; h.identity(owner, token); h.page.onShow()
    assert.equal(h.requests.length, 2); assert.equal(h.page.data.list.length, 0)
    success(h.requests[1], [{ id: 8 }]); success(old, [{ id: 3 }]); old.fail({ code: 401 })
    assert.deepEqual(h.page.data.list.map(r => r.id), [8]); assert.equal(h.page.data.readAccess, '')
  }
})
test('hide invalidates both request and login completion, return starts a fresh read', async () => {
  const h = mount('7', 'Bearer unit'); h.page.onLoad({}); h.page.onHide()
  success(h.requests[0]); assert.equal(h.page.data.list.length, 0)
  h.page.onShow(); assert.equal(h.requests.length, 2)
  const guest = mount(); guest.page.onLoad({}); guest.page.retryRead(); guest.page.onHide()
  guest.identity('7', 'Bearer new'); guest.logins[0]({ ok: true }); await flush()
  assert.equal(guest.requests.length, 0); guest.page.onShow(); assert.equal(guest.requests.length, 1)
})
test('login finishing after hide then show cannot trigger an extra read', async () => {
  const h = mount(); h.page.onLoad({}); h.page.retryRead(); h.page.onHide(); h.page.onShow()
  h.identity('7', 'Bearer new'); h.logins[0]({ ok: true }); await flush()
  assert.equal(h.requests.length, 0)
})
test('unload makes list and login late callbacks inert', async () => {
  const h = mount('7', 'Bearer unit'); h.page.onLoad({}); h.page.onUnload(); success(h.requests[0])
  assert.equal(h.page.data.list.length, 0)
  const g = mount(); g.page.onLoad({}); g.page.retryRead(); g.page.onUnload()
  g.identity('7', 'Bearer new'); g.logins[0]({ ok: true }); await flush(); assert.equal(g.requests.length, 0)
})
test('actual page template exposes a clickable login and distinct permission branch', () => {
  const wxml = fs.readFileSync(modulePath.replace('.js', '.wxml'), 'utf8').replace(/<!--[\s\S]*?-->/g, '')
  assert.match(wxml, /wx:if="\{\{readAccess\}\}"[^>]*title="\{\{readAccess === 'login' \? '登录后查看我的节点玩法' : '当前账号无法查看节点玩法'\}\}"[^>]*cta="\{\{readAccess === 'login' \? '登录' : '重试'\}\}"[^>]*bind:cta="retryRead"/s)
})
