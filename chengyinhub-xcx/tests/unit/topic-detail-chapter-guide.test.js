const test = require('node:test')
const assert = require('node:assert/strict')
const m = require('../../pages/topic/utils/topic-motion.js')

const node = (i, extra) => Object.assign({ name: 'n' + i, imgUrl: 'n' + i + '.jpg' }, extra)
const chapters = () => [
  { name: 'c1', nodes: [node(1), node(2)] },
  { name: 'c2', nodes: [node(3)] },
  { name: 'c3', locked: true, nodes: [node(4), node(5)] },
  { name: 'c4', locked: true, nodes: [node(6)] },
  { name: 'c5', locked: true, nodes: [node(7)] },
  { name: 'c6', locked: true, nodes: [node(8)] },
]

test('路线进度兜底:含未解锁预告章,总数=全部站点,完成数 0,每站一个标签', () => {
  const p = m.progressFallback({ chaptersList: chapters() })
  assert.equal(p.total, 8)
  assert.equal(p.done, 0)
  assert.equal(p.labels.length, 8)
  assert.equal(p.labels[0], '第1站')
  assert.equal(m.progressFallback({ productType: 2, chaptersList: [{ nodes: [node(1)] }] }).labels[0], '第 1 家')
})

test('章节导读:优先简介,否则取首个文本块开头,长文截断且不出整章', () => {
  assert.equal(m.chapterGuide({ description: '简介一句', blocks: [{ type: 'text', content: '正文' }] }), '简介一句')
  const long = '【林早】你被拉进一座旧城。' + '雨一直下。'.repeat(80)
  const g = m.chapterGuide({ blocks: [{ type: 'image', content: 'x' }, { type: 'text', content: long }] })
  assert.ok(g.length <= 61 && g.length > 0)
  assert.ok(g.startsWith('【林早】你被拉进一座旧城。'))
  assert.equal(m.chapterGuide({}), '')
  assert.ok(m.chapterGuide({ description: '甲'.repeat(300) }).length <= 61)
  const cards = m.buildStoryCards({ chaptersList: [{ name: 'c', locked: true, blocks: [{ type: 'text', content: long }] }] })
  assert.ok(cards[0].excerpt.length <= 61)
})

test('章节卡图片:章节图→本章站点图→主题封面,尽量不重复,有图就不留空', () => {
  const info = { imgUrl: 'cover.jpg', chaptersList: chapters() }
  const imgs = m.buildStoryCards(info).map((c) => c.img)
  assert.deepEqual(imgs, ['n1.jpg', 'n3.jpg', 'n4.jpg', 'n6.jpg', 'n7.jpg', 'n8.jpg'])
  assert.equal(new Set(imgs).size, imgs.length)
  const own = m.buildStoryCards({ chaptersList: [{ name: 'a', imgArr: 'own.jpg', nodes: [node(1)] }] })
  assert.equal(own[0].img, 'own.jpg')
  // 前面章节已占用本章唯一的图时沿用;没有任何站点图用封面
  const dup = m.buildStoryCards({ imgUrl: 'cover.jpg', chaptersList: [
    { name: 'a', nodes: [node(1)] }, { name: 'b', nodes: [node(1)] }, { name: 'c', nodes: [{ name: 'x' }] }] })
  assert.deepEqual(dup.map((c) => c.img), ['n1.jpg', 'n1.jpg', 'cover.jpg'])
  // 站点玩法图被后端回填成主题封面时,不当作本章图
  const tpl = m.buildStoryCards({ imgUrl: 'cover.jpg', chaptersList: [{ name: 'a', nodes: [{ cmsMemberTemplate: { imgUrl: 'cover.jpg' } }] }] })
  assert.equal(tpl[0].img, 'cover.jpg')
})
