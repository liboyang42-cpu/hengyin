'use strict'
// x-design1 D11:票务设置的日期/时间/地点行——没有说明文字,右侧显示值或「未设置」,没有「选择 >」药丸
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const read = (p) => fs.readFileSync(path.resolve(__dirname, '../..', p), 'utf8')
const wxml = read('pages/publish/fabu/step3.wxml').replace(/<!--[\s\S]*?-->/g, '')

test('D11:日期/时间/地点行没有「选择…」说明字,也没有「选择」药丸', () => {
  assert.doesNotMatch(wxml, /'选择开始日期'|'选择结束日期'|'选择日期'|'选择开始时间'|'选择结束时间'|'请选择地点'/)
  assert.doesNotMatch(wxml, /<view class="node-pill"><text>(选择|\{\{editingTicket\.meetingPoint \? '修改节点' : '选择节点'\}\})<\/text>/)
})

test('D11:十行都改成右侧值,空时「未设置」', () => {
  const rows = wxml.match(/<text class="node-card__rv [^>]*>[^<]*<\/text><cy-icon name="chevron-right"/g) || []
  assert.equal(rows.length, 10)
  for (const r of rows) assert.match(r, /未设置/)
})

test('D11:右侧值样式用 token,空值更淡', () => {
  const css = read('pages/publish/fabu/index.wxss')
  assert.match(css, /\.node-card__rv \{[^}]*margin-left: auto;[^}]*font-size: var\(--cy-font-body\)/)
  assert.match(css, /\.node-card__rv\.is-empty \{ color: var\(--cy-color-text-tertiary\); \}/)
})
