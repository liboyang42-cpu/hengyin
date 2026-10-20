'use strict'

// SC-THOUGHT-CHAPTER(P1,pages/publish/fabu/index):编辑已有主题 → 新增章节 → 保存 → 重开。
// 验收:
//   · 章节键唯一(服务端 id / 本地 _localId 都不撞);
//   · 普通新章不继承上一章(结局章)的结局设置;
//   · 条件不断链:结局章的「伏笔应验」条件、正文块的 when、伏笔块的 thoughtKey
//     在 保存载荷 → 服务端回显 → applyEditingTopic 之后仍挂在原来那几章上。
// 「服务端回显」这一步按 ChapterDTO.ending → journeyStory.ending.endings[chapterId] 的契约
// (见 utils/publish/story-ending.js 顶注)在测试里拼装,不发真实请求。
const { test, beforeEach } = require('node:test')
const assert = require('node:assert/strict')

const PAGE = '../../pages/publish/fabu/index.js'

let pageConfig
let storage
let requests

global.getApp = () => ({
  globalData: { statusBarHeight: 20, navBarHeight: 44 },
  getUserID: () => 42,
  getNickname: () => '测试创作者',
  getAvatar: () => '',
  sendRequest: options => { requests.push(options) },
  getRequestErrorMessage: (_e, fallback) => fallback,
  chooseImage: () => {},
  tips: () => {},
})

global.wx = {
  getStorageSync: key => storage[key],
  setStorageSync: (key, value) => { storage[key] = value },
  removeStorageSync: key => { delete storage[key] },
  getSystemInfoSync: () => ({ windowWidth: 375 }),
  showToast: () => {},
  hideLoading: () => {},
  showModal: options => { if (options && options.success) options.success({ confirm: true }) },
  createMapContext: () => ({ getCenterLocation: () => {} }),
  pageScrollTo: () => {},
  nextTick: callback => callback(),
}

global.Page = config => { pageConfig = config }

function setAtPath(target, rawPath, value) {
  const parts = rawPath.replace(/\[(\d+)\]/g, '.$1').split('.')
  let cursor = target
  for (let i = 0; i < parts.length - 1; i += 1) {
    if (cursor[parts[i]] == null) cursor[parts[i]] = {}
    cursor = cursor[parts[i]]
  }
  cursor[parts[parts.length - 1]] = value
}

function makePage() {
  const page = Object.assign({}, pageConfig)
  page.data = JSON.parse(JSON.stringify(pageConfig.data))
  page.setData = function (patch, callback) {
    Object.keys(patch).forEach(key => setAtPath(page.data, key, patch[key]))
    if (callback) callback()
  }
  page.updateAllStatistics = function () { this.refreshPrimaryActionState() }
  return page
}

beforeEach(() => {
  storage = {}
  requests = []
  pageConfig = null
  delete require.cache[require.resolve(PAGE)]
  require(PAGE)
})

const RULES = JSON.stringify({ schemaVersion: 1, thoughts: [{ key: 'w', name: '那扇窗', need: 10 }] })

function serverDetail(chapters, endings) {
  return {
    topic: { id: 501, name: '伏笔主题', productType: 1, publishMode: 'pro' },
    journeyRules: RULES,
    journeyStory: JSON.stringify({ schemaVersion: 1, ending: { endings } }),
    chapters,
    tickets: [],
  }
}

const ORIGINAL = serverDetail([
  { id: 70, name: '第1章', schemaVersion: 1, required: 1, cmsTopicNodeList: [], blocks: [
    { key: 'b1', type: 'text', content: '窗外有风', when: { op: 'HAS_TAG', value: 'thought.w.done' } },
    { key: 'b2', type: 'thought', thoughtKey: 'w' },
  ] },
  { id: 80, name: '窗外', schemaVersion: 1, required: 1, cmsTopicNodeList: [], blocks: [] },
  { id: 90, name: '余波', schemaVersion: 1, required: 1, cmsTopicNodeList: [], blocks: [] },
], [
  { code: 'window', title: '窗外', when: [{ op: 'HAS_TAG', value: 'thought.w.done' }], chapterId: 80 },
  { code: 'default', title: '余波', fallback: true, chapterId: 90 },
])

/** 模拟服务端:新章拿到新 id;结局按章重建并记下 chapterId;块原样回显。 */
function echoFromServer(payloadChapters) {
  let nextId = 200
  const chapters = payloadChapters.map(ch => Object.assign({}, ch, {
    id: ch.id == null ? ++nextId : ch.id, cmsTopicNodeList: ch.nodes || [], nodes: undefined, ending: undefined,
  }))
  const endings = []
  payloadChapters.forEach((ch, i) => {
    if (!ch.ending) return
    const row = { code: 'c' + chapters[i].id, title: ch.name, chapterId: chapters[i].id }
    if (ch.ending.fallback) row.fallback = true
    else row.when = ch.ending.when
    endings.push(row)
  })
  return serverDetail(chapters, endings)
}

