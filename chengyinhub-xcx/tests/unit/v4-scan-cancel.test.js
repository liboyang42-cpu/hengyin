'use strict'
// SC-SCAN-CANCEL:play 页打开扫码(wx.scanCode)后取消,或只靠定位到达 ——
// 都不得调用完成/核验类接口(扫码核验 /checkin、答题 /answer、玩法会话写入、核销/完成等)。
// 定位到达只允许走到达接口 /api/play/arrive,且只对「到点即完成 vm0 / GPS 到达 vm5」节点。

const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')
const PAGE_JS = path.join(ROOT, 'pages/play/index.js')
const BG_TRACKER = path.join(ROOT, 'utils/location/bg-tracker.js')

// 完成 / 核验类接口:任何一个出现在"取消扫码"或"仅定位到达"的请求里都算违规
const COMPLETION_OR_VERIFY = /\/(checkin|answer|verify|redeem|complete|finish|submit|scan|game|evidence|proof)/i

function loadPage() {
  const original = { getApp: global.getApp, Page: global.Page, wx: global.wx }
  let definition
  const requests = []
  const platform = {
    scans: [], toasts: [], locationHandlers: [],
    scanCode(options) { this.scans.push(options) },
    showModal() {}, showLoading() {}, hideLoading() {},
    showToast(options) { this.toasts.push(options) },
    vibrateShort() {}, navigateTo() {}, nextTick(fn) { fn() },
    onLocationChange(handler) { this.locationHandlers.push(handler) },
    offLocationChange() {},
    getStorageSync: () => '', setStorageSync() {}, removeStorageSync() {},
  }
  const app = { sendRequest(options) { requests.push(options) }, getUserID: () => 9, globalData: {} }
  global.getApp = () => app
  global.Page = (value) => { definition = value }
  global.wx = platform
  const savedTracker = require.cache[BG_TRACKER]
  require.cache[BG_TRACKER] = {
    id: BG_TRACKER, filename: BG_TRACKER, loaded: true,
    exports: {
      acquire(key, opts) { opts && opts.success && opts.success(false) },
      release() {}, revoke() {}, revision: () => 0, observeRevocation() { return () => {} },
      hasOwner: () => true, isBackground: () => false, isRunning: () => true,
    },
  }
  delete require.cache[require.resolve(PAGE_JS)]
  require(PAGE_JS)
  delete require.cache[require.resolve(PAGE_JS)]
  if (savedTracker) require.cache[BG_TRACKER] = savedTracker
  else delete require.cache[BG_TRACKER]
  global.getApp = original.getApp
  global.Page = original.Page
  return { definition, requests, platform, restore: () => { global.wx = original.wx } }
}

function createContext(definition, extra) {
  const context = {
    ...definition,
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, callback) {
      Object.entries(patch).forEach(([key, value]) => {
        if (!key.includes('.')) this.data[key] = value
      })
      if (callback) callback()
    },
  }
  Object.assign(context.data, { topicId: '71', loading: false, isPreview: false, isMock: false }, extra || {})
  context._routeState = { routeMode: 'LINEAR', nodeStates: {} }
  return context
}

const NODE = (vm) => ({ nodeId: 501, name: '第一站', lat: 31.3, lng: 120.7, vm, done: false, arrived: false })

const settle = () => new Promise((resolve) => setImmediate(resolve))

test('扫码到达:打开扫码后取消,不发任何请求、不提示失败、不点亮节点', async () => {
  const harness = loadPage()
  try {
    const node = NODE(4)
    const page = createContext(harness.definition, { nodes: [node], nextNode: { nodeId: 501 }, sheet: { node, show: true } })
    page.onScanArrive()
    assert.equal(harness.platform.scans.length, 1, '必须真的调起 wx.scanCode')
    harness.platform.scans.pop().fail({ errMsg: 'scanCode:fail cancel' })
    await settle()
    assert.deepEqual(harness.requests.map((r) => r.url), [], '取消扫码不能发任何请求')
    assert.equal(harness.platform.toasts.length, 0, '用户主动取消是静默的')
    assert.equal(page.data.nodes[0].done, false)
    assert.equal(page._arrivalWriteInFlight, undefined, '取消不占用到达写锁')
  } finally {
    harness.restore()
  }
})

test('玩家任务扫码凭证:取消扫码不写凭证、不发请求', async () => {
  const harness = loadPage()
  try {
    const page = createContext(harness.definition, {
      gameModule: { nodes: [{ nodeId: 501, playerTask: { inputType: 'SCAN' } }] },
    })
    const evidence = []
    page._setPlayerTaskEvidence = (...args) => evidence.push(args)
    page.scanPlayerTaskEvidence({ currentTarget: { dataset: { node: 501 } } })
    assert.equal(harness.platform.scans.length, 1)
    harness.platform.scans.pop().fail({ errMsg: 'scanCode:fail cancel' })
    await settle()
    assert.equal(evidence.length, 0)
    assert.equal(harness.requests.length, 0)
    assert.equal(harness.platform.toasts.length, 0)
  } finally {
    harness.restore()
  }
})

