// r2-create(2026-11-10):故事编辑器对标 X Articles。用户:「这个整体改一下 就是类似 我发你参考图」。
// 钉住:段落样式 / 段内 B·I·S 的数据模型(content 仍是纯文字)、格式跟着文字走、拆段与退格合并、
// 段首哨兵把「段首退格」变成可收到的信号、保存→重开→再保存逐块一致。
const { test } = require('node:test')
const assert = require('node:assert/strict')

const inline = require('../../pages/publish/utils/publish/story-inline.js')
const story = require('../../pages/publish/utils/publish/pro-editor-story.js')

const apply = (chapter, command) => story.applyStoryCommand({ chapter, pendingMaterials: [] }, command)
const B = (from, to) => ({ from, to, type: 'bold' })

function chapterOf(blocks) {
  return story.materializeChapter({ _localId: 'c1', name: '第一章', nodes: [], blocks })
}

// ── 区间模型 ──────────────────────────────────────────────
test('区间清洗:越界截断、空区间丢弃、同类重叠合并、未知类型丢弃', () => {
  assert.deepEqual(inline.normalizeMarks([B(-3, 2), B(1, 4), { from: 0, to: 9, type: 'underline' }, B(6, 6)], 5), [B(0, 4)])
})

test('文字改了格式跟着走:格式区两端新打的字不带格式,中间打的字带格式,删光的区间消失', () => {
  assert.deepEqual(inline.remapMarks([B(2, 5)], 'abcdefg', 'abXXcdefg'), [B(4, 7)])
  assert.deepEqual(inline.remapMarks([B(2, 5)], 'abcdefg', 'abcdeXXfg'), [B(2, 5)])
  assert.deepEqual(inline.remapMarks([B(2, 5)], 'abcdefg', 'abcXXdefg'), [B(2, 7)])
  assert.deepEqual(inline.remapMarks([B(2, 5)], 'abcdefg', 'afg'), [])
})

test('B/I/S 切换:选区整段已有就去掉(中间挖洞),否则补满', () => {
  assert.deepEqual(inline.toggleMark([], 1, 3, 'bold', 6), [B(1, 3)])
  assert.deepEqual(inline.toggleMark([B(0, 6)], 2, 4, 'bold', 6), [B(0, 2), B(4, 6)])
  assert.deepEqual(inline.toggleMark([B(0, 2)], 0, 4, 'bold', 6), [B(0, 4)])
  assert.throws(() => inline.toggleMark([], 0, 1, 'underline', 2), /不支持/)
})

test('静态渲染切段:重叠的加粗与斜体切成互不重叠的小段', () => {
  assert.deepEqual(inline.segments('abcdefg', [B(2, 5), { from: 4, to: 6, type: 'italic' }]).map((s) => [s.text, s.bold, s.italic]),
    [['ab', false, false], ['cd', true, false], ['e', true, true], ['f', false, true], ['g', false, false]])
})

test('字数只数正文 / 台词里的非空白字;编号列表按连续段从 1 数起', () => {
  const blocks = [{ type: 'text', content: '一 二', style: 'ol' }, { type: 'text', content: '三', style: 'ol' }, { type: 'image' },
    { type: 'text', content: '四', style: 'ol' }, { type: 'voice', content: '五六' }]
  assert.equal(inline.wordCount(blocks), 6)
  assert.deepEqual(inline.listNumbers(blocks), [1, 2, 0, 1, 0])
})

// ── 命令 ──────────────────────────────────────────────────
test('formatText:有选区作用于选区,没选区作用于整段;叙事段 / 台词拒绝', () => {
  const chapter = chapterOf([{ key: 'p', type: 'text', content: '雨停了。' }, { key: 'w', type: 'text', who: '店主', content: '欢迎' }])
  let r = apply(chapter, { type: 'formatText', blockKey: 'p', from: 0, to: 2, mark: 'bold' })
  assert.deepEqual(r.draft.chapter.blocks[0].marks, [B(0, 2)])
  r = apply(r.draft.chapter, { type: 'formatText', blockKey: 'p', from: -1, to: -1, mark: 'italic' })
  assert.deepEqual(r.draft.chapter.blocks[0].marks, [B(0, 2), { from: 0, to: 4, type: 'italic' }])
  assert.throws(() => apply(r.draft.chapter, { type: 'formatText', blockKey: 'w', mark: 'bold' }), /正文段/)
})

