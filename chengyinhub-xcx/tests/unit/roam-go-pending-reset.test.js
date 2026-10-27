// 漫游是 tabBar 页,页面实例常驻(切 tab 不 onUnload,小程序热启动也保留实例)。
// goStart 的在途标记 _roamStartPending 若因弹窗/网络异常没被清,之后每次点 GO 和相机都静默 return,
// 且冷启动前无法自愈 —— 实机上表现为「GO 点了完全没反应,重启也没用」。
// onShow 必须把它归零,这是已死锁的实例唯一的自愈点。
const { test } = require('node:test')
const assert = require('node:assert/strict')

// W8 起 onShow 会异步回读服务端护照统计(_loadIntroPassport),其回调仍读 getApp();
// 每个用例在恢复全局桩之前先 await settle(),让这次回读在本用例的桩下收尾。
const PAGE_MODULE = '../../pages/roam/index.js'
const settle = () => new Promise(resolve => setImmediate(resolve))

function loadRoamPage() {
  let definition
  const previous = { Page: global.Page, getApp: global.getApp, getCurrentPages: global.getCurrentPages, wx: global.wx }
  try {
    global.Page = config => { definition = config }
    global.getApp = () => ({ globalData: {} })
    global.getCurrentPages = () => []
    global.wx = { getStorageSync: () => undefined }
    delete require.cache[require.resolve(PAGE_MODULE)]
    require(PAGE_MODULE)
  } finally {
    global.Page = previous.Page
    global.getApp = previous.getApp
    global.getCurrentPages = previous.getCurrentPages
    global.wx = previous.wx
  }
  const page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)) })
  page.setData = function (patch) { Object.assign(this.data, patch) }
  return page
}

test('onShow 清掉 goStart 的在途标记,让已死锁的常驻实例自愈', async () => {
  const page = loadRoamPage()
  const prevGetApp = global.getApp
  global.getApp = () => ({ globalData: {} })
  try {
    page._roamStartPending = true      // 模拟上一次 goStart 卡在途中(弹窗被吞/网络挂起)
    page.onShow()
    assert.equal(page._roamStartPending, false, 'onShow 后必须归零，否则 GO 永久静默')
    await settle()
  } finally {
    global.getApp = prevGetApp
  }
})

test('onShow 一并归零「开完就拍」的意图,别在下次出发时莫名弹相机', async () => {
  const page = loadRoamPage()
  const prevGetApp = global.getApp
  global.getApp = () => ({ globalData: {} })
  try {
    page._shootAfterStart = true     // 上一次是点相机进来的,卡住了
    page.onShow()
    assert.equal(page._shootAfterStart, false, '陈旧的拍照意图必须清掉')
    await settle()
  } finally {
    global.getApp = prevGetApp
  }
})

test('onShow 作废在途流程:旧 chain 的回调弃权,不会开出第二个会话', async () => {
  const page = loadRoamPage()
  const prevGetApp = global.getApp
  const prevWx = global.wx
  let locOpts = null
  let consentCalls = 0
  let openedSessions = 0
  global.wx = {
    getStorageSync: () => undefined,
    setStorageSync: () => {},
    showModal: options => options.success({ confirm: true }),
    getLocation: opts => { locOpts = opts },   // 扣住回调,模拟定位还没回来
  }
  global.getApp = () => ({
    globalData: {},
    getUserID: () => '9',
    recordConsent: () => { consentCalls += 1; return Promise.resolve() },
  })
  page._openRoamMap = () => { openedSessions += 1 }
  try {
    page.onShow()
    page.goStart()                       // 本次 chain 捕获当前世代
    await settle()                      // 明确确认并保存同意后才请求定位
    assert.ok(locOpts, 'goStart 应该走到 wx.getLocation')
    page.onShow()                        // 切走再回来 ⇒ 世代 +1,在途流程作废
    locOpts.success({ latitude: 31.23, longitude: 121.47 })  // 旧 chain 的回调这才回来
    assert.equal(openedSessions, 0, '已作废的 chain 不该再开第二个会话')
    assert.equal(consentCalls, 1, '旧定位回包不能自动追加AGREE;仅确认前的已保存记录')
    await settle()
  } finally {
    global.getApp = prevGetApp
    global.wx = prevWx
  }
})

test('onShow 归零后 goStart 不再被首行守卫挡掉', async () => {
  const page = loadRoamPage()
  const prevGetApp = global.getApp
  const prevWx = global.wx
  let locRequested = false
  global.getApp = () => ({ globalData: {}, getUserID: () => '9', recordConsent: () => Promise.resolve() })
  global.wx = {
    getStorageSync: () => undefined,
    setStorageSync: () => {},
    showModal: options => options.success({ confirm: true }),
    getLocation: () => { locRequested = true },   // 只验证走到了请求定位这一步
  }
  try {
    page._roamStartPending = true
    page.onShow()
    page.goStart()
    await settle()
    assert.equal(locRequested, true, 'goStart 应该走到 wx.getLocation,而不是被 _roamStartPending 挡在首行')
  } finally {
    global.getApp = prevGetApp
    global.wx = prevWx
  }
})

// 业务同意不能被 getApp 不可用的匿名降级绕过。本负控直接击中真实引导标记，
// 保留「非关键本地标记异常不毒死已明确同意的下一次出发」这个用户恢复契约。
test('取「看过引导」标记时抛异常,不能把 GO 的门闩焊死', async () => {
  const page = loadRoamPage()
  const prevGetApp = global.getApp
  const prevWx = global.wx
  let locRequests = 0
  global.getApp = () => ({ globalData: {}, getUserID: () => '9', recordConsent: () => Promise.resolve() })
  page.markRoamIntroSeen = () => { throw new Error('引导本地标记失败') }
  global.wx = {
    getStorageSync: () => undefined,
    setStorageSync: () => {},
    showModal: options => options.success({ confirm: true }),
    getLocation: () => { locRequests += 1 },
  }
  try {
    page.goStart()
    await settle()
    assert.equal(locRequests, 1, '取本地标记失败不该挡住这次出发')
    // 常驻页返回后仍能重新明确确认，非关键本地标记异常不影响后续出发。
    global.getApp = () => ({ globalData: {}, getUserID: () => '9', recordConsent: () => Promise.resolve() })
    page.onShow()
    page.goStart()
    await settle()
    assert.equal(locRequests, 2, '第二次点 GO 仍须走到 getLocation,而不是被卡住的门闩静默吃掉')
  } finally {
    global.getApp = prevGetApp
    global.wx = prevWx
  }
})
