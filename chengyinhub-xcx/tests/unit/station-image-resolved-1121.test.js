'use strict'
// 站点配图兜底链只用页面上已解析(签名)的地址(2026-11-21 复核:路线 9914701 第 4 站 node 9914724,章「那算了」)。
// 现场:站点自己没图,路线 img_url 原值是 'media-asset:756'(素材引用,不是地址),而页面上已有签名后的章节封面 ——
// 开始弹窗大图却是放大镜插图、故事流玩法卡缩略图是空灰框。链必须是:站点图 → 本章封面 → 本章第一张故事块图 → topicCover,
// 绝不把 media-asset: 引用当图;只有全空才回插图。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const path = require('path')
const si = require('../../utils/station-image.js')
const pops = require('../../pages/play/utils/play-stage-pops.js')

const CH_COVER = 'https://cos.example.com/ch/nasuanle.jpg?sign=abc'
const BLOCK_IMG = 'https://cos.example.com/ch/block1.jpg?sign=def'
const TOPIC_RESOLVED = 'https://cos.example.com/topic/756.jpg?sign=xyz'

/** 真实页面方法(index.js):_noteStationImageContext / _stationPhotos / _fillStationPhotos */
function pageMethods() {
  const src = fs.readFileSync(path.join(__dirname, '../../pages/play/index.js'), 'utf8')
  const pick = (name) => { const s = src.indexOf('\n  ' + name + '(') + 1; assert.ok(s > 0, name); return src.slice(s, src.indexOf('\n  },', s) + 4) }
  return new Function('stationImage', 'return ({' + ['_noteStationImageContext', '_stationPhotos', '_fillStationPhotos'].map(pick).join(',') + '})')(si)
}

/** /nodes 回包:站点无图、路线封面是 media-asset 引用、章节 img_arr 是引用但 cover / 故事块已签名 */
function nodesResponse(topicCover) {
  return {
    topicCover,
    chapters: [
      { chapterId: 31, name: '序章', imgArr: '', cover: 'https://cos.example.com/ch/prologue.jpg' },
      { chapterId: 34, name: '那算了', imgArr: 'media-asset:801', cover: CH_COVER, blocks: [{ type: 'text', text: '…' }, { type: 'image', url: BLOCK_IMG }] },
    ],
  }
}

function buildPage(topicCover) {
  const d = nodesResponse(topicCover)
  const page = Object.assign({ data: { topicCover: '' } }, pageMethods())
  page._noteStationImageContext(d)
  const node = { nodeId: 9914724, name: '今晚轮到我们上场', chapterId: 34, imgUrl: '', storyImg: '' }
  const norm = Object.assign({}, node, page._stationPhotos(node))
  const chapter = { id: 34, name: '那算了', cover: CH_COVER, blocks: d.chapters[1].blocks }
  const chapterCards = [{ id: 31, cover: 'https://cos.example.com/ch/prologue.jpg' }, { id: 34, cover: CH_COVER, blocks: d.chapters[1].blocks }]
  const nodes = [norm]
  page._fillStationPhotos([nodes], { chapter, chapterCards, topicCover: d.topicCover })
  page.data = { topicCover: TOPIC_RESOLVED, chapter, chapterCards, nodes, advancedPlay: { node: nodes[0] } }
  return page
}

test('页面形态:站点无图 + 路线 media-asset:756 + 已签名章节封面 → 开始弹窗大图与玩法卡缩略图都是章节封面', () => {
  const page = buildPage('media-asset:756')
  const n = page.data.nodes[0]
  assert.equal(n.photo, CH_COVER, '站点抽屉 / 列表缩略图')
  assert.equal(page.data.advancedPlay.node.gamePhoto, CH_COVER, '故事流玩法卡缩略图(chfull__kitcard)')
  assert.equal(pops.kitStartCover(page, n, null), CH_COVER, '开始弹窗大图不回放大镜插图')
  assert.doesNotMatch(JSON.stringify(page.data.nodes), /media-asset:/)
})

test('章节封面优先于已签名的路线封面', () => {
  const page = buildPage(TOPIC_RESOLVED)
  assert.equal(page.data.nodes[0].photo, CH_COVER)
  assert.equal(pops.kitStartCover(page, page.data.nodes[0], null), CH_COVER)
})

test('链:站点图 → 本章封面 → 本章第一张故事块图 → topicCover;全空才回插图', () => {
  const blocks = [{ type: 'image', url: 'media-asset:9' }, { type: 'image', url: BLOCK_IMG }]
  const view = { chapter: { id: 34, cover: '', blocks }, chapterCards: [{ id: 34, cover: 'media-asset:801', blocks }, { id: 35, cover: 'x' }], topicCover: TOPIC_RESOLVED }
  assert.equal(si.resolvePageStationImage(view, { chapterId: 34, imgUrl: 'https://x/own.jpg' }), 'https://x/own.jpg')
  assert.equal(si.resolvePageStationImage(view, { chapterId: 34, imgUrl: 'media-asset:1' }), BLOCK_IMG)
  assert.equal(si.resolvePageStationImage({ ...view, chapter: { id: 34 }, chapterCards: [{ id: 34 }, { id: 35 }] }, { chapterId: 34 }), TOPIC_RESOLVED)
  assert.equal(si.resolvePageStationImage({ topicCover: 'media-asset:756' }, { chapterId: 34 }), '')
  assert.equal(pops.kitStartCover({ data: { topicCover: 'media-asset:756' }, _stationPhotos: () => ({ photo: 'media-asset:756' }) }, { imgUrl: '' }, null), '', '全空 → 插图')
})

test('玩法自己的封面保留;media-asset 引用的玩法封面不算', () => {
  const view = { chapterCards: [{ id: 34, cover: CH_COVER }], chapter: { id: 34, cover: CH_COVER }, topicCover: '' }
  const own = [{ chapterId: 34, imgUrl: '', photo: '', gamePhoto: 'https://x/game.jpg' }]
  si.fillStationPhotos(own, view)
  assert.deepEqual([own[0].photo, own[0].gamePhoto], [CH_COVER, 'https://x/game.jpg'])
  assert.equal(si.resolveGameImage({ gameCover: 'media-asset:3', chapterImgArr: ['media-asset:801', CH_COVER] }).src, CH_COVER)
})