test('setParagraphStyle:标题 / 副标题 / 引用 / 列表;同一个再点一次回到正文', () => {
  const chapter = chapterOf([{ key: 'p', type: 'text', content: '第一节' }])
  let r = apply(chapter, { type: 'setParagraphStyle', blockKey: 'p', style: 'h1' })
  assert.equal(r.draft.chapter.blocks[0].style, 'h1')
  r = apply(r.draft.chapter, { type: 'setParagraphStyle', blockKey: 'p', style: 'quote', toggle: true })
  assert.equal(r.draft.chapter.blocks[0].style, 'quote')
  r = apply(r.draft.chapter, { type: 'setParagraphStyle', blockKey: 'p', style: 'quote', toggle: true })
  assert.equal(r.draft.chapter.blocks[0].style, undefined)
  assert.throws(() => apply(r.draft.chapter, { type: 'setParagraphStyle', blockKey: 'p', style: 'h3' }), /无效/)
})

test('回车拆段:格式按各段起点切开;标题后回车起正文,列表接着是列表', () => {
  const chapter = chapterOf([{ key: 'h', type: 'text', content: '标题甲乙', style: 'h1', marks: [B(0, 4)] },
    { key: 'l', type: 'text', content: '一项', style: 'ul' }])
  let r = apply(chapter, { type: 'splitParagraphs', blockKey: 'h', content: '标题\n甲乙', cursor: 3, blockKeys: ['n1'] })
  assert.deepEqual(r.draft.chapter.blocks.slice(0, 2).map((b) => [b.content, b.style, b.marks]),
    [['标题', 'h1', [B(0, 2)]], ['甲乙', undefined, [B(0, 2)]]])
  r = apply(r.draft.chapter, { type: 'splitParagraphs', blockKey: 'l', content: '一项\n二项', cursor: 3, blockKeys: ['n2'] })
  assert.deepEqual(r.draft.chapter.blocks.slice(2).map((b) => [b.content, b.style]), [['一项', 'ul'], ['二项', 'ul']])
})

test('段首退格:正文段并入上一段(格式平移);上一块是图片时空段收掉、非空段只选中图片', () => {
  const chapter = chapterOf([{ key: 'a', type: 'text', content: '雨停', marks: [B(0, 1)] },
    { key: 'b', type: 'text', content: '了。', marks: [B(0, 1)] }, { key: 'i', type: 'image', url: '/x.jpg' }, { key: 'c', type: 'text', content: '' },
    { key: 'd', type: 'text', content: '她推开门' }])
  let r = apply(chapter, { type: 'mergeParagraphUp', blockKey: 'b' })
  assert.equal(r.merged, true)
  assert.deepEqual([r.focusBlockKey, r.focusCursor], ['a', 2])
  assert.deepEqual([r.draft.chapter.blocks[0].content, r.draft.chapter.blocks[0].marks], ['雨停了。', [B(0, 1), B(2, 3)]])
  r = apply(r.draft.chapter, { type: 'mergeParagraphUp', blockKey: 'c' })
  assert.deepEqual([r.merged, r.removed, r.selectBlockKey], [false, true, 'i'])
  assert.deepEqual(r.draft.chapter.blocks.map((b) => b.key), ['a', 'i', 'd'])
  r = apply(r.draft.chapter, { type: 'mergeParagraphUp', blockKey: 'd' })
  assert.deepEqual([r.merged, r.selectBlockKey], [false, 'i'])
  assert.deepEqual(r.draft.chapter.blocks.map((b) => b.key), ['a', 'i', 'd'])
})

test('保存→重开→再保存:style / marks 逐块一致,正文仍是纯文字,摘要投影不含标记', () => {
  const chapter = chapterOf([{ key: 'h', type: 'text', content: '雨夜', style: 'h2' },
    { key: 'p', type: 'text', content: '她推开门。', marks: [B(0, 1), { from: 2, to: 4, type: 'strike' }] },
    { key: 'q', type: 'text', content: '「谁？」', style: 'quote' }])
  const payload = story.toPayloadChapter(chapter)
  assert.deepEqual(payload.blocks.map((b) => [b.content, b.style, b.marks]), [
    ['雨夜', 'h2', undefined], ['她推开门。', undefined, [B(0, 1), { from: 2, to: 4, type: 'strike' }]], ['「谁？」', 'quote', undefined]])
  assert.equal(payload.description, '雨夜\n她推开门。\n「谁？」')
  const reopened = story.toPayloadChapter(story.materializeChapter(payload))
  assert.deepEqual(reopened.blocks, payload.blocks)
})

test('正文样式不写进 payload;非法样式在保存时就拒', () => {
  const body = story.toPayloadChapter(chapterOf([{ key: 'p', type: 'text', content: '甲', style: 'body', marks: [] }]))
  assert.deepEqual(Object.keys(body.blocks[0]).sort(), ['content', 'key', 'type'])
  assert.throws(() => story.toPayloadChapter({ nodes: [], blocks: [{ key: 'p', type: 'text', content: '甲', style: 'h9' }] }), /段落样式无效/)
})
