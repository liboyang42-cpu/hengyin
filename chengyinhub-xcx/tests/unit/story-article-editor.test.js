// qx-a5d(2026-11-09):文章式故事编辑器。用户原话「这样一段一段写太麻烦了 你看一下x article是怎么做的
// …玩法/图片/音频之类的统一顶部标签 然后添加 然后添加之后可以拖动 放到合适的剧情里…也可以添加分割线」。
// 钉住:回车/粘贴多段拆段、顶部插入栏插到光标处、任何块可拖动排序、保存→重开→再保存逐块一致。
const { test, beforeEach } = require('node:test')
const assert = require('node:assert/strict')

const story = require('../../pages/publish/utils/publish/pro-editor-story.js')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

// 渲染层拖拽内核是 WXS(ES5 + module.exports),在 node 里按原文件求值来测
function loadWxs(file) {
  const module = { exports: {} }
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../..', file), 'utf8'), { module, exports: module.exports, Math, JSON, Number })
  return module.exports
}
const sd = loadWxs('pages/publish/utils/wxs/story-drag.wxs')

// 假的 ownerInstance:列表上挂 data-geo,块记录 setStyle,callMethod 记账,rAF 手动推进
function fakeOwner(geo, count) {
  const state = {}
  const blocks = Array.from({ length: count }, () => ({ style: null, setStyle(s) { this.style = s } }))
  const calls = []
  const frames = []
  return {
    blocks, calls, frames,
    getState: () => state,
    selectComponent: (sel) => sel === '.story-article' ? { getDataset: () => ({ geo: geo ? JSON.stringify(geo) : '' }) } : null,
    selectAllComponents: (sel) => sel === '.story-block' ? blocks : [],
    callMethod: (name, arg) => calls.push([name, JSON.parse(JSON.stringify(arg))]), // vm 里建的对象跨 realm,先拷成本地对象
    requestAnimationFrame: (cb) => frames.push(cb),
  }
}
const touch = (y) => ({ touches: [{ clientY: y }], changedTouches: [{ clientY: y }] })

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

// ── 拖拽内核(WXS,渲染层)────────────────────────────────
const GEO = { key: 'p1', from: 0, y: 140, st: 0, vt: 100, vb: 700, max: 400, top: [120, 170, 280], h: [40, 100, 30] }

test('WXS 拖拽:被拖块 1:1 跟手、中线越过邻块中点才让位,让位量 = 被拖块高 + 间距', () => {
  const owner = fakeOwner(GEO, 3)
  sd.start(touch(140), owner)
  assert.equal(sd.move(touch(160), owner), false, '拖动中吃掉 touchmove,页面不跟着滚')
  assert.match(owner.blocks[0].style.transform, /translate3d\(0,20px,0\) scale\(1\.02\)/)
  assert.equal(owner.blocks[1].style.transform, 'translate3d(0,0px,0)')
  sd.move(touch(240), owner) // 中线 140+100=240 > 第二块中点 220
  assert.equal(owner.blocks[1].style.transform, 'translate3d(0,-50px,0)')
  assert.equal(owner.blocks[2].style.transform, 'translate3d(0,0px,0)')
  assert.deepEqual(owner.calls.filter((c) => c[0] === 'onStoryDragSlot').map((c) => c[1].target), [1], '跨一格只回调一次(触感)')
  sd.end(touch(240), owner)
  assert.deepEqual(owner.calls.pop(), ['onStoryDragDrop', { key: 'p1', from: 0, to: 1, scrollTop: 0 }])
})

test('WXS 拖拽:往上拖,上面的块整体下移让位', () => {
  const owner = fakeOwner(Object.assign({}, GEO, { key: 'p3', from: 2, y: 290 }), 3)
  sd.start(touch(290), owner)
  sd.move(touch(120), owner)
  assert.equal(owner.blocks[0].style.transform, 'translate3d(0,40px,0)')
  assert.equal(owner.blocks[1].style.transform, 'translate3d(0,40px,0)')
  sd.end(touch(120), owner)
  assert.equal(owner.calls.pop()[1].to, 0)
})

