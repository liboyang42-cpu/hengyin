// P-14 / g21(2026-11-02 走查修复):店卡写明本店权益、给「出示入场码」、CTA 叫「到店打卡」。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

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

test('D1:玩页主面与站点详情都没有「出示入场码」按钮/浮钮,入场码只走「⋯」工具菜单行', () => {
  assert.doesNotMatch(wxml, /<cy-btn[^>]*bindtap="openEntryQr"/)
  assert.doesNotMatch(wxml, /class="pscan"/)
  const rows = wxml.match(/bindtap="openTicketFromTools"/g) || []
  assert.ok(rows.length >= 1, '「⋯」菜单里仍有入场码行')
  assert.equal(rows.length, 1, 'v2-b:菜单里入场码只留一行(原来 mode2 / 城市定向各一份)')
})

test('v2-b 时刻 2:标题在包上方(主题名 + 本章 N 张),包面只有 城瘾 / 主题名 / N 张,「轻点开启」在包下', () => {
  const head = wxml.slice(wxml.indexOf('<view class="fx-packhead'), wxml.indexOf('<view class="fx-packwrap"'))
  assert.match(head, /<text class="fx-packhead__t">\{\{topicName \|\| chapter\.name \|\| modeLabel\}\}<\/text>/)
  assert.match(head, /本章 \{\{seatTiles\.length\}\} 张/)
  const wrap = wxml.slice(wxml.indexOf('<view class="fx-packwrap"'), wxml.indexOf('<text class="fx-packtap'))
  assert.ok(wrap.length > 100, '找不到卡包块')
  assert.deepEqual(wrap.match(/<text\b[^>]*>/g), ['<text class="fx-pack__t">', '<text class="fx-pack__sub">', '<text class="fx-pack__ct">'])
  assert.match(wxml, /<text class="fx-packtap[^>]*>轻点开启<\/text>/)
  assert.doesNotMatch(wxml, /轻点拆开|fx-pack-hint/)
})

function assertHeroCtaContract(markup) {
  const button = markup.match(/<cy-btn variant="\{\{([^}]+)\}\}" style="width:100%" bindtap="heroEnter"[^>]*>\{\{([^}]+)\}\}<\/cy-btn>/)
  assert.ok(button, '缺店卡主 CTA')
  assert.equal(button[1], "hero.node.entryDisabled ? 'disabled' : 'primary'")
  for (const [node, label, variant] of [
    [{ done: true, entryDisabled: false }, '继续互动', 'primary'],
    [{ done: true, entryDisabled: true }, '已核销', 'disabled'],
    [{ done: false, entryDisabled: false }, '到店打卡', 'primary'],
  ]) {
    assert.equal(vm.runInNewContext(button[2], { hero: { node } }), label)
    assert.equal(vm.runInNewContext(button[1], { hero: { node } }), variant)
  }
}

test('CTA 按 entryDisabled 展示已核销,未完成玩法可继续互动,未核销到店打卡', () => {
  assertHeroCtaContract(wxml)
})

test('mutation 负控:旧核销文案或 done 禁用条件必须变红', () => {
  for (const mutated of [
    wxml.replace("hero.node.entryDisabled ? '已核销' : (hero.node.done ? '继续互动' : '到店打卡')", "hero.node.done ? '已核销' : '到店打卡'"),
    wxml.replace("hero.node.entryDisabled ? 'disabled' : 'primary'", "hero.node.done ? 'disabled' : 'primary'"),
  ]) assert.throws(() => assertHeroCtaContract(mutated), assert.AssertionError)
})
