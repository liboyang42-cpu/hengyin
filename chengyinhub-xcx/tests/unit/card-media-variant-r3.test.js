'use strict'

// 打磨第 3 轮 §4-7:cy-card 封面媒体变体(2xl),只用于活动列表封面卡。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.join(__dirname, '../..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')

test('全局与组件都有 cy-card--media,圆角是 --cy-radius-2xl', () => {
  assert.match(read('style/components.wxss'), /\.cy-card--media \{ border-radius: var\(--cy-radius-2xl\); \}/)
  assert.match(read('components/cy/card/index.wxss'), /\.cy-card--media \{ border-radius: var\(--cy-radius-2xl\); \}/)
  assert.match(read('components/cy/card/index.wxml'), /\{\{media \? 'cy-card--media' : ''\}\}/)
  assert.match(read('components/cy/card/index.js'), /media: \{ type: Boolean, value: false \}/)
})

test('活动列表封面卡用 media 变体,其它页面没有被顺手改', () => {
  assert.match(read('pages/activity/list/index.wxml'), /class="cy-card cy-card--media oe-card"/)
  const users = []
  ;(function walk(dir) {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      if (['node_modules', 'miniprogram_npm', 'tests', 'docs', 'scripts', '.git'].includes(e.name)) continue
      const full = path.join(dir, e.name)
      if (e.isDirectory()) walk(full)
      else if (e.name.endsWith('.wxml') && /cy-card--media|<cy-card[^>]*\smedia(?:\s|=|\/?>)/.test(fs.readFileSync(full, 'utf8'))) users.push(path.relative(ROOT, full))
    }
  })(ROOT)
  assert.deepEqual(users.sort(), ['components/cy/card/index.wxml', 'pages/activity/list/index.wxml'])
})
