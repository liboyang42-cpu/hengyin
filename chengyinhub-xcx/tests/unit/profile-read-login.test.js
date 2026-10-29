'use strict'
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const ROOT = path.resolve(__dirname, '../..')
const PROFILE = path.join(ROOT, 'subpackageMember/components/cy/profile/index.js')
const HOST = path.join(ROOT, 'pages/member/index/index.js')
const flush = () => new Promise(resolve => setImmediate(resolve))

function environment(owner = '', token = '') {
  const requests = [], logins = [], roleWrites = []
  const app = { globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getUserID: () => owner, getAuthorization: () => token, getUserRole: () => 'player', getUserType: () => 1,
    getPageSize: () => 8, getTotalPage: (n, size) => Math.ceil(n / size), canUseCommunity: () => false,
    setUserRole: value => roleWrites.push(value), sendRequest: value => requests.push(value),
    firstLogin: () => new Promise(resolve => logins.push(resolve)), reLogin: () => new Promise(resolve => logins.push(resolve)) }
  global.getApp = () => app
  let stopped = 0
  global.wx = { getStorageSync: () => '', setStorageSync() {}, removeStorageSync() {}, hideTabBar() {},
    setNavigationBarColor() {}, setBackgroundColor() {}, stopPullDownRefresh() { stopped++ } }
  return { app, requests, logins, roleWrites, identity(id, value) { owner = id; token = value }, stopped: () => stopped }
}
function dataWrite(patch, done) {
  for (const [key, value] of Object.entries(patch)) {
    const keys = key.replace(/\[(\d+)\]/g, '.$1').split('.'); let target = this.data
    for (const part of keys.slice(0, -1)) target = target[part] || (target[part] = {})
    target[keys.at(-1)] = value
  }
  if (done) done.call(this)
}
function host(owner, token) {
  const h = environment(owner, token); let definition
  global.Page = value => { definition = value }; delete require.cache[require.resolve(HOST)]; require(HOST)
  h.page = Object.assign({}, definition, { data: structuredClone(definition.data), setData: dataWrite,
    selectComponent() { throw new Error('Guest must not mount or read lazy private subtree') } })
  return h
}
function profile(owner, token, self = true) {
  const h = environment(owner, token); let definition
  global.Component = value => { definition = value }
  require('../helpers/community-page-seam').loadPage(PROFILE)
  const data = structuredClone(definition.data)
  for (const [key, property] of Object.entries(definition.properties)) data[key] = property.value
  Object.assign(data, { isSelf: self, hostSelf: self, userId: self ? '' : '92', viewer: self ? 'self' : 'other' })
  h.component = Object.assign({}, definition.methods, { data, _profileAttached: true, setData: dataWrite })
  h.definition = definition
  return h
}
// 2026-10-27 L08 P-53:俱乐部入口删除后 loadClubSummary(/api/club/my)随之移除,私有读集合同步去掉它。
const PRIVATE_READS = ['loadRoleInfo','loadMerchantAccess','loadGrowthBundle','loadJoinData',
  'loadThemes','loadMedals','loadPointsStat','loadOrderPreview','loadProjectPreview','loadProjectPending','loadPointsTasks']

