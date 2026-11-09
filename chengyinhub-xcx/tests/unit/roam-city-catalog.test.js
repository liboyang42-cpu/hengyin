'use strict'
const { test } = require('node:test')
const assert = require('node:assert/strict')
const raw = require('../../subpackageRoam/data/roam-city-catalog.js')
const catalog = require('../../subpackageRoam/utils/roam-city-catalog.js')
const { presentationForPoi } = require('../../utils/roam-poi-presentation.js')
const { buildImport, coordinateTemplate } = require('../../../scripts/roam-catalog-import.js')

test('11城33处真实目录身份独立，四类与来源可检查，未伪造坐标或合作身份', () => {
  assert.equal(raw.cities.length, 11)
  assert.equal(raw.places.length, 33)
  assert.equal(new Set(raw.places.map(row => row.id)).size, raw.places.length)
  for (const city of raw.cities) assert.equal(catalog.listPlaces(city.id, 'all').length, 3)
  for (const place of raw.places) {
    assert.ok(raw.cities.some(city => city.id === place.city))
    assert.ok(['street','park','shop','landmark'].includes(place.category))
    assert.equal(place.coordinate, null)
    assert.ok(place.address && place.reason && place.visitNote)
    assert.equal(place.reviewMethod, 'public_sources')
    assert.ok(new Set(place.sources).size >= 2)
    for (const url of place.sources) assert.match(url, /^https:\/\/[^\s]+$/)
    for (const key of ['merchantId','templateId','couponId','found','xp']) assert.equal(Object.hasOwn(place, key), false)
  }
})
test('城市和分类严格筛选，未知条件不会泄漏其他城市，行选择保持稳定', () => {
  assert.deepEqual(catalog.listPlaces('BJ','shop').map(row => row.id), ['bj-zhengyang-books'])
  assert.equal(catalog.listPlaces('HZ','shop').length, 0)
  assert.equal(catalog.listPlaces('unknown','all').length, 0)
  assert.equal(catalog.listPlaces('SH','unknown').length, 0)
  assert.equal(catalog.findPlace('unknown'), null)
  const row = catalog.findPlace('bj-zhengyang-books')
  row.name = 'local edit'
  assert.equal(catalog.findPlace('bj-zhengyang-books').name, '正阳书局（西四店）')
})
test('公园与街道按明确标签展示，普通推荐店铺不进入商家打卡链', () => {
  assert.equal(presentationForPoi({type:1,tags:'["roam:park"]'}).cat, 'park')
  assert.equal(presentationForPoi({type:1,tags:['roam:street']}).catLabel, '宝藏街道')
  const shop = presentationForPoi({type:1,tags:['roam:shop']})
  assert.equal(shop.cat, 'landmark')
  assert.equal(shop.catLabel, '推荐店铺')
  assert.equal(presentationForPoi({type:1,name:'公园咖啡',tags:'bad json'}).cat, 'landmark')
  assert.equal(presentationForPoi({type:1,tags:'{"roam:park":true}'}).cat, 'landmark')
})
test('合作标签必须有真实商家关联，分类标签不能覆盖type=2的既有业务链', () => {
  assert.equal(presentationForPoi({type:2,merchantId:7,tags:['roam:park']}).cat, 'merchant')
  assert.equal(presentationForPoi({type:2,merchantId:7}).catLabel, '合作商家 · 探店')
  assert.equal(presentationForPoi({type:2,merchantId:null}).catLabel, '商家据点')
  assert.equal(presentationForPoi({type:2,merchantId:'invalid'}).catLabel, '商家据点')
})

