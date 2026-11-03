'use strict'

// ROUND5C l6-club(业主 2026-11-03):俱乐部主办深色 P-6「招商」层里,汇总数字、章节名、站点名
// 渲染成深字压深底(shots/MERCHANT-r4-after.jpg)。
// 原因:project-drawer 的 wxss 首行 @import merchant-light.wxss,其 page{} 在组件里落到抽屉宿主上,
// 把 --cy-text-title / --cy-text-secondary 等别名按浅色重新求值,盖掉了外层 .theme-dark 的值;
// 底色别名没被重声明,仍是深色 —— 于是深字压深底。
// 修法:俱乐部主办时给「招商」抽屉宿主挂现成的 .theme-dark,用现有深色 token 重新求值。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')
const HOST_WXML = read('pages/topic/components/project-host/index.wxml')
const HOST_WXSS = read('pages/topic/components/project-host/index.wxss')
const DRAWER_WXSS = read('pages/topic/components/project-drawer/index.wxss')
const MERCHANT_LIGHT = read('style/merchant-light.wxss').replace(/\/\*[\s\S]*?\*\//g, '')
const TOKENS = read('style/tokens.wxss').replace(/\/\*[\s\S]*?\*\//g, '')

const declared = (body) => new Set([...body.matchAll(/(--cy-[\w-]+)\s*:/g)].map((m) => m[1]))
const lightPageBlock = () => {
  const i = MERCHANT_LIGHT.indexOf('page {')
  return MERCHANT_LIGHT.slice(i, MERCHANT_LIGHT.indexOf('\n}', i))
}
const darkBlock = () => {
  const m = TOKENS.match(/page\.theme-dark,\s*\.theme-dark \{([\s\S]*?)\n\}/)
  assert.ok(m, 'tokens.wxss 仍有 page.theme-dark, .theme-dark 块')
  return m[1]
}
const merchantDrawerTag = () => {
  const m = HOST_WXML.match(/<project-drawer\b[^>]*title="招商"[^>]*>/)
  assert.ok(m, '招商抽屉仍在 project-host 里')
  return m[0]
}

test('前提:project-drawer 引入 merchant-light,其 page{} 会把文字别名按浅色重声明', () => {
  assert.match(DRAWER_WXSS, /^@import '\/style\/merchant-light\.wxss';/)
  const light = declared(lightPageBlock())
  for (const v of ['--cy-text-title', '--cy-text-secondary', '--cy-color-text-primary']) assert.ok(light.has(v), v)
})

test('俱乐部主办时,「招商」抽屉宿主挂现成的 theme-dark;商家主办不挂', () => {
  const tag = merchantDrawerTag()
  assert.match(tag, /class="merchant-drawer \{\{ hostOwnerType === 'club' \? 'theme-dark' : '' \}\}"/)
  // 页面根节点同一条件
  assert.match(HOST_WXML, /<view class="page \{\{ hostOwnerType === 'club' \? 'theme-dark' : 'theme-merchant' \}\}">/)
})

test('招商层用到的颜色别名,凡被 merchant-light 浅色重声明的,.theme-dark 都给了深色值', () => {
  const s = HOST_WXML.indexOf(merchantDrawerTag())
  const e = HOST_WXML.indexOf('</project-drawer>', s)
  const classes = new Set()
  for (const m of HOST_WXML.slice(s, e).matchAll(/class="([^"]*)"/g)) {
    m[1].replace(/\{\{[\s\S]*?\}\}/g, ' ').split(/\s+/).filter(Boolean).forEach((c) => classes.add(c))
  }
  const used = new Set()
  for (const m of HOST_WXSS.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/([^{}]*)\{([^{}]*)\}/g)) {
    const sel = [...m[1].matchAll(/\.([\w-]+)/g)].map((x) => x[1])
    if (sel.some((c) => classes.has(c))) for (const v of m[2].matchAll(/var\((--cy-[\w-]+)/g)) used.add(v[1])
  }
  for (const v of ['--cy-text-title', '--cy-text-secondary']) assert.ok(used.has(v), `招商层用到 ${v}`)
  const light = declared(lightPageBlock())
  const dark = declared(darkBlock())
  const colour = /color|text|bg|status|border|btn-solid/
  const missing = [...used].filter((v) => light.has(v) && colour.test(v) && !dark.has(v))
  assert.deepEqual(missing, [])
  // 深色块里的文字主色是浅字
  assert.match(darkBlock(), /--cy-color-text-primary:\s*#F8F8F8;/)
  assert.match(darkBlock(), /--cy-text-title:\s*var\(--cy-color-text-primary\);/)
})