test('WXS 拖拽:贴近下沿自动滚屏,滚动量计进位移,不超过最大 scrollTop;逻辑层每两帧收到一次 scrollTop', () => {
  const owner = fakeOwner(GEO, 3)
  sd.start(touch(140), owner)
  sd.move(touch(690), owner)
  assert.equal(owner.frames.length, 1, '进入边缘区启动 rAF 循环')
  for (let i = 0; i < 60 && owner.frames.length; i += 1) owner.frames.shift()()
  const scrolls = owner.calls.filter((c) => c[0] === 'onStoryDragScroll').map((c) => c[1].scrollTop)
  assert.ok(scrolls.length >= 2)
  assert.ok(scrolls.every((v, i) => i === 0 || v >= scrolls[i - 1]))
  assert.ok(scrolls[scrolls.length - 1] <= GEO.max)
  sd.end(touch(690), owner)
  const drop = owner.calls.pop()
  assert.equal(drop[0], 'onStoryDragDrop')
  assert.ok(drop[1].scrollTop > 0 && drop[1].scrollTop <= GEO.max)
  assert.equal(drop[1].to, 2)
})

test('WXS 拖拽:没拿起(无几何)时 touchmove 不拦截;松手只通知逻辑层取消待定的拿起', () => {
  const owner = fakeOwner(null, 2)
  sd.start(touch(100), owner)
  assert.equal(sd.move(touch(130), owner), false)
  assert.equal(owner.blocks[0].style, null)
  sd.end(touch(130), owner)
  assert.deepEqual(owner.calls, [['onStoryTouchRelease', {}]])
})

test('WXS 拖拽:落下后 epoch 变化 → 清掉所有块的临时 transform', () => {
  const owner = fakeOwner(GEO, 3)
  sd.start(touch(140), owner); sd.move(touch(240), owner)
  sd.reset(2, 1, owner)
  assert.ok(owner.blocks.every((b) => b.style.transform === 'none'))
  owner.frames.shift()()
  assert.ok(owner.blocks.every((b) => Object.keys(b.style).length === 0))
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
    const q = { selectAll: () => q, select: () => q, boundingClientRect: () => q, scrollOffset: () => q, exec: (cb) => cb([queryRects, { top: 100, bottom: 700, height: 600 }, { scrollTop: 0, scrollHeight: 600 }]) }
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
  page.onStoryAdd(ev({ kind: 'divider' }))
  assert.deepEqual(blocksOf(page).map((b) => b.type === 'text' ? b.content : b.type), ['雨停了。', '她推', 'divider', '开门。'])
})

test('页面:没有光标时插到文末;音频先落空块', () => {
  const page = openPage()
  page.onStoryAdd(ev({ kind: 'audio' }))
  assert.deepEqual(blocksOf(page).map((b) => b.type), ['text', 'text', 'audio'])
})

test('页面:长按量一次几何挂到 data-geo,拿起震一次;渲染层回调落点后改顺序,epoch +1', () => {
  const page = openPage()
  queryRects = [{ top: 120, height: 40 }, { top: 170, height: 40 }]
  page.onStoryDragArm({ currentTarget: { id: 'sb-p1', dataset: { blockkey: 'p1', index: 0 } }, target: { dataset: {} }, touches: [{ clientY: 140 }] })
  assert.equal(page.data.storyDrag.key, 'p1')
  const geo = JSON.parse(page.data.storyDrag.geo)
  assert.deepEqual([geo.from, geo.y, geo.top, geo.h], [0, 140, [120, 170], [40, 40]])
  const epoch = page.data.storyDrag.epoch
  page.onStoryDragSlot({ target: 1 })
  page.onStoryDragDrop({ key: 'p1', from: 0, to: 1, scrollTop: 0 })
  assert.deepEqual(blocksOf(page).map((b) => b.key), ['p2', 'p1'])
  assert.deepEqual(vibrations, ['light', 'light', 'medium'])
  assert.deepEqual([page.data.storyDrag.key, page.data.storyDrag.geo, page.data.storyDrag.epoch], ['', '', epoch + 1])
})

test('页面:长按原地松手 = 选中该块(显示删除键),不改顺序', () => {
  const page = openPage()
  queryRects = [{ top: 120, height: 40 }, { top: 170, height: 40 }]
  page.onStoryDragArm({ currentTarget: { id: 'sb-p2', dataset: { blockkey: 'p2', index: 1 } }, target: { dataset: {} }, touches: [{ clientY: 190 }] })
  page.onStoryDragDrop({ key: 'p2', from: 1, to: 1, scrollTop: 0 })
  assert.deepEqual(blocksOf(page).map((b) => b.key), ['p1', 'p2'])
  assert.equal(page.data.storyEditor.selectedBlockKey, 'p2')
})

