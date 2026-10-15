'use strict'
// CL1 / G1 + G3:漫游「跟随官方路线」—— 交接(一次性消费/过期/换号/坏数据)、降级、地图叠加、页面接线。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.join(__dirname, '..', '..')
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8')

function memStorage() {
  const box = new Map()
  return {
    box,
    setStorageSync(k, v) { box.set(k, JSON.parse(JSON.stringify(v))) },
    getStorageSync(k) { return box.has(k) ? box.get(k) : '' },
    removeStorageSync(k) { box.delete(k) },
  }
}

const toasts = []
const storage = memStorage()
global.wx = Object.assign(storage, { showToast(o) { toasts.push(o.title) } })

const fr = require('../../utils/roam-follow-route.js')
const ctl = require('../../pages/roam/follow-route.js')
const { normalizeRouteDetail } = require('../../subpackageRoam/utils/official-route.js')

const ROUTE = normalizeRouteDetail({
  id: 42, name: '外滩步行线', city: '上海',
  nodes: [
    { id: 1, name: '外白渡桥', latitude: '31.2440', longitude: '121.4900' },
    { id: 2, name: '无坐标站', latitude: '', longitude: '' },
    { id: 3, name: '和平饭店', latitude: '31.2400', longitude: '121.4880' },
    { id: 4, name: '十六铺', latitude: 31.2300, longitude: 121.4950 },
  ],
})

test('buildFollowPlan:只收带坐标的站点并重排站序，记录跳过数', () => {
  const plan = fr.buildFollowPlan(ROUTE)
  assert.equal(plan.supported, true)
  assert.equal(plan.routeId, '42')
  assert.deepEqual(plan.stations.map((s) => [s.order, s.name]), [[1, '外白渡桥'], [2, '和平饭店'], [3, '十六铺']])
  assert.equal(plan.skipped, 1)
})

test('buildFollowPlan:无站点 / 全无坐标 / 坏 id 明确降级，不给跟随', () => {
  const none = fr.buildFollowPlan(normalizeRouteDetail({ id: 1, name: 'A', nodes: [] }))
  assert.equal(none.supported, false); assert.match(none.reason, /还没有站点/)
  const noPt = fr.buildFollowPlan(normalizeRouteDetail({ id: 1, name: 'A', nodes: [{ name: 'x' }] }))
  assert.equal(noPt.supported, false); assert.match(noPt.reason, /没有标注位置/)
  assert.equal(fr.buildFollowPlan({ id: 'abc', name: 'A', nodes: [] }).supported, false)
  assert.equal(fr.buildFollowPlan(null).supported, false)
})

test('交接:写入后只能消费一次', () => {
  const s = memStorage()
  const plan = fr.buildFollowPlan(ROUTE)
  assert.equal(fr.writeHandoff(s, plan, 'u1', 1000), true)
  const got = fr.consumeHandoff(s, 'u1', 2000)
  assert.equal(got.routeId, '42')
  assert.equal(got.stations.length, 3)
  assert.equal(got.skipped, 1)
  assert.equal(s.box.has(fr.HANDOFF_SLOT), false, '消费后必须删除')
  assert.equal(fr.consumeHandoff(s, 'u1', 2001), null)
})

test('交接:过期、换账号、坏数据一律丢弃且删除', () => {
  const plan = fr.buildFollowPlan(ROUTE)
  let s = memStorage()
  fr.writeHandoff(s, plan, 'u1', 0)
  assert.equal(fr.consumeHandoff(s, 'u1', fr.HANDOFF_TTL_MS + 1), null)
  assert.equal(s.box.size, 0)

  s = memStorage(); fr.writeHandoff(s, plan, 'u1', 0)
  assert.equal(fr.consumeHandoff(s, 'u2', 10), null, '换号不得接手别人的路线')
  assert.equal(s.box.size, 0)

  s = memStorage(); fr.writeHandoff(s, plan, '', 0)
  assert.ok(fr.consumeHandoff(s, 'u2', 10), '详情页未登录写入的交接,登录后可消费')

  s = memStorage(); s.setStorageSync(fr.HANDOFF_SLOT, { v: 1, expiresAt: 99, routeId: '1', name: 'x', stations: [{ name: 'a', lat: 'bad', lng: 1 }] })
  assert.equal(fr.consumeHandoff(s, '', 10), null)
  assert.equal(s.box.size, 0)

  s = memStorage(); s.setStorageSync(fr.HANDOFF_SLOT, { v: 1, expiresAt: 10 + fr.HANDOFF_TTL_MS * 10, routeId: '1', name: 'x', stations: [{ name: 'a', lat: 1, lng: 1 }] })
  assert.equal(fr.consumeHandoff(s, '', 10), null, '过期时间远超 TTL 视为篡改')

  assert.equal(fr.writeHandoff(s, { supported: false }, 'u1', 0), false)
  const broken = { setStorageSync() { throw new Error('full') } }
  assert.equal(fr.writeHandoff(broken, plan, 'u1', 0), false, '写失败要回 false 让详情页提示')
})

