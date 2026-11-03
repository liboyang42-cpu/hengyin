// P-08 第四轮(业主 2026-11-03):带领场里被挡住的章不再停在章节翻转门上 ——
// 上方「等待放行」、屏幕中间集合屏同款三点等待(减弱动态静止)、这一屏没有真实动作就不放按钮;
// 队长放行后换回正常的章节翻转门,播完照常进正文。翻转门组件本身回到不认识 held 的样子。
const assert = require('node:assert/strict')
const { test } = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const Module = require('node:module')

const ROOT = path.join(__dirname, '../..')
const PLAY = path.join(ROOT, 'pages/play/index.js')
const WXML = fs.readFileSync(path.join(ROOT, 'pages/play/index.wxml'), 'utf8')
const WXSS = fs.readFileSync(path.join(ROOT, 'pages/play/index.wxss'), 'utf8')

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

function holdBlock() {
  const m = WXML.match(/<view class="chhold[^"]*"[^>]*>[\s\S]*?\n<\/view>/)
  assert.ok(m, '等待放行屏要在页面里')
  return m[0]
}

test('被挡住时不挂翻转门,挂等待放行屏;放行后才挂翻转门', () => {
  assert.match(WXML, /<cy-chapter-flip wx:if="\{\{chapterFull && chapterGate\.show && !chapterGate\.held\}\}" mode="gate"/)
  assert.match(WXML, /<view class="chhold[^"]*" wx:if="\{\{chapterFull && chapterGate\.show && chapterGate\.held\}\}"/)
})

test('布局:「等待放行」在上,三点等待在正中,没有按钮也没有说明行', () => {
  const b = holdBlock()
  assert.ok(b.indexOf('等待放行</view>') < b.indexOf('lg-wait'), '标题在三点之前(上方)')
  assert.match(b, /<view class="lg-wait chhold__wait" aria-hidden="true">(<view class="lg-wait__d"><\/view>){3}<\/view>/)
  assert.doesNotMatch(b, /<cy-btn|bindtap/)
  assert.equal((b.match(/chhold__(t|wait)\b/g) || []).length, 2, '只有标题与三点两样')
  assert.equal((b.match(/<view /g) || []).length, 6, '外层 + 标题 + 三点容器 + 三颗点')
  assert.match(WXSS, /\.chhold__wait\{ position:absolute; top:50%; left:50%; transform:translate\(-50%,-50%\)/)
})

test('减弱动态:三点静止', () => {
  assert.match(holdBlock(), /reducedMotion \? 'chhold--still' : ''/)
  assert.match(WXSS, /\.chhold--still \.lg-wait__d\{ animation:none; opacity:\.6; \}/)
})

test('翻转门组件不再认识 held,底部仍是「轻点跳过」', () => {
  const dir = path.join(ROOT, 'pages/play/components/chapter-flip')
  assert.doesNotMatch(fs.readFileSync(path.join(dir, 'index.js'), 'utf8'), /held/)
  const w = fs.readFileSync(path.join(dir, 'index.wxml'), 'utf8')
  assert.doesNotMatch(w, /held|等队长放行/)
  assert.match(w, /<view class="cf__hint" wx:if="\{\{mode === 'gate'\}\}">轻点跳过<\/view>/)
})

test('放行:收起等待屏、换上翻转门,门不发 continue 时按原兜底计时进正文;仍被挡时兜底不进', () => {
  const p = page()
  const timers = []
  const realSet = global.setTimeout, realClear = global.clearTimeout
  global.setTimeout = (fn, ms) => { timers.push({ fn, ms }); return timers.length }
  global.clearTimeout = () => {}
  try {
    let entered = 0
    p.continueChapterGate = function () { entered++ }
    p.data.chapterFull = true
    p._pendingChapterGateKey = 'k'
    p.data.chapterGate = { show: true, held: true, endInfo: null }
    p._releaseChapterGate()
    assert.equal(p.data.chapterGate.held, false)
    assert.equal(timers.at(-1).ms, 4200)
    timers.at(-1).fn()
    assert.equal(entered, 1)
    p.data.chapterGate = { show: true, held: true, endInfo: { number: '01' } }
    p._releaseChapterGate()
    assert.equal(timers.at(-1).ms, 7800, '带上一章完成面时按长计时兜底')
  } finally { global.setTimeout = realSet; global.clearTimeout = realClear }

  const q = page()
  q.data.chapter = { id: 12 }
  q.data.chapterGate = { show: true, held: true }
  q._leadHolds = () => true
  let shown = 0
  q._withLookTransition = () => { shown++ }
  q.continueChapterGate()
  assert.equal(shown, 0)
  assert.equal(q.data.chapterGate.show, true)
  assert.equal(q.data.chapterGate.held, true)
})
