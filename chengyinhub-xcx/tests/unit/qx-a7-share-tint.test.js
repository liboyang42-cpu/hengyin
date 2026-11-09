'use strict'
// qx-a7 V1-P36:通用分享页的底色取自内容封面(Spotify 同款),导出图与预览一致。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')
const { tintFromPixels, L_MIN, L_MAX } = require('../../utils/share-tint.js')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')

function px(list) {
  const out = []
  list.forEach(([r, g, b, a]) => out.push(r, g, b, a === undefined ? 255 : a))
  return out
}
function lightness(hex) {
  const n = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
  return (Math.max(...n) + Math.min(...n)) / 2
}

test('取封面主色:鲜艳像素优先,结果压到白字可读的暗度', () => {
  // 大片灰 + 一块青:主色应偏青,而不是灰
  const data = px([...Array(20).fill([120, 120, 120]), ...Array(6).fill([20, 160, 170])])
  const tint = tintFromPixels(data)
  assert.match(tint, /^#[0-9A-F]{6}$/)
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(tint.slice(i, i + 2), 16))
  assert.ok(g > r && b > r, '主色偏青: ' + tint)
  const l = lightness(tint)
  assert.ok(l >= L_MIN - 0.01 && l <= L_MAX + 0.01, '亮度在可读范围: ' + l)
})

test('亮封面也压暗,纯白不出白底', () => {
  const l = lightness(tintFromPixels(px(Array(10).fill([250, 250, 250]))))
  assert.ok(l <= L_MAX + 0.01)
})

test('取不到像素时返回空,调用方保留原色块', () => {
  assert.equal(tintFromPixels(null), '')
  assert.equal(tintFromPixels([]), '')
  assert.equal(tintFromPixels(px([[200, 10, 10, 0], [10, 200, 10, 20]])), '')
})

test('分享层:第一个色块用封面主色并默认选中;用户已换色不覆盖;失败不改色块', () => {
  const js = read('components/cy/share-card/index.js')
  assert.match(js, /require\('..\/..\/..\/utils\/share-tint.js'\)/)
  assert.match(js, /this\._loadTint\(\);/)
  assert.match(js, /if \(open !== this\._open \|\| !this\.data\.show\) return;/)
  assert.match(js, /this\.setData\(\{ colors, bg: keep \? this\.data\.bg : tint \}\);/)
  assert.match(js, /catch \(e\) \{ tint = ''; \}/)
})

test('分享层:导出图标题与眉题按宽度截断加省略号,不再按字数硬切', () => {
  const js = read('components/cy/share-card/index.js')
  assert.doesNotMatch(js, /d\.title\.slice\(/)
  assert.equal((js.match(/line\(d\.title, cw - 80\)/g) || []).length, 3)
  assert.match(js, /fillText\(line\(d\.kind === 'shop' \? '店铺'/)
  assert.match(read('components/cy/share-card/index.wxss'), /\.sc-eb \{[^}]*white-space: nowrap; overflow: hidden; text-overflow: ellipsis;/)
})

test('分享层组件运行:封面取色后第一个色块与底色换成主色', () => {
  let config
  global.Component = (c) => { config = c }
  global.getApp = () => ({ globalData: {}, getImgUrl: (u) => u, sendRequest() {} })
  const img = {}
  const ctx = { drawImage() {}, getImageData: () => ({ data: px(Array(4).fill([30, 150, 160])) }) }
  global.wx = { createOffscreenCanvas: () => ({ createImage: () => img, getContext: () => ctx }) }
  const file = path.join(ROOT, 'components/cy/share-card/index.js')
  delete require.cache[require.resolve(file)]
  require(file)
  const inst = Object.assign({}, config.methods, { data: JSON.parse(JSON.stringify(config.data)) })
  inst.setData = (p) => Object.assign(inst.data, p)
  inst.data.show = true // 属性在真组件里并入 data
  config.observers['show, card'].call(inst, true, { kind: 'theme', id: '0', title: 't', cover: 'https://x/c.jpg' })
  assert.equal(img.src, 'https://x/c.jpg')
  img.onload()
  assert.equal(inst.data.colors.length, 4)
  assert.equal(inst.data.bg, inst.data.colors[0])
  assert.notEqual(inst.data.colors[0], '#7A2619')
  // 用户先换了色块:取色回来只换第一个色块,不改底色
  config.observers['show, card'].call(inst, true, { kind: 'theme', id: '0', title: 't', cover: 'https://x/c.jpg' })
  inst.pickColor({ currentTarget: { dataset: { i: 2 } } })
  const picked = inst.data.bg
  img.onload()
  assert.equal(inst.data.bg, picked)
})
