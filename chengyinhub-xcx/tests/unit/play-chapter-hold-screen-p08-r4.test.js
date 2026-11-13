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
  // 第五轮 D:「返回地图」去了地图上(chapterGate.away)时等待屏收起
  assert.match(WXML, /<view class="chhold[^"]*" wx:if="\{\{chapterFull && chapterGate\.show && chapterGate\.held && !chapterGate\.away\}\}"/)
})

// 第五轮 D(业主):最底下加「返回地图」(cy-footer-bar),见 play-chapter-hold-map-p08-r5d.test.js;其余仍无按钮、无说明行
test('布局:「等待放行」在上,三点等待在正中,没有说明行,唯一的按钮是底部「返回地图」', () => {
  const b = holdBlock()
  assert.ok(b.indexOf('等待放行</view>') < b.indexOf('lg-wait'), '标题在三点之前(上方)')
  assert.match(b, /<view class="lg-wait chhold__wait" aria-hidden="true">(<view class="lg-wait__d"><\/view>){3}<\/view>/)
  assert.doesNotMatch(b, /<cy-btn|bindtap/)
  assert.equal((b.match(/<cy-footer-bar /g) || []).length, 1)
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
  // r2-finish:章节完成卡在场时底部是「下一站」按钮,不再叠一行「轻点跳过」
  // l-play 2026-11-10(业主批准 proto gp-chapter-start):开场屏底部是「读下去」按钮(点它方块炸散),不再是一行「轻点跳过」
  assert.match(w, /<view class="cf__foot cf__foot--start \{\{cta \? 'is-on' : ''\}\}" wx:if="\{\{mode === 'gate' && start\}\}"><view class="cf__btn" catchtap="onTap"[^>]*aria-label="读下去"/)
  assert.doesNotMatch(w, /轻点跳过/)
})

test('放行:收起等待屏、换上翻转门,翻转门等玩家点按、无计时自动进正文(D-46);仍被挡时仍挡着', () => {
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
    // D-46 业主「点了才能进」:页面兜底计时(4.2s/10.8s)已移除,放行后翻转门只等点按
    assert.equal(timers.length, 0)
    assert.equal(entered, 0)
    p.data.chapterGate = { show: true, held: true, endInfo: { number: '01' } }
    p._releaseChapterGate()
    assert.equal(timers.length, 0, 'D-46:带上一章完成面时同样不计时自动前进')
    assert.equal(entered, 0)
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
