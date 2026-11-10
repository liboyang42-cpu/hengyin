// l-editor(2026-11-10,业主批准的编辑器原型 v2):图片组 / 分支任务 / 章节开始·结束卡 / 拖进段落拆段。
// 钉住:三种块的物化与上送形状、空图片组不落库、分支形状上限、开场白结语固定首尾、拖进段落只落在句末。
const { test } = require('node:test')
const assert = require('node:assert/strict')

const story = require('../../pages/publish/utils/publish/pro-editor-story.js')

const copy = (v) => JSON.parse(JSON.stringify(v))
const apply = (chapter, command) => story.applyStoryCommand({ chapter, pendingMaterials: [] }, command)
const shape = (chapter) => chapter.blocks.map((b) => b.type === 'text' ? 'T:' + b.content : b.type)
let seq = 0
const key = () => 'k' + (++seq)

function chapter(blocks) {
  return story.materializeChapter({ _localId: 'c1', name: '雨停之后', nodes: [], blocks }, key)
}

// ── 章节开始 / 结束卡 ──────────────────────────────────────
test('cap 块物化时抬到章节的 _capOpen / _capEnd 上,不进 blocks;上送时还原为第一个 / 最后一个块', () => {
  const ch = chapter([
    { key: 'cap-open', type: 'cap', cap: 'open', content: '雨刚停。' },
    { key: 'p1', type: 'text', content: '她推开门。' },
    { key: 'cap-end', type: 'cap', cap: 'end', content: '门铃响了一声。' },
  ])
  assert.equal(ch._capOpen, '雨刚停。')
  assert.equal(ch._capEnd, '门铃响了一声。')
  assert.deepEqual(shape(ch), ['T:她推开门。'])
  const payload = story.toPayloadChapter(ch)
  assert.deepEqual(payload.blocks.map((b) => b.type + ':' + (b.cap || '')), ['cap:open', 'text:', 'cap:end'])
  assert.equal(payload.blocks[0].key, 'cap-open')
  assert.equal(payload.description, '她推开门。', '开场白与结语不进摘要')
  assert.equal(payload._capOpen, undefined, '本地字段不上送')
})

test('setChapterCap 只改卡上的话;空白的话不出 cap 块;超长被拒', () => {
  const ch = chapter([{ key: 'p1', type: 'text', content: '正文。' }])
  let r = apply(ch, { type: 'setChapterCap', cap: 'open', content: '  开场  ' })
  assert.equal(r.draft.chapter._capOpen, '  开场  ')
  assert.deepEqual(shape(r.draft.chapter), ['T:正文。'])
  r = apply(r.draft.chapter, { type: 'setChapterCap', cap: 'end', content: '   ' })
  const payload = story.toPayloadChapter(r.draft.chapter)
  assert.deepEqual(payload.blocks.map((b) => b.type), ['cap', 'text'])
  assert.equal(payload.blocks[0].content, '开场', '上送时 trim')
  assert.throws(() => apply(ch, { type: 'setChapterCap', cap: 'open', content: 'x'.repeat(501) }), /开场白不能超过 500 字/)
  assert.throws(() => apply(ch, { type: 'setChapterCap', cap: 'middle', content: 'x' }), /open \/ end/)
})

