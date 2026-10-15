'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.join(__dirname, '..', '..')
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8')

const route = require('../../subpackageRoam/utils/official-route.js')
const points = require('../../subpackageMember/points-history/rows.js')
const keys = require('../../pages/publish/components/topic-state-keys/rules.js')

test('B8: 漫游半径常量抽出且两处请求都引用它', () => {
  const src = read('pages/roam/index.js')
  assert.match(src, /const ROAM_POI_RADIUS_M = 3000;/)
  assert.match(src, /radius: String\(ROAM_POI_RADIUS_M\)/)
  assert.match(src, /radius: ROAM_POI_RADIUS_M \}/)
  assert.doesNotMatch(src, /radius: '3000'/)
})

test('C9: 继续上次漫游条用 validRecovery + 真实玩家桶读取，且无网络请求', () => {
  const src = read('components/cy/scene-roam-resume-bar/index.js')
  assert.match(src, /validRecovery/)
  assert.match(src, /readRecoveryState/)
  assert.doesNotMatch(src, /sendRequest|wx\.request/)
  assert.match(src, /switchTab\(\{ url: '\/pages\/roam\/index' \}\)/)
  assert.match(read('pages/index/index.wxml'), /<cy-roam-resume-bar \/>/)
})

test('C9: 记录键与 roam-player-memory 一致', () => {
  const { createPlayerRoamMemory } = require('../../utils/roam-player-memory.js')
  const store = {}
  const storage = { getStorageSync: (k) => store[k], setStorageSync: (k, v) => { store[k] = v } }
  const memory = createPlayerRoamMemory('42', storage)
  memory.writeRecovery({ clientSessionKey: 'a'.repeat(32), pendingTiles: [], finishRequested: false })
  assert.deepEqual(Object.keys(store), ['roam_memory_v1:42:recovery'])
})

test('C5: 路线整形——单位、缺字段、坏坐标', () => {
  assert.equal(route.distanceText(3.5), '3.5 km')
  assert.equal(route.distanceText(0), '')
  assert.equal(route.durationText(45), '45 分钟')
  assert.equal(route.durationText(150), '2 小时 30 分')
  assert.equal(route.normalizeRoute({ id: 1 }), null)
  const list = route.normalizeRouteList([{ id: 7, name: '老城', city: '上海', totalDistance: 4, totalTime: 90 }])
  assert.equal(list[0].metaText, '上海 · 4 km · 1 小时 30 分')
  assert.equal(route.normalizeRouteList({}), null)
  const detail = route.normalizeRouteDetail({ id: 7, name: '老城', nodes: [
    { name: 'A', latitude: '31.2', longitude: '121.4', nodeTime: 20 },
    { name: 'B', latitude: '', longitude: '' },
    { name: '', latitude: '1', longitude: '1' },
    { name: 'C', latitude: '99', longitude: '10' },
  ] })
  assert.deepEqual(detail.nodes.map((n) => [n.order, n.name, n.lat]), [[1, 'A', 31.2], [2, 'B', null], [3, 'C', null]])
})

test('C5: 官方路线入口与分包页面已注册；未接「按这条路线开始」', () => {
  const app = JSON.parse(read('app.json'))
  const roam = app.subPackages.find((p) => p.root === 'subpackageRoam').pages
  assert.ok(roam.includes('official-routes/index') && roam.includes('official-route/index'))
  assert.match(read('pages/roam/index.wxml'), /bindtap="openOfficialRoutes"/)
  assert.doesNotMatch(read('subpackageRoam/official-route/index.wxml'), /按这条路线开始/)
})

test('C6: 集邮册「收到的」走 /api/roam/stamp/received，且两个 tab 分页互不串', () => {
  const src = read('components/cy/scene-roam-stamp-album/index.js')
  assert.match(src, /received: '\/api\/roam\/stamp\/received'/)
  assert.match(src, /_tabCache/)
})

test('C1: 清除对话记录——危险确认、端点、成功后清本地', () => {
  const src = read('components/cy/npc-chat/index.js')
  assert.match(src, /dc\.open\('ai\.npc\.chatData\.delete'/)
  assert.match(src, /url: '\/api\/ai\/npc\/chat\/data\/delete'/)
  assert.match(src, /this\.setData\(\{ messages: \[\], state: 'IDLE'/)
  const actions = require('../../utils/danger-actions.js')
  const a = actions.getDangerAction('ai.npc.chatData.delete', {})
  assert.ok(a.consequences.some((c) => c.text.includes('此操作不可撤销')))
})

test('C2: 举报队伍请求体与入口', () => {
  const src = read('pages/team/report-sheet/index.js')
  assert.match(src, /url: '\/api\/team\/report'/)
  assert.match(src, /JSON\.stringify\(\{ teamId, reason \}\)/)
  assert.match(read('pages/team/detail/index.wxml'), /bindtap="openReport"/)
})

test('C4: 积分明细行整形与分页', () => {
  assert.equal(points.normalizeRows({}), null)
  assert.equal(points.normalizeRows([{ changePoints: 'x' }]), null)
  const rows = points.normalizeRows([
    { id: 2, changePoints: 5, changeType: 1, changeReason: '签到', afterPoints: 15, createTime: '2026-10-01 08:30:12' },
    { id: 1, changePoints: 3, changeType: 2, afterPoints: 10, createTime: '2026-09-30T07:00:00' },
  ])
  assert.deepEqual(rows.map((r) => r.deltaText), ['+5', '-3'])
  assert.equal(rows[0].timeText, '2026-10-01 08:30')
  assert.equal(rows[1].reason, '积分变动')
  assert.equal(points.PAGE_SIZE, 20)
  const app = JSON.parse(read('app.json'))
  assert.ok(app.subPackages.find((p) => p.root === 'subpackageMember').pages.includes('points-history/index'))
  assert.match(read('subpackageMember/components/cy/profile/index.wxml'), /bindtap="goPointsHistory"/)
})

test('C7: 状态键规则与后端一致', () => {
  assert.ok(keys.KEY_NAME.test('clue.ledger_page'))
  assert.ok(!keys.KEY_NAME.test('Clue.x'))
  assert.ok(!keys.KEY_NAME.test('clue.1abc'))
  assert.ok(!keys.KEY_NAME.test('foo.bar'))
  const ok = keys.buildKey(1, 'visits', '到访', '3')
  assert.deepEqual(ok.key, { stateKey: 'counter.visits', keyType: 'counter', scope: 'run', label: '到访', initialValue: 3 })
  assert.equal(keys.buildKey(0, 'Bad', '', '').ok, false)
  assert.equal(keys.buildKey(1, 'n', '', '1.5').ok, false)
  assert.equal(keys.buildKey(0, 'a', '', '').key.initialValue, undefined)
  assert.equal(keys.normalizeKeys([{ stateKey: 'bad' }]), null)
  assert.match(read('pages/publish/components/topic-state-keys/index.js'), /method: 'DELETE'/)
})
