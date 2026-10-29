'use strict'
// 2026-10-29 走查 V-22 / V-23:主题详情页 —— 地址放详情 tab 顶部;去掉「我的游玩记录」「主题音频讲解」
// 「举报这个主题」三行;头图简介可展开收起;数据条在总里程右边恢复「参与商家 / 已参加」,真实为 0 就写 0。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ROOT = path.resolve(__dirname, '../..')
const read = rel => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const wxml = () => read('pages/topic/index/index.wxml').replace(/<!--[\s\S]*?-->/g, '')
const { buildTopicStatBar } = require('../../pages/topic/utils/topic-detail-facts.js')

test('V-22 详情 tab 第一块是地址卡，在主题名和副标题之前', () => {
  const x = wxml()
  const tab = x.slice(x.indexOf("activeTab=='0'"))
  const venue = tab.indexOf('class="venue-card"')
  assert.ok(venue > 0)
  assert.ok(venue < tab.indexOf('<view class="det-type">{{info.name}}</view>'))
  assert.ok(venue < tab.indexOf('{{info.subtitle}}'))
})

test('V-22 详情页不再展示「我的游玩记录」和「主题音频讲解」行', () => {
  const x = wxml()
  assert.doesNotMatch(x, /我的游玩记录|主题音频讲解|toggleTopicAudio/)
  assert.doesNotMatch(read('pages/topic/index/index.js'), /toggleTopicAudio\(\)/)
})

test('V-23 详情正文不再有「举报这个主题」行', () => {
  assert.doesNotMatch(wxml(), /举报这个主题|topic-report-entry/)
})

test('V-23 头图简介点按展开 / 收起', () => {
  const x = wxml()
  assert.match(x, /<view class="hero-desc \{\{heroDescOpen \? 'is-open' : ''\}\}"[^>]*bindtap="toggleHeroDesc"[^>]*hover-class="cy-pressed"/)
  assert.match(read('pages/topic/index/index.wxss'), /\.hero-desc\.is-open \{ height: auto;[^}]*mask: none;/)
  let config
  global.getApp = () => ({ globalData: {} })
  global.Page = c => { config = c }
  global.wx = { getSystemInfoSync: () => ({}), getMenuButtonBoundingClientRect: () => ({}) }
  require('../../pages/topic/index/index.js')
  assert.equal(config.data.heroDescOpen, false)
  const page = { data: { heroDescOpen: false }, setData(p) { Object.assign(this.data, p) } }
  config.toggleHeroDesc.call(page); assert.equal(page.data.heroDescOpen, true)
  config.toggleHeroDesc.call(page); assert.equal(page.data.heroDescOpen, false)
})

test('V-23 玩家详情数据条:总里程右边照常出参与商家与已参加，真实为 0 就写 0', () => {
  const cells = buildTopicStatBar({ productType: 1, totalTime: 3600, totalMileage: 0.6, registrationMerchantCount: 0, playerCount: 0 }, { keepZeroCounts: true })
  assert.deepEqual(cells.map(c => c.key), ['hour', 'mileage', 'merchant', 'players'])
  assert.equal(cells[2].value, '0'); assert.equal(cells[3].value, '0')
  const rich = buildTopicStatBar({ productType: 1, totalMileage: 3, registrationMerchantCount: 4, playerCount: 1280 }, { keepZeroCounts: true })
  assert.deepEqual(rich.slice(-2).map(c => c.value), ['4', '1,280'])
  // 商家页不传选项，口径不变
  assert.deepEqual(buildTopicStatBar({ productType: 1, totalMileage: 3 }).map(c => c.key), ['mileage'])
  assert.match(read('pages/topic/index/index.js'), /buildTopicStatBar\(res\.data, \{ keepZeroCounts: true \}\)/)
})
