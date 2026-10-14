// 集成核对(B 登出入口 × C 漫游缓存代次):服务端确认注销后清本机,
// reLaunch 卸载漫游页时不得把登出前的本范围未同步格子/揭示记录写回存储。
const { test, beforeEach } = require('node:test')
const assert = require('node:assert/strict')

const ROAM = '../../pages/roam/index.js'
const SHEZHI = '../../pages/shezhi/shezhi.js'
const EPOCH = '../../utils/roam-cache-epoch.js'
let configs, app, values, userId

function mountRoam() {
  delete require.cache[require.resolve(ROAM)]
  require(ROAM)
  const config = configs.pop()
  const page = Object.assign({}, config, { data: JSON.parse(JSON.stringify(config.data)) })
  page.setData = patch => Object.entries(patch).forEach(([key, value]) => {
    const parts = key.split('.'); let object = page.data
    parts.slice(0, -1).forEach(p => { object = object[p] || (object[p] = {}) })
    object[parts.at(-1)] = value
  })
  page._loadIntroTopics = () => {}; page._fetchShopBadgeCfg = () => {}
  page._stopClock = () => {}; page._stopReal = () => {}; page._triggerCelebration = () => {}
  page.onLoad({})
  return page
}

function mountShezhi() {
  delete require.cache[require.resolve(SHEZHI)]
  require(SHEZHI)
  const config = configs.pop()
  return Object.assign({}, config, { selectComponent: () => null, setData() {} })
}

beforeEach(() => {
  configs = []; values = {}; userId = '9'
  for (const mod of [ROAM, SHEZHI, EPOCH]) delete require.cache[require.resolve(mod)]
  global.Page = x => { configs.push(x) }
  app = {
    getUserID: () => userId,
    globalData: { user_id: '9', features: {} },
    isDevEnv: () => false,
    sendRequest(o) {
      if (o.url === '/api/logout') { o.success({ code: 200 }); return }
      o.fail && o.fail({})
    },
  }
  global.getApp = () => app
  global.wx = {
    getStorageSync: k => values[k],
    setStorageSync: (k, v) => { values[k] = JSON.parse(JSON.stringify(v)) },
    removeStorageSync: k => { delete values[k] },
    clearStorageSync: () => { values = {}; userId = '' },
    getRandomValues: o => o.success({ randomValues: new Uint8Array(16).fill(17).buffer }),
    getWindowInfo: () => ({ statusBarHeight: 20 }), getSystemInfoSync: () => ({ statusBarHeight: 20 }),
    showToast() {}, hideLoading() {}, showLoading() {},
  }
})

function roamKeysWithData() {
  return Object.entries(values).filter(([key, value]) => {
    if (!key.startsWith('roam_memory_v1:')) return false
    if (value == null) return false
    if (Array.isArray(value)) return value.length > 0
    if (key.endsWith(':tiles')) return Object.keys((value && value.tiles) || {}).length > 0
    return true
  }).map(([key]) => key)
}

test('服务端确认登出后 reLaunch 卸载漫游页，不重建登出前本机漫游格子与揭示记录', () => {
  const roam = mountRoam()
  roam._localTiles = { wtw3sjq: 1 }; roam._tilesDirty = 1
  roam._reveals = [{ geohash: 'wtw3sjq', ts: 1 }]; roam._revealsSaved = false
  wx.reLaunch = () => roam.onUnload()

  const shezhi = mountShezhi()
  shezhi.onDangerConfirm({ detail: { key: 'account.logout' } })

  assert.deepEqual(roamKeysWithData(), [], 'logout cleared storage; unloading roam must not rebuild cleared roam records')
})
