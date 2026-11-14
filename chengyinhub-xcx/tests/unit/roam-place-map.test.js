const { test } = require('node:test')
const assert = require('node:assert/strict')
const PM = require('../../utils/roam-place-map.js')
const entryMap = require('../../utils/roam-entry-map.js')
const L = require('../../subpackageRoam/utils/map-layers.js')
const guide = require('../../subpackageRoam/utils/roam-city-guide.js')

const seedTags = JSON.stringify(['roam:park', 'roam:seed:v1:sh-lu-xun-park', '树荫散步'])

test('A4.1 JSON 标签串先解析,roam:* 保留标签不给玩家看,老逗号串照旧', () => {
  assert.deepEqual(PM.visibleTags(seedTags), ['树荫散步'])
  assert.deepEqual(PM.visibleTags('咖啡, 安静,roam:shop'), ['咖啡', '安静'])
  assert.deepEqual(L.splitTags(seedTags), ['树荫散步'])
  assert.equal(L.filterOptions([{ type: 1, tags: seedTags }], []).place.includes('树荫散步'), true)
  assert.equal(L.filterOptions([{ type: 1, tags: seedTags }], []).place.some((t) => /[[\]"]|roam:/.test(t)), false)
})

test('A1.2 入口图画全部已上架点,种子标签只决定理由', () => {
  const shop = { id: 3, type: 2, lat: 31.2, lng: 121.4, name: '咖啡' }
  const plain = { id: 4, type: 1, lat: 31.2, lng: 121.41, name: '公园', tags: '' }
  const seed = { id: 5, type: 1, lat: 31.21, lng: 121.4, name: '山阴路', tags: seedTags }
  const noGeo = { id: 6, type: 1, name: '无坐标' }
  assert.deepEqual([shop, plain, seed, noGeo].filter(PM.isRoamPlace).map((r) => r.id), [3, 4, 5])
  assert.deepEqual([shop, plain, seed].filter(entryMap.isCatalogPlace).map((r) => r.id), [5])
  const view = entryMap.build([], [], { places: true }, (v) => v, [shop, plain, seed, noGeo], { scale: 16 })
  assert.equal(view.markers.length, 3)
})

test('A1.3 分类图钉、近景名字;远景聚合并写数量', () => {
  const rows = [
    { id: 1, type: 1, lat: 31.2, lng: 121.4, name: '鲁迅公园', tags: seedTags },
    { id: 2, type: 1, lat: 31.2001, lng: 121.4001, name: '山阴路', tags: '["roam:street"]' },
    { id: 3, type: 2, lat: 31.21, lng: 121.41, name: '咖啡' },
  ]
  const near = PM.buildPins(rows, { scale: 16 })
  assert.deepEqual(near.markers.map((m) => m.iconPath.split('/').pop()), ['pin-park.png', 'pin-street.png', 'pin-merchant.png'])
  assert.equal(near.markers[0].callout.content, '鲁迅公园')
  const mid = PM.buildPins(rows, { scale: 14.5 })
  assert.equal(mid.markers.some((m) => m.callout), false)
  const region = { southwest: { latitude: 31.19, longitude: 121.39 }, northeast: { latitude: 31.22, longitude: 121.42 } }
  const far = PM.buildPins(rows, { scale: 12, region, viewport: { width: 390, height: 844 } })
  const cluster = far.markers.find((m) => m.iconPath.endsWith('dot-cluster.png'))
  assert.equal(cluster.callout.content, '2')
  assert.deepEqual(PM.parsePinMarker(cluster.id), { kind: 'cluster', index: 0 })
  assert.equal(far.clusters.length, 1)
})

test('A2.5 视口裁剪:视口外的点不建 marker', () => {
  const region = { southwest: { latitude: 31.19, longitude: 121.39 }, northeast: { latitude: 31.22, longitude: 121.42 } }
  const rows = [{ id: 1, type: 1, lat: 31.2, lng: 121.4 }, { id: 2, type: 1, lat: 39.9, lng: 116.4 }]
  assert.deepEqual(PM.buildPins(rows, { scale: 16, region }).markers.map((m) => m.id), [16])
})

test('A1.5 半径取自视口外接圆并夹在上下限内;位移超半径一半才重拉', () => {
  const region = { southwest: { latitude: 31.2, longitude: 121.4 }, northeast: { latitude: 31.26, longitude: 121.46 } }
  const r = PM.radiusFromRegion(region, 3000)
  assert.ok(r > 4000 && r < 6000)
  assert.equal(PM.radiusFromRegion(null, 3000), 3000)
  assert.equal(PM.radiusFromRegion({ southwest: { latitude: 0, longitude: 0 }, northeast: { latitude: 40, longitude: 40 } }, 3000), PM.MAX_RADIUS_M)
  assert.equal(PM.shouldRefetch({ lat: 31.2, lng: 121.4 }, { lat: 31.2, lng: 121.401 }, 3000), false)
  assert.equal(PM.shouldRefetch({ lat: 31.2, lng: 121.4 }, { lat: 31.2, lng: 121.43 }, 3000), true)
})

test('A2.2 合并去重:新覆盖旧,远处旧点丢掉', () => {
  const prev = [{ id: 1, name: '旧', lat: 31.2, lng: 121.4 }, { id: 2, lat: 31.2, lng: 121.4 }, { id: 9, lat: 39.9, lng: 116.4 }]
  const next = [{ id: 1, name: '新', lat: 31.2, lng: 121.4 }]
  const merged = PM.mergePlaces(prev, next, { lat: 31.2, lng: 121.4 }, 3000)
  assert.deepEqual(merged.map((r) => [r.id, r.name]), [[1, '新'], [2, undefined]])
})

test('A2.3 距离只写真实值,没有位置就不写', () => {
  const row = { lat: 31.2, lng: 121.41 }
  assert.equal(PM.distanceText(PM.distanceFrom(null, row)), '')
  assert.match(PM.distanceText(PM.distanceFrom({ lat: 31.2, lng: 121.4 }, row)), /^\d+ 米$|公里$/)
  assert.equal(PM.gapText(120.4), '还差 120 米')
  assert.equal(PM.gapText(0), '')
})

test('A2.1 剪影只在玩家 1 公里内', () => {
  const near = { lat: 31.2, lng: 121.405 }
  const far = { lat: 31.3, lng: 121.4 }
  assert.equal(PM.isMysteryVisible(near, { lat: 31.2, lng: 121.4 }), true)
  assert.equal(PM.isMysteryVisible(far, { lat: 31.2, lng: 121.4 }), false)
  assert.equal(PM.isMysteryVisible(near, null), false)
})

test('A4.2 推荐理由唯一来源:quotes 优先,其次目录 note;非商家 description 不直出', () => {
  assert.equal(PM.reasonFor({ quotes: [{ text: '好去处', source: 'x' }], description: '内部说明' }, { reason: '目录理由' }), '好去处')
  assert.equal(PM.reasonFor({ quotes: '[]', description: '内部说明' }, { reason: '目录理由' }), '目录理由')
  assert.equal(PM.reasonFor({ description: '内部说明' }, null), '')
  const card = L.poiCard({ id: 1, type: 1, name: '山阴路', description: '内部说明', tags: JSON.stringify(['roam:street', 'roam:seed:v1:sh-shanyin-road']) })
  assert.notEqual(card.desc, '内部说明')
  assert.match(card.desc, /里弄/)
  assert.equal(card.sourceList.length > 0, true)
  assert.equal(L.poiCard({ id: 2, type: 2, name: '店', description: '店家介绍' }).desc, '店家介绍')
})

test('W1 新字段防御式读取:缺了照旧,有了就用', () => {
  assert.equal(PM.coverKey({ coverImg: 'a.jpg' }), 'a.jpg')
  assert.equal(PM.coverKey({ cover: 'b.jpg', coverImg: 'a.jpg' }), 'b.jpg')
  assert.deepEqual(PM.galleryKeys({ gallery: '["g1.jpg","g2.jpg"]' }), ['g1.jpg', 'g2.jpg'])
  assert.equal(PM.credit({ photo_credit: '摄影' }), '摄影')
  assert.equal(PM.nodeLevel({ node_level: 3 }), 3)
  assert.equal(PM.nodeLevel({}), 1)
  const flat = PM.unwrapPois({ data: [{ id: 1 }] })
  assert.deepEqual([flat.rows.length, flat.truncated], [1, false])
  const wrapped = PM.unwrapPois({ data: { rows: [{ id: 1 }], truncated: true } })
  assert.deepEqual([wrapped.rows.length, wrapped.truncated], [1, true])
  assert.equal(PM.unwrapPois({ data: 'x' }), null)
})

test('A2.4 / A1.4 入口卡片带含地点卡,图层可关,搜索含地点', () => {
  const rows = [{ id: 7, type: 1, lat: 31.2, lng: 121.4, name: '鲁迅公园', address: '虹口', coverImg: 'p/a.jpg', tags: seedTags }]
  const on = entryMap.build([], [], { places: true }, (v) => '/img/' + v, rows, { scale: 16, origin: { lat: 31.2, lng: 121.41 } })
  assert.equal(on.placeCards[0].cover, '/img/p/a.jpg')
  assert.match(on.placeCards[0].meta, /米|公里/)
  assert.equal(on.searchPlaces[0].name, '鲁迅公园')
  const off = entryMap.build([], [], { places: false }, (v) => v, rows, { scale: 16 })
  assert.deepEqual([off.markers.length, off.placeCards.length], [0, 0])
  const noOrigin = entryMap.build([], [], { places: true }, (v) => v, rows, { scale: 16 })
  assert.equal(noOrigin.placeCards[0].meta, '虹口')
})

test('B5.8 本城推荐:按地图中心找最近的目录城市,不依赖定位', () => {
  assert.equal(guide.nearestCity({ lat: 31.23, lng: 121.47 }), 'SH')
  assert.equal(guide.nearestCity({ lat: 0, lng: 0 }), '')
  const strip = guide.stripFor('SH')
  assert.equal(strip.name, '上海')
  assert.equal(strip.count, strip.items.length)
  assert.match(strip.items[0].seed, /^roam:seed:v1:/)
  assert.equal(guide.stripFor('XX'), null)
  assert.equal(guide.stripFor(guide.defaultCity()).count > 0, true)
})
