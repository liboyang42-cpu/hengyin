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
  assert.ok(rows.length >= 2, '「⋯」菜单里仍有入场码行')
})

test('g21 第三轮 + x-fix7 P-6:卡包只印主题名一行,没有章数副标和「轻点拆开」', () => {
  const wrap = wxml.slice(wxml.indexOf('<view class="fx-packwrap"'), wxml.indexOf('<view class="fx-shard"'))
  assert.ok(wrap.length > 100, '找不到卡包块')
  const texts = wrap.match(/<text\b[^>]*>/g) || []
  assert.deepEqual(texts, ['<text class="fx-pack__t">'], '卡包块里只许有主题名这一个文字节点')
  assert.match(wrap, /<text class="fx-pack__t">\{\{topicName \|\| chapter\.name \|\| modeLabel\}\}<\/text>/)
  assert.doesNotMatch(wxml, /轻点拆开|fx-pack__s\b|fx-pack-hint/)
  assert.doesNotMatch(wxss, /\.fx-pack__s\b|\.fx-pack-hint/)
  assert.match(wxss, /\.fx-pack__t\{[^}]*color:#0A0A0A/, '膜是浅色的,主题名必须黑字')
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
