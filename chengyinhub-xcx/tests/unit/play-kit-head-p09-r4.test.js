// P-09 第四轮(业主 2026-11-03):故事流里的猜数字,题目和返回钮、微信胶囊同一行 ——
// 在返回钮右边、胶囊左边,最多两行;不再单独一行压在圆钮下面。交过之后那颗只写「这一关结束了」的灰钮不是真动作,收起;「继续故事」留着。
const assert = require('node:assert/strict')
const { test } = require('node:test')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.join(__dirname, '../..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')
const { kitHeadChrome } = require('../../pages/play/utils/kit-head.js')
const { resolveMenuChrome } = require('../../utils/nav-safe-area.js')

test('顶栏和胶囊竖向居中对齐:88rpx 返回钮的中线 = 胶囊中线', () => {
  const win = { windowWidth: 390, statusBarHeight: 47 }
  const menu = { top: 51, bottom: 83, height: 32, left: 296, right: 383 }
  const c = kitHeadChrome(resolveMenuChrome(win, menu), win, menu)
  const btn = 88 * 390 / 750
  assert.ok(Math.abs(c.kitTop + btn / 2 - (menu.top + menu.height / 2)) <= 0.5)
  assert.ok(c.kitTop < menu.bottom, '和胶囊同一行,不在胶囊下面')
  assert.equal(c.actionRight, 390 - 296 + 12, '右边距让开胶囊')
  assert.equal(c.contentTop, resolveMenuChrome(win, menu).contentTop, '其余几何不变')
  const noMenu = kitHeadChrome({ actionTop: 28, actionRight: 12, contentTop: 76, sheetTop: 69 }, win, null)
  assert.equal(noMenu.kitTop, 28)
})

test('页面:猜数字在场时顶栏改用 kitTop 与胶囊右边距,题目写在返回钮右边,最多两行;右上圆钮收起', () => {
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /\{\{advancedPlay\.show && playKit\.kit\.type === 'estimate' \? 'padding-top:' \+ chrome\.kitTop \+ 'px;padding-right:' \+ chrome\.actionRight \+ 'px' : 'padding-top:' \+ chrome\.contentTop \+ 'px'\}\}/)
  const back = wxml.indexOf('fx-story__kitback')
  const q = wxml.indexOf('<view class="fx-story__kitq"')
  assert.ok(back > 0 && q > back && q < wxml.indexOf('fx-story__compass'), '题目紧跟在返回钮后面')
  assert.match(wxml, /<view class="fx-story__kitq" wx:if="\{\{advancedPlay\.show && playKit\.kit\.type === 'estimate' && playKit\.kit\.title\}\}">\{\{playKit\.kit\.title\}\}<\/view>/)
  assert.match(wxml, /--pk-es-reel-h:860rpx;--pk-es-q-display:none/)
  const wxss = read('pages/play/index.wxss')
  assert.match(wxss, /\.fx-story__kitq\{ flex:1; min-width:0;[^}]*-webkit-line-clamp:2; overflow:hidden;/)
  assert.match(wxss, /\.fx-story__hd\.is-kitq \.fx-story__acts\{ display:none; \}/)
  const js = read('pages/play/index.js')
  assert.match(js, /chrome: kitHeadChrome\(resolveMenuChrome\(win, menu\), win, menu\)/)
})

test('猜数字组件:内嵌时题目由页面收起;单独整屏时照旧显示', () => {
  const wxss = read('pages/play/components/playkit-estimate/index.wxss')
  assert.match(wxss, /\.es__q \{\n  display: var\(--pk-es-q-display, block\);/)
})

test('交过之后不再留「这一关结束了」灰钮;「继续故事」仍在故事流里', () => {
  const w = read('pages/play/components/playkit-estimate/index.wxml')
  assert.doesNotMatch(w, /这一关结束了/)
  assert.match(w, /<view class="g-btn pe pe--up" wx:if="\{\{!locked\}\}"[^>]*bindtap="onSubmit"[\s\S]*?>就这个数<\/view>/)
  assert.match(read('pages/play/index.wxml'), /bindtap="continueStoryGame"[\s\S]{0,120}>继续故事<\/view>/)
})
