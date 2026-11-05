// 2026-11-05 owner: restore one optional theme track; chapter narration stays separate.
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.join(__dirname, '../..')
const SOURCE = fs.readFileSync(path.join(ROOT, 'pages/publish/fabu/index.js'), 'utf8')
// 判据只看代码:行首注释里保留的历史说明(「原来写的是主题级 …」)不算读写残留。
const CODE = SOURCE.replace(/^\s*\/\/.*$/gm, '')
const WXML = fs.readFileSync(path.join(ROOT, 'pages/publish/fabu/index.wxml'), 'utf8')

const PAGE = '../../pages/publish/fabu/index.js'
let pageConfig
let chosenDocuments

global.getApp = () => ({
  globalData: { statusBarHeight: 20, navBarHeight: 44 },
  getUserID: () => 42,
  sendRequest: () => {},
  chooseImage: () => {},
  chooseDocument: (cb) => { if (chosenDocuments) cb(chosenDocuments) },
  tips: () => {},
  getRequestErrorMessage: (err, fallback) => (err && err.errMsg) || fallback,
})

const createdAudios = []
function makeAudio() {
  const audio = {
    src: '', destroyed: 0, handlers: {},
    play() {}, pause() {}, stop() {},
    destroy() { this.destroyed += 1 },
    onPlay(fn) { this.handlers.play = fn },
    onPause(fn) { this.handlers.pause = fn },
    onStop(fn) { this.handlers.stop = fn },
    onEnded(fn) { this.handlers.ended = fn },
    onError(fn) { this.handlers.error = fn },
    onCanplay(fn) { this.handlers.canplay = fn },
  }
  createdAudios.push(audio)
  return audio
}

global.wx = {
  getStorageSync: () => undefined,
  setStorageSync: () => {},
  removeStorageSync: () => {},
  getSystemInfoSync: () => ({ windowWidth: 375, statusBarHeight: 20 }),
  showToast: () => {},
  hideLoading: () => {},
  showLoading: () => {},
  createInnerAudioContext: makeAudio,
  createSelectorQuery: () => ({ select: () => ({ boundingClientRect: () => ({ exec() {} }) }), exec() {} }),
  pageScrollTo: () => {},
  nextTick: (cb) => cb(),
}

global.Page = (config) => { pageConfig = config }

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
    Object.keys(patch).forEach((key) => setAtPath(page.data, key, patch[key]))
    if (callback) callback()
  }
  return page
}

require(PAGE)

test('主题配乐在编辑、复制与提交路径保留', () => {
  assert.match(CODE, /'formData\.audioUrl': topic\.audioUrl/)
  assert.match(CODE, /'formData\.audioUrl': sourceData\.audioUrl/)
  assert.match(CODE, /audioUrl: this\.data\.formData\.audioUrl/)
  const sheet = fs.readFileSync(path.join(ROOT, 'pages/publish/fabu/topic-detail-sheet.wxml'), 'utf8')
  assert.match(sheet, /主题配乐/)
  assert.match(sheet, /bindtap="uploadThemeAudio"/)
})

test('主题配乐上传、清空不改章节音频', () => {
  const page = makePage()
  page.data.chapterForm.audioUrl = 'chapter.mp3'
  chosenDocuments = [{ url: 'https://cdn/theme.mp3' }]
  page.uploadThemeAudio()
  chosenDocuments = null
  assert.equal(page.data.formData.audioUrl, 'https://cdn/theme.mp3')
  page.clearThemeAudio()
  assert.equal(page.data.formData.audioUrl, '')
  assert.equal(page.data.chapterForm.audioUrl, 'chapter.mp3')
})

test('章节音频链路保留:WXML 三个入口 + JS 方法齐备', () => {
  assert.match(WXML, /bindtap="uploadChapterAudio"/, '章节音频上传入口丢了')
  assert.match(WXML, /bindtap="onPreviewChapterAudio"/, '章节音频预听入口丢了')
  assert.match(WXML, /bindtap="clearChapterAudio"/, '章节音频清空入口丢了')
  assert.match(WXML, /chapterForm\.audioUrl/, '章节音频渲染丢了')
  for (const method of ['uploadChapterAudio', 'autoDetectAudioDuration', 'clearChapterAudio', 'onPreviewChapterAudio']) {
    assert.equal(typeof pageConfig[method], 'function', `章节音频方法 ${method} 丢了`)
  }
})

test('章节音频行为不受清理影响:上传写 chapterForm,清空只清 chapterForm', () => {
  const page = makePage()
  page.data.popChapter = true // 上传入口只在章节抽屉里;抽屉已关时的迟到回调会被丢弃
  chosenDocuments = [{ url: 'https://cdn/chapter.mp3', filename: 'chapter.mp3' }]
  try {
    page.uploadChapterAudio()
  } finally {
    chosenDocuments = null
  }
  assert.equal(page.data.chapterForm.audioUrl, 'https://cdn/chapter.mp3')
  assert.equal(page.data.chapterForm.audioFileName, 'chapter.mp3')
  assert.equal(page.data.formData.audioUrl, '', '章节音频不改主题配乐')

  page.clearChapterAudio()
  assert.equal(page.data.chapterForm.audioUrl, '')
  assert.equal(page.data.chapterForm.audioDuration, 0)
  assert.equal(page.data.chapterForm.audioFileName, '')
})