function chapterKey(c) { return c.id != null ? 'id:' + c.id : 'lid:' + c._localId }

function assertKeysUnique(chapters, label) {
  const keys = chapters.map(chapterKey)
  assert.ok(keys.every(k => k !== 'lid:undefined' && k !== 'lid:'), label + ':每章都要有键 ' + keys.join(','))
  assert.equal(new Set(keys).size, keys.length, label + ':章节键重复 ' + keys.join(','))
  const lids = chapters.map(c => c._localId).filter(Boolean)
  assert.equal(new Set(lids).size, lids.length, label + ':_localId 重复 ' + lids.join(','))
}

function plainEnding(e) { return e ? JSON.parse(JSON.stringify(e)) : e }

test('SC-THOUGHT-CHAPTER 编辑结局章后新增章节:键唯一、新章无结局、保存→重开条件仍挂原章', () => {
  // ---- 打开已有主题 ----
  const page = makePage()
  page.data.editingTopicId = 501
  page.applyEditingTopic(ORIGINAL)
  const opened = page.data.formData.chapters
  assert.deepEqual(opened.map(c => c.id), [70, 80, 90])
  assert.equal(opened[1].ending.kind, 'thought')
  assert.equal(opened[1].ending.thoughtKey, 'w')
  assert.equal(opened[2].ending.fallback, true)

  // ---- 编辑:打开结局章「窗外」的设置并完成(chapterForm 此刻带着伏笔结局) ----
  page.showEditChapter({ currentTarget: { dataset: { index: 1 } } })
  assert.equal(page.data.chapterForm.ending.thoughtKey, 'w')
  page.confrimChapter()

  // ---- 新增两章:直接落章 + 弹窗落章 ----
  page._createChapterDirect()
  page.closeStoryEditor()
  page.showAddChapter()
  page.confrimChapter()
  page.closeStoryEditor()

  const edited = page.data.formData.chapters
  assert.equal(edited.length, 5)
  assertKeysUnique(edited, '编辑后')
  for (const added of edited.slice(3)) {
    assert.equal(added.id, undefined, '新章不能沿用上一章的服务端 id')
    assert.equal(added.ending, null, '普通新章不能继承上一章的结局')
    assert.equal(added.opening, false)
  }
  assert.equal(edited[1].ending.thoughtKey, 'w', '编辑完成后结局章自己的条件仍在')

  // ---- 保存:组装提交载荷 ----
  const payload = page._buildSubmitChapters(1)
  assert.deepEqual(payload.map(c => c.id), [70, 80, 90, undefined, undefined])
  assert.deepEqual(payload[1].ending, { when: [{ op: 'HAS_TAG', value: 'thought.w.done' }] })
  assert.deepEqual(payload[2].ending, { fallback: true })
  assert.equal(Object.hasOwn(payload[3], 'ending'), false, '新章载荷不带结局')
  assert.equal(Object.hasOwn(payload[4], 'ending'), false, '新章载荷不带结局')
  assert.deepEqual(payload[0].blocks.find(b => b.key === 'b1').when, { op: 'HAS_TAG', value: 'thought.w.done' })
  assert.equal(payload[0].blocks.find(b => b.key === 'b2').thoughtKey, 'w')

  // ---- 重开:服务端回显后重新回填 ----
  const reopened = makePage()
  reopened.data.editingTopicId = 501
  reopened.applyEditingTopic(echoFromServer(payload))
  const back = reopened.data.formData.chapters
  assert.equal(back.length, 5, '不能把结局再转出一份「老结局章」')
  assertKeysUnique(back, '重开后')
  assert.deepEqual(back.map(c => c.id), [70, 80, 90, 201, 202])
  assert.deepEqual(plainEnding(back[1].ending),
    { fallback: false, kind: 'thought', thoughtKey: 'w', tag: '', nodeId: null })
  assert.equal(back[2].ending.fallback, true)
  assert.equal(back[3].ending, null)
  assert.equal(back[4].ending, null)
  assert.deepEqual(back[0].blocks.find(b => b.key === 'b1').when, { op: 'HAS_TAG', value: 'thought.w.done' })
  assert.equal(back[0].blocks.find(b => b.key === 'b2').thoughtKey, 'w')
  assert.equal(reopened.data.thoughtNameOf.w, '那扇窗', '伏笔名字表随重开回填')

  // ---- 重开后再加一章:键依然不撞,也不继承结局 ----
  reopened._createChapterDirect()
  reopened.closeStoryEditor()
  const again = reopened.data.formData.chapters
  assertKeysUnique(again, '重开再加后')
  assert.equal(again[5].ending, null)
  assert.equal(again[5].id, undefined)

  // 二次保存:条件仍是同一条
  const second = reopened._buildSubmitChapters(1)
  assert.deepEqual(second[1].ending, { when: [{ op: 'HAS_TAG', value: 'thought.w.done' }] })
  assert.deepEqual(second[2].ending, { fallback: true })
  assert.equal(Object.hasOwn(second[5], 'ending'), false)
})
