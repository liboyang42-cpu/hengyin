// D-81(业主 2026-11-11「同步写回主题设置」):发布预览里切风格 / 配色走编辑器同一条 formData.look 写回路径。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const dir = path.join(__dirname, '../../pages/publish/fabu')
const js = fs.readFileSync(path.join(dir, 'index.js'), 'utf8')
const wxml = fs.readFileSync(path.join(dir, 'index.wxml'), 'utf8')

test('D-81 预览的风格/配色处理器复用 onLookStyle/onLookPalette 并刷新预览', () => {
  assert.match(js, /onPreviewLookStyle\(e\) \{ this\.onLookStyle\(e\); this\._refreshStoryPreview\(\); \}/)
  assert.match(js, /onPreviewLookPalette\(e\) \{ this\.onLookPalette\(e\); this\._refreshStoryPreview\(\); \}/)
  assert.match(js, /lookStyles: look\.styles, lookPalettes: look\.palettes/)
})

test('D-81 预览 wxml 绑定新处理器且在 sp-look 点击区之外', () => {
  assert.match(wxml, /bindtap="onPreviewLookStyle"/)
  assert.match(wxml, /bindtap="onPreviewLookPalette"/)
  const lookBlock = wxml.slice(wxml.indexOf('<view class="sp-look"'), wxml.indexOf('<view class="look-styles">\n          <view wx:for="{{publishCheck.preview.lookStyles}}"'))
  assert.doesNotMatch(lookBlock, /onPreviewLookStyle/)
})

test('D-81 写回走 _patchLook → formData.look(草稿自动保存同路径)', () => {
  const ed = fs.readFileSync(path.join(dir, 'theme-look-editor.js'), 'utf8')
  assert.match(ed, /'formData\.look': raw/)
})