test('advance / hudModel:进半径算到站，可跳站；全部到达有终态', () => {
  let st = fr.startFollow(fr.buildFollowPlan(ROUTE))
  let hud = fr.hudModel(st, null)
  assert.equal(hud.progress, '0/3'); assert.equal(hud.total, 3)
  assert.match(hud.nextText, /第1站 外白渡桥/); assert.equal(hud.distText, '')
  assert.match(hud.skippedText, /1 个站点未标注位置/)

  let r = fr.advance(st, { lat: 31.2300, lng: 121.4950 }) // 直接走到第 3 站
  assert.equal(r.changed, true); st = r.state
  hud = fr.hudModel(st, { lat: 31.2300, lng: 121.4950 })
  assert.equal(hud.progress, '1/3'); assert.match(hud.nextText, /第1站/); assert.match(hud.distText, /公里|米/)

  r = fr.advance(st, { lat: 31.2300, lng: 121.4950 })
  assert.equal(r.changed, false); assert.equal(r.state, st, '没变化不产生新对象')
  st = fr.advance(st, { lat: 31.2440, lng: 121.4900 }).state
  st = fr.advance(st, { lat: 31.2400, lng: 121.4880 }).state
  hud = fr.hudModel(st, { lat: 31.24, lng: 121.488 })
  assert.equal(hud.finished, true); assert.equal(hud.nextText, '全部站点已到达')
  assert.equal(fr.advance(st, { lat: NaN, lng: 1 }).changed, false)
})

test('叠加:折线与针 id 不撞漫游页既有 marker 段', () => {
  const st = fr.startFollow(fr.buildFollowPlan(ROUTE))
  const line = fr.routePolyline(st)
  assert.equal(line.length, 1); assert.equal(line[0].points.length, 3); assert.equal(line[0].dottedLine, true)
  const mk = fr.routeMarkers(st)
  assert.deepEqual(mk.map((m) => m.id), [960000, 960001, 960002])
  mk.forEach((m) => {
    assert.ok(m.id > 800000 + 20 && m.id !== 99 && m.id !== 99997)
    assert.ok(fr.isFollowMarkerId(m.id))
    assert.match(m.label.content, /^\d /)
  })
  assert.equal(fr.isFollowMarkerId(800001), false)
  assert.deepEqual(fr.routePolyline(null), []); assert.deepEqual(fr.routeMarkers(null), [])
  const single = fr.startFollow(fr.buildFollowPlan(normalizeRouteDetail({ id: 1, name: 'A', nodes: [{ name: 'x', latitude: 1, longitude: 1 }] })))
  assert.deepEqual(fr.routePolyline(single), [], '单站不画线,只画针')
})

function fakePage(screen) {
  const page = {
    data: { screen, mapReady: screen !== 'entry-map', followRoute: null, polyline: [] },
    _track: [], calls: [],
    setData(patch) { Object.assign(this.data, patch) },
    _flushTrackLine() { this.calls.push('flush') },
    _syncMarkers() { this.calls.push('markers') },
    openEntryRoamSheet() { this.calls.push('sheet') },
  }
  return page
}
const app = { getUserID: () => 'u1' }

test('接线:入口屏消费交接 → 记计划 + 弹开始漫游半屏（不自动取定位）；开局后才画', () => {
  fr.writeHandoff(storage, fr.buildFollowPlan(ROUTE), 'u1')
  const page = fakePage('entry-map')
  assert.equal(ctl.consume(page, app), true)
  assert.ok(page._followRoute)
  assert.deepEqual(page.calls, ['sheet'])
  assert.equal(page.data.followRoute.name, '外滩步行线')
  assert.deepEqual(page.data.polyline, [], '入口屏不改地图折线')
  assert.equal(ctl.consume(page, app), false, '同一份交接不能二次消费')

  page.data.screen = 'opening'; page.data.mapReady = true
  ctl.onSessionOpened(page)
  assert.equal(page.data.polyline.length, 1)
  assert.ok(page.calls.includes('flush') && page.calls.includes('markers'))
  const track = [{ points: [], color: 'x' }]
  assert.equal(ctl.withRoute(page, track).length, 2)
  assert.equal(ctl.markers(page).length, 3)
})

