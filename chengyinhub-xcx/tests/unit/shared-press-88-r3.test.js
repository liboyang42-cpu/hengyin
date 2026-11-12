'use strict'

// 打磨第 3 轮 §4-12:头像 .av--press 与错误态重试 .cy-error-retry--press 的按压减淡统一为 .88(cy-pressed 同值)。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const read = (p) => fs.readFileSync(path.join(__dirname, '../..', p), 'utf8')

test('avatar 与 error retry 按压 .88', () => {
  assert.match(read('components/cy/avatar/index.wxss'), /\.av--press \{ opacity: \.88; \}/)
  assert.match(read('components/cy/error/index.wxss'), /\.cy-error-retry--press \{ opacity: \.92; transform: scale\(\.97\); transition-duration: var\(--cy-motion-press-in\); \}/)
})
