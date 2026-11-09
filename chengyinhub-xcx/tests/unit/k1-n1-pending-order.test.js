const { beforeEach, test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const PAGE_PATH = '../../pages/activity/baoming/baoming.js'

let config
let requests
let requestTasks
let navigation
let pageStackDepth

beforeEach(() => {
  config = null
  requests = []
  requestTasks = []
  navigation = []
  pageStackDepth = 1

  global.getApp = () => ({
    globalData: { user_id: 9, features: {} },
    getUserID: () => 9,
    isDevEnv: () => false,
    tips() {},
    goBack() { navigation.push({ type: 'back' }) },
    recordConsent: () => Promise.resolve(),
    sendRequest(options) {
      requests.push(options)
      const task = {
        aborted: false,
        abort() { this.aborted = true },
      }
      requestTasks.push(task)
      return task
    },
  })
  global.getCurrentPages = () => Array.from({ length: pageStackDepth }, () => ({}))
  global.Page = value => { config = value }
  global.wx = {
    getStorageSync: () => '',
    setStorageSync() {},
    removeStorageSync() {},
    getWindowInfo: () => ({ statusBarHeight: 20 }),
    getSystemInfoSync: () => ({ statusBarHeight: 20 }),
    showToast() {},
    showModal() {},
    showLoading() {},
    hideLoading() {},
    navigateBack(options = {}) {
      navigation.push({ type: 'back' })
    },
    redirectTo(options) { navigation.push({ type: 'redirect', url: options.url }) },
    navigateTo(options) { navigation.push({ type: 'navigate', url: options.url }) },
  }
})

function setByPath(target, path, value) {
  const parts = path.split('.')
  let cursor = target
  parts.slice(0, -1).forEach((part) => {
    if (!cursor[part] || typeof cursor[part] !== 'object') cursor[part] = {}
    cursor = cursor[part]
  })
  cursor[parts[parts.length - 1]] = value
}

function loadPage(source) {
  if (source) {
    // B-08 负控:按变异源码建模块,不污染 require 缓存
    const Module = require('node:module')
    const modulePath = path.resolve(__dirname, PAGE_PATH)
    const m = new Module(modulePath, module)
    m.filename = modulePath
    m.paths = Module._nodeModulePaths(path.dirname(modulePath))
    m._compile(source, modulePath)
  } else {
    delete require.cache[require.resolve(PAGE_PATH)]
    require(PAGE_PATH)
  }
  const page = Object.assign({}, config)
  page.data = JSON.parse(JSON.stringify(config.data))
  page.setData = function (patch, callback) {
    Object.entries(patch).forEach(([path, value]) => setByPath(this.data, path, value))
    if (callback) callback()
  }
  return page
}


// 第十二轮 k1 第 5 项(j6 N1):关掉支付面板后取消没落库 → 带玩家去那笔订单;服务端拒第二笔 → 带去那一笔。
test('j6 N1:关支付面板后取消被拒,去那笔订单看现状;取消成功照旧返回', () => {
  const page = loadPage()
  page.data.orderInfo = { registrationId: 77 }
  page.cancelPendingOrder({ openOrderWhenStuck: true })
  assert.equal(requests.length, 1)
  assert.equal(requests[0].url, '/api/registration/cancel')
  requests[0].success({ code: 409, msg: '支付结果确认中，请稍后再试' })
  assert.deepEqual(navigation, [{ type: 'redirect', url: '/subpackageMember/orderinfo/orderinfo?id=77&from=signup' }])

  navigation.length = 0
  const ok = loadPage()
  ok.data.orderInfo = { registrationId: 78 }
  ok._reqId = 'old-key'
  ok.cancelPendingOrder({ openOrderWhenStuck: true })
  requests[1].success({ code: 200 })
  assert.equal(ok._reqId, null, '取消落库才换幂等键')
  assert.deepEqual(navigation, [{ type: 'back' }])

  navigation.length = 0
  const net = loadPage()
  net.data.orderInfo = { registrationId: 79 }
  net.cancelPendingOrder({ openOrderWhenStuck: true })
  requests[2].fail({ errMsg: 'request:fail timeout' })
  assert.deepEqual(navigation, [{ type: 'redirect', url: '/subpackageMember/orderinfo/orderinfo?id=79&from=signup' }])
})

test('j6 N1:支付失败留在本页时不跳转', () => {
  const page = loadPage()
  page.data.orderInfo = { registrationId: 80 }
  page.cancelPendingOrder({ stayOnPage: true })
  requests[0].success({ code: 409 })
  assert.deepEqual(navigation, [])
})

test('j6 N1:服务端说这一场已有待支付的单,带去那一笔订单', () => {
  const page = loadPage()
  page.data.isPaying = true
  page.openOrderForPendingConflict(77, '这一场你还有一笔订单没有完成支付')
  assert.equal(page.data.isPaying, false)
  assert.deepEqual(navigation, [{ type: 'redirect', url: '/subpackageMember/orderinfo/orderinfo?id=77&from=signup' }])
  const source = fs.readFileSync(path.join(__dirname, PAGE_PATH), 'utf8')
  const create = source.slice(source.indexOf("url: '/api/registration/create'"))
  assert.ok(create.indexOf('pendingOrderRedirectId(res)') > 0
    && create.indexOf('pendingOrderRedirectId(res)') < create.indexOf("res.code == 409"),
    '先认 PENDING_ORDER_EXISTS,不能被当成「价格已更新」')
  assert.match(source, /errorCode !== 'PENDING_ORDER_EXISTS' && res\.errorCode !== 'ORDER_SUPERSEDED'/)
})
