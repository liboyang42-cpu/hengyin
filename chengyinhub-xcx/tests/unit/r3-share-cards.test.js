'use strict'
// r3-share(refs4/share-page-cards):通用分享层与结算分享卡改成整色卡 —— 上半封面 / 插画、弧形下沿、徽标、两行大标题、一颗主按钮。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ROOT = path.join(__dirname, '../..')
const read = (f) => fs.readFileSync(path.join(ROOT, f), 'utf8')
const { fitRows } = require('../../utils/share-lines.js')

const px = (s) => Array.from(s).length * 10

test('标题折行:中文逐字、数字字母整词换行,最多两行,放不下加省略号', () => {
  assert.deepEqual(fitRows('城市定向样板 1110095134', px, 120), ['城市定向样板', '1110095134'])
  assert.deepEqual(fitRows('外滩夜行者', px, 120), ['外滩夜行者'])
  const long = fitRows('一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十', px, 100)
  assert.equal(long.length, 2)
  assert.ok(long[1].endsWith('…'))
  assert.ok(px(long[1]) <= 100)
  // 一个词比整行还宽时逐字拆,不溢出
  fitRows('Supercalifragilistic', px, 80).forEach((r) => assert.ok(px(r) <= 80, r))
  assert.deepEqual(fitRows('', px, 100), [])
})

function loadComponent() {
  let config
  global.Component = (c) => { config = c }
  global.getApp = () => ({ globalData: {}, getImgUrl: (u) => u, sendRequest() {} })
  global.wx = {}
  const file = path.join(ROOT, 'components/cy/share-card/index.js')
  delete require.cache[require.resolve(file)]
  require(file)
  const inst = Object.assign({}, config.methods, { data: JSON.parse(JSON.stringify(config.data)) })
  inst.setData = (p) => Object.assign(inst.data, p)
  inst.data.show = true
  return { config, inst }
}

test('分享层:眉题与副信息按内容类型给,没有的行不出', () => {
  const { config, inst } = loadComponent()
  const open = (card) => { config.observers['show, card'].call(inst, true, card); return { eyebrow: inst.data.eyebrow, sub: inst.data.sub } }
  assert.deepEqual(open({ kind: 'theme', id: '1', title: 't', meta: '1章', stops: ['外滩', '', '豫园'] }), { eyebrow: '主题 · 1章', sub: '外滩 · 豫园' })
  assert.deepEqual(open({ kind: 'theme', id: '1', title: 't' }), { eyebrow: '主题', sub: '' })
  assert.deepEqual(open({ kind: 'activity', id: '2', title: 't', date: '11.12', when: '周六 14:00' }), { eyebrow: '活动', sub: '11.12 · 周六 14:00' })
  assert.deepEqual(open({ kind: 'medal', title: '外滩夜行者', meta: '' }), { eyebrow: '勋章', sub: '' })
  assert.deepEqual(open({ kind: 'shop', id: '3', title: '店', when: '黄浦区' }), { eyebrow: '店铺', sub: '黄浦区' })
})

test('分享层版式:整色卡 + 封面 / 插画 + 弧 + 徽标 + 两行标题;一颗主按钮,其余渠道一排小圆钮', () => {
  const wxml = read('components/cy/share-card/index.wxml')
  const wxss = read('components/cy/share-card/index.wxss')
  assert.match(wxml, /<view class="sc-arc"><\/view>/)
  assert.match(wxml, /class="sc-badge"><image class="sc-badge-img" src="\/images\/cy_logo_mark\.png"/)
  assert.match(wxml, /wx:else src="\/components\/cy\/share-card\/route-art\.png"/)
  assert.ok(fs.existsSync(path.join(ROOT, 'components/cy/share-card/route-art.png')))
  // 主按钮只有一颗,仍是 open-type=share(宿主页 onShareAppMessage 用 data-share-kind 区分)
  assert.equal((wxml.match(/open-type="share"/g) || []).length, 1)
  assert.match(wxml, /<button class="sc-primary" open-type="share" data-share-kind="card" aria-label="发给微信好友"/)
  assert.match(wxss, /\.sc-big \{[^}]*-webkit-line-clamp: 2;/)
  assert.match(wxss, /\.sc-primary \{[^}]*border-radius: var\(--cy-comp-btn-radius\)/)
  // 动效只用 transform / opacity,并尊重减弱动态
  assert.match(wxss, /@media \(prefers-reduced-motion: reduce\) \{ \.sc-card, \.sc-badge, \.sc-in \{ animation: none; \}/)
  // 导出图与预览同一椭圆弧与徽标
  const js = read('components/cy/share-card/index.js')
  assert.match(js, /g\.ellipse\(mx, 905, 600, 365, 0, 0, Math\.PI \* 2\)/)
  assert.match(js, /if \(logo\) g\.drawImage\(logo,/)
})

test('结算分享卡:同一版式,只画服务端事实;徽标缺失时照样出卡;动画帧 t 从 0 到 1', () => {
  const card = require('../../subpackageJourney/utils/share-card.js')
  const calls = []
  const ctx = new Proxy({ measureText: (s) => ({ width: String(s).length * 20 }), createLinearGradient: () => ({ addColorStop() {} }) }, {
    get(t, k) { if (k in t) return t[k]; return (...a) => { calls.push([k, a]) } },
    set(t, k, v) { t[k] = v; return true },
  })
  const lines = card.cardLines({ win: true, nodesText: '2/2', scored: true, scoreText: '100', rankSub: 'x', rankText: '第 1 名' }, '城市定向样板 1110095134')
  card.draw(ctx, 360, 450, lines, 1, null)
  const texts = calls.filter(([k]) => k === 'fillText').map(([, a]) => a[0])
  assert.deepEqual(texts, ['已通关', '城市定向样板', '1110095134', '节点 2/2 · 100 分 · 第 1 名', '城瘾'])
  assert.ok(calls.some(([k]) => k === 'ellipse'))
  assert.ok(!calls.some(([k]) => k === 'drawImage'), '没有徽标图时不画图')
  calls.length = 0
  card.draw(ctx, 270, 480, lines, 0, { width: 1 })
  assert.ok(calls.some(([k]) => k === 'drawImage'))
  assert.equal(card.LOGO, '/images/cy_logo_mark.png')
  const page = read('subpackageJourney/pages/settle/index.js')
  assert.match(page, /shareCard\.draw\(ctx, size\.w, size\.h, this\._card\(\), 1, logo\);/)
  assert.match(page, /shareCard\.draw\(ctx, size\.w, size\.h, card, Math\.min\(1, frame \/ 45\), this\._logo\);/)
})
