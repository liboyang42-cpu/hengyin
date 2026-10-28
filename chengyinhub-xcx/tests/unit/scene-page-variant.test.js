'use strict'
// G-1(业主 2026-10-27):本该是页面的内容不再做成弹窗 —— cy-scene-sheet 加 page 档,
// 订单详情 / 优惠券 / 邀请好友 / 漫游历史切到 page;营销消息与优惠券改成设置里点进去的独立页面。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const { normalizeScene } = require('../../utils/scene-stack.js')
const { getScene } = require('../../utils/scene-registry.js')

test('scene-stack 放行 page 档,未知值仍 fail-closed 到 full', () => {
  assert.equal(normalizeScene({ id: 'x', variant: 'page' }).variant, 'page')
  assert.equal(normalizeScene({ id: 'x', variant: 'drw' }).variant, 'full')
})

test('业主点名的四个场景是 page 档(P-51 / P-58 / P-59 / P-37)', () => {
  for (const id of ['member-order-detail', 'game-coupon-wallet', 'share-invite', 'roam-history']) {
    assert.equal(getScene(id).variant, 'page', id)
  }
})

test('page 档:没有遮罩和把手,普通导航栏 + 大标题,导航返回与物理返回同一出口', () => {
  const wxml = read('components/cy/scene-sheet/index.wxml')
  assert.match(wxml, /<view wx:if="\{\{variant !== 'page'\}\}" class="ss__mask"/)
  // 2026-10-28 p92:bare 档(订单详情 Figma 578:2298)= 封面头铺到顶,overlay 导航、无页标题;其余 page 档不变
  assert.match(wxml, /<block wx:if="\{\{variant === 'page'\}\}">\s*<cy-nav-bar wx:if="\{\{bare\}\}" overlay tint="light" custom-back bind:back="onPageBack" \/>\s*<cy-nav-bar wx:else plain custom-back bind:back="onPageBack" \/>\s*<cy-page-title wx:if="\{\{!bare\}\}" title="\{\{title\}\}" \/>/)
  assert.match(wxml, /<view class="ss__head" wx:else>/)
  const wxss = read('components/cy/scene-sheet/index.wxss')
  assert.match(wxss, /\.ss--page \.ss__panel \{[^}]*height: 100%;[^}]*border-radius: 0;[^}]*transform: none;/)
  const json = JSON.parse(read('components/cy/scene-sheet/index.json'))
  assert.equal(json.usingComponents['cy-nav-bar'], '/components/cy/nav-bar/index')
  assert.equal(json.usingComponents['cy-page-title'], '/components/cy/page-title/index')
})

test('roam / play 的 psheet 宿主遇到 page 档直接进深链页,不在地图上压弹层', () => {
  for (const file of ['pages/roam/index.js', 'pages/play/index.js']) {
    const source = read(file)
    assert.match(source, /const \{ openScene: openPageScene \} = require\('\.\.\/\.\.\/utils\/scene-entry\.js'\)/, file)
    assert.match(source, /openScene\(id, params = \{\}\) \{\n\s*const next = getScene\(id, params\);\n[^\n]*\n\s*if \(next\.variant === 'page' && next\.route\) \{ openPageScene\(id, params\); return true; \}/, file)
  }
})

test('P-55:设置里的营销消息与优惠券是独立页面,不再挂弹层', () => {
  const settings = read('pages/shezhi/shezhi.wxml')
  assert.doesNotMatch(settings, /<cy-marketing-consent\b/)
  assert.match(settings, /id="settings-marketing-consent"[^>]*\n?[^>]*bind:tap="openMarketingConsent"/)
  assert.match(read('pages/shezhi/shezhi.js'), /openMarketingConsent\(\) \{[\s\S]*?wx\.navigateTo\(\{ url: '\/pages\/shezhi\/marketing\/index'/)
  const app = JSON.parse(read('app.json'))
  const pkg = app.subPackages.find((item) => item.root === 'pages/shezhi')
  assert.ok(pkg.pages.includes('marketing/index'))
  const page = read('pages/shezhi/marketing/index.wxml')
  assert.match(page, /<cy-page-title title="营销消息与优惠券"/)
  assert.match(page, /<cy-marketing-consent show="\{\{ready\}\}" layout="page" \/>/)
  assert.match(read('pages/shezhi/components/marketing-consent/index.wxml'), /class="mc mc--\{\{layout\}\}"/)
})
