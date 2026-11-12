'use strict'
// x-design1 D1 / D3(设计评审 2026-11-12)
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ROOT = path.resolve(__dirname, '../..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')
const walk = (dir, out = []) => {
  for (const n of fs.readdirSync(path.join(ROOT, dir), { withFileTypes: true })) {
    if (n.name === 'node_modules' || n.name === 'tests') continue
    const rel = path.posix.join(dir, n.name)
    if (n.isDirectory()) walk(rel, out)
    else if (rel.endsWith('.wxml')) out.push(rel)
  }
  return out
}

test('D1:入场码半屏是普通半屏里一个 400rpx 码,没有翻面票卡与 600rpx 码块', () => {
  const xml = read('pages/play/components/scene-qr-ticket/index.wxml')
  const css = read('pages/play/components/scene-qr-ticket/index.wxss')
  assert.doesNotMatch(xml, /voucher__flip|voucher__rear|toggleFlip/)
  assert.doesNotMatch(css, /600rpx|voucher__flip/)
  assert.match(css, /\.voucher__qr \{[^}]*width: 400rpx; height: 400rpx;/)
})

test('D1:玩页主面没有常驻扫码浮钮,入场码只走「⋯」菜单行', () => {
  const wxml = read('pages/play/index.wxml')
  assert.doesNotMatch(wxml, /class="pscan"/)
  assert.doesNotMatch(read('pages/play/index.wxss'), /\.pscan\s*\{/)
  assert.doesNotMatch(wxml, /<cy-btn[^>]*bindtap="openEntryQr"/)
  assert.ok((wxml.match(/bindtap="openTicketFromTools"/g) || []).length >= 2)
})

test('D3:全仓任何页面都不再把「封面暂不可用」写给用户(作者端原封面上传提示除外)', () => {
  const hits = walk('.').concat([]).filter((f) => /(?<!原)封面暂不可用/.test(read(f).replace(/<!--[\s\S]*?-->/g, '')))
  assert.deepEqual(hits, [])
})

test('D3:兜底是 look 渐变 + 小路线插画,宿主可注入 --cover-a/--cover-b', () => {
  const css = read('style/components.wxss')
  assert.match(css, /\.cy-cover-fb \{[^}]*linear-gradient\(160deg, var\(--cover-a, .*var\(--cover-b,/)
  assert.match(css, /\.cy-cover-fb__art \{[^}]*width: 120rpx/)
  assert.ok(fs.existsSync(path.join(ROOT, 'images/cover-route.svg')))
  for (const f of ['pages/topic/merchantinfo/merchantinfo.wxml', 'pages/club/topic-detail/index.wxml', 'pages/activity/list/index.wxml', 'subpackageB/pages/im/chat/index.wxml', 'pages/templatedetail/topic-preview/index.wxml']) {
    assert.match(read(f), /cy-cover-fb/, f)
  }
  assert.match(read('pages/templatedetail/shelf/index.wxss'), /\.cv-err \{[^}]*var\(--cover-a/)
})