test('页面:几何还没量完手就松了 → 不进入拖拽态,按原地松手处理', () => {
  const page = openPage()
  queryRects = [{ top: 120, height: 40 }, { top: 170, height: 40 }]
  let pending = null
  const realQuery = wx.createSelectorQuery
  wx.createSelectorQuery = () => { const q = realQuery(); q.exec = (cb) => { pending = cb }; return q }
  page.onStoryDragArm({ currentTarget: { id: 'sb-p1', dataset: { blockkey: 'p1', index: 0 } }, target: { dataset: {} }, touches: [{ clientY: 140 }] })
  page.onStoryTouchRelease()
  pending([queryRects, { top: 100, bottom: 700, height: 600 }, { scrollTop: 0, scrollHeight: 600 }])
  wx.createSelectorQuery = realQuery
  assert.equal(page.data.storyDrag.key, '')
  assert.equal(page.data.storyEditor.selectedBlockKey, 'p1')
})

test('页面:正在输入的段长按留给系统选字;按住把手仍然能拖', () => {
  const page = openPage()
  queryRects = [{ top: 120, height: 40 }, { top: 170, height: 40 }]
  page._storyFocusKey = 'p1'
  page.data.storyEditor.kb = 300
  page.onStoryDragArm({ currentTarget: { id: 'sb-p1', dataset: { blockkey: 'p1', index: 0 } }, target: { dataset: { blockkey: 'p1' } }, touches: [{ clientY: 140 }] })
  assert.equal(page.data.storyDrag.key, '')
  page.onStoryDragArm({ currentTarget: { id: 'sb-p1', dataset: { blockkey: 'p1', index: 0 } }, target: { dataset: { grip: '1', blockkey: 'p1' } }, touches: [{ clientY: 140 }] })
  assert.equal(page.data.storyDrag.key, 'p1')
})

test('页面:保存 payload 与重开后的块顺序一致', () => {
  const page = openPage()
  page.onStoryTextInput(ev({ blockkey: 'p1' }, { value: '雨停\n了。', cursor: 3 }))
  page.onStoryTextBlur(ev({ blockkey: 'p2' }, { value: '她推开门。', cursor: 5 }))
  page.onStoryAdd(ev({ kind: 'divider' }))
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
  page.onStoryAdd(ev({ kind: 'image' }))
  assert.equal(typeof pick, 'function', '先选图上传,不先落空块')
  assert.deepEqual(blocksOf(page).map((b) => b.type), ['text', 'text'])
  pick(['https://example.com/rain.jpg'])
  assert.deepEqual(blocksOf(page).map((b) => b.type === 'text' ? b.content : b.type), ['雨停', 'image', '了。', '她推开门。'])
  assert.equal(blocksOf(page)[1].url, 'https://example.com/rain.jpg')
})

// ── r2-create(2026-11-10):段首哨兵 / 退格合并 / 工具栏格式 ─────────────────
const ZW = '​'

test('页面:非首段输入框值带段首哨兵,收字时去掉哨兵并把光标换回正文坐标', () => {
  const page = openPage()
  page.onStoryTextInput(ev({ blockkey: 'p2' }, { value: ZW + '她推开门了。', cursor: 7 }))
  assert.equal(blocksOf(page)[1].content, '她推开门了。')
  assert.equal(page._storyTextCursor.index, 6)
})

test('页面:段首退格(哨兵被删、正文没变)→ 并入上一段,光标落在接缝处', () => {
  const page = openPage()
  page.onStoryTextInput(ev({ blockkey: 'p2' }, { value: '她推开门。', cursor: 0 }))
  assert.deepEqual(blocksOf(page).map((b) => b.content), ['雨停了。她推开门。'])
  assert.equal(page.data.storyEditor.focusBlockKey, 'p1')
  assert.equal(page.data.storyEditor.focusCursor, 4)
})

test('页面:光标落在哨兵前又打了字 → 摘掉哨兵照常收字,不误判成退格', () => {
  const page = openPage()
  page.onStoryTextInput(ev({ blockkey: 'p2' }, { value: '「' + ZW + '她推开门。', cursor: 1 }))
  assert.deepEqual(blocksOf(page).map((b) => b.content), ['雨停了。', '「她推开门。'])
})

