// qx-a5d(2026-11-09):文章式故事编辑器。用户原话「这样一段一段写太麻烦了 你看一下x article是怎么做的
// …玩法/图片/音频之类的统一顶部标签 然后添加 然后添加之后可以拖动 放到合适的剧情里…也可以添加分割线」。
// 钉住:回车/粘贴多段拆段、顶部插入栏插到光标处、任何块可拖动排序、保存→重开→再保存逐块一致。
const { test, beforeEach } = require('node:test')
const assert = require('node:assert/strict')

const story = require('../../pages/publish/utils/publish/pro-editor-story.js')
const drag = require('../../pages/publish/utils/publish/story-drag.js')

const copy = (v) => JSON.parse(JSON.stringify(v))
const apply = (chapter, command) => story.applyStoryCommand({ chapter, pendingMaterials: [] }, command)
const shape = (chapter) => chapter.blocks.map((b) => b.type === 'text' ? 'T:' + b.content : b.type)

function article() {
  return story.materializeChapter({
    _localId: 'c1', name: '第一章', nodes: [],
    blocks: [
      { key: 'p1', type: 'text', content: '雨停了。' },
      { key: 'p2', type: 'text', content: '她推开门。' },
    ],
  })
}

// ── 段落拆分 ──────────────────────────────────────────────
test('回车在段中:拆成两段,光标落到新段开头;块标识用调用方给的', () => {
  const r = apply(article(), { type: 'splitParagraphs', blockKey: 'p1', content: '雨停\n了。', cursor: 3, blockKeys: ['n1'] })
  assert.deepEqual(shape(r.draft.chapter), ['T:雨停', 'T:了。', 'T:她推开门。'])
  assert.equal(r.draft.chapter.blocks[1].key, 'n1')
  assert.equal(r.focusBlockKey, 'n1')
  assert.equal(r.focusCursor, 0)
})

test('段末回车:新起一段空段,光标在空段里', () => {
  const r = apply(article(), { type: 'splitParagraphs', blockKey: 'p2', content: '她推开门。\n', cursor: 6, blockKeys: ['n1'] })
  assert.deepEqual(shape(r.draft.chapter), ['T:雨停了。', 'T:她推开门。', 'T:'])
  assert.equal(r.focusBlockKey, 'n1')
})

test('在光标处粘贴多段:按段拆开,中间空行丢掉,光标落在粘贴内容之后', () => {
  const pasted = '第一段\n\n第二段\n\n第三段'
  const before = '雨停'; const after = '了。'
  const content = before + pasted + after
  const cursor = (before + pasted).length
  const keys = story.paragraphParts(content, cursor).parts.slice(1).map((_, i) => 'k' + i)
  const r = apply(article(), { type: 'splitParagraphs', blockKey: 'p1', content, cursor, blockKeys: keys })
  assert.deepEqual(shape(r.draft.chapter), ['T:雨停第一段', 'T:第二段', 'T:第三段了。', 'T:她推开门。'])
  assert.equal(r.focusBlockKey, 'k1')
  assert.equal(r.focusCursor, '第三段'.length)
})

test('普通输入(换行没变多)只改文字,不拆段;原段里已有的换行不被重排', () => {
  const chapter = story.materializeChapter({ nodes: [], blocks: [{ key: 'p', type: 'text', content: '旧\n段' }] })
  const r = apply(chapter, { type: 'splitParagraphs', blockKey: 'p', content: '旧\n段落', cursor: 4, blockKeys: [] })
  assert.deepEqual(shape(r.draft.chapter), ['T:旧\n段落'])
  assert.equal(r.focusBlockKey, 'p')
})

test('带说话人的段与节点叙事段保留段内换行,不拆', () => {
  const chapter = story.materializeChapter({ nodes: [], blocks: [{ key: 'v', type: 'text', who: '店主', content: '欢迎' }] })
  const r = apply(chapter, { type: 'splitParagraphs', blockKey: 'v', content: '欢迎\n光临', cursor: 3, blockKeys: ['x'] })
  assert.deepEqual(r.draft.chapter.blocks.map((b) => [b.key, b.content, b.who]), [['v', '欢迎\n光临', '店主']])
})

