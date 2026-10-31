'use strict'

// R10-03 发布意图键:三个真实发布入口(list / post-compose / roam)丢回包重试、冷恢复、
// 改内容换意图、账号隔离;负控证明门禁能红。真实 Page/Component + 请求桩,存储桩按账号隔离。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('../helpers/ui-sandbox-vm.js')

const ROOT = path.resolve(__dirname, '../..')
const read = (relativePath) => fs.readFileSync(path.join(ROOT, relativePath), 'utf8')

const POST_COMPOSE = 'pages/square/components/cy/post-compose/index.js'
const ROAM = 'pages/roam/index.js'
const INTENT = 'utils/publish/publish-intent.js'

const INTENT_RE = /^[A-Za-z0-9_-]{16,64}$/
const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

function makeWx(onModal) {
  const store = {}
  return {
    _store: store,
    getStorageSync(key) { return store[key] === undefined ? '' : store[key] },
    setStorageSync(key, value) { store[key] = value },
    removeStorageSync(key) { delete store[key] },
    showToast() {}, hideToast() {}, showLoading() {}, hideLoading() {},
    showModal(options) { if (onModal) onModal(options) },
    getWindowInfo: () => ({ windowWidth: 375, windowHeight: 667, safeArea: { top: 20, bottom: 647 } }),
    getSystemInfoSync: () => ({ windowWidth: 375, windowHeight: 667, safeArea: { top: 20, bottom: 647 } }),
    getMenuButtonBoundingClientRect: () => ({ top: 24, bottom: 56, left: 278, right: 365 }),
    navigateTo() {}, navigateBack() {}, reLaunch() {}, switchTab() {}, setNavigationBarTitle() {},
    stopPullDownRefresh() {}, startPullDownRefresh() {},
  }
}

function setByPath(target, keyPath, value) {
  const parts = keyPath.split('.')
  let cursor = target
  parts.slice(0, -1).forEach((part) => {
    if (!cursor[part] || typeof cursor[part] !== 'object') cursor[part] = {}
    cursor = cursor[part]
  })
  cursor[parts[parts.length - 1]] = value
}

// 真实 Page/Component 定义 + 请求桩;require 走 node:vm 版 ui-sandbox-vm(toast/loading/modal 装真模块)。
function mount(relativePath, kind, options = {}) {
  const requests = []
  const wx = makeWx(options.onModal)
  const member = options.member || { value: 'member-A' }
  const app = Object.assign({
    canUseCommunity: () => true, // Current-account server-open fixture.
    globalData: {},
    getUserID: () => member.value,
    getAvatar: () => '',
    getNickname: () => '',
    getPageSize: () => 10,
    getUserRole: () => 'player',
    isDevEnv: () => false,
    sendRequest(request) { requests.push(request) },
    tips() {},
    getRequestErrorMessage: (res, fallback) => (res && (res.msg || res.errMsg)) || fallback,
  }, options.app || {})
  global.getApp = () => app
  let definition
  const sandbox = {
    getApp: () => app,
    getCurrentPages: () => [{}],
    wx,
    setInterval() { return 1 },
    clearInterval() {},
    setTimeout(fn) { if (typeof fn === 'function') fn(); return 1 },
    clearTimeout() {},
    require(id) {
      if (id.includes('community-post-api')) return require('../helpers/community-page-seam');
      if (!id.startsWith('.')) return require(id)
      return require(path.resolve(path.dirname(path.join(ROOT, relativePath)), id))
    },
    Page(value) { definition = value },
    Component(value) { definition = value },
    console, Date, JSON, Object, Array, Number, String, Math, Promise, isFinite, isNaN,
  }
  vm.runInNewContext(options.source || read(relativePath), sandbox, { filename: relativePath })

  const methods = kind === 'component' ? definition.methods : definition
  const instance = {
    data: JSON.parse(JSON.stringify(definition.data || {})),
    setData(patch, callback) {
      Object.entries(patch).forEach(([key, value]) => setByPath(this.data, key, value))
      if (callback) callback.call(this)
    },
    triggerEvent() {},
  }
  if (kind === 'component') {
    Object.keys(definition.properties || {}).forEach((key) => {
      const prop = definition.properties[key]
      instance.data[key] = prop && prop.value
    })
  }
  Object.entries(methods).forEach(([name, fn]) => { if (typeof fn === 'function') instance[name] = fn.bind(instance) })
  return { instance, requests, wx, member, app, definition }
}