// ── 图片组 ────────────────────────────────────────────────
test('图片组:按版式落空组 → 逐格选图 → 整组一个块;上送只带已选的图,一张都没有的组不落库', () => {
  const ch = chapter([{ key: 'p1', type: 'text', content: '正文。' }])
  let r = apply(ch, { type: 'insertMediaAt', mediaType: 'gallery', index: 1, blockKey: 'g1', layout: 3 })
  assert.deepEqual(shape(r.draft.chapter), ['T:正文。', 'gallery'])
  assert.equal(r.draft.chapter.blocks[1].layout, 3)
  assert.deepEqual(r.draft.chapter.blocks[1].images, [])
  assert.deepEqual(story.toPayloadChapter(r.draft.chapter).blocks.map((b) => b.type), ['text'], '空组不落库')

  r = apply(r.draft.chapter, { type: 'fillGalleryCell', blockKey: 'g1', index: 0, url: '/a.jpg' })
  r = apply(r.draft.chapter, { type: 'fillGalleryCell', blockKey: 'g1', index: 2, url: '/c.jpg' })
  assert.deepEqual(r.draft.chapter.blocks[1].images, [{ url: '/a.jpg' }, { url: '/c.jpg' }], '第 2 格还空着时,第 3 格的图顺位补进去')
  r = apply(r.draft.chapter, { type: 'fillGalleryCell', blockKey: 'g1', index: 0, url: '/a2.jpg' })
  assert.equal(r.draft.chapter.blocks[1].images[0].url, '/a2.jpg', '已有图的格子被替换')
  assert.throws(() => apply(r.draft.chapter, { type: 'fillGalleryCell', blockKey: 'g1', index: 3, url: '/d.jpg' }), /这一格不存在/)
  assert.throws(() => apply(r.draft.chapter, { type: 'fillGalleryCell', blockKey: 'g1', index: 0, url: '   ' }), /缺少地址/)

  r = apply(r.draft.chapter, { type: 'editGalleryCaption', blockKey: 'g1', altText: '吧台上的那杯咖啡' })
  r = apply(r.draft.chapter, { type: 'removeGalleryImage', blockKey: 'g1', index: 1 })
  const payload = story.toPayloadChapter(r.draft.chapter)
  assert.deepEqual(payload.blocks[1], { key: 'g1', type: 'gallery', layout: 3, images: [{ url: '/a2.jpg' }], altText: '吧台上的那杯咖啡' })

  const reopened = chapter(copy(payload.blocks))
  assert.deepEqual(story.toPayloadChapter(reopened).blocks[1], payload.blocks[1], '重开再保存逐字一致')
})

test('图片组:版式越界回落单图;多于版式的图被裁掉;整组可以 removeMedia 一次删掉', () => {
  const ch = chapter([{ key: 'g', type: 'gallery', layout: 9, images: [{ url: '/a.jpg' }, { url: '/b.jpg' }] }])
  assert.equal(ch.blocks[0].layout, 1)
  assert.deepEqual(ch.blocks[0].images, [{ url: '/a.jpg' }])
  const r = apply(ch, { type: 'removeMedia', blockKey: 'g' })
  assert.deepEqual(shape(r.draft.chapter), [])
})

// ── 分支任务 ──────────────────────────────────────────────
test('分支任务:落块带两条空分支;改问题 / 改选项 / 改去向 / 加到 3 条 / 删回 2 条;上送校验', () => {
  const ch = chapter([{ key: 'p1', type: 'text', content: '正文。' }])
  let r = apply(ch, { type: 'insertMediaAt', mediaType: 'branch', index: 1, blockKey: 'b1' })
  assert.deepEqual(r.draft.chapter.blocks[1], { key: 'b1', type: 'branch', content: '', options: [{ text: '', to: 'continue' }, { text: '', to: 'continue' }] })
  assert.throws(() => story.toPayloadChapter(r.draft.chapter), /分支任务要写一个问题/)

  r = apply(r.draft.chapter, { type: 'updateBranch', blockKey: 'b1', content: '先去哪一处?' })
  assert.throws(() => story.toPayloadChapter(r.draft.chapter), /每条分支都要写玩家看到的选项/)
  r = apply(r.draft.chapter, { type: 'updateBranch', blockKey: 'b1', option: { index: 0, text: '直接去邮局' } })
  r = apply(r.draft.chapter, { type: 'updateBranch', blockKey: 'b1', option: { index: 1, text: '先回旧书店', to: 'chapter:2' } })
  r = apply(r.draft.chapter, { type: 'updateBranch', blockKey: 'b1', addOption: true })
  assert.equal(r.draft.chapter.blocks[1].options.length, 3)
  assert.throws(() => apply(r.draft.chapter, { type: 'updateBranch', blockKey: 'b1', addOption: true }), /最多 3 条分支/)
  r = apply(r.draft.chapter, { type: 'updateBranch', blockKey: 'b1', removeOption: 2 })
  assert.throws(() => apply(r.draft.chapter, { type: 'updateBranch', blockKey: 'b1', removeOption: 0 }), /至少 2 条分支/)
  assert.throws(() => apply(r.draft.chapter, { type: 'updateBranch', blockKey: 'b1', option: { index: 0, to: 'nowhere' } }), /去向无效/)

  const payload = story.toPayloadChapter(r.draft.chapter)
  assert.deepEqual(payload.blocks[1], { key: 'b1', type: 'branch', content: '先去哪一处?', options: [{ text: '直接去邮局', to: 'continue' }, { text: '先回旧书店', to: 'chapter:2' }] })
  assert.equal(payload.description, '正文。', '分支不进摘要')
  assert.deepEqual(story.toPayloadChapter(chapter(copy(payload.blocks))).blocks[1], payload.blocks[1])
})