for (const [owner, token] of [['', ''], ['7', ''], ['', 'Bearer unit'], ['0', 'Bearer unit']]) {
  test(`self host guest gate is outside lazy profile (${owner}/${!!token})`, () => {
    const h = host(owner, token); h.page.onShow(); h.page.onPullDownRefresh()
    assert.deepEqual(h.page.data.profileMounts, []); assert.equal(h.requests.length, 0); assert.equal(h.stopped(), 1)
  })
}
test('normal host login is single-flight and refusal retains guest entry', async () => {
  const h = host(); h.page.onShow(); h.page.loginToRead(); h.page.loginToRead()
  assert.equal(h.logins.length, 1); h.logins[0]({ ok: false }); await flush()
  assert.deepEqual(h.page.data.profileMounts, []); h.page.loginToRead()
  h.identity('7', 'Bearer new'); h.logins[1]({ ok: true }); await flush()
  assert.equal(h.page.data.profileMounts.length, 1); assert.equal(h.requests.length, 0)
  assert.equal(JSON.stringify(h.page.data).includes('Bearer new'), false)
})
test('host hide/unload and old account login completions cannot mount a stale profile', async () => {
  for (const end of ['onHide', 'onUnload']) {
    const h = host(); h.page.onShow(); h.page.loginToRead(); h.page[end]()
    h.identity('7', 'Bearer new'); h.logins[0]({ ok: true }); await flush()
    assert.deepEqual(h.page.data.profileMounts, [])
  }
})
test('host replaces private instance on owner/token changes and refresh delegates only a complete session', async () => {
  const h = host('7', 'Bearer first'); h.page.onShow(); const first = h.page.data.profileMounts[0].id
  h.identity('7', 'Bearer replacement'); h.page.onShow(); assert.notEqual(h.page.data.profileMounts[0].id, first)
  const second = h.page.data.profileMounts[0].id; h.identity('8', 'Bearer other'); h.page.onShow()
  assert.notEqual(h.page.data.profileMounts[0].id, second)
  let refresh = 0; h.page.selectComponent = () => ({ refreshAll() { refresh++; return Promise.resolve() } })
  h.page.onPullDownRefresh(); await flush(); assert.equal(refresh, 1)
  h.identity('', ''); h.page.onShow(); assert.deepEqual(h.page.data.profileMounts, [])
})
test('initial app readiness only mounts current visible complete session', async () => {
  const h = host(); let resolve
  h.app.isAppReady = false; h.app.waitForAppReady = () => new Promise(done => { resolve = done })
  h.page.onShow(); h.page.onHide(); h.identity('7', 'Bearer ready'); resolve(); await flush()
  assert.deepEqual(h.page.data.profileMounts, []); h.page.onShow(); assert.equal(h.page.data.profileMounts.length, 1)
})
for (const method of PRIVATE_READS) {
  test(`guest effective-self ${method} does not issue private API`, async () => {
    const h = profile(); const pending = h.component[method]()
    assert.equal(h.requests.length, 0); await pending; assert.equal(h.component.data.selfReadAccess, 'login')
  })
}
test('private primary profile late token response cannot mutate data or global role', async () => {
  const h = profile('7', 'Bearer old'); const pending = h.component.loadUserData()
  h.identity('7', 'Bearer replacement'); h.requests[0].success({ code: 200, data: { role: 'merchant', nickname: 'Old token' } }); await pending
  assert.deepEqual(h.roleWrites, []); assert.equal(h.component.data.userInfo.nickname, undefined)
})
for (const method of ['loadMedals','loadJoinData','loadMerchantAccess','loadPointsTasks']) {
  test(`${method} rejects old owner, hidden and detached callback mutations`, async () => {
    for (const transition of ['owner', 'hide', 'detach']) {
      const h = profile('7', 'Bearer old'); const pending = h.component[method]()
      assert.equal(h.requests.length, 1)
      if (transition === 'owner') h.identity('8', 'Bearer other')
      else h.definition[transition === 'hide' ? 'pageLifetimes' : 'lifetimes'][transition === 'hide' ? 'hide' : 'detached'].call(h.component)
      const before = JSON.stringify(h.component.data)
      h.requests[0].success({ code: 200, data: { medals: [{ medalName: 'Old' }], count: 9, owned: [{ id: 3 }], active: true } }); await pending
      if (transition === 'owner') {
        assert.deepEqual(h.component.data.userInfo, {})
        assert.deepEqual(h.component.data.medals, [])
        assert.deepEqual(h.component.data.joinInfo, { id: 0 })
        assert.equal(h.component.data.merchantAccess.active, false)
        assert.deepEqual(h.component.data.pointsSheet.tasks, [])
      } else assert.equal(JSON.stringify(h.component.data), before)
      assert.deepEqual(h.roleWrites, [])
    }
  })
}
test('anonymous public-other retains public profile read and never writes viewer role', async () => {
  const h = profile('', '', false); const pending = h.component.loadUserData()
  assert.equal(h.requests.length, 1); assert.equal(h.requests[0].url, '/api/user/public-info')
  h.requests[0].success({ code: 200, data: { nickname: 'Public member', role: 'merchant' } }); await pending
  assert.equal(h.component.data.userInfo.nickname, 'Public member'); assert.deepEqual(h.roleWrites, [])
})
test('public target changes reject old subject without making anonymous content private', async () => {
  const h = profile('', '', false); const pending = h.component.loadUserData()
  h.component.data.userId = '93'; h.requests[0].success({ code: 200, data: { nickname: 'Previous subject' } }); await pending
  assert.equal(h.component.data.userInfo.nickname, undefined)
})
test('logout failure clears private state while same-owner server failure keeps existing data', async () => {
  const h = profile('7', 'Bearer old'); h.component.data.userInfo = { nickname: 'Private' }; h.component.data.orderPreviewList = [{ id: 3 }]
  const pending = h.component.loadUserData(); h.identity('', ''); h.requests[0].fail({ code: 401 }, 401); await pending
  assert.equal(h.component.data.selfReadAccess, 'login'); assert.deepEqual(h.component.data.userInfo, {}); assert.deepEqual(h.component.data.orderPreviewList, [])
  const normal = profile('7', 'Bearer normal'); normal.component.data.userInfo = { nickname: 'Keep' }
  const p = normal.component.loadUserData(); normal.requests[0].success({ code: 500 }); await p
  assert.equal(normal.component.data.userInfo.nickname, 'Keep'); assert.notEqual(normal.component.data.selfReadAccess, 'login')
})
test('guest entry markup does not depend on lazy subtree and public-other host stays ungated', () => {
  const host = fs.readFileSync(HOST.replace('.js','.wxml'),'utf8')
  assert.match(host,/cy-empty[^>]*wx:if="\{\{!profileMounts.length\}\}"[^>]*cta="登录"[^>]*bind:cta="loginToRead"/s)
  assert.match(host,/cy-profile[^>]*wx:for="\{\{profileMounts\}\}"[^>]*wx:key="id"/s)
  const other = fs.readFileSync(path.join(ROOT,'pages/userinfo/userinfo.wxml'),'utf8')
  assert.match(other,/viewer="other"/); assert.doesNotMatch(other,/profileMounts|loginToRead/)
})

