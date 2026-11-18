'use strict'
// F07:后面站点的照片到站前不显示。服务端对未到达的后续站下发 imgUrl:null + imgHidden:true,
// 端上所有原本显示站图的位置改放 cy-station-silhouette(中性剪影 + 站序号),章节封面不再拿站图兜底。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.join(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(root, rel), 'utf8')
const { buildFinale, buildGateFlip } = require('../../pages/play/utils/chapter-flip-data.js')

function pageMethod(name) {
  const src = read('pages/play/index.js')
  const start = src.indexOf('  ' + name + '(')
  assert.ok(start > 0, name)
  const end = src.indexOf('\n  },', start)
  return new Function('return ({' + src.slice(start, end + 4) + '})')()[name]
}

test('剪影组件:注册在游玩页,只用主题 token,不写死颜色', () => {
  const json = JSON.parse(read('pages/play/index.json'))
  assert.equal(json.usingComponents['cy-station-silhouette'], '/pages/play/components/station-silhouette/index')
  const wxss = read('pages/play/components/station-silhouette/index.wxss')
  assert.doesNotMatch(wxss, /#[0-9a-fA-F]{3,8}\b|rgba?\(/, '颜色一律走 token')
  assert.match(wxss, /var\(--cy-color-play-/)
  const wxml = read('pages/play/components/station-silhouette/index.wxml')
  assert.match(wxml, /\{\{num\}\}/, '显示站序号')
})

test('normNode:imgHidden 原样带上;有图时不当作隐藏', () => {
  const src = read('pages/play/index.js')
  const start = src.indexOf('  normNode(n, i, mode) {')
  const body = src.slice(start, src.indexOf('\n  },', start))
  const line = body.match(/imgHidden: ([^,]+),/)
  assert.ok(line, 'normNode 映射 imgHidden')
  const f = new Function('n', 'return ' + line[1])
  assert.equal(f({ imgUrl: null, imgHidden: true }), true)
  assert.equal(f({ imgUrl: 'https://x/a.jpg', imgHidden: true }), false)
  assert.equal(f({ imgUrl: 'https://x/a.jpg' }), false)
})

test('每个站图展示点在 imgHidden 时放剪影,不留空白', () => {
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /<cy-station-silhouette wx:elif="\{\{sheet\.node\.imgHidden\}\}" class="sheet-cover__ss"/, '站点抽屉封面')
  assert.match(wxml, /sheet\.node\.imgUrl \|\| sheet\.node\.imgHidden \? '' : 'sheet-cover--bare'/, '隐藏时封面区不收起')
  assert.match(wxml, /<cy-station-silhouette wx:elif="\{\{sheet\.node\.imgHidden\}\}" class="sheet-kit__img"/, '玩法横卡')
  assert.match(wxml, /<cy-station-silhouette wx:elif="\{\{advancedPlay\.node\.imgHidden\}\}" class="chfull__kitcard-img"/, '故事流 kit 卡')
  assert.match(wxml, /<cy-station-silhouette class="fx-tile__ph" wx:elif="\{\{item\.imgHidden\}\}"/, '地点格')
  assert.match(wxml, /<cy-station-silhouette class="fx-hero__im" wx:elif="\{\{hero\.node\.imgHidden\}\}"/, '地点大卡')
  assert.match(read('pages/play/play-topbar.wxml'), /<cy-station-silhouette wx:elif="\{\{item\.imgHidden\}\}" class="xd__img" num="\{\{item\.no\}\}"/, '延伸任务卡')
  const storyKit = read('pages/play/utils/story-kit-card.js')
  assert.match(storyKit, /imgHidden: !n\.imgUrl && !!n\.imgHidden/)
})

test('_coverOf:章节没封面时用主题封面,不再拿站点图兜底', () => {
  const coverOf = pageMethod('_coverOf')
  assert.equal(coverOf('https://x/chap.jpg', 'https://x/topic.jpg'), 'https://x/chap.jpg')
  assert.equal(coverOf('', 'https://x/topic.jpg,https://x/t2.jpg'), 'https://x/topic.jpg')
  assert.equal(coverOf('', ''), '')
  const src = read('pages/play/index.js')
  assert.doesNotMatch(src, /coverNodes/, '章节封面不再收集本章站点')
  assert.match(src, /this\._coverOf\(card\.cover \|\| '', this\.data\.topicCover\)/)
})

test('通关卡与章节翻页:未到达的站不出照片', () => {
  const cards = [{ id: 1, title: 'A' }, { id: 2, title: 'B' }]
  const nodes = [
    { nodeId: 11, chapterId: 1, done: true, imgUrl: 'https://x/1.jpg' },
    { nodeId: 21, chapterId: 2, done: false, arrived: false, imgUrl: 'https://x/2.jpg' },
    { nodeId: 22, chapterId: 2, done: false, imgUrl: null, imgHidden: true },
  ]
  const f = buildFinale({ cards, nodes, topicName: 'T' })
  assert.deepEqual(f.faces[0].photos, ['https://x/1.jpg'])
  assert.deepEqual(f.faces[1].photos, [])
  const g = buildGateFlip({ cards, chapterId: 2, nodes })
  assert.deepEqual(g.faces[1].photos, [])
})
