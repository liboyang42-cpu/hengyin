'use strict'
// 2026-10-29 走查 V-10 / V-01b:首页主题卡先用主题自己的图片，没有才用占位；占位图本身不能带骨架胶囊。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ROOT = path.resolve(__dirname, '../..')
const { decorateFeedCards } = require('../../utils/index/feed-card.js')
const https = src => /^https?:\/\//.test(src) ? src.replace(/^http:/, 'https:') : (src.startsWith('/') ? src : 'https://www.chengyinhub.com/prod-api/profile/' + src)

test('V-10 主题卡用 imgArr 首图', () => {
  const [card] = decorateFeedCards([{ imgArr: 'upload/a.jpg,upload/b.jpg', imgUrl: 'upload/c.jpg' }], https)
  assert.equal(card.cover, 'https://www.chengyinhub.com/prod-api/profile/upload/a.jpg')
})

test('V-10 imgArr 拆不出可用图时退回 imgUrl', () => {
  const reject = src => src.startsWith('http://untrusted') ? '' : https(src)
  const [card] = decorateFeedCards([{ imgArr: 'http://untrusted.example/a.jpg', imgUrl: 'http://chengyin-oss.oss-cn-shanghai.aliyuncs.com/t.jpg' }], reject)
  assert.equal(card.cover, 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/t.jpg')
  const [blank] = decorateFeedCards([{ imgArr: ' , ', imgUrl: 'upload/c.jpg' }], https)
  assert.equal(blank.cover, 'https://www.chengyinhub.com/prod-api/profile/upload/c.jpg')
})

test('V-10 主题没有图片才落占位', () => {
  const [card] = decorateFeedCards([{ imgArr: '', imgUrl: '' }], https)
  assert.equal(card.cover, '')
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/index/index.wxml'), 'utf8')
  assert.equal((wxml.match(/<image class="v2-fcover" src="\{\{item\.cover \|\| '\/images\/home-route-city-placeholder\.jpg'\}\}"/g) || []).length, 2)
})

test('V-01b 首页卡片占位不再用自带灰色骨架胶囊的 route_city_cover，且占位图会被打包', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/index/index.wxml'), 'utf8')
  const feed = wxml.slice(wxml.indexOf('id="sec-feed"'))
  assert.doesNotMatch(feed, /route_city_cover\.png/)
  const ignore = JSON.parse(fs.readFileSync(path.join(ROOT, 'project.config.json'), 'utf8')).packOptions.ignore
  assert.ok(!ignore.some(x => x.value === 'images/home-route-city-placeholder.jpg'))
  assert.ok(fs.statSync(path.join(ROOT, 'images/home-route-city-placeholder.jpg')).size > 10000)
})