test('拆段沿用原段的条件等元数据;标识重复或缺失时整体拒绝且不改输入', () => {
  const chapter = story.materializeChapter({ nodes: [], blocks: [{ key: 'p', type: 'text', content: 'ab', when: { op: 'HAS_TAG', value: 'tag.x' } }] })
  const r = apply(chapter, { type: 'splitParagraphs', blockKey: 'p', content: 'a\nb', cursor: 2, blockKeys: ['q'] })
  assert.deepEqual(r.draft.chapter.blocks[1].when, { op: 'HAS_TAG', value: 'tag.x' })
  const before = copy(chapter)
  assert.throws(() => apply(chapter, { type: 'splitParagraphs', blockKey: 'p', content: 'a\nb', cursor: 2, blockKeys: [] }), /标识/)
  assert.throws(() => apply(chapter, { type: 'splitParagraphs', blockKey: 'p', content: 'a\nb', cursor: 2, blockKeys: ['p'] }), /标识/)
  assert.deepEqual(chapter, before)
})

test('超长单段按 5000 字上限切开', () => {
  const long = 'x'.repeat(5003) + '\n'
  const plan = story.paragraphParts(long, long.length)
  assert.deepEqual(plan.parts.map((p) => p.length), [5000, 3, 0])
  assert.equal(plan.focusPart, 2)
})

test('不敲回车、一段写超 5000 字:也按上限切成相邻段落,不会存出服务端拒收的块', () => {
  const chapter = story.materializeChapter({ nodes: [], blocks: [{ key: 'p', type: 'text', content: '' }] })
  const content = 'y'.repeat(5001)
  const r = apply(chapter, { type: 'splitParagraphs', blockKey: 'p', content, cursor: 5001, blockKeys: ['q'] })
  assert.deepEqual(r.draft.chapter.blocks.map((b) => b.content.length), [5000, 1])
  assert.equal(r.focusBlockKey, 'q')
  assert.equal(r.focusCursor, 1)
})

// ── 插到光标处 ────────────────────────────────────────────
test('光标在段中:插入栏变成 insertAtCaret,段被拆开、卡片插在中间', () => {
  const chapter = article()
  let n = 0
  const cmd = story.caretInsertCommand(chapter, { blockKey: 'p1', cursor: 2, content: '雨停了。' },
    { type: 'insertMediaAt', mediaType: 'divider', blockKey: 'd1', index: 1 }, () => 'tail' + (++n))
  assert.equal(cmd.type, 'insertAtCaret')
  const out = apply(chapter, cmd).draft.chapter
  assert.deepEqual(shape(out), ['T:雨停', 'divider', 'T:了。', 'T:她推开门。'])
})

test('光标在段首/段尾:不拆段,插在这段前/后', () => {
  const head = apply(article(), story.caretInsertCommand(article(), { blockKey: 'p2', cursor: 0 },
    { type: 'insertMediaAt', mediaType: 'audio', url: '', blockKey: 'a1' }, () => 'unused')).draft.chapter
  assert.deepEqual(shape(head), ['T:雨停了。', 'audio', 'T:她推开门。'])
  const tail = apply(article(), story.caretInsertCommand(article(), { blockKey: 'p2', cursor: 5 },
    { type: 'insertMediaAt', mediaType: 'divider', blockKey: 'd1' }, () => 'unused')).draft.chapter
  assert.deepEqual(shape(tail), ['T:雨停了。', 'T:她推开门。', 'divider'])
})

