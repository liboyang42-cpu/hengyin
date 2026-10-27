// SC-ROAM-END(客户端部分):漫游页结束本次漫游,/api/roam/finish 回包丢失(服务端可能已结算),
// 玩家在结算屏点「核对结算结果」重试 —— 不得再结束一次(不重发 finish)、奖励(勋章 toast / 勋章庆祝)
// 只出现一次;之后漫游记录(roam-history 场景)能打开并且只有这一次的记录。
const { test, beforeEach } = require('node:test')
const assert = require('node:assert/strict')

const PAGE = '../../pages/roam/index.js'
const HISTORY = '../../components/cy/scene-roam-history/index.js'
const SID = '9007199254740993'
const settle = async () => { for (let i = 0; i < 12; i++) await new Promise(r => setImmediate(r)) }

let config, componentConfig, app, values, calls, sessions, toasts, modals, finishMode, navigations

function mount() {
  delete require.cache[require.resolve(PAGE)]
  require(PAGE)
  const page = Object.assign({}, config, { data: JSON.parse(JSON.stringify(config.data)) })
  page.setData = patch => Object.entries(patch).forEach(([key, value]) => {
    const parts = key.split('.'); let object = page.data
    parts.slice(0, -1).forEach(p => { object = object[p] || (object[p] = {}) })
    object[parts.at(-1)] = value
  })
  page.celebrations = []
  page._loadIntroTopics = () => {}; page._fetchShopBadgeCfg = () => {}
  page._stopClock = () => {}; page._stopReal = () => {}
  page._triggerCelebration = (kind) => page.celebrations.push(kind)
  page.onLoad({})
  return page
}

beforeEach(() => {
  calls = []; sessions = {}; values = {}; toasts = []; modals = []; navigations = []; finishMode = 'timeout-after-apply'
  global.Page = x => { config = x }
  global.Component = x => { componentConfig = x }
  app = { getUserID: () => '9', globalData: { user_id: '9', features: {} }, isDevEnv: () => false,
    sendRequest(o) {
      calls.push({ url: o.url, data: o.data })
      const key = app.getUserID() + ':' + o.data.clientSessionKey
      if (o.url === '/api/roam/reveal') {
        sessions[key] = sessions[key] || { state: 'ACTIVE', sessionId: SID, clientSessionKey: o.data.clientSessionKey }
        o.success({ code: 200, data: { sessionId: SID } }); return
      }
      if (o.url === '/api/roam/session') {
        const found = Object.values(sessions).find(s => s.clientSessionKey === o.data.clientSessionKey || s.sessionId === o.data.sessionId)
        o.success({ code: 200, data: found || { state: 'NOT_FOUND' } }); return
      }
      if (o.url === '/api/roam/finish') {
        // 服务端已结算,回包丢失(超时)
        const k = Object.keys(sessions)[0]
        sessions[k] = { ...sessions[k], state: 'FINISHED', resultComplete: true, badgeDelivery: [{ badgeCode: 'ROAM_TILES_50', state: 'DELIVERED' }], result: { totalXp: 7, medal: '城市点亮 50 格' } }
        if (finishMode === 'timeout-after-apply') o.fail({ errMsg: 'request:fail timeout' })
        else o.successStatusAbnormal({ statusCode: 504 })
        return
      }
      o.fail && o.fail({})
    },
  }
  global.getApp = () => app
  global.wx = {
    getStorageSync: k => values[k], setStorageSync: (k, v) => { values[k] = JSON.parse(JSON.stringify(v)) },
    removeStorageSync: k => { delete values[k] },
    getRandomValues: o => o.success({ randomValues: new Uint8Array(16).fill(17).buffer }),
    getWindowInfo: () => ({ statusBarHeight: 20 }), getSystemInfoSync: () => ({ statusBarHeight: 20 }),
    showToast: o => toasts.push(o.title), hideLoading() {}, showLoading() {},
    showModal: o => modals.push(o),
    navigateTo: o => { navigations.push(o.url); if (o.complete) o.complete() },
  }
})

const finishCalls = () => calls.filter(c => c.url === '/api/roam/finish').length
const medalToasts = () => toasts.filter(t => /获得勋章/.test(String(t))).length

for (const mode of ['timeout-after-apply', 'http-504-after-apply']) {
  test(`结束→finish 回包丢失(${mode})→重试只回读,不二次结束、奖励只出一次,随后可打开漫游记录`, async () => {
    finishMode = mode
    const page = mount()
    page._pendingTiles = ['wtw3sjq']; page._pois = []; page._dist = 1200
    page.data.screen = 'map'

    // 抽屉里的「结束本次漫游」→ 危险确认 → 确认
    page.endRoamSession()
    assert.equal(modals.length, 1)
    modals[0].success({ confirm: true })
    await settle()
    assert.equal(finishCalls(), 1)
    assert.equal(page.data.screen, 'arrive')
    assert.equal(page.data.finish.settleErr, 'unknown', '回包丢失只能标成待确认')
    assert.equal(page.data.finish.xpAwarded, undefined, '未确认前不展示奖励')
    assert.equal(medalToasts(), 0)
    assert.equal(page.celebrations.filter(c => c === 'medal-earned').length, 0)

    // 玩家连点两次「核对结算结果」
    page.retrySettle(); page.retrySettle()
    await settle()
    assert.equal(finishCalls(), 1, '重试不得重发 /api/roam/finish')
    assert.equal(calls.filter(c => c.url === '/api/roam/session').length, 1, '连点只发一次刷新')
    assert.equal(page.data.finish.xpAwarded, 7)
    assert.equal(page.data.finish.medal, '城市点亮 50 格')
    assert.equal(page.data.finish.settleErr, '', '结算确认完成后重试入口消失')
    assert.equal(medalToasts(), 1, '勋章提示只出现一次')
    assert.equal(page.celebrations.filter(c => c === 'medal-earned').length, 1, '勋章庆祝只出现一次')
    assert.equal(page.celebrations.filter(c => c === 'roam-finish').length, 1, '结束仪式只出现一次')

    // 再点一次「结束」:结算屏上已经结束,不得再次结束/结算
    page.endRoamSession()
    modals.at(-1).success({ confirm: true })
    await settle()
    assert.equal(finishCalls(), 1)
    assert.equal(medalToasts(), 1)
    assert.equal(page.celebrations.filter(c => c === 'roam-finish').length, 1)

    // 之后打开漫游记录
    // 2026-10-27 P-37(业主 p120):漫游历史改成全屏页面,不再在地图上压 psheet ⇒ 断言改为进历史页
    navigations.length = 0
    page.openRoamHistory()
    assert.deepEqual(navigations, ['/subpackageRoam/history/index'])
    assert.equal(page.data.sceneCurrent, null, '地图上不再叠漫游历史弹层')
    delete require.cache[require.resolve(HISTORY)]
    require(HISTORY)
    const history = Object.assign({}, componentConfig.methods, { data: JSON.parse(JSON.stringify(componentConfig.data)) })
    history.setData = (patch) => Object.assign(history.data, patch)
    componentConfig.lifetimes.attached.call(history)
    assert.equal(history.data.state, 'ready')
    assert.equal(history.data.list.length, 1, '本次漫游只留一条记录，不因重试重复')
    assert.equal(history.data.inProgress, false, '已结算的漫游不再显示为进行中')
    assert.equal(history._sessions[0].xpAwarded, 7)
    assert.equal(history._sessions[0].sessionId, SID)
  })
}