// ── l-editor(2026-11-10):顶部添加栏 / 章节开始结束卡 / 图片组 / 分支任务 ─────────────────
test('页面:添加栏「段落」插到选中块之后并给撤销条;「分支」落块并打开分支弹窗;「图片」先出版式再落空格', () => {
  const page = openPage()
  page.setData({ 'storyEditor.selectedBlockKey': 'p1' })
  page.onStoryAdd(ev({ kind: 'text' }))
  assert.deepEqual(blocksOf(page).map((b) => b.type === 'text' ? b.content : b.type), ['雨停了。', '', '她推开门。'])
  assert.equal(page.data.storyUndo.show, true)
  page.onStoryAdd(ev({ kind: 'branch' }))
  const branch = blocksOf(page).find((b) => b.type === 'branch')
  assert.ok(branch)
  assert.equal(page.data.storySheet, 'branch')
  assert.equal(page.data.storyBranch.blockKey, branch.key)
  page.onStoryBranchQuestion({ detail: { value: '先去哪?' } })
  page.onStoryBranchOptionText(ev({ i: 0 }, { value: '邮局' }))
  page.onStoryBranchTo(ev({ i: 1, to: 'end' }))
  page.addStoryBranchOption()
  assert.deepEqual(blocksOf(page).find((b) => b.type === 'branch').options, [{ text: '邮局', to: 'continue' }, { text: '', to: 'end' }, { text: '', to: 'continue' }])
  assert.equal(page.data.storyBranch.options.length, 3)
  assert.deepEqual(page.data.storyBranchTos.map((t) => t.value), ['continue', 'end'], '只有一章时没有「跳到第 N 章」')
  page.closeStorySheet()
  page.onStoryAdd(ev({ kind: 'gallery' }))
  assert.equal(page.data.storySheet, 'gallery')
  page.onStoryGalleryLayout(ev({ n: 3 }))
  const gallery = blocksOf(page).find((b) => b.type === 'gallery')
  assert.deepEqual([gallery.layout, gallery.images], [3, []])
  assert.deepEqual(page.data.storyGalleryCells[gallery.key], ['', '', ''])
  page.onStoryGalleryCaptionInput(ev({ blockkey: gallery.key }, { value: '吧台' }))
  assert.equal(blocksOf(page).find((b) => b.type === 'gallery').altText, '吧台')
  page.undoStoryStep()
  assert.ok(!blocksOf(page).some((b) => b.type === 'gallery'), '撤销回到落图片组之前')
})

test('页面:开场白 / 结语写在章节卡上,派生去向;上送时还原成首尾 cap 块', () => {
  const page = openPage()
  assert.deepEqual(page.data.storyCaps, { open: '', end: '', next: '结束主题' })
  page.onStoryCapInput(ev({ cap: 'open' }, { value: '雨刚停。' }))
  page.onStoryCapInput(ev({ cap: 'end' }, { value: '门铃响了。' }))
  assert.deepEqual([page.data.storyCaps.open, page.data.storyCaps.end], ['雨刚停。', '门铃响了。'])
  const payload = page._storyPayloadChapter(page.data.formData.chapters[0], 0)
  assert.deepEqual(payload.blocks.map((b) => b.type + ':' + (b.cap || b.content)), ['cap:open', 'text:雨停了。', 'text:她推开门。', 'cap:end'])
})

test('页面:拖进段落中间 → 渲染层回调 split,页面把那一段临时拆成两半;松手按 split 拆段并给撤销条', () => {
  const page = openPage()
  page.data.formData.chapters[0].blocks[0].content = '雨停了。她推开门。'
  queryRects = [{ top: 120, height: 80 }, { top: 210, height: 40 }]
  page.onStoryDragArm({ currentTarget: { id: 'sb-p2', dataset: { blockkey: 'p2', index: 1 } }, target: { dataset: {} }, touches: [{ clientY: 230 }] })
  const geo = JSON.parse(page.data.storyDrag.geo)
  assert.deepEqual([geo.kind, geo.len, geo.bounds], [['p', ''], [9, 5], [[4], []]])
  page.onStoryDragSplit({ index: 0, at: 4, h: 40 })
  assert.deepEqual(page.data.storyDrag.split, { key: 'p1', at: 4, a: '雨停了。', b: '她推开门。', h: 40 })
  page.onStoryDragDrop({ key: 'p2', from: 1, to: 1, scrollTop: 0, split: { index: 0, at: 4 } })
  assert.deepEqual(blocksOf(page).map((b) => b.content), ['雨停了。', '她推开门。', '她推开门。'])
  assert.equal(blocksOf(page)[1].key, 'p2')
  assert.equal(page.data.storyDrag.split, null)
  assert.match(page.data.storyUndo.text, /拆成两段/)
})
