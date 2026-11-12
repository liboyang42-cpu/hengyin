'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('../helpers/ui-sandbox-vm.js')
const { createRequestClient } = require('../../utils/transport/request-client.js')
const messages = require('../../utils/transport/safe-user-message.js')
const ROOT = path.resolve(__dirname, '../..')

// Capture production app predicates without launching its session/network lifecycle.
function appPredicates() {
  let definition
  vm.runInNewContext(fs.readFileSync(path.join(ROOT, 'app.js'), 'utf8'), {
    App(value) { definition = value },
    require(id) { return id.endsWith('/safe-user-message.js') ? messages : {} },
  }, { filename: 'app.js' })
  return definition
}

function harness(relative, code, msg, options = {}) {
  const effects = { requests: [], toasts: [], navigation: [], login: 0, retries: 0 }
  const predicates = appPredicates()
  const app = {
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getRequestErrorMessage: predicates.getRequestErrorMessage,
  }
  const client = createRequestClient({
    getBaseUrl: () => 'https://example.invalid',
    getUserID: () => options.guest ? '' : '7',
    getAuthorization: () => options.guest ? '' : 'token',
    isPlayerView: () => true,
    reLogin() { effects.login += 1 },
    promptLogin() { effects.login += 1; return Promise.resolve(false) },
    scheduleRetry() { effects.retries += 1 },
    shouldAutoToast: predicates.shouldAutoToastRequestError,
    getErrorMessage: app.getRequestErrorMessage,
    showToast(text) { effects.toasts.push(text) },
    wxRequest(request) {
      effects.requests.push({ url: request.url, method: request.method, data: request.data })
      const response = { statusCode: 200, data: { code, msg } }
      request.success(response)
      request.complete(response)
    },
  })
  app.sendRequest = client.send
  let definition
  const file = path.join(ROOT, relative)
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    getApp: () => app,
    Page(value) { definition = value },
    require(id) {
      if (id.endsWith('/shelf/art.js')) return { topicArt: () => null }
      if (id.endsWith('/theme-records.js')) return { data: {}, methods: () => ({}) }
      if (id.endsWith('/map-style.js')) return { lightMapStyle: () => ({}) }
      return require(path.resolve(path.dirname(file), id))
    },
    wx: {
      getStorageSync: () => '',
      getSystemInfoSync: () => ({}),
      navigateBack(value) { effects.navigation.push('back'); if (value.fail) value.fail() },
      switchTab(value) { effects.navigation.push(value.url) },
      showToast(value) { effects.toasts.push(value.title) },
    },
    setTimeout, clearTimeout,
  }, { filename: file })
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch) { Object.assign(this.data, patch) },
    _id: '42',
  })
  page.data.id = '42'
  return { page, app, effects }
}

function plain(value) { return JSON.parse(JSON.stringify(value)) }
function comparison(relative, method, code, msg, guest) {
  const current = harness(relative, code, msg, { guest })
  current.page[method]()
  return current
}

for (const guest of [false, true]) {
  for (const [code, msg] of [[400, '缺少模板ID'], [404, '模板不存在']]) {
    for (const relative of ['pages/templatedetail/topic-preview/index.js', 'pages/templatedetail/topic-detail/index.js']) {
      test(`${relative}: body 500/${code} ${msg} has identical refusal, copy and effects (${guest ? 'guest' : 'member'})`, () => {
        const old = comparison(relative, 'load', 500, msg, guest)
        const next = comparison(relative, 'load', code, msg, guest)
        assert.deepEqual(plain(next.page.data), plain(old.page.data))
        assert.deepEqual(plain(next.effects), plain(old.effects))
        assert.equal(next.page.data.loading, false)
        assert.equal(next.effects.requests.length, 1)
        assert.equal(next.effects.login, 0)
        assert.equal(next.effects.retries, 0)
        assert.equal(next.page.data.errorRefused || Boolean(next.page.data.refusal), true)
        assert.equal(next.page.data.errorMsg || next.page.data.loadErrorMsg, msg)
        old.page.goBack(); next.page.goBack()
        assert.deepEqual(next.effects.navigation, old.effects.navigation)
        assert.deepEqual(next.effects.navigation, ['back', '/pages/template/index'])
      })
    }
  }
  for (const [code, msg] of [[400, '参数不合规'], [404, '主题活动不存在']]) {
    test(`public topic body 500/${code} retains existing loadRefused/back rather than creating it (${guest ? 'guest' : 'member'})`, () => {
      const old = comparison('pages/topic/index/index.js', 'getData', 500, msg, guest)
      const next = comparison('pages/topic/index/index.js', 'getData', code, msg, guest)
      assert.deepEqual(plain(next.page.data), plain(old.page.data))
      assert.deepEqual(plain(next.effects), plain(old.effects))
      assert.equal(next.page.data.loadError, true)
      assert.equal(next.page.data.loadRefused, true)
      assert.equal(next.page.data.loadErrorTitle, msg)
      assert.equal(next.page.data.loadErrorSub, '')
      assert.equal(next.page.data.topicLoaded, false)
      assert.equal(Boolean(next.page.data.loginRequired), false)
      assert.equal(next.effects.requests.length, 1)
      assert.deepEqual(next.effects.toasts, [])
      assert.deepEqual(next.effects.navigation, [])
      assert.equal(next.effects.login, 0)
      assert.equal(next.effects.retries, 0)
    })
  }
}

test('missing-ID preview guard exactly matches old server 500 callback refusal and back action, without the request', () => {
  const old = comparison('pages/templatedetail/topic-preview/index.js', 'load', 500, '缺少模板ID', false)
  const guarded = harness('pages/templatedetail/topic-preview/index.js', 500, '缺少模板ID')
  guarded.page._id = undefined
  guarded.page.load()
  assert.deepEqual(plain(guarded.page.data), plain(old.page.data))
  assert.equal(guarded.effects.requests.length, 0)
  assert.equal(old.effects.requests.length, 1)
  assert.equal(guarded.page.data.errorRefused, true)
  assert.equal(guarded.page.data.errorMsg, '缺少模板ID')
  old.page.goBack(); guarded.page.goBack()
  assert.deepEqual(guarded.effects.navigation, old.effects.navigation)
  assert.deepEqual(guarded.effects.toasts, old.effects.toasts)
  assert.equal(guarded.effects.login, old.effects.login)
  assert.equal(guarded.effects.retries, old.effects.retries)
})