test('same-session 401 preserves lawful cache and blocks private reads until normal login recovery', async () => {
  const h = profile('7', 'Bearer valid')
  h.component.data.userInfo = { nickname: 'Keep my cache' }
  h.component.data.orderPreviewList = [{ id: 3 }]
  const pending = h.component.loadUserData()
  h.requests[0].fail({ code: 401 }, 401); await pending
  assert.equal(h.component.data.selfReadAccess, 'login')
  assert.equal(h.component.data.userInfo.nickname, 'Keep my cache')
  assert.deepEqual(h.component.data.orderPreviewList, [{ id: 3 }])
  await h.component.loadOrderPreview(); assert.equal(h.requests.length, 1)
  h.component.syncMerchantView = () => {}; h.component.loadBanner = () => Promise.resolve()
  let refreshed = 0; h.component.refreshAll = () => { refreshed++; return Promise.resolve() }
  const login = h.component.loginToReadProfile(); h.component.loginToReadProfile()
  assert.equal(h.logins.length, 1); h.logins[0]({ ok: true }); await login
  assert.equal(h.component.data.selfReadAccess, ''); assert.equal(refreshed, 1)
})

for (const end of ['hide', 'detach', 'owner']) test(`profile normal login late ${end} cannot initialize private reads`, async () => {
  const h = profile('7', 'Bearer valid'); h.component._captureProfileRead(true)
  h.component.data.selfReadAccess = 'login'
  let initialized = 0; h.component.initializeProfile = () => { initialized++ }
  const pending = h.component.loginToReadProfile()
  if (end === 'owner') h.identity('8', 'Bearer changed')
  else h.definition[end === 'hide' ? 'pageLifetimes' : 'lifetimes'][end === 'hide' ? 'hide' : 'detached'].call(h.component)
  h.logins[0]({ ok: true }); await pending
  assert.equal(initialized, 0); assert.equal(h.requests.length, 0)
})

test('hidden points read releases loading and reopening retries without accepting its late result', async () => {
  const h = profile('7', 'Bearer valid')
  h.component.onPointsHelp()
  assert.equal(h.component.data.pointsSheet.loading, true)
  assert.equal(h.requests.length, 1)
  h.definition.pageLifetimes.hide.call(h.component)
  assert.equal(h.component.data.pointsSheet.loading, false)
  h.component.refreshAll = () => {}; h.component.syncMerchantView = () => {}
  h.component.syncViewerIdentity = () => {}; h.component.refreshCoopPublisherAccess = () => {}
  h.component.refreshDirtyPosts = () => {}; h.component._syncProfileReducedMotion = () => {}
  h.definition.pageLifetimes.show.call(h.component)
  h.requests[0].success({ code: 200, data: [{ id: 9, taskName: 'Old hidden result' }] }); await flush()
  assert.deepEqual(h.component.data.pointsSheet.tasks, [])
  h.component.onPointsHelp()
  assert.equal(h.requests.length, 2)
  assert.equal(h.component.data.pointsSheet.loading, true)
})
