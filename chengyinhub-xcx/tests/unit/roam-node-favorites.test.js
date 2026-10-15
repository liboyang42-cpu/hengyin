'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const fav = require('../../subpackageRoam/utils/node-favorites.js')
const ROOT = path.join(__dirname, '..', '..')

test('BL-42 收藏据点整形:只取 CityNodeVO 已有字段,丢弃无 id/无名并去重', () => {
  const list = fav.normalizeFavoriteNodes([
    { poiId: 7, name: ' 咖啡馆 ', coverImg: 'a.png', merchantName: '某店', merchantAddress: '某路 1 号', completed: true, distance: null },
    { poiId: 7, name: '重复' },
    { poiId: '', name: '无 id' },
    { poiId: 9, name: '' },
    null,
    { poiId: 10, name: '书店' },
  ])
  assert.deepEqual(list, [
    { poiId: '7', name: '咖啡馆', coverImg: 'a.png', metaText: '某店 · 某路 1 号', completed: true },
    { poiId: '10', name: '书店', coverImg: '', metaText: '', completed: false },
  ])
})

test('BL-42 收藏据点整形:非数组返回 null(走失败态,不当成空列表)', () => {
  assert.equal(fav.normalizeFavoriteNodes(null), null)
  assert.equal(fav.normalizeFavoriteNodes({}), null)
  assert.deepEqual(fav.normalizeFavoriteNodes([]), [])
})

test('BL-42 页面:GET /api/city/nodes/favorites,点开进 poi-detail 深链宿主,回调不用 this', () => {
  const js = fs.readFileSync(path.join(ROOT, 'subpackageRoam/node-favorites/index.js'), 'utf8')
  assert.match(js, /url: '\/api\/city\/nodes\/favorites', method: 'GET'/)
  assert.match(js, /\/subpackageRoam\/poi-detail\/index\?poiId=/)
  const sendBlock = js.slice(js.indexOf('app.sendRequest('), js.indexOf('openNode('))
  assert.doesNotMatch(sendBlock, /\bthis\./)
  const wxml = fs.readFileSync(path.join(ROOT, 'subpackageRoam/node-favorites/index.wxml'), 'utf8')
  assert.match(wxml, /<cy-nav-bar plain custom-back/)
  assert.match(wxml, /<cy-page-title title="收藏的据点"/)
  assert.match(wxml, /wx:key="poiId"/)
})
