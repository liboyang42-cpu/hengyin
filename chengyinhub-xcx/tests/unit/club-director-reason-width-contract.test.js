'use strict'

// C-04 ROUND4(业主):放行章节的「现场原因」textarea 与上方章节卡同宽。
// 小程序 textarea 原生默认宽 300px,不显式写 width:100% 就会比卡窄一截。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')
const WXSS = fs.readFileSync(path.join(ROOT, 'pages/club/topic-detail/index.wxss'), 'utf8')
const WXML = fs.readFileSync(path.join(ROOT, 'pages/club/topic-detail/index.wxml'), 'utf8')

test('C-04:放行原因 textarea 用 ctd-director-field__input,且该类占满宽度(border-box)', () => {
  assert.match(WXML, /<textarea class="ctd-director-field__input" value="\{\{unlockReason\}\}"/)
  const rule = (WXSS.match(/\.ctd-director-field__input \{([^}]*)\}/) || [])[1] || ''
  assert.match(rule, /width: 100%/)
  assert.match(rule, /box-sizing: border-box/)
})
