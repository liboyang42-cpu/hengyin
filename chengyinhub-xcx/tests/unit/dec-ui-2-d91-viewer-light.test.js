// D-91(业主 2026-11-11「浅色」):商家从发现进玩家公开主页 ⇒ 玩家主页按商家浅色渲染(主题跟随观看者)。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const dir = path.join(__dirname, '../../subpackageMember/components/cy/profile')
const js = fs.readFileSync(path.join(dir, 'index.js'), 'utf8')
const wxml = fs.readFileSync(path.join(dir, 'index.wxml'), 'utf8')

test('D-91 viewerLight 只在他人宿主、非本人、观看者是商家时为真,且不改 isMerchantView', () => {
  assert.match(js, /var light = !d\.hostSelf && !d\.isSelf && !!this\._viewerIsMerchant;/)
  assert.match(js, /applyTheme\(light \|\| d\.subjectIsMerchant \? 'light' : 'dark'\)/)
  assert.doesNotMatch(js, /isMerchantView:\s*!!this\._viewerIsMerchant/)
})

test('D-91 根节点与浮层都按 viewerLight 挂 theme-merchant', () => {
  assert.match(wxml, /\(isMerchantView \|\| viewerLight\) \? 'pc-merchant theme-merchant' : 'pc-player'/)
  assert.equal((wxml.match(/\(isMerchantView \|\| viewerLight\) \? 'theme-merchant'/g) || []).length, 4)
})

test('D-91 离开页面还原深色', () => {
  assert.match(js, /subjectIsMerchant \|\| this\.data\.viewerLight\)\) applyTheme\('dark'\)/)
})