function last(requests, url) {
  return requests.filter((r) => r.url === url).pop()
}

function intentStoreFor(wx, memberId, scope) {
  return wx._store['square_publish_intent_v1:' + memberId + ':' + scope] || null
}

// ---------------------------------------------------------------- 纯 util

test('R10-03 util:同 payload 未决意图复用键,成功清除后重新发布才是新键', () => {
  const intent = require(path.join(ROOT, INTENT))
  const wx = makeWx()
  const scope = 'square:list'
  const payload = ['正文', 'pic', '', '', '', '', 2, '', '', '']

  const first = intent.begin(wx, { scope, memberId: 'A', payload })
  assert.match(first.key, INTENT_RE)
  assert.equal(first.reused, false)

  const retry = intent.begin(wx, { scope, memberId: 'A', payload })
  assert.equal(retry.key, first.key, '同 payload 未决重试必须复用键')
  assert.equal(retry.reused, true)

  intent.settle(wx, scope, 'A', first.key, 'success')
  assert.equal(intentStoreFor(wx, 'A', scope), null, '成功后意图清除')

  const second = intent.begin(wx, { scope, memberId: 'A', payload })
  assert.notEqual(second.key, first.key, '主动再次发布相同内容必须是新意图=新帖')
})

test('R10-03 util:改 payload 换新键并保留前次未知痕迹;账号隔离存储', () => {
  const intent = require(path.join(ROOT, INTENT))
  const wx = makeWx()
  const scope = 'square:list'

  const first = intent.begin(wx, { scope, memberId: 'A', payload: ['正文', 'p1'] })
  intent.settle(wx, scope, 'A', first.key, 'unknown')
  const changed = intent.begin(wx, { scope, memberId: 'A', payload: ['正文', 'p2'] })

  assert.notEqual(changed.key, first.key, '改内容=新意图')
  assert.match(changed.key, INTENT_RE)
  const previous = intentStoreFor(wx, 'A', scope + ':previous')
  assert.ok(previous && previous.key === first.key, '前次未决意图痕迹必须保留')

  const other = intent.begin(wx, { scope, memberId: 'B', payload: ['正文', 'p1'] })
  assert.notEqual(other.key, first.key, '账号隔离:不同账号不同键')
})

// ---------------------------------------------------------------- 广场列表
// 2026-10-27 P-20 owner:广场行内发布框撤掉,新建帖统一走 cy-post-compose(下方「发布组件」用例覆盖意图键);
// 原「广场列表:首次发布 / 丢回包重试 / 改内容换键 / 切账号 / 软删换键」五条随页内发布流水一并撤走。

// 2026-10-29:广场列表页(pages/square/list)已删,mountSquareList 随之撤走。


// ---------------------------------------------------------------- post-compose

function mountCompose(options = {}) {
  const h = mount(POST_COMPOSE, 'component', options)
  h.definition.lifetimes.attached.call(h.instance)
  h.definition.observers.show.call(h.instance, true)
  h.instance.setData({ content: '组件正文', picList: [], address: '', selectedActivity: null })
  return h
}

