'use strict'
// F-L5C-06:分享卡/深链直达的帖文详情与收藏页「帖文」页签同样受当前账号社区读闸约束,闸关时不请求社区数据。
// F-L5C-10:隐私闸在本机已记下明确同意、仅服务端留痕失败时关闭弹窗,留痕待登录后补传。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { createRequire } = require('node:module')

const ROOT = path.resolve(__dirname, '../..')

function wxStub(extra) {
  return Object.assign({
    getSystemInfoSync: () => ({ windowWidth: 375, windowHeight: 812, statusBarHeight: 20, safeAreaInsets: { bottom: 0 } }),
    getMenuButtonBoundingClientRect: () => ({ bottom: 64 }),
    getStorageSync: () => '', setStorageSync() {}, removeStorageSync() {},
    navigateBack() {}, navigateTo() {}, switchTab() {}, showToast() {}, hideToast() {}, showLoading() {}, hideLoading() {},
    stopPullDownRefresh() {}, setNavigationBarColor() {}, setBackgroundColor() {},
    createSelectorQuery: () => ({ select() { return this }, selectViewport() { return this }, boundingClientRect() { return this }, fields() { return this }, scrollOffset() { return this }, exec(cb) { if (cb) cb([null]) } }),
  }, extra || {})
}

function loadDefinition(rel, app, wx, kind) {
  let definition
  const file = path.join(ROOT, rel)
  global.getApp = () => app
  global.wx = wx
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    console, getApp: () => app, getCurrentPages: () => [],
    [kind]: (config) => { definition = config },
    require: (id) => id.includes('community-post-api') ? require('../helpers/community-page-seam') : createRequire(file)(id),
    setTimeout() {}, clearTimeout() {}, setInterval() {}, clearInterval() {}, wx,
  }, { filename: file })
  return definition
}

function page(definition, extra) {
  return Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, done) { Object.assign(this.data, patch); if (done) done.call(this) },
  }, extra || {})
}

const settle = () => new Promise((resolve) => setImmediate(resolve))

function communityApp(readable, flagsReadableAfterLoad) {
  const requests = []
  let open = readable
  return {
    requests,
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getUserID: () => 7, getAvatar: () => '', getNickname: () => '', getPageSize: () => 10,
    getTotalPage: (total, size) => Math.ceil(Number(total || 0) / size),
    getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
    canUseCommunity: () => open,
    loadFeatureFlags: () => { open = !!flagsReadableAfterLoad; return Promise.resolve({}) },
    sendRequest(options) { requests.push(options); return { abort() {} } },
  }
}

test('帖文详情:社区读闸关(拉取开关后仍关)时展示未开放空态,不请求详情与评论', async () => {
  const app = communityApp(false, false)
  const p = page(loadDefinition('pages/square/detail/index.js', app, wxStub(), 'Page'))
  p.onLoad({ id: '77' })
  await settle()
  assert.equal(p.data.detailState, 'closed')
  assert.equal(app.requests.length, 0)
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/square/detail/index.wxml'), 'utf8')
  assert.match(wxml, /detailState === 'closed'[\s\S]*?社区暂未开放/)
})

test('帖文详情:开关拉取后确认开放才请求详情', async () => {
  const app = communityApp(false, true)
  const p = page(loadDefinition('pages/square/detail/index.js', app, wxStub(), 'Page'))
  p.onLoad({ id: '77' })
  await settle()
  assert.ok(app.requests.some((r) => r.url === '/api/creativesquare/info'))
})

test('收藏页:社区读闸关时只留「主题」页签且不请求帖文收藏;开关确认开放后补回页签', async () => {
  const closed = communityApp(false, false)
  const p = page(loadDefinition('pages/mylike/mylike.js', closed, wxStub(), 'Page'))
  p.onLoad({})
  await settle()
  assert.equal(p.data.favoriteTabs.map((t) => t.key).join(','), 'topics')
  assert.equal(p.data.activeFavoriteTab, 'topics')
  assert.equal(closed.requests.some((r) => /bookmark/.test(r.url)), false)

  const later = communityApp(false, true)
  const q = page(loadDefinition('pages/mylike/mylike.js', later, wxStub(), 'Page'))
  q.onLoad({})
  await settle()
  assert.equal(q.data.favoriteTabs.map((t) => t.key).join(','), 'posts,topics')
  assert.equal(q.data.activeFavoriteTab, 'topics', '补回页签不抢当前页签')
})

test('隐私闸:本机已记下同意、仅留痕失败时关闭弹窗;本机未记下时停在弹窗', async () => {
  for (const granted of [true, false]) {
    const toasts = []
    const app = {
      agreeUsageConsent: () => Promise.reject(new Error('offline')),
      getUsageConsent: () => ({ granted: () => granted }),
    }
    const def = loadDefinition('components/cy/privacy-gate/index.js', app,
      wxStub({ showToast: (o) => toasts.push(o.title) }), 'Component')
    const events = []
    const c = page(def, def.methods)
    c.triggerEvent = (name, detail) => events.push({ name, detail })
    c.setData({ serviceAgreed: true })
    c.agreePrivacy({ detail: { buttonId: 'agree' } })
    await settle()
    assert.equal(c.data.submitting, false)
    assert.equal(JSON.stringify(events),
      granted ? JSON.stringify([{ name: 'settled', detail: { event: 'agree', recordPending: true } }]) : '[]')
  }
})
