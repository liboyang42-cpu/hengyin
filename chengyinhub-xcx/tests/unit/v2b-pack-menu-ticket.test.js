'use strict'
// 动效 v2 · lane v2-b:时刻 2 卡包 + 时刻 7 ⋯ 菜单与入场票根的合同。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const read = (p) => fs.readFileSync(path.resolve(__dirname, '../..', p), 'utf8')
const js = read('pages/play/index.js')
const wxml = read('pages/play/index.wxml')
const wxss = read('pages/play/index.wxss')
const tWxml = read('pages/play/components/scene-qr-ticket/index.wxml')
const tWxss = read('pages/play/components/scene-qr-ticket/index.wxss')

test('时刻 2:开包走 this.animate(抖 → 下沉)+ 彩纸 36 片,逐张弹出间隔 90ms', () => {
  const open = js.slice(js.indexOf('  openPack() {'), js.indexOf('  _fireConfetti() {'))
  assert.match(open, /this\.animate\('\.fx-pack'/)
  assert.match(open, /PACK_SHAKE/)
  assert.match(open, /PACK_DROP/)
  assert.match(js, /const PACK_SHAKE = 260;/)
  assert.match(js, /const PACK_DROP = 420;/)
  assert.match(js, /const DEAL_STAGGER = 90;/)
  assert.match(js, /for \(let i = 0; i < 36; i\+\+\) pieces\.push/)
  assert.match(js, /this\.animate\('\.' \+ pc\.id/)
})

test('时刻 2:待机浮动与流光是 this.animate 循环,循环有同步递归保护;清理时 clearAnimation', () => {
  assert.match(js, /_animLoop\('\.fx-pack',/)
  assert.match(js, /_animLoop\('\.fx-pack__sheen',/)
  assert.match(js, /_animLoop\('\.fx-tile__sheen',/)
  assert.match(js, /Date\.now\(\) - t0 < dur \* 0\.5\) return/)
  assert.match(js, /this\.clearAnimation\('\.fx-pack'/)
  assert.match(js, /_clearPackTimers\(\) \{[\s\S]*_packLoopOn = false[\s\S]*_tileSheenOn = false/)
})

test('时刻 2:减弱动态直接落定,不起循环', () => {
  assert.match(js, /if \(this\.data\.reducedMotion\) \{ this\.setData\(\{ packOpen: true, headIn: true \}\)/)
  assert.match(js, /_packLoopStart\(\) \{\s*if \(this\.data\.reducedMotion/)
  assert.match(js, /_tileSheenStart\(\) \{\s*if \(this\.data\.reducedMotion/)
})

test('时刻 2:发牌用弹簧 cubic-bezier(.34,1.56,.64,1),旧撕包 / 爆炸 @keyframes 已删', () => {
  assert.match(wxss, /\.fx-tile\.is-flying\{[^}]*cubic-bezier\(\.34,1\.56,\.64,1\)/)
  assert.match(wxss, /\.fx-tile\.is-dealt\{[^}]*cubic-bezier\(\.34,1\.56,\.64,1\)/)
  for (const k of ['fxShake', 'fxRip', 'fxMouth', 'fxFlapL', 'fxFlapR', 'fxSink', 'fxBloom', 'fxRay', 'fxShard']) {
    assert.doesNotMatch(wxss, new RegExp('@keyframes ' + k + '\\b'))
  }
  assert.doesNotMatch(js, /onPackTouch|_applyTear|_fireBlast|PACK_CHARGE|PACK_TEAR/)
})

test('时刻 2:「收进卡册」在发牌收尾最后出现', () => {
  assert.match(wxml, /class="fx-collect[^"]*"[^>]*>收进卡册<\/view>/)
  assert.match(js, /packOpen: true, collectShow: true/)
})

test('时刻 7:⋯ 菜单从右上角缩放长出(transform-origin 92% 0),行错峰 30ms,用 transition 不用 keyframes', () => {
  assert.match(wxss, /\.tools--corner\{[^}]*transform-origin:92% 0;[^}]*transform:scale\(\.2\)/)
  assert.match(wxss, /\.tools--corner\.is-in \.drow:nth-child\(1\)\{ transition-delay:60ms; \}/)
  assert.match(wxss, /\.tools--corner\.is-in \.drow:nth-child\(2\)\{ transition-delay:90ms; \}/)
  assert.match(wxss, /\.tools--corner\.is-in \.drow:nth-child\(3\)\{ transition-delay:120ms; \}/)
  assert.match(js, /_toolsMount\(\) \{/)
  assert.match(wxss, /\.play--reduced-motion \.tools--corner,[^{]*\{ transition:none; \}/)
})

test('时刻 7:前五行次序 入场码 / 切换章节 / 旅程手记 / 同行者 / 声音与动效,紧急求助红色且在最后', () => {
  const tools = wxml.slice(wxml.indexOf('class="tools tools--corner'), wxml.indexOf('</scroll-view>', wxml.indexOf('class="tools tools--corner')))
  const labels = [...tools.matchAll(/aria-label="(入场码|切换章节|旅程手记|同行者|声音与动效|紧急求助)"/g)].map((m) => m[1])
  assert.deepEqual(labels.filter((l, i) => labels.indexOf(l) === i), ['入场码', '切换章节', '旅程手记', '同行者', '声音与动效', '紧急求助'])
  assert.equal(labels[labels.length - 1], '紧急求助')
  assert.match(tools, /class="drow drow--sos"/)
  assert.match(wxss, /\.drow--sos \.dt\{ color:#ff6b5e; \}/)
  assert.doesNotMatch(tools, /\.dsw|大二维码/)
})

test('时刻 7:入场码半屏是票根(缺口 / 齿孔 / 196px 码 / 使用人 / 完成),玩页主面没有大二维码', () => {
  assert.match(tWxml, /stub__notch--l/)
  assert.match(tWxml, /stub__notch--r/)
  assert.match(tWxml, /stub__perf/)
  assert.match(tWxml, /size="\{\{360\}\}"/)
  assert.match(tWxml, /使用人/)
  assert.match(tWxml, /class="stub__done"[^>]*>完成<\/view>/)
  assert.match(tWxss, /\.stub__perf \{ border-top: 4rpx dashed/)
  assert.match(tWxss, /\.stub \.voucher__qr \{[^}]*width: 392rpx; height: 392rpx/)
  assert.doesNotMatch(wxml, /class="[^"]*pscan|<cy-btn[^>]*bindtap="openEntryQr"/)
})