test('发布组件:丢回包重试复用意图键,成功后清除', () => {
  const h = mountCompose()
  h.instance.onPublish()
  const first = last(h.requests, '/api/creativesquare/action')
  assert.match(first.data.request_id, INTENT_RE)
  const firstKey = first.data.request_id

  first.fail({ errMsg: 'request:fail timeout' })
  first.complete()
  h.instance.onPublish()
  const retry = last(h.requests, '/api/creativesquare/action')
  assert.equal(retry.data.request_id, firstKey, '组件重试必须复用意图键')

  retry.success({ code: '200', data: { id: 9, lifecycle: 'PUBLISHED' } })
  retry.complete()
  assert.equal(intentStoreFor(h.wx, 'member-A', 'square:compose'), null, '成功后清除')

  h.instance.setData({ content: '组件正文' })
  h.instance.onPublish()
  const fresh = last(h.requests, '/api/creativesquare/action')
  assert.notEqual(fresh.data.request_id, firstKey, '再次主动发布=新意图')
})

// ---------------------------------------------------------------- roam 分享

function mountRoam() {
  return mount(ROAM, 'page', {
    onModal(options) {
      if (options && typeof options.success === 'function') options.success({ confirm: true, content: '漫游分享文案' })
    },
  })
}

function readyRoam(h) {
  h.app.canUseCommunity = () => true; // Explicit current-account feature fixture, not a production fallback.
  h.instance._shareScope = () => () => true
  h.instance._shareCopy = () => '漫游分享文案'
  h.instance._activeShareSessionId = () => '44'
  h.instance._drawShareCard = () => Promise.resolve('/tmp/card.png')
  h.instance._uploadOne = () => Promise.resolve('https://oss.example/card.png')
  h.instance._shareLoading = false
  h.instance._squarePostUnknown = false
  h.instance._squarePostInFlight = false
}

test('漫游分享:未知结果重试复用意图键,成功后再次主动分享才是新帖', async () => {
  const h = mountRoam()
  readyRoam(h)

  const firstFlow = h.instance._shareToSquare()
  await flush(); await flush(); await flush()
  const first = last(h.requests, '/api/creativesquare/action')
  assert.ok(first, '必须发起发布请求')
  assert.match(first.data.request_id, INTENT_RE)
  const firstKey = first.data.request_id

  // 传输失败=结果未知:保留同键,不新开意图
  first.fail({ errMsg: 'request:fail timeout' })
  await firstFlow
  assert.equal(intentStoreFor(h.wx, 'member-A', 'square:roam:44').status, 'unknown')
  assert.equal(h.instance._squarePostUnknown, true, '结果未知必须挂起，不假成功')

  // 用户明确选择再次尝试(清掉待核对挂起) → 同一未决意图复用键
  h.instance._squarePostUnknown = false
  const retryFlow = h.instance._shareToSquare()
  await flush(); await flush(); await flush()
  const retry = last(h.requests, '/api/creativesquare/action')
  assert.equal(retry.data.request_id, firstKey, '同一未决分享重试必须复用键')
  retry.success({ code: '200', data: { id: 45, lifecycle: 'PUBLISHED' } })
  await retryFlow
  assert.equal(intentStoreFor(h.wx, 'member-A', 'square:roam:44'), null, '成功后清除')

  // 主动再次分享:意图已结算 → 新键=新帖
  const secondFlow = h.instance._shareToSquare()
  await flush(); await flush(); await flush()
  const second = last(h.requests, '/api/creativesquare/action')
  assert.notEqual(second.data.request_id, firstKey, '主动再次分享是新帖')
  second.success({ code: '200', data: { id: 46, lifecycle: 'PUBLISHED' } })
  await secondFlow
})

// ---------------------------------------------------------------- 收尾:冷恢复/软删终态

test('R10-03 util:进程被杀后的 sending 冷恢复必须提示并复用同一键', () => {
  const intent = require(path.join(ROOT, INTENT))
  const wx = makeWx()
  const scope = 'square:list'
  const payload = ['正文', 'p']

  const first = intent.begin(wx, { scope, memberId: 'A', payload })
  assert.equal(intentStoreFor(wx, 'A', scope).status, 'sending', '请求发出后存储态是 sending')

  // 进程被杀:没有任何 settle;冷启动同 payload 再来一次
  const cold = intent.begin(wx, { scope, memberId: 'A', payload })
  assert.equal(cold.key, first.key, 'sending 冷恢复不得盲目新键重发')
  assert.equal(cold.reused, true)
  assert.equal(cold.previousUnknown, true, '未确认的 sending 必须给出可恢复提示')
})