test('光标记录已过期(段落内容变了 / 段落已删)时退回下标插入,不在错的位置拆段', () => {
  const stale = story.caretInsertCommand(article(), { blockKey: 'p1', cursor: 2, content: '别的内容' },
    { type: 'insertMediaAt', mediaType: 'divider', blockKey: 'd', index: 1 }, () => 't')
  assert.deepEqual(stale, { type: 'insertMediaAt', mediaType: 'divider', blockKey: 'd', index: 1 })
  const gone = story.caretInsertCommand(article(), { blockKey: 'nope', cursor: 0 },
    { type: 'insertMediaAt', mediaType: 'divider', blockKey: 'd' }, () => 't')
  assert.equal(gone.index, 2, '没有下标时插到文末')
})

// ── 拖动排序 + 保存往返 ───────────────────────────────────
test('任何块都能拖:段落、图片、分割线、玩法卡;保存→重开→再保存逐块一致', () => {
  let chapter = story.materializeChapter({
    _localId: 'c1', name: '第一章',
    nodes: [{ _localId: 'g1', name: '暗号', templateId: 7, templateInfo: { title: '暗号' } }],
    blocks: [
      { key: 'p1', type: 'text', content: '一' },
      { key: 'img', type: 'image', url: 'https://example.com/a.jpg' },
      { key: 'p2', type: 'text', content: '二' },
      { key: 'div', type: 'divider' },
      { key: 'g', type: 'node', nodeKey: 'g1', locationRequired: false },
      { key: 'p3', type: 'text', content: '三' },
    ],
  })
  chapter = apply(chapter, { type: 'moveBlock', blockKey: 'p3', index: 0 }).draft.chapter
  chapter = apply(chapter, { type: 'moveBlock', blockKey: 'g', index: 2 }).draft.chapter
  chapter = apply(chapter, { type: 'moveBlock', blockKey: 'div', index: 5 }).draft.chapter
  assert.deepEqual(chapter.blocks.map((b) => b.key), ['p3', 'p1', 'g', 'img', 'p2', 'div'])
  const saved = story.toPayloadChapter(chapter)
  assert.deepEqual(saved.blocks.map((b) => b.type), ['text', 'text', 'node', 'image', 'text', 'divider'])
  assert.deepEqual(saved.blocks[5], { type: 'divider', key: 'div' })
  // 服务端读回:节点带回本地身份(页面在 applyEditingTopic 里做同一件事),块的 key 原样带回
  const readBack = copy(saved); readBack.nodes.forEach((node, i) => { node._localId = 'srv' + i })
  const reopened = story.materializeChapter(readBack, () => { throw new Error('重开不应补新标识') })
  assert.deepEqual(reopened.blocks.map((b) => b.key), ['p3', 'p1', 'g', 'img', 'p2', 'div'])
  assert.deepEqual(story.toPayloadChapter(reopened).blocks, saved.blocks)
  assert.equal(saved.description, '三\n一', '摘要投影仍在首个节点处截断')
})

test('拖动不改输入草稿;下标越界整体拒绝', () => {
  const chapter = article(); const before = copy(chapter)
  apply(chapter, { type: 'moveBlock', blockKey: 'p1', index: 1 })
  assert.deepEqual(chapter, before)
  assert.throws(() => apply(chapter, { type: 'moveBlock', blockKey: 'p1', index: 2 }), /插入点/)
})

// ── 拖拽几何 ──────────────────────────────────────────────
test('拖拽几何:中线越过邻块中点才换位;让位量 = 被拖块高 + 间距;贴边自动滚', () => {
  const rects = [{ top: 0, height: 40 }, { top: 50, height: 100 }, { top: 160, height: 30 }]
  assert.equal(drag.targetIndex(rects, 0, 20), 0)
  assert.equal(drag.targetIndex(rects, 0, 101), 1)
  assert.equal(drag.targetIndex(rects, 0, 400), 2)
  assert.equal(drag.targetIndex(rects, 2, -10), 0)
  assert.deepEqual(drag.shiftFor(rects, 0, 2), [0, -50, -50])
  assert.deepEqual(drag.shiftFor(rects, 2, 0), [40, 40, 0])
  assert.equal(drag.edgeScrollStep(300, 100, 600), 0)
  assert.ok(drag.edgeScrollStep(105, 100, 600) < 0)
  assert.ok(drag.edgeScrollStep(598, 100, 600) > 0)
})

