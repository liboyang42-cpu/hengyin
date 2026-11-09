'use strict'

// 打磨第 3 轮:发布 temp 卡片页边/圆角回到全站(--cy-page-x / --cy-radius-lg)。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.join(__dirname, '../..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')

test('publish/temp:卡片与内嵌配置块用页边与 lg 圆角,不再是 26rpx / 28rpx', () => {
  const wxss = read('pages/publish/temp/index.wxss')
  const card = wxss.match(/^\.cg-card \{ margin-left:[^\n]*$/m)[0]
  assert.match(card, /margin-left: var\(--cy-page-x\); margin-right: var\(--cy-page-x\)/)
  assert.match(card, /--cy-comp-card-radius: var\(--cy-radius-lg\)/)
  const inline = wxss.match(/^\.cg-inline-cfg > \.cg-gcfg, \.cg-inline-cfg > \.cg-cfg \{ margin-left[^\n]*$/m)[0]
  assert.match(inline, /margin-left: var\(--cy-page-x\)/)
  assert.match(inline, /border-radius: var\(--cy-radius-lg\)/)
  assert.match(wxss, /^\.cg-addmod \{ margin: 44rpx var\(--cy-page-x\) 0; \}/m)
})
