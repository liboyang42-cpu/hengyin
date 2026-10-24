// L21 · BL-186 章节阶段结算:本章(未通关)结算半屏接 GET /api/play/journey/stage-end。
// 取数与降级照 /journey/ending:只给故事流真玩家;没配阶段 / 拿不到 → 整块不渲染、不弹错;
// 迟到回包(半屏已关 / 换局)不写。
const { beforeEach, test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const PLAY_PAGE = path.join(ROOT, 'pages/play/index.js')

let pageConfig
let requests

global.getApp = () => ({
  getUserID: () => '9',
  globalData: { user_id: 9, features: {} },
  isDevEnv: () => false,
  sendRequest: (request) => { requests.push(request) },
})
global.wx = {
  getStorageSync: () => '', setStorageSync() {},
  getWindowInfo: () => ({ statusBarHeight: 20 }),
  getSystemInfoSync: () => ({ statusBarHeight: 20 }),
  showLoading() {}, hideLoading() {}, showToast() {}, vibrateShort() {},
}
global.Page = (config) => { pageConfig = config }

beforeEach(() => { pageConfig = null; requests = [] })

function loadPage() {
  delete require.cache[require.resolve(PLAY_PAGE)]
  require(PLAY_PAGE)
  const page = Object.assign({}, pageConfig, { data: JSON.parse(JSON.stringify(pageConfig.data)) })
  page.setData = (patch, callback) => {
    Object.entries(patch).forEach(([key, value]) => {
      const parts = key.split('.')
      let cursor = page.data
      parts.slice(0, -1).forEach((part) => { cursor = cursor[part] = cursor[part] || {} })
      cursor[parts[parts.length - 1]] = value
    })
    if (callback) callback()
  }
  page._isPlayAccountCurrent = () => true
  Object.assign(page.data, {
    topicId: '31', activityId: 5, mode: 1, allDone: false, isPreview: false, isMock: false, showFinish: true,
    chapter: { id: 77, name: '第一章', blocks: [{ type: 'text', content: '雨停了' }] },
  })
  return page
}

const flush = () => new Promise((resolve) => setImmediate(resolve))

test('BL-186 本章完成:按 topicId/activityId/chapterId 静默读 stage-end,把作者写的变化与下一步放进半屏', async () => {
  const page = loadPage()
  page.loadStageEnd()
  assert.equal(requests.length, 1)
  const request = requests[0]
  assert.equal(request.url, '/api/play/journey/stage-end')
  assert.equal(request.method, 'GET')
  assert.deepEqual(request.data, { topicId: '31', activityId: 5, chapterId: 77 })
  assert.equal(request.silentError, true, '没配阶段是常态，不能弹「本章节未配置阶段结算」')
  request.success({ code: 200, data: { changed: ['你在巷口留下了名字', '', 3], open: ' 去码头找船夫 ', hp: { value: 3 } } })
  await flush()
  assert.deepEqual(page.data.stageEnd, { changed: ['你在巷口留下了名字'], open: '去码头找船夫' })

  const wxml = fs.readFileSync(path.join(ROOT, 'pages/play/index.wxml'), 'utf8')
  assert.match(wxml, /wx:if="\{\{stageEnd && !allDone\}\}"/)
  assert.match(wxml, /wx:for="\{\{stageEnd\.changed\}\}"/)
  assert.match(wxml, /\{\{stageEnd\.open\}\}/)
})

test('BL-186 降级:没配阶段 / 网络失败 / 空内容 → 不渲染、不挡路', async () => {
  for (const reply of [
    { code: 500, msg: '本章节未配置阶段结算' },
    { code: 'fail', netFail: true },
    { code: 200, data: { changed: [], open: '' } },
    { code: 200, data: null },
  ]) {
    requests = []
    const page = loadPage()
    page.loadStageEnd()
    requests[0].success(reply)
    await flush()
    assert.equal(page.data.stageEnd, null, JSON.stringify(reply))
  }
})

test('BL-186 只给故事流真玩家的未通关章节:通关 / 试玩 / 预览 / 探店日 / 经典路线 / 结局章都不请求', () => {
  const cases = [
    (p) => { p.data.allDone = true },
    (p) => { p.data.isPreview = true },
    (p) => { p.data.isMock = true },
    (p) => { p.data.mode = 2 },
    (p) => { p.data.chapter = { id: 77, name: '经典' } },
    (p) => { p.data.chapter = Object.assign({}, p.data.chapter, { ending: true }) },
    (p) => { p.data.chapter = Object.assign({}, p.data.chapter, { id: '' }) },
    (p) => { p._isPlayAccountCurrent = () => false },
  ]
  cases.forEach((mutate, index) => {
    requests = []
    const page = loadPage()
    mutate(page)
    page.loadStageEnd()
    assert.equal(requests.length, 0, 'case ' + index)
  })
})

test('BL-186 迟到回包:半屏已关或又开了一次,旧回包不写', async () => {
  const page = loadPage()
  page.loadStageEnd()
  const first = requests[0]
  page._hideFinish()
  first.success({ code: 200, data: { changed: ['旧'], open: '' } })
  await flush()
  assert.equal(page.data.stageEnd, null)

  page.data.showFinish = true
  page.loadStageEnd()
  const second = requests[1]
  page.loadStageEnd()
  second.success({ code: 200, data: { changed: ['被取代'], open: '' } })
  await flush()
  assert.equal(page.data.stageEnd, null)
  requests[2].success({ code: 200, data: { changed: ['最新'], open: '' } })
  await flush()
  assert.deepEqual(page.data.stageEnd, { changed: ['最新'], open: '' })
})

test('BL-186 openFinish 接上 loadStageEnd(与里程碑并列读,不阻塞完成页)', () => {
  const src = fs.readFileSync(PLAY_PAGE, 'utf8')
  const openFinish = src.slice(src.indexOf('  openFinish() {'), src.indexOf('  closeFinish() {'))
  assert.match(openFinish, /this\.loadMilestone\(\);\s*this\.loadStageEnd\(\);/)
})
