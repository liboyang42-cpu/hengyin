'use strict'
// N2(2026-11-21 新玩家走《这条街没有丢过猫》):活动内容只留故事引子,实用话收进「出发前」;数据条不出假 0。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const { splitDescription, buildDepartNotes } = require('../../pages/topic/utils/depart-notes.js')
const { buildTopicStatBar } = require('../../pages/topic/utils/topic-detail-facts.js')

const ROOT = path.join(__dirname, '..', '..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')

test('正文拆分:故事句留下,安全 / 营业 / 路线句拿进出发前,不改字', () => {
  const text = '这条街上的猫从来没丢过。直到今天，橘子没有回家。停稳再看手机，不在斑马线上停留。\n部分店铺营业时间 10:00-21:00\n\n路线：\n1. 巷口面馆\n2. 旧书店'
  const { story, notes } = splitDescription(text)
  assert.equal(story, '这条街上的猫从来没丢过。直到今天，橘子没有回家。')
  assert.deepEqual(notes, ['停稳再看手机，不在斑马线上停留', '部分店铺营业时间 10:00-21:00', '巷口面馆', '旧书店'])
})

test('作者小标题后整段是实用话;空 / null 正文不出任何东西', () => {
  const { story, notes } = splitDescription('一只猫的故事。\n\n【注意事项】\n雨天路滑；结伴同行')
  assert.equal(story, '一只猫的故事。')
  assert.deepEqual(notes, ['雨天路滑', '结伴同行'])
  assert.deepEqual(splitDescription(null), { story: '', notes: [] })
  assert.deepEqual(splitDescription('null'), { story: '', notes: [] })
})

test('出发前:价格与包含 / 同票人数 / 续玩 / 勋章都来自已有字段,缺就不出', () => {
  const notes = buildDepartNotes({ productType: 1, finishMedalName: '猫街守望者' }, {
    buyOpts: [{ name: '自玩门票', priceText: '¥29', people: 1 }, { name: '双人票', priceText: '¥49', people: 2 }],
    includes: ['3 个章节', '9 个节点', '通关勋章：猫街守望者'],
    notes: ['停稳再看手机'],
  })
  assert.deepEqual(notes, ['自玩门票 ¥29', '双人票 ¥49 · 2 人同玩一张票', '含 3 个章节、9 个节点', '中途可暂停，下次接着玩', '通关得勋章「猫街守望者」', '停稳再看手机'])
  // 自由探索没有一局可暂停;什么都没配就一条不出
  assert.deepEqual(buildDepartNotes({ productType: 2 }, {}), [])
})

test('详情页:活动内容读 storyText,出发前读 departNotes,站点图走共用兜底链', () => {
  const wxml = read('pages/topic/index/index.wxml')
  const js = read('pages/topic/index/index.js')
  assert.match(wxml, /<block wx:if="\{\{info\.storyText\}\}">\s*<view class="det-type">活动内容<\/view>/)
  assert.match(wxml, /<view class="topic-includes__t">出发前<\/view>/)
  assert.doesNotMatch(wxml, /\{\{info\.description\}\}/, '活动内容不再原样印整段正文')
  assert.match(js, /departNotes\.splitDescription\(res\.data\.description\)/)
  assert.match(js, /stationImage\.resolveStationImage\(/)
  assert.match(wxml, /class="item-pic" lazy-load="true" src="\{\{ img\.fit\(node\.photo, imgCtx, 'thumb', true\) \}\}"/)
})

test('数据条:值为 0 的格子整格不出(不出「参与商家 0 家」)', () => {
  const cells = buildTopicStatBar({ productType: 1, totalMileage: 2, registrationMerchantCount: 0, playerCount: 0 }, { withPlayers: true })
  assert.deepEqual(cells.map((c) => c.key), ['mileage'])
})
