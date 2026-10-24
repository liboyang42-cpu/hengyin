'use strict'

// C:U-092:转向不能绕过 marker 节流另起一次全量 _syncMarkers —— 一个定位点最多同步一次。
const assert = require('assert')
const test = require('node:test')

let pageConfig
global.getApp = () => ({ globalData: { user_id: 9, features: {} }, isDevEnv: () => false, sendRequest() {} })
global.wx = {
  getStorageSync: () => '',
  setStorageSync() {},
  getWindowInfo: () => ({ statusBarHeight: 20 }),
  getSystemInfoSync: () => ({ statusBarHeight: 20 }),
}
global.Page = (config) => { pageConfig = config }

function loadRoamPage() {
  delete require.cache[require.resolve('../../pages/roam/index.js')]
  require('../../pages/roam/index.js')
  const page = Object.assign({}, pageConfig, { data: JSON.parse(JSON.stringify(pageConfig.data)) })
  page.setData = (patch, cb) => { Object.assign(page.data, patch); if (cb) cb() }
  let syncs = 0
  Object.assign(page, {
    _c: { lat: 31.23, lng: 121.47 }, _track: [], _stats: {}, _zoomLock: true,
    _syncStats() {}, _checkNear() {}, _reportPresence() {}, _fetchRunners() {}, _rollStat() {},
    _syncMarkers() { syncs++ },
  })
  return { page, syncs: () => syncs }
}

const A = { lat: 31.23, lng: 121.47 }
const NORTH = { lat: 31.2301, lng: 121.47 }
const EAST = { lat: 31.2301, lng: 121.4702 }

test('U-092: 转向的那个点只同步一次 marker(不再 _updateHeading + 节流各一次)', () => {
  const { page, syncs } = loadRoamPage()
  page._heading = 90
  page._mkTimer = Date.now() - 5000 // 节流窗已过:旧实现转向同步一次 + 节流再同步一次
  page._applyMovePatch(NORTH, A)
  assert.strictEqual(page.data.heading, 0)
  assert.strictEqual(syncs(), 1, '转向要即时反映箭头,但只能同步一次')
})

test('U-092: 节流窗内不转向的点不同步 marker;转向后的下一个点重新受节流', () => {
  const { page, syncs } = loadRoamPage()
  page._heading = 0
  page._mkTimer = Date.now()
  page._applyMovePatch(NORTH, A) // 方向不变(正北)
  assert.strictEqual(syncs(), 0)
  page._heading = 0
  page._applyMovePatch(EAST, NORTH) // 转向正东
  assert.strictEqual(syncs(), 1)
  page._applyMovePatch({ lat: 31.2301, lng: 121.4704 }, EAST) // 同向继续,刚同步过
  assert.strictEqual(syncs(), 1, '刚同步过的节流窗内不能再同步')
})
