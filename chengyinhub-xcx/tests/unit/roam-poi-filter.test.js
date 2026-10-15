'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const f = require('../../utils/roam-poi-filter.js')

function memStorage() {
  const box = {}
  return {
    box,
    getStorageSync(k) { return box[k] },
    setStorageSync(k, v) { box[k] = v },
  }
}

test('BL-01 默认 3km、全部点位;非法值回落默认', () => {
  assert.deepEqual(f.normalizeFilter(null), { radiusM: 3000, unvisitedOnly: false })
  assert.deepEqual(f.normalizeFilter({ radiusM: 7000, unvisitedOnly: 'yes' }), { radiusM: 3000, unvisitedOnly: false })
  assert.deepEqual(f.normalizeFilter({ radiusM: '1000', unvisitedOnly: true }), { radiusM: 1000, unvisitedOnly: true })
})

test('BL-01 按账号持久化 roam_filter_v1,无账号不落盘', () => {
  const s = memStorage()
  assert.equal(f.writeFilter('42', { radiusM: 5000, unvisitedOnly: true }, s), true)
  assert.deepEqual(s.box['roam_filter_v1:42'], { radiusM: 5000, unvisitedOnly: true })
  assert.deepEqual(f.readFilter('42', s), { radiusM: 5000, unvisitedOnly: true })
  assert.deepEqual(f.readFilter('43', s), { radiusM: 3000, unvisitedOnly: false })
  assert.equal(f.writeFilter('', { radiusM: 1000 }, s), false)
  assert.equal(f.writeFilter('0', { radiusM: 1000 }, s), false)
  const broken = { getStorageSync() { throw new Error('x') } }
  assert.deepEqual(f.readFilter('42', broken), { radiusM: 3000, unvisitedOnly: false })
})

test('BL-01 只看未到过:藏 passed/done,保留待核销与当前目标', () => {
  const on = { radiusM: 3000, unvisitedOnly: true }
  assert.equal(f.hiddenByFilter({ state: 'passed' }, on, 'candidate'), true)
  assert.equal(f.hiddenByFilter({ state: 'done' }, on, 'completed'), true)
  assert.equal(f.hiddenByFilter({ state: 'pendingRedeem' }, on, 'redeem-pending'), false)
  assert.equal(f.hiddenByFilter({ state: 'seen' }, on, 'actionable'), false)
  assert.equal(f.hiddenByFilter({ state: 'done' }, on, 'target'), false)
  assert.equal(f.hiddenByFilter({ state: 'passed' }, { radiusM: 3000, unvisitedOnly: false }, 'candidate'), false)
})

test('BL-01 页面把筛选半径传给 /api/roam/pois,半屏带标题与关闭', () => {
  const root = path.join(__dirname, '../..')
  const js = fs.readFileSync(path.join(root, 'pages/roam/index.js'), 'utf8')
  const wxml = fs.readFileSync(path.join(root, 'pages/roam/index.wxml'), 'utf8')
  assert.match(js, /req\('\/api\/roam\/pois', 'GET', \{ lat: String\(c\.lat\), lng: String\(c\.lng\), radius: String\(radiusM\) \}/)
  assert.match(wxml, /<block wx:if="\{\{poiFilterShow\}\}">[\s\S]*?class="psheet__x" catchtap="closePoiFilter"[\s\S]*?<text class="ph1">筛选点位<\/text>/)
  assert.deepEqual(f.radiusTabs().map((t) => t.key), ['1000', '3000', '5000'])
})
