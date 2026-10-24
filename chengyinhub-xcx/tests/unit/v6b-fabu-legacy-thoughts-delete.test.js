// V6b 验收（SC-THOUGHT-AUTHOR / F-R2S-14）：旧主题存了多个伏笔时，作者能逐条删除；
// 被正文伏笔块引用的那条不能删（给出原因）；删到一条后保存，再打开时步数与文字原样保留。
// pro-editor-fabu-flow.test.js 已证「多条不能保存」和「wxml 每行有删除出口」，这里补删除行为本身与保存回读。
// 负控：把 removeThought 的引用检查去掉，第一条用例必红；把 list.splice 去掉，第二条必红。
const { test, beforeEach } = require('node:test')
const assert = require('node:assert/strict')

const PAGE = '../../pages/publish/fabu/index.js'
let pageConfig
let storage

global.getApp = () => ({
  globalData: { statusBarHeight: 20, navBarHeight: 44 },
  getUserID: () => 42, getNickname: () => '测试创作者', getAvatar: () => '',
  sendRequest: () => {}, chooseImage: () => {}, tips: () => {},
})
global.wx = {
  getStorageSync: key => storage[key], setStorageSync: (key, value) => { storage[key] = value },
  removeStorageSync: key => { delete storage[key] }, getSystemInfoSync: () => ({ windowWidth: 375 }),
  showToast: () => {}, hideLoading: () => {}, showModal: () => {}, chooseLocation: () => {},
  createMapContext: () => ({ getCenterLocation: () => {} }), pageScrollTo: () => {}, nextTick: cb => cb(),
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
  page.updateAllStatistics = function () {}
  return page
}

beforeEach(() => {
  storage = {}
  pageConfig = null
  delete require.cache[require.resolve(PAGE)]
  require(PAGE)
})

const LEGACY = JSON.stringify({ schemaVersion: 1, thoughts: [
  { key: 'thought_window', name: '那扇窗', desc: '窗外有光', need: 3000 },
  { key: 'thought_bell', name: '钟声', desc: '整点的钟', need: 1500 },
] })
const remove = (page, index) => page.removeThought({ currentTarget: { dataset: { index } } })

test('V6b SC-THOUGHT-AUTHOR：正文伏笔块还引用的那条不能删，给出原因且清单不变', () => {
  const page = makePage()
  page.setData({ 'formData.journeyRules': LEGACY, 'formData.chapters': [{ _localId: 'c1', name: '第一章', nodes: [],
    blocks: [{ key: 'b1', type: 'thought', thoughtKey: 'thought_bell' }] }] })
  page.openThoughts()
  assert.equal(page.data.thoughtsEditor.list.length, 2)
  remove(page, 1)
  assert.equal(page.data.thoughtsEditor.list.length, 2, '被引用的伏笔被删掉了')
  assert.match(page.data.thoughtsEditor.error, /引用/)
})

test('V6b SC-THOUGHT-AUTHOR：旧主题多条伏笔逐条可删，删到一条后保存，重开步数与文字原样', () => {
  const page = makePage()
  page.setData({ 'formData.journeyRules': LEGACY, 'formData.chapters': [{ _localId: 'c1', name: '第一章', nodes: [],
    blocks: [{ key: 'b1', type: 'thought', thoughtKey: 'thought_window' }] }] })
  page.openThoughts()
  page.saveThoughts()
  assert.equal(page.data.formData.journeyRules, LEGACY, '两条时不能保存')
  remove(page, 1)
  assert.equal(page.data.thoughtsEditor.list.length, 1)
  assert.equal(page.data.thoughtsEditor.count, 1)
  assert.equal(page.data.thoughtsEditor.error, '')
  page.onThoughtInput({ currentTarget: { dataset: { index: 0, field: 'desc' } }, detail: { value: '窗外有一道光' } })
  page.onThoughtInput({ currentTarget: { dataset: { index: 0, field: 'need' } }, detail: { value: '2400' } })
  page.saveThoughts()
  assert.equal(page.data.thoughtsEditor.show, false, page.data.thoughtsEditor.error)
  const saved = JSON.parse(page.data.formData.journeyRules).thoughts
  assert.equal(saved.length, 1)
  assert.equal(saved[0].key, 'thought_window', '正文引用的那条键不变')
  page.openThoughts()
  assert.equal(page.data.thoughtsEditor.list.length, 1)
  assert.equal(page.data.thoughtsEditor.list[0].desc, '窗外有一道光')
  assert.equal(Number(page.data.thoughtsEditor.list[0].need), 2400)
  assert.equal(page.data.thoughtOptions.length, 1)
})