// ── 页面接线 ──────────────────────────────────────────────
const PAGE = '../../pages/publish/fabu/index.js'
let pageConfig; let vibrations; let toasts; let queryRects
global.getApp = () => ({
  globalData: { statusBarHeight: 20, navBarHeight: 44 }, getUserID: () => 42, getNickname: () => '', getAvatar: () => '',
  sendRequest: () => {}, chooseImage: () => {}, tips: () => {}, getRequestErrorMessage: (e, f) => (e && e.message) || f,
})
global.wx = {
  getStorageSync: () => undefined, setStorageSync: () => {}, removeStorageSync: () => {},
  getSystemInfoSync: () => ({ windowWidth: 390 }), showToast: (o) => toasts.push(o && o.title),
  showModal: (o) => o && o.success && o.success({ confirm: true }), navigateTo: () => {},
  createMapContext: () => ({ getCenterLocation: () => {} }), pageScrollTo: () => {}, nextTick: (cb) => cb(),
  vibrateShort: (o) => vibrations.push(o && o.type),
  createSelectorQuery: () => {
    const q = { selectAll: () => q, select: () => q, boundingClientRect: () => q, exec: (cb) => cb([queryRects, { top: 100, bottom: 700 }]) }
    return q
  },
}
global.Page = (config) => { pageConfig = config }
function setAtPath(target, rawPath, value) {
  const parts = rawPath.replace(/\[(\d+)\]/g, '.$1').split('.')
  let cursor = target
  for (let i = 0; i < parts.length - 1; i += 1) { if (cursor[parts[i]] == null) cursor[parts[i]] = {}; cursor = cursor[parts[i]] }
  cursor[parts[parts.length - 1]] = value
}
function openPage() {
  const page = Object.assign({}, pageConfig)
  page.data = JSON.parse(JSON.stringify(pageConfig.data))
  page.setData = function (patch, cb) { Object.keys(patch).forEach((k) => setAtPath(page.data, k, patch[k])); if (cb) cb() }
  page.updateAllStatistics = () => {}
  page._canAddFormalNodes = () => true
  let id = 0
  page._genLocalId = () => 'L' + (++id)
  page.data.formData.productType = 1
  page.data.formData.chapters = [{ _localId: 'c1', name: '第一章', nodes: [], blocks: [
    { key: 'p1', type: 'text', content: '雨停了。' }, { key: 'p2', type: 'text', content: '她推开门。' }] }]
  page.openStoryEditor({ currentTarget: { dataset: { index: 0 } } })
  return page
}
const blocksOf = (page) => page.data.formData.chapters[0].blocks
const ev = (dataset, detail) => ({ currentTarget: { dataset }, detail: detail || {} })

beforeEach(() => {
  vibrations = []; toasts = []; queryRects = []
  delete require.cache[require.resolve(PAGE)]
  require(PAGE)
})

test('页面:敲回车自动起新段,光标跟过去', () => {
  const page = openPage()
  page.onStoryTextInput(ev({ blockkey: 'p1' }, { value: '雨停\n了。', cursor: 3 }))
  assert.deepEqual(blocksOf(page).map((b) => b.content), ['雨停', '了。', '她推开门。'])
  assert.equal(page.data.storyEditor.focusBlockKey, blocksOf(page)[1].key)
  assert.equal(page.data.storyEditor.focusCursor, 0)
})

test('页面:点插入栏前输入框失焦,分割线插到失焦时的光标处', () => {
  const page = openPage()
  page.onStoryTextBlur(ev({ blockkey: 'p2' }, { value: '她推开门。', cursor: 2 }))
  page.onStoryToolbarInsert(ev({ kind: 'divider' }))
  assert.deepEqual(blocksOf(page).map((b) => b.type === 'text' ? b.content : b.type), ['雨停了。', '她推', 'divider', '开门。'])
})

