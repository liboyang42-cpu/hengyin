'use strict'
// 2026-10-28 业主:勋章墙改珐琅徽章(参考 x.com/danywander/status/2097314310783390104)。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const path = require('path')

const dir = path.resolve(__dirname, '../../subpackageP3/pages/badge-wall/index')
const GLYPHS = require(path.join(dir, 'glyphs.js'))
const { PLATE } = require(path.join(dir, 'enamel-pin.js'))

test('每枚徽记都有自己的珐琅底板,未知徽记落 DEFAULT', () => {
  for (const key of GLYPHS.keys) assert.ok(PLATE[key], key + ' 缺底板')
  assert.ok(PLATE.DEFAULT)
})

test('列表与详情用珐琅图,星星占位不再出现', () => {
  const wxml = fs.readFileSync(path.join(dir, 'index.wxml'), 'utf8')
  assert.match(wxml, /class="bw-medal__pin" src="\{\{pins\[item\.pinId\]\}\}"/)
  assert.match(wxml, /class="bw-sh-pin" src="\{\{pins\[sheet\.pinId\]\}\}"/)
  assert.match(wxml, /<canvas type="2d" id="pinCanvas"/)
  assert.doesNotMatch(wxml, /bw-medal__rim|star-filled/)
})

test('未解锁的珐琅线稿不再压灰,上传图仍按原样压灰', () => {
  const wxss = fs.readFileSync(path.join(dir, 'index.wxss'), 'utf8')
  assert.match(wxss, /\.bw-medal\.is-locked \.bw-medal__pin \{ filter: none; \}/)
  assert.match(wxss, /\.bw-medal\.is-locked \.bw-medal__img \{ filter: grayscale\(1\)/)
})

test('颜色按类别分金属(业主:颜色你来定),每个类别与每套预设的成色都认得', () => {
  const E = require(path.join(dir, 'enamel-pin.js'))
  assert.equal(E.BADGE_PRESET, 'bycat')
  assert.deepEqual(E.TRACK_FINISH, ['silver', 'gold', 'bronze', 'rose', 'black'])
  for (let t = 0; t < 5; t++) assert.ok(E.FINISH[E.optsFor(t).finish], 'track ' + t)
  assert.equal(E.optsFor(undefined).finish, 'gold', '没有类别的落金色')
  for (const id of Object.keys(E.PRESETS)) assert.ok(E.FINISH[E.PRESETS[id].finish], id)
})

test('背面不放字', () => {
  const src = fs.readFileSync(path.join(dir, 'enamel-pin.js'), 'utf8')
  assert.doesNotMatch(src, /fillText/)
})

test('珐琅勋章点开能转:详情大图与按钮都进 badge-3d 的 pin 样式,未解锁的不转', () => {
  const wxml = fs.readFileSync(path.join(dir, 'index.wxml'), 'utf8')
  const js = fs.readFileSync(path.join(dir, 'index.js'), 'utf8')
  assert.match(wxml, /wx:if="\{\{!sheet\.iconUrl && pins\[sheet\.pinId\] && sheet\.locked\}\}" class="bw-sh-pin"[^>]*mode="aspectFit" \/>/, '未解锁的大图不可点')
  assert.match(wxml, /wx:elif="\{\{!sheet\.iconUrl && pins\[sheet\.pinId\]\}\}" class="bw-sh-pin"[^>]*bindtap="goBadge3d"/)
  assert.match(js, /if \(!sh\.iconUrl && sh\.pinId && !sh\.locked\)[\s\S]*badge-3d\/index\?style=pin&glyph=/)
  const page = fs.readFileSync(path.resolve(dir, '../../badge-3d/index.wxml'), 'utf8')
  assert.match(page, /<pin-spin wx:elif="\{\{style=='pin' && !unsupported\}\}"[^>]*reduced="\{\{reducedMotion\}\}"[^>]*bind:unsupported="onUnsupported" bind:loaded="onLoaded"/)
})

test('pin-spin 只用勋章墙验证过的接口,并尊重减弱动态效果', () => {
  const src = fs.readFileSync(path.resolve(dir, '../../../components/pin-spin/index.js'), 'utf8')
  assert.match(src, /wx\.canvasToTempFilePath\(/)
  assert.match(src, /canvas\.createImage\(\)/)
  assert.match(src, /if \(!this\.data\.reduced\) this\._spinTo\(1\)/, '减弱动态效果时进页不自动转')
  assert.match(src, /detached\(\) \{ this\._dead = true; this\._stop\(\); \}/)
})
