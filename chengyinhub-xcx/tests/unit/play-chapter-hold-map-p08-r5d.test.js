// P-08 第五轮 D(业主 2026-11-03):等待放行屏最底下加「返回地图」(既有底部按钮条 cy-footer-bar)。
// 点了收起等待屏、打开地图;之后队长放行,不管玩家在地图上还是回到了这一屏,都照常走 cy-chapter-flip 进这一章,
// 不卡住、不漏掉;地图上再点回这一章(「回到故事」)回到等待放行屏。
const assert = require('node:assert/strict')
const { test } = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const Module = require('node:module')

const ROOT = path.join(__dirname, '../..')
const PLAY = path.join(ROOT, 'pages/play/index.js')
const WXML = fs.readFileSync(path.join(ROOT, 'pages/play/index.wxml'), 'utf8')
const WXSS = fs.readFileSync(path.join(ROOT, 'pages/play/index.wxss'), 'utf8')
const JSON_CFG = JSON.parse(fs.readFileSync(path.join(ROOT, 'pages/play/index.json'), 'utf8'))

let pageConfig
global.getApp = () => ({ globalData: {}, getUserID: () => 1, sendRequest: () => {} })
global.Page = (c) => { pageConfig = c }
global.wx = {
  getSystemInfoSync: () => ({ windowWidth: 375, statusBarHeight: 20 }),
  getMenuButtonBoundingClientRect: () => ({ top: 20, height: 32 }),
  getStorageSync: () => null, setStorageSync() {}, removeStorageSync() {}, showToast() {}, vibrateShort() {},
  createInnerAudioContext: () => ({ play() {}, pause() {}, stop() {}, destroy() {}, onPlay() {}, onPause() {}, onStop() {}, onEnded() {}, onError() {}, onWaiting() {}, onCanplay() {} }),
}

function page() {
  pageConfig = null
  const m = new Module(PLAY, module)
  m.filename = PLAY
  m.paths = Module._nodeModulePaths(path.dirname(PLAY))
  m._compile(fs.readFileSync(PLAY, 'utf8'), PLAY)
  const p = Object.assign({}, pageConfig)
  p.data = JSON.parse(JSON.stringify(pageConfig.data))
  p.setData = function (patch, cb) {
    Object.keys(patch).forEach((k) => {
      const parts = k.split('.')
      let o = this.data
      while (parts.length > 1) { const h = parts.shift(); o[h] = o[h] || {}; o = o[h] }
      o[parts[0]] = patch[k]
    })
    if (cb) cb()
  }
  return p
}

// 视图条件照 wxml 原样求值:等待屏 / 翻转门 / 地图三层谁在场
const holdShown = (d) => !!(d.chapterFull && d.chapterGate.show && d.chapterGate.held && !d.chapterGate.away)
const flipShown = (d) => !!(d.chapterFull && d.chapterGate.show && !d.chapterGate.held)
const mapShown = (d) => !!(d.chapterFull && d.storyMap)

// 带领场:队长在第一章(11),玩家停在第二章(12)门前
function heldPage({ coords = true } = {}) {
  const p = page()
  const timers = []
  p._timers = timers
  Object.assign(p.data, {
    mode: 1, topicId: 77, activityId: 5, chapterFull: true,
    chapter: { id: 12, name: '钟楼' },
    chapterCards: [{ id: 11 }, { id: 12 }],
    lead: { exists: true, status: 2, currentChapterId: 11 },
    fmNodes: coords ? [{ nodeId: 1, lat: 31.2, lng: 121.4 }] : [{ nodeId: 1 }],
    nextNode: coords ? { nodeId: 1, lat: 31.2, lng: 121.4, name: '钟楼' } : null,
    gpsOk: true,
    chapterGate: { show: true, held: true, endInfo: null },
  })
  p._pendingChapterGateKey = '77:12'
  p._seenChapterGates = new Set()
  p.closeAlbum = () => {}; p.stopAudio = () => {}; p._stopStoryDust = () => {}
  p.requestPlayLocation = () => {}
  p.entered = 0
  p.continueChapterGate = function () { this.entered++; this.setData({ 'chapterGate.show': false }) }
  return p
}
const release = (p) => { p.data.lead = { exists: true, status: 2, currentChapterId: 12 } }