const example = raw.places[0]
const fixtureCatalog = {places:[example]}
const now = new Date('2026-10-24T06:00:00Z')
// Synthetic fixture only. This is not a production coordinate manifest.
function verified(overrides) {
  return Object.assign({}, coordinateTemplate(fixtureCatalog)[0], {
    provider:'amap',providerPoiId:'fixture-only',verificationSource:'https://www.amap.com/place/fixture-only',
    lat:31.25,lng:121.5,verifiedOn:'2026-10-23',matchConfirmed:true,
  }, overrides)
}
test('原始坐标模板不能生成导入文件，缺行、空值和未确认入口全部拒绝', () => {
  assert.equal(coordinateTemplate(raw).length, 33)
  assert.throws(() => buildImport(fixtureCatalog, coordinateTemplate(fixtureCatalog), null, now))
  assert.throws(() => buildImport(fixtureCatalog, [], null, now))
  for (const overrides of [{lat:null},{lng:''},{matchConfirmed:false},{lat:Infinity},{lat:NaN}]) {
    assert.throws(() => buildImport(fixtureCatalog, [verified(overrides)], null, now))
  }
})
test('坐标城市、坐标系、名称地址及来源必须一致，不接受同名误匹配', () => {
  for (const overrides of [
    {city:'BJ'},{lat:39.9,lng:116.4},{coordinateSystem:'WGS-84'},
    {name:'other'},{address:'other'},{providerPoiId:''},{provider:'unknown'},
    {verificationSource:'https://www.amap.com.evil.invalid/place/1'},
  ]) assert.throws(() => buildImport(fixtureCatalog, [verified(overrides)], null, now))
})
test('过期、未来或伪日期核验不发布，重复与未知身份不合并', () => {
  for (const verifiedOn of ['2025-01-01','2026-10-25','2026-02-30','yesterday']) {
    assert.throws(() => buildImport(fixtureCatalog, [verified({verifiedOn})], null, now))
  }
  assert.throws(() => buildImport(fixtureCatalog, [verified(),verified()], null, now))
  assert.throws(() => buildImport(fixtureCatalog, [verified({id:'unknown'})], null, now))
  assert.throws(() => buildImport(fixtureCatalog, [verified()], 'unknown', now))
})
test('导入保持下架type=1，不造合作商家和券；按稳定标签去重并附回读', () => {
  const sql = buildImport(fixtureCatalog, [verified()], 'SH', now)
  assert.match(sql, /START TRANSACTION;/)
  assert.match(sql, /, 1, 31\.250000, 121\.500000, 80,/)
  assert.match(sql, /, 0, NULL, NULL, NULL,/)
  assert.match(sql, /WHERE NOT EXISTS/)
  assert.match(sql, /JSON_CONTAINS/)
  assert.match(sql, /COMMIT;/)
  assert.match(sql, /SELECT id, name, type, city_code, status/)
  assert.doesNotMatch(sql, /UPDATE |DELETE |DROP |TRUNCATE |CREATE TABLE/i)
})
test('SQL文本使用UTF8十六进制，目录文字不能注入额外语句', () => {
  const hostile = Object.assign({},example,{name:"quote'; DELETE FROM roam_poi; --"})
  const sql = buildImport({places:[hostile]}, [verified({name:hostile.name})], null, now)
  assert.doesNotMatch(sql, /DELETE FROM/)
  assert.match(sql, /CONVERT\(0x[0-9a-f]+ USING utf8mb4\)/)
})