test('页面:没有光标时插到文末;音频先落空块', () => {
  const page = openPage()
  page.onStoryToolbarInsert(ev({ kind: 'audio' }))
  assert.deepEqual(blocksOf(page).map((b) => b.type), ['text', 'text', 'audio'])
})

test('页面:长按拖动段落,拿起和放下各震一次,松手后顺序改变', () => {
  const page = openPage()
  queryRects = [{ top: 120, height: 40 }, { top: 170, height: 40 }]
  page.onStoryDragStart({ currentTarget: { dataset: { blockkey: 'p1', index: 0 } }, touches: [{ clientY: 140 }] })
  assert.equal(page.data.storyDrag.key, 'p1')
  page.onStoryDragMove({ touches: [{ clientY: 200 }] })
  assert.equal(page.data.storyDrag.dy, 60)
  assert.deepEqual(page.data.storyDrag.shift, [0, -50])
  page.onStoryDragEnd()
  assert.deepEqual(blocksOf(page).map((b) => b.key), ['p2', 'p1'])
  assert.deepEqual(vibrations, ['light', 'light'])
  assert.equal(page.data.storyDrag.key, '')
})

test('页面:长按原地松手 = 选中该块(显示删除键),不改顺序', () => {
  const page = openPage()
  queryRects = [{ top: 120, height: 40 }, { top: 170, height: 40 }]
  page.onStoryDragStart({ currentTarget: { dataset: { blockkey: 'p2', index: 1 } }, touches: [{ clientY: 190 }] })
  page.onStoryDragEnd()
  assert.deepEqual(blocksOf(page).map((b) => b.key), ['p1', 'p2'])
  assert.equal(page.data.storyEditor.selectedBlockKey, 'p2')
})

test('页面:保存 payload 与重开后的块顺序一致', () => {
  const page = openPage()
  page.onStoryTextInput(ev({ blockkey: 'p1' }, { value: '雨停\n了。', cursor: 3 }))
  page.onStoryTextBlur(ev({ blockkey: 'p2' }, { value: '她推开门。', cursor: 5 }))
  page.onStoryToolbarInsert(ev({ kind: 'divider' }))
  const payload = page._storyPayloadChapter(page.data.formData.chapters[0], 0)
  assert.deepEqual(payload.blocks.map((b) => b.type === 'text' ? b.content : b.type), ['雨停', '了。', '她推开门。', 'divider'])
  const reopened = story.toPayloadChapter(story.materializeChapter(payload))
  assert.deepEqual(reopened.blocks, payload.blocks)
})

test('页面:图片先选图上传,回来后插到点插入栏时的光标处(段中拆开)', () => {
  // app 在页面模块加载时取一次(let app = getApp()),所以在 require 前替换 chooseImage
  let pick = null
  const appStub = global.getApp()
  global.getApp = () => Object.assign(appStub, { chooseImage: (cb) => { pick = cb; return { isFinished: () => false, isAborted: () => false } } })
  delete require.cache[require.resolve(PAGE)]
  require(PAGE)
  const page = openPage()
  page.onStoryTextBlur(ev({ blockkey: 'p1' }, { value: '雨停了。', cursor: 2 }))
  page.onStoryToolbarInsert(ev({ kind: 'image' }))
  assert.equal(typeof pick, 'function', '先选图上传,不先落空块')
  assert.deepEqual(blocksOf(page).map((b) => b.type), ['text', 'text'])
  pick(['https://example.com/rain.jpg'])
  assert.deepEqual(blocksOf(page).map((b) => b.type === 'text' ? b.content : b.type), ['雨停', 'image', '了。', '她推开门。'])
  assert.equal(blocksOf(page)[1].url, 'https://example.com/rain.jpg')
})
