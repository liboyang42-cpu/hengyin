const { test, afterEach } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

// 原生地图气泡/标签不吃 --cy-type-scale。它们必须按 utils/font-scale.js 的同一倍率
// (1~1.3,只放大)在 JS 里算字号,否则手机调大字号后地图上的字永远不变。

const ROOT = path.resolve(__dirname, '../..')
const read = (f) => fs.readFileSync(path.join(ROOT, f), 'utf8')
const fresh = (rel) => {
  const full = path.join(ROOT, rel)
  delete require.cache[require.resolve(full)]
  return require(full)
}

function withFontSetting(px) {
  global.wx = { getAppBaseInfo: () => ({ fontSizeSetting: px }) }
}
afterEach(() => { delete global.wx })

test('mapFontSize 用同一倍率并取整', () => {
  const { mapFontSize } = fresh('utils/font-scale.js')
  assert.equal(mapFontSize(11, 1), 11)
  assert.equal(mapFontSize(11, 1.3), 14)
  assert.equal(mapFontSize(9, 1.3), 12)
  withFontSetting(26) // 超过上限,夹到 1.3
  assert.equal(mapFontSize(11), 14)
  withFontSetting(12) // 调小不缩
  assert.equal(mapFontSize(11), 11)
})

test('漫游约局气泡跟随字号', () => {
  const items = [{ kind: 'hangout', id: 1, title: '打 UNO', latitude: 31.23, longitude: 121.47, distance: 120, ownerNickname: '小明' }]
  const icons = { 'hg-player-open-%E5%B0%8F-': '/tmp/near.png' }
  withFontSetting(16)
  const { buildHangoutMarkers } = fresh('utils/roam-hangout.js')
  const normal = buildHangoutMarkers(items, icons, {}).find((m) => m.callout)
  assert.ok(normal, '近距点应带名牌')
  assert.equal(normal.callout.fontSize, 11)
  withFontSetting(20.8)
  const large = buildHangoutMarkers(items, icons, {}).find((m) => m.callout)
  assert.equal(large.callout.fontSize, 14)
})

test('主题路线预览气泡跟随字号', () => {
  const { build } = fresh('utils/topic-route-preview.js')
  const chapters = [{ nodes: [{ latitude: 31.2, longitude: 121.4, name: 'A' }, { latitude: 31.21, longitude: 121.41, name: 'B' }] }]
  withFontSetting(16)
  assert.equal(build(chapters).routeMarkers[0].callout.fontSize, 11)
  withFontSetting(20.8)
  assert.equal(build(chapters).routeMarkers[0].callout.fontSize, 14)
})

test('发布页路线地图编号标签跟随字号', () => {
  const view = fresh('pages/publish/utils/publish/route-map-view.js')
  const state = { chapters: [{ _localId: 'c', nodes: [
    { _localId: 'a', latitude: 31.2, longitude: 121.4 },
    { _localId: 'b', latitude: 31.21, longitude: 121.41 },
  ] }] }
  withFontSetting(16)
  assert.equal(view.buildRouteMapView(state).patch.mapMarkers[0].label.fontSize, 11)
  withFontSetting(20.8)
  assert.equal(view.buildRouteMapView(state).patch.mapMarkers[0].label.fontSize, 14)
})

test('页面与组件里的地图气泡不再写死字号', () => {
  const files = [
    'pages/roam/index.js',
    'components/cy/free-map/index.js',
    'utils/roam-hangout.js',
    'utils/topic-route-preview.js',
    'pages/publish/utils/publish/route-map-view.js',
    'pages/searchmap/index.js',
  ]
  for (const f of files) {
    const src = read(f)
    assert.doesNotMatch(src, /fontSize:\s*(?:n\.reviewLabel \? )?\d/, f + ' 仍有写死的地图字号')
    assert.match(src, /mapFontSize\(/, f + ' 应走 mapFontSize')
  }
  // roam 页 5 处气泡共用一次读出的倍率
  const roam = read('pages/roam/index.js')
  assert.equal((roam.match(/fontSize: calloutFont,/g) || []).length, 5)
})
