const { test } = require('node:test')
const assert = require('node:assert/strict')

const storyFlow = require('../../pages/publish/utils/publish/pro-editor-story.js')

// #18 故事媒体 altText:后端 ChapterBlockDTO.altText(图片文字替代 / 音频文字稿,≤2000 字,#450)已在 master。
// 编辑器此前读取与提交都把媒体块重建成 {key,type,url},altText 一进一出就丢。
// 这里断言它在 读取 → 编辑 → 提交 → 再读取 整条链上不丢、不串块,且旧数据(没有 altText)形状不变。

function chapterWithMedia(blocks) {
  return {
    _localId: 'chapter-1', name: '第一章', required: 1,
    nodes: [{ id: 501, _localId: 'node-1', name: '旧码头', templateId: 7 }],
    blocks: [{ key: 'node-block', type: 'node', nodeKey: 'node-1' }].concat(blocks),
  }
}

function draftOf(chapter) {
  return { chapter: storyFlow.materializeChapter(chapter, () => 'gen') }
}

test('读取时保留图片/音频块的 altText,提交时原样写回', () => {
  const opened = storyFlow.materializeChapter(chapterWithMedia([
    { key: 'img', type: 'image', url: 'https://cdn.example/a.jpg', altText: '桥洞下的旧船' },
    { key: 'aud', type: 'audio', url: 'https://cdn.example/b.mp3', altText: '“别回头。”' },
  ]))
  assert.equal(opened.blocks[1].altText, '桥洞下的旧船')
  assert.equal(opened.blocks[2].altText, '“别回头。”')
  const payload = storyFlow.toPayloadChapter(opened)
  assert.deepEqual(payload.blocks.slice(1), [
    { key: 'img', type: 'image', url: 'https://cdn.example/a.jpg', altText: '桥洞下的旧船' },
    { key: 'aud', type: 'audio', url: 'https://cdn.example/b.mp3', altText: '“别回头。”' },
  ])
  const reopened = storyFlow.materializeChapter({ ...opened, blocks: payload.blocks })
  assert.deepEqual(reopened.blocks.filter(b => b.type !== 'node').map(b => b.altText), ['桥洞下的旧船', '“别回头。”'])
})

test('没有 altText 的存量媒体块形状不变,不凭空带空键', () => {
  const opened = storyFlow.materializeChapter(chapterWithMedia([
    { key: 'img', type: 'image', url: 'a.jpg' },
    { key: 'img2', type: 'image', url: 'c.jpg', altText: null },
  ]))
  assert.equal('altText' in opened.blocks[1], false)
  assert.equal('altText' in opened.blocks[2], false)
  const payload = storyFlow.toPayloadChapter(opened)
  assert.deepEqual(payload.blocks.slice(1), [
    { key: 'img', type: 'image', url: 'a.jpg' },
    { key: 'img2', type: 'image', url: 'c.jpg' },
  ])
})

test('editMediaAltText 只改目标媒体块;清空即删除;纯空白不上送', () => {
  let draft = draftOf(chapterWithMedia([
    { key: 'img', type: 'image', url: 'a.jpg' },
    { key: 'aud', type: 'audio', url: 'b.mp3', altText: '旧文字稿' },
  ]))
  draft = storyFlow.applyStoryCommand(draft, { type: 'editMediaAltText', blockKey: 'img', altText: ' 一盏灯 ' }).draft
  const img = draft.chapter.blocks.find(b => b.key === 'img')
  assert.equal(img.altText, ' 一盏灯 ', '输入中不 trim,否则删到空会回弹')
  assert.equal(img.url, 'a.jpg')
  assert.equal(draft.chapter.blocks.find(b => b.key === 'aud').altText, '旧文字稿', '不串到别的块')
  draft = storyFlow.applyStoryCommand(draft, { type: 'editMediaAltText', blockKey: 'aud', altText: '' }).draft
  assert.equal('altText' in draft.chapter.blocks.find(b => b.key === 'aud'), false)
  draft = storyFlow.applyStoryCommand(draft, { type: 'editMediaAltText', blockKey: 'aud', altText: '   ' }).draft
  const payload = storyFlow.toPayloadChapter(draft.chapter)
  assert.equal(payload.blocks.find(b => b.key === 'img').altText, '一盏灯')
  assert.equal('altText' in payload.blocks.find(b => b.key === 'aud'), false)
})

test('altText 只给媒体块,超过 2000 字当场拒绝且提交也拒', () => {
  const draft = draftOf(chapterWithMedia([
    { key: 'img', type: 'image', url: 'a.jpg' },
    { key: 'txt', type: 'text', content: '正文' },
  ]))
  assert.throws(() => storyFlow.applyStoryCommand(draft, { type: 'editMediaAltText', blockKey: 'txt', altText: 'x' }), /未找到要编辑的媒体块/)
  assert.throws(() => storyFlow.applyStoryCommand(draft, { type: 'editMediaAltText', blockKey: 'img',
    altText: 'x'.repeat(storyFlow.MEDIA_ALT_TEXT_MAX + 1) }), /图片说明不能超过 2000 字/)
  storyFlow.applyStoryCommand(draft, { type: 'editMediaAltText', blockKey: 'img', altText: 'x'.repeat(2000) })
  const tooLong = storyFlow.materializeChapter(chapterWithMedia([{ key: 'aud', type: 'audio', url: 'b.mp3', altText: 'y'.repeat(2001) }]))
  assert.throws(() => storyFlow.toPayloadChapter(tooLong), /音频文字稿不能超过 2000 字/)
})

test('空音频占位块带着 altText 也不落库(仍按空 url 过滤)', () => {
  const opened = storyFlow.materializeChapter(chapterWithMedia([{ key: 'aud', type: 'audio', url: '', altText: '先写好的文字稿' }]))
  assert.equal(opened.blocks[1].altText, '先写好的文字稿', '重开占位块不丢已写的文字稿')
  assert.equal(storyFlow.toPayloadChapter(opened).blocks.some(b => b.type === 'audio'), false)
})