// 2026-11-09 V3-35 业主「没有这个城市漫游清单 这个应该直接展示在地图上的」:清单组件删除,
// 目录地点(已上架、带种子标签的平台点位)由漫游入口地图画成图钉,点开走同一个理由组件。
test('城市漫游清单下线:组件与入口按钮都不在,目录地点改由入口地图画图钉', () => {
  const fs = require('node:fs')
  const path = require('node:path')
  const root = path.resolve(__dirname, '../..')
  assert.equal(fs.existsSync(path.join(root, 'subpackageRoam/components/roam-city-guide')), false)
  const wxml = fs.readFileSync(path.join(root, 'pages/roam/index.wxml'), 'utf8')
  const js = fs.readFileSync(path.join(root, 'pages/roam/index.js'), 'utf8')
  assert.doesNotMatch(wxml, /cy-roam-city-guide|openCityGuide|intro-guide-hit/)
  assert.doesNotMatch(js, /cityGuideOpen|openCityGuide|closeCityGuide/)
  assert.match(js, /req\('\/api\/roam\/pois', 'GET'[\s\S]*entryMap\.isCatalogPlace/)
  assert.match(wxml, /<cy-roam-place-note tags="\{\{entryPlace\.tags\}\}" bind:note="onEntryPlaceNote" \/>/)
})

test('地图点位按种子标签取目录里的推荐理由和来源，查不到就不显示', () => {
  const note = catalog.placeNoteForTags('["roam:park","roam:seed:v1:sh-shanyin-road"]')
  assert.equal(note.reason, raw.places[0].reason)
  assert.equal(note.sources.length, 2)
  assert.match(note.sourceText, /shio\.gov\.cn · shhk\.gov\.cn/)
  assert.equal(catalog.placeNoteForTags('["roam:park"]'), null)
  assert.equal(catalog.placeNoteForTags('["roam:seed:v1:unknown"]'), null)
  assert.equal(catalog.placeNoteForTags('山阴路'), null)
  assert.equal(catalog.placeNoteForTags(null), null)
})

test('目录、目录组件和地点卡理由组件都不进主包，漫游页经占位异步引用', () => {
  const fs = require('node:fs')
  const path = require('node:path')
  const root = path.resolve(__dirname, '../..')
  const app = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'))
  const roam = (app.subPackages || app.subpackages).find(row => row.root === 'subpackageRoam')
  assert.ok(roam, 'subpackageRoam 必须是分包')
  for (const file of ['subpackageRoam/data/roam-city-catalog.js', 'subpackageRoam/utils/roam-city-catalog.js',
    'subpackageRoam/components/roam-place-note/index.js']) {
    assert.ok(fs.existsSync(path.join(root, file)), file)
  }
  for (const gone of ['data/roam-city-catalog.json', 'utils/roam-city-catalog.js', 'components/cy/roam-city-guide']) {
    assert.equal(fs.existsSync(path.join(root, gone)), false, gone + ' 不能留在主包')
  }
  const page = JSON.parse(fs.readFileSync(path.join(root, 'pages/roam/index.json'), 'utf8'))
  assert.equal(page.usingComponents['cy-roam-city-guide'], undefined)
  assert.equal(page.componentPlaceholder['cy-roam-place-note'], 'view')
  const js = fs.readFileSync(path.join(root, 'pages/roam/index.js'), 'utf8')
  assert.doesNotMatch(js, /roam-city-catalog/, '主包漫游页不得直接 require 目录')
  const wxml = fs.readFileSync(path.join(root, 'pages/roam/index.wxml'), 'utf8')
  // 第九轮 m1 第 9 项:地址行仍是同一行 .pc-body,只加了点开地图
  assert.match(wxml, /<text class="pc-body cy-addr-tap" wx:if="\{\{paceCard\.address\}\}" catchtap="onAddrTap"[^>]*>\{\{paceCard\.address\}\}<\/text>/)
  assert.match(wxml, /<text class="pc-body" wx:if="\{\{paceCard\.reason\}\}">\{\{paceCard\.reason\}\}<\/text>/)
  assert.match(wxml, /<cy-roam-place-note wx:if="\{\{paceCard\.tags\}\}" tags="\{\{paceCard\.tags\}\}" bind:note="onPlaceNote" \/>/)
})

test('地点卡理由组件把查表结果交回页面，换卡后的迟到回填被丢弃', () => {
  const previousComponent = global.Component
  let definition
  global.Component = value => { definition = value }
  try {
    require('../../subpackageRoam/components/roam-place-note/index.js')
    const events = []
    const instance = { triggerEvent: (name, detail) => events.push([name, detail]) }
    const tags = '["roam:shop","roam:seed:v1:bj-zhengyang-books"]'
    definition.properties.tags.observer.call(instance, tags)
    assert.equal(events[0][0], 'note')
    assert.equal(events[0][1].tags, tags)
    assert.match(events[0][1].reason, /万松老人塔/)
    assert.match(events[0][1].sources, /beijing\.gov\.cn/)
    definition.properties.tags.observer.call(instance, '["roam:park"]')
    assert.deepEqual(events[1][1], { tags: '["roam:park"]', reason: '', sources: '' })
  } finally { global.Component = previousComponent }
  const fs = require('node:fs')
  const path = require('node:path')
  const js = fs.readFileSync(path.resolve(__dirname, '../../pages/roam/index.js'), 'utf8')
  assert.match(js, /onPlaceNote\(e\) \{[\s\S]{0,200}card\.tags !== d\.tags\) return;/)
})
