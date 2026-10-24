'use strict'

// BUG-ROAM2(2026-10-23)漫游分享回归:
//   作用域失效后提前返回不收 loading / 缺渠道时的坏提示 / 右上角转发截地图 /
//   未登录时分享作废待办落到全局键 / 两条足迹共用同一张已存照片时不得被淘汰删除。

const assert = require('node:assert/strict')
const test = require('node:test')

let pageConfig
let storage
let loadingCalls
let toasts

function setByPath(target, keyPath, value) {
  const parts = keyPath.split('.')
  let cursor = target
  parts.slice(0, -1).forEach((part) => {
    if (!cursor[part] || typeof cursor[part] !== 'object') cursor[part] = {}
    cursor = cursor[part]
  })
  cursor[parts[parts.length - 1]] = value
}

global.getApp = () => ({
  getUserID: () => '9',
  globalData: { user_id: 9, features: {} },
  isDevEnv: () => false,
  sendRequest() {},
})
global.Page = (config) => { pageConfig = config }
global.wx = {
  getStorageSync: (key) => storage[key],
  setStorageSync(key, value) { storage[key] = JSON.parse(JSON.stringify(value)) },
  getWindowInfo: () => ({ statusBarHeight: 20 }),
  getSystemInfoSync: () => ({ statusBarHeight: 20 }),
  showLoading() { loadingCalls.push('show') },
  hideLoading() { loadingCalls.push('hide') },
  showToast(options) { toasts.push(options && options.title) },
  showShareMenu() {},
}

function loadRoamPage() {
  delete require.cache[require.resolve('../../pages/roam/index.js')]
  require('../../pages/roam/index.js')
  const page = Object.assign({}, pageConfig, { data: JSON.parse(JSON.stringify(pageConfig.data)) })
  page.setData = (patch, callback) => {
    Object.entries(patch).forEach(([key, value]) => setByPath(page.data, key, value))
    if (callback) callback()
  }
  return page
}

test.beforeEach(() => {
  pageConfig = null
  storage = {}
  loadingCalls = []
  toasts = []
})

test('U-088 作用域在画卡途中失效:不存相册,但 loading 必须收起', async () => {
  const page = loadRoamPage()
  let alive = true, release
  page._saveToAlbum = async () => { throw new Error('不该存相册') }
  const pending = page._exportAndSave('生成卡片', () => new Promise((resolve) => { release = resolve }), () => alive)
  await Promise.resolve()
  assert.deepEqual(loadingCalls, ['show'])
  alive = false                      // 换号 / 离开本次漫游,但没走 _cancelShareWork
  release('/tmp/card.png')
  assert.deepEqual(await pending, { path: null, saved: false })
  assert.deepEqual(loadingCalls, ['show', 'hide'])
  assert.equal(page._shareLoading, false)
})

test('U-088 画卡失败且作用域失效:同样收起 loading,不弹「生成失败」', async () => {
  const page = loadRoamPage()
  let alive = true
  const pending = page._exportAndSave('生成卡片', () => Promise.resolve().then(() => { alive = false; throw new Error('x') }), () => alive)
  assert.deepEqual(await pending, { path: null, saved: false })
  assert.deepEqual(loadingCalls, ['show', 'hide'])
})

test('U-088 两轮导出交错:旧一轮提前返回只收自己那一层,新一轮结束后遮罩关闭', async () => {
  const page = loadRoamPage()
  let oldAlive = true, releaseOld, releaseNew
  page._saveToAlbum = async () => true
  const old = page._exportAndSave('生成卡片', () => new Promise((resolve) => { releaseOld = resolve }), () => oldAlive)
  const newer = page._exportAndSave('生成卡片', () => new Promise((resolve) => { releaseNew = resolve }), () => true)
  await Promise.resolve()
  oldAlive = false
  releaseOld('/tmp/old.png')
  await old
  assert.deepEqual(loadingCalls, ['show', 'show'], '新一轮还在画,遮罩不能被旧的关掉')
  releaseNew('/tmp/new.png')
  assert.equal((await newer).saved, true)
  assert.deepEqual(loadingCalls, ['show', 'show', 'hide'])
})