test('仅定位到达:扫码/答题等需核验节点(vm1-4)走到点旁也不发任何请求', async () => {
  for (const vm of [1, 2, 3, 4]) {
    const harness = loadPage()
    try {
      const node = NODE(vm)
      const page = createContext(harness.definition, { nodes: [node], nextNode: { nodeId: 501 } })
      page.watchLocation()
      assert.equal(harness.platform.locationHandlers.length, 1, 'vm' + vm + ' 必须注册定位监听')
      harness.platform.locationHandlers[0]({ latitude: 31.3, longitude: 120.7, accuracy: 15 })
      await settle()
      assert.deepEqual(harness.requests.map((r) => r.url), [], 'vm' + vm + ' 定位不能替代核验')
      assert.equal(harness.platform.scans.length, 0)
    } finally {
      harness.restore()
    }
  }
})

// O-01(2026-10-23 删空精度放行):定位回调没给精度时后端必拒 ARRIVE_ACCURACY_MISSING,自动到达不发请求
test('仅定位到达:vm0/vm5 节点定位回调缺精度/精度非法时不自动发 /api/play/arrive', async () => {
  for (const vm of [0, 5]) {
    for (const accuracy of [undefined, NaN, -1, 81]) {
      const harness = loadPage()
      try {
        const page = createContext(harness.definition, { nodes: [NODE(vm)], nextNode: { nodeId: 501 } })
        page.watchLocation()
        harness.platform.locationHandlers[0]({ latitude: 31.3, longitude: 120.7, accuracy })
        await settle()
        assert.deepEqual(harness.requests.map((r) => r.url), [], 'vm' + vm + ' accuracy=' + accuracy)
      } finally {
        harness.restore()
      }
    }
  }
})

test('仅定位到达:vm0/vm5 节点只调用到达接口 /api/play/arrive,不调用任何完成/核验接口', async (t) => {
  // 到达收尾里的震动/飞字/动画是纯展示 setTimeout,冻结掉,避免在测试结束后访问已复原的 wx
  t.mock.timers.enable({ apis: ['setTimeout'] })
  for (const vm of [0, 5]) {
    const harness = loadPage()
    try {
      const node = NODE(vm)
      const page = createContext(harness.definition, { nodes: [node], nextNode: { nodeId: 501 } })
      page.watchLocation()
      harness.platform.locationHandlers[0]({ latitude: 31.3, longitude: 120.7, accuracy: 15 })
      await settle()
      // 基础写入先只读原局身份(GET /api/play/action-context),绑定后才发到达写
      const context = harness.requests[0]
      assert.equal(context && context.url, '/api/play/action-context', 'vm' + vm)
      assert.equal(context.method, 'GET')
      context.success({ code: 200, data: { routeSessionId: '77', expectedRouteVersion: 0 } })
      await settle()
      const urls = harness.requests.map((r) => r.url)
      assert.deepEqual(urls, ['/api/play/action-context', '/api/play/arrive'], 'vm' + vm)
      assert.equal(urls.some((url) => COMPLETION_OR_VERIFY.test(url)), false)
      assert.equal(harness.platform.scans.length, 0, '定位到达不能顺带调起扫码')
      // 到达回执落地后,本地收尾也不得再补发完成/核验写(服务端终态回执带 intentTerminal)
      harness.requests[1].success({ code: 200, intentTerminal: true, data: { nodeId: 501 } })
      await settle()
      assert.equal(page.data.nodes[0].done, true, 'vm' + vm + ' 回执已走到本地完成收尾(onComplete 未抛错)')
      const after = harness.requests.slice(2).map((r) => r.url)
      assert.equal(after.some((url) => COMPLETION_OR_VERIFY.test(url)), false, 'vm' + vm + ' 回执后补发了: ' + after.join(','))
      // 远处(>50m)的定位点不触发任何写
      const far = loadPage()
      try {
        const farPage = createContext(far.definition, { nodes: [NODE(vm)], nextNode: { nodeId: 501 } })
        farPage.watchLocation()
        far.platform.locationHandlers[0]({ latitude: 31.31, longitude: 120.7 })
        await settle()
        assert.equal(far.requests.length, 0)
      } finally {
        far.restore()
      }
    } finally {
      harness.restore()
    }
  }
})
