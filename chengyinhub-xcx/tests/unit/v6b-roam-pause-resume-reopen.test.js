// V6b 验收（SC-ROAM-RESUME 客户端）：开始 → 暂停 → 继续 → 离开 → 重进，页面恢复同一会话；
// 用时只算走着的时间且跨重进连续（暂停的那段不计、也不归零），里程保留，重进不新建第二个会话。
// roam-recovery-flow.test.js 已证「页面重建后保留累计用时」「ACTIVE 恢复后新增距离再冷进保留」，
// 这里补中间夹一次暂停/继续的完整链路。负控：把 togglePause 里的 _sessionClock.pause() 去掉，用时断言必红。
const { test, beforeEach } = require('node:test')
const assert = require('node:assert/strict')
const source = '../../pages/roam/index.js'
let config, app, values, calls
const SID = '9007199254740993'
const settle = async () => { for (let i = 0; i < 12; i++) await new Promise(r => setImmediate(r)) }
function mount() {
  delete require.cache[require.resolve(source)]
  require(source)
  const page = Object.assign({}, config, { data: JSON.parse(JSON.stringify(config.data)) })
  page.setData = (patch, cb) => {
    Object.entries(patch).forEach(([key, value]) => {
      const parts = key.split('.'); let object = page.data
      parts.slice(0, -1).forEach(p => { object = object[p] || (object[p] = {}) })
      object[parts.at(-1)] = value
    })
    if (cb) cb()
  }
  page._loadIntroTopics = () => {}; page._fetchShopBadgeCfg = () => {}
  page._stopClock = () => {}; page._stopReal = () => {}; page._triggerCelebration = () => {}
  page._flushTrackLine = () => {}; page._fitRouteOverview = () => {}; page._enterWalkZoom = () => {}
  page._syncPlayHud = () => {}; page._syncStats = () => {}
  page.onLoad({})
  return page
}
beforeEach(() => {
  calls = []; values = {}
  const sessions = {}
  global.Page = x => { config = x }
  app = { getUserID: () => '9', globalData: { user_id: '9', features: {} }, isDevEnv: () => false,
    sendRequest(o) {
      calls.push({ url: o.url, data: o.data })
      const key = app.getUserID() + ':' + o.data.clientSessionKey
      if (o.url === '/api/roam/reveal') {
        sessions[key] = sessions[key] || { state: 'ACTIVE', sessionId: SID, clientSessionKey: o.data.clientSessionKey }
        o.success({ code: 200, data: { sessionId: SID } }); return
      }
      if (o.url === '/api/roam/session') { o.success({ code: 200, data: sessions[key] || { state: 'NOT_FOUND' } }); return }
      if (o.url === '/api/roam/finish') throw new Error('重进不应结算')
      o.fail && o.fail({})
    },
  }
  global.getApp = () => app
  global.wx = {
    getStorageSync: k => values[k], setStorageSync: (k, v) => { values[k] = JSON.parse(JSON.stringify(v)) },
    getRandomValues: o => o.success({ randomValues: new Uint8Array(16).fill(17).buffer }),
    getWindowInfo: () => ({ statusBarHeight: 20 }), getSystemInfoSync: () => ({ statusBarHeight: 20 }),
    showToast() {}, hideLoading() {}, showLoading() {},
  }
})

test('V6b SC-ROAM-RESUME：暂停/继续后离开再重进，同一会话、用时不含暂停且连续、里程保留、不新建会话', async () => {
  const { createSessionClock } = require('../../utils/play-ui-contract.js')
  const originalNow = Date.now
  let now = 100000
  Date.now = () => now
  let reopened
  try {
    const first = mount(); first._pendingTiles = ['wtw3sjq']; first._dist = 120
    await first._flushReveal(); await settle()
    assert.equal(first._roamSid, SID)
    first._sessionClock = createSessionClock(() => now); first._sessionClock.start()
    now += 30000                 // 走 30 秒
    first.togglePause()
    assert.equal(first.data.paused, true)
    now += 600000                // 暂停 10 分钟，不计时
    first.togglePause()
    assert.equal(first.data.paused, false)
    now += 20000                 // 再走 20 秒
    assert.equal(first._sessionClock.elapsedSeconds(), 50)
    first.onHide()               // 离开（未开实时定位，onHide 会自动暂停并落盘）

    now += 300000                // 离开 5 分钟
    const reveals = calls.filter(c => c.url === '/api/roam/reveal').length
    reopened = mount(); await settle()
    reopened._startClock()
    assert.equal(reopened._roamSid, SID, '重进必须找回同一会话')
    assert.equal(reopened._sessionClock.elapsedSeconds(), 50, '用时必须接着 50 秒，不含暂停与离开的时间')
    now += 10000
    assert.equal(reopened._sessionClock.elapsedSeconds(), 60, '重进后时钟继续走')
    assert.equal(reopened._dist, 120, '里程保留')
    assert.equal(calls.filter(c => c.url === '/api/roam/reveal').length, reveals, '重进不应再发起新会话的首笔写')
    assert.equal(new Set(calls.filter(c => c.data && c.data.clientSessionKey).map(c => c.data.clientSessionKey)).size, 1,
      '全程只用一个 clientSessionKey')
  } finally {
    if (reopened && reopened._clock) clearInterval(reopened._clock)
    Date.now = originalNow
  }
})