test('U-088 _cancelShareWork 已收过 loading 时不重复 hide', async () => {
  const page = loadRoamPage()
  let release
  const pending = page._exportAndSave('生成卡片', () => new Promise((resolve) => { release = resolve }), page._shareScope())
  await Promise.resolve()
  page._cancelShareWork()
  release('/tmp/card.png')
  await pending
  assert.deepEqual(loadingCalls, ['show', 'hide'])
})

test('U-106 缺 data-ch 的调用静默拦下,不拼「当前可见范围不发布到」', async () => {
  const page = loadRoamPage()
  const result = await page.shareTo({ currentTarget: { dataset: {} } })
  assert.equal(result.blocked, true)
  assert.equal(result.ok, false)
  assert.ok(!toasts.some((title) => /不发布到/.test(title || '')), JSON.stringify(toasts))
})

test('U-100 右上角转发返回固定插画封面,不让微信截漫游地图', () => {
  const page = loadRoamPage()
  const share = page.onShareAppMessage({ from: 'menu' })
  assert.ok(share && typeof share === 'object')
  assert.equal(share.path, '/pages/roam/index')
  assert.equal(share.imageUrl, '/images/route_city_cover.png')
  const fs = require('node:fs')
  const path = require('node:path')
  const root = path.resolve(__dirname, '../..')
  assert.ok(fs.existsSync(path.join(root, share.imageUrl)), '封面必须是仓内真实文件')
  const ignore = JSON.parse(fs.readFileSync(path.join(root, 'project.config.json'), 'utf8')).packOptions.ignore
  assert.ok(!ignore.some((x) => x.value === share.imageUrl.slice(1)), '封面不能在打包排除名单里')
  // 活动分享按钮仍走活动卡
  const activity = page.onShareAppMessage({ from: 'button', target: { dataset: { shareType: 'activity', activityId: 7, activityName: 'A' } } })
  assert.equal(activity.path, '/pages/activity/detail/index?id=7')
})

function shareHarness(userId) {
  const values = {}, requests = []
  return {
    values, requests,
    app: { getUserID: () => userId, sendRequest: (options) => requests.push(options) },
    storage: {
      getStorageSync: (key) => values[key],
      setStorageSync: (key, value) => { values[key] = value },
      removeStorageSync: (key) => { delete values[key] },
    },
  }
}

test('U-098 没有账号时不读不写全局作废待办键,也不发请求', async () => {
  const share = require('../../utils/roam-share-snapshot.js')
  for (const id of ['', 0, '0', undefined, null]) {
    const h = shareHarness(id)
    assert.equal(share.revokePendingKey(h.app), '')
    assert.equal(await share.revokeShareSnapshots(h.app, h.storage), false)
    const published = await share.publishShareSnapshot(h.app, h.storage, { sessionId: '12', ts: 123, track: [], pois: [] })
    assert.deepEqual(published, { token: '', reason: 'failed' })
    assert.deepEqual(h.values, {}, 'id=' + String(id))
    assert.equal(h.requests.length, 0, 'id=' + String(id))
  }
  const h = shareHarness('42')
  assert.equal(share.revokePendingKey(h.app), 'roam_share_revoke_pending:42')
})

test('U-097 被淘汰的足迹与留下的足迹共用同一张已存照片时,不返回给 removeSavedFile', () => {
  const { evictedSavedPhotos, MAX_SAVED_SESSIONS } = require('../../utils/roam-session-photos.js')
  const shared = { path: 'wxfile://shared.jpg', persisted: true }
  const own = { path: 'wxfile://old-only.jpg', persisted: true }
  const existing = Array.from({ length: MAX_SAVED_SESSIONS }, (_, i) => ({ ts: i, photos: [] }))
  existing[0].photos = [shared]                                   // 留下的一条也引用它
  existing[MAX_SAVED_SESSIONS - 1].photos = [shared, own]         // 第 51 条被淘汰
  const evicted = evictedSavedPhotos(existing, { ts: 999, photos: [] })
  assert.deepEqual(evicted.map((photo) => photo.path), ['wxfile://old-only.jpg'])
})
