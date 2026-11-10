// l-editor(2026-11-10):发布分包里的 --lk-* 外观变量是 pages/play/look-tokens.wxss 的逐字副本
// (分包之间不能互相 require,弹窗预览要在编辑器里用同一套变量)。两份文件必须一致,改一处就要同步另一处。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

test('pages/publish/fabu/look-tokens.wxss 与 pages/play/look-tokens.wxss 逐字相同', () => {
  const play = fs.readFileSync(path.join(__dirname, '../../pages/play/look-tokens.wxss'), 'utf8')
  const publish = fs.readFileSync(path.join(__dirname, '../../pages/publish/fabu/look-tokens.wxss'), 'utf8')
  assert.equal(publish, play)
})

test('故事编辑器样式引入了这份副本,弹窗预览读 --lk-*', () => {
  const wxss = fs.readFileSync(path.join(__dirname, '../../pages/publish/fabu/index.wxss'), 'utf8')
  assert.match(wxss, /@import "\.\/look-tokens\.wxss"/)
  assert.match(wxss, /\.pvl \{[^}]*var\(--lk-bg\)/)
})
