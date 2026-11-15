const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const read = (file) => fs.readFileSync(path.join(__dirname, '../..', file), 'utf8')
const mapStyle = require('../../utils/map-style.js')
const mapSwitch = require('../../utils/map-style-switch.js')
const mapSnap = require('../../utils/map-snapshot.js')

const BOUND = { subkey: 'k', black: 2, white: 1, paper: 3, cyber: 4 }

function memoryStore(initial) {
  const data = Object.assign({}, initial)
  return { getStorageSync: (k) => data[k], setStorageSync: (k, v) => { data[k] = v }, data }
}

test('绑定序号:white 1 / black 2 / paper 3 / 2077 4;仓库里 subkey 空、序号 0,没配置回官方默认', () => {
  assert.deepEqual(mapStyle.mapStyleFor('white', BOUND), { subkey: 'k', layerStyle: 1 })
  assert.deepEqual(mapStyle.mapStyleFor('black', BOUND), { subkey: 'k', layerStyle: 2 })
  assert.deepEqual(mapStyle.mapStyleFor('paper', BOUND), { subkey: 'k', layerStyle: 3 })
  assert.deepEqual(mapStyle.mapStyleFor('2077', BOUND), { subkey: 'k', layerStyle: 4 })
  assert.equal(mapStyle.MAP_STYLE.subkey, '')
  ;['black', 'white', 'paper', 'cyber'].forEach((f) => assert.equal(mapStyle.MAP_STYLE[f], 0))
  assert.deepEqual(mapStyle.mapStyleFor('paper'), { subkey: '', layerStyle: 1 })
})

test('自由图选择:默认 black;读写走 try/catch,坏值与存储异常都回 black', () => {
  assert.equal(mapStyle.freeMapKind(memoryStore({})), 'black')
  assert.equal(mapStyle.freeMapKind(memoryStore({ cy_map_kind: 'paper' })), 'paper')
  assert.equal(mapStyle.freeMapKind(memoryStore({ cy_map_kind: 'neon' })), 'black')
  const broken = { getStorageSync() { throw new Error('denied') }, setStorageSync() { throw new Error('denied') } }
  assert.equal(mapStyle.freeMapKind(broken), 'black')
  assert.equal(mapStyle.saveFreeMapKind('2077', broken), false)
  const store = memoryStore({})
  assert.equal(mapStyle.saveFreeMapKind('2077', store), true)
  assert.equal(store.data.cy_map_kind, '2077')
  assert.equal(mapStyle.saveFreeMapKind('neon', store), false)
  assert.equal(store.data.cy_map_kind, '2077')
})

test('路线地图按发布时外观取底;编辑器 / 商家 / 创作者预览一律 white', () => {
  assert.equal(mapStyle.kindForLookJson('{"style":"PAPER"}'), 'paper')
  assert.equal(mapStyle.kindForLookJson({ style: 'CYBER' }), '2077')
  assert.equal(mapStyle.kindForLookJson('{"style":"STARDUST"}'), 'black')
  assert.equal(mapStyle.kindForLookJson(''), 'black')
  assert.equal(mapStyle.kindForLookJson('not json'), 'black')
  assert.equal(mapStyle.routeMapKind('{"style":"PAPER"}', false), 'paper')
  assert.equal(mapStyle.routeMapKind('{"style":"PAPER"}', true), 'white')
})

test('切换:先撤图、下一拍按新样式重建,选择写入存储;同一选择不重建', () => {
  const calls = []
  global.wx = { nextTick: (fn) => calls.push(fn), ...memoryStore({}) }
  const page = Object.assign({ data: mapSwitch.initialData(), rebuilt: 0 }, mapSwitch.methods(), { _onMapKindRebuilt() { this.rebuilt++ } })
  page.setData = (patch, cb) => { Object.assign(page.data, patch); if (cb) cb() }
  assert.equal(page.data.fmKind, 'black')
  page._applyMapKind('paper', true)
  assert.equal(page.data.fmStyleReady, false)
  assert.equal(calls.length, 1)
  calls[0]()
  assert.equal(page.data.fmStyleReady, true)
  assert.equal(page.data.fmKind, 'paper')
  assert.equal(page.data.mapKindIndex, 2)
  assert.equal(page.rebuilt, 1)
  assert.equal(global.wx.data.cy_map_kind, 'paper')
  page._applyMapKind('paper', true)
  assert.equal(calls.length, 1)
  global.wx.data.cy_map_kind = 'white'
  page._syncMapKindPref()
  calls[1]()
  assert.equal(page.data.fmKind, 'white')
  delete global.wx
})

