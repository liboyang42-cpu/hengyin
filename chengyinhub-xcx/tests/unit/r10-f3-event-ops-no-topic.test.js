'use strict'
// 第十轮 f3 p3 F4:活动运营没有可开场主题、也没有系列时,只给「暂无可开场的主题 · 发布主题」一个出口,
// 不再画「添加日期 · 按重复方式批量生成」与「重复方式」这些点了也没用的排期表单。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.resolve(__dirname, '../..')
const wxml = fs.readFileSync(path.join(root, 'pages/club/event-ops/index.wxml'), 'utf8')

function load() {
  let config
  const requests = []
  const navigations = []
  global.getApp = () => ({ globalData: {}, sendRequest(options) { requests.push(options) } })
  global.Page = value => { config = value }
  global.wx = { stopPullDownRefresh() {}, navigateTo(o) { navigations.push(o.url); if (o.complete) o.complete() } }
  const modulePath = path.join(root, 'pages/club/event-ops/index.js')
  delete require.cache[require.resolve(modulePath)]
  require(modulePath)
  const page = Object.assign({}, config, {
    data: Object.assign({}, config.data, { clubId: 21 }),
    setData(patch) { Object.assign(this.data, patch) },
  })
  return { page, requests, navigations }
}

test('主题回包到达后才标记已加载;空主题时 topicsLoaded=true', () => {
  const { page, requests } = load()
  assert.equal(page.data.topicsLoaded, false)
  page.loadTopics()
  requests.find(r => r.url === '/api/club/event-ops/topics').success({ code: 200, data: [] })
  assert.equal(page.data.topicsLoaded, true)
  assert.equal(page.data.topics.length, 0)
})

test('「发布主题」带俱乐部 id 进发布页', () => {
  const { page, navigations } = load()
  page.goPublishTopic()
  assert.deepEqual(navigations, ['/pages/publish/fabu/index?clubId=21'])
})

test('无主题无系列时空态在前,日期清单与重复方式两节都不画', () => {
  const empty = /wx:if="\{\{canManage && topicsLoaded && topics\.length === 0 && seriesState === 'empty'\}\}">\s*<cy-empty title="暂无可开场的主题" cta="发布主题" bind:cta="goPublishTopic" \/>/
  assert.match(wxml, empty)
  assert.match(wxml, /wx:elif="\{\{canManage\}\}">\s*<text class="section-title">日期清单<\/text>/)
  assert.match(wxml, /wx:if="\{\{canManage && !\(topicsLoaded && topics\.length === 0 && seriesState === 'empty'\)\}\}">\s*<text class="section-title">\{\{editingSeriesId \? '编辑未来场次' : '重复方式'\}\}<\/text>/)
})