test('接线:已在地图上则直接叠到当前这局；退出跟随只清叠加', () => {
  fr.writeHandoff(storage, fr.buildFollowPlan(ROUTE), 'u1')
  const page = fakePage('map')
  toasts.length = 0
  ctl.consume(page, app)
  assert.equal(page.data.polyline.length, 1)
  assert.match(toasts[0], /标出线路站点/)

  page._player = { lat: 31.2440, lng: 121.4900 }
  ctl.onMove(page, page._player)
  assert.equal(page.data.followRoute.progress, '1/3')
  assert.equal(ctl.tapMarker(page, 960001), true)
  assert.match(toasts[toasts.length - 1], /第2站 和平饭店，约/)
  assert.equal(ctl.tapMarker(page, 800001), false)

  ctl.exit(page)
  assert.equal(page._followRoute, null)
  assert.equal(page.data.followRoute, null)
  assert.deepEqual(page.data.polyline, [])
  assert.deepEqual(ctl.withRoute(page, [1]), [1])
  assert.deepEqual(ctl.markers(page), [])
  assert.match(toasts[toasts.length - 1], /继续自由漫游/)
})

test('页面接线:详情页按钮 + switchTab；漫游页只做薄接线', () => {
  const detail = read('subpackageRoam/official-route/index.js')
  assert.match(detail, /follow\.writeHandoff\(wx, this\._plan/)
  assert.match(detail, /wx\.switchTab\(\{ url: '\/pages\/roam\/index' \}\)/)
  assert.doesNotMatch(detail, /暂未接/)
  const dw = read('subpackageRoam/official-route/index.wxml')
  assert.match(dw, /wx:if="\{\{canFollow\}\}"[^>]*bindtap="startFollow"/)
  assert.match(dw, /\{\{followHint\}\}/)

  const js = read('pages/roam/index.js')
  assert.match(js, /followRouteCtl\.consume\(this, app\)/)
  assert.match(js, /followRouteCtl\.onSessionOpened\(this\)/)
  assert.match(js, /followRouteCtl\.onMove\(this, p\)/)
  assert.match(js, /followRouteCtl\.tapMarker\(this, e\.detail\.markerId\)/)
  assert.match(js, /followRouteCtl\.exit\(this, true\);   \/\/ 结算屏/)
  assert.equal((js.match(/followRouteCtl\.withRoute\(this, \[/g) || []).length, 2, '两处轨迹线都拼上路线')
  const wxml = read('pages/roam/index.wxml')
  assert.match(wxml, /<cy-follow-route-hud hud="\{\{followRoute\}\}" bind:exit="exitFollowRoute" \/>/)
  assert.match(js, /exitFollowRoute\(\) \{ followRouteCtl\.exit\(this\); \}/)
})

test('G3:入口与列表/详情/HUD 统一为「官方线路」(漫游红线 R2 禁对外用「路线」)，两处一行两语句已拆开', () => {
  const wxml = read('pages/roam/index.wxml')
  assert.match(wxml, /aria-label="官方线路"[^>]*><text>官方线路<\/text>/)
  assert.match(read('subpackageRoam/official-routes/index.wxml'), /title="官方线路"/)
  assert.match(read('subpackageRoam/official-route/index.wxml'), /按这条线路开始/)
  const strip = (s) => s.replace(/<!--[\s\S]*?-->/g, '').replace(/^\s*\/\/.*$/gm, '').replace(/\/\*[\s\S]*?\*\//g, '')
  for (const f of ['subpackageRoam/official-routes/index.wxml', 'subpackageRoam/official-routes/index.json', 'subpackageRoam/official-route/index.wxml', 'subpackageRoam/official-route/index.json', 'subpackageRoam/official-route/index.js', 'pages/roam/components/follow-route-hud/index.wxml', 'utils/roam-follow-route.js', 'pages/roam/follow-route.js']) {
    assert.doesNotMatch(strip(read(f)), /路线/, f)
  }
  assert.doesNotMatch(strip(wxml), /官方路线|跟随官方路线/)
  const js = read('pages/roam/index.js')
  assert.match(js, /^const RUNNERS_MS = 45000;$/m)
  assert.match(js, /^const ROAM_POI_RADIUS_M = 3000;$/m)
  assert.match(js, /^  openOfficialRoutes\(\) \{ wx\.navigateTo\(\{ url: '\/subpackageRoam\/official-routes\/index' \}\); \},$/m)
  assert.match(js, /^  goStartAndShoot\(\) \{ this\._shootAfterStart = true; this\.goStart\(\); \},$/m)
})