function withTimers(fn) {
  const timers = []
  const realSet = global.setTimeout, realClear = global.clearTimeout
  global.setTimeout = (f, ms) => { timers.push({ f, ms }); return timers.length }
  global.clearTimeout = () => {}
  try { return fn(timers) } finally { global.setTimeout = realSet; global.clearTimeout = realClear }
}

test('视图:「返回地图」在等待屏最底下,用既有 cy-footer-bar(次按钮样式),点了走 leaveChapterHold', () => {
  const b = WXML.match(/<view class="chhold[^"]*"[^>]*>[\s\S]*?\n<\/view>/)[0]
  const last = b.trim().split('\n').slice(-2)[0]
  assert.match(last, /^\s*<cy-footer-bar primary="返回地图" primary-variant="secondary" bind:primarytap="leaveChapterHold" \/>$/, '是这一屏最后一个元素(最底下)')
  assert.equal(JSON_CFG.usingComponents['cy-footer-bar'], '/components/cy/footer-bar/index')
  // 底部条承托底接本屏底色,不多出黑带
  assert.match(WXSS, /\.chhold\{ --cy-color-bg-page:var\(--cy-color-bg-elevated\); \}/)
  // 地图的「回到故事」仍是同一个入口
  assert.match(WXML, /class="story-map__back"[^>]*bindtap="closeStoryMap"/)
})

test('点「返回地图」:等待屏收起、地图打开;仍挂着这一章的门', () => {
  const p = heldPage()
  p.leaveChapterHold()
  assert.equal(holdShown(p.data), false)
  assert.equal(mapShown(p.data), true)
  assert.equal(flipShown(p.data), false, '还没放行,不放翻转门')
  assert.equal(p.data.chapterGate.show, true)
  assert.equal(p._pendingChapterGateKey, '77:12')
})

test('在地图上被放行:收起地图,照常放 cy-chapter-flip,播完(或兜底计时)进这一章', () => {
  withTimers((timers) => {
    const p = heldPage()
    p.leaveChapterHold()
    release(p)
    p._syncChapterHold()
    assert.equal(mapShown(p.data), false)
    assert.equal(flipShown(p.data), true)
    assert.equal(holdShown(p.data), false)
    assert.equal(timers.at(-1).ms, 6600)
    timers.at(-1).f()
    assert.equal(p.entered, 1)
  })
})

test('地图上再点回这一章(「回到故事」),仍被挡着 → 回到等待放行屏;之后放行照常翻转门', () => {
  withTimers((timers) => {
    const p = heldPage()
    p.leaveChapterHold()
    p.closeStoryMap()
    assert.equal(mapShown(p.data), false)
    assert.equal(holdShown(p.data), true)
    assert.equal(flipShown(p.data), false)
    release(p)
    p._syncChapterHold()
    assert.equal(flipShown(p.data), true)
    timers.at(-1).f()
    assert.equal(p.entered, 1)
  })
})

test('地图上放行了但轮询还没到,玩家先点回来 → 不再停在等待屏,直接翻转门进章', () => {
  withTimers((timers) => {
    const p = heldPage()
    p.leaveChapterHold()
    release(p)
    p.closeStoryMap()
    assert.equal(holdShown(p.data), false)
    assert.equal(flipShown(p.data), true)
    timers.at(-1).f()
    assert.equal(p.entered, 1)
  })
})

test('没有带坐标的地点:「返回地图」退回关故事流;放行时重开这一章,开场门走翻转门而不是等待屏', () => {
  const p = heldPage({ coords: false })
  p.leaveChapterHold()
  assert.equal(p.data.chapterFull, false)
  let reopened = 0
  p.openChapterFull = function () { reopened++ }
  p._syncChapterHold()
  assert.equal(reopened, 0, '仍被挡着不重开')
  release(p)
  p._syncChapterHold()
  assert.equal(reopened, 1)
  p._syncChapterHold()
  assert.equal(reopened, 1, '只重开一次')
})

test('普通地图(不是从等待屏来的):「回到故事」照旧只收起地图', () => {
  const p = heldPage()
  p.data.chapterGate = { show: false }
  p.data.storyMap = true
  let back = 0
  p.backToChapterHold = () => { back++ }
  p.closeStoryMap()
  assert.equal(p.data.storyMap, false)
  assert.equal(back, 0)
})

test('没被挡住时「返回地图」不动作', () => {
  const p = heldPage()
  p.data.chapterGate = { show: true, held: false }
  p.leaveChapterHold()
  assert.equal(p.data.storyMap, false)
  assert.equal(p.data.chapterGate.away, undefined)
})