test('R10-03 util:软删终态 discard 丢键,下一次同 payload 换新键', () => {
  const intent = require(path.join(ROOT, INTENT))
  const wx = makeWx()
  const scope = 'square:list'
  const payload = ['已删帖正文', 'p']

  const first = intent.begin(wx, { scope, memberId: 'A', payload })
  intent.settle(wx, scope, 'A', first.key, 'rejected')
  intent.discard(wx, scope, 'A', first.key)

  assert.equal(intentStoreFor(wx, 'A', scope), null, 'discard 后未决键清除')
  const trace = intentStoreFor(wx, 'A', scope + ':previous')
  assert.ok(trace && trace.key === first.key && trace.status === 'discarded', '旧键留痕但不复用')

  const next = intent.begin(wx, { scope, memberId: 'A', payload })
  assert.notEqual(next.key, first.key, '重新发布必须换新键，不能被软删行占键')
})

test('发布组件:服务端软删终态回执丢键并换新键重发', () => {
  const h = mountCompose()
  h.instance.onPublish()
  const first = last(h.requests, '/api/creativesquare/action')
  const firstKey = first.data.request_id

  first.success({ code: 500, msg: '该发布已删除，请重新发布', intentDiscarded: true })
  first.complete()
  assert.equal(intentStoreFor(h.wx, 'member-A', 'square:compose'), null, '软删终态必须丢弃意图键')

  h.instance.onPublish()
  const next = last(h.requests, '/api/creativesquare/action')
  assert.notEqual(next.data.request_id, firstKey, '重新发布必须换新键')
})

// ---------------------------------------------------------------- 负控

test('负控:发布组件去掉意图键时必须判红', () => {
  const source = read(POST_COMPOSE)
  const mutated = source.replace('        payload.request_id = intent.key;\n', '')
  assert.notEqual(mutated, source, '负控锚点失效:未找到 request_id 写入')
  const h = mount(POST_COMPOSE, 'component', { source: mutated })
  h.definition.lifetimes.attached.call(h.instance)
  h.definition.observers.show.call(h.instance, true)
  h.instance.setData({ content: '组件正文', picList: [], address: '', selectedActivity: null })
  h.instance.onPublish()
  const request = last(h.requests, '/api/creativesquare/action')
  assert.equal(request.data.request_id, undefined, '去掉写入后不得携带意图键')
})

test('负控:意图键不再是稳定复用(每次新键)时必须判红', () => {
  const source = read(INTENT)
  const mutated = source.replace(
    "  if (existing && KEY_RE.test(String(existing.key)) && existing.payloadKey === pk && existing.status !== 'success') {",
    '  if (false) {')
  assert.notEqual(mutated, source, '负控锚点失效:未找到复用条件')
  // 用变异后的 util 源替换真实模块:直接在独立沙箱里验证同 payload 两次 begin 不再同键
  let sandboxModule = null
  const sandbox = {
    module: { exports: {} }, exports: {}, console, JSON, Date, Object, Array, String, Math,
  }
  sandbox.module.exports = sandbox.exports
  vm.runInNewContext(mutated, sandbox, { filename: INTENT })
  sandboxModule = sandbox.module.exports
  const wx = makeWx()
  const first = sandboxModule.begin(wx, { scope: 'x', memberId: 'A', payload: ['p'] })
  const second = sandboxModule.begin(wx, { scope: 'x', memberId: 'A', payload: ['p'] })
  assert.notEqual(first.key, second.key, '变异体确实每次新键')
  assert.throws(() => assert.equal(first.key, second.key))
})