test('漫游与附近都接了切换;底图重建后 free-map 带 tone', () => {
  const roamJs = read('pages/roam/index.js')
  const roamWxml = read('pages/roam/index.wxml')
  assert.match(roamJs, /\.\.\.mapSwitch\.methods\(\)/)
  assert.match(roamJs, /_onMapKindRebuilt\(\) \{[^}]*getMapContext/)
  assert.match(roamWxml, /catchtap="onMapKindTap"/); assert.match(roamJs, /onMapKindTap\(e\) \{/)
  ;(roamWxml.match(/<free-map\b[^>]*>/g) || []).forEach((tag) => {
    assert.match(tag, /wx:if="\{\{fmStyleReady\}\}"/)
    assert.match(tag, /tone="\{\{fmTone\}\}"/)
  })
  const nearJs = read('subpackageRoam/nearby/index.js')
  const nearWxml = read('subpackageRoam/nearby/index.wxml')
  assert.match(nearJs, /\.\.\.mapSwitch\.methods\(\)/)
  assert.match(nearJs, /onShow\(\) \{ this\._hidden = false; this\._syncMapKindPref\(\)/)
  assert.match(nearWxml, /<free-map id="fmap"[^>]*wx:if="\{\{fmStyleReady\}\}"[^>]*tone="\{\{fmTone\}\}"/)
  assert.match(nearWxml, /<cy-option-sheet show="\{\{mapKindSheetShow\}\}" title="地图样式"/)
})

test('topic 路线图:玩家按发布外观,商家视角 / 本人预览 white;square route-map 默认 white 且可传 look', () => {
  const topicJs = read('pages/topic/index/index.js')
  assert.match(topicJs, /routeMapKind\(info && info\.lookJson, isMerchantView\(\) \|\| Number\(info && info\.isOwner\) === 1\)/)
  assert.match(topicJs, /info: res\.data, mapSubkey: routeStyle\.mapSubkey, mapLayerStyle: routeStyle\.mapLayerStyle/)
  const routeMap = read('pages/square/components/route-map/index.js')
  assert.match(routeMap, /preview: \{ type: Boolean, value: true \}/)
  assert.match(routeMap, /routeMapKind\(that\.data\.look \|\| res\.data\.lookJson, that\.data\.preview\)/)
})

test('route-thumb 跟玩家选的底图并按浅底换线色', () => {
  const js = read('components/cy/route-thumb/index.js')
  assert.match(js, /freeMapKind\(\)/)
  assert.match(js, /toneLines\(/)
  assert.match(read('components/cy/route-thumb/index.wxml'), /polyline="\{\{lines\}\}"/)
  const lines = mapStyle.toneLines([{ color: '#FFFFFF', width: 5, borderColor: '#000', borderWidth: 1 }], 'white')
  assert.notEqual(lines[0].color, '#FFFFFF')
})

test('快照:cover 裁剪不拉伸;没渲染 / 截图失败 / 空路径都拒绝并带 mapSnapshot', async () => {
  const r = mapSnap.coverRect(400, 300, 800, 300)
  assert.deepEqual(r, { sx: 200, sy: 0, sw: 400, sh: 300 })
  assert.equal(mapSnap.coverRect(0, 300, 800, 300), null)
  const ok = { takeSnapshot: (o) => o.success({ tempImagePath: '/tmp/a.png' }) }
  assert.equal(await mapSnap.takeMapSnapshot(ok, () => true), '/tmp/a.png')
  await assert.rejects(mapSnap.takeMapSnapshot(ok, () => false, 10), (e) => e.mapSnapshot === true)
  await assert.rejects(mapSnap.takeMapSnapshot({ takeSnapshot: (o) => o.fail({}) }, () => true), (e) => e.mapSnapshot === true)
  await assert.rejects(mapSnap.takeMapSnapshot({ takeSnapshot: (o) => o.success({}) }, () => true), (e) => e.mapSnapshot === true)
  await assert.rejects(mapSnap.takeMapSnapshot(null), (e) => e.mapSnapshot === true)
})

test('漫游分享卡：优先真实快照，截图不可用时绘制本次真实轨迹', () => {
  const wxml = read('pages/roam/index.wxml')
  assert.match(wxml, /<map wx:if="\{\{shareArch\.archGeo\}\}" id="rshMap"[\s\S]*?bindupdated="onShareMapUpdated"/)
  assert.doesNotMatch(wxml, /class="rsh-seg"|class="rsh-route"/)
  const js = read('pages/roam/index.js')
  assert.match(js, /_shareMapImage\(cv, idx, arch\)/)
  assert.match(js, /mapSnap\.drawContain\(g, mapImage/)
  assert.match(js, /mapSnap\.drawRoute\(g, arch && arch\.archGeo/)
  const drawArch = js.slice(js.indexOf('  _drawShareArch('), js.indexOf('  /** idx 0=story'))
  assert.doesNotMatch(drawArch, /archSegs/)
})

test('DARK_MAP.md 改为绑定序号模型,不再让人填 styleId 或 MAP_DARK', () => {
  const md = read('components/cy/free-map/DARK_MAP.md')
  assert.match(md, /white\s*\|?\s*1|white 1/)
  assert.doesNotMatch(md, /MAP_DARK/)
  assert.doesNotMatch(md, /你的styleId/)
})
