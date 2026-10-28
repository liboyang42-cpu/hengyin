'use strict'
// 2026-10-27 真机走查 L06(漫游):护照方卡真实图片(P-29)、迷雾开关(P-32)、搜索面板(P-33)、结算页关闭(P-35)
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const root = path.join(__dirname, '..', '..')
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8')
const tiles = require('../../utils/roam-passport-tiles.js')
const fog = require('../../utils/roam-fog-preference.js')

test('P-29 护照方卡取最新轨迹 / 邮票 / 点亮勋章,拿不到返回空串', () => {
  assert.equal(tiles.latestStampImage([{ ts: 1, photos: ['a.jpg'] }, { ts: 2, photos: [{ path: 'b.jpg' }, 'c.jpg'] }]), 'c.jpg')
  assert.equal(tiles.latestStampImage([{ ts: 1, photos: [] }]), '')
  const uri = tiles.latestTrackThumb([{ ts: 1, track: [{ lat: 31.2, lng: 121.4 }, { lat: 31.21, lng: 121.41 }] }])
  assert.match(uri, /^data:image\/svg\+xml;base64,/)
  assert.match(Buffer.from(uri.split(',')[1], 'base64').toString(), /<polyline points="/)
  assert.equal(tiles.latestTrackThumb([{ ts: 1, track: [{ lat: 31.2, lng: 121.4 }] }]), '', '单点不画')
  assert.equal(tiles.latestBadgeImage([{ medalImg: 'x.png', getTime: '2026-01-01 10:00:00' }, { medalImg: 'y.png', getTime: '2026-03-01 10:00:00' }, { medalImg: '' }]), 'y.png')
  assert.equal(tiles.latestBadgeImage(null), '')
  assert.equal(tiles.asciiBase64('ab'), Buffer.from('ab').toString('base64'))
})

test('P-32 迷雾开关:默认开着,写失败返回 null', () => {
  const store = {}
  const ok = { getStorageSync: (k) => store[k], setStorageSync: (k, v) => { store[k] = v } }
  assert.equal(fog.readFogOff(ok), false)
  assert.equal(fog.writeFogOff(ok, true), true)
  assert.equal(fog.readFogOff(ok), true)
  assert.equal(fog.writeFogOff({ setStorageSync: () => { throw new Error('x') } }, true), null)
  const wxml = read('pages/roam/index.wxml')
  assert.match(wxml, /catchtap="toggleFogOff"/)
  assert.match(wxml, /<block wx:if="\{\{!fogOff\}\}">\s*<view class="fogscreen-guard"/)
  assert.match(wxml, /map-polygons="\{\{fogOff \? noFogPolys : fogPolys\}\}"/)
  assert.doesNotMatch(read('pages/roam/index.js'), /filter\(\(p\) => p\.state !== 'fog' && p\.lat && p\.lng\)/, '地点搜索不再过滤雾里的点')
  assert.doesNotMatch(read('utils/play-state-contract.js'), /(text|readerLabel): '[^']*暂未开放/)
})

// 2026-10-28 p115 业主:「正常也应该是之前的样式 白色的 底部对齐的 只是应该显示数据」⇒ 面板回到原来的白底
test('P-33 搜索面板最多 5 条、每条可导航,底是原来的白色', () => {
  const js = read('components/cy/map-address-search/index.js')
  const wxss = read('components/cy/map-address-search/index.wxss')
  assert.match(js, /const MAX_RESULTS = 5;/)
  assert.match(js, /wx\.openLocation\(/)
  assert.match(read('components/cy/map-address-search/index.wxml'), /catchtap="onNavigate"/)
  assert.match(wxss, /\.addrlist\{[\s\S]{0,200}?background:#FFFFFF/)
  assert.doesNotMatch(wxss, /background:rgba\(28,28,30,\.62\)/)
})

test('P-35 结算页 × 只回漫游首页,不再走 backToSquare', () => {
  const wxml = read('pages/roam/index.wxml')
  assert.match(wxml, /class="settle__x" bindtap="closeSettle"/)
  assert.match(read('pages/roam/index.js'), /closeSettle\(\) \{[\s\S]{0,120}?screen: 'entry-map'/)
  assert.match(wxml, /class="settle__mapbtn" bindtap="openPassport"/, 'P-34 查看漫游数据浮在地图上')
})
