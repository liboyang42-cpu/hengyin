// P-14 / g21(2026-11-02 走查修复):店卡写明本店权益、给「出示入场码」、卡包下写「轻点拆开」、CTA 叫「到店打卡」。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const wxml = fs.readFileSync(path.join(ROOT, 'pages/play/index.wxml'), 'utf8')
const wxss = fs.readFileSync(path.join(ROOT, 'pages/play/index.wxss'), 'utf8')

test('本店权益紧跟店铺,排在玩法与到店三步之前', () => {
  const shop = wxml.indexOf('<!-- ② 店铺')
  const perk = wxml.indexOf('<!-- ⑤ 本店权益')
  const game = wxml.indexOf('<!-- ③ 游戏')
  const steps = wxml.indexOf('<!-- ④ 到店三步')
  assert.ok(shop > 0 && perk > shop && perk < game && perk < steps, '权益块应在店铺之后、玩法与三步之前')
  assert.match(wxml, /fx-perk__n">\{\{hero\.node\.perkName\}\}/)
})

test('到店三步下有「出示入场码」,只给有票、未核销的商家站,走现成的入场码', () => {
  const m = wxml.match(/<cy-btn[^>]*wx:if="\{\{([^}]*)\}\}"[^>]*\n?[^>]*bindtap="openEntryQr"[^>]*>出示入场码<\/cy-btn>/)
  assert.ok(m, '缺「出示入场码」按钮')
  for (const cond of ['hero.node.isMerchant', '!hero.node.done', 'regId', '!isPreview', '!isMock']) assert.ok(m[1].includes(cond), cond)
})

test('卡包下写「轻点拆开」,撕开时退场,不挡点击', () => {
  assert.match(wxml, /<text class="fx-pack-hint" wx:if="\{\{!packTear && !packBurst[^"]*\}\}">轻点拆开<\/text>/)
  assert.match(wxss, /\.fx-pack-hint\{[^}]*pointer-events:none/)
})

test('CTA 是「到店打卡」', () => {
  assert.match(wxml, /\{\{hero\.node\.done \? '已核销' : '到店打卡'\}\}/)
})
