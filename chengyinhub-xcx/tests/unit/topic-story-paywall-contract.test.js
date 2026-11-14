const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.resolve(__dirname, '../..')
const read = (file) => fs.readFileSync(path.join(root, file), 'utf8')

test('剧情付费墙跟在完整章节列表后，且不渲染后端未下发的章节', () => {
  const wxml = read('pages/topic/index/index.wxml')

  assert.match(wxml, /wx:if="\{\{info\.storyLocked && info\.lockedChapterCount > 0\}\}"/)
  assert.match(wxml, /topic-story-paywall__title">解锁完整体验/)
  assert.doesNotMatch(wxml, /后面还有 \{\{info\.lockedChapterCount/)
  assert.match(wxml, /topic-story-paywall__sub">解锁后可看全部故事线、答题揭秘\{\{merchantCards\.length \? '与到店权益' : ''\}\}/)
  assert.match(wxml, /topic-story-paywall__price">\{\{money\.amount\(info\.selfPlayPrice\)\}\}/)
  assert.doesNotMatch(wxml, /selfPlayBenefitCount/)
  assert.doesNotMatch(wxml, /项到店体验/)
  assert.doesNotMatch(wxml, /含 4 项到店体验/)
  assert.doesNotMatch(wxml, /购票解锁 ·/)
  assert.match(wxml, /aria-label="立即解锁"[\s\S]*>立即解锁<\/view>/)
  assert.ok(wxml.indexOf('info.chaptersList') < wxml.indexOf('topic-story-paywall'))
  assert.match(wxml, /bindtap="selfPlayBuy"/)
  assert.match(wxml, /bindtap="goMyOrders"/)
})

test('主题响应只计算锁定数量，不伪造隐藏章节', () => {
  const js = read('pages/topic/index/index.js')

  assert.match(js, /totalChapterCount[\s\S]*unlockedChapterCount[\s\S]*lockedChapterCount/)
  assert.match(js, /Math\.max\(0, totalChapterCount - unlockedChapterCount\)/)
  assert.match(js, /goMyOrders[\s\S]*\/subpackageMember\/order\/order/)
})

test('未解锁章节以锁定预告卡下发：介绍可见、点按走购票、进度含预告章站点', () => {
  const motion = require(path.join(root, 'pages/topic/utils/topic-motion.js'))
  const cards = motion.buildStoryCards({ productType: 1, chaptersList: [
    { name: '序', description: '开场', nodes: [] },
    { name: '终', description: '未解锁的介绍', locked: true, imgArr: 'a.jpg;b.jpg', nodes: [{ id: 9, name: '站', imgUrl: '' }] },
  ] }, [])
  assert.equal(cards.length, 2)
  assert.equal(cards[0].locked, false)
  assert.equal(cards[1].locked, true)
  assert.equal(cards[1].excerpt, '未解锁的介绍')
  assert.equal(cards[1].img, 'a.jpg', '无站点图时取章节封面')

  const js = read('pages/topic/index/index.js')
  assert.match(js, /card && card\.locked\) return this\.selfPlayBuy\(\)/)
  assert.match(js, /motion\.progressFallback\(info\)/) // 进度总数含预告章,完成数只来自真实进度
  const wxml = read('pages/topic/index/index.wxml')
  assert.match(wxml, /item\.locked \? ' is-locked'/)
  assert.match(wxml, /item\.locked \? 'lock' : 'chevron-right'/)
})

test('章节标题已自带序号时不重复加前缀', () => {
  const motion = require(path.join(root, 'pages/topic/utils/topic-motion.js'))
  assert.equal(motion.chapterHeading(0, 2, '雨夜'), '第一章 · 雨夜')
  assert.equal(motion.chapterHeading(0, 2, '第一章 · 雨夜'), '第一章 · 雨夜')
  assert.equal(motion.chapterHeading(1, 2, '第二章：归途'), '第二章：归途')
  assert.equal(motion.chapterHeading(2, 3, '终章 尾声'), '终章 尾声')
  assert.equal(motion.chapterHeading(0, 2, ''), '第一章')
})

test('路线：有坐标默认真地图，无坐标才用示意图；站点无图回退主题封面而非白线稿', () => {
  const wxml = read('pages/topic/index/index.wxml')
  const js = read('pages/topic/index/index.js')
  assert.match(wxml, /<cy-route-sketch wx:if="\{\{!routeReal && !sketchPoints\.length\}\}"/)
  assert.match(js, /routeReal: sketchPoints\.length > 0/)
  assert.doesNotMatch(wxml, /icon_line\.jpg/)
  assert.match(wxml, /img\.first\(node\.imgUrl\) \|\| img\.first\(info\.imgUrl\)/)
})