test('分支任务:存量块里坏去向回落 continue,少于两条补齐,多于三条裁掉', () => {
  const ch = chapter([{ key: 'b', type: 'branch', content: 'q', options: [{ text: 'a', to: 'x' }] }])
  assert.deepEqual(ch.blocks[0].options, [{ text: 'a', to: 'continue' }, { text: '', to: 'continue' }])
  const wide = chapter([{ key: 'b', type: 'branch', content: 'q', options: [1, 2, 3, 4].map((n) => ({ text: String(n), to: 'end' })) }])
  assert.equal(wide.blocks[0].options.length, 3)
})

// ── 拖进段落中间 ──────────────────────────────────────────
test('句子边界:只在 。!?;… 与换行之后,且不含段首段尾', () => {
  assert.deepEqual(story.sentenceBoundaries('雨停了。她推开门!铃响了一声?吧台没人…完'), [4, 9, 15, 20])
  assert.deepEqual(story.sentenceBoundaries('一句话。'), [], '只有一句:没有可拆的点')
  assert.deepEqual(story.sentenceBoundaries('上\n下'), [2])
  assert.deepEqual(story.sentenceBoundaries(''), [])
})

test('moveBlockInto:被拖的块落在句子之间,原段拆成两段,B/I/S 随各自那一半;非句末被拒', () => {
  const ch = chapter([
    { key: 'p1', type: 'text', content: '雨停了。她推开门。', marks: [{ from: 0, to: 2, type: 'bold' }, { from: 5, to: 8, type: 'italic' }] },
    { key: 'img', type: 'image', url: '/a.jpg' },
    { key: 'p2', type: 'text', content: '吧台没人。' },
  ])
  const r = apply(ch, { type: 'moveBlockInto', blockKey: 'p2', targetKey: 'p1', at: 4, tailKey: 't1' })
  assert.deepEqual(shape(r.draft.chapter), ['T:雨停了。', 'T:吧台没人。', 'T:她推开门。', 'image'])
  assert.deepEqual(r.draft.chapter.blocks[0].marks, [{ from: 0, to: 2, type: 'bold' }])
  assert.deepEqual(r.draft.chapter.blocks[2].marks, [{ from: 1, to: 4, type: 'italic' }])
  assert.equal(r.draft.chapter.blocks[2].key, 't1')
  assert.equal(r.hostIndex, 0)
  assert.throws(() => apply(ch, { type: 'moveBlockInto', blockKey: 'img', targetKey: 'p1', at: 2, tailKey: 't2' }), /只能插在句子之间/)
  assert.throws(() => apply(ch, { type: 'moveBlockInto', blockKey: 'img', targetKey: 'img', at: 4, tailKey: 't2' }), /未找到要插入的段落/)
  assert.throws(() => apply(ch, { type: 'moveBlockInto', blockKey: 'img', targetKey: 'p1', at: 4, tailKey: 'p2' }), /标识无效或重复/)
})

test('moveBlockInto:从段落后面往前拖同样成立(下标在删掉原块后重算)', () => {
  const ch = chapter([
    { key: 'img', type: 'image', url: '/a.jpg' },
    { key: 'p1', type: 'text', content: '雨停了。她推开门。' },
  ])
  const r = apply(ch, { type: 'moveBlockInto', blockKey: 'img', targetKey: 'p1', at: 4, tailKey: 't1' })
  assert.deepEqual(shape(r.draft.chapter), ['T:雨停了。', 'image', 'T:她推开门。'])
})
