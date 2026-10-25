/* 2026-10-22 漫游地图三类点位 / 筛选 / 边缘缩小(subpackageRoam/utils/map-layers.js)。
   钉住的事实:筛选选项只来自真实数据;边缘缩小不消失,远处不画;「我」永远保留。 */
const test = require('node:test')
const assert = require('node:assert/strict')
const L = require('../../subpackageRoam/utils/map-layers.js')

const C = { lat: 31.23, lng: 121.47 }
const colors = { place: '#4ADE80', shop: '#E7A84B', plateText: '#132A2E' }

test('筛选选项:地点 = 本屏主题/活动 + 地标标签;商家 = 商家标签;不预置分类', () => {
  const o = L.filterOptions(
    [{ type: 1, tags: '公园，滨江' }, { type: 2, tags: '咖啡、餐饮' }, { type: 2, tags: '咖啡' }],
    [{ kind: 'topic', productType: 2 }, { kind: 'activity' }, { kind: 'topic', productType: 2 }])
  assert.deepEqual(o.place, ['全部地点', '主题', '活动', '公园', '滨江'])
  assert.deepEqual(o.shop, ['全部商家', '咖啡', '餐饮'])
  assert.deepEqual(L.filterOptions([], []), { place: ['全部地点'], shop: ['全部商家'] })
})

test('筛选:队伍 / 地点子类 / 商家子类各管各的', () => {
  const f = (layer, sub = '') => ({ layer, sub })
  assert.equal(L.keepTeam(f('all')), true); assert.equal(L.keepTeam(f('shop')), false)
  assert.equal(L.keepItem({ kind: 'topic', productType: 2 }, f('place', '主题')), true)
  assert.equal(L.keepItem({ kind: 'activity' }, f('place', '主题')), false)
  assert.equal(L.keepItem({ kind: 'topic', productType: 2 }, f('team')), false)
  assert.equal(L.keepPoi({ type: 2, tags: '咖啡' }, f('shop', '咖啡')), true)
  assert.equal(L.keepPoi({ type: 2, tags: '餐饮' }, f('shop', '咖啡')), false)
  assert.equal(L.keepPoi({ type: 1, tags: '公园' }, f('shop')), false)
})

test('城市定向不能借全部或主题筛选重新出现', () => {
  for (const productType of [1, '1', null, undefined]) {
    const city = { kind: 'topic', productType };
    for (const filter of [undefined, { layer: 'all' }, { layer: 'place', sub: '主题' }])
      assert.equal(L.keepItem(city, filter), false);
    assert.deepEqual(L.filterOptions([], [city]).place, ['全部地点']);
  }
});

test('地标 / 商家点:id 尾号 6/7 不与既有 1–4、9 撞号;近处放大挂名牌;无坐标不画', () => {
  const ms = L.buildPoiMarkers([
    { id: 3, type: 1, name: '老钟楼', lat: C.lat, lng: C.lng },
    { id: 5, type: 2, name: '旧城咖啡', lat: C.lat + 0.02, lng: C.lng },
    { id: 6, type: 2, name: '无坐标', lat: null, lng: 121 },
  ], C, { '#4ADE80': 'g.png' }, colors, 'fb.png')
  assert.equal(ms.length, 2)
  assert.equal(ms[0].id, 36); assert.equal(ms[0].iconPath, 'g.png'); assert.equal(ms[0].callout.content, '老钟楼')
  assert.equal(ms[1].id, 57); assert.equal(ms[1].iconPath, 'fb.png'); assert.equal(ms[1].callout, undefined)
  assert.deepEqual(L.parsePoiMarkerId(57), { kind: 'shop', id: 5 })
  assert.equal(L.parsePoiMarkerId(74), null)
})

test('边缘缩小不消失、远处不画、「我」永远在', () => {
  const at = (dLat) => ({ latitude: C.lat + dLat, longitude: C.lng })
  const out = L.edgeLod([
    Object.assign({ id: 14, width: 54, height: 54, callout: { content: 'x' } }, at(0.002)),   // ~220m:原样
    Object.assign({ id: 24, width: 54, height: 54, callout: { content: 'y' } }, at(0.0081)),  // ~900m:缩小去名牌
    Object.assign({ id: 34, width: 54, height: 54 }, at(0.02)),                               // ~2.2km:不画
    Object.assign({ id: 9, width: 58, height: 58 }, at(0.05)),                                // 我
  ], C, 1000)
  assert.deepEqual(out.map((m) => m.id), [14, 24, 9])
  assert.equal(out[0].width, 54); assert.ok(out[0].callout)
  assert.equal(out[1].width, 33); assert.equal(out[1].callout, undefined)
})

test('点位卡只放后端给的字段', () => {
  const c = L.poiCard({ id: 5, type: 2, name: '', merchantName: '旧城咖啡', tags: '咖啡', found: true, lat: '31.2', lng: '121.4' })
  assert.equal(c.title, '旧城咖啡'); assert.equal(c.tag, '商家'); assert.equal(c.found, true)
  assert.equal(c.desc, ''); assert.equal(c.lat, 31.2)
})
