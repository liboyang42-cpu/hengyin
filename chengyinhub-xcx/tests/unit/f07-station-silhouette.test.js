'use strict'
// F07:后面站点的照片到站前不显示。服务端对未到达的后续站下发 imgUrl:null + imgHidden:true,
// 端上原本显示站图的位置走 utils/station-image.js 兜底链(章节图 → 路线封面 → 纹理底),章节封面不再拿站图兜底。
// 2026-11-21:人形剪影 cy-station-silhouette 退场(业主「这个应该有图片」)。
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

test('人形剪影已退场:游玩页不再注册 cy-station-silhouette(2026-11-21 配图兜底链)', () => {
  const json = JSON.parse(read('pages/play/index.json'))
  assert.equal(json.usingComponents['cy-station-silhouette'], undefined)
  assert.equal(fs.existsSync(path.join(root, 'pages/play/components/station-silhouette')), false)
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

test('每个站图展示点走配图兜底链(photo / gamePhoto),无图落纹理底,不留空白也不放剪影', () => {
  const wxml = read('pages/play/index.wxml')
  assert.doesNotMatch(wxml, /cy-station-silhouette/)
  assert.match(wxml, /<image wx:if="\{\{sheet\.node\.photo\}\}" src="\{\{img\.fit\(sheet\.node\.photo, imgCtx, 'card', true\)\}\}" data-raw="\{\{sheet\.node\.photo\}\}" binderror="onImgRenditionError" webp="\{\{true\}\}" mode="aspectFill" lazy-load="true"><\/image>\s*<view wx:else class="sheet-cover__ss photo-tex"/, '站点抽屉封面')
  assert.match(wxml, /<view class="sheet-cover" bindtap="previewNodeImages"/, '封面区高度固定,不再收起')
  assert.match(wxml, /<image wx:if="\{\{sheet\.node\.gamePhoto\}\}" class="sheet-kit__img"[^>]*mode="aspectFill" lazy-load="true" \/>\s*<view wx:else class="sheet-kit__img photo-tex"/, '玩法横卡')
  assert.match(wxml, /<image wx:if="\{\{advancedPlay\.node\.gamePhoto\}\}" class="chfull__kitcard-img"[^>]*\/><view wx:else class="chfull__kitcard-img photo-tex"/, '故事流 kit 卡')
  assert.match(wxml, /<image class="fx-tile__im" wx:if="\{\{item\.photo\}\}"[^>]*lazy-load="true" \/>\s*<view class="fx-tile__ph photo-tex" wx:else/, '地点格')
  assert.match(wxml, /<image class="fx-hero__im" wx:if="\{\{hero\.node\.photo\}\}"/, '地点大卡')
  assert.match(read('pages/play/play-topbar.wxml'), /<image wx:if="\{\{item\.photo\}\}" class="xd__img"/, '延伸任务卡')
  const storyKit = read('pages/play/utils/story-kit-card.js')
  assert.match(storyKit, /imgHidden: !n\.imgUrl && !!n\.imgHidden, photo: n\.photo \|\| ''/)
})

test('F07:被遮的站(imgUrl 已置空)只会落到章节图 / 路线封面,拿不到站点照片', () => {
  const page = { data: { topicCover: '' } }
  Object.assign(page, (function () {
    const src = read('pages/play/index.js')
    const pick = (name) => { const s = src.indexOf('  ' + name + '('); return src.slice(s, src.indexOf('\n  },', s) + 4) }
    return new Function('stationImage', 'return ({' + pick('_noteStationImageContext') + ',' + pick('_stationPhotos') + '})')(require('../../utils/station-image.js'))
  })())
  page._noteStationImageContext({ chapters: [{ chapterId: 2, imgArr: 'https://x/ch2.jpg' }], topicCover: 'https://x/topic.jpg' })
  assert.deepEqual(page._stationPhotos({ imgUrl: null, imgHidden: true, chapterId: 2 }), { photo: 'https://x/ch2.jpg', gamePhoto: 'https://x/ch2.jpg' })
  assert.deepEqual(page._stationPhotos({ imgUrl: null, imgHidden: true, chapterId: 9 }), { photo: 'https://x/topic.jpg', gamePhoto: 'https://x/topic.jpg' })
  assert.deepEqual(page._stationPhotos({ imgUrl: 'https://x/s.jpg,https://x/s2.jpg', chapterId: 2, storyImg: 'https://x/g.jpg' }), { photo: 'https://x/s.jpg', gamePhoto: 'https://x/g.jpg' })
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
