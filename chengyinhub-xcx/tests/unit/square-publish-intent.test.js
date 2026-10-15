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

const SQUARE_LIST = 'pages/square/list/index.js'
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
    showModal(options) { if (onModal) onModal(options); else options.success({confirm:true}) },
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
  const member = options.member || { value: '7' }
  const app = Object.assign({
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
      if (!id.startsWith('.')) return require(id)
      const absolute = path.resolve(path.dirname(path.join(ROOT, relativePath)), id)
      if (options.apiSource && absolute === path.join(ROOT, 'utils/community-post-api.js')) {
        const module = {exports:{}}
        require('node:vm').runInNewContext(options.apiSource, {module,exports:module.exports,require:require('node:module').createRequire(absolute),console}, {filename:absolute})
        return module.exports
      }
      return require(absolute)
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

// Keep the real client and persistence; only app.sendRequest receives controlled transport replies.
const tick = () => new Promise(resolve => setImmediate(resolve))
const CREATE = '/api/v1/community/posts'
async function awaitCreate(h) {
  await tick()
  let request = h.requests.at(-1)
  if (request && request.url === '/api/v1/community/guidelines/active') {
    request.success({code:200,data:{id:3,contentJson:{rules:[{title:'真实规范'}]}}})
    await tick(); request = h.requests.at(-1)
    assert.equal(request.url, '/api/v1/community/guidelines/ack')
    request.success({code:200,data:{}})
    await tick(); request = h.requests.at(-1)
  }
  assert.ok(request, '必须发起真实创建请求')
  assert.equal(request.url, CREATE)
  assert.equal(request.method, 'POST')
  return request
}
async function publishSuccess(h, create, id) {
  const draft = {post:{...create.data,id,authorId:Number(h.member.value),version:0,lifecycle:'DRAFT'},
    viewerIsAuthor:true,viewerCanComment:true,viewerFollowing:false,media:[],references:create.data.references,topicCodes:[],mentions:[]}
  create.success({code:200,data:draft}); await tick()
  const publish = h.requests.at(-1)
  assert.equal(publish.url, CREATE + '/' + id + '/publish')
  assert.equal(publish.data.expectedVersion, 0)
  assert.match(publish.data.requestId, INTENT_RE)
  publish.success({code:200,data:{...draft,post:{...draft.post,version:1,lifecycle:'CHECKING'}}})
  await tick()
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

function mountSquareList(options = {}) {
  return mount(SQUARE_LIST, 'page', options)
}

function fillListForm(instance, content) {
  instance.data.valCont = content
  instance.data.picList = []
  instance.data.fileList = []
  instance.data.address = ''
  instance.data.longitude = ''
  instance.data.latitude = ''
  instance.data.selectedActivity = null
}

test('广场列表:首次发布携带稳定意图键并持久化', async () => {
  const h = mountSquareList()
  fillListForm(h.instance, '列表正文'); h.instance.submitFormData()
  const request = await awaitCreate(h)
  assert.match(request.data.clientRequestId, INTENT_RE)
  assert.equal(intentStoreFor(h.wx, '7', 'square:list').key, request.data.clientRequestId)
})

test('广场列表:丢回包后重试复用同一完整 wire;成功后相同正文才算新帖', async () => {
  const h = mountSquareList()
  fillListForm(h.instance, '列表正文'); h.instance.submitFormData()
  const first = await awaitCreate(h), firstKey = first.data.clientRequestId
  first.fail({errMsg:'request:fail timeout'}); await tick()
  h.instance.retrySubmit()
  const retry = await awaitCreate(h)
  assert.notEqual(retry, first)
  assert.deepEqual(retry.data, first.data, '未知回包重试必须复用完整创建 wire')
  assert.equal(intentStoreFor(h.wx, '7', 'square:list').key, firstKey)
  await publishSuccess(h, retry, 77)
  assert.equal(intentStoreFor(h.wx, '7', 'square:list'), null)
  fillListForm(h.instance, '列表正文'); h.instance.submitFormData()
  const fresh = await awaitCreate(h)
  assert.notEqual(fresh.data.clientRequestId, firstKey)
})

test('广场列表:未知结果后改内容先核旧wire并保留新输入，禁止新写', async () => {
  const dialogs = [], h = mountSquareList({onModal:options=>{if(options.title==='先核对上次提交')dialogs.push(options);else options.success({confirm:true})}})
  fillListForm(h.instance, '初稿'); h.instance.submitFormData()
  const first = await awaitCreate(h)
  first.fail({errMsg:'request:fail timeout'}); await tick()
  const original = structuredClone(intentStoreFor(h.wx, '7', 'square:list')), count = h.requests.length
  fillListForm(h.instance, '改过的稿'); h.instance.submitFormData(); await tick()
  assert.equal(h.requests.length,count);assert.deepEqual(intentStoreFor(h.wx,'7','square:list'),original);assert.equal(h.instance.data.valCont,'改过的稿')
  dialogs[0].success({confirm:false});await tick();assert.equal(h.requests.length,count)
  h.instance.submitFormData();await tick();dialogs[1].success({confirm:true});await tick()
  const replay=h.requests.at(-1);assert.deepEqual(replay.data,first.data);assert.equal(replay.data.body,'初稿')
  await publishSuccess(h,replay,77);assert.equal(h.instance.data.valCont,'改过的稿');assert.equal(h.instance.data.resultSheet.title,'上次提交已确认')
})

test('广场列表:切账号后不复用上一账号的意图键', async () => {
  const h = mountSquareList()
  fillListForm(h.instance, '同一段正文'); h.instance.submitFormData()
  const first = await awaitCreate(h)
  first.fail({errMsg:'request:fail timeout'}); await tick()
  h.member.value = '8'
  fillListForm(h.instance, '同一段正文'); h.instance.submitFormData()
  const second = await awaitCreate(h)
  assert.notEqual(second.data.clientRequestId, first.data.clientRequestId)
  assert.equal(intentStoreFor(h.wx, '7', 'square:list').key, first.data.clientRequestId)
  assert.equal(intentStoreFor(h.wx, '8', 'square:list').key, second.data.clientRequestId)
})

// ---------------------------------------------------------------- post-compose

function mountCompose(options = {}) {
  const h = mount(POST_COMPOSE, 'component', options)
  h.definition.lifetimes.attached.call(h.instance)
  h.definition.observers.show.call(h.instance, true)
  h.instance.setData({ content: '组件正文', picList: [], address: '', selectedActivity: null })
  return h
}

test('发布组件:丢回包重试复用完整 wire,成功后清除', async () => {
  const h = mountCompose()
  h.instance.onPublish()
  const first = await awaitCreate(h), firstKey = first.data.clientRequestId
  assert.match(firstKey, INTENT_RE)
  first.fail({errMsg:'request:fail timeout'}); await tick()
  h.instance.onPublish()
  const retry = await awaitCreate(h)
  assert.notEqual(retry, first)
  assert.deepEqual(retry.data, first.data)
  await publishSuccess(h, retry, 9)
  assert.equal(intentStoreFor(h.wx, '7', 'square:compose'), null)
  h.instance.setData({content:'组件正文'})
  h.instance.onPublish()
  assert.notEqual((await awaitCreate(h)).data.clientRequestId, firstKey)
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
  assert.equal(intentStoreFor(h.wx, '7', 'square:roam:44').status, 'unknown')
  assert.equal(h.instance._squarePostUnknown, true, '结果未知必须挂起,不假成功')

  // 用户明确选择再次尝试(清掉待核对挂起) → 同一未决意图复用键
  h.instance._squarePostUnknown = false
  const retryFlow = h.instance._shareToSquare()
  await flush(); await flush(); await flush()
  const retry = last(h.requests, '/api/creativesquare/action')
  assert.equal(retry.data.request_id, firstKey, '同一未决分享重试必须复用键')
  retry.success({ code: '200', data: { id: 45 } })
  await retryFlow
  assert.equal(intentStoreFor(h.wx, '7', 'square:roam:44'), null, '成功后清除')

  // 主动再次分享:意图已结算 → 新键=新帖
  const secondFlow = h.instance._shareToSquare()
  await flush(); await flush(); await flush()
  const second = last(h.requests, '/api/creativesquare/action')
  assert.notEqual(second.data.request_id, firstKey, '主动再次分享是新帖')
  second.success({ code: '200', data: { id: 46 } })
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
  assert.notEqual(next.key, first.key, '重新发布必须换新键,不能被软删行占键')
})

for (const component of [false, true]) {
  test((component ? '发布组件' : '广场列表') + ':已删除或移除回读拒绝不得自动丢键复活', async () => {
    const h = component ? mountCompose() : mountSquareList()
    const scope = component ? 'square:compose' : 'square:list'
    if (!component) fillListForm(h.instance, '已删帖正文')
    const publish = () => component ? h.instance.onPublish() : h.instance.submitFormData()
    publish()
    const first = await awaitCreate(h), key = first.data.clientRequestId
    // The canonical contract has no intentDiscarded flag. A rejected request cannot prove a new post is safe.
    first.success({code:404,msg:'该帖文已不可访问'}); await tick()
    assert.equal(intentStoreFor(h.wx, '7', scope).key, key)
    assert.equal(component ? h.instance.data.content : h.instance.data.valCont, component ? '组件正文' : '已删帖正文')
    publish()
    const retry = await awaitCreate(h)
    assert.deepEqual(retry.data, first.data, '不得通过自动换键绕过终态')
    retry.success({code:409,msg:'当前内容不能发布'}); await tick()
    assert.equal(intentStoreFor(h.wx, '7', scope).key, key)
    assert.equal(h.requests.filter(r => /\/publish$/.test(r.url)).length, 0)
  })
}

// ---------------------------------------------------------------- 负控

test('负控:真实创建 wire 去掉意图键时必须判红', async () => {
  const source = read('utils/community-post-api.js')
  const mutated = source.replace('body.clientRequestId = ctx.key;', 'delete body.clientRequestId;')
  assert.notEqual(mutated, source, '负控锚点必须命中实际 helper 创建路径')
  const h = mountSquareList({apiSource:mutated})
  fillListForm(h.instance, '列表正文'); h.instance.submitFormData()
  const request = await awaitCreate(h)
  assert.equal(request.data.clientRequestId, undefined)
  assert.throws(() => assert.match(request.data.clientRequestId, INTENT_RE), assert.AssertionError)
})

test('负控:意图键不再是稳定复用(每次新键)时必须判红', () => {
  const source = read(INTENT)
  const mutated = source.replace(
    "  if (existing && KEY_RE.test(String(existing.key)) && existing.payloadKey === pk && existing.status !== 'success') {",
    '  if (false) {')
  assert.notEqual(mutated, source, '负控锚点失效:未找到复用条件')
  const h = mount(SQUARE_LIST, 'page', { source: read(SQUARE_LIST) })
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

for (const drift of [false, true]) {
  test('广场列表:发布回包丢失后先读真实草稿，' + (drift ? '内容漂移拒绝盲重发' : '未变才复用原 CAS 命令'), async () => {
    const h = mountSquareList()
    fillListForm(h.instance, '需要确认的稿'); h.instance.submitFormData()
    const create = await awaitCreate(h)
    const draft = {post:{...create.data,id:77,authorId:7,version:0,lifecycle:'DRAFT'},
      viewerIsAuthor:true,viewerCanComment:true,viewerFollowing:false,media:[],references:[],topicCodes:[],mentions:[]}
    create.success({code:200,data:draft}); await tick()
    const originalPublish = h.requests.at(-1)
    assert.equal(originalPublish.url, CREATE + '/77/publish')
    originalPublish.fail({errMsg:'request:fail timeout'}); await tick()
    const beforeRetryCount = h.requests.length
    h.instance.retrySubmit(); await tick()
    const readback = h.requests.at(-1)
    assert.equal(h.requests.length, beforeRetryCount + 1)
    assert.equal(readback.url, CREATE + '/77')
    assert.equal(readback.method, 'GET')
    readback.success({code:200,data: drift ? {...draft,post:{...draft.post,version:1,body:'另一处已改正文'}} : draft})
    await tick()
    if (drift) {
      assert.equal(h.requests.length, beforeRetryCount + 1, '内容漂移必须阻止第二次写入')
      assert.match(h.instance.data.submitError, /内容已变化/)
      assert.equal(h.instance.data.valCont, '需要确认的稿')
      assert.equal(intentStoreFor(h.wx,'7','square:list').key, create.data.clientRequestId)
    } else {
      const retry = h.requests.at(-1)
      assert.equal(retry.url, originalPublish.url)
      assert.deepEqual(retry.data, originalPublish.data, 'expectedVersion 与 requestId 都不可漂移')
      assert.equal(h.requests.filter(r => r.url === CREATE).length, 1, '不能重新创建第二个草稿')
      retry.success({code:200,data:{...draft,post:{...draft.post,version:1,lifecycle:'CHECKING'}}}); await tick()
      assert.equal(intentStoreFor(h.wx,'7','square:list'), null)
    }
  })
}
